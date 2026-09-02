package com.example.areadtext.utils

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import java.io.File

/**
 * 存储管理工具类
 * 处理存储空间检查和文件管理
 */
object StorageUtils {

    /**
     * 检查存储空间是否足够
     * @param context 应用上下文
     * @param requiredBytes 需要的字节数
     * @param includeExternal 是否包含外部存储检查
     * @return 是否有足够的存储空间
     */
    fun hasEnoughStorage(context: Context, requiredBytes: Long, includeExternal: Boolean = false): Boolean {
        // 内部存储检查
        val internalFree = getAvailableInternalStorage(context)
        if (internalFree >= requiredBytes) {
            return true
        }

        // 外部存储检查
        if (includeExternal && isExternalStorageWritable()) {
            val externalFree = getAvailableExternalStorage()
            return externalFree >= requiredBytes
        }

        return false
    }

    /**
     * 获取可用的内部存储空间（字节）
     */
    fun getAvailableInternalStorage(context: Context): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        val blockSize = stat.blockSizeLong
        val availableBlocks = stat.availableBlocksLong
        return availableBlocks * blockSize
    }

    /**
     * 获取内部存储总空间（字节）
     */
    fun getTotalInternalStorage(context: Context): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        return totalBlocks * blockSize
    }

    /**
     * 获取可用的外部存储空间（字节）
     */
    fun getAvailableExternalStorage(): Long {
        if (!isExternalStorageWritable()) {
            return 0L
        }

        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val blockSize = stat.blockSizeLong
        val availableBlocks = stat.availableBlocksLong
        return availableBlocks * blockSize
    }

    /**
     * 获取外部存储总空间（字节）
     */
    fun getTotalExternalStorage(): Long {
        if (!isExternalStorageReadable()) {
            return 0L
        }

        val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        return totalBlocks * blockSize
    }

    /**
     * 检查外部存储是否可写
     */
    fun isExternalStorageWritable(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }

    /**
     * 检查外部存储是否可读
     */
    fun isExternalStorageReadable(): Boolean {
        val state = Environment.getExternalStorageState()
        return state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
    }

    /**
     * 获取格式化的存储信息字符串
     */
    fun getStorageInfoString(context: Context): String {
        val totalInternal = getTotalInternalStorage(context)
        val availableInternal = getAvailableInternalStorage(context)
        val usedInternal = totalInternal - availableInternal

        val totalExternal = getTotalExternalStorage()
        val availableExternal = getAvailableExternalStorage()
        val usedExternal = totalExternal - availableExternal

        return buildString {
            append("内部存储:\n")
            append("  总计: ${Formatter.formatFileSize(context, totalInternal)}\n")
            append("  已用: ${Formatter.formatFileSize(context, usedInternal)}\n")
            append("  可用: ${Formatter.formatFileSize(context, availableInternal)}\n")

            if (isExternalStorageReadable()) {
                append("\n外部存储:\n")
                append("  总计: ${Formatter.formatFileSize(context, totalExternal)}\n")
                append("  已用: ${Formatter.formatFileSize(context, usedExternal)}\n")
                append("  可用: ${Formatter.formatFileSize(context, availableExternal)}\n")
            }
        }
    }

    /**
     * 获取应用总存储占用（字节）
     */
    fun getAppStorageSize(context: Context): Long {
        return calculateFolderSize(context.filesDir) +
               calculateFolderSize(context.cacheDir) +
               (if (isExternalStorageWritable()) calculateFolderSize(context.getExternalFilesDir(null) ?: File("")) else 0L) +
               (if (isExternalStorageWritable()) calculateFolderSize(context.externalCacheDir ?: File("")) else 0L)
    }

    /**
     * 计算文件夹大小
     */
    private fun calculateFolderSize(folder: File): Long {
        if (!folder.exists() || !folder.isDirectory) {
            return 0L
        }

        var size = 0L
        folder.listFiles()?.forEach { file ->
            size += if (file.isFile) {
                file.length()
            } else {
                calculateFolderSize(file)
            }
        }
        return size
    }

    /**
     * 清理应用缓存
     * @param context 应用上下文
     * @return 清理释放的空间（字节）
     */
    fun clearAppCache(context: Context): Long {
        val beforeSize = getAppStorageSize(context)
        
        // 清理内部缓存
        deleteFolderContent(context.cacheDir)
        
        // 清理外部缓存
        context.externalCacheDir?.let { deleteFolderContent(it) }
        
        val afterSize = getAppStorageSize(context)
        return beforeSize - afterSize
    }

    /**
     * 删除文件夹内容
     */
    private fun deleteFolderContent(folder: File) {
        if (!folder.exists() || !folder.isDirectory) {
            return
        }

        folder.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            } else {
                deleteFolderContent(file)
                file.delete()
            }
        }
    }

    /**
     * 获取模型存储目录大小
     */
    fun getModelStorageSize(context: Context): Long {
        val modelsDir = File(context.filesDir, "models")
        return if (modelsDir.exists()) {
            calculateFolderSize(modelsDir)
        } else {
            0L
        }
    }

    /**
     * 获取录音文件存储目录大小
     */
    fun getRecordingsStorageSize(context: Context): Long {
        val recordingsDir = File(context.filesDir, "recordings")
        return if (recordingsDir.exists()) {
            calculateFolderSize(recordingsDir)
        } else {
            0L
        }
    }
}