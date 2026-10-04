package com.example.sandman.model

import org.json.JSONObject

/**
 * Configuration state for the Sandman non-rooted virtual container.
 * Stores target parameters for Location, Time, Telephony, and Filesystem spoofing.
 */
data class SandboxConfig(
    // Location Spoofing
    val spoofLocationEnabled: Boolean = true,
    val fakeLatitude: Double = 35.6586,       // Default: Tokyo Shibuya
    val fakeLongitude: Double = 139.7454,
    val fakeAltitude: Double = 35.0,
    val fakeAccuracy: Float = 3.5f,
    val fakeSpeed: Float = 0.0f,
    val fakeBearing: Float = 90.0f,

    // Time & Temporal Drift Spoofing
    val spoofTimeEnabled: Boolean = true,
    val timeOffsetMillis: Long = 86400000L,   // Default: +24 Hours (1 day in future)
    val isTimeFrozen: Boolean = false,        // If true, clock is permanently fixed/pinned to fixedTimeMillis
    val fixedTimeMillis: Long = 1735689600000L, // Default anchor: 2025-01-01 00:00:00 UTC
    val timeMultiplier: Float = 1.0f,

    // Telephony Spoofing
    val spoofTelephonyEnabled: Boolean = true,
    val spoofedImei: String = "867530901234567",
    val spoofedCarrier: String = "Sandman Virtual Telecom",
    val spoofedCountryIso: String = "us",
    val spoofedNetworkType: String = "5G NR",
    val spoofedSimState: String = "READY",

    // Storage Isolation
    val isolatedStorageEnabled: Boolean = true,

    // Verbose Interception Logging
    val loggingEnabled: Boolean = true
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("spoofLocationEnabled", spoofLocationEnabled)
        json.put("fakeLatitude", fakeLatitude)
        json.put("fakeLongitude", fakeLongitude)
        json.put("fakeAltitude", fakeAltitude)
        json.put("fakeAccuracy", fakeAccuracy.toDouble())
        json.put("fakeSpeed", fakeSpeed.toDouble())
        json.put("fakeBearing", fakeBearing.toDouble())

        json.put("spoofTimeEnabled", spoofTimeEnabled)
        json.put("timeOffsetMillis", timeOffsetMillis)
        json.put("isTimeFrozen", isTimeFrozen)
        json.put("fixedTimeMillis", fixedTimeMillis)
        json.put("timeMultiplier", timeMultiplier.toDouble())

        json.put("spoofTelephonyEnabled", spoofTelephonyEnabled)
        json.put("spoofedImei", spoofedImei)
        json.put("spoofedCarrier", spoofedCarrier)
        json.put("spoofedCountryIso", spoofedCountryIso)
        json.put("spoofedNetworkType", spoofedNetworkType)
        json.put("spoofedSimState", spoofedSimState)

        json.put("isolatedStorageEnabled", isolatedStorageEnabled)
        json.put("loggingEnabled", loggingEnabled)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): SandboxConfig {
            return try {
                val json = JSONObject(jsonStr)
                SandboxConfig(
                    spoofLocationEnabled = json.optBoolean("spoofLocationEnabled", true),
                    fakeLatitude = json.optDouble("fakeLatitude", 35.6586),
                    fakeLongitude = json.optDouble("fakeLongitude", 139.7454),
                    fakeAltitude = json.optDouble("fakeAltitude", 35.0),
                    fakeAccuracy = json.optDouble("fakeAccuracy", 3.5).toFloat(),
                    fakeSpeed = json.optDouble("fakeSpeed", 0.0).toFloat(),
                    fakeBearing = json.optDouble("fakeBearing", 90.0).toFloat(),

                    spoofTimeEnabled = json.optBoolean("spoofTimeEnabled", true),
                    timeOffsetMillis = json.optLong("timeOffsetMillis", 86400000L),
                    isTimeFrozen = json.optBoolean("isTimeFrozen", false),
                    fixedTimeMillis = json.optLong("fixedTimeMillis", 1735689600000L),
                    timeMultiplier = json.optDouble("timeMultiplier", 1.0).toFloat(),

                    spoofTelephonyEnabled = json.optBoolean("spoofTelephonyEnabled", true),
                    spoofedImei = json.optString("spoofedImei", "867530901234567"),
                    spoofedCarrier = json.optString("spoofedCarrier", "Sandman Virtual Telecom"),
                    spoofedCountryIso = json.optString("spoofedCountryIso", "us"),
                    spoofedNetworkType = json.optString("spoofedNetworkType", "5G NR"),
                    spoofedSimState = json.optString("spoofedSimState", "READY"),

                    isolatedStorageEnabled = json.optBoolean("isolatedStorageEnabled", true),
                    loggingEnabled = json.optBoolean("loggingEnabled", true)
                )
            } catch (e: Exception) {
                SandboxConfig()
            }
        }
    }
}
