package com.example.areadtext.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.areadtext.R
import com.example.areadtext.reader.OfflineTtsEngine
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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

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

    // 失败信息上下文：当前阶段与已下载字节，出错时拼进报错文案
    @Volatile private var stage = "准备"
    @Volatile private var downloadedBytes = 0L
    // 最近一次 HTTP 响应码（用于把 403/404/5xx 归类成不同的人话提示）
    @Volatile private var lastHttpCode = 0

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext Result.failure(
                errorData("【准备】任务数据缺少 model_id 参数（inputData=$inputData）")
            )
        val model = ModelCatalog.findById(applicationContext, modelId)
            ?: return@withContext Result.failure(
                errorData("【准备】模型目录中找不到 id=$modelId，可能该自定义模型已被删除")
            )

        return@withContext try {
            setForeground(foregroundInfo(model.name, 0))
            val staging = ModelManager.stagingDirectory(applicationContext, model)
            if (!staging.mkdirs() && !staging.isDirectory) {
                throw IOException("无法创建临时目录: ${staging.absolutePath}（应用存储可能被拒绝或已满）")
            }
            checkFreeSpace(model)

            stage = "下载"
            downloadArchive(model, staging)
            downloadedBytes = model.archive.sizeBytes
            updateProgress(100, STAGE_VERIFYING)
            setForeground(foregroundInfo(model.name, 100, verifying = true))

            stage = "解压"
            try {
                extractor.extract(File(staging, model.archive.name), staging)
            } catch (e: Exception) {
                throw IOException(
                    "解压 ${model.archive.name} 失败（${e.javaClass.simpleName}: ${e.message}）。" +
                        "压缩包可能损坏或格式不支持（仅支持 .tar.bz2/.tar.gz/.zip），" +
                        "建议点「取消」清理后重新下载",
                    e
                )
            }

            stage = "校验文件"
            if (model.files.isNotEmpty()) verifyFiles(model, staging)

            stage = "安装"
            installAtomically(model, staging)
            val dest = ModelManager.installedDirectory(applicationContext, model)
            writeMetadata(model, dest)

            stage = "加载校验"
            val invalidReason = OfflineTtsEngine.validateReason(dest)
            if (invalidReason != null) {
                dest.deleteRecursively()
                throw IllegalStateException(
                    "TTS 原生加载校验未通过，已删除安装文件（重新下载可能解决）。\n$invalidReason"
                )
            }
            ModelManager.setActiveModel(applicationContext, model.id)
            Log.i(TAG, "model installed: ${model.id}")
            Result.success(workDataOf(KEY_MODEL_ID to model.id, KEY_PROGRESS to 100))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "download failed: model=$modelId stage=$stage", e)
            val clazz = classify(e)
            val tech = buildFailureMessage(model, e)
            // 人话三层（给普通用户看）+ 技术详情（给排查用）分层输出
            Result.failure(
                errorData(
                    message = tech,
                    human = humanMessage(model, e, clazz),
                    clazz = clazz
                )
            )
        }
    }

    /** 把异常归类为稳定错误码，UI 据此决定展示哪些补救按钮（如「换镜像重试」）。 */
    private fun classify(e: Exception): String = when {
        e is UnknownHostException -> CLASS_DNS
        e is SocketTimeoutException -> CLASS_TIMEOUT
        e is SSLException -> CLASS_SSL
        e is IOException && (
            e.message?.contains("No space left", true) == true ||
                e.message?.contains("存储空间不足", true) == true
            ) -> CLASS_SPACE
        e is IOException && e.message?.contains("SHA-256 校验和不匹配", true) == true -> CLASS_SHA
        e is IOException && e.message?.contains("下载不完整", true) == true -> CLASS_INCOMPLETE
        e is IOException && e.message?.contains("下载体积超出预期", true) == true -> CLASS_SIZE
        stage == "解压" -> CLASS_EXTRACT
        stage == "下载" && lastHttpCode != 0 -> when (lastHttpCode) {
            403 -> CLASS_HTTP_403
            404 -> CLASS_HTTP_404
            416 -> CLASS_HTTP_416
            in 500..599 -> CLASS_HTTP_5XX
            else -> CLASS_HTTP_OTHER
        }
        stage == "下载" -> CLASS_NETWORK
        else -> CLASS_OTHER
    }

    /**
     * 人话三层：① 安抚+定性 ② 原因 ③ 具体建议。
     * 展示在技术详情上方，普通用户只看这段就够了；技术详情折叠在下方。
     */
    private fun humanMessage(model: LocalModelInfo, e: Exception, clazz: String): String {
        val reason: String
        val action: String
        when (clazz) {
            CLASS_HTTP_403 -> {
                reason = applicationContext.getString(R.string.model_err_reason_403)
                action = applicationContext.getString(R.string.model_err_action_403)
            }
            CLASS_TIMEOUT -> {
                reason = applicationContext.getString(R.string.model_err_reason_timeout)
                action = applicationContext.getString(R.string.model_err_action_timeout)
            }
            CLASS_DNS -> {
                reason = applicationContext.getString(R.string.model_err_reason_dns)
                action = applicationContext.getString(R.string.model_err_action_dns)
            }
            CLASS_SSL -> {
                reason = applicationContext.getString(R.string.model_err_reason_ssl)
                action = applicationContext.getString(R.string.model_err_action_ssl)
            }
            CLASS_SPACE -> {
                reason = applicationContext.getString(R.string.model_err_reason_space)
                action = applicationContext.getString(R.string.model_err_action_space)
            }
            CLASS_SHA, CLASS_EXTRACT, CLASS_INCOMPLETE, CLASS_SIZE -> {
                reason = applicationContext.getString(R.string.model_err_reason_sha)
                action = applicationContext.getString(R.string.model_err_action_sha)
            }
            CLASS_HTTP_404, CLASS_HTTP_416, CLASS_HTTP_OTHER -> {
                reason = applicationContext.getString(R.string.model_err_reason_http4, lastHttpCode)
                action = applicationContext.getString(R.string.model_err_action_http4)
            }
            CLASS_HTTP_5XX -> {
                reason = applicationContext.getString(R.string.model_err_reason_http5)
                action = applicationContext.getString(R.string.model_err_action_http5)
            }
            CLASS_NETWORK -> {
                reason = applicationContext.getString(R.string.model_err_reason_network)
                action = applicationContext.getString(R.string.model_err_action_network)
            }
            else -> {
                reason = applicationContext.getString(R.string.model_err_reason_other, e.javaClass.simpleName)
                action = applicationContext.getString(R.string.model_err_action_other)
            }
        }
        return applicationContext.getString(R.string.model_error_human_format, reason, action)
    }

    /** 汇总一条完整的报错：阶段 + 模型 + URL + 镜像 + 进度 + 异常分类提示与原因链（技术详情）。 */
    private fun buildFailureMessage(model: LocalModelInfo, e: Exception): String {
        val total = model.archive.sizeBytes
        val (url, mirror) = ModelMirror.resolve(applicationContext, model)
        return buildString {
            appendLine("【${stage}阶段】失败")
            appendLine("模型: ${model.name} (${model.id})")
            appendLine("地址: $url")
            appendLine("下载通道: ${mirror.label}")
            if (stage == "下载" && total > 0) {
                appendLine(
                    "进度: ${fmt(downloadedBytes)} / ${fmt(total)}" +
                        " (${((downloadedBytes * 100) / total).toInt()}%)，" +
                        "「下载」按钮可从断点继续"
                )
            }
            append(describeException(e))
        }
    }

    /** 把网络/IO 异常翻译成带排查建议的中文说明。 */
    private fun describeException(e: Exception): String {
        val guidance = when {
            e is UnknownHostException ->
                "无法解析域名「${e.message?.substringAfter(": ")}」。GitHub 等地址在国内网络常被 DNS 污染，" +
                    "请切换网络（Wi-Fi/流量互换）、开启代理后重试，或点「下载」断点续传。"
            e is SocketTimeoutException ->
                "连接超时（建立连接或读取数据无响应）。网络不稳定或对端限速常见，" +
                    "可稍后点「下载」从断点继续。"
            e is SSLException ->
                "TLS/证书握手失败。请检查：① 系统日期时间是否正确 ② 是否连入了需要登录认证的公共 Wi-Fi " +
                    "③ 代理/防火墙是否拦截了 HTTPS 流量。"
            e is IOException && e.message?.contains("No space left", true) == true ->
                "设备存储空间已写满，请清理存储后点「下载」从断点续传。"
            else -> null
        }
        val base = "${e.javaClass.simpleName}: ${e.message ?: "（无错误信息）"}"
        val root = generateSequence(e.cause) { it.cause }.lastOrNull()
        return buildString {
            if (guidance != null) {
                append(guidance)
                appendLine()
                append("原始错误: $base")
            } else {
                append("原因: $base")
            }
            if (root != null && root !== e) {
                appendLine()
                append("根因: ${root.javaClass.simpleName}: ${root.message}")
            }
        }
    }

    /** 下载前预检剩余存储空间，不足时给出需要/可用的具体数字。 */
    private fun checkFreeSpace(model: LocalModelInfo) {
        val free = StatFs(applicationContext.filesDir.absolutePath).availableBytes
        val archive = model.archive.sizeBytes
        if (archive > 0) {
            val needed = archive + archive / 2 // 压缩包 + 解压产物
            if (free < needed) {
                throw IOException(
                    "存储空间不足：下载并解压 ${model.archive.name} 约需 ${fmt(needed)}，" +
                        "应用当前可用仅 ${fmt(free)}，请清理存储后重试"
                )
            }
        } else if (free < 60L * 1024 * 1024) {
            throw IOException(
                "存储空间不足：自定义模型大小未知，当前应用可用仅 ${fmt(free)}（低于 60MB），" +
                    "下载很可能中途失败，请先清理存储"
            )
        }
    }

    private fun fmt(bytes: Long): String =
        Formatter.formatShortFileSize(applicationContext, bytes)

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

        // 按当前选择的镜像解析实际下载地址（GitHub 直连 / 社区加速 / 自定义前缀）
        val (resolvedUrl, mirror) = ModelMirror.resolve(applicationContext, model)
        Log.i(TAG, "download via ${mirror.label}: $resolvedUrl")

        val request = Request.Builder()
            .url(resolvedUrl)
            .header("User-Agent", "AReadText/1.0")
            .apply { if (sizeKnown && offset > 0) header("Range", "bytes=$offset-") }
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                lastHttpCode = resp.code
                val hint = when (resp.code) {
                    403 -> "（可能被 CDN 限流或拒绝访问，可点「换镜像重试」换下载通道，或先用浏览器打开链接验证）"
                    404 -> "（下载地址不存在：模型可能已改名或已从发布页移除）"
                    416 -> "（断点位置超出文件大小，请点「取消」后重新下载）"
                    in 500..599 -> "（服务器暂时不可用，请稍后重试）"
                    else -> ""
                }
                throw IOException(
                    "HTTP ${resp.code} ${resp.message.orEmpty()}，下载 ${model.archive.name} 失败$hint" +
                        "\nURL: $resolvedUrl"
                )
            }
            val append = sizeKnown && offset > 0 && resp.code == 206
            if (sizeKnown && offset > 0 && !append) {
                Log.i(TAG, "server ignored Range header, restarting ${model.archive.name} from 0")
                partial.delete(); offset = 0
            }
            val body = resp.body ?: throw IOException("服务器返回空响应体: ${model.archive.url}")
            downloadedBytes = offset
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
                    downloadedBytes = offset
                    if (sizeKnown && offset > model.archive.sizeBytes) {
                        throw IOException(
                            "${model.archive.name} 下载体积超出预期：已写入 ${fmt(offset)} > " +
                                "服务器标称 ${fmt(model.archive.sizeBytes)}。返回内容可能不是模型文件（如被门户/代理拦截）"
                        )
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
            throw IOException(
                "${model.archive.name} 下载不完整：实际 ${fmt(partial.length())} / " +
                    "预期 ${fmt(model.archive.sizeBytes)}（${((partial.length() * 100) / model.archive.sizeBytes).toInt()}%）。" +
                    "多为网络中断所致，点「下载」可从断点续传"
            )
        }
        if (sha != null) {
            val actual = sha256(partial)
            if (actual != sha) {
                partial.delete()
                throw IOException(
                    "${model.archive.name} SHA-256 校验和不匹配，文件已删除需重新下载：" +
                        "\n期望 $sha\n实际 $actual"
                )
            }
        }
        move(partial, complete)
    }

    private fun verifyFiles(model: LocalModelInfo, staging: File) {
        for (f in model.files) {
            val file = File(staging, f.name)
            if (!file.isFile) {
                throw IOException(
                    "解压后缺少文件 ${f.name}（压缩包内容与模型清单不符）。" +
                        "目录实际内容: ${staging.listFiles()?.joinToString { it.name } ?: "无法读取"}"
                )
            }
            if (f.sizeBytes > 0 && file.length() != f.sizeBytes) {
                throw IOException(
                    "文件 ${f.name} 大小不匹配：实际 ${fmt(file.length())} ≠ 预期 ${fmt(f.sizeBytes)}"
                )
            }
            if (!f.sha256.isNullOrBlank() && sha256(file) != f.sha256) {
                throw IOException("文件 ${f.name} SHA-256 与清单不符，压缩包内容已损坏")
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
            throw IOException("无法把旧版本目录 ${dest.absolutePath} 移开，安装中止（未破坏现有文件）")
        }
        if (!staging.renameTo(dest)) {
            backup.renameTo(dest)
            throw IOException(
                "无法将解压目录移动为安装目录 ${dest.absolutePath}（存储空间/权限问题），已恢复旧目录"
            )
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
            put("task", "tts")
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
        if (!src.renameTo(dst)) {
            throw IOException("无法重命名 ${src.name} → ${dst.name}（文件被占用或跨存储分区）")
        }
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
            applicationContext.applicationContext.getString(R.string.download_verifying)
        } else {
            applicationContext.applicationContext.getString(R.string.download_progress, name, progress.coerceIn(0, 100))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.applicationContext.getString(R.string.download_notification_title))
            .setContentText(content)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), verifying)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                applicationContext.applicationContext.getString(R.string.action_cancel), cancel)
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
                applicationContext.applicationContext.getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun errorData(
        message: String,
        human: String? = null,
        clazz: String = CLASS_OTHER
    ) = workDataOf(
        KEY_ERROR to message.take(2000),
        KEY_ERROR_HUMAN to (human ?: message).take(600),
        KEY_ERROR_CLASS to clazz
    )

    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        const val KEY_ERROR_HUMAN = "error_human"
        const val KEY_ERROR_CLASS = "error_class"
        const val STAGE_DOWNLOADING = "downloading"
        const val STAGE_VERIFYING = "verifying"
        private const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID = 4307

        // ---- 错误分类码：UI 依据分类决定补救按钮（403/超时 → 换镜像重试） ----
        const val CLASS_HTTP_403 = "HTTP_403"
        const val CLASS_HTTP_404 = "HTTP_404"
        const val CLASS_HTTP_416 = "HTTP_416"
        const val CLASS_HTTP_5XX = "HTTP_5XX"
        const val CLASS_HTTP_OTHER = "HTTP_OTHER"
        const val CLASS_TIMEOUT = "TIMEOUT"
        const val CLASS_DNS = "DNS"
        const val CLASS_SSL = "SSL"
        const val CLASS_SPACE = "SPACE"
        const val CLASS_SHA = "SHA"
        const val CLASS_EXTRACT = "EXTRACT"
        const val CLASS_INCOMPLETE = "INCOMPLETE"
        const val CLASS_SIZE = "SIZE"
        const val CLASS_NETWORK = "NETWORK"
        const val CLASS_OTHER = "OTHER"

        /** 是否需要展示「换镜像重试」：网络 / 下载源类问题才需要换通道。 */
        fun supportsMirrorRetry(clazz: String?): Boolean = when (clazz) {
            CLASS_HTTP_403, CLASS_HTTP_404, CLASS_HTTP_416, CLASS_HTTP_5XX, CLASS_HTTP_OTHER,
            CLASS_TIMEOUT, CLASS_DNS, CLASS_SSL, CLASS_NETWORK -> true
            else -> false
        }
    }
}
