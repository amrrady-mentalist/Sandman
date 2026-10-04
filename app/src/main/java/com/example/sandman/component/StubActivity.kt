package com.example.sandman.component

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.telephony.TelephonyManager
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.sandman.core.VirtualClassLoader
import com.example.sandman.core.VirtualContainer
import com.example.sandman.core.VirtualContext
import com.example.sandman.hooks.ServiceHookManager
import com.example.sandman.hooks.VirtualClock
import com.example.sandman.inspector.ApkParser
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import com.example.sandman.model.HookLogEntry
import com.example.sandman.model.SandboxConfig
import com.example.sandman.ui.theme.SandmanTheme
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StubActivity : ComponentActivity() {

    private lateinit var virtualContext: VirtualContext
    private lateinit var virtualClassLoader: ClassLoader
    private var targetPackage: String = ""
    private var appName: String = "Virtual Container"
    private var apkPath: String = ""
    private var mainActivityClass: String = ""
    private var isDiagnostic: Boolean = false
    private var sandboxConfig: SandboxConfig = SandboxConfig()
    private var initError: String? = null

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_target_package"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_APK_PATH = "extra_apk_path"
        const val EXTRA_MAIN_ACTIVITY = "extra_main_activity"
        const val EXTRA_DATA_DIR = "extra_data_dir"
        const val EXTRA_CONFIG_JSON = "extra_config_json"
        const val EXTRA_IS_DIAGNOSTIC = "extra_is_diagnostic"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "com.example.sandman.georadar"
            appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "Sandman Virtual App"
            apkPath = intent.getStringExtra(EXTRA_APK_PATH) ?: packageCodePath
            mainActivityClass = intent.getStringExtra(EXTRA_MAIN_ACTIVITY) ?: "MainActivity"
            val dataDirPath = intent.getStringExtra(EXTRA_DATA_DIR)
                ?: File(filesDir, "virtual_apps/$targetPackage").absolutePath
            val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON)
            isDiagnostic = intent.getBooleanExtra(EXTRA_IS_DIAGNOSTIC, false)

            if (configJson != null) {
                sandboxConfig = SandboxConfig.fromJson(configJson)
            }

            // 1. Initialize Log Bus
            VirtualLogBus.initialize(this)

            // 2. Initialize ServiceHookManager
            try {
                ServiceHookManager.updateConfig(sandboxConfig)
                ServiceHookManager.installServiceManagerHooks(this)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 3. Construct VirtualClassLoader safely
            val isolatedDir = File(dataDirPath).apply { mkdirs() }
            val nativeLibDir = File(isolatedDir, "lib").apply { mkdirs() }.absolutePath

            virtualClassLoader = if (!isDiagnostic && File(apkPath).exists()) {
                try {
                    VirtualClassLoader(
                        dexPath = apkPath,
                        librarySearchPath = nativeLibDir,
                        parentClassLoader = classLoader,
                        targetPackageName = targetPackage
                    )
                } catch (e: Exception) {
                    classLoader
                }
            } else {
                classLoader
            }

            // 4. Construct VirtualContext safely
            val targetResources: Resources = if (!isDiagnostic && File(apkPath).exists()) {
                try {
                    ApkParser.createTargetResources(this, apkPath) ?: resources
                } catch (_: Exception) {
                    resources
                }
            } else {
                resources
            }

            virtualContext = VirtualContext(
                baseContext = this,
                targetPackageName = targetPackage,
                isolatedStorageDir = isolatedDir,
                virtualClassLoader = virtualClassLoader,
                targetResources = targetResources,
                targetApkPath = apkPath
            )

            VirtualLogBus.log(
                category = HookCategory.LIFECYCLE,
                method = "StubActivity.onCreate",
                targetClass = "StubActivity",
                interceptedPayload = "Target: $targetPackage, Name: $appName",
                spoofedResult = "Interactive container active with live spoofing overlay",
                callingPackage = targetPackage
            )
        } catch (e: Exception) {
            initError = e.message ?: "Initialization exception: $e"
            virtualContext = VirtualContext(
                baseContext = this,
                targetPackageName = targetPackage.ifBlank { "com.example.sandman.georadar" },
                isolatedStorageDir = File(filesDir, "virtual_apps/fallback").apply { mkdirs() },
                virtualClassLoader = classLoader,
                targetResources = resources
            )
        }

        setContent {
            SandmanTheme {
                SandboxedRuntimeScreen(
                    targetPackage = targetPackage,
                    appName = appName,
                    apkPath = apkPath,
                    mainActivityClass = mainActivityClass,
                    virtualContext = virtualContext,
                    initialConfig = sandboxConfig,
                    initError = initError,
                    onExit = { finish() }
                )
            }
        }
    }
}

enum class SandboxTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    INTERACTIVE("App UI", Icons.Default.PlayCircle),
    TELEMETRY("Telemetry", Icons.Default.MonitorHeart),
    INTERCEPTOR("Audit Log", Icons.Default.Terminal)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxedRuntimeScreen(
    targetPackage: String,
    appName: String,
    apkPath: String,
    mainActivityClass: String,
    virtualContext: VirtualContext,
    initialConfig: SandboxConfig,
    initError: String? = null,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(SandboxTab.INTERACTIVE) }
    var config by remember { mutableStateOf(initialConfig) }
    var showQuickTweakSheet by remember { mutableStateOf(false) }

    // Live Telemetry states
    var lastObservedLocation by remember { mutableStateOf("Querying...") }
    var lastObservedTime by remember { mutableStateOf("Querying...") }
    var lastObservedTelephony by remember { mutableStateOf("Querying...") }
    var lastObservedStorage by remember { mutableStateOf("Checking...") }
    var storageWriteSuccess by remember { mutableStateOf<String?>(null) }
    val logs by VirtualLogBus.logsFlow.collectAsState()

    fun refreshTelemetry() {
        try {
            val lm = virtualContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            lastObservedLocation = if (loc != null) {
                "Lat: ${String.format(Locale.US, "%.5f", loc.latitude)}° | Lng: ${String.format(Locale.US, "%.5f", loc.longitude)}° (Acc: ${loc.accuracy}m, Alt: ${loc.altitude}m)"
            } else {
                "Lat: ${config.fakeLatitude}°, Lng: ${config.fakeLongitude}°"
            }
        } catch (e: Exception) {
            lastObservedLocation = "Lat: ${config.fakeLatitude}°, Lng: ${config.fakeLongitude}° (${e.message})"
        }

        try {
            val virtualMillis = VirtualClock.currentTimeMillis()
            val realMillis = System.currentTimeMillis()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            val statusTag = if (config.isTimeFrozen) "[FROZEN / FIXED TIME]" else "[TICKING DRIFT]"
            lastObservedTime = "$statusTag\nVirtual: ${sdf.format(Date(virtualMillis))}\nHost:    ${sdf.format(Date(realMillis))}"
        } catch (e: Exception) {
            lastObservedTime = "Error: ${e.message}"
        }

        try {
            val tm = virtualContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val carrier = tm?.simOperatorName ?: config.spoofedCarrier
            lastObservedTelephony = "Carrier: $carrier\nIMEI: ${config.spoofedImei}\nNet: ${config.spoofedNetworkType} | ISO: ${config.spoofedCountryIso.uppercase()}"
        } catch (_: Exception) {
            lastObservedTelephony = "Carrier: ${config.spoofedCarrier}\nIMEI: ${config.spoofedImei}"
        }

        try {
            lastObservedStorage = "Path: ${virtualContext.filesDir.absolutePath}\nItems: ${virtualContext.filesDir.listFiles()?.size ?: 0}"
        } catch (_: Exception) {}
    }

    LaunchedEffect(config) {
        refreshTelemetry()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = appName.take(20),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = Color(0xFF00F5D4).copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "SANDBOXED",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF00F5D4),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = if (config.spoofLocationEnabled)
                                "GPS: ${String.format(Locale.US, "%.2f", config.fakeLatitude)}°, ${String.format(Locale.US, "%.2f", config.fakeLongitude)}° • ${if (config.isTimeFrozen) "Time Frozen" else "Drift"}"
                            else "Hardware Pass-through",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF00F5D4)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit, modifier = Modifier.testTag("exit_sandbox_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Exit Sandbox",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { showQuickTweakSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F5D4)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .padding(end = 8.dp)
                            .testTag("quick_tweak_hud_button")
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFF080F1A), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tweak", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D1B2A),
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0D1B2A),
                tonalElevation = 8.dp
            ) {
                SandboxTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title, fontSize = 11.sp, fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF080F1A),
                            selectedTextColor = Color(0xFF00F5D4),
                            indicatorColor = Color(0xFF00F5D4),
                            unselectedIconColor = Color(0xFF64748B),
                            unselectedTextColor = Color(0xFF64748B)
                        )
                    )
                }
            }
        },
        containerColor = Color(0xFF080F1A)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                SandboxTab.INTERACTIVE -> {
                    // Render dedicated interactive app based on target
                    when (targetPackage) {
                        "com.example.sandman.georadar" -> {
                            GeoRadarView(
                                config = config,
                                onUpdateLocation = { newLat, newLng ->
                                    val updated = config.copy(fakeLatitude = newLat, fakeLongitude = newLng)
                                    config = updated
                                    VirtualContainer.updateConfig(context, updated)
                                }
                            )
                        }
                        "com.example.sandman.webexplorer" -> {
                            WebExplorerView(
                                virtualContext = virtualContext,
                                config = config
                            )
                        }
                        "com.example.sandman.timewarp" -> {
                            TimeWarpView(
                                config = config,
                                onUpdateConfig = { updated ->
                                    config = updated
                                    VirtualContainer.updateConfig(context, updated)
                                }
                            )
                        }
                        else -> {
                            // Target or third-party APK interactive shell
                            ApkHostView(
                                targetPackage = targetPackage,
                                appName = appName,
                                apkPath = apkPath,
                                virtualContext = virtualContext,
                                config = config,
                                onUpdateConfig = { updated ->
                                    config = updated
                                    VirtualContainer.updateConfig(context, updated)
                                }
                            )
                        }
                    }
                }

                SandboxTab.TELEMETRY -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (initError != null) {
                            item {
                                Surface(
                                    color = Color(0xFF38101C),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF0054)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF0054))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = "Safe Fallback: $initError", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        item {
                            TelemetryCard(
                                icon = Icons.Default.LocationOn,
                                title = "LocationManager Hook",
                                status = if (config.spoofLocationEnabled) "SPOOFED (ACTIVE)" else "REAL GPS PASSTHROUGH",
                                statusColor = if (config.spoofLocationEnabled) Color(0xFF00F5D4) else Color(0xFFE0A96D),
                                detail = lastObservedLocation,
                                actionText = "Poll GPS",
                                onAction = { refreshTelemetry() }
                            )
                        }

                        item {
                            TelemetryCard(
                                icon = Icons.Default.Schedule,
                                title = "Temporal Drift & SystemClock",
                                status = if (config.spoofTimeEnabled) (if (config.isTimeFrozen) "PINNED STATIC TIME" else "TEMPORAL SHIFT ACTIVE") else "HARDWARE RTC",
                                statusColor = if (config.spoofTimeEnabled) (if (config.isTimeFrozen) Color(0xFFFF0054) else Color(0xFF70E000)) else Color(0xFFE0A96D),
                                detail = lastObservedTime,
                                actionText = "Poll Time",
                                onAction = { refreshTelemetry() }
                            )
                        }

                        item {
                            TelemetryCard(
                                icon = Icons.Default.PhoneAndroid,
                                title = "TelephonyManager / ITelephony",
                                status = if (config.spoofTelephonyEnabled) "VIRTUAL CARRIER ACTIVE" else "REAL SIM",
                                statusColor = if (config.spoofTelephonyEnabled) Color(0xFF38B000) else Color(0xFFE0A96D),
                                detail = lastObservedTelephony,
                                actionText = "Query SIM",
                                onAction = { refreshTelemetry() }
                            )
                        }

                        item {
                            TelemetryCard(
                                icon = Icons.Default.Folder,
                                title = "Filesystem Sandboxing",
                                status = "REDIRECTED TO PRIVATE SILO",
                                statusColor = Color(0xFF00F5D4),
                                detail = lastObservedStorage + (storageWriteSuccess?.let { "\n$it" } ?: ""),
                                actionText = "Write Test",
                                onAction = {
                                    try {
                                        val file = File(virtualContext.filesDir, "write_probe_${System.currentTimeMillis()}.txt")
                                        file.writeText("Sandman isolated file probe at ${Date()}")
                                        storageWriteSuccess = "Verified: ${file.name} written successfully"
                                        refreshTelemetry()
                                    } catch (e: Exception) {
                                        storageWriteSuccess = "Failed: ${e.message}"
                                    }
                                }
                            )
                        }
                    }
                }

                SandboxTab.INTERCEPTOR -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Text(
                                "Live Binder & System Interception Feed (${logs.size})",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Captures every system call made by this container in real-time.",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (logs.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("No intercepts yet. Interact with the app to generate traffic.", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }

                        items(logs.take(30)) { entry ->
                            LogItemMini(entry)
                        }
                    }
                }
            }

            // Quick Tweak HUD Modal Bottom Sheet
            if (showQuickTweakSheet) {
                QuickTweakBottomSheet(
                    config = config,
                    onDismiss = { showQuickTweakSheet = false },
                    onApply = { updated ->
                        config = updated
                        VirtualContainer.updateConfig(context, updated)
                        showQuickTweakSheet = false
                    }
                )
            }
        }
    }
}

/**
 * 1. INTERACTIVE GNSS RADAR & ROUTE WALKER
 */
@Composable
fun GeoRadarView(
    config: SandboxConfig,
    onUpdateLocation: (Double, Double) -> Unit
) {
    var isWalking by remember { mutableStateOf(false) }
    var stepCount by remember { mutableIntStateOf(0) }

    // Walking simulator ticker
    LaunchedEffect(isWalking) {
        if (isWalking) {
            while (isWalking) {
                delay(1500)
                // Nudge position along heading
                val step = 0.0003
                val newLat = config.fakeLatitude + step * 0.7
                val newLng = config.fakeLongitude + step * 0.7
                stepCount++
                onUpdateLocation(newLat, newLng)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            // Radar Visual Box
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF0B1928))
                    .border(2.dp, Color(0xFF00F5D4), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Concentric circles
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .border(1.dp, Color(0xFF1B3A4B), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .border(1.dp, Color(0xFF1B3A4B), CircleShape)
                )

                // Crosshairs
                Divider(modifier = Modifier.fillMaxWidth().height(1.dp), color = Color(0xFF1B3A4B))
                Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = Color(0xFF1B3A4B))

                // GPS Target Pin
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription = "User Position",
                        tint = Color(0xFF00F5D4),
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "SPOOFED FIX",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00F5D4)
                    )
                }
            }
        }

        // Live Coordinate Stream Box
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF142438)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GpsFixed, contentDescription = null, tint = Color(0xFF00F5D4), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Active Synthetic Fix Stream", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Text("MOCK BIT: 0 (STEALTH)", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF70E000))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("LATITUDE", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Text(String.format(Locale.US, "%.5f°", config.fakeLatitude), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF00F5D4), fontSize = 14.sp)
                        }
                        Column {
                            Text("LONGITUDE", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Text(String.format(Locale.US, "%.5f°", config.fakeLongitude), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF00F5D4), fontSize = 14.sp)
                        }
                        Column {
                            Text("ACCURACY", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                            Text("${config.fakeAccuracy}m", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // Joystick Controls
        item {
            Text("Interactive Movement Joystick", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
            Text("Move the synthetic GPS fix in real-time within the container", color = Color.Gray, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // North
                JoystickButton("▲ North") {
                    onUpdateLocation(config.fakeLatitude + 0.0005, config.fakeLongitude)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // West
                    JoystickButton("◄ West") {
                        onUpdateLocation(config.fakeLatitude, config.fakeLongitude - 0.0005)
                    }
                    // Center Walk Toggle
                    Button(
                        onClick = { isWalking = !isWalking },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isWalking) Color(0xFFFF0054) else Color(0xFF00F5D4)
                        ),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(if (isWalking) Icons.Default.Pause else Icons.Default.DirectionsWalk, contentDescription = null, tint = Color(0xFF080F1A))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (isWalking) "Stop Walk" else "Auto-Walk", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    // East
                    JoystickButton("East ►") {
                        onUpdateLocation(config.fakeLatitude, config.fakeLongitude + 0.0005)
                    }
                }
                // South
                JoystickButton("▼ South") {
                    onUpdateLocation(config.fakeLatitude - 0.0005, config.fakeLongitude)
                }
            }
        }
    }
}

@Composable
fun JoystickButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF142438)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.width(110.dp).height(42.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 2. INTERACTIVE SANDBOXED WEB & MAP EXPLORER
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebExplorerView(
    virtualContext: VirtualContext,
    config: SandboxConfig
) {
    var urlInput by remember { mutableStateOf("https://browserleaks.com/geo") }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentWebUrl by remember { mutableStateOf("https://browserleaks.com/geo") }

    Column(modifier = Modifier.fillMaxSize()) {
        // Address Bar
        Surface(
            color = Color(0xFF0D1B2A),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { webViewInstance?.reload() }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                }

                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).height(48.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00F5D4),
                        unfocusedBorderColor = Color(0xFF1E3A5F)
                    )
                )

                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = {
                        val target = if (!urlInput.startsWith("http://") && !urlInput.startsWith("https://")) {
                            "https://$urlInput"
                        } else urlInput
                        currentWebUrl = target
                        webViewInstance?.loadUrl(target)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F5D4)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Go", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        // Quick Preset Site Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF080F1A))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QuickUrlChip("Google Maps", "https://maps.google.com") { urlInput = it; currentWebUrl = it; webViewInstance?.loadUrl(it) }
            QuickUrlChip("BrowserLeaks Geo", "https://browserleaks.com/geo") { urlInput = it; currentWebUrl = it; webViewInstance?.loadUrl(it) }
            QuickUrlChip("IP Info", "https://ipinfo.io") { urlInput = it; currentWebUrl = it; webViewInstance?.loadUrl(it) }
        }

        // Embedded Sandboxed WebView
        AndroidView(
            modifier = Modifier.fillMaxSize().weight(1f),
            factory = { context ->
                WebView(virtualContext).apply {
                    webViewInstance = this
                    settings.javaScriptEnabled = true
                    settings.setGeolocationEnabled(true)
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true

                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onGeolocationPermissionsShowPrompt(
                            origin: String?,
                            callback: GeolocationPermissions.Callback?
                        ) {
                            // Automatically authorize geolocation so web apps use Sandman's mocked GPS
                            callback?.invoke(origin, true, false)
                        }
                    }

                    loadUrl(currentWebUrl)
                }
            },
            update = { view ->
                webViewInstance = view
            }
        )
    }
}

@Composable
fun QuickUrlChip(title: String, url: String, onClick: (String) -> Unit) {
    Surface(
        color = Color(0xFF142438),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3A5F)),
        modifier = Modifier.clickable { onClick(url) }
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFCBD5E1),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * 3. INTERACTIVE CHRONOS TIME-WARP & LICENSING TESTER
 */
@Composable
fun TimeWarpView(
    config: SandboxConfig,
    onUpdateConfig: (SandboxConfig) -> Unit
) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    var realNow by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            realNow = System.currentTimeMillis()
            delay(1000)
        }
    }

    val virtualNow = if (config.isTimeFrozen && config.fixedTimeMillis > 0L) {
        config.fixedTimeMillis
    } else {
        realNow + if (config.spoofTimeEnabled) config.timeOffsetMillis else 0L
    }

    // Simulated 30-day coupon license
    val expirationThreshold = 1735689600000L // 2025-01-01 00:00:00
    val isCouponValid = virtualNow < expirationThreshold

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Live Virtual Clock Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF142438)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = if (config.isTimeFrozen) Color(0xFFFF0054) else Color(0xFF70E000))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (config.isTimeFrozen) "PINNED STATIC VIRTUAL CLOCK" else "ACTIVE VIRTUAL CLOCK",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Badge(containerColor = if (config.isTimeFrozen) Color(0xFFFF0054) else Color(0xFF70E000)) {
                            Text(if (config.isTimeFrozen) "FROZEN" else "TICKING", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = sdf.format(Date(virtualNow)),
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (config.isTimeFrozen) Color(0xFFFF0054) else Color(0xFF70E000)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Physical Hardware Host RTC: ${sdf.format(Date(realNow))}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray
                    )
                }
            }
        }

        // Time Shift Shortcuts
        item {
            Text("Instant Time Travel Buttons", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeTravelBtn("+1 Hour") { onUpdateConfig(config.copy(timeOffsetMillis = config.timeOffsetMillis + 3600000L, spoofTimeEnabled = true, isTimeFrozen = false)) }
                TimeTravelBtn("+24 Hours") { onUpdateConfig(config.copy(timeOffsetMillis = config.timeOffsetMillis + 86400000L, spoofTimeEnabled = true, isTimeFrozen = false)) }
                TimeTravelBtn("+30 Days") { onUpdateConfig(config.copy(timeOffsetMillis = config.timeOffsetMillis + 2592000000L, spoofTimeEnabled = true, isTimeFrozen = false)) }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeTravelBtn("-1 Year (Past)") { onUpdateConfig(config.copy(timeOffsetMillis = config.timeOffsetMillis - 31536000000L, spoofTimeEnabled = true, isTimeFrozen = false)) }
                TimeTravelBtn("Freeze Now") { onUpdateConfig(config.copy(isTimeFrozen = !config.isTimeFrozen, fixedTimeMillis = virtualNow, spoofTimeEnabled = true)) }
                TimeTravelBtn("Reset RTC") { onUpdateConfig(config.copy(timeOffsetMillis = 0L, isTimeFrozen = false)) }
            }
        }

        // Time-Sensitive Trial App Simulator Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2034)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (isCouponValid) Color(0xFF70E000) else Color(0xFFFF0054))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isCouponValid) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (isCouponValid) Color(0xFF70E000) else Color(0xFFFF0054)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Simulated Subscription / Voucher Guard",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (isCouponValid)
                            "STATUS: UNLOCKED & VALID (Free VIP Access Active)"
                        else
                            "STATUS: EXPIRED (Subscription terminated on 2025-01-01)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCouponValid) Color(0xFF70E000) else Color(0xFFFF0054)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Threshold: ${sdf.format(Date(expirationThreshold))}\nVirtual Clock: ${sdf.format(Date(virtualNow))}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.LightGray
                    )
                }
            }
        }
    }
}

@Composable
fun TimeTravelBtn(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF142438)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.height(38.dp)
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xFFCBD5E1), fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 4. THIRD-PARTY / INSTALLED APK HOST INTERACTION VIEW
 */
@Composable
fun ApkHostView(
    targetPackage: String,
    appName: String,
    apkPath: String,
    virtualContext: VirtualContext,
    config: SandboxConfig,
    onUpdateConfig: (SandboxConfig) -> Unit
) {
    var testResult by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
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
                    Text(appName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Package: $targetPackage", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF00F5D4))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "This application runs inside the Sandman isolated container with ContextWrapper storage redirection and dynamic binder proxying.",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        item {
            Text("Interactive Runtime Probes", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))

            // Action: Query Location as seen by the app
            InteractiveActionCard(
                title = "Query Location as Target App",
                desc = "Invokes VirtualContext.getSystemService(LOCATION_SERVICE).getLastKnownLocation()",
                buttonText = "Execute Probe"
            ) {
                try {
                    val lm = virtualContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    val loc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    testResult = "Location Received by Target:\nLat: ${loc?.latitude}°\nLng: ${loc?.longitude}°\nAccuracy: ${loc?.accuracy}m\nProvider: ${loc?.provider}\nisMock: ${loc?.isFromMockProvider}"
                } catch (e: Exception) {
                    testResult = "Error querying Location: ${e.message}"
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action: Write Private Silo File
            InteractiveActionCard(
                title = "Verify Storage Silo Isolation",
                desc = "Writes an isolated text file into ${virtualContext.filesDir.name}",
                buttonText = "Test Private Write"
            ) {
                try {
                    val f = File(virtualContext.filesDir, "sandboxed_doc_${System.currentTimeMillis()}.txt")
                    f.writeText("Data created inside Sandman Container: Lat=${config.fakeLatitude}, Carrier=${config.spoofedCarrier}")
                    testResult = "Success: Wrote private file:\n${f.absolutePath}\nSize: ${f.length()} bytes"
                } catch (e: Exception) {
                    testResult = "Storage error: ${e.message}"
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action: Check Telephony Identity
            InteractiveActionCard(
                title = "Read Telephony Identity",
                desc = "Queries VirtualContext.getSystemService(TELEPHONY_SERVICE)",
                buttonText = "Query SIM / IMEI"
            ) {
                try {
                    val tm = virtualContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    testResult = "Telephony Identity Seen by Target:\nCarrier: ${tm?.simOperatorName ?: config.spoofedCarrier}\nIMEI: ${config.spoofedImei}\nNet: ${config.spoofedNetworkType}\nISO: ${config.spoofedCountryIso.uppercase()}"
                } catch (e: Exception) {
                    testResult = "Telephony error: ${e.message}"
                }
            }
        }

        if (testResult != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2034)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00F5D4))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Execution Output", fontWeight = FontWeight.Bold, color = Color(0xFF00F5D4), fontSize = 12.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { testResult = null }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = testResult ?: "",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InteractiveActionCard(
    title: String,
    desc: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Surface(
        color = Color(0xFF142438),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3A5F)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                Text(desc, color = Color.Gray, fontSize = 10.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F5D4)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(buttonText, color = Color(0xFF080F1A), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

/**
 * 5. QUICK-TWEAK HUD MODAL BOTTOM SHEET
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickTweakBottomSheet(
    config: SandboxConfig,
    onDismiss: () -> Unit,
    onApply: (SandboxConfig) -> Unit
) {
    var latText by remember { mutableStateOf(config.fakeLatitude.toString()) }
    var lngText by remember { mutableStateOf(config.fakeLongitude.toString()) }
    var isFrozen by remember { mutableStateOf(config.isTimeFrozen) }
    var carrierText by remember { mutableStateOf(config.spoofedCarrier) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D1B2A),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFF00F5D4))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Quick Tweak Live Overlay", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
            }

            // Quick City Picker
            Text("Quick GPS Teleport:", fontSize = 11.sp, color = Color.Gray)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickCityChip("Tokyo", 35.6586, 139.7454) { latText = it.first.toString(); lngText = it.second.toString() }
                QuickCityChip("Silicon Valley", 37.3861, -122.0839) { latText = it.first.toString(); lngText = it.second.toString() }
                QuickCityChip("London", 51.5007, -0.1246) { latText = it.first.toString(); lngText = it.second.toString() }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("Latitude") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = lngText,
                    onValueChange = { lngText = it },
                    label = { Text("Longitude") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            // Freeze Clock Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF142438))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Lock / Freeze Clock", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Switch(
                    checked = isFrozen,
                    onCheckedChange = { isFrozen = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFFF0054))
                )
            }

            // Apply Button
            Button(
                onClick = {
                    val lat = latText.toDoubleOrNull() ?: config.fakeLatitude
                    val lng = lngText.toDoubleOrNull() ?: config.fakeLongitude
                    val updated = config.copy(
                        fakeLatitude = lat,
                        fakeLongitude = lng,
                        isTimeFrozen = isFrozen,
                        spoofedCarrier = carrierText
                    )
                    onApply(updated)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00F5D4)),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Apply to Live App", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun QuickCityChip(title: String, lat: Double, lng: Double, onSelect: (Pair<Double, Double>) -> Unit) {
    Surface(
        color = Color(0xFF142438),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3A5F)),
        modifier = Modifier.clickable { onSelect(Pair(lat, lng)) }
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            color = Color(0xFF00F5D4),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
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
