package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicSensorProfileTest {

    @Test
    fun `wide angle 24mm equivalent uses calm one over focal fallback without invented ois stops`() {
        val profile = DynamicSensorProfile.compute(
            sensorWidthMm = 9.6f,
            sensorHeightMm = 7.2f,
            nativeFocalLengthMm = 6.66f,
            hasOis = true
        )

        assertTrue(profile.hasOis)
        assertEquals(24.0f, profile.focalLength35mmEq, 0.5f)
        val expectedNs = (1.0 / 24.0 * 1_000_000_000.0).toLong()
        assertTrue(Math.abs(expectedNs - profile.maxHandheldShutterNs) <= 1_500_000L)
    }

    @Test
    fun `ultrawide 14mm equivalent without ois uses conservative upper fallback ceiling`() {
        val profile = DynamicSensorProfile.compute(
            sensorWidthMm = 6.0f,
            sensorHeightMm = 4.5f,
            nativeFocalLengthMm = 2.43f,
            hasOis = false
        )

        assertFalse(profile.hasOis)
        assertEquals(14.0f, profile.focalLength35mmEq, 0.5f)
        assertEquals(66_666_667L, profile.maxHandheldShutterNs)
    }

    @Test
    fun `telephoto ois capability is recorded but does not fabricate stabilization stops`() {
        val profile = DynamicSensorProfile.compute(
            sensorWidthMm = 6.0f,
            sensorHeightMm = 4.5f,
            nativeFocalLengthMm = 20.8f,
            hasOis = true
        )

        assertTrue(profile.hasOis)
        assertEquals(120.0f, profile.focalLength35mmEq, 1.0f)
        assertEquals(10_000_000L, profile.maxHandheldShutterNs)
    }

    @Test
    fun `telephoto without ois uses same uncalibrated lens fallback floor`() {
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
    fun `explicit maximum analog sensitivity is preserved as sensor truth`() {
        val profile = DynamicSensorProfile.compute(
            sensorWidthMm = 9.6f,
            sensorHeightMm = 7.2f,
            nativeFocalLengthMm = 6.66f,
            hasOis = true,
            minIso = 50,
            maxIso = 12_800,
            maxAnalogSensitivityIso = 3_200
        )

        assertEquals(3_200, profile.analogGainLimitIso)
    }

    @Test
    fun `unknown analog limit does not invent iso 1600 threshold`() {
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
        assertEquals(6400, profile.analogGainLimitIso)
    }
}
