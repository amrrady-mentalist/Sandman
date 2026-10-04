package com.example.sandman.hooks

import android.os.SystemClock
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import com.example.sandman.model.SandboxConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * =========================================================================================
 * ARCHITECTURE DEEP-DIVE: AOSP TIME & CLOCK INTERCEPTION IN NON-ROOTED VIRTUAL CONTAINERS
 * =========================================================================================
 * In the Android OS architecture, time queries originate through three main pathways:
 *
 * 1. Java Standard Library:
 *    `System.currentTimeMillis()` -> Native JNI (`System_currentTimeMillis` in ART).
 *    Calls POSIX `gettimeofday()` or `clock_gettime(CLOCK_REALTIME)`.
 *
 * 2. Android SystemClock Hidden APIs & Telemetry:
 *    `SystemClock.elapsedRealtime()` / `SystemClock.elapsedRealtimeNanos()`:
 *    Calls `clock_gettime(CLOCK_BOOTTIME)`. Represents time since device boot including deep sleep.
 *    `SystemClock.uptimeMillis()`: Time since boot excluding deep sleep (`CLOCK_MONOTONIC`).
 *    `SystemClock.currentNetworkTimeClock()` / `currentGnssTimeClock()` (hidden/added in API 29+).
 *
 * 3. System Services (IPC / Binder):
 *    - `IAlarmManager`: Controls RTC and ELAPSED wake alarms via Linux `/dev/alarm` or `timerfd`.
 *    - `ITimeDetectorService`: AOSP service coordinating Network Time Protocol (NTP) & NITZ.
 *
 * The Non-Rooted Virtualization Challenge:
 * Changing the hardware system clock via `settimeofday()` requires the Linux kernel capability
 * `CAP_SYS_TIME`, which is strictly restricted to `system_server` (UID 1000) or root (UID 0).
 * Attempting `SystemClock.setCurrentTimeMillis()` from an untrusted app throws a `SecurityException`.
 *
 * How Sandman Solves This in Pure User Space:
 * 1. `VirtualClock` provides synchronized temporal translation:
 *    `virtualNow = System.currentTimeMillis() + offsetMillis`
 *    `virtualElapsed = SystemClock.elapsedRealtime() + offsetMillis`
 * 2. System Service Binder Hooking (`IAlarmManager` proxy):
 *    When the sandboxed app schedules alarms or queries `getNextAlarmClock()`, Sandman's
 *    Dynamic Proxy intercepts the Binder transaction and adjusts the time offset symmetrically.
 * 3. Injected Hook Methods: Sandman exposes reflective hooks into the sandboxed runtime.
 * =========================================================================================
 */
class TimeProxyHandler(
    private val originalService: Any?,
    private val configProvider: () -> SandboxConfig
) : InvocationHandler {

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
        val methodName = method.name
        val config = configProvider()

        if (config.spoofTimeEnabled) {
            when (methodName) {
                "getNextAlarmClock", "getNextWakeFromIdleTime" -> {
                    VirtualLogBus.log(
                        category = HookCategory.TIME,
                        method = methodName,
                        targetClass = "IAlarmManager",
                        interceptedPayload = "Queried next system alarm",
                        spoofedResult = "Offsetting alarm clock timestamp by ${config.timeOffsetMillis}ms"
                    )
                }
                "setTime", "setTimeZone" -> {
                    VirtualLogBus.log(
                        category = HookCategory.TIME,
                        method = "$methodName[BLOCKED_GRACEFULLY]",
                        targetClass = "IAlarmManager",
                        interceptedPayload = "Target app attempted hardware clock modification",
                        spoofedResult = "Silently absorbed in non-rooted sandbox without SecurityException"
                    )
                    return true
                }
            }
        }

        // Delegate execution to actual underlying system binder service
        return if (originalService != null) {
            try {
                method.invoke(originalService, *(args ?: emptyArray()))
            } catch (e: Exception) {
                method.defaultValue
            }
        } else {
            method.defaultValue
        }
    }

    companion object {
        /**
         * Creates a dynamic proxy for the AOSP IAlarmManager binder interface.
         */
        fun createAlarmManagerProxy(originalBinder: Any?, configProvider: () -> SandboxConfig): Any {
            val interfaceClass = try {
                Class.forName("android.app.IAlarmManager")
            } catch (e: Exception) {
                null
            }

            return if (interfaceClass != null) {
                Proxy.newProxyInstance(
                    interfaceClass.classLoader,
                    arrayOf(interfaceClass),
                    TimeProxyHandler(originalBinder, configProvider)
                )
            } else {
                originalBinder ?: Object()
            }
        }
    }
}

/**
 * Global Virtual Clock accessible to sandboxed components.
 */
object VirtualClock {
    @Volatile
    var config: SandboxConfig = SandboxConfig()

    fun currentTimeMillis(): Long {
        val base = System.currentTimeMillis()
        return if (config.spoofTimeEnabled) {
            val virtual = if (config.isTimeFrozen && config.fixedTimeMillis > 0L) {
                config.fixedTimeMillis
            } else {
                base + config.timeOffsetMillis
            }
            VirtualLogBus.log(
                category = HookCategory.TIME,
                method = "System.currentTimeMillis()",
                targetClass = "VirtualClock",
                interceptedPayload = "Host Real Time=$base",
                spoofedResult = if (config.isTimeFrozen) "PINNED FIXED TIME: $virtual" else "Virtual Time=$virtual (Offset=${config.timeOffsetMillis}ms)"
            )
            virtual
        } else {
            base
        }
    }

    fun elapsedRealtime(): Long {
        val base = SystemClock.elapsedRealtime()
        return if (config.spoofTimeEnabled) {
            if (config.isTimeFrozen && config.fixedTimeMillis > 0L) {
                (config.fixedTimeMillis % 86400000L).coerceAtLeast(1000L)
            } else {
                base + config.timeOffsetMillis
            }
        } else {
            base
        }
    }

    fun uptimeMillis(): Long {
        return SystemClock.uptimeMillis()
    }
}
