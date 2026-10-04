package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.sandman.hooks.VirtualClock
import com.example.sandman.model.SandboxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Sandman", appName)
  }

  @Test
  fun `verify sandbox config serialization`() {
    val config = SandboxConfig(
      fakeLatitude = 37.7749,
      fakeLongitude = -122.4194,
      timeOffsetMillis = 3600000L,
      isTimeFrozen = true,
      fixedTimeMillis = 1735689600000L,
      spoofedCarrier = "Sandman Telecom"
    )
    val json = config.toJson()
    val parsed = SandboxConfig.fromJson(json)

    assertEquals(37.7749, parsed.fakeLatitude, 0.0001)
    assertEquals(-122.4194, parsed.fakeLongitude, 0.0001)
    assertEquals(3600000L, parsed.timeOffsetMillis)
    assertEquals(true, parsed.isTimeFrozen)
    assertEquals(1735689600000L, parsed.fixedTimeMillis)
    assertEquals("Sandman Telecom", parsed.spoofedCarrier)
  }

  @Test
  fun `verify virtual clock temporal offset`() {
    val offset = 7200000L
    VirtualClock.config = SandboxConfig(
      spoofTimeEnabled = true,
      isTimeFrozen = false,
      timeOffsetMillis = offset
    )

    val realNow = System.currentTimeMillis()
    val virtualNow = VirtualClock.currentTimeMillis()

    assertTrue(virtualNow >= realNow + offset - 50)
  }

  @Test
  fun `verify virtual clock fixed specific date and time`() {
    val specificFixedTime = 1735689600000L // 2025-01-01 00:00:00 UTC
    VirtualClock.config = SandboxConfig(
      spoofTimeEnabled = true,
      isTimeFrozen = true,
      fixedTimeMillis = specificFixedTime
    )

    val fixedNow1 = VirtualClock.currentTimeMillis()
    Thread.sleep(10)
    val fixedNow2 = VirtualClock.currentTimeMillis()

    assertEquals(specificFixedTime, fixedNow1)
    assertEquals(specificFixedTime, fixedNow2)
  }
}
