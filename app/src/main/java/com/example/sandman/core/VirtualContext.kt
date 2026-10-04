package com.example.sandman.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import com.example.sandman.hooks.ServiceHookManager
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import java.io.File

/**
 * =========================================================================================
 * ARCHITECTURE DEEP-DIVE: VIRTUAL CONTEXT & FILESYSTEM ISOLATION (SANDBOX REDIRECTION)
 * =========================================================================================
 * In standard Android execution, every application has an assigned Linux User ID (UID).
 * The OS Linux kernel enforces file separation: an application with UID 10123 cannot access
 * `/data/data/com.other.app/` without root permission or sharedUserId.
 *
 * The Non-Rooted Virtualization Challenge:
 * In a user-space virtual container, the host application and all hosted sandboxed applications
 * execute under the same host Linux UID (e.g. `u0_a245`). If target apps write to the default
 * `context.getFilesDir()`, they would:
 * 1. Overwrite the host's databases and preferences.
 * 2. Overwrite each other's cache and user data.
 * 3. Violate isolation and crash due to hardcoded path expectations.
 *
 * How Sandman Redefines Storage via ContextWrapper:
 * `VirtualContext` subclasses `android.content.ContextWrapper` and completely overrides:
 * - `getDataDir()` -> `/data/data/<host>/virtual_apps/<targetPackageName>/`
 * - `getFilesDir()` -> `<isolatedDataDir>/files/`
 * - `getCacheDir()` -> `<isolatedDataDir>/cache/`
 * - `getDatabasePath(name)` -> `<isolatedDataDir>/databases/<name>`
 * - `getSharedPreferences(name, mode)` -> isolated XML files prefixed per target package.
 *
 * In addition, `getSystemService(name)` intercepts Android system services (Location,
 * Telephony, Alarms) and returns proxy hooked managers.
 * =========================================================================================
 */
class VirtualContext(
    baseContext: Context,
    val targetPackageName: String,
    val isolatedStorageDir: File,
    private val virtualClassLoader: ClassLoader,
    private val targetResources: Resources? = null,
    private val targetApkPath: String? = null
) : ContextWrapper(baseContext) {

    init {
        if (!isolatedStorageDir.exists()) {
            isolatedStorageDir.mkdirs()
        }
        File(isolatedStorageDir, "files").mkdirs()
        File(isolatedStorageDir, "cache").mkdirs()
        File(isolatedStorageDir, "databases").mkdirs()
        File(isolatedStorageDir, "shared_prefs").mkdirs()

        VirtualLogBus.log(
            category = HookCategory.STORAGE,
            method = "VirtualContext.<init>",
            targetClass = "VirtualContext",
            interceptedPayload = "Target Package: $targetPackageName",
            spoofedResult = "Isolated root created: ${isolatedStorageDir.absolutePath}",
            callingPackage = targetPackageName
        )
    }

    override fun getPackageName(): String {
        return targetPackageName
    }

    override fun getPackageResourcePath(): String {
        return targetApkPath ?: super.getPackageResourcePath()
    }

    override fun getPackageCodePath(): String {
        return targetApkPath ?: super.getPackageCodePath()
    }

    override fun getClassLoader(): ClassLoader {
        return virtualClassLoader
    }

    override fun getResources(): Resources {
        return targetResources ?: super.getResources()
    }

    override fun getAssets(): AssetManager {
        return targetResources?.assets ?: super.getAssets()
    }

    override fun getDataDir(): File {
        VirtualLogBus.log(
            category = HookCategory.STORAGE,
            method = "getDataDir()",
            targetClass = "Context",
            interceptedPayload = "Queried App data root",
            spoofedResult = isolatedStorageDir.absolutePath,
            callingPackage = targetPackageName
        )
        return isolatedStorageDir
    }

    override fun getFilesDir(): File {
        val filesDir = File(isolatedStorageDir, "files")
        if (!filesDir.exists()) filesDir.mkdirs()

        VirtualLogBus.log(
            category = HookCategory.STORAGE,
            method = "getFilesDir()",
            targetClass = "Context",
            interceptedPayload = "Queried internal files directory",
            spoofedResult = filesDir.absolutePath,
            callingPackage = targetPackageName
        )
        return filesDir
    }

    override fun getCacheDir(): File {
        val cacheDir = File(isolatedStorageDir, "cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return cacheDir
    }

    override fun getCodeCacheDir(): File {
        val codeCacheDir = File(isolatedStorageDir, "code_cache")
        if (!codeCacheDir.exists()) codeCacheDir.mkdirs()
        return codeCacheDir
    }

    override fun getDatabasePath(name: String): File {
        val dbDir = File(isolatedStorageDir, "databases")
        if (!dbDir.exists()) dbDir.mkdirs()

        val dbFile = File(dbDir, name)
        VirtualLogBus.log(
            category = HookCategory.STORAGE,
            method = "getDatabasePath('$name')",
            targetClass = "Context",
            interceptedPayload = "Database query: $name",
            spoofedResult = dbFile.absolutePath,
            callingPackage = targetPackageName
        )
        return dbFile
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        // Redefine shared preferences so target apps do not overwrite host settings
        val isolatedPrefName = "sandbox_${targetPackageName}_$name"
        VirtualLogBus.log(
            category = HookCategory.STORAGE,
            method = "getSharedPreferences('$name')",
            targetClass = "Context",
            interceptedPayload = "Accessing SharedPreferences: $name",
            spoofedResult = "Redirected to host sandbox namespace: $isolatedPrefName",
            callingPackage = targetPackageName
        )
        return super.getSharedPreferences(isolatedPrefName, mode)
    }

    override fun getApplicationInfo(): ApplicationInfo {
        val info = super.getApplicationInfo()
        val copy = ApplicationInfo(info)
        copy.packageName = targetPackageName
        copy.dataDir = isolatedStorageDir.absolutePath
        if (targetApkPath != null) {
            copy.sourceDir = targetApkPath
            copy.publicSourceDir = targetApkPath
        }
        return copy
    }

    override fun getSystemService(name: String): Any? {
        val baseService = super.getSystemService(name)
        return ServiceHookManager.interceptSystemService(name, baseService, this)
    }
}
