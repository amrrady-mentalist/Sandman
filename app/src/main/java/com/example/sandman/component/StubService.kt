package com.example.sandman.component

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory

/**
 * Host shell service declared in AndroidManifest.xml running in :sandbox_env.
 * Used for hosting sandboxed Android services.
 */
class StubService : Service() {

    override fun onCreate() {
        super.onCreate()
        VirtualLogBus.log(
            category = HookCategory.LIFECYCLE,
            method = "StubService.onCreate",
            targetClass = "StubService",
            interceptedPayload = "Sandboxed background service shell initialized",
            spoofedResult = "Running in process :sandbox_env"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetPackage = intent?.getStringExtra("target_package") ?: "unknown"
        VirtualLogBus.log(
            category = HookCategory.LIFECYCLE,
            method = "StubService.onStartCommand",
            targetClass = "StubService",
            interceptedPayload = "Target Package: $targetPackage, startId=$startId",
            spoofedResult = "Handled in virtual container",
            callingPackage = targetPackage
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        VirtualLogBus.log(
            category = HookCategory.LIFECYCLE,
            method = "StubService.onDestroy",
            targetClass = "StubService",
            interceptedPayload = "Sandboxed service terminated",
            spoofedResult = "Lifecycle cleanup complete"
        )
    }
}
