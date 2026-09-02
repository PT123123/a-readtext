package com.example.areadtext.model

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.areadtext.asr.SherpaStreamingAsr
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

enum class ModelInstallStatus {
    NOT_INSTALLED, QUEUED, DOWNLOADING, VERIFYING, PAUSED, INSTALLED, FAILED
}

data class ModelState(
    val info: LocalModelInfo,
    val status: ModelInstallStatus,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0,
    val error: String? = null,
    val isActive: Boolean = false
) {
    val isInstalled: Boolean get() = status == ModelInstallStatus.INSTALLED
}

/**
 * Orchestrates model download/install via WorkManager with pause-resume
 * (HTTP Range), SHA-256 verification, atomic install and failure recovery.
 */
object ModelManager {

    private const val PREFS = "areadtext_model_active"
    private const val KEY_ACTIVE = "active_model_id"
    private val refresh = MutableStateFlow(0)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun uniqueWorkName(info: LocalModelInfo) = "model-download-${info.id}"
    fun modelRoot(context: Context, info: LocalModelInfo) =
        File(context.filesDir, "models/sherpa-onnx/${info.id}")
    fun stagingDirectory(context: Context, info: LocalModelInfo) =
        File(modelRoot(context, info), "${info.version}.staging")
    fun installedDirectory(context: Context, info: LocalModelInfo) =
        File(modelRoot(context, info), info.version)
    private fun pausedMarker(context: Context, info: LocalModelInfo) =
        File(modelRoot(context, info), ".paused")

    fun isInstalled(context: Context, info: LocalModelInfo): Boolean {
        val dir = installedDirectory(context, info)
        return File(dir, "installed.marker").isFile && hasCoreFiles(dir)
    }

    // TTS 模型结构各异（vits/melo 是 model.onnx，zipvoice/supertonic/pocket 是 encoder/decoder/...），
    // 因此只要求 tokens.txt + 任意 .onnx 即视为核心文件齐全。
    private fun hasCoreFiles(dir: File): Boolean {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val names = files.map { it.name.lowercase() }
        return names.contains("tokens.txt") &&
            names.any { it.endsWith(".onnx") }
    }

    // ---- active model ----

    fun getActiveModelId(context: Context): String? = prefs(context).getString(KEY_ACTIVE, null)

    fun setActiveModel(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
        refresh.value++
    }

    /** Returns the installed directory of the active model, or null if none ready. */
    fun getActiveModelDirectory(context: Context): File? {
        val id = getActiveModelId(context)
        val candidate = id?.let { ModelCatalog.findById(context, it) }
            ?.takeIf { isInstalled(context, it) }
            ?: ModelCatalog.all(context).firstOrNull { isInstalled(context, it) }
        candidate ?: return null
        val dir = installedDirectory(context, candidate)
        // TODO(TTS): replace the ASR-specific SherpaStreamingAsr.isModelValid with a TTS
        //  native load-test (e.g. SherpaOnnxOfflineTts) once the text-to-speech engine lands.
        return if (SherpaStreamingAsr.isModelValid(dir)) dir else null
    }

    // ---- work control ----

    fun download(context: Context, info: LocalModelInfo) {
        pausedMarker(context, info).delete()
        val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to info.id))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .addTag("model-download")
            .addTag(info.id)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueWorkName(info), ExistingWorkPolicy.REPLACE, req
        )
        refresh.value++
    }

    fun pause(context: Context, info: LocalModelInfo) {
        pausedMarker(context, info).apply { parentFile?.mkdirs(); writeText("paused") }
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(info))
        refresh.value++
    }

    fun resume(context: Context, info: LocalModelInfo) {
        pausedMarker(context, info).delete()
        download(context, info)
    }

    fun cancel(context: Context, info: LocalModelInfo) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(info))
        pausedMarker(context, info).delete()
        stagingDirectory(context, info).deleteRecursively()
        refresh.value++
    }

    fun delete(context: Context, info: LocalModelInfo) {
        cancel(context, info)
        installedDirectory(context, info).deleteRecursively()
        if (getActiveModelId(context) == info.id) {
            prefs(context).edit().remove(KEY_ACTIVE).apply()
        }
        refresh.value++
    }

    // ---- observation ----

    fun observeStates(context: Context): Flow<List<ModelState>> {
        val models = ModelCatalog.all(context)
        val activeId = getActiveModelId(context)
        val flows = models.map { m -> observeModel(context, m, activeId) }
        return if (flows.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList<ModelState>())
        else combine(flows) { arr -> arr.toList() }
    }

    private fun observeModel(
        context: Context, info: LocalModelInfo, activeId: String?
    ): Flow<ModelState> = combine(
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(uniqueWorkName(info)),
        refresh
    ) { infos, _ -> stateFor(context, info, infos.lastOrNull(), activeId) }

    private fun stateFor(
        context: Context, info: LocalModelInfo, work: WorkInfo?, activeId: String?
    ): ModelState {
        if (isInstalled(context, info)) {
            return ModelState(
                info = info, status = ModelInstallStatus.INSTALLED,
                progressPercent = 100, downloadedBytes = info.downloadSizeBytes,
                isActive = info.id == activeId
            )
        }
        if (pausedMarker(context, info).exists()) {
            return ModelState(
                info = info, status = ModelInstallStatus.PAUSED,
                progressPercent = work?.progress?.getInt(ModelDownloadWorker.KEY_PROGRESS, 0) ?: 0,
                downloadedBytes = work?.progress?.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L) ?: 0,
                isActive = info.id == activeId
            )
        }
        val status = when (work?.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelInstallStatus.QUEUED
            WorkInfo.State.RUNNING -> {
                if (work.progress.getString(ModelDownloadWorker.KEY_STAGE) == ModelDownloadWorker.STAGE_VERIFYING)
                    ModelInstallStatus.VERIFYING else ModelInstallStatus.DOWNLOADING
            }
            WorkInfo.State.FAILED -> ModelInstallStatus.FAILED
            else -> ModelInstallStatus.NOT_INSTALLED
        }
        return ModelState(
            info = info, status = status,
            progressPercent = work?.progress?.getInt(ModelDownloadWorker.KEY_PROGRESS, 0) ?: 0,
            downloadedBytes = work?.progress?.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L) ?: 0,
            error = work?.outputData?.getString(ModelDownloadWorker.KEY_ERROR),
            isActive = info.id == activeId
        )
    }
}
