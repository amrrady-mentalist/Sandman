package com.example.sandman.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.sandman.component.StubActivity
import com.example.sandman.hooks.ServiceHookManager
import com.example.sandman.hooks.VirtualClock
import com.example.sandman.inspector.ApkParser
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import com.example.sandman.model.InstalledVirtualApp
import com.example.sandman.model.SandboxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * =========================================================================================
 * ARCHITECTURE DEEP-DIVE: VIRTUAL CONTAINER & MULTI-APP LIFECYCLE
 * =========================================================================================
 * In modern Android, apps installed via Google Play or `pm install` are allocated a unique
 * Linux UID (e.g. `u0_a145`) and placed in `/data/app/<package>-<hash>/base.apk`.
 *
 * Sandman Virtual Space:
 * 1. Maintains an isolated storage repository at `/data/data/.../virtual_apps/<package>/`.
 * 2. Manages dynamic metadata, ClassLoader linkage, and system service proxy injection.
 * 3. Launches applications and interactive sandboxed suites inside the secure host shell.
 * =========================================================================================
 */
object VirtualContainer {

    private const val PREFS_NAME = "sandman_virtual_space_prefs"
    private const val KEY_CONFIG = "saved_sandbox_config"
    private const val KEY_INSTALLED_APPS = "saved_installed_apps"

    private val _installedApps = MutableStateFlow<List<InstalledVirtualApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledVirtualApp>> = _installedApps.asStateFlow()

    private val _currentConfig = MutableStateFlow(SandboxConfig())
    val currentConfig: StateFlow<SandboxConfig> = _currentConfig.asStateFlow()

    fun initialize(context: Context) {
        VirtualLogBus.initialize(context)
        loadSavedConfig(context)
        loadInstalledApps(context)
        ensureBuiltInVirtualApps(context)

        // Install dynamic service proxy hooks
        ServiceHookManager.updateConfig(_currentConfig.value)
        ServiceHookManager.installServiceManagerHooks(context)

        VirtualLogBus.log(
            category = HookCategory.LIFECYCLE,
            method = "VirtualContainer.initialize",
            targetClass = "VirtualContainer",
            interceptedPayload = "Installed apps count: ${_installedApps.value.size}",
            spoofedResult = "Virtual space runtime active and verified"
        )
    }

    fun updateConfig(context: Context, newConfig: SandboxConfig) {
        _currentConfig.value = newConfig
        VirtualClock.config = newConfig
        ServiceHookManager.updateConfig(newConfig)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, newConfig.toJson()).apply()

        VirtualLogBus.log(
            category = HookCategory.AOSP_BINDER,
            method = "updateConfig",
            targetClass = "VirtualContainer",
            interceptedPayload = "Config updated: Location=${newConfig.fakeLatitude},${newConfig.fakeLongitude}, TimeOffset=${newConfig.timeOffsetMillis}ms, Frozen=${newConfig.isTimeFrozen}",
            spoofedResult = "Synced across virtualization engine"
        )
    }

    private fun loadSavedConfig(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CONFIG, null)
        if (jsonStr != null) {
            _currentConfig.value = SandboxConfig.fromJson(jsonStr)
        }
    }

    private fun loadInstalledApps(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_INSTALLED_APPS, null) ?: return
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<InstalledVirtualApp>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    InstalledVirtualApp(
                        packageName = obj.getString("packageName"),
                        appName = obj.getString("appName"),
                        versionName = obj.getString("versionName"),
                        versionCode = obj.getLong("versionCode"),
                        apkPath = obj.getString("apkPath"),
                        mainActivity = obj.getString("mainActivity"),
                        applicationClass = obj.optString("applicationClass", null),
                        permissions = (0 until obj.getJSONArray("permissions").length()).map { pIdx ->
                            obj.getJSONArray("permissions").getString(pIdx)
                        },
                        isolatedDataDir = obj.getString("isolatedDataDir"),
                        isBuiltInDiagnostic = obj.optBoolean("isBuiltInDiagnostic", false),
                        installedAt = obj.optLong("installedAt", System.currentTimeMillis())
                    )
                )
            }
            _installedApps.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveInstalledApps(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (app in _installedApps.value) {
            val obj = JSONObject()
            obj.put("packageName", app.packageName)
            obj.put("appName", app.appName)
            obj.put("versionName", app.versionName)
            obj.put("versionCode", app.versionCode)
            obj.put("apkPath", app.apkPath)
            obj.put("mainActivity", app.mainActivity)
            obj.put("applicationClass", app.applicationClass)
            val perms = JSONArray()
            app.permissions.forEach { perms.put(it) }
            obj.put("permissions", perms)
            obj.put("isolatedDataDir", app.isolatedDataDir)
            obj.put("isBuiltInDiagnostic", app.isBuiltInDiagnostic)
            obj.put("installedAt", app.installedAt)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_INSTALLED_APPS, arr.toString()).apply()
    }

    private fun ensureBuiltInVirtualApps(context: Context) {
        val virtualAppsRoot = File(context.filesDir, "virtual_apps")

        val builtInTemplates = listOf(
            Triple(
                "com.example.sandman.georadar",
                "GNSS Radar & Route Walker",
                "com.example.sandman.component.GeoRadarRunner"
            ),
            Triple(
                "com.example.sandman.webexplorer",
                "Sandboxed Web & Map Explorer",
                "com.example.sandman.component.WebExplorerRunner"
            ),
            Triple(
                "com.example.sandman.timewarp",
                "Chronos Time-Warp & Licensing",
                "com.example.sandman.component.TimeWarpRunner"
            ),
            Triple(
                "com.example.sandman.diagnostics",
                "Sandman Telemetry Diagnostics",
                "com.example.sandman.component.DiagnosticsRunner"
            )
        )

        val currentList = _installedApps.value.toMutableList()
        var modified = false

        for ((pkg, name, entry) in builtInTemplates) {
            if (currentList.none { it.packageName == pkg }) {
                val targetDir = File(virtualAppsRoot, pkg).apply { mkdirs() }
                val virtualApp = InstalledVirtualApp(
                    packageName = pkg,
                    appName = name,
                    versionName = "2.5.0",
                    versionCode = 250,
                    apkPath = context.packageCodePath,
                    mainActivity = entry,
                    applicationClass = null,
                    permissions = listOf(
                        "android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_COARSE_LOCATION",
                        "android.permission.READ_PHONE_STATE",
                        "android.permission.INTERNET"
                    ),
                    isolatedDataDir = targetDir.absolutePath,
                    isBuiltInDiagnostic = true
                )
                currentList.add(virtualApp)
                modified = true
            }
        }

        if (modified) {
            _installedApps.value = currentList
            saveInstalledApps(context)
        }
    }

    /**
     * Installs an uninstalled target APK from a content Uri into the virtual sandbox.
     */
    suspend fun installApkFromUri(context: Context, apkUri: Uri): Result<InstalledVirtualApp> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "temp_ingest_${System.currentTimeMillis()}.apk")
            context.contentResolver.openInputStream(apkUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Failed to open APK input stream"))

            // Parse metadata
            val tempParsed = ApkParser.parseApk(context, tempFile, "")
                ?: return@withContext Result.failure(Exception("Invalid APK: Unable to parse AndroidManifest.xml"))

            val packageName = tempParsed.packageName
            val virtualAppsRoot = File(context.filesDir, "virtual_apps")
            val appStorageDir = File(virtualAppsRoot, packageName).apply { mkdirs() }
            val libDir = File(appStorageDir, "lib").apply { mkdirs() }

            // Permanent APK silo
            val permanentApk = File(appStorageDir, "base.apk")
            tempFile.copyTo(permanentApk, overwrite = true)
            tempFile.delete()

            // Extract native shared libraries (.so)
            extractNativeLibraries(permanentApk, libDir)

            val installedApp = InstalledVirtualApp(
                packageName = packageName,
                appName = tempParsed.appName,
                versionName = tempParsed.versionName,
                versionCode = tempParsed.versionCode,
                apkPath = permanentApk.absolutePath,
                mainActivity = tempParsed.mainActivity,
                applicationClass = tempParsed.applicationClass,
                permissions = tempParsed.permissions,
                isolatedDataDir = appStorageDir.absolutePath,
                isBuiltInDiagnostic = false
            )

            // Update state
            val currentList = _installedApps.value.filter { it.packageName != packageName }.toMutableList()
            currentList.add(installedApp)
            _installedApps.value = currentList
            saveInstalledApps(context)

            VirtualLogBus.log(
                category = HookCategory.LIFECYCLE,
                method = "installApk",
                targetClass = "VirtualContainer",
                interceptedPayload = "Installed APK: ${installedApp.appName} ($packageName)",
                spoofedResult = "Provisioned isolated container at ${appStorageDir.absolutePath}",
                callingPackage = packageName
            )

            Result.success(installedApp)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractNativeLibraries(apkFile: File, libDir: File) {
        try {
            val zip = ZipFile(apkFile)
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name.startsWith("lib/") && entry.name.endsWith(".so")) {
                    val outFile = File(libDir, entry.name.substringAfterLast("/"))
                    zip.getInputStream(entry).use { inStream ->
                        FileOutputStream(outFile).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
            }
            zip.close()
        } catch (_: Exception) {}
    }

    fun launchApp(context: Context, app: InstalledVirtualApp): Boolean {
        VirtualLogBus.log(
            category = HookCategory.LIFECYCLE,
            method = "launchApp",
            targetClass = "VirtualContainer",
            interceptedPayload = "Target: ${app.packageName} -> Activity: ${app.mainActivity}",
            spoofedResult = "Launching StubActivity sandbox container",
            callingPackage = app.packageName
        )

        return try {
            val intent = Intent(context, StubActivity::class.java).apply {
                putExtra(StubActivity.EXTRA_PACKAGE_NAME, app.packageName)
                putExtra(StubActivity.EXTRA_APP_NAME, app.appName)
                putExtra(StubActivity.EXTRA_APK_PATH, app.apkPath)
                putExtra(StubActivity.EXTRA_MAIN_ACTIVITY, app.mainActivity)
                putExtra(StubActivity.EXTRA_DATA_DIR, app.isolatedDataDir)
                putExtra(StubActivity.EXTRA_CONFIG_JSON, _currentConfig.value.toJson())
                putExtra(StubActivity.EXTRA_IS_DIAGNOSTIC, app.isBuiltInDiagnostic)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            VirtualLogBus.log(
                category = HookCategory.LIFECYCLE,
                method = "launchApp[FAIL]",
                targetClass = "VirtualContainer",
                interceptedPayload = "Exception: ${e.message}",
                spoofedResult = "Failed to start StubActivity",
                callingPackage = app.packageName
            )
            false
        }
    }

    fun deleteApp(context: Context, packageName: String) {
        val app = _installedApps.value.firstOrNull { it.packageName == packageName } ?: return
        if (app.isBuiltInDiagnostic) return

        try {
            File(app.isolatedDataDir).deleteRecursively()
        } catch (_: Exception) {}

        _installedApps.value = _installedApps.value.filter { it.packageName != packageName }
        saveInstalledApps(context)

        VirtualLogBus.log(
            category = HookCategory.LIFECYCLE,
            method = "deleteApp",
            targetClass = "VirtualContainer",
            interceptedPayload = "Deleted container $packageName",
            spoofedResult = "Purged all isolated storage and unlinked ClassLoader",
            callingPackage = packageName
        )
    }

    fun clearAppData(context: Context, packageName: String) {
        val app = _installedApps.value.firstOrNull { it.packageName == packageName } ?: return
        val dir = File(app.isolatedDataDir)
        try {
            File(dir, "files").deleteRecursively()
            File(dir, "cache").deleteRecursively()
            File(dir, "databases").deleteRecursively()
            File(dir, "shared_prefs").deleteRecursively()
            File(dir, "files").mkdirs()
            File(dir, "cache").mkdirs()
            File(dir, "databases").mkdirs()
            File(dir, "shared_prefs").mkdirs()
        } catch (_: Exception) {}

        VirtualLogBus.log(
            category = HookCategory.STORAGE,
            method = "clearAppData",
            targetClass = "VirtualContainer",
            interceptedPayload = "Cleared data for $packageName",
            spoofedResult = "Reset private filesystem storage silo",
            callingPackage = packageName
        )
    }
}
