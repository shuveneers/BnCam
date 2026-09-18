package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhiteLevelSettingsModelTest {
    @Test
    fun `auto is default and has no developed manual override`() {
        val settings = LensWhiteLevelSettings().sanitized()
        assertEquals(WhiteLevelModes.AUTO, settings.mode)
        assertEquals("Auto", settings.summary())
        assertNull(settings.manualOverrideOrNull())
    }

    @Test
    fun `all AGC compatible white presets remain exact`() {
        val expected = listOf(1023, 4095, 16383, 65535)
        assertEquals(expected, WhiteLevelPresets.values.map { it.value })

        expected.forEach { value ->
            val settings = LensWhiteLevelSettings(
                mode = WhiteLevelModes.MANUAL,
                manualWhiteLevel = value
            ).sanitized()
            assertEquals(value, settings.manualOverrideOrNull())
        }
    }

    @Test
    fun `invalid manual white falls safely back to auto`() {
        val settings = LensWhiteLevelSettings(
            mode = WhiteLevelModes.MANUAL,
            manualWhiteLevel = 12345
        ).sanitized()

        assertEquals(WhiteLevelModes.AUTO, settings.mode)
        assertNull(settings.manualOverrideOrNull())
        assertEquals("Auto", settings.summary())
    }

    @Test
    fun `manual summary exposes selected raw domain label`() {
        val settings = LensWhiteLevelSettings(
            mode = WhiteLevelModes.MANUAL,
            manualWhiteLevel = 16383
        )
        assertEquals("16383 · 14-bit full scale", settings.summary())
    }
}
