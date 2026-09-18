package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StreamConfigurationRuntimeFingerprintTest {
    @Test
    fun `auto ignores dormant validated and manual values`() {
        val a = StreamConfigurationRuntimeFingerprint.photo(
            configuration = StreamClassConfiguration(
                mode = StreamConfigurationMode.AUTO,
                validatedCandidateId = "PHOTO:37:4096x3072"
            ),
            raw10Binding = "0x25",
            rawSensorBinding = "12345"
        )
        val b = StreamConfigurationRuntimeFingerprint.photo(
            configuration = StreamClassConfiguration(mode = StreamConfigurationMode.AUTO),
            raw10Binding = "AUTO",
            rawSensorBinding = "AUTO"
        )
        assertEquals("AUTO", a)
        assertEquals(a, b)
    }

    @Test
    fun `validated fingerprint changes only with active candidate`() {
        val autoCandidate = StreamConfigurationRuntimeFingerprint.photo(
            StreamClassConfiguration(mode = StreamConfigurationMode.VALIDATED)
        )
        val explicit = StreamConfigurationRuntimeFingerprint.photo(
            StreamClassConfiguration(
                mode = StreamConfigurationMode.VALIDATED,
                validatedCandidateId = "PHOTO:37:4096x3072"
            )
        )
        assertEquals("VALIDATED:AUTO_CANDIDATE", autoCandidate)
        assertNotEquals(autoCandidate, explicit)
    }

    @Test
    fun `manual fingerprint includes both raw binding authorities`() {
        val first = StreamConfigurationRuntimeFingerprint.photo(
            StreamClassConfiguration(mode = StreamConfigurationMode.MANUAL),
            raw10Binding = " auto ",
            rawSensorBinding = "0x1234"
        )
        val second = StreamConfigurationRuntimeFingerprint.photo(
            StreamClassConfiguration(mode = StreamConfigurationMode.MANUAL),
            raw10Binding = "37",
            rawSensorBinding = "0x1234"
        )
        assertEquals("MANUAL:RAW10=AUTO:RAW_SENSOR=0X1234", first)
        assertNotEquals(first, second)
    }
}
