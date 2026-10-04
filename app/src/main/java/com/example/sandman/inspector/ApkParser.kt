package com.example.sandman.inspector

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import com.example.sandman.model.InstalledVirtualApp
import java.io.File

/**
 * Technical utility to parse Android Application Packages (APKs) without host installation.
 * Uses Android PackageManager's archive inspection APIs and reflection on AssetManager
 * to extract manifest headers, component declarations, and resources.
 */
object ApkParser {

    fun parseApk(context: Context, apkFile: File, isolatedDataDir: String): InstalledVirtualApp? {
        if (!apkFile.exists() || !apkFile.canRead()) return null

        return try {
            val flags = PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_PERMISSIONS or
                    PackageManager.GET_SERVICES

            val packageInfo: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    apkFile.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            }

            if (packageInfo == null) return null

            // AOSP Trick: ApplicationInfo needs sourceDir set to load labels/assets
            packageInfo.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = apkFile.absolutePath
                appInfo.publicSourceDir = apkFile.absolutePath
            }

            val packageName = packageInfo.packageName ?: "unknown.virtual.pkg"
            val versionName = packageInfo.versionName ?: "1.0.0"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            val appLabel = try {
                packageInfo.applicationInfo?.loadLabel(context.packageManager)?.toString()
                    ?: packageName.substringAfterLast(".")
            } catch (e: Exception) {
                packageName.substringAfterLast(".")
            }

            // Find main launcher activity or first declared activity
            val activities = packageInfo.activities?.map { it.name } ?: emptyList()
            val mainActivity = activities.firstOrNull { it.contains("Main", ignoreCase = true) || it.contains("Launch", ignoreCase = true) }
                ?: activities.firstOrNull()
                ?: "com.example.sandman.component.DiagnosticsActivity"

            val appClass = packageInfo.applicationInfo?.className
            val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()

            InstalledVirtualApp(
                packageName = packageName,
                appName = appLabel,
                versionName = versionName,
                versionCode = versionCode,
                apkPath = apkFile.absolutePath,
                mainActivity = mainActivity,
                applicationClass = appClass,
                permissions = permissions,
                isolatedDataDir = isolatedDataDir
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Creates a synthetic Resources object for the target APK.
     * Reflection on AssetManager: addAssetPath(apkPath)
     */
    fun createTargetResources(hostContext: Context, apkPath: String): Resources? {
        return try {
            val assetManager = AssetManager::class.java.newInstance()
            val addAssetPathMethod = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
            addAssetPathMethod.invoke(assetManager, apkPath)
            Resources(assetManager, hostContext.resources.displayMetrics, hostContext.resources.configuration)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
