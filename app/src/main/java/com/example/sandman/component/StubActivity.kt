package com.example.sandman.component

import android.app.Activity
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sandman.core.VirtualClassLoader
import com.example.sandman.core.VirtualContext
import com.example.sandman.hooks.LocationProxyHandler
import com.example.sandman.hooks.ServiceHookManager
import com.example.sandman.hooks.TimeProxyHandler
import com.example.sandman.hooks.VirtualClock
import com.example.sandman.inspector.ApkParser
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import com.example.sandman.model.HookLogEntry
import com.example.sandman.model.SandboxConfig
import com.example.sandman.ui.theme.SandmanTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * =========================================================================================
 * ARCHITECTURE DEEP-DIVE: STUB ACTIVITY (HOST CONTAINER SHELL)
 * =========================================================================================
 * In standard Android execution, the `ActivityManagerService` (AMS) and `WindowManagerService` (WMS)
 * reject any Activity launch whose `<activity>` tag is not explicitly declared in the host
 * `AndroidManifest.xml`. If an uninstalled third-party APK tries to launch its own Activity,
 * the OS throws `ActivityNotFoundException`.
 *
 * The Non-Rooted Stub Shell Solution:
 * 1. Sandman declares `StubActivity` in `AndroidManifest.xml` running in `:sandbox_env`.
 * 2. When launching any sandboxed app, AMS starts `StubActivity` normally.
 * 3. Inside `StubActivity.onCreate()`:
 *    - We initialize the `VirtualClassLoader` targeting the sandboxed APK.
 *    - We construct the `VirtualContext` wrapping this Activity and storage paths.
 *    - We activate the dynamic service proxies (Location, Time, Telephony).
 *    - We load the sandboxed component's classes or run its entry point with hooked environment.
 *    - We provide a live telemetry monitor showing active interception metrics.
 * =========================================================================================
 */
class StubActivity : ComponentActivity() {

    private lateinit var virtualContext: VirtualContext
    private lateinit var virtualClassLoader: VirtualClassLoader
    private var targetPackage: String = ""
    private var apkPath: String = ""
    private var mainActivityClass: String = ""
    private var isDiagnostic: Boolean = false
    private var sandboxConfig: SandboxConfig = SandboxConfig()

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_target_package"
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_MAIN_ACTIVITY = "extra_main_activity"
        const val EXTRA_DATA_DIR = "extra_data_dir"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_IS_DIAGNOSTIC = "extra_is_diagnostic"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "com.example.sandman.diagnostics"
        apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: packageCodePath
        mainActivityClass = intent.getStringExtra(EXTRA_MAIN_ACTIVITY) ?: "MainActivity"
        val dataDirPath = intent.getStringExtra(EXTRA_DATA_DIR) ?: File(filesDir, "virtual_apps/$targetPackage").absolutePath
        val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
        isDiagnostic = intent.getBooleanExtra(EXTRA_IS_DIAGNOSTIC, false)

        if (configJson != null) {
            sandboxConfig = SandboxConfig.fromJson(configJson)
        }

        // 1. Initialize Log Bus in this process
        VirtualLogBus.initialize(this)

        // 2. Initialize ServiceHookManager in :sandbox_env
        ServiceHookManager.updateConfig(sandboxConfig)
        ServiceHookManager.installServiceManagerHooks(this)

        // 3. Construct VirtualClassLoader
        val nativeLibDir = File(dataDirPath, "lib").absolutePath
        virtualClassLoader = VirtualClassLoader(
            dexPath = apkPath,
            librarySearchPath = nativeLibDir,
            parentClassLoader = classLoader,
            targetPackageName = targetPackage
        )

        // 4. Construct VirtualContext
        val targetResources = if (!isDiagnostic) ApkParser.createTargetResources(this, apkPath) else resources
        virtualContext = VirtualContext(
            baseContext = this,
            targetPackageName = targetPackage,
            isolatedStorageDir = File(dataDirPath),
            virtualClassLoader = virtualClassLoader,
            targetResources = targetResources,
            targetApkPath = apkPath
        )

        VirtualLogBus.log(
            category = HookCategory.LIFECYCLE,
            method = "StubActivity.onCreate",
            targetClass = "StubActivity",
            interceptedPayload = "Target: $targetPackage, NativeLibDir: $nativeLibDir",
            spoofedResult = "Initialized sandboxed execution container in process :sandbox_env",
            callingPackage = targetPackage
        )

        // Attempt reflective class load of target Activity
        if (!isDiagnostic) {
            try {
                val clazz = virtualClassLoader.loadClass(mainActivityClass)
                VirtualLogBus.log(
                    category = HookCategory.CLASSLOADER,
                    method = "loadTargetActivity",
                    targetClass = mainActivityClass,
                    interceptedPayload = "Reflective resolution of entry activity",
                    spoofedResult = "Loaded class successfully: ${clazz.name}",
                    callingPackage = targetPackage
                )
            } catch (e: Exception) {
                VirtualLogBus.log(
                    category = HookCategory.CLASSLOADER,
                    method = "loadTargetActivity[FAIL]",
                    targetClass = mainActivityClass,
                    interceptedPayload = "Error: ${e.message}",
                    spoofedResult = "Fallback to Sandman Virtual Container Shell",
                    callingPackage = targetPackage
                )
            }
        }

        // Render Sandboxed Environment UI
        setContent {
            SandmanTheme {
                SandboxedRuntimeScreen(
                    targetPackage = targetPackage,
                    apkPath = apkPath,
                    mainActivityClass = mainActivityClass,
                    virtualContext = virtualContext,
                    config = sandboxConfig,
                    onExit = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxedRuntimeScreen(
    targetPackage: String,
    apkPath: String,
    mainActivityClass: String,
    virtualContext: VirtualContext,
    config: SandboxConfig,
    onExit: () -> Unit
) {
    var lastObservedLocation by remember { mutableStateOf("Querying...") }
    var lastObservedTime by remember { mutableStateOf("Querying...") }
    var lastObservedTelephony by remember { mutableStateOf("Querying...") }
    var lastObservedStorage by remember { mutableStateOf("Checking...") }
    var storageWriteSuccess by remember { mutableStateOf<String?>(null) }
    val logs by VirtualLogBus.logsFlow.collectAsState()

    // Test hooked services using the virtualContext
    fun pollLocation() {
        try {
            val lm = virtualContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc != null) {
                lastObservedLocation = "Lat: ${String.format(Locale.US, "%.5f", loc.latitude)}° | Lng: ${String.format(Locale.US, "%.5f", loc.longitude)}° (Acc: ${loc.accuracy}m, Alt: ${loc.altitude}m)"
            } else {
                lastObservedLocation = "No fix returned from provider"
            }
        } catch (e: Exception) {
            lastObservedLocation = "Error: ${e.message}"
        }
    }

    fun pollTime() {
        val virtualMillis = VirtualClock.currentTimeMillis()
        val realMillis = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val statusTag = if (config.isTimeFrozen) "[FROZEN / FIXED TIME]" else "[TICKING DRIFT]"
        lastObservedTime = "$statusTag\nVirtual: ${sdf.format(Date(virtualMillis))}\nHost:    ${sdf.format(Date(realMillis))}"
    }

    fun pollTelephony() {
        try {
            val tm = virtualContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val carrier = tm?.simOperatorName ?: config.spoofedCarrier
            lastObservedTelephony = "Carrier: $carrier\nIMEI: ${config.spoofedImei}\nNet: ${config.spoofedNetworkType} | ISO: ${config.spoofedCountryIso.uppercase()}"
        } catch (e: Exception) {
            lastObservedTelephony = "Error: ${e.message}"
        }
    }

    fun testStorageWrite() {
        try {
            val file = File(virtualContext.filesDir, "sandbox_write_test_${System.currentTimeMillis()}.txt")
            file.writeText("Sandman isolated sandbox telemetry write test at ${System.currentTimeMillis()}")
            storageWriteSuccess = "Verified: ${file.name} written to isolated silo"
            lastObservedStorage = "Path: ${virtualContext.filesDir.absolutePath}\nItems in dir: ${virtualContext.filesDir.listFiles()?.size ?: 0}"
        } catch (e: Exception) {
            storageWriteSuccess = "Write failed: ${e.message}"
        }
    }

    LaunchedEffect(Unit) {
        pollLocation()
        pollTime()
        pollTelephony()
        testStorageWrite()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Virtual Container Active",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00F5D4)
                        )
                        Text(
                            text = "Process :sandbox_env | $targetPackage",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit, modifier = Modifier.testTag("exit_sandbox_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Exit Sandbox"
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = {
                            pollLocation()
                            pollTime()
                            pollTelephony()
                            testStorageWrite()
                        },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("refresh_telemetry_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Probe", fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D1B2A),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF080F1A)
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF142438)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF00F5D4))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Container Isolation Status",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Badge(containerColor = Color(0xFF00F5D4)) {
                                Text("SECURE", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Target Entry: $mainActivityClass",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF8E9AAF)
                        )
                        Text(
                            text = "Sandbox ClassLoader: dalvik.system.PathClassLoader",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF8E9AAF)
                        )
                    }
                }
            }

            // Location Spoof Card
            item {
                TelemetryCard(
                    icon = Icons.Default.LocationOn,
                    title = "LocationManager Hook",
                    status = if (config.spoofLocationEnabled) "SPOOFED (ACTIVE)" else "REAL GPS PASSTHROUGH",
                    statusColor = if (config.spoofLocationEnabled) Color(0xFF00F5D4) else Color(0xFFE0A96D),
                    detail = lastObservedLocation,
                    actionText = "Poll GPS",
                    onAction = { pollLocation() }
                )
            }

            // Time Spoof Card
            item {
                TelemetryCard(
                    icon = Icons.Default.Schedule,
                    title = "Temporal Drift & SystemClock",
                    status = if (config.spoofTimeEnabled) (if (config.isTimeFrozen) "PINNED STATIC TIME" else "TEMPORAL SHIFT ACTIVE") else "HARDWARE RTC",
                    statusColor = if (config.spoofTimeEnabled) (if (config.isTimeFrozen) Color(0xFFFF0054) else Color(0xFF70E000)) else Color(0xFFE0A96D),
                    detail = lastObservedTime,
                    actionText = "Poll Time",
                    onAction = { pollTime() }
                )
            }

            // Telephony Spoof Card
            item {
                TelemetryCard(
                    icon = Icons.Default.PhoneAndroid,
                    title = "TelephonyManager / ITelephony",
                    status = if (config.spoofTelephonyEnabled) "VIRTUAL CARRIER ACTIVE" else "REAL SIM",
                    statusColor = if (config.spoofTelephonyEnabled) Color(0xFF38B000) else Color(0xFFE0A96D),
                    detail = lastObservedTelephony,
                    actionText = "Query SIM",
                    onAction = { pollTelephony() }
                )
            }

            // Storage Isolation Card
            item {
                TelemetryCard(
                    icon = Icons.Default.Folder,
                    title = "Filesystem Sandboxing",
                    status = "REDIRECTED TO PRIVATE SILO",
                    statusColor = Color(0xFF00F5D4),
                    detail = lastObservedStorage + (storageWriteSuccess?.let { "\n$it" } ?: ""),
                    actionText = "Write Test",
                    onAction = { testStorageWrite() }
                )
            }

            // Interception Log Feed Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF00F5D4), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Real-Time Interceptor Stream (${logs.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Logs
            items(logs.take(15)) { entry ->
                LogItemMini(entry)
            }
        }
    }
}

@Composable
fun TelemetryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    status: String,
    statusColor: Color,
    detail: String,
    actionText: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF142438)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.weight(1f))
                Text(status, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = statusColor)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF080F1A), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = detail,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFCBD5E1)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(actionText, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun LogItemMini(entry: HookLogEntry) {
    val tagColor = when (entry.category) {
        HookCategory.LOCATION -> Color(0xFF00F5D4)
        HookCategory.TIME -> Color(0xFF70E000)
        HookCategory.TELEPHONY -> Color(0xFFFFB703)
        HookCategory.STORAGE -> Color(0xFF48CAE4)
        HookCategory.CLASSLOADER -> Color(0xFFB5179E)
        HookCategory.LIFECYCLE -> Color(0xFFFF0054)
        HookCategory.AOSP_BINDER -> Color(0xFF9D4EDD)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1B2A), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF1B2E4B), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "[${entry.category.name}]",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = tagColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = entry.method,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = entry.formattedTime,
                    fontSize = 9.sp,
                    color = Color(0xFF64748B)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "-> ${entry.spoofedResult}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFCBD5E1)
            )
        }
    }
}
