package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicSensorProfileTest {

    @Test
    fun `wide angle 24mm equivalent with OIS provides high handheld ceiling`() {
        // Sensor: 9.6mm x 7.2mm (diag = 12.0mm). Crop factor: 43.27 / 12 = 3.606
        // Native focal length = 6.66mm -> 35mm eq ~ 24.0mm
        val profile = DynamicSensorProfile.compute(
            sensorWidthMm = 9.6f,
            sensorHeightMm = 7.2f,
            nativeFocalLengthMm = 6.66f,
            hasOis = true
        )

        assertTrue(profile.hasOis)
        assertEquals(24.0f, profile.focalLength35mmEq, 0.5f)
        // 1/24s * 4 = 166.7ms -> capped at 66.7ms (1/15s)
        assertTrue(profile.maxHandheldShutterNs >= 50_000_000L)
        assertTrue(profile.maxHandheldShutterNs <= 66_700_000L)
    }

    @Test
    fun `ultrawide 14mm equivalent without OIS uses rule of thumb ceiling`() {
        // Sensor: 6.0mm x 4.5mm (diag = 7.5mm). Crop factor: 43.27 / 7.5 = 5.769
        // Native focal length = 2.43mm -> 35mm eq ~ 14.0mm
        val profile = DynamicSensorProfile.compute(
            sensorWidthMm = 6.0f,
            sensorHeightMm = 4.5f,
            nativeFocalLengthMm = 2.43f,
            hasOis = false
        )

        assertFalse(profile.hasOis)
        assertEquals(14.0f, profile.focalLength35mmEq, 0.5f)
        // 1/14s ~ 71.4ms -> capped at 66.7ms (1/15s)
        assertEquals(66_666_667L, profile.maxHandheldShutterNs)
    }

    @Test
    fun `telephoto 120mm equivalent with OIS limits exposure to safe ceiling`() {
        // Sensor: 6.0mm x 4.5mm (diag = 7.5mm). Crop factor: 5.769
        // Native focal length = 20.8mm -> 35mm eq ~ 120.0mm
        val profile = DynamicSensorProfile.compute(
            sensorWidthMm = 6.0f,
            sensorHeightMm = 4.5f,
            nativeFocalLengthMm = 20.8f,
            hasOis = true
        )

        assertTrue(profile.hasOis)
        assertEquals(120.0f, profile.focalLength35mmEq, 1.0f)
        // 1/120s * 4 = 1/30s ~ 33.3ms
        val expectedNs = (1.0 / 120.0 * 4.0 * 1_000_000_000.0).toLong()
        assertTrue(Math.abs(expectedNs - profile.maxHandheldShutterNs) <= 1_000_000L)
    }

    @Test
    fun `telephoto without OIS clamps to minimum handheld floor`() {
        // 35mm eq ~ 120.0mm without OIS: 1/120s = 8.33ms -> clamped to min floor 10ms (1/100s)
        val profile = DynamicSensorProfile.compute(
            sensorWidthMm = 6.0f,
            sensorHeightMm = 4.5f,
            nativeFocalLengthMm = 20.8f,
            hasOis = false
        )

        assertFalse(profile.hasOis)
        assertEquals(10_000_000L, profile.maxHandheldShutterNs)
    }

    @Test
    fun `handles missing or null characteristics gracefully`() {
        val profile = DynamicSensorProfile.compute(
            sensorWidthMm = null,
            sensorHeightMm = null,
            nativeFocalLengthMm = null,
            hasOis = false
        )

        assertTrue(profile.focalLength35mmEq > 0f)
        assertTrue(profile.maxHandheldShutterNs in 10_000_000L..66_700_000L)
        assertEquals(100, profile.minIso)
        assertEquals(6400, profile.maxIso)
        assertEquals(1600, profile.analogGainLimitIso)
    }
}
