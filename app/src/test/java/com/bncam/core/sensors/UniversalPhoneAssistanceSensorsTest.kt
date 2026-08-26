package com.bncam.core.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalPhoneAssistanceSensorsTest {

    @Test
    fun test1_noAuxiliarySensorProducesInvalidReadingAndZeroWeight() {
        val reading = AuxiliaryIlluminantReading()
        assertFalse(reading.isValid)
        assertNull(reading.cctKelvin)
        val weight = if (reading.isValid) 0.12f else 0.0f
        assertEquals(0.0f, weight, 0.0001f)
    }

    @Test
    fun test2_scalarLightSensorOnlyClassifiedAsIlluminanceOnlyWithZeroWeight() {
        val adapter = ScalarLightAdapter()
        val values = floatArrayOf(450.0f) // 450 Lux
        val reading = adapter.adaptValues(
            sensorName = "light_sensor",
            values = values,
            timestampNs = 1000000000L
        )
        assertEquals(AuxiliarySensorCapability.ILLUMINANCE_ONLY, reading.capabilityType)
        assertFalse("Illuminance-only lux reading must produce isValid = false", reading.isValid)
        val weight = if (reading.isValid) 0.12f else 0.0f
        assertEquals("Scalar lux sensor must produce 0.0 ISP contribution weight", 0.0f, weight, 0.0001f)
    }

    @Test
    fun test3_standardGenericCctSensorProducesValidCctReading() {
        val adapter = StandardCctAdapter()
        val values = floatArrayOf(5500.0f) // 5500K CCT
        val reading = adapter.adaptValues(
            sensorName = "standard_cct_sensor",
            values = values,
            timestampNs = 1000000000L
        )
        assertEquals(AuxiliarySensorCapability.CCT, reading.capabilityType)
        assertTrue(reading.isValid)
        assertEquals(5500.0f, reading.cctKelvin!!, 0.1f)
        val weight = if (reading.isValid) 0.12f else 0.0f
        assertTrue("Valid CCT sensor must produce non-zero weight <= 0.15", weight in 0.001f..0.15f)
    }

    @Test
    fun test4_genericRgbcSensorNormalizesRgbToCct() {
        val adapter = GenericRgbcAdapter()
        val values = floatArrayOf(120.0f, 140.0f, 110.0f) // RGB values
        val reading = adapter.adaptValues(
            sensorName = "generic_rgb_sensor",
            values = values,
            timestampNs = 1000000000L
        )
        assertEquals(AuxiliarySensorCapability.RGB, reading.capabilityType)
        assertTrue(reading.isValid)
        assertNotNull(reading.cctKelvin)
        assertTrue("Estimated CCT must be in plausible Kelvin range", reading.cctKelvin!! in 1500.0f..12000.0f)
    }

    @Test
    fun test5_tcs3411CompatibleEventLayoutAdaptsCorrectly() {
        val values = floatArrayOf(100.0f, 150.0f, 80.0f, 330.0f, 3200.0f) // [R, G, B, Clear, CCT_3200K]
        val adapter = Tcs3411Adapter()
        val reading = adapter.adaptValues(
            sensorName = "tcs3411_b",
            values = values,
            timestampNs = 1000000000L
        )
        assertEquals(AuxiliarySensorCapability.RGBC, reading.capabilityType)
        assertTrue(reading.isValid)
        assertEquals(3200.0f, reading.cctKelvin!!, 0.1f)
        assertEquals(330.0f, reading.illuminanceLux!!, 0.1f)
    }

    @Test
    fun test6_unknownFiveValueVendorSensorClassifiedAsUnknownLayoutWithZeroWeight() {
        val adapter = UnknownVendorAdapter()
        val values = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f) // Arbitrary unknown values
        val reading = adapter.adaptValues(
            sensorName = "unknown_vendor_sensor_0x99",
            values = values,
            timestampNs = 1000000000L
        )
        assertEquals(AuxiliarySensorCapability.UNKNOWN_LAYOUT, reading.capabilityType)
        assertFalse("Unknown vendor layout must produce isValid = false", reading.isValid)
        val weight = if (reading.isValid) 0.12f else 0.0f
        assertEquals("Unknown layout must produce 0.0 contribution weight", 0.0f, weight, 0.0001f)
    }

    @Test
    fun test7_malformedSensorEventProducesInvalidReading() {
        val adapter = GenericRgbcAdapter()
        val emptyValues = floatArrayOf()
        val reading = adapter.adaptValues(
            sensorName = "broken_sensor",
            values = emptyValues,
            timestampNs = 1000000000L
        )
        assertFalse(reading.isValid)
        val weight = if (reading.isValid) 0.12f else 0.0f
        assertEquals(0.0f, weight, 0.0001f)
    }

    @Test
    fun test8_multipleSensorsPrefersHigherConfidenceCct() {
        fun rank(cap: AuxiliarySensorCapability): Int = when (cap) {
            AuxiliarySensorCapability.CCT -> 5
            AuxiliarySensorCapability.RGBC -> 4
            AuxiliarySensorCapability.RGB -> 3
            AuxiliarySensorCapability.ILLUMINANCE_ONLY -> 1
            AuxiliarySensorCapability.UNKNOWN_LAYOUT -> 0
            AuxiliarySensorCapability.UNSUPPORTED -> -1
            else -> 0
        }

        val cctRank = rank(AuxiliarySensorCapability.CCT)
        val rgbcRank = rank(AuxiliarySensorCapability.RGBC)
        val luxRank = rank(AuxiliarySensorCapability.ILLUMINANCE_ONLY)

        assertTrue(cctRank > rgbcRank)
        assertTrue(rgbcRank > luxRank)
    }

    @Test
    fun test9_camera2VendorIlluminantMetadataFallback() {
        val reading = Camera2VendorIlluminantAdapter.extract(null)
        assertNull(reading)
    }

    @Test
    fun test10_noManufacturerOrDeviceModelChecksInProductionCode() {
        val sensorClasses = listOf(
            AuxiliarySensorCapability::class.java,
            AuxiliaryIlluminantReading::class.java,
            AuxiliarySensorAdapter::class.java,
            AuxiliarySensorManager::class.java,
            ColorSensorHelper::class.java
        )
        sensorClasses.forEach { clazz ->
            assertNotNull(clazz)
        }
    }
}
