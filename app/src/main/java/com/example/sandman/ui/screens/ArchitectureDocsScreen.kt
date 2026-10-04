package com.example.sandman.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sandman.ui.components.SandmanCard
import com.example.sandman.ui.components.SectionHeader
import com.example.sandman.ui.theme.*

@Composable
fun ArchitectureDocsScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
    ) {
        item {
            SandmanCard {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF0F2236)).padding(16.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, tint = NeonCyan)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "AOSP INTERNALS SPECIFICATION",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Comprehensive technical reference on non-rooted Android application virtualization, dynamic proxy hooking, and ART runtime isolation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }
            }
        }

        // Pillar 1: Dynamic ClassLoader
        item {
            DocSection(
                pillarNumber = "01",
                title = "VirtualClassLoader & ART DexPathList",
                tag = "dalvik.system.PathClassLoader",
                color = NeonCyan,
                summary = "Bypasses OS app installation by feeding uninstalled APK DEX bytecode directly to ART.",
                codeSnippet = """
// 1. PathClassLoader subclass instantiation:
class VirtualClassLoader(
    dexPath: String,
    nativeLibPath: String?,
    parent: ClassLoader,
    targetPackage: String
) : PathClassLoader(dexPath, nativeLibPath, parent) {

    // Overriding loadClass to prioritize sandboxed namespace:
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val loaded = findLoadedClass(name)
        if (loaded != null) return loaded
        
        // Prioritize target APK DEX before delegating to host:
        if (!name.startsWith("android.") && !name.startsWith("java.")) {
            try {
                return findClass(name)
            } catch (_: ClassNotFoundException) {}
        }
        return super.loadClass(name, resolve)
    }
}
                """.trimIndent()
            )
        }

        // Pillar 2: VirtualContext & Storage Redirection
        item {
            DocSection(
                pillarNumber = "02",
                title = "VirtualContext & Storage Silos",
                tag = "android.content.ContextWrapper",
                color = NeonGreen,
                summary = "Redirects getFilesDir(), getDatabasePath(), and getSharedPreferences() to private sandbox paths under the host UID.",
                codeSnippet = """
// ContextWrapper overrides target paths:
class VirtualContext(
    base: Context,
    val targetPackage: String,
    val isolatedRoot: File,
    ...
) : ContextWrapper(base) {
    override fun getDataDir() = isolatedRoot
    override fun getFilesDir() = File(isolatedRoot, "files").apply { mkdirs() }
    override fun getCacheDir() = File(isolatedRoot, "cache").apply { mkdirs() }
    override fun getDatabasePath(name: String) =
        File(File(isolatedRoot, "databases").apply { mkdirs() }, name)

    override fun getSharedPreferences(name: String, mode: Int) =
        super.getSharedPreferences("sandbox_${'$'}{targetPackage}_${'$'}name", mode)
}
                """.trimIndent()
            )
        }

        // Pillar 3: ServiceManager.sCache Hooking
        item {
            DocSection(
                pillarNumber = "03",
                title = "ServiceManager.sCache & Binder Proxy",
                tag = "java.lang.reflect.Proxy (IBinder)",
                color = NeonAmber,
                summary = "Poisoning ServiceManager.sCache with Dynamic Proxy IBinder to intercept ILocationManager and ITelephony AIDL calls.",
                codeSnippet = """
// Access hidden static cache in AOSP ServiceManager:
val serviceManagerClass = Class.forName("android.os.ServiceManager")
val sCacheField = serviceManagerClass.getDeclaredField("sCache")
sCacheField.isAccessible = true
val cache = sCacheField.get(null) as MutableMap<String, IBinder>

// Proxy raw IBinder:
val proxyBinder = Proxy.newProxyInstance(
    classLoader, arrayOf(IBinder::class.java)
) { proxy, method, args ->
    if (method.name == "queryLocalInterface") {
        // Return synthetic ILocationManager Dynamic Proxy:
        return@newProxyInstance LocationProxyHandler.createProxy(...)
    }
    method.invoke(realBinder, *args)
}
cache[Context.LOCATION_SERVICE] = proxyBinder
                """.trimIndent()
            )
        }

        // Pillar 4: GNSS Mock Flag Bypass & System Time
        item {
            DocSection(
                pillarNumber = "04",
                title = "Hardware GNSS & Time Interception",
                tag = "Native Synthetic Location & VirtualClock",
                color = NeonPurple,
                summary = "Creates authentic Location parcel without mock bits, and translates System.currentTimeMillis() using user-space arithmetic.",
                codeSnippet = """
// Anti-mock detection bypass:
val location = Location(LocationManager.GPS_PROVIDER)
location.latitude = config.fakeLatitude
location.longitude = config.fakeLongitude
location.accuracy = config.fakeAccuracy
location.time = System.currentTimeMillis() + config.timeOffsetMillis
// Note: Location.isFromMockProvider() returns FALSE because
// no mock provider was registered via OS system_server!

// Virtual Clock for sandboxed calls:
val virtualNow = System.currentTimeMillis() + config.timeOffsetMillis
val virtualElapsed = SystemClock.elapsedRealtime() + config.timeOffsetMillis
                """.trimIndent()
            )
        }
    }
}

@Composable
fun DocSection(
    pillarNumber: String,
    title: String,
    tag: String,
    color: Color,
    summary: String,
    codeSnippet: String
) {
    SandmanCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = color.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "PILLAR $pillarNumber",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = tag,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = color
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFCBD5E1)
            )

            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyberNavyBg)
                    .border(1.dp, CyberBorder, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = codeSnippet,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF94A3B8),
                    lineHeight = 15.sp
                )
            }
        }
    }
}
