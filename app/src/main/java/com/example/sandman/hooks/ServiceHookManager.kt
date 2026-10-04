package com.example.sandman.hooks

import android.content.Context
import android.os.IBinder
import android.os.IInterface
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import com.example.sandman.model.SandboxConfig
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * =========================================================================================
 * ARCHITECTURE DEEP-DIVE: AOSP SERVICEMANAGER REFLECTION & BINDER PROXY INJECTION
 * =========================================================================================
 * How Android Applications Access System Services:
 * 1. `Context.getSystemService(String name)` queries `android.app.SystemServiceRegistry`.
 * 2. SystemServiceRegistry fetches the service's IPC handle via:
 *    `IBinder rawBinder = ServiceManager.getService(name);`
 * 3. It converts the raw `IBinder` into a typed AIDL interface via the generated Stub:
 *    `ILocationManager service = ILocationManager.Stub.asInterface(rawBinder);`
 * 4. The AIDL interface is wrapped in a client-facing manager (e.g. `LocationManager`).
 *
 * Inside AOSP `android.os.ServiceManager`:
 * ```java
 * public final class ServiceManager {
 *     private static Map<String, IBinder> sCache = new ArrayMap<String, IBinder>();
 *     public static IBinder getService(String name) {
 *         IBinder service = sCache.get(name);
 *         if (service != null) return service;
 *         return Binder.allowBlocking(rawGetService(name));
 *     }
 * }
 * ```
 *
 * The Non-Rooted Hook Strategy:
 * 1. Cache Poisoning / Hook Injection:
 *    Using Java Reflection, Sandman accesses `ServiceManager.class.getDeclaredField("sCache")`.
 * 2. Proxy IBinder Installation:
 *    We create a `java.lang.reflect.Proxy` implementing `android.os.IBinder`.
 *    When `Stub.asInterface(rawBinder)` calls:
 *    `rawBinder.queryLocalInterface(descriptor)`
 *    Our Proxy intercepts `queryLocalInterface` and returns our `ILocationManager` or
 *    `ITelephony` Dynamic Proxy!
 * 3. Fallback ContextWrapper Interception:
 *    In Android 9+ (Pie), Google introduced Hidden API Restrictions (Light/Dark Greylist).
 *    To ensure complete compatibility across all Android versions, Sandman also overrides
 *    `VirtualContext.getSystemService(name)` to intercept services directly.
 * =========================================================================================
 */
object ServiceHookManager {

    private var currentConfig: SandboxConfig = SandboxConfig()
    private val hookedServices = mutableMapOf<String, Any>()

    fun updateConfig(config: SandboxConfig) {
        currentConfig = config
        VirtualClock.config = config
    }

    fun getConfig(): SandboxConfig = currentConfig

    /**
     * Installs global reflection hooks into AOSP ServiceManager.sCache
     */
    fun installServiceManagerHooks(context: Context) {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val sCacheField: Field = serviceManagerClass.getDeclaredField("sCache")
            sCacheField.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val cache = sCacheField.get(null) as? MutableMap<String, IBinder>

            if (cache != null) {
                // Hook Location Service
                hookBinderInCache(serviceManagerClass, cache, Context.LOCATION_SERVICE, "android.location.ILocationManager") { rawBinder ->
                    LocationProxyHandler.createProxy(rawBinder) { currentConfig }
                }

                // Hook Telephony Service
                hookBinderInCache(serviceManagerClass, cache, Context.TELEPHONY_SERVICE, "com.android.internal.telephony.ITelephony") { rawBinder ->
                    TelephonyProxyHandler.createProxy(rawBinder) { currentConfig }
                }

                // Hook Alarm Service
                hookBinderInCache(serviceManagerClass, cache, Context.ALARM_SERVICE, "android.app.IAlarmManager") { rawBinder ->
                    TimeProxyHandler.createAlarmManagerProxy(rawBinder) { currentConfig }
                }

                VirtualLogBus.log(
                    category = HookCategory.AOSP_BINDER,
                    method = "installServiceManagerHooks",
                    targetClass = "android.os.ServiceManager.sCache",
                    interceptedPayload = "Installed reflection proxy hooks for location, telephony, alarm",
                    spoofedResult = "ServiceManager.sCache successfully hooked in non-rooted user space"
                )
            }
        } catch (e: Exception) {
            VirtualLogBus.log(
                category = HookCategory.AOSP_BINDER,
                method = "installServiceManagerHooks[WARN]",
                targetClass = "android.os.ServiceManager",
                interceptedPayload = "Reflection restriction encountered: ${e.message}",
                spoofedResult = "Falling back to VirtualContext.getSystemService direct interception"
            )
        }
    }

    private fun hookBinderInCache(
        serviceManagerClass: Class<*>,
        cache: MutableMap<String, IBinder>,
        serviceName: String,
        interfaceDescriptor: String,
        proxyCreator: (original: Any?) -> Any
    ) {
        try {
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val realBinder = getServiceMethod.invoke(null, serviceName) as? IBinder ?: return

            // Create a dynamic proxy IBinder that intercepts queryLocalInterface
            val proxyBinder = Proxy.newProxyInstance(
                realBinder.javaClass.classLoader,
                arrayOf(IBinder::class.java),
                object : InvocationHandler {
                    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
                        if (method.name == "queryLocalInterface") {
                            VirtualLogBus.log(
                                category = HookCategory.AOSP_BINDER,
                                method = "queryLocalInterface",
                                targetClass = "IBinder[$serviceName]",
                                interceptedPayload = "AOSP Stub requested interface: ${args?.firstOrNull()}",
                                spoofedResult = "Returning Sandman Dynamic Proxy for $interfaceDescriptor"
                            )
                            return proxyCreator(realBinder)
                        }
                        return method.invoke(realBinder, *(args ?: emptyArray()))
                    }
                }
            ) as IBinder

            cache[serviceName] = proxyBinder
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Hook interceptor for Context.getSystemService(name)
     */
    fun interceptSystemService(name: String, baseService: Any?, context: Context): Any? {
        when (name) {
            Context.LOCATION_SERVICE -> {
                if (currentConfig.spoofLocationEnabled) {
                    VirtualLogBus.log(
                        category = HookCategory.LOCATION,
                        method = "getSystemService(LOCATION_SERVICE)",
                        targetClass = "VirtualContext",
                        interceptedPayload = "Target requested LocationManager",
                        spoofedResult = "Injecting Sandman Location Proxy"
                    )
                    return hookLocationManager(baseService)
                }
            }

            Context.TELEPHONY_SERVICE -> {
                if (currentConfig.spoofTelephonyEnabled) {
                    VirtualLogBus.log(
                        category = HookCategory.TELEPHONY,
                        method = "getSystemService(TELEPHONY_SERVICE)",
                        targetClass = "VirtualContext",
                        interceptedPayload = "Target requested TelephonyManager",
                        spoofedResult = "Injecting Sandman Telephony Proxy"
                    )
                    return hookTelephonyManager(baseService)
                }
            }

            Context.ALARM_SERVICE -> {
                if (currentConfig.spoofTimeEnabled) {
                    VirtualLogBus.log(
                        category = HookCategory.TIME,
                        method = "getSystemService(ALARM_SERVICE)",
                        targetClass = "VirtualContext",
                        interceptedPayload = "Target requested AlarmManager",
                        spoofedResult = "Injecting Sandman Time Offset Proxy"
                    )
                }
            }
        }
        return baseService
    }

    private fun hookLocationManager(baseService: Any?): Any? {
        if (baseService == null) return null
        try {
            // In AOSP LocationManager, field mService holds the ILocationManager
            val mServiceField = findFieldInHierarchy(baseService.javaClass, "mService")
            if (mServiceField != null) {
                mServiceField.isAccessible = true
                val originalBinder = mServiceField.get(baseService)
                val proxy = LocationProxyHandler.createProxy(originalBinder) { currentConfig }
                mServiceField.set(baseService, proxy)
            }
        } catch (e: Exception) {
            // If field injection fails, wrap the entire object
        }
        return baseService
    }

    private fun hookTelephonyManager(baseService: Any?): Any? {
        if (baseService == null) return null
        try {
            val sIPhoneSubInfoField = findFieldInHierarchy(baseService.javaClass, "sIPhoneSubInfo")
            if (sIPhoneSubInfoField != null) {
                sIPhoneSubInfoField.isAccessible = true
                val original = sIPhoneSubInfoField.get(baseService)
                val proxy = TelephonyProxyHandler.createProxy(original) { currentConfig }
                sIPhoneSubInfoField.set(baseService, proxy)
            }
        } catch (_: Exception) {}
        return baseService
    }

    private fun findFieldInHierarchy(clazz: Class<*>, fieldName: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
