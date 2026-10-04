package com.example.sandman.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sandman.core.VirtualContainer
import com.example.sandman.hooks.VirtualClock
import com.example.sandman.model.SandboxConfig
import com.example.sandman.ui.components.SandmanCard
import com.example.sandman.ui.components.SectionHeader
import com.example.sandman.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

data class LocationPreset(val name: String, val lat: Double, val lng: Double)

val LOCATION_PRESETS = listOf(
    LocationPreset("Tokyo", 35.6586, 139.7454),
    LocationPreset("Silicon Valley", 37.3861, -122.0839),
    LocationPreset("London", 51.5007, -0.1246),
    LocationPreset("Paris", 48.8584, 2.2945),
    LocationPreset("Cairo", 29.9792, 31.1342),
    LocationPreset("New York", 40.7128, -74.0060)
)

val CARRIER_PRESETS = listOf(
    "Sandman Virtual Telecom",
    "Verizon Wireless",
    "NTT DOCOMO",
    "Vodafone Global",
    "Deutsche Telekom"
)

data class FixedDatePreset(val label: String, val dateStr: String)

val FIXED_DATE_PRESETS = listOf(
    FixedDatePreset("New Year 2025", "2025-01-01 00:00:00"),
    FixedDatePreset("New Year 2026", "2026-01-01 00:00:00"),
    FixedDatePreset("Mid 2024", "2024-06-15 12:00:00"),
    FixedDatePreset("Y2K Bug", "2000-01-01 00:00:00"),
    FixedDatePreset("Future 2050", "2050-01-01 00:00:00")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpoofConfigScreen(
    initialConfig: SandboxConfig,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(initialConfig) }
    var latText by remember { mutableStateOf(initialConfig.fakeLatitude.toString()) }
    var lngText by remember { mutableStateOf(initialConfig.fakeLongitude.toString()) }
    var imeiText by remember { mutableStateOf(initialConfig.spoofedImei) }
    var carrierText by remember { mutableStateOf(initialConfig.spoofedCarrier) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }

    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    var fixedDateInput by remember {
        val initialFixed = if (initialConfig.fixedTimeMillis > 0L) initialConfig.fixedTimeMillis else System.currentTimeMillis()
        mutableStateOf(sdf.format(Date(initialFixed)))
    }
    var parseError by remember { mutableStateOf<String?>(null) }

    // Live clock ticker
    var currentRealTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            currentRealTime = System.currentTimeMillis()
            delay(1000)
        }
    }

    val virtualTimeMillis = if (config.isTimeFrozen && config.fixedTimeMillis > 0L) {
        config.fixedTimeMillis
    } else {
        currentRealTime + if (config.spoofTimeEnabled) config.timeOffsetMillis else 0L
    }

    fun parseDateInputToMillis(input: String): Long? {
        return try {
            val date = sdf.parse(input.trim())
            parseError = null
            date?.time
        } catch (_: Exception) {
            parseError = "Invalid format: Must be yyyy-MM-dd HH:mm:ss"
            null
        }
    }

    fun adjustFixedDate(calendarField: Int, amount: Int) {
        val currentMillis = parseDateInputToMillis(fixedDateInput) ?: System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.timeInMillis = currentMillis
        cal.add(calendarField, amount)
        val newMillis = cal.timeInMillis
        fixedDateInput = sdf.format(Date(newMillis))
        config = config.copy(
            fixedTimeMillis = newMillis,
            timeOffsetMillis = newMillis - currentRealTime
        )
    }

    fun applyAndSave() {
        val lat = latText.toDoubleOrNull() ?: config.fakeLatitude
        val lng = lngText.toDoubleOrNull() ?: config.fakeLongitude

        val parsedMillis = parseDateInputToMillis(fixedDateInput) ?: config.fixedTimeMillis
        val offset = if (!config.isTimeFrozen) {
            parsedMillis - System.currentTimeMillis()
        } else {
            config.timeOffsetMillis
        }

        val updated = config.copy(
            fakeLatitude = lat,
            fakeLongitude = lng,
            spoofedImei = imeiText.trim(),
            spoofedCarrier = carrierText.trim(),
            fixedTimeMillis = parsedMillis,
            timeOffsetMillis = if (config.isTimeFrozen) config.timeOffsetMillis else offset
        )
        config = updated
        VirtualContainer.updateConfig(context, updated)
        saveFeedback = "Settings committed to VirtualContainer runtime!"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Spoof Matrix & Telemetry Config", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("config_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = { applyAndSave() },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        modifier = Modifier.padding(end = 8.dp).testTag("save_config_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = Color(0xFF080F1A), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Apply", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CyberSurface,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = CyberNavyBg
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // Save feedback banner
            if (saveFeedback != null) {
                item {
                    Surface(
                        color = Color(0xFF0F382A),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(saveFeedback ?: "", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { saveFeedback = null }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            // 1. LOCATION SECTION
            item {
                SectionHeader(
                    icon = Icons.Default.LocationOn,
                    title = "LocationManager & GNSS Spoofing",
                    subtitle = "Inject fake coordinates into AOSP ILocationManager Binder",
                    badge = if (config.spoofLocationEnabled) "ACTIVE" else "DISABLED",
                    badgeColor = if (config.spoofLocationEnabled) NeonCyan else Color.Gray
                )
            }

            item {
                SandmanCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable Location Interception", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Switch(
                                checked = config.spoofLocationEnabled,
                                onCheckedChange = { config = config.copy(spoofLocationEnabled = it) },
                                modifier = Modifier.testTag("spoof_location_switch")
                            )
                        }

                        if (config.spoofLocationEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Quick Presets:", fontSize = 11.sp, color = Color.LightGray)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                LOCATION_PRESETS.take(3).forEach { preset ->
                                    PresetChip(
                                        title = preset.name,
                                        selected = latText == preset.lat.toString() && lngText == preset.lng.toString(),
                                        onClick = {
                                            latText = preset.lat.toString()
                                            lngText = preset.lng.toString()
                                            config = config.copy(fakeLatitude = preset.lat, fakeLongitude = preset.lng)
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                LOCATION_PRESETS.drop(3).forEach { preset ->
                                    PresetChip(
                                        title = preset.name,
                                        selected = latText == preset.lat.toString() && lngText == preset.lng.toString(),
                                        onClick = {
                                            latText = preset.lat.toString()
                                            lngText = preset.lng.toString()
                                            config = config.copy(fakeLatitude = preset.lat, fakeLongitude = preset.lng)
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = latText,
                                    onValueChange = { latText = it },
                                    label = { Text("Latitude (-90 to +90)") },
                                    modifier = Modifier.weight(1f).testTag("fake_lat_input"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = lngText,
                                    onValueChange = { lngText = it },
                                    label = { Text("Longitude (-180 to +180)") },
                                    modifier = Modifier.weight(1f).testTag("fake_lng_input"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            Text("GNSS Accuracy: ${String.format(Locale.US, "%.1f", config.fakeAccuracy)}m", fontSize = 12.sp, color = Color.LightGray)
                            Slider(
                                value = config.fakeAccuracy,
                                onValueChange = { config = config.copy(fakeAccuracy = it) },
                                valueRange = 1.0f..30.0f
                            )

                            Text("Altitude: ${String.format(Locale.US, "%.0f", config.fakeAltitude)}m ASL", fontSize = 12.sp, color = Color.LightGray)
                            Slider(
                                value = config.fakeAltitude.toFloat(),
                                onValueChange = { config = config.copy(fakeAltitude = it.toDouble()) },
                                valueRange = 0.0f..1000.0f
                            )
                        }
                    }
                }
            }

            // 2. TIME DRIFT & SPECIFIC DATE/TIME FIXER SECTION
            item {
                SectionHeader(
                    icon = Icons.Default.Schedule,
                    title = "System Time & Date Fixing",
                    subtitle = "Fix a specific date/time or freeze the clock in user-space",
                    badge = if (config.spoofTimeEnabled) {
                        if (config.isTimeFrozen) "PINNED STATIC" else "OFFSET ACTIVE"
                    } else "RTC REALTIME",
                    badgeColor = if (config.spoofTimeEnabled) {
                        if (config.isTimeFrozen) NeonPink else NeonGreen
                    } else Color.Gray
                )
            }

            item {
                SandmanCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable Virtual Clock", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Switch(
                                checked = config.spoofTimeEnabled,
                                onCheckedChange = { config = config.copy(spoofTimeEnabled = it) },
                                modifier = Modifier.testTag("spoof_time_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        // Live Dual Clock Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CyberNavyBg, RoundedCornerShape(10.dp))
                                .border(1.dp, CyberBorder, RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (config.isTimeFrozen) "FROZEN SPECIFIC TIME:" else "SANDMAN VIRTUAL TIME:",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (config.isTimeFrozen) NeonPink else NeonGreen
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        if (config.isTimeFrozen) "CLOCK STOPPED"
                                        else "${config.timeOffsetMillis / 3600000}h offset",
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }
                                Text(
                                    text = sdf.format(Date(virtualTimeMillis)),
                                    fontSize = 16.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (config.isTimeFrozen) NeonPink else NeonGreen
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("HOST HARDWARE RTC:", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B))
                                Text(
                                    text = sdf.format(Date(currentRealTime)),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                        }

                        if (config.spoofTimeEnabled) {
                            Spacer(modifier = Modifier.height(16.dp))

                            // Freeze Clock Toggle
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F1B2B))
                                    .border(1.dp, if (config.isTimeFrozen) NeonPink else CyberBorder, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Freeze / Lock Clock",
                                        color = if (config.isTimeFrozen) NeonPink else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        "If enabled, clock stops ticking and stays permanently fixed at the specified timestamp",
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = config.isTimeFrozen,
                                    onCheckedChange = { isFrozen ->
                                        config = config.copy(isTimeFrozen = isFrozen)
                                        if (isFrozen) {
                                            val parsed = parseDateInputToMillis(fixedDateInput) ?: System.currentTimeMillis()
                                            config = config.copy(fixedTimeMillis = parsed)
                                        }
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = NeonPink
                                    ),
                                    modifier = Modifier.testTag("freeze_clock_switch")
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Specific Date & Time Input Field
                            Text("Fix Specific Date & Time (yyyy-MM-dd HH:mm:ss):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedTextField(
                                value = fixedDateInput,
                                onValueChange = {
                                    fixedDateInput = it
                                    val parsed = parseDateInputToMillis(it)
                                    if (parsed != null) {
                                        config = config.copy(
                                            fixedTimeMillis = parsed,
                                            timeOffsetMillis = parsed - currentRealTime
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("fixed_date_input"),
                                singleLine = true,
                                isError = parseError != null,
                                trailingIcon = {
                                    IconButton(onClick = {
                                        fixedDateInput = sdf.format(Date(currentRealTime))
                                        config = config.copy(
                                            fixedTimeMillis = currentRealTime,
                                            timeOffsetMillis = 0L
                                        )
                                    }) {
                                        Icon(Icons.Default.Today, contentDescription = "Set to Current Time", tint = NeonGreen)
                                    }
                                }
                            )

                            if (parseError != null) {
                                Text(parseError ?: "", color = NeonPink, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Specific Date Presets
                            Text("Fixed Date Presets:", fontSize = 11.sp, color = Color.LightGray)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FIXED_DATE_PRESETS.take(3).forEach { preset ->
                                    PresetChip(
                                        title = preset.label,
                                        selected = fixedDateInput == preset.dateStr,
                                        onClick = {
                                            fixedDateInput = preset.dateStr
                                            val millis = parseDateInputToMillis(preset.dateStr)
                                            if (millis != null) {
                                                config = config.copy(
                                                    fixedTimeMillis = millis,
                                                    timeOffsetMillis = millis - currentRealTime
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FIXED_DATE_PRESETS.drop(3).forEach { preset ->
                                    PresetChip(
                                        title = preset.label,
                                        selected = fixedDateInput == preset.dateStr,
                                        onClick = {
                                            fixedDateInput = preset.dateStr
                                            val millis = parseDateInputToMillis(preset.dateStr)
                                            if (millis != null) {
                                                config = config.copy(
                                                    fixedTimeMillis = millis,
                                                    timeOffsetMillis = millis - currentRealTime
                                                )
                                            }
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Interactive Steppers
                            Text("Adjust Date & Time Instantly:", fontSize = 11.sp, color = Color.LightGray)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AdjusterButton("+1 Year") { adjustFixedDate(Calendar.YEAR, 1) }
                                AdjusterButton("-1 Year") { adjustFixedDate(Calendar.YEAR, -1) }
                                AdjusterButton("+1 Month") { adjustFixedDate(Calendar.MONTH, 1) }
                                AdjusterButton("-1 Month") { adjustFixedDate(Calendar.MONTH, -1) }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AdjusterButton("+1 Day") { adjustFixedDate(Calendar.DAY_OF_YEAR, 1) }
                                AdjusterButton("-1 Day") { adjustFixedDate(Calendar.DAY_OF_YEAR, -1) }
                                AdjusterButton("+1 Hour") { adjustFixedDate(Calendar.HOUR_OF_DAY, 1) }
                                AdjusterButton("-1 Hour") { adjustFixedDate(Calendar.HOUR_OF_DAY, -1) }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            // Relative Drift Presets
                            Text("Relative Drift Presets:", fontSize = 11.sp, color = Color.LightGray)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TimeOffsetChip("+1 Hour", 3600000L, config.timeOffsetMillis) {
                                    config = config.copy(timeOffsetMillis = it, isTimeFrozen = false)
                                    fixedDateInput = sdf.format(Date(currentRealTime + it))
                                }
                                TimeOffsetChip("+24 Hours", 86400000L, config.timeOffsetMillis) {
                                    config = config.copy(timeOffsetMillis = it, isTimeFrozen = false)
                                    fixedDateInput = sdf.format(Date(currentRealTime + it))
                                }
                                TimeOffsetChip("+7 Days", 604800000L, config.timeOffsetMillis) {
                                    config = config.copy(timeOffsetMillis = it, isTimeFrozen = false)
                                    fixedDateInput = sdf.format(Date(currentRealTime + it))
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TimeOffsetChip("+30 Days", 2592000000L, config.timeOffsetMillis) {
                                    config = config.copy(timeOffsetMillis = it, isTimeFrozen = false)
                                    fixedDateInput = sdf.format(Date(currentRealTime + it))
                                }
                                TimeOffsetChip("-24h (Past)", -86400000L, config.timeOffsetMillis) {
                                    config = config.copy(timeOffsetMillis = it, isTimeFrozen = false)
                                    fixedDateInput = sdf.format(Date(currentRealTime + it))
                                }
                                TimeOffsetChip("Reset (Now)", 0L, config.timeOffsetMillis) {
                                    config = config.copy(timeOffsetMillis = 0L, isTimeFrozen = false, fixedTimeMillis = currentRealTime)
                                    fixedDateInput = sdf.format(Date(currentRealTime))
                                }
                            }
                        }
                    }
                }
            }

            // 3. TELEPHONY SECTION
            item {
                SectionHeader(
                    icon = Icons.Default.PhoneAndroid,
                    title = "Telephony & Device Fingerprint",
                    subtitle = "Spoofs ITelephony binder queries for IMEI, SIM, and Carrier",
                    badge = if (config.spoofTelephonyEnabled) "VIRTUAL SIM" else "HARDWARE",
                    badgeColor = if (config.spoofTelephonyEnabled) NeonAmber else Color.Gray
                )
            }

            item {
                SandmanCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable Telephony Spoofing", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Switch(
                                checked = config.spoofTelephonyEnabled,
                                onCheckedChange = { config = config.copy(spoofTelephonyEnabled = it) },
                                modifier = Modifier.testTag("spoof_telephony_switch")
                            )
                        }

                        if (config.spoofTelephonyEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = imeiText,
                                onValueChange = { imeiText = it },
                                label = { Text("Spoofed Device IMEI (15 Digits)") },
                                modifier = Modifier.fillMaxWidth().testTag("fake_imei_input"),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        imeiText = generateRandomImei()
                                    }) {
                                        Icon(Icons.Default.Casino, contentDescription = "Generate Random IMEI", tint = NeonAmber)
                                    }
                                },
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Text("Carrier Name:", fontSize = 11.sp, color = Color.LightGray)
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = carrierText,
                                onValueChange = { carrierText = it },
                                modifier = Modifier.fillMaxWidth().testTag("carrier_input"),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CARRIER_PRESETS.take(3).forEach { carrier ->
                                    PresetChip(
                                        title = carrier.take(12),
                                        selected = carrierText == carrier,
                                        onClick = { carrierText = carrier }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = config.spoofedCountryIso.uppercase(),
                                    onValueChange = { config = config.copy(spoofedCountryIso = it.lowercase()) },
                                    label = { Text("Country ISO") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = config.spoofedNetworkType,
                                    onValueChange = { config = config.copy(spoofedNetworkType = it) },
                                    label = { Text("Network Type") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }
            }

            // 4. STORAGE ISOLATION SECTION
            item {
                SectionHeader(
                    icon = Icons.Default.FolderSpecial,
                    title = "Filesystem & Storage Silo",
                    subtitle = "ContextWrapper redirection to /data/data/.../virtual_apps/",
                    badge = "ACTIVE",
                    badgeColor = NeonCyan
                )
            }

            item {
                SandmanCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Storage Isolation Active", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Badge(containerColor = NeonCyan) {
                                Text("ENFORCED", color = Color(0xFF080F1A), fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Sandboxed apps write to private isolated paths. Host databases, preferences, and files are completely invisible and shielded.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AdjusterButton(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        color = Color(0xFF142438),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyberBorder),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFCBD5E1),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun PresetChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) NeonCyan.copy(alpha = 0.25f) else Color(0xFF0B1928),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) NeonCyan else CyberBorder
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) NeonCyan else Color(0xFFCBD5E1),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
fun TimeOffsetChip(
    title: String,
    offset: Long,
    currentOffset: Long,
    onClick: (Long) -> Unit
) {
    val selected = currentOffset == offset
    Surface(
        color = if (selected) NeonGreen.copy(alpha = 0.25f) else Color(0xFF0B1928),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) NeonGreen else CyberBorder
        ),
        modifier = Modifier.clickable { onClick(offset) }
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) NeonGreen else Color(0xFFCBD5E1),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

private fun generateRandomImei(): String {
    val prefix = "86" + Random.nextInt(100000, 999999).toString()
    val suffix = Random.nextInt(1000000, 9999999).toString()
    return prefix + suffix
}
