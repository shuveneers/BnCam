package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStreamSettingsModelTest {
    @Test
    fun `defaults expose no stream override`() {
        val settings = PhotoStreamSettings().sanitized()
        assertNull(settings.specificRawSizeIndex)
        assertNull(settings.resolutionFixReferenceFormatCode)
        assertFalse(settings.hasExplicitResolutionOverride)
    }

    @Test
    fun `valid runtime controls survive sanitization`() {
        val settings = PhotoStreamSettings(
            specificRawSizeIndex = 2,
            resolutionFixReferenceFormatCode = 35
        ).sanitized()
        assertEquals(2, settings.specificRawSizeIndex)
        assertEquals(35, settings.resolutionFixReferenceFormatCode)
        assertTrue(settings.hasExplicitResolutionOverride)
    }

    @Test
    fun `negative persisted values are discarded`() {
        val settings = PhotoStreamSettings(
            specificRawSizeIndex = -1,
            resolutionFixReferenceFormatCode = -2
        ).sanitized()
        assertNull(settings.specificRawSizeIndex)
        assertNull(settings.resolutionFixReferenceFormatCode)
    }

    @Test
    fun `runtime fingerprint tracks resolution and raw support bindings`() {
        val base = PhotoStreamRuntimeFingerprint.create(PhotoStreamSettings())
        val resolution = PhotoStreamRuntimeFingerprint.create(
            PhotoStreamSettings(specificRawSizeIndex = 1)
        )
        val binding = PhotoStreamRuntimeFingerprint.create(
            PhotoStreamSettings(),
            raw10Binding = " 0x1234 ",
            rawSensorBinding = "auto"
        )
        assertNotEquals(base, resolution)
        assertNotEquals(base, binding)
        assertTrue(binding.contains("RAW10_BINDING=0X1234"))
    }
}
