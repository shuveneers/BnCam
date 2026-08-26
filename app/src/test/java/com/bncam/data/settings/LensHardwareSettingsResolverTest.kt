package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensHardwareSettingsResolverTest {
    @Test
    fun dynamicBlackLevelScalesMetadataBaseline() {
        val settings = LensHardwareSettingsResolver.resolve(
            lensId = "0",
            noiseModelType = "Default",
            noiseAString = "",
            noiseBString = "",
            noiseCString = "",
            noiseDString = "",
            isoStepString = "",
            isoNrStyle = "Default",
            dynamicIsoCoeff = 0.5f,
            manualIsoValueString = "",
            blackLevelMode = "Dynamic",
            dynamicBlackLevel = 50f,
            manualBlackLevelsString = "",
            colorMatrixMode = "System",
            manualColorMatrixString = "",
            awbProfile = "System",
            awbRatio = "Auto",
            awbTemp = 0f,
            awbIntensity = 0f
        )

        assertEquals(1, settings.blackLevelNativeMode)
        assertEquals(listOf(32, 64, 96, 128), settings.effectiveBlackLevels(listOf(64, 128, 192, 256), 1023))
        assertTrue(settings.blackLevelSource("metadata", listOf(64, 128, 192, 256), 1023).contains("Dynamic"))
    }
}
