package com.example.areadtext.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.areadtext.R
import com.example.areadtext.asr.SherpaStreamingAsr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Downloads a model archive, verifies it, extracts it, installs it atomically and
 * finally load-tests the native recognizer. Supports resume via HTTP Range and
 * retries transient failures through WorkManager.
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient()
    private val extractor = ArchiveExtractor

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext Result.failure(errorData("Missing model id"))
        val model = ModelCatalog.findById(applicationContext, modelId)
            ?: return@withContext Result.failure(errorData("Unknown model: $modelId"))

        return@withContext try {
            setForeground(foregroundInfo(model.name, 0))
            val staging = ModelManager.stagingDirectory(applicationContext, model)
            staging.mkdirs()

            downloadArchive(model, staging)
            updateProgress(100, STAGE_VERIFYING)
            setForeground(foregroundInfo(model.name, 100, verifying = true))

            extractor.extract(File(staging, model.archive.name), staging)
            if (model.files.isNotEmpty()) verifyFiles(model, staging)

            installAtomically(model, staging)
            val dest = ModelManager.installedDirectory(applicationContext, model)
            writeMetadata(model, dest)
            if (!SherpaStreamingAsr.isModelValid(dest)) {
                dest.deleteRecursively()
                throw IllegalStateException("模型加载校验失败（文件可能损坏或不匹配）")
            }
            ModelManager.setActiveModel(applicationContext, model.id)
            Result.success(workDataOf(KEY_MODEL_ID to model.id, KEY_PROGRESS to 100))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(errorData(e.message ?: "下载失败"))
        }
    }

    private suspend fun downloadArchive(model: LocalModelInfo, staging: File) {
        val name = model.archive.name
        val complete = File(staging, name)
        val sizeKnown = model.archive.sizeBytes > 0
        val sha = model.archive.sha256

        if (complete.exists() && (!sizeKnown || complete.length() == model.archive.sizeBytes)) {
            if (sha == null || sha256(complete) == sha) return
            complete.delete()
        }

        val partial = File(staging, "$name.part")
        if (sizeKnown && partial.length() == model.archive.sizeBytes) {
            if (sha == null || sha256(partial) == sha) {
                move(partial, complete); return
            }
            partial.delete()
        }

        var offset = if (sizeKnown) partial.length().coerceAtLeast(0) else 0L
        if (!sizeKnown) partial.delete() // no resume without a known total size

        val request = Request.Builder()
            .url(model.archive.url)
            .header("User-Agent", "AReadText/1.0")
            .apply { if (sizeKnown && offset > 0) header("Range", "bytes=$offset-") }
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} 下载 ${model.archive.name} 失败")
            val append = sizeKnown && offset > 0 && resp.code == 206
            if (sizeKnown && offset > 0 && !append) {
                partial.delete(); offset = 0
            }
            val body = resp.body ?: throw IOException("空响应: ${model.archive.name}")
            val source = body.byteStream()
            FileOutputStream(partial, append).use { out ->
                val buf = ByteArray(8192)
                var lastPct = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val c = source.read(buf)
                    if (c < 0) break
                    out.write(buf, 0, c)
                    offset += c
                    if (sizeKnown && offset > model.archive.sizeBytes) {
                        throw IOException("${model.archive.name} 体积超过预期")
                    }
                    if (sizeKnown) {
                        val pct = ((offset * 100) / model.archive.sizeBytes).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            updateProgress(offset, STAGE_DOWNLOADING)
                            setForeground(foregroundInfo(model.name, pct))
                        }
                    }
                }
                out.fd.sync()
            }
        }

        if (sizeKnown && partial.length() != model.archive.sizeBytes) {
            throw IOException("${model.archive.name} 下载不完整")
        }
        if (sha != null && sha256(partial) != sha) {
            partial.delete()
            throw IOException("${model.archive.name} 校验和不匹配")
        }
        move(partial, complete)
    }

    private fun verifyFiles(model: LocalModelInfo, staging: File) {
        for (f in model.files) {
            val file = File(staging, f.name)
            if (!file.isFile) throw IOException("缺少文件: ${f.name}")
            if (f.sizeBytes > 0 && file.length() != f.sizeBytes) {
                throw IOException("文件大小不匹配: ${f.name}")
            }
            if (!f.sha256.isNullOrBlank() && sha256(file) != f.sha256) {
                throw IOException("文件校验失败: ${f.name}")
            }
        }
    }

    private fun installAtomically(model: LocalModelInfo, staging: File) {
        val root = ModelManager.modelRoot(applicationContext, model)
        val dest = ModelManager.installedDirectory(applicationContext, model)
        if (ModelManager.isInstalled(applicationContext, model)) {
            staging.deleteRecursively(); return
        }
        val backup = File(root, "${model.version}.previous")
        backup.deleteRecursively()
        if (dest.exists() && !dest.renameTo(backup)) {
            throw IOException("无法替换旧模型")
        }
        if (!staging.renameTo(dest)) {
            backup.renameTo(dest)
            throw IOException("无法安装已校验的模型")
        }
        backup.deleteRecursively()
        File(root, ".paused").delete()
    }

    private fun writeMetadata(model: LocalModelInfo, dir: File) {
        File(dir, "installed.marker").writeText(model.version)
        val manifest = JSONObject().apply {
            put("id", model.id)
            put("version", model.version)
            put("runtime", "sherpa-onnx")
            put("task", "stt")
            put("license", model.license)
            put("url", model.archive.url)
            put(
                "files", JSONArray().apply {
                    dir.listFiles()?.forEach { f -> put(f.name) }
                }
            )
        }
        File(dir, "manifest.json").writeText(manifest.toString())
    }

    private fun move(src: File, dst: File) {
        dst.delete()
        if (!src.renameTo(dst)) throw IOException("无法移动 ${dst.name}")
    }

    private suspend fun updateProgress(bytes: Long, stage: String) {
        val pct = if (modelTotalBytes() > 0) ((bytes * 100) / modelTotalBytes()).toInt().coerceIn(0, 100) else 0
        setProgress(
            workDataOf(
                KEY_MODEL_ID to (inputData.getString(KEY_MODEL_ID) ?: ""),
                KEY_PROGRESS to pct,
                KEY_DOWNLOADED_BYTES to bytes,
                KEY_STAGE to stage
            )
        )
    }

    private fun modelTotalBytes(): Long {
        val id = inputData.getString(KEY_MODEL_ID) ?: return 0
        return ModelCatalog.findById(applicationContext, id)?.archive?.sizeBytes ?: 0
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val c = input.read(buf)
                if (c < 0) break
                digest.update(buf, 0, c)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun foregroundInfo(name: String, progress: Int, verifying: Boolean = false): ForegroundInfo {
        createChannel()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val content = if (verifying) {
            applicationContext.getString(R.string.download_verifying)
        } else {
            applicationContext.getString(R.string.download_progress, name, progress.coerceIn(0, 100))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), verifying)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.getString(R.string.action_cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = applicationContext.getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun errorData(message: String) = workDataOf(KEY_ERROR to message.take(500))

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        const val STAGE_DOWNLOADING = "downloading"
        const val STAGE_VERIFYING = "verifying"
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4307
    }
}
