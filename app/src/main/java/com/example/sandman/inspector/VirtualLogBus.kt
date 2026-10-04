package com.example.sandman.inspector

import android.content.Context
import com.example.sandman.model.HookCategory
import com.example.sandman.model.HookLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Thread-safe log bus for interceptor and virtualization events.
 * Supports cross-process sync between Host UI and :sandbox_env process.
 */
object VirtualLogBus {
    private const val MAX_LOGS = 250
    private val memoryLogs = ConcurrentLinkedDeque<HookLogEntry>()
    private val _logsFlow = MutableStateFlow<List<HookLogEntry>>(emptyList())
    val logsFlow: StateFlow<List<HookLogEntry>> = _logsFlow.asStateFlow()

    private var logDir: File? = null

    fun initialize(context: Context) {
        val baseDir = File(context.filesDir, "sandbox_logs")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        logDir = baseDir
        loadPersistedLogs()
    }

    @Synchronized
    fun log(
        category: HookCategory,
        method: String,
        targetClass: String,
        interceptedPayload: String,
        spoofedResult: String,
        callingPackage: String = "target.virtual.app"
    ) {
        val entry = HookLogEntry(
            category = category,
            method = method,
            targetClass = targetClass,
            interceptedPayload = interceptedPayload,
            spoofedResult = spoofedResult,
            callingPackage = callingPackage
        )

        memoryLogs.addFirst(entry)
        while (memoryLogs.size > MAX_LOGS) {
            memoryLogs.pollLast()
        }

        _logsFlow.value = memoryLogs.toList()
        persistEntry(entry)
    }

    private fun persistEntry(entry: HookLogEntry) {
        try {
            val dir = logDir ?: return
            val file = File(dir, "hook_stream.log")
            val line = "${entry.timestamp}|${entry.category.name}|${entry.method}|${entry.targetClass}|${entry.interceptedPayload}|${entry.spoofedResult}|${entry.callingPackage}\n"
            file.appendText(line)
        } catch (_: Exception) {
            // Ignore file write errors during high-frequency hooking
        }
    }

    fun loadPersistedLogs() {
        try {
            val dir = logDir ?: return
            val file = File(dir, "hook_stream.log")
            if (!file.exists()) return

            val lines = file.readLines().takeLast(MAX_LOGS)
            memoryLogs.clear()
            for (line in lines.reversed()) {
                val parts = line.split("|")
                if (parts.size >= 7) {
                    val entry = HookLogEntry(
                        timestamp = parts[0].toLongOrNull() ?: System.currentTimeMillis(),
                        category = try { HookCategory.valueOf(parts[1]) } catch (_: Exception) { HookCategory.AOSP_BINDER },
                        method = parts[2],
                        targetClass = parts[3],
                        interceptedPayload = parts[4],
                        spoofedResult = parts[5],
                        callingPackage = parts[6]
                    )
                    memoryLogs.addLast(entry)
                }
            }
            _logsFlow.value = memoryLogs.toList()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clear() {
        memoryLogs.clear()
        _logsFlow.value = emptyList()
        try {
            val dir = logDir ?: return
            val file = File(dir, "hook_stream.log")
            if (file.exists()) file.delete()
        } catch (_: Exception) {
        }
    }
}
