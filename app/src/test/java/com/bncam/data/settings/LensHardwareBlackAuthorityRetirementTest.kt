package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensHardwareBlackAuthorityRetirementTest {
    private fun resolve(mode: String, dynamic: Float, manual: String = "") =
        LensHardwareSettingsResolver.resolve(
            lensId = "lens-test",
            noiseModelType = LensHardwareModes.NOISE_OFF,
            noiseAString = "",
            noiseBString = "",
            noiseCString = "",
            noiseDString = "",
            isoStepString = "",
            isoNrStyle = "Default",
            dynamicIsoCoeff = 0f,
            manualIsoValueString = "",
            blackLevelMode = mode,
            dynamicBlackLevel = dynamic,
            manualBlackLevelsString = manual,
            colorMatrixMode = LensHardwareModes.COLOR_SYSTEM,
            manualColorMatrixString = "",
            awbProfile = LensHardwareModes.AWB_SYSTEM,
            awbRatio = LensHardwareModes.AWB_AUTO_RATIO,
            awbTemp = 0f,
            awbIntensity = 0f
        )

    @Test
    fun `legacy dynamic transport is hard neutral regardless of historical percentage`() {
        val resolved = resolve(LensHardwareModes.BLACK_DYNAMIC, 25f)

        assertEquals(LensHardwareModes.BLACK_SYSTEM, resolved.blackLevelMode)
        assertEquals(0, resolved.blackLevelNativeMode)
        assertEquals(100f, resolved.dynamicBlackLevelPercent)
        assertEquals(listOf(64, 65, 66, 67), resolved.effectiveBlackLevels(listOf(64, 65, 66, 67), 1023))
        assertEquals("Camera2", resolved.blackLevelSource("Camera2", listOf(64, 65, 66, 67), 1023))
        assertNull(resolved.nativeManualBlackLevels())
        assertTrue(resolved.warnings.any { it.contains("migration-only") })
    }

    @Test
    fun `legacy manual transport cannot become a second developed raw owner`() {
        val resolved = resolve(LensHardwareModes.BLACK_MANUAL, 100f, "100,101,102,103")

        assertEquals(LensHardwareModes.BLACK_SYSTEM, resolved.blackLevelMode)
        assertEquals(0, resolved.blackLevelNativeMode)
        assertEquals(listOf(0f, 0f, 0f, 0f), resolved.manualBlackLevels)
        assertEquals(listOf(64, 65, 66, 67), resolved.effectiveBlackLevels(listOf(64, 65, 66, 67), 1023))
        assertNull(resolved.nativeManualBlackLevels())
    }
}
