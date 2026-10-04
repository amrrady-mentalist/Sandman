package com.example.sandman.hooks

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import com.example.sandman.model.SandboxConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * =========================================================================================
 * ARCHITECTURE DEEP-DIVE: AOSP LOCATION SUBSYSTEM & BINDER PROXYING
 * =========================================================================================
 * In the Android Open Source Project (AOSP), location queries run through the following pipeline:
 *
 * 1. Client App Process:
 *    `context.getSystemService(Context.LOCATION_SERVICE)` returns `android.location.LocationManager`.
 *
 * 2. IPC Boundary (AIDL):
 *    `LocationManager` caches an instance of `ILocationManager`, acquired via:
 *    `ILocationManager.Stub.asInterface(ServiceManager.getService("location"))`
 *    All calls such as `getLastKnownLocation()`, `requestLocationUpdates()`, and `getProviders()`
 *    serialize parameters into `android.os.Parcel` and execute `transact()` across Binder IPC to
 *    `system_server` (`com.android.server.location.LocationManagerService`).
 *
 * 3. Android Mock Location Restrictions in Unrooted Environments:
 *    Standard Android requires "Allow mock locations" in Developer Options and `ACCESS_MOCK_LOCATION`
 *    signature permission. Furthermore, modern apps detect mock locations via `Location.isFromMockProvider()`
 *    or checking `Settings.Secure.ALLOW_MOCK_LOCATION`.
 *
 * How Sandman Bypasses OS Mock Flags Without Root:
 * Instead of registering a mock provider with the OS kernel/system_server, Sandman hooks the client-side
 * `ILocationManager` interface or `LocationManager` instance using a `java.lang.reflect.Proxy`.
 *
 * Benefits of this approach:
 * 1. Zero Root / No Magisk / No Xposed required.
 * 2. `Location.isFromMockProvider()` returns `false` because we construct a synthetic native GPS Location
 *    object directly inside ART without setting the internal mock bit flag.
 * 3. The target app believes it is speaking directly to genuine satellite GNSS hardware.
 * =========================================================================================
 */
class LocationProxyHandler(
    private val originalService: Any?,
    private val configProvider: () -> SandboxConfig
) : InvocationHandler {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeListeners = mutableMapOf<Any, Runnable>()

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
        val methodName = method.name
        val config = configProvider()

        if (!config.spoofLocationEnabled) {
            return delegate(method, args)
        }

        when (methodName) {
            "getLastLocation", "getLastKnownLocation" -> {
                val fakeLoc = createSyntheticLocation(config, "gps")
                VirtualLogBus.log(
                    category = HookCategory.LOCATION,
                    method = methodName,
                    targetClass = "ILocationManager",
                    interceptedPayload = "Provider=${args?.firstOrNull() ?: "any"}",
                    spoofedResult = "Lat=${fakeLoc.latitude}, Lng=${fakeLoc.longitude}, Acc=${fakeLoc.accuracy}m"
                )
                return fakeLoc
            }

            "getCurrentLocation" -> {
                val fakeLoc = createSyntheticLocation(config, "fused")
                VirtualLogBus.log(
                    category = HookCategory.LOCATION,
                    method = "getCurrentLocation",
                    targetClass = "ILocationManager",
                    interceptedPayload = "Single immediate GNSS fix requested",
                    spoofedResult = "Lat=${fakeLoc.latitude}, Lng=${fakeLoc.longitude}"
                )
                // If callback or consumer is provided in args
                handleCurrentLocationCallback(args, fakeLoc)
                return null
            }

            "requestLocationUpdates" -> {
                VirtualLogBus.log(
                    category = HookCategory.LOCATION,
                    method = "requestLocationUpdates",
                    targetClass = "ILocationManager",
                    interceptedPayload = "Registered continuous location listener stream",
                    spoofedResult = "Streaming synthetic GNSS fixes (Lat=${config.fakeLatitude}, Lng=${config.fakeLongitude})"
                )
                handleLocationListenerRegistration(args, config)
                return null
            }

            "removeUpdates" -> {
                VirtualLogBus.log(
                    category = HookCategory.LOCATION,
                    method = "removeUpdates",
                    targetClass = "ILocationManager",
                    interceptedPayload = "Cancelled location updates",
                    spoofedResult = "Unregistered listener from synthetic dispatcher"
                )
                handleLocationListenerRemoval(args)
                return null
            }

            "isProviderEnabled" -> {
                val provider = args?.firstOrNull()?.toString() ?: "gps"
                VirtualLogBus.log(
                    category = HookCategory.LOCATION,
                    method = "isProviderEnabled",
                    targetClass = "ILocationManager",
                    interceptedPayload = "Query status for: $provider",
                    spoofedResult = "true (Always reports GNSS hardware active)"
                )
                return true
            }

            "getProviders", "getAllProviders" -> {
                return listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
            }

            "getBestProvider" -> {
                return LocationManager.GPS_PROVIDER
            }
        }

        return delegate(method, args)
    }

    private fun delegate(method: Method, args: Array<out Any?>?): Any? {
        return if (originalService != null) {
            try {
                method.invoke(originalService, *(args ?: emptyArray()))
            } catch (e: Exception) {
                getDefaultPrimitiveValue(method.returnType)
            }
        } else {
            getDefaultPrimitiveValue(method.returnType)
        }
    }

    private fun getDefaultPrimitiveValue(type: Class<*>): Any? {
        return when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0.0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }

    /**
     * Synthesizes an authentic Android Location parcel without setting mock flags.
     */
    private fun createSyntheticLocation(config: SandboxConfig, provider: String): Location {
        val location = Location(provider)
        location.latitude = config.fakeLatitude
        location.longitude = config.fakeLongitude
        location.altitude = config.fakeAltitude
        location.accuracy = config.fakeAccuracy
        location.speed = config.fakeSpeed
        location.bearing = config.fakeBearing

        // Synchronize with virtual clock
        val virtualTime = if (config.spoofTimeEnabled) {
            System.currentTimeMillis() + config.timeOffsetMillis
        } else {
            System.currentTimeMillis()
        }
        location.time = virtualTime

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val virtualElapsedNanos = SystemClock.elapsedRealtimeNanos() +
                    (if (config.spoofTimeEnabled) config.timeOffsetMillis * 1_000_000L else 0L)
            location.elapsedRealtimeNanos = virtualElapsedNanos
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.bearingAccuracyDegrees = 1.5f
            location.speedAccuracyMetersPerSecond = 0.2f
            location.verticalAccuracyMeters = 2.0f
        }

        // Note: In AOSP, setIsFromMockProvider(false) or leaving default ensures
        // anti-spoofing SDKs do NOT detect mock locations.
        return location
    }

    private fun handleLocationListenerRegistration(args: Array<out Any?>?, config: SandboxConfig) {
        if (args == null) return
        val listener = args.firstOrNull { it is LocationListener } as? LocationListener ?: return

        // Dispatch initial immediate fix
        val initialLoc = createSyntheticLocation(config, "gps")
        listener.onLocationChanged(initialLoc)

        // Setup periodic continuous ticker (default every 2.5s)
        val runnable = object : Runnable {
            override fun run() {
                val currentConfig = configProvider()
                if (currentConfig.spoofLocationEnabled) {
                    val updatedLoc = createSyntheticLocation(currentConfig, "gps")
                    listener.onLocationChanged(updatedLoc)
                    mainHandler.postDelayed(this, 2500)
                }
            }
        }
        activeListeners[listener] = runnable
        mainHandler.postDelayed(runnable, 2500)
    }

    private fun handleLocationListenerRemoval(args: Array<out Any?>?) {
        if (args == null) return
        val listener = args.firstOrNull { it is LocationListener } ?: return
        activeListeners.remove(listener)?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
        }
    }

    private fun handleCurrentLocationCallback(args: Array<out Any?>?, fakeLocation: Location) {
        if (args == null) return
        // Reflectively check for Consumer<Location> (API 30+)
        for (arg in args) {
            if (arg != null && arg.javaClass.name.contains("Consumer")) {
                try {
                    val acceptMethod = arg.javaClass.getMethod("accept", Any::class.java)
                    acceptMethod.invoke(arg, fakeLocation)
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        /**
         * Creates an ILocationManager AIDL Dynamic Proxy.
         */
        fun createProxy(originalBinder: Any?, configProvider: () -> SandboxConfig): Any {
            val interfaceClass = try {
                Class.forName("android.location.ILocationManager")
            } catch (e: Exception) {
                null
            }

            return if (interfaceClass != null) {
                Proxy.newProxyInstance(
                    interfaceClass.classLoader,
                    arrayOf(interfaceClass),
                    LocationProxyHandler(originalBinder, configProvider)
                )
            } else {
                originalBinder ?: Object()
            }
        }
    }
}
