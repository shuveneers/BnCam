package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackLevelSettingsModelTest {
    @Test
    fun `dynamic strength is clamped and rounded to two decimals`() {
        assertEquals(0.0f, sanitizeBlackLevelDynamicStrength(-1f), 0f)
        assertEquals(1.0f, sanitizeBlackLevelDynamicStrength(2f), 0f)
        assertEquals(0.34f, sanitizeBlackLevelDynamicStrength(0.336f), 0f)
    }

    @Test
    fun `black level types sanitize auto to system`() {
        assertEquals(BlackLevelTypes.SYSTEM, BlackLevelTypes.sanitize("Auto"))
        assertEquals(BlackLevelTypes.SYSTEM, BlackLevelTypes.sanitize("System"))
        assertEquals(BlackLevelTypes.DYNAMIC, BlackLevelTypes.sanitize("dynamic"))
        assertEquals(BlackLevelTypes.MANUAL, BlackLevelTypes.sanitize("manual"))
    }

    @Test
    fun `manual values stay floating point`() {
        val settings = LensBlackLevelControlSettings(
            type = BlackLevelTypes.MANUAL,
            manualValues = listOf(63.86, 63.93, 64.14, 64.2),
            manualInitialized = true
        ).sanitized()
        assertEquals(listOf(63.86, 63.93, 64.14, 64.2), settings.manualValues)
        assertTrue(settings.manualInitialized)
    }
}
