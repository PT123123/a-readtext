package com.example.areadtext.utils

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Decodes an arbitrary audio file (wav / mp3 / m4a / amr / ogg / aac ...) to a
 * 16 kHz mono float array usable by the ASR engine. WAV/PCM files take a
 * header-parse fast path; everything else is decoded through
 * MediaExtractor + MediaCodec and downmixed/resampled to 16 kHz mono.
 */
object AudioFileDecoder {

    private const val TAG = "AudioFileDecoder"
    const val TARGET_RATE = 16000

    private const val ENC_16BIT = AudioFormat.ENCODING_PCM_16BIT
    private const val ENC_8BIT = AudioFormat.ENCODING_PCM_8BIT
    private const val ENC_FLOAT = AudioFormat.ENCODING_PCM_FLOAT
    private const val ENC_24BIT = AudioFormat.ENCODING_PCM_24BIT_PACKED

    data class PcmSource(
        val file: File,
        val offset: Long,
        val length: Long,
        val encoding: Int,
        val channels: Int,
        val sampleRate: Int
    )

    /** Returns mono 16 kHz samples in [-1, 1], or null when the file cannot be decoded. */
    fun decodeToPcm16kMono(context: Context, path: String): FloatArray? {
        val f = File(path)
        if (!f.exists() || f.length() <= 0L) return null
        val wav = tryParseWav(f)
        if (wav != null) return convertPcm16kMono(wav)
        return decodeWithMediaCodec(context, f)
    }

    // ---------- WAV fast path ----------

    private fun tryParseWav(f: File): PcmSource? {
        try {
            RandomAccessFile(f, "r").use { raf ->
                if (raf.length() < 12) return null
                val hdr = ByteArray(12)
                raf.readFully(hdr)
                if (!(hdr[0] == 'R'.code.toByte() && hdr[1] == 'I'.code.toByte() &&
                        hdr[2] == 'F'.code.toByte() && hdr[3] == 'F'.code.toByte())) return null
                if (!(hdr[8] == 'W'.code.toByte() && hdr[9] == 'A'.code.toByte() &&
                        hdr[10] == 'V'.code.toByte() && hdr[11] == 'E'.code.toByte())) return null

                var fmt = -1
                var channels = -1
                var sampleRate = -1
                var bits = -1
                var dataOffset = -1L
                var dataLen = -1L

                while (raf.filePointer + 8 <= raf.length()) {
                    val c = ByteArray(8)
                    raf.readFully(c)
                    val id = String(c, 0, 4, Charsets.US_ASCII)
                    val size = leInt(c, 4)
                    when (id) {
                        "fmt " -> {
                            val b = ByteArray(size.coerceAtMost(64))
                            raf.readFully(b)
                            fmt = leShort(b, 0)
                            channels = leShort(b, 2)
                            sampleRate = leInt(b, 4)
                            bits = leShort(b, 14)
                            if (size > b.size) skipFully(raf, size.toLong() - b.size)
                        }
                        "data" -> {
                            dataOffset = raf.filePointer
                            dataLen = size.toLong()
                            break
                        }
                        else -> skipFully(raf, size.toLong())
                    }
                    // chunks are word-aligned: a pad byte follows odd-sized chunks
                    if (id != "data" && (size % 2) == 1) skipFully(raf, 1)
                }

                if (sampleRate <= 0 || channels <= 0 || dataOffset < 0 || dataLen <= 0) return null
                val encoding = when {
                    fmt == 3 && bits == 32 -> ENC_FLOAT
                    fmt == 1 && bits == 8 -> ENC_8BIT
                    fmt == 1 && bits == 16 -> ENC_16BIT
                    fmt == 1 && bits == 24 -> ENC_24BIT
                    else -> return null
                }
                return PcmSource(f, dataOffset, dataLen, encoding, channels, sampleRate)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wav parse failed: ${e.message}")
            return null
        }
    }

    // ---------- MediaExtractor / MediaCodec path ----------

    private fun decodeWithMediaCodec(context: Context, f: File): FloatArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(f.absolutePath)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val mf = extractor.getTrackFormat(i)
                if (mf.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    fmt = mf
                    break
                }
            }
            if (track < 0 || fmt == null) return null
            extractor.selectTrack(track)

            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcm = if (Build.VERSION.SDK_INT >= 24 && fmt.containsKey(MediaFormat.KEY_PCM_ENCODING))
                fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else ENC_16BIT

            val codec = MediaCodec.createDecoderByType(mime)
            val tmp = File(context.cacheDir, "import_decode_${System.currentTimeMillis()}.pcm")
            try {
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputEos = false
                var outputEos = false

                FileOutputStream(tmp).use { fos ->
                    while (!outputEos) {
                        if (!inputEos) {
                            val inIdx = codec.dequeueInputBuffer(10_000)
                            if (inIdx >= 0) {
                                val inBuf = codec.getInputBuffer(inIdx)
                                if (inBuf == null) continue
                                val n = extractor.readSampleData(inBuf, 0)
                                if (n < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputEos = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                        when {
                            outIdx >= 0 -> {
                                if (info.size > 0) {
                                    val outBuf = codec.getOutputBuffer(outIdx)
                                    if (outBuf != null) {
                                        outBuf.position(info.offset)
                                        outBuf.limit(info.offset + info.size)
                                        val bytes = ByteArray(info.size)
                                        outBuf.get(bytes)
                                        fos.write(bytes)
                                    }
                                }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                                codec.releaseOutputBuffer(outIdx, false)
                            }
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val of = codec.outputFormat
                                if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                if (Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                    pcm = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                    }
                }
                codec.stop()
                if (tmp.length() <= 0L) return null
                return convertPcm16kMono(PcmSource(tmp, 0, tmp.length(), pcm, channels, sampleRate))
            } finally {
                try { codec.release() } catch (_: Exception) {}
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "media decode failed: ${e.message}")
            return null
        } finally {
            extractor.release()
        }
    }

    // ---------- PCM -> mono 16 kHz ----------

    private fun convertPcm16kMono(src: PcmSource): FloatArray {
        val bytesPerSample = when (src.encoding) {
            ENC_24BIT -> 3
            ENC_FLOAT -> 4
            ENC_8BIT -> 1
            else -> 2
        }
        val frameBytes = bytesPerSample * src.channels
        if (frameBytes <= 0 || src.sampleRate <= 0) return FloatArray(0)
        val totalFrames = src.length / frameBytes
        val outSamples = (totalFrames * TARGET_RATE.toDouble() / src.sampleRate).toInt().coerceAtLeast(0)
        val out = FloatArray(outSamples)
        if (outSamples == 0) return out

        val step = src.sampleRate.toDouble() / TARGET_RATE
        var t = 0.0
        var last = 0f
        var haveLast = false
        var outPos = 0

        val bis = BufferedInputStream(FileInputStream(src.file), 256 * 1024)
        try {
            skipFully(bis, src.offset)
            val frame = ByteArray(frameBytes)
            var i = 0L
            while (i < totalFrames && outPos < outSamples) {
                var read = 0
                while (read < frameBytes) {
                    val n = bis.read(frame, read, frameBytes - read)
                    if (n < 0) break
                    read += n
                }
                if (read < frameBytes) break
                val mono = monoSample(frame, src.encoding, src.channels)
                if (haveLast) {
                    while (t <= i.toDouble() && outPos < outSamples) {
                        val frac = (t - (i - 1)).toFloat()
                        out[outPos++] = last + (mono - last) * frac
                        t += step
                    }
                }
                last = mono
                haveLast = true
                i++
            }
        } finally {
            bis.close()
        }
        return out
    }

    private fun monoSample(frame: ByteArray, encoding: Int, channels: Int): Float {
        var sum = 0f
        when (encoding) {
            ENC_8BIT -> for (c in 0 until channels) sum += ((frame[c].toInt() and 0xFF) - 128) / 128.0f
            ENC_24BIT -> for (c in 0 until channels) {
                val v = (frame[c * 3].toInt() and 0xFF) or
                    ((frame[c * 3 + 1].toInt() and 0xFF) shl 8) or
                    ((frame[c * 3 + 2].toInt() and 0xFF) shl 16)
                val sv = (v shl 8) shr 8
                sum += sv / 8388608.0f
            }
            ENC_FLOAT -> for (c in 0 until channels) {
                val bits = (frame[c * 4].toInt() and 0xFF) or
                    ((frame[c * 4 + 1].toInt() and 0xFF) shl 8) or
                    ((frame[c * 4 + 2].toInt() and 0xFF) shl 16) or
                    ((frame[c * 4 + 3].toInt() and 0xFF) shl 24)
                sum += Float.fromBits(bits)
            }
            else -> for (c in 0 until channels) {
                val lo = frame[c * 2].toInt() and 0xFF
                val hi = frame[c * 2 + 1].toInt()
                sum += (lo or (hi shl 8)) / 32768.0f
            }
        }
        return sum / channels
    }

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun skipFully(bis: BufferedInputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = bis.skip(remaining)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun skipFully(raf: RandomAccessFile, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = raf.skipBytes(remaining.toInt().coerceAtMost(Int.MAX_VALUE))
            if (skipped <= 0) break
            remaining -= skipped
        }
    }
}
