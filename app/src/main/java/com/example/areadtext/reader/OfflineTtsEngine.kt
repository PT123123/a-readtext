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

    /** 最近一次 init/synthesize 失败的具体原因，成功时为 null，供 UI 直接展示。 */
    var lastError: String? = null
        private set

    /** 初始化：扫描目录 → 构建配置 → 实例化原生引擎。失败返回 false，原因见 [lastError]。 */
    fun init(): Boolean {
        release()
        lastError = null
        return try {
            if (!modelDir.isDirectory) {
                lastError = "模型目录不存在: ${modelDir.absolutePath}"
                return false
            }
            val files = modelDir.walkTopDown().filter { it.isFile }.toList()
            if (files.isEmpty()) {
                lastError = "模型目录为空: ${modelDir.absolutePath}"
                return false
            }
            val cfg = buildConfig(files) ?: return false
            tts = OfflineTts(assetManager = null, config = cfg)
            type = detectType(files)
            Log.i(TAG, "offline TTS ready: type=$type model=$modelId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "init failed: ${e.message}", e)
            lastError = lastError
                ?: "原生引擎初始化失败（模型族: ${type ?: "未识别"}）: ${describe(e)}\n目录: ${modelDir.absolutePath}"
            release()
            false
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "native library load failed", e)
            lastError = "sherpa-onnx 原生库加载失败（SO 库缺失或与设备 ABI 不兼容）: ${e.message}"
            release()
            false
        }
    }

    fun sampleRate(): Int = tts?.sampleRate() ?: 0

    /** 模型支持的说话人数（vits/kokoro 等多音色模型 >1；单音色或引擎未就绪返回 1）。 */
    fun numSpeakers(): Int = (tts?.numSpeakers() ?: 0).coerceAtLeast(1)

    /** 合成一句：返回音频样本 + 时长；失败返回 null，原因见 [lastError]。speed 在合成期生效（sherpa 原生支持）。 */
    fun synthesize(text: String, speed: Float, sid: Int = 0): SynthesizedAudio? {
        val engine = tts ?: run {
            if (lastError == null) lastError = "引擎尚未初始化（init 未成功或已被释放）"
            return null
        }
        return try {
            val audio: GeneratedAudio = engine.generate(text, sid = sid, speed = speed.coerceIn(0.3f, 4f))
            val sr = audio.sampleRate
            val durationMs = if (sr > 0) audio.samples.size.toLong() * 1000L / sr else 0L
            SynthesizedAudio(samples = audio.samples, sampleRate = sr, durationMs = durationMs, text = text)
        } catch (e: Exception) {
            Log.e(TAG, "synthesize failed: ${e.message}", e)
            lastError = "合成「${text.take(24)}…」失败: ${describe(e)}"
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
        val listing = files.take(20).joinToString(", ") { it.name } +
            if (files.size > 20) "…（共 ${files.size} 个文件）" else ""

        fun need(typeName: String, vararg prefixes: String): String? =
            files.firstOrNull { f -> prefixes.any { f.name.startsWith(it, true) } }?.absolutePath
                ?: run {
                    lastError = "识别为 $typeName 模型，但缺少「${prefixes.joinToString(" / ")}」开头的文件" +
                        "\n目录内文件: $listing\n目录: ${modelDir.absolutePath}"
                    null
                }

        val tokens = files.firstOrNull { it.name.equals("tokens.txt", true) }?.absolutePath
            ?: run {
                lastError = "目录中未找到 tokens.txt，不是有效的 sherpa-onnx TTS 模型" +
                    "\n目录内文件: $listing\n目录: ${modelDir.absolutePath}"
                return null
            }
        val type = detectType(files) ?: run {
            lastError = "无法识别模型族（目录内文件: $listing）"
            return null
        }
        this.type = type

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
                    encoder = need("ZIPVOICE", "encoder", "encoder-epoch") ?: return null,
                    decoder = need("ZIPVOICE", "decoder", "decoder-epoch") ?: return null,
                    vocoder = need("ZIPVOICE", "vocoder", "vocoder-epoch") ?: return null,
                )
            }
            ModelType.MATCHA -> {
                model.matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = need("MATCHA", "acoustic_model", "acoustic-model") ?: return null,
                    vocoder = need("MATCHA", "vocoder", "vocoder-epoch") ?: return null,
                    lexicon = files.firstOrNull { it.name.startsWith("lexicon.txt", true) }?.absolutePath ?: "",
                    tokens = tokens,
                    dataDir = modelDir.absolutePath,
                )
            }
            ModelType.KOKORO -> {
                model.kokoro = OfflineTtsKokoroModelConfig(
                    model = need("KOKORO", "model") ?: return null,
                    voices = files.firstOrNull { f ->
                        f.name.equals("voices.bin", true) || f.name.endsWith("_voices.bin", true) ||
                            f.name.contains("voices", true)
                    }?.absolutePath ?: "",
                    tokens = tokens,
                    dataDir = modelDir.absolutePath,
                    lexicon = files.firstOrNull { it.name.startsWith("lexicon.txt", true) }?.absolutePath ?: "",
                )
            }
            ModelType.POCKET -> {
                model.pocket = OfflineTtsPocketModelConfig(
                    lmFlow = files.firstOrNull { it.name.startsWith("lm_flow", true) || it.name.startsWith("lm-flow", true) }?.absolutePath ?: "",
                    lmMain = files.firstOrNull { it.name.startsWith("lm_main", true) || it.name.startsWith("lm-main", true) }?.absolutePath ?: "",
                    encoder = files.firstOrNull { it.name.startsWith("encoder", true) }?.absolutePath ?: "",
                    decoder = files.firstOrNull { it.name.startsWith("decoder", true) }?.absolutePath ?: "",
                    textConditioner = files.firstOrNull { it.name.startsWith("text_conditioner", true) || it.name.startsWith("text-conditioner", true) }?.absolutePath ?: "",
                    vocabJson = files.firstOrNull { it.name.equals("vocab.json", true) }?.absolutePath ?: "",
                    tokenScoresJson = files.firstOrNull { it.name.equals("token_scores.json", true) }?.absolutePath ?: "",
                )
            }
            ModelType.SUPERTONIC -> {
                model.supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = files.firstOrNull { it.name.startsWith("duration_predictor", true) || it.name.startsWith("duration-predictor", true) }?.absolutePath ?: "",
                    textEncoder = files.firstOrNull { it.name.startsWith("text_encoder", true) || it.name.startsWith("text-encoder", true) }?.absolutePath ?: "",
                    vectorEstimator = files.firstOrNull { it.name.startsWith("vector_estimator", true) || it.name.startsWith("vector-estimator", true) }?.absolutePath ?: "",
                    vocoder = files.firstOrNull { it.name.startsWith("vocoder", true) }?.absolutePath ?: "",
                    ttsJson = files.firstOrNull { it.name.equals("tts.json", true) }?.absolutePath ?: "",
                    unicodeIndexer = files.firstOrNull { it.name.startsWith("unicode_indexer", true) || it.name.startsWith("unicode-indexer", true) }?.absolutePath ?: "",
                    voiceStyle = files.firstOrNull { it.name.startsWith("voice_style", true) || it.name.startsWith("voice-style", true) }?.absolutePath ?: "",
                )
            }
            else -> { // VITS（含 melo / piper）
                model.vits = OfflineTtsVitsModelConfig(
                    model = need("VITS", "model") ?: return null,
                    lexicon = files.firstOrNull { it.name.startsWith("lexicon.txt", true) }?.absolutePath ?: "",
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

    /** 异常 → "类名: message（根因 类名: message）" 一行式描述。 */
    private fun describe(e: Throwable): String {
        val root = generateSequence(e.cause) { it.cause }.lastOrNull() ?: e
        return if (root === e) "${e.javaClass.simpleName}: ${e.message}"
        else "${e.javaClass.simpleName}: ${e.message}（根因 ${root.javaClass.simpleName}: ${root.message}）"
    }

    companion object {
        private const val TAG = "OfflineTtsEngine"

        /**
         * 真正构造一次原生引擎校验模型目录：可用返回 null，
         * 否则返回具体失败原因（缺哪个文件 / 原生异常信息），供下载与切换模型时展示。
         */
        fun validateReason(dir: File): String? {
            return try {
                val engine = OfflineTtsEngine(dir, "validate")
                if (engine.init()) {
                    engine.release()
                    null
                } else {
                    engine.lastError ?: "模型加载测试失败（未知原因），目录: ${dir.absolutePath}"
                }
            } catch (e: Exception) {
                Log.w(TAG, "validateReason failed: ${e.message}", e)
                "模型校验异常 ${e.javaClass.simpleName}: ${e.message}"
            }
        }

        /** 模型目录是否可被离线 TTS 使用（供 ModelManager 校验）。 */
        fun isModelValid(dir: File): Boolean = validateReason(dir) == null
    }
}

/** 一次合成结果。 */
data class SynthesizedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val durationMs: Long,
    val text: String,
)
