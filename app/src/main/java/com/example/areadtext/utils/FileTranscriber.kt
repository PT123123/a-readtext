package com.example.areadtext.utils

import android.content.Context
import com.example.areadtext.asr.SherpaStreamingAsr
import java.io.File

/**
 * Offline transcription of an audio file through the streaming recognizer:
 * decodes the file to 16 kHz mono PCM and feeds it chunk by chunk, then returns
 * the accumulated final text. Used both for freshly imported files and for
 * re-transcribing previously pending recordings.
 */
object FileTranscriber {

    data class Result(val text: String, val durationMs: Long)

    /**
     * Returns null when decode or ASR init fails; otherwise a [Result] (text may
     * be blank when no speech is detected).
     */
    fun transcribe(context: Context, modelDir: File, path: String): Result? {
        val pcm = AudioFileDecoder.decodeToPcm16kMono(context, path) ?: return null
        val durationMs = (pcm.size / 16.0).toLong()
        if (pcm.isEmpty()) return Result("", durationMs)

        val asr = SherpaStreamingAsr()
        if (!asr.init(modelDir)) return null
        try {
            val chunk = FloatArray(1600)
            var i = 0
            while (i < pcm.size) {
                val n = minOf(1600, pcm.size - i)
                pcm.copyInto(chunk, 0, i, i + n)
                asr.accept(chunk.copyOfRange(0, n))
                i += n
            }
            return Result(asr.finalText(), durationMs)
        } finally {
            asr.release()
        }
    }
}
