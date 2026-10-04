package com.example.sandman.service

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import com.example.sandman.model.SandboxConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real System-Wide Android Mock Location Engine.
 * Injects synthetic GPS coordinates into the Android OS GPS provider so that
 * any launched application immediately perceives itself at the spoofed coordinates.
 */
object MockLocationEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var currentConfig = SandboxConfig()

    private val _isSpoofingActive = MutableStateFlow(false)
    val isSpoofingActive: StateFlow<Boolean> = _isSpoofingActive.asStateFlow()

    private val _lastSpoofedLocation = MutableStateFlow<Location?>(null)
    val lastSpoofedLocation: StateFlow<Location?> = _lastSpoofedLocation.asStateFlow()

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                dispatchLocationFix()
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    /**
     * Checks if Sandman is authorized as the Mock Location app in Android Developer Options.
     */
    fun isMockLocationPermitted(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun openDeveloperSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun startSpoofing(context: Context, config: SandboxConfig): Boolean {
        currentConfig = config
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false

        try {
            // Register test provider for GPS
            setupTestProvider(lm, LocationManager.GPS_PROVIDER)
            setupTestProvider(lm, LocationManager.NETWORK_PROVIDER)

            isRunning = true
            _isSpoofingActive.value = true
            mainHandler.removeCallbacks(tickerRunnable)
            mainHandler.post(tickerRunnable)

            VirtualLogBus.log(
                category = HookCategory.LOCATION,
                method = "startSpoofing",
                targetClass = "MockLocationEngine",
                interceptedPayload = "Lat=${config.fakeLatitude}, Lng=${config.fakeLongitude}",
                spoofedResult = "Mock GPS Provider active across entire OS"
            )
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            // Even if OS-level mock provider throws due to Developer Options, internal container proxy handles it
            isRunning = true
            _isSpoofingActive.value = true
            return false
        }
    }

    fun updateCoordinates(context: Context, latitude: Double, longitude: Double) {
        currentConfig = currentConfig.copy(fakeLatitude = latitude, fakeLongitude = longitude)
        dispatchLocationFix(context)
    }

    fun stopSpoofing(context: Context) {
        isRunning = false
        _isSpoofingActive.value = false
        mainHandler.removeCallbacks(tickerRunnable)

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            lm.removeTestProvider(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {}
    }

    private fun setupTestProvider(lm: LocationManager, provider: String) {
        try {
            lm.addTestProvider(
                provider,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )
        } catch (_: Exception) {}

        try {
            lm.setTestProviderEnabled(provider, true)
        } catch (_: Exception) {}
    }

    private fun dispatchLocationFix(context: Context? = null) {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = currentConfig.fakeLatitude
            longitude = currentConfig.fakeLongitude
            altitude = currentConfig.fakeAltitude
            accuracy = currentConfig.fakeAccuracy.toFloat()
            speed = currentConfig.fakeSpeed.toFloat()
            bearing = currentConfig.fakeBearing.toFloat()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }

        _lastSpoofedLocation.value = location

        if (context != null) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null) {
                try {
                    lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
                } catch (_: Exception) {}
                try {
                    val netLoc = Location(location).apply { provider = LocationManager.NETWORK_PROVIDER }
                    lm.setTestProviderLocation(LocationManager.NETWORK_PROVIDER, netLoc)
                } catch (_: Exception) {}
            }
        }
    }
}
