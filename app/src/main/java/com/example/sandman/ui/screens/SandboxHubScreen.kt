package com.example.sandman.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.sandman.core.VirtualContainer
import com.example.sandman.model.InstalledVirtualApp
import com.example.sandman.model.SandboxConfig
import com.example.sandman.service.MockLocationEngine
import com.example.sandman.ui.theme.*
import com.example.sandman.util.DeviceApp
import com.example.sandman.util.InstalledAppsHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxHubScreen(
    config: SandboxConfig,
    onNavigateToConfig: () -> Unit,
    onNavigateToLogs: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val installedApps by VirtualContainer.installedApps.collectAsState()

    var isInstalling by remember { mutableStateOf(false) }
    var installStatusMessage by remember { mutableStateOf<String?>(null) }
    var showDeviceAppPicker by remember { mutableStateOf(false) }
    var deviceApps by remember { mutableStateOf<List<DeviceApp>>(emptyList()) }
    var isLoadingDeviceApps by remember { mutableStateOf(false) }

    // Quick city switcher
    fun updateLocation(lat: Double, lng: Double, cityName: String) {
        val updated = config.copy(fakeLatitude = lat, fakeLongitude = lng, spoofLocationEnabled = true)
        VirtualContainer.updateConfig(context, updated)
        MockLocationEngine.startSpoofing(context, updated)
        installStatusMessage = "Location set to $cityName ($lat, $lng)"
    }

    // Quick time switcher
    fun updateTime(freeze: Boolean, offsetMillis: Long = 0L, fixedMillis: Long = 0L, label: String) {
        val updated = config.copy(
            spoofTimeEnabled = true,
            isTimeFrozen = freeze,
            timeOffsetMillis = offsetMillis,
            fixedTimeMillis = fixedMillis
        )
        VirtualContainer.updateConfig(context, updated)
        installStatusMessage = "Date & Time updated: $label"
    }

    // System File Picker for APKs
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isInstalling = true
                installStatusMessage = "Loading & extracting APK..."
                val result = VirtualContainer.installApkFromUri(context, uri)
                isInstalling = false
                if (result.isSuccess) {
                    val app = result.getOrNull()
                    installStatusMessage = "Ready! ${app?.appName ?: "App"} loaded into container."
                    // Auto-launch
                    if (app != null) {
                        MockLocationEngine.startSpoofing(context, config)
                        VirtualContainer.launchApp(context, app)
                    }
                } else {
                    installStatusMessage = "Error loading APK: ${result.exceptionOrNull()?.message}"
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
    ) {
        // Active Spoofing Conditions Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1E30)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(NeonGreen)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "ACTIVE SPOOF CONDITIONS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonGreen,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = onNavigateToConfig,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Full Settings", fontSize = 11.sp, color = NeonCyan)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Location summary + Quick Teleport
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Location:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "${String.format(Locale.US, "%.4f", config.fakeLatitude)}°, ${String.format(Locale.US, "%.4f", config.fakeLongitude)}°",
                            fontFamily = FontFamily.Monospace,
                            color = NeonCyan,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Quick City Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CityPill("Tokyo", 35.6586, 139.7454) { lat, lng -> updateLocation(lat, lng, "Tokyo") }
                        CityPill("Silicon Valley", 37.3861, -122.0839) { lat, lng -> updateLocation(lat, lng, "Silicon Valley") }
                        CityPill("London", 51.5007, -0.1246) { lat, lng -> updateLocation(lat, lng, "London") }
                        CityPill("New York", 40.7128, -74.0060) { lat, lng -> updateLocation(lat, lng, "New York") }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Time summary + Quick Presets
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    val effectiveTime = if (config.isTimeFrozen && config.fixedTimeMillis > 0L) {
                        config.fixedTimeMillis
                    } else {
                        System.currentTimeMillis() + config.timeOffsetMillis
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Virtual Date & Time:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            sdf.format(Date(effectiveTime)),
                            fontFamily = FontFamily.Monospace,
                            color = if (config.isTimeFrozen) NeonPink else NeonGreen,
                            fontSize = 12.sp
                        )
                        if (config.isTimeFrozen) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(color = NeonPink.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                Text("FROZEN", fontSize = 8.sp, color = NeonPink, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TimePill("Freeze 2025-01-01") { updateTime(freeze = true, fixedMillis = 1735689600000L, label = "Frozen at 2025-01-01") }
                        TimePill("+30 Days") { updateTime(freeze = false, offsetMillis = 2592000000L, label = "+30 Days fast forward") }
                        TimePill("Reset RTC") { updateTime(freeze = false, offsetMillis = 0L, label = "Reset to real time") }
                    }
                }
            }
        }

        // Action Buttons: Load APK or Pick Installed App
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Button 1: Load APK File
                    Button(
                        onClick = { apkPickerLauncher.launch("application/vnd.android.package-archive") },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("load_apk_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, tint = Color(0xFF080F1A), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Load APK File", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    // Button 2: Pick from Installed Apps
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isLoadingDeviceApps = true
                                showDeviceAppPicker = true
                                deviceApps = InstalledAppsHelper.getLaunchableApps(context)
                                isLoadingDeviceApps = false
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("pick_device_app_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF142438)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Installed Apps", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }

        // Status banner
        if (installStatusMessage != null) {
            item {
                Surface(
                    color = Color(0xFF132A1C),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = installStatusMessage ?: "",
                            fontSize = 12.sp,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { installStatusMessage = null }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.Gray, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }

        // Section: Ready to Run Applications
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Ready to Launch & Use",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "${installedApps.size} APPS",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan
                )
            }
        }

        // List of Apps
        items(installedApps) { app ->
            AppLaunchCard(
                app = app,
                onLaunch = {
                    MockLocationEngine.startSpoofing(context, config)
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        installStatusMessage = "Opened ${app.appName} with active spoofing!"
                    } else {
                        VirtualContainer.launchApp(context, app)
                    }
                },
                onDelete = {
                    VirtualContainer.deleteApp(context, app.packageName)
                    installStatusMessage = "Removed ${app.appName}"
                }
            )
        }
    }

    // Device App Picker Modal Bottom Sheet
    if (showDeviceAppPicker) {
        ModalBottomSheet(
            onDismissRequest = { showDeviceAppPicker = false },
            containerColor = Color(0xFF0D1B2A),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = NeonGreen)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Launch Installed App with Spoofed GPS & Time",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Select any app on your device. Sandman will start the mock location engine at your coordinates and launch the app.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(14.dp))

                if (isLoadingDeviceApps) {
                    Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NeonCyan)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 350.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(deviceApps) { deviceApp ->
                            Surface(
                                color = Color(0xFF142438),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3A5F)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showDeviceAppPicker = false
                                        // Start spoofing engine
                                        MockLocationEngine.startSpoofing(context, config)
                                        // Launch app
                                        val launched = InstalledAppsHelper.launchDeviceApp(context, deviceApp.packageName)
                                        if (launched) {
                                            installStatusMessage = "Launched ${deviceApp.appName} with active spoofing!"
                                        } else {
                                            installStatusMessage = "Unable to open ${deviceApp.appName}"
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Android, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(24.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(deviceApp.appName, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                        Text(deviceApp.packageName, fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                    }
                                    Button(
                                        onClick = {
                                            showDeviceAppPicker = false
                                            MockLocationEngine.startSpoofing(context, config)
                                            InstalledAppsHelper.launchDeviceApp(context, deviceApp.packageName)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonGreen),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text("Launch", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CityPill(name: String, lat: Double, lng: Double, onSelect: (Double, Double) -> Unit) {
    Surface(
        color = Color(0xFF142438),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3A5F)),
        modifier = Modifier.clickable { onSelect(lat, lng) }
    ) {
        Text(
            text = name,
            fontSize = 11.sp,
            color = NeonCyan,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun TimePill(label: String, onSelect: () -> Unit) {
    Surface(
        color = Color(0xFF142438),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3A5F)),
        modifier = Modifier.clickable { onSelect() }
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = NeonGreen,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun AppLaunchCard(
    app: InstalledVirtualApp,
    onLaunch: () -> Unit,
    onDelete: () -> Unit
) {
    val appIcon = when (app.packageName) {
        "com.example.sandman.georadar" -> Icons.Default.Navigation
        "com.example.sandman.webexplorer" -> Icons.Default.Language
        "com.example.sandman.timewarp" -> Icons.Default.Schedule
        "com.example.sandman.diagnostics" -> Icons.Default.Shield
        else -> Icons.Default.Android
    }

    val iconColor = when (app.packageName) {
        "com.example.sandman.georadar" -> NeonCyan
        "com.example.sandman.webexplorer" -> NeonAmber
        "com.example.sandman.timewarp" -> NeonGreen
        "com.example.sandman.diagnostics" -> NeonPink
        else -> NeonCyan
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF102032)),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1A334E))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconColor.copy(alpha = 0.2f))
                        .border(1.dp, iconColor, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(appIcon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Text(
                        text = app.packageName,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Gray
                    )
                }

                if (!app.isBuiltInDiagnostic) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Big Launch & Use Button
            Button(
                onClick = onLaunch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("launch_${app.packageName}_button"),
                colors = ButtonDefaults.buttonColors(containerColor = iconColor),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF080F1A), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Launch & Use App Under Spoofing",
                    color = Color(0xFF080F1A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
