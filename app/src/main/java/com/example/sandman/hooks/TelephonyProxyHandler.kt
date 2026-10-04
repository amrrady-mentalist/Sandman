package com.example.sandman.hooks

import android.telephony.TelephonyManager
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import com.example.sandman.model.SandboxConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * =========================================================================================
 * ARCHITECTURE DEEP-DIVE: AOSP TELEPHONY SUBSYSTEM & HARDWARE IDENTIFIER SPOOFING
 * =========================================================================================
 * In modern Android (API 29+ Android 10+):
 * Access to non-resettable hardware identifiers (IMEI, MEID, Serial Number) is restricted
 * to apps with system-privileged permission `READ_PRIVILEGED_PHONE_STATE`. Third-party apps
 * querying `getDeviceId()` or `getImei()` receive a `SecurityException` or null.
 *
 * Telephony IPC Architecture:
 * 1. `Context.getSystemService(Context.TELEPHONY_SERVICE)` returns `TelephonyManager`.
 * 2. `TelephonyManager` talks across IPC to `com.android.internal.telephony.ITelephony` and
 *    `com.android.internal.telephony.ISub` (Subscription Manager).
 *
 * How Sandman Intercepts Telephony Without Root:
 * Sandman intercepts client calls using Java Dynamic Proxy on the `ITelephony` interface.
 * When the sandboxed app calls:
 * - `getDeviceId()` / `getImei()`: Sandman returns the configured spoofed IMEI.
 * - `getSimOperatorName()` / `getNetworkOperatorName()`: Returns the custom spoofed carrier name.
 * - `getSimState()`: Returns `TelephonyManager.SIM_STATE_READY` (5).
 * - `getSimCountryIso()` / `getNetworkCountryIso()`: Returns the configured ISO code.
 * - `getDataNetworkType()` / `getNetworkType()`: Returns `NETWORK_TYPE_NR` (5G) or LTE.
 * =========================================================================================
 */
class TelephonyProxyHandler(
    private val originalService: Any?,
    private val configProvider: () -> SandboxConfig
) : InvocationHandler {

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
        val methodName = method.name
        val config = configProvider()

        if (!config.spoofTelephonyEnabled) {
            return delegate(method, args)
        }

        when (methodName) {
            "getDeviceId", "getImei", "getMeid" -> {
                VirtualLogBus.log(
                    category = HookCategory.TELEPHONY,
                    method = methodName,
                    targetClass = "ITelephony",
                    interceptedPayload = "Hardware IMEI query intercepted",
                    spoofedResult = config.spoofedImei
                )
                return config.spoofedImei
            }

            "getSubscriberId" -> {
                val fakeImsi = "310410" + config.spoofedImei.takeLast(9)
                VirtualLogBus.log(
                    category = HookCategory.TELEPHONY,
                    method = "getSubscriberId",
                    targetClass = "ITelephony",
                    interceptedPayload = "IMSI subscriber query",
                    spoofedResult = fakeImsi
                )
                return fakeImsi
            }

            "getSimOperatorName", "getNetworkOperatorName" -> {
                VirtualLogBus.log(
                    category = HookCategory.TELEPHONY,
                    method = methodName,
                    targetClass = "ITelephony",
                    interceptedPayload = "Carrier name queried",
                    spoofedResult = config.spoofedCarrier
                )
                return config.spoofedCarrier
            }

            "getSimOperator", "getNetworkOperator" -> {
                return "310410" // Default US MCC/MNC
            }

            "getSimCountryIso", "getNetworkCountryIso" -> {
                return config.spoofedCountryIso.lowercase()
            }

            "getSimState" -> {
                VirtualLogBus.log(
                    category = HookCategory.TELEPHONY,
                    method = "getSimState",
                    targetClass = "ITelephony",
                    interceptedPayload = "SIM card status queried",
                    spoofedResult = "SIM_STATE_READY (5)"
                )
                return TelephonyManager.SIM_STATE_READY
            }

            "getDataNetworkType", "getNetworkType", "getVoiceNetworkType" -> {
                // Return TelephonyManager.NETWORK_TYPE_NR (20) for 5G
                return 20
            }

            "getPhoneType" -> {
                return TelephonyManager.PHONE_TYPE_GSM
            }

            "isDataEnabled", "isNetworkRoaming" -> {
                return if (methodName == "isDataEnabled") true else false
            }
        }

        return delegate(method, args)
    }

    private fun delegate(method: Method, args: Array<out Any?>?): Any? {
        return if (originalService != null) {
            try {
                method.invoke(originalService, *(args ?: emptyArray()))
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    companion object {
        fun createProxy(originalBinder: Any?, configProvider: () -> SandboxConfig): Any {
            val interfaceClass = try {
                Class.forName("com.android.internal.telephony.ITelephony")
            } catch (e: Exception) {
                null
            }

            return if (interfaceClass != null) {
                Proxy.newProxyInstance(
                    interfaceClass.classLoader,
                    arrayOf(interfaceClass),
                    TelephonyProxyHandler(originalBinder, configProvider)
                )
            } else {
                originalBinder ?: Object()
            }
        }
    }
}
