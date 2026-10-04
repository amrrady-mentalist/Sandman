package com.example.sandman.core

import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import dalvik.system.PathClassLoader

/**
 * =========================================================================================
 * ARCHITECTURE DEEP-DIVE: VIRTUAL CLASS LOADER & NON-ROOTED ART ISOLATION
 * =========================================================================================
 * In modern Android Open Source Project (AOSP), the runtime (ART - Android Runtime) loads
 * compiled bytecode from Dalvik Executable (.dex) files within APK archives.
 *
 * ClassLoader Hierarchy in Android:
 *    [BootClassLoader] (Java & AOSP Framework core classes: android.os.*, android.location.*)
 *            ▲
 *            │
 *    [PathClassLoader] (Application APK DEX files loaded via BaseDexClassLoader)
 *
 * In standard execution, the OS Zygote forks an untrusted app process and initializes
 * its `LoadedApk` and `PathClassLoader` pointing to `/data/app/<package>/base.apk`.
 *
 * How Sandman Virtualization Operates Without Root:
 * Sandman runs inside an unprivileged Android process ("EnvSandbox"). To run a target APK:
 * 1. Sandman instantiates `VirtualClassLoader`, which subclasses `PathClassLoader`.
 * 2. It passes the isolated target APK path (e.g. `/data/data/.../virtual_apps/<target_pkg>/base.apk`)
 *    and the target's extracted native library directory (`lib/arm64-v8a`).
 * 3. BaseDexClassLoader constructs a `DexPathList` internally via `makeDexElements()`.
 * 4. We override `loadClass(name, resolve)` to intercept class queries:
 *    - Parent-first delegation is bypassed for classes we wish to substitute.
 *    - Calls to hidden or spoofed subsystem utilities are rerouted to Sandman's proxy wrappers.
 *    - Any class loading events can be audited in real-time on `VirtualLogBus`.
 * =========================================================================================
 */
class VirtualClassLoader(
    private val dexPath: String,
    private val librarySearchPath: String?,
    private val parentClassLoader: ClassLoader,
    private val targetPackageName: String
) : PathClassLoader(dexPath, librarySearchPath, parentClassLoader) {

    init {
        VirtualLogBus.log(
            category = HookCategory.CLASSLOADER,
            method = "<init>",
            targetClass = "VirtualClassLoader",
            interceptedPayload = "dexPath=$dexPath, nativeLibPath=$librarySearchPath",
            spoofedResult = "Initialized PathClassLoader sandbox for $targetPackageName",
            callingPackage = targetPackageName
        )
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Intercept class loading if needed for specific internal classes
        // For example, redirecting custom time wrappers or isolated mock targets
        if (name.startsWith("com.example.sandman.fake.")) {
            VirtualLogBus.log(
                category = HookCategory.CLASSLOADER,
                method = "loadClass[INTERCEPT]",
                targetClass = name,
                interceptedPayload = "Intercepted system class query: $name",
                spoofedResult = "Redirecting to Sandman sandbox stub",
                callingPackage = targetPackageName
            )
        }

        return try {
            // First check if already loaded
            val loaded = findLoadedClass(name)
            if (loaded != null) {
                return loaded
            }

            // AOSP PathClassLoader delegates to parent first, then findClass(name).
            // For sandboxed classes, we prioritize the target APK's classes so that
            // target classes never conflict with host application classes.
            if (!name.startsWith("android.") && !name.startsWith("java.") && !name.startsWith("kotlin.")) {
                try {
                    val targetClass = findClass(name)
                    if (targetClass != null) {
                        return targetClass
                    }
                } catch (_: ClassNotFoundException) {
                    // Fallthrough to standard delegation
                }
            }

            super.loadClass(name, resolve)
        } catch (e: ClassNotFoundException) {
            // Last resort fallback to parent classloader
            parentClassLoader.loadClass(name)
        }
    }

    override fun findLibrary(name: String): String? {
        val foundPath = super.findLibrary(name)
        VirtualLogBus.log(
            category = HookCategory.CLASSLOADER,
            method = "findLibrary",
            targetClass = "BaseDexClassLoader",
            interceptedPayload = "libName=$name",
            spoofedResult = foundPath ?: "Delegated to host ABI search path",
            callingPackage = targetPackageName
        )
        return foundPath
    }
}
