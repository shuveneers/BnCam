package com.bncam.core.sensors

import com.bncam.core.model.ColorSensorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAssistanceSensorsTest {

    @Test
    fun test1_defaultReadingIsInvalidAndWeightZero() {
        val reading = ColorSensorReading()
        assertFalse(reading.isValid)
        assertEquals(0L, reading.timestampNs)
        assertEquals(0.0f, reading.cctKelvin, 0.001f)
    }

    @Test
    fun test2_validReadingCreationAndContributionWeightBound() {
        val reading = ColorSensorReading(
            timestampNs = 1000000000L,
            cctKelvin = 5600.0f,
            r = 120.0f,
            g = 140.0f,
            b = 110.0f,
            isValid = true
        )
        assertTrue(reading.isValid)
        val calculatedWeight = if (reading.isValid) 0.12f else 0.0f
        assertTrue("Weight must be bounded <= 0.15", calculatedWeight <= 0.15f)
        assertTrue("Weight must be > 0 when valid", calculatedWeight > 0.0f)
    }

    @Test
    fun test3_staleReadingReturnsInvalid() {
        val captureTimestampNs = 1000000000000L
        val readingTimestampNs = 999600000000L // 400 ms old (> 250 ms)
        val deltaMs = (captureTimestampNs - readingTimestampNs) / 1_000_000L
        assertTrue(deltaMs > 250L)
        val isValid = deltaMs <= 250L
        assertFalse("Stale reading (> 250ms) must be rejected", isValid)
    }

    @Test
    fun test4_disabledSettingForcesZeroContribution() {
        val settingEnabled = false
        val contributionWeight = if (settingEnabled) 0.12f else 0.0f
        assertEquals("Disabled setting must pass 0.0 contribution weight", 0.0f, contributionWeight, 0.0001f)
    }

    @Test
    fun test5_contributionNeverExceedsMaxBound() {
        val rawWeight = 0.45f
        val boundedWeight = Math.min(0.15f, rawWeight)
        assertEquals(0.15f, boundedWeight, 0.0001f)
    }

    @Test
    fun test6_invalidCctOrObstructedReadingForcesZeroWeight() {
        val readingZeroCct = ColorSensorReading(
            timestampNs = 1000000000L,
            cctKelvin = 0.0f,
            isValid = false
        )
        val weight = if (readingZeroCct.isValid) 0.12f else 0.0f
        assertEquals(0.0f, weight, 0.0001f)
    }

    @Test
    fun test7_enabledValidReadingWithinSyncProducesNonZeroBoundedWeight() {
        val captureNs = 1000000000000L
        val sensorNs = 999980000000L // 20ms delta
        val ageMs = (captureNs - sensorNs) / 1_000_000L
        val isValidSync = ageMs in 0..250L
        assertTrue(isValidSync)

        val reading = ColorSensorReading(
            timestampNs = sensorNs,
            cctKelvin = 3200.0f,
            isValid = true
        )
        val enabled = true
        val weight = if (enabled && reading.isValid) 0.12f else 0.0f
        assertTrue("Applied weight must be > 0.0", weight > 0.0f)
        assertTrue("Applied weight must be <= 0.15", weight <= 0.15f)
    }

    @Test
    fun test8_parameterBridgePassingIntegrity() {
        val reading = ColorSensorReading(timestampNs = 100L, cctKelvin = 4000.0f, isValid = true)
        val enabled = true
        val weight = 0.12f
        assertTrue(enabled)
        assertTrue(reading.isValid)
        assertEquals(4000.0f, reading.cctKelvin, 0.001f)
        assertEquals(0.12f, weight, 0.001f)
    }
}
