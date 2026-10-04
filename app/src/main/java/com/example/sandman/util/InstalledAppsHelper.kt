package com.example.sandman.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DeviceApp(
    val appName: String,
    val packageName: String,
    val launchActivity: String?,
    val isSystemApp: Boolean
)

object InstalledAppsHelper {

    suspend fun getLaunchableApps(context: Context): List<DeviceApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        try {
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val list = mutableListOf<DeviceApp>()

            for (info in resolveInfos) {
                val pkgName = info.activityInfo.packageName
                if (pkgName == context.packageName) continue // Skip self

                val appName = info.loadLabel(pm).toString()
                val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                list.add(
                    DeviceApp(
                        appName = appName,
                        packageName = pkgName,
                        launchActivity = info.activityInfo.name,
                        isSystemApp = isSystem
                    )
                )
            }
            list.sortedBy { it.appName.lowercase() }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun launchDeviceApp(context: Context, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
