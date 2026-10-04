package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sandman.core.VirtualContainer
import com.example.sandman.ui.screens.ArchitectureDocsScreen
import com.example.sandman.ui.screens.LiveTerminalScreen
import com.example.sandman.ui.screens.SandboxHubScreen
import com.example.sandman.ui.screens.SpoofConfigScreen
import com.example.sandman.ui.theme.*

enum class AppScreen(val title: String, val icon: ImageVector, val tag: String) {
    HUB("Sandbox", Icons.Default.Apps, "nav_hub"),
    CONFIG("Spoof Matrix", Icons.Default.Tune, "nav_config"),
    TERMINAL("Interceptor", Icons.Default.Terminal, "nav_terminal"),
    DOCS("AOSP Docs", Icons.Default.MenuBook, "nav_docs")
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Virtual Container Engine and Service Hook Registry
        VirtualContainer.initialize(this)

        setContent {
            SandmanTheme {
                SandmanHostApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandmanHostApp() {
    var currentScreen by remember { mutableStateOf(AppScreen.HUB) }
    val config by VirtualContainer.currentConfig.collectAsState()

    // Handle back button when not on Hub
    BackHandler(enabled = currentScreen != AppScreen.HUB) {
        currentScreen = AppScreen.HUB
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(NeonCyan.copy(alpha = 0.2f))
                                .border(1.dp, NeonCyan, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.VpnLock,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "SANDMAN",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = NeonGreen.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "UNROOTED",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeonGreen,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Virtual Sandbox & Telemetry Hook Engine",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF94A3B8)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CyberSurface,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = CyberSurface,
                tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars,
                modifier = Modifier.border(1.dp, CyberBorder)
            ) {
                AppScreen.values().forEach { screen ->
                    val selected = currentScreen == screen
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentScreen = screen },
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = screen.title,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF080F1A),
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = Color(0xFF64748B),
                            unselectedTextColor = Color(0xFF64748B)
                        ),
                        modifier = Modifier.testTag(screen.tag)
                    )
                }
            }
        },
        containerColor = CyberNavyBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentScreen) {
                AppScreen.HUB -> {
                    SandboxHubScreen(
                        config = config,
                        onNavigateToConfig = { currentScreen = AppScreen.CONFIG },
                        onNavigateToLogs = { currentScreen = AppScreen.TERMINAL }
                    )
                }
                AppScreen.CONFIG -> {
                    SpoofConfigScreen(
                        initialConfig = config,
                        onBack = { currentScreen = AppScreen.HUB }
                    )
                }
                AppScreen.TERMINAL -> {
                    LiveTerminalScreen()
                }
                AppScreen.DOCS -> {
                    ArchitectureDocsScreen()
                }
            }
        }
    }
}
