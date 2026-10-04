package com.example.sandman.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HookCategory {
    LOCATION,
    TIME,
    TELEPHONY,
    STORAGE,
    CLASSLOADER,
    LIFECYCLE,
    AOSP_BINDER
}

data class HookLogEntry(
    val id: Long = System.currentTimeMillis() + (0..999).random(),
    val timestamp: Long = System.currentTimeMillis(),
    val category: HookCategory,
    val method: String,
    val targetClass: String,
    val interceptedPayload: String,
    val spoofedResult: String,
    val callingPackage: String = "target.virtual.app"
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
}
