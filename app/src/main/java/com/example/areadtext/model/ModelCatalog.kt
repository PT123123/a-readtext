package com.example.areadtext.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Describes an on-device TTS (text-to-speech / 文本转录音) model and where to fetch it from.
 *
 * `sha256` is optional: when null we skip the cryptographic check and rely on the
 * final native load-test as the source of truth (a TTS validator replaces the old
 * SherpaStreamingAsr.isModelValid once the TTS engine is implemented).
 * Fill in exact sha256/size once you have the model URL.
 */
data class LocalModelFile(
    val name: String,
    val sizeBytes: Long = 0,
    val sha256: String? = null
)

data class LocalModelArchive(
    val name: String,
    val url: String,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    /** Top-level directory name inside the archive (its contents are flattened on extract). */
    val rootDirectory: String = ""
)

data class LocalModelInfo(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val minSdk: Int = 24,
    val minRamMb: Int = 0,
    val archive: LocalModelArchive,
    val files: List<LocalModelFile> = emptyList(),
    val huggingFaceUrl: String = "",
    val license: String = "Apache-2.0",
    val isCustom: Boolean = false,
    /** Comma-separated language tags, e.g. "zh,en", "zh,yue,en", "ko", used by the UI filter. */
    val language: String = ""
) {
    val downloadSizeBytes: Long get() = archive.sizeBytes
    val installedSizeBytes: Long get() = files.sumOf { it.sizeBytes }
}

object ModelCatalog {

    // ---- built-in official TTS models (sherpa-onnx, k2-fsa) ----
    //
    // All archive URLs are verified live at https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
    // sizeBytes = exact tar.bz2 Content-Length (used for progress / resume / completeness check).
    // sha256 left null: final verification falls back to a native load-test once the TTS engine lands.
    //
    // NOTE on model types (OfflineTtsModelConfig): vits / matcha / melo / kokoro / zipvoice /
    // supertonic / pocket. Newer families (zipvoice, supertonic-3, pocket) require a recent
    // sherpa-onnx AAR — build.gradle is already bumped to the latest v1.13.7.

    private fun official(
        id: String,
        version: String,
        name: String,
        description: String,
        sizeBytes: Long,
        minRamMb: Int = 0,
        language: String,
        huggingFaceUrl: String = ""
    ): LocalModelInfo {
        val archiveName = "$id.tar.bz2"
        return LocalModelInfo(
            id = id,
            version = version,
            name = name,
            description = description,
            minSdk = 24,
            minRamMb = minRamMb,
            archive = LocalModelArchive(
                name = archiveName,
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$archiveName",
                sizeBytes = sizeBytes,
                sha256 = null,
                rootDirectory = id
            ),
            files = emptyList(),
            huggingFaceUrl = huggingFaceUrl,
            license = "Apache-2.0",
            language = language
        )
    }

    val builtIn: List<LocalModelInfo> = listOf(

        // ---- 中英双语 Zipvoice（2025 新版，音质好，支持流式合成）----
        official(
            id = "sherpa-onnx-zipvoice-zh-en-emilia",
            version = "2025-10",
            name = "Zipvoice 中英双语 (Emilia)",
            description = "Zipvoice 神经 TTS，中英双语，Emilia 语料，音质出色 · 体积最大",
            sizeBytes = 634_731_511,
            minRamMb = 2048,
            language = "zh,en",
            huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-zipvoice-zh-en-emilia"
        ),
        official(
            id = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia",
            version = "2025-10",
            name = "Zipvoice 中英双语 蒸馏 (int8)",
            description = "Zipvoice 蒸馏版，encoder int8，体积小速度快，中英双语 · 推荐日常使用",
            sizeBytes = 109_162_785,
            minRamMb = 512,
            language = "zh,en",
            huggingFaceUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-zipvoice-zh-en-emilia"
        ),

        // ---- SuperTonic-3（2026 新模型，轻量快速）----
        official(
            id = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
            version = "2026-05-11",
            name = "SuperTonic-3 中英双语 (int8)",
            description = "SuperTonic-3 神经 TTS，int8，极致轻量快速，中英双语 · 适合低端设备",
            sizeBytes = 128_774_318,
            minRamMb = 512,
            language = "zh,en"
        ),

        // ---- Pocket TTS（2026 新模型，流式低延迟）----
        official(
            id = "sherpa-onnx-pocket-tts-int8-2026-01-26",
            version = "2026-01-26",
            name = "Pocket TTS 中英双语 (int8)",
            description = "Pocket TTS，int8，低延迟流式合成，中英双语 · 适合实时朗读",
            sizeBytes = 98_336_520,
            minRamMb = 512,
            language = "zh,en"
        ),

        // ---- Kokoro（多语言）----
        official(
            id = "kokoro-int8-multi-lang-v1_1",
            version = "v1.1",
            name = "Kokoro 多语言 (int8)",
            description = "Kokoro v1.1 多语言 TTS，int8，多音色，支持中文+英文 · 效果自然",
            sizeBytes = 147_031_220,
            minRamMb = 512,
            language = "zh,en",
            huggingFaceUrl = "https://huggingface.co/hexgrad/Kokoro-82M"
        ),

        // ---- Matcha-TTS（扩散模型，中英双语）----
        official(
            id = "matcha-icefall-zh-en",
            version = "2024-09",
            name = "Matcha 中英双语",
            description = "Matcha-TTS 扩散模型，中英双语，需搭配声码器与词典，效果自然",
            sizeBytes = 79_033_838,
            minRamMb = 512,
            language = "zh,en"
        ),

        // ---- Melo TTS（中英混合）----
        official(
            id = "vits-melo-tts-zh_en",
            version = "v1.0",
            name = "Melo TTS 中英混合",
            description = "Melo TTS，中英文混合朗读，单音色，支持中英混读场景",
            sizeBytes = 167_006_755,
            minRamMb = 512,
            language = "zh,en"
        ),

        // ---- VITS 中文 ----
        official(
            id = "sherpa-onnx-vits-zh-ll",
            version = "v1.0",
            name = "VITS 中文 (5 音色)",
            description = "经典 VITS 中文 TTS，5 个音色可选，兼容性最好，体积适中",
            sizeBytes = 118_810_709,
            minRamMb = 512,
            language = "zh"
        ),
        official(
            id = "vits-zh-aishell3",
            version = "2024-09",
            name = "VITS 中文 AISHELL3 (174 音色)",
            description = "AISHELL3 语料训练的中文 VITS，多达 174 个音色可选",
            sizeBytes = 146_922_607,
            minRamMb = 1024,
            language = "zh"
        ),

        // ---- 粤语 ----
        official(
            id = "vits-cantonese-hf-xiaomaiiwn",
            version = "v1.0",
            name = "VITS 粤语 (小美声)",
            description = "粤语 VITS TTS（小美声），支持粤语朗读",
            sizeBytes = 107_995_442,
            minRamMb = 512,
            language = "zh,yue"
        ),

        // ---- 英文（轻量）----
        official(
            id = "vits-piper-en_US-amy-low",
            version = "v1.0",
            name = "VITS 英文 Piper Amy (low)",
            description = "Piper 英文 Amy 女声低资源版，体积最小，适合英文朗读",
            sizeBytes = 67_095_344,
            minRamMb = 256,
            language = "en"
        )
    )

    fun all(context: Context): List<LocalModelInfo> = builtIn + customModels(context)

    fun findById(context: Context, id: String?): LocalModelInfo? =
        all(context).firstOrNull { it.id == id }

    /**
     * Adds a custom model (persisted at runtime, no recompile needed).
     * Same URL is de-duplicated; a blank name is derived from the file name.
     */
    fun addCustom(context: Context, url: String, name: String? = null): LocalModelInfo {
        val safeUrl = url.trim()
        val archiveName = safeUrl.substringAfterLast('/').ifBlank { "model.tar.bz2" }
        val displayName = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: archiveName.removeSuffix(".tar.bz2").removeSuffix(".zip")
        val id = "custom-" + safeUrl.hashCode().let { if (it < 0) -it else it }
        val info = LocalModelInfo(
            id = id,
            version = "custom",
            name = displayName,
            description = "自定义模型（运行期添加）\n$safeUrl",
            minSdk = 24,
            minRamMb = 0,
            archive = LocalModelArchive(
                name = archiveName,
                url = safeUrl,
                sizeBytes = 0,
                sha256 = null,
                rootDirectory = archiveName.removeSuffix(".tar.bz2").removeSuffix(".zip")
            ),
            files = emptyList(),
            huggingFaceUrl = safeUrl,
            license = "Apache-2.0",
            isCustom = true
        )
        val list = customModels(context).toMutableList()
        list.removeIf { it.id == id }
        list.add(info)
        saveCustom(context, list)
        return info
    }

    /** Removes a custom model from the runtime catalog (does not delete installed files). */
    fun removeCustom(context: Context, id: String) {
        val list = customModels(context).toMutableList()
        list.removeIf { it.id == id }
        saveCustom(context, list)
    }

    // ---- custom model persistence (SharedPreferences JSON) ----

    private const val PREFS = "areadtext_models"
    private const val KEY_CUSTOM = "custom_models"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun customModels(context: Context): List<LocalModelInfo> {
        val raw = prefs(context).getString(KEY_CUSTOM, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { decode(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Custom models only (persisted, added at runtime without recompiling). */
    fun custom(context: Context): List<LocalModelInfo> = customModels(context)


    private fun saveCustom(context: Context, models: List<LocalModelInfo>) {
        val arr = JSONArray()
        models.forEach { arr.put(encode(it)) }
        prefs(context).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    private fun encode(m: LocalModelInfo) = JSONObject().apply {
        put("id", m.id)
        put("version", m.version)
        put("name", m.name)
        put("description", m.description)
        put("minSdk", m.minSdk)
        put("minRamMb", m.minRamMb)
        put("huggingFaceUrl", m.huggingFaceUrl)
        put("license", m.license)
        put("isCustom", m.isCustom)
        put("language", m.language)
        put(
            "archive", JSONObject().apply {
                put("name", m.archive.name)
                put("url", m.archive.url)
                put("sizeBytes", m.archive.sizeBytes)
                put("sha256", m.archive.sha256)
                put("rootDirectory", m.archive.rootDirectory)
            }
        )
        put("files", JSONArray().apply { m.files.forEach { f -> put(JSONObject().apply {
            put("name", f.name); put("sizeBytes", f.sizeBytes); put("sha256", f.sha256)
        }) } })
    }

    private fun decode(j: JSONObject): LocalModelInfo? = try {
        val a = j.getJSONObject("archive")
        LocalModelInfo(
            id = j.getString("id"),
            version = j.optString("version", "custom"),
            name = j.optString("name", "custom"),
            description = j.optString("description", ""),
            minSdk = j.optInt("minSdk", 24),
            minRamMb = j.optInt("minRamMb", 0),
            archive = LocalModelArchive(
                name = a.getString("name"),
                url = a.getString("url"),
                sizeBytes = a.optLong("sizeBytes", 0),
                sha256 = a.optString("sha256", "").takeIf { it.isNotBlank() },
                rootDirectory = a.optString("rootDirectory", "")
            ),
            files = run {
                val fa = j.optJSONArray("files") ?: JSONArray()
                (0 until fa.length()).map {
                    val f = fa.getJSONObject(it)
                    LocalModelFile(
                        name = f.getString("name"),
                        sizeBytes = f.optLong("sizeBytes", 0),
                        sha256 = f.optString("sha256", "").takeIf { s -> s.isNotBlank() }
                    )
                }
            },
            huggingFaceUrl = j.optString("huggingFaceUrl", ""),
            license = j.optString("license", "Apache-2.0"),
            isCustom = j.optBoolean("isCustom", true),
            language = j.optString("language", "")
        )
    } catch (_: Exception) {
        null
    }
}
