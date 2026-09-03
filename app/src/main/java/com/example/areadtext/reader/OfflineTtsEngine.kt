package com.example.areadtext.reader

import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import java.io.File

/**
 * 离线 TTS 引擎封装 —— 完全绕开系统引擎接口（HandyReader 三引擎架构中的
 * "离线神经网络 AI" 路径，用 sherpa-onnx 本机合成）。
 *
 * 模型目录结构各异（vits/melo 是 model.onnx，zipvoice 是 encoder/decoder/vocoder，
 * matcha 是 acoustic_model/vocoder，kokoro 带 voices.bin，pocket/supertonic 更碎），
 * 这里在 init 时扫描目录自动识别模型族并构建对应的 [OfflineTtsModelConfig]，
 * 与现有 ModelManager 的"模型下载后自动可用"体验一致。
 */
class OfflineTtsEngine(private val modelDir: File, private val modelId: String) {

    enum class ModelType { VITS, MATCHA, KOKORO, ZIPVOICE, POCKET, SUPERTONIC }

    private var tts: OfflineTts? = null
    private var type: ModelType? = null

    val modelType: ModelType? get() = type
    val isReady: Boolean get() = tts != null

    /** 初始化：扫描目录 → 构建配置 → 实例化原生引擎。失败返回 false。 */
    fun init(): Boolean {
        release()
        return try {
            val files = modelDir.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) return false
            val cfg = buildConfig(files) ?: return false
            tts = OfflineTts(assetManager = null, config = cfg)
            type = detectType(files)
            Log.i(TAG, "offline TTS ready: type=$type model=$modelId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}", e)
            release()
            false
        }
    }

    fun sampleRate(): Int = tts?.sampleRate() ?: 0

    /** 合成一句：返回音频样本 + 时长；失败返回 null。speed 在合成期生效（sherpa 原生支持）。 */
    fun synthesize(text: String, speed: Float, sid: Int = 0): SynthesizedAudio? {
        val engine = tts ?: return null
        return try {
            val audio: GeneratedAudio = engine.generate(text, sid = sid, speed = speed.coerceIn(0.3f, 4f))
            val sr = audio.sampleRate
            val durationMs = if (sr > 0) audio.samples.size.toLong() * 1000L / sr else 0L
            SynthesizedAudio(samples = audio.samples, sampleRate = sr, durationMs = durationMs, text = text)
        } catch (e: Exception) {
            Log.e(TAG, "synthesize failed: ${e.message}", e)
            null
        }
    }

    fun release() {
        try { tts?.release() } catch (_: Exception) {}
        tts = null
        type = null
    }

    // ---- 模型族自动探测 ----

    private fun detectType(files: List<File>): ModelType? {
        val names = files.map { it.name.lowercase() }
        return when {
            names.any { it.startsWith("duration_predictor") || it.startsWith("duration-predictor") } -> ModelType.SUPERTONIC
            names.any { it.startsWith("lm_flow") || it.startsWith("lm-flow") } -> ModelType.POCKET
            names.any { it.startsWith("acoustic_model") || it.startsWith("acoustic-model") } -> ModelType.MATCHA
            names.any { it == "voices.bin" || it.endsWith("_voices.bin") || it.contains("voices") } -> ModelType.KOKORO
            names.any { it.startsWith("encoder") } && names.any { it.startsWith("decoder") } &&
                names.any { it.startsWith("vocoder") } -> ModelType.ZIPVOICE
            else -> ModelType.VITS
        }
    }

    private fun buildConfig(files: List<File>): OfflineTtsConfig? {
        val tokens = files.firstOrNull { it.name.equals("tokens.txt", true) }?.absolutePath ?: return null
        val type = detectType(files) ?: return null
        this.type = type

        fun firstOrNull(vararg prefixes: String): String? =
            files.firstOrNull { f -> prefixes.any { f.name.startsWith(it, true) } }?.absolutePath

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val model = OfflineTtsModelConfig(
            numThreads = threads,
            debug = false,
            provider = "cpu",
        )

        when (type) {
            ModelType.ZIPVOICE -> {
                model.zipvoice = OfflineTtsZipVoiceModelConfig(
                    tokens = tokens,
                    encoder = firstOrNull("encoder", "encoder-epoch") ?: return null,
                    decoder = firstOrNull("decoder", "decoder-epoch") ?: return null,
                    vocoder = firstOrNull("vocoder", "vocoder-epoch") ?: return null,
                )
            }
            ModelType.MATCHA -> {
                model.matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = firstOrNull("acoustic_model", "acoustic-model") ?: return null,
                    vocoder = firstOrNull("vocoder", "vocoder-epoch") ?: return null,
                    lexicon = firstOrNull("lexicon.txt") ?: "",
                    tokens = tokens,
                    dataDir = modelDir.absolutePath,
                )
            }
            ModelType.KOKORO -> {
                model.kokoro = OfflineTtsKokoroModelConfig(
                    model = firstOrNull("model") ?: return null,
                    voices = firstOrNull("voices.bin", "voices") ?: "",
                    tokens = tokens,
                    dataDir = modelDir.absolutePath,
                    lexicon = firstOrNull("lexicon.txt") ?: "",
                )
            }
            ModelType.POCKET -> {
                model.pocket = OfflineTtsPocketModelConfig(
                    lmFlow = firstOrNull("lm_flow", "lm-flow") ?: "",
                    lmMain = firstOrNull("lm_main", "lm-main") ?: "",
                    encoder = firstOrNull("encoder", "encoder-epoch") ?: "",
                    decoder = firstOrNull("decoder", "decoder-epoch") ?: "",
                    textConditioner = firstOrNull("text_conditioner", "text-conditioner") ?: "",
                    vocabJson = firstOrNull("vocab.json") ?: "",
                    tokenScoresJson = firstOrNull("token_scores.json") ?: "",
                )
            }
            ModelType.SUPERTONIC -> {
                model.supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = firstOrNull("duration_predictor", "duration-predictor") ?: "",
                    textEncoder = firstOrNull("text_encoder", "text-encoder") ?: "",
                    vectorEstimator = firstOrNull("vector_estimator", "vector-estimator") ?: "",
                    vocoder = firstOrNull("vocoder", "vocoder-epoch") ?: "",
                    ttsJson = firstOrNull("tts.json") ?: "",
                    unicodeIndexer = firstOrNull("unicode_indexer", "unicode-indexer") ?: "",
                    voiceStyle = firstOrNull("voice_style", "voice-style") ?: "",
                )
            }
            else -> { // VITS（含 melo / piper）
                model.vits = OfflineTtsVitsModelConfig(
                    model = firstOrNull("model") ?: return null,
                    lexicon = firstOrNull("lexicon.txt") ?: "",
                    tokens = tokens,
                    dataDir = modelDir.absolutePath,
                )
            }
        }
        return OfflineTtsConfig(
            model = model,
            ruleFsts = "",
            ruleFars = "",
            maxNumSentences = 1,
            silenceScale = 0.2f,
        )
    }

    /** 最终校验：真正构造一次原生引擎（模型文件错误/损坏会抛异常 → false）。 */
    fun isValid(): Boolean {
        return try {
            if (!init()) false else { release(); true }
        } catch (e: Exception) {
            Log.w(TAG, "model load test failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "OfflineTtsEngine"

        /** 模型目录是否可被离线 TTS 使用（供 ModelManager 校验）。 */
        fun isModelValid(dir: File): Boolean {
            return try {
                val files = dir.walkTopDown().filter { it.isFile }.toList()
                if (files.isEmpty()) return false
                if (!files.any { it.name.equals("tokens.txt", true) }) return false
                val engine = OfflineTtsEngine(dir, "validate")
                engine.isValid()
            } catch (e: Exception) {
                Log.w(TAG, "isModelValid failed: ${e.message}")
                false
            }
        }
    }
}

/** 一次合成结果。 */
data class SynthesizedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationMs: Long,
    val text: String,
)
