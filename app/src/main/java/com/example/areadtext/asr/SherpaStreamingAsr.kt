package com.example.areadtext.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File

/**
 * Streaming ASR engine built on sherpa-onnx [OnlineRecognizer].
 *
 * Supports the two streaming model families shipped by k2-fsa:
 *  - Paraformer   (encoder.int8.onnx + decoder.int8.onnx + tokens.txt)
 *  - Transducer   (encoder + decoder + joiner + tokens.txt, e.g. zipformer)
 *
 * File layout is auto-detected from the model directory so the same code path
 * works for the bundled bilingual Paraformer and for any custom model URL the
 * user pastes in the model manager.
 */
class SherpaStreamingAsr {

    companion object {
        private const val TAG = "SherpaStreamingAsr"
        const val SAMPLE_RATE = 16000
        private const val PARAFORMER_TAIL_MS = 800 // right-context padding for last word

        /**
         * Build a recognizer config by scanning [modelDir] for model files.
         * Scanning is recursive (some archives keep files under exp/ / data/ subfolders)
         * and matches by role prefix + .onnx suffix instead of exact names, so epoch-style
         * file names (e.g. encoder-epoch-99-avg-1.int8.onnx) load too. int8 variants are
         * preferred to keep the on-device footprint small.
         *
         * Returns null when the directory does not look like a valid streaming model.
         */
        private fun buildConfig(modelDir: File): OnlineRecognizerConfig? {
            val all = modelDir.walkTopDown().filter { it.isFile }.toList()
            if (all.isEmpty()) return null

            val tokens = all.firstOrNull { it.name.equals("tokens.txt", true) }
                ?.absolutePath ?: return null

            fun roleFiles(role: String): List<File> =
                all.filter { f ->
                    f.name.startsWith(role, true) && f.name.endsWith(".onnx", true)
                }.sortedByDescending { it.name.contains("int8", true) }

            val enc = roleFiles("encoder").firstOrNull()
            val dec = roleFiles("decoder").firstOrNull()
            val joiner = roleFiles("joiner").firstOrNull()

            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.4f, 0.0f),
                    rule2 = EndpointRule(true, 1.4f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 20.0f)
                ),
                enableEndpoint = true,
                modelConfig = OnlineModelConfig(
                    tokens = tokens,
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                    provider = "cpu"
                )
            )

            // Paraformer: encoder + decoder, no joiner
            if (enc != null && dec != null && joiner == null) {
                config.modelConfig.paraformer =
                    OnlineParaformerModelConfig(encoder = enc.absolutePath, decoder = dec.absolutePath)
                config.modelConfig.modelType = "paraformer"
                return config
            }

            // Streaming Zipformer-CTC: encoder + tokens, no decoder/joiner.
            // These are the smallest official streaming models (~20 MB int8).
            if (enc != null && dec == null) {
                config.modelConfig.zipformer2Ctc =
                    OnlineZipformer2CtcModelConfig(model = enc.absolutePath)
                config.modelConfig.modelType = "zipformer2_ctc"
                return config
            }

            // Transducer: encoder + decoder + joiner (zipformer / zipformer2 /
            // conformer / lstm / ebranchformer). modelType is left empty so
            // sherpa-onnx reads the "model_type" metadata embedded in the ONNX
            // encoder and picks the right implementation automatically.
            if (enc != null && dec != null && joiner != null) {
                config.modelConfig.transducer =
                    OnlineTransducerModelConfig(
                        encoder = enc.absolutePath,
                        decoder = dec.absolutePath,
                        joiner = joiner.absolutePath
                    )
                config.modelConfig.modelType = ""
                return config
            }

            return null
        }

        /**
         * Final verification step: try to actually construct the native recognizer.
         * If the model files are wrong/corrupt this throws, so we surface a clean FAIL
         * instead of failing later at record time.
         */
        fun isModelValid(modelDir: File): Boolean {
            return try {
                val cfg = buildConfig(modelDir) ?: return false
                val r = OnlineRecognizer(assetManager = null, config = cfg)
                r.release()
                true
            } catch (e: Exception) {
                Log.w(TAG, "model load test failed for $modelDir: ${e.message}")
                false
            }
        }
    }

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var modelType: String = ""
    private var isReady = false

    /** Accumulated finalized text (segments ended at an endpoint). */
    private var finalized = StringBuilder()

    /** Whether the currently loaded model is a Paraformer (needs tail padding). */
    private val isParaformer: Boolean get() = modelType == "paraformer"

    fun init(modelDir: File): Boolean {
        release()
        return try {
            val cfg = buildConfig(modelDir) ?: return false
            recognizer = OnlineRecognizer(assetManager = null, config = cfg)
            modelType = cfg.modelConfig.modelType
            stream = recognizer!!.createStream()
            finalized.setLength(0)
            isReady = true
            Log.i(TAG, "ASR ready (modelType=$modelType) from ${modelDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ASR init failed: ${e.message}")
            release()
            false
        }
    }

    fun isInitialized(): Boolean = isReady

    /**
     * Feed one chunk of 16 kHz mono PCM (float, -1..1). Returns the current
     * display text = finalized segments + current partial.
     */
    fun accept(samples: FloatArray): String {
        val rec = recognizer ?: return finalized.toString()
        val s = stream ?: return finalized.toString()

        s.acceptWaveform(samples, SAMPLE_RATE)
        while (rec.isReady(s)) rec.decode(s)

        val endpoint = rec.isEndpoint(s)
        var text = rec.getResult(s).text

        if (endpoint && isParaformer) {
            val tail = FloatArray((PARAFORMER_TAIL_MS * SAMPLE_RATE / 1000))
            s.acceptWaveform(tail, SAMPLE_RATE)
            while (rec.isReady(s)) rec.decode(s)
            text = rec.getResult(s).text
        }

        if (endpoint) {
            rec.reset(s)
            if (text.isNotBlank()) {
                if (finalized.isNotEmpty()) finalized.append('\n')
                finalized.append(text)
            }
            return finalized.toString()
        }

        return if (finalized.isNotEmpty()) {
            if (text.isBlank()) finalized.toString() else "${finalized}\n$text"
        } else {
            text
        }
    }

    /** Final flush: add tail padding so the last word / final partial is decoded into text. */
    fun finalText(): String {
        val rec = recognizer ?: return finalized.toString()
        val s = stream ?: return finalized.toString()
        // 尾部补静音并读取最终 partial：流式模型（Paraformer / Transducer / CTC）通用做法，
        // 否则短音频若无端点触发，最后一段识别结果会一直停留在 partial 中而丢失。
        val tail = FloatArray((PARAFORMER_TAIL_MS * SAMPLE_RATE / 1000))
        s.acceptWaveform(tail, SAMPLE_RATE)
        while (rec.isReady(s)) rec.decode(s)
        val text = rec.getResult(s).text
        if (text.isNotBlank()) {
            if (finalized.isNotEmpty()) finalized.append('\n')
            finalized.append(text)
        }
        return finalized.toString()
    }

    fun release() {
        try { stream?.release() } catch (_: Exception) {}
        try { recognizer?.release() } catch (_: Exception) {}
        stream = null
        recognizer = null
        isReady = false
        finalized.setLength(0)
    }
}
