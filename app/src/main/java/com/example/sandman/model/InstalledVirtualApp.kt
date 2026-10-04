package com.example.sandman.model

data class InstalledVirtualApp(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val apkPath: String,
    val mainActivity: String,
    val applicationClass: String? = null,
    val permissions: List<String> = emptyList(),
    val isolatedDataDir: String,
    val isBuiltInDiagnostic: Boolean = false,
    val installedAt: Long = System.currentTimeMillis()
)
