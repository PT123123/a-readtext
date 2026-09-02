package com.example.areadtext.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.areadtext.R

/**
 * 权限管理工具类
 * 处理应用所需的各种权限检查和请求
 */
object PermissionUtils {

    /**
     * 检查是否已授予所有必需的权限
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 获取尚未授予的权限列表
     */
    fun getMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 获取所有必需的权限
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )

        // Android 13+ 需要通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions
    }

    /**
     * 检查是否需要显示权限说明
     */
    fun shouldShowPermissionRationale(activity: androidx.appcompat.app.AppCompatActivity, permission: String): Boolean {
        return activity.shouldShowRequestPermissionRationale(permission)
    }

    /**
     * 获取权限的友好名称（用于UI显示）
     */
    fun getPermissionFriendlyName(context: Context, permission: String): String {
        return when (permission) {
            Manifest.permission.RECORD_AUDIO -> context.getString(R.string.permission_microphone)
            Manifest.permission.POST_NOTIFICATIONS -> context.getString(R.string.permission_notifications)
            Manifest.permission.INTERNET -> context.getString(R.string.permission_internet)
            else -> permission
        }
    }

    /**
     * 检查是否需要特殊权限（如存储权限）
     */
    fun needsStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
    }
}