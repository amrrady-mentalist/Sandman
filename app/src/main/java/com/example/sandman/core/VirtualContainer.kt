package com.example.sandman.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.sandman.component.StubActivity
import com.example.sandman.hooks.ServiceHookManager
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
 * ARCHITECTURE DEEP-DIVE: VIRTUAL CONTAINER ENGINE
 * =========================================================================================
 * The `VirtualContainer` is the central orchestration unit of Sandman.
 * It manages the lifecycle of sandboxed applications without host OS installation:
 *
 * 1. APK Ingestion & Storage Provisioning:
 *    - Copies incoming APK to `/data/data/<host>/virtual_apps/<package_name>/base.apk`.
 *    - Extracts native `.so` dynamic libraries to `<package_name>/lib/<abi>/`.
 *    - Initializes private storage silos (`files`, `cache`, `databases`, `shared_prefs`).
 *
 * 2. Component Reflection & Manifest Analysis:
 *    - Reads application class names, launcher activity, and permissions without root.
 *
 * 3. Execution Pipeline:
 *    - Loads bytecode into `VirtualClassLoader`.
 *    - Hooks AOSP ServiceManager and framework binders via `ServiceHookManager`.
 *    - Dispatches execution to `StubActivity` shell.
 * =========================================================================================
 */
object VirtualContainer {

    private const val PREFS_NAME = "sandman_virtual_container_prefs"
    private const val KEY_INSTALLED_APPS = "installed_virtual_apps"
    private const val KEY_CONFIG = "sandbox_config"

    private val _installedApps = MutableStateFlow<List<InstalledVirtualApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledVirtualApp>> = _installedApps.asStateFlow()

    private val _currentConfig = MutableStateFlow(SandboxConfig())
    val currentConfig: StateFlow<SandboxConfig> = _currentConfig.asStateFlow()

    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true

        VirtualLogBus.initialize(context)
        loadSavedConfig(context)
        loadInstalledApps(context)

        // Install system hooks in current process
        ServiceHookManager.updateConfig(_currentConfig.value)
        ServiceHookManager.installServiceManagerHooks(context)

        // Ensure built-in diagnostic target is present
        ensureBuiltInDiagnosticApp(context)
    }

    fun updateConfig(context: Context, newConfig: SandboxConfig) {
        _currentConfig.value = newConfig
        ServiceHookManager.updateConfig(newConfig)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, newConfig.toJson()).apply()

        VirtualLogBus.log(
            category = HookCategory.AOSP_BINDER,
            method = "updateConfig",
            targetClass = "VirtualContainer",
            interceptedPayload = "Config updated: Location=${newConfig.fakeLatitude},${newConfig.fakeLongitude}, TimeOffset=${newConfig.timeOffsetMillis}ms",
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

    private fun ensureBuiltInDiagnosticApp(context: Context) {
        val pkg = "com.example.sandman.diagnostics"
        val existing = _installedApps.value.firstOrNull { it.packageName == pkg }
        if (existing == null) {
            val virtualAppsRoot = File(context.filesDir, "virtual_apps")
            val targetDir = File(virtualAppsRoot, pkg).apply { mkdirs() }

            val diagnosticApp = InstalledVirtualApp(
                packageName = pkg,
                appName = "Sandman Telemetry Diagnostics",
                versionName = "2.4.0",
                versionCode = 240,
                apkPath = context.packageCodePath, // Uses host APK code path
                mainActivity = "com.example.sandman.component.DiagnosticsRunner",
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

            _installedApps.value = listOf(diagnosticApp) + _installedApps.value
            saveInstalledApps(context)
        }
    }

    /**
     * Installs an uninstalled target APK from a content Uri (via File Picker) into the virtual sandbox.
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
            val finalApkFile = File(appStorageDir, "base.apk")

            tempFile.copyTo(finalApkFile, overwrite = true)
            tempFile.delete()

            // Extract native .so libraries if present
            val nativeLibDir = File(appStorageDir, "lib").apply { mkdirs() }
            extractNativeLibraries(finalApkFile, nativeLibDir)

            // Re-parse with permanent storage path
            val installedApp = ApkParser.parseApk(context, finalApkFile, appStorageDir.absolutePath)
                ?: return@withContext Result.failure(Exception("Failed to finalize installed virtual app"))

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

    fun launchApp(context: Context, app: InstalledVirtualApp) {
        VirtualLogBus.log(
            category = HookCategory.LIFECYCLE,
            method = "launchApp",
            targetClass = "VirtualContainer",
            interceptedPayload = "Target: ${app.packageName} -> Activity: ${app.mainActivity}",
            spoofedResult = "Dispatching to StubActivity in process :sandbox_env",
            callingPackage = app.packageName
        )

        val intent = Intent(context, StubActivity::class.java).apply {
            putExtra(StubActivity.EXTRA_PACKAGE_NAME, app.packageName)
            putExtra(StubActivity.EXTRA_APK_PATH, app.apkPath)
            putExtra(StubActivity.EXTRA_MAIN_ACTIVITY, app.mainActivity)
            putExtra(StubActivity.EXTRA_DATA_DIR, app.isolatedDataDir)
            putExtra(StubActivity.EXTRA_CONFIG_JSON, _currentConfig.value.toJson())
            putExtra(StubActivity.EXTRA_IS_DIAGNOSTIC, app.isBuiltInDiagnostic)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
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
            category = HookCategory.STORAGE,
            method = "deleteApp",
            targetClass = "VirtualContainer",
            interceptedPayload = "Purged sandbox storage for $packageName",
            spoofedResult = "Isolated directory removed",
            callingPackage = packageName
        )
    }

    fun clearAppData(context: Context, packageName: String) {
        val app = _installedApps.value.firstOrNull { it.packageName == packageName } ?: return
        val root = File(app.isolatedDataDir)
        File(root, "files").deleteRecursively()
        File(root, "cache").deleteRecursively()
        File(root, "databases").deleteRecursively()
        File(root, "files").mkdirs()
        File(root, "cache").mkdirs()
        File(root, "databases").mkdirs()

        VirtualLogBus.log(
            category = HookCategory.STORAGE,
            method = "clearAppData",
            targetClass = "VirtualContainer",
            interceptedPayload = "Cleared sandbox cache and databases for $packageName",
            spoofedResult = "Storage reset to clean state",
            callingPackage = packageName
        )
    }
}
