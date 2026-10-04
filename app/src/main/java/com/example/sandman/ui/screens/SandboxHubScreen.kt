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
import com.example.sandman.ui.components.SandmanCard
import com.example.sandman.ui.components.SectionHeader
import com.example.sandman.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

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
    var appToDelete by remember { mutableStateOf<InstalledVirtualApp?>(null) }
    var selectedAppForDetail by remember { mutableStateOf<InstalledVirtualApp?>(null) }

    // System SAF File Picker to load external target APK without root
    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isInstalling = true
                installStatusMessage = "Analyzing APK archive & extracting native libraries..."
                val result = VirtualContainer.installApkFromUri(context, uri)
                isInstalling = false
                if (result.isSuccess) {
                    val app = result.getOrNull()
                    installStatusMessage = "Successfully ingested ${app?.appName ?: "App"} into isolated sandbox!"
                } else {
                    installStatusMessage = "Failed: ${result.exceptionOrNull()?.message}"
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
        // Hero Status Banner
        item {
            SandmanCard {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF0F2236)).padding(18.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(NeonCyan.copy(alpha = 0.2f))
                                    .border(1.dp, NeonCyan, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.VpnLock, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(24.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "SANDMAN VIRTUAL BOX",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = "Non-Rooted ART Subsystem & Hook Engine",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonCyan
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Apps run isolated under host PID with custom PathClassLoader, ContextWrapper storage redirection, and reflective Binder dynamic proxying.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1),
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        // Quick Telemetry Strip
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(CyberNavyBg)
                                .border(1.dp, CyberBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("VIRTUAL GPS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                Text(
                                    if (config.spoofLocationEnabled)
                                        "${String.format(Locale.US, "%.3f", config.fakeLatitude)}°, ${String.format(Locale.US, "%.3f", config.fakeLongitude)}°"
                                    else "Hardware GPS",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (config.spoofLocationEnabled) NeonCyan else Color.White
                                )
                            }
                            Divider(modifier = Modifier.height(24.dp).width(1.dp), color = CyberBorder)
                            Column {
                                Text(if (config.isTimeFrozen) "FIXED TIME" else "TEMPORAL DRIFT", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                Text(
                                    if (config.spoofTimeEnabled) {
                                        if (config.isTimeFrozen) "Locked" else "+${config.timeOffsetMillis / 3600000}h Offset"
                                    } else "RTC Sync",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (config.spoofTimeEnabled) (if (config.isTimeFrozen) NeonPink else NeonGreen) else Color.White
                                )
                            }
                            Divider(modifier = Modifier.height(24.dp).width(1.dp), color = CyberBorder)
                            Column {
                                Text("SPOOFED CARRIER", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                Text(
                                    if (config.spoofTelephonyEnabled) config.spoofedCarrier.take(12) else "Real SIM",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (config.spoofTelephonyEnabled) NeonAmber else Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // Action Buttons Row: Load Target APK & Configure
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { apkPickerLauncher.launch("application/vnd.android.package-archive") },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("load_apk_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, tint = Color(0xFF080F1A), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Load Target APK", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = onNavigateToConfig,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("nav_to_config_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Spoof Matrix", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Install progress feedback
        if (isInstalling || installStatusMessage != null) {
            item {
                Surface(
                    color = if (isInstalling) Color(0xFF0B253A) else Color(0xFF132A1C),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isInstalling) NeonCyan else NeonGreen),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isInstalling) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NeonCyan, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = installStatusMessage ?: "",
                            fontSize = 12.sp,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        if (!isInstalling) {
                            IconButton(onClick = { installStatusMessage = null }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color.Gray, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        // Sandboxed Applications List Header
        item {
            SectionHeader(
                icon = Icons.Default.Apps,
                title = "Sandboxed Applications",
                subtitle = "Select an application to launch within the virtual container",
                badge = "${installedApps.size} READY",
                badgeColor = NeonCyan
            )
        }

        // Apps List
        items(installedApps) { app ->
            VirtualAppCard(
                app = app,
                onLaunch = {
                    val launched = VirtualContainer.launchApp(context, app)
                    if (!launched) {
                        installStatusMessage = "Notice: Unable to launch container for ${app.appName}."
                    }
                },
                onClearData = {
                    VirtualContainer.clearAppData(context, app.packageName)
                    installStatusMessage = "Purged sandbox storage cache for ${app.appName}."
                },
                onDelete = { appToDelete = app },
                onViewDetails = { selectedAppForDetail = app }
            )
        }
    }

    // App Detail / Storage Inspector Dialog
    if (selectedAppForDetail != null) {
        val app = selectedAppForDetail!!
        AlertDialog(
            onDismissRequest = { selectedAppForDetail = null },
            title = {
                Text(app.appName, fontWeight = FontWeight.Bold, color = Color.White)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Package: ${app.packageName}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = NeonCyan)
                    Text("Version: ${app.versionName} (${app.versionCode})", fontSize = 12.sp, color = Color.LightGray)
                    Text("Launch Entry: ${app.mainActivity}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.LightGray)
                    Text("Isolated Storage Root:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CyberNavyBg, RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(app.isolatedDataDir, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFCBD5E1))
                    }
                    if (app.permissions.isNotEmpty()) {
                        Text("Declared Manifest Permissions (${app.permissions.size}):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(app.permissions.take(5).joinToString("\n") { "• ${it.substringAfterLast(".")}" }, fontSize = 10.sp, color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedAppForDetail = null
                        VirtualContainer.launchApp(context, app)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                ) {
                    Text("Launch Container", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedAppForDetail = null }) {
                    Text("Close", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF142438),
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Delete confirmation dialog
    if (appToDelete != null) {
        val app = appToDelete!!
        AlertDialog(
            onDismissRequest = { appToDelete = null },
            title = { Text("Purge Sandboxed App?", color = Color.White) },
            text = { Text("This will permanently remove ${app.appName} and wipe its isolated storage directory.", color = Color.LightGray) },
            confirmButton = {
                Button(
                    onClick = {
                        VirtualContainer.deleteApp(context, app.packageName)
                        appToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonPink)
                ) {
                    Text("Purge", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { appToDelete = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF142438)
        )
    }
}

@Composable
fun VirtualAppCard(
    app: InstalledVirtualApp,
    onLaunch: () -> Unit,
    onClearData: () -> Unit,
    onDelete: () -> Unit,
    onViewDetails: () -> Unit
) {
    SandmanCard(
        modifier = Modifier.clickable { onViewDetails() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // App Avatar Icon
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (app.isBuiltInDiagnostic) NeonGreen.copy(alpha = 0.2f) else NeonCyan.copy(alpha = 0.2f)
                        )
                        .border(
                            1.dp,
                            if (app.isBuiltInDiagnostic) NeonGreen else NeonCyan,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (app.isBuiltInDiagnostic) Icons.Default.BuildCircle else Icons.Default.Android,
                        contentDescription = null,
                        tint = if (app.isBuiltInDiagnostic) NeonGreen else NeonCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = app.appName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (app.isBuiltInDiagnostic) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = NeonGreen.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "BUILT-IN",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonGreen,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = app.packageName,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "v${app.versionName} • Isolated Path: .../${app.packageName.takeLast(16)}",
                        fontSize = 10.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onLaunch,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("launch_${app.packageName}_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (app.isBuiltInDiagnostic) NeonGreen else NeonCyan
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF080F1A),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Launch in Sandbox",
                        color = Color(0xFF080F1A),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                FilledTonalIconButton(
                    onClick = onClearData,
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        Icons.Default.CleaningServices,
                        contentDescription = "Clear Cache",
                        tint = NeonAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (!app.isBuiltInDiagnostic) {
                    FilledTonalIconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete App",
                            tint = NeonPink,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
