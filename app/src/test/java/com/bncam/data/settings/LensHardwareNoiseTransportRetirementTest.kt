package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensHardwareNoiseTransportRetirementTest {
    @Test
    fun legacyNoiseAndIsoNrAreFailClosedWhileOtherLensControlsRemainActive() {
        val resolved = LensHardwareSettingsResolver.resolve(
            lensId = "main",
            noiseModelType = "Manual",
            noiseAString = "1 2 3 4",
            noiseBString = "5 6 7 8",
            noiseCString = "9 10 11 12",
            noiseDString = "13 14 15 16",
            isoStepString = "800",
            isoNrStyle = "Dynamic ISO coefficient",
            dynamicIsoCoeff = 0.8f,
            manualIsoValueString = "1600",
            blackLevelMode = "Manual",
            dynamicBlackLevel = 100f,
            manualBlackLevelsString = "64 65 66 67",
            colorMatrixMode = "Manual",
            manualColorMatrixString = "1 0 0 0 1 0 0 0 1",
            awbProfile = "Manual",
            awbRatio = "1.1",
            awbTemp = 0.2f,
            awbIntensity = 0.5f,
            manualNoiseSoString = "0.001 0.00001 0.0011 0.000011 0.0012 0.000012 0.0013 0.000013",
            noiseCalibrationAdj = 0.7f,
            chromaAuthorityAdj = 0.6f,
            lumaAuthorityAdj = -0.4f
        )

        assertEquals(LensHardwareModes.NOISE_OFF, resolved.noiseModelType)
        assertEquals(0, resolved.noiseModelNativeMode)
        assertEquals(0, resolved.isoNrNativeMode)
        assertEquals(0.0f, resolved.dynamicIsoCoeff, 0.0f)
        assertEquals(0.0f, resolved.manualIsoValue, 0.0f)
        assertEquals(0.0f, resolved.isoStep, 0.0f)
        assertTrue(resolved.manualNoiseProfile.isEmpty())
        assertNull(resolved.nativeNoiseA())
        assertNull(resolved.nativeNoiseB())
        assertNull(resolved.nativeNoiseC())
        assertNull(resolved.nativeNoiseD())
        assertNull(resolved.nativeManualNoiseProfile())
        assertEquals(1.0f, resolved.chromaUserScale, 0.0f)
        assertEquals(1.0f, resolved.lumaUserScale, 0.0f)
        assertEquals(0.0f, resolved.outerRingAuthority, 0.0f)
        assertEquals(0.0f, resolved.noiseModelCalibrationAdjustment, 0.0f)
        assertEquals(0.0f, resolved.dynamicChromaAuthorityAdjustment, 0.0f)
        assertEquals(0.0f, resolved.dynamicLumaAuthorityAdjustment, 0.0f)
        assertEquals(1.0f, resolved.noiseModelCalibrationFactor, 1.0e-6f)

        assertEquals(2, resolved.blackLevelNativeMode)
        assertEquals(listOf(64, 65, 66, 67), resolved.effectiveBlackLevels(listOf(10, 10, 10, 10), 1024))
        assertEquals(1, resolved.colorMatrixNativeMode)
        assertTrue(resolved.colorMatrixValidationPassed)
        assertEquals(1, resolved.awbNativeMode)
        assertTrue(resolved.anyManualEffectActive)
        assertTrue(resolved.warnings.any { it.contains("PhysicalNoiseModelSettingsStore") })
    }

    @Test
    fun neutralLegacyNoiseDoesNotCreateRetirementWarningOrManualEffect() {
        val resolved = LensHardwareSettingsResolver.resolve(
            lensId = "tele",
            noiseModelType = "Off",
            noiseAString = "",
            noiseBString = "",
            noiseCString = "",
            noiseDString = "",
            isoStepString = "",
            isoNrStyle = "Default",
            dynamicIsoCoeff = 0f,
            manualIsoValueString = "",
            blackLevelMode = "Auto",
            dynamicBlackLevel = 100f,
            manualBlackLevelsString = "",
            colorMatrixMode = "System",
            manualColorMatrixString = "",
            awbProfile = "System",
            awbRatio = "Auto",
            awbTemp = 0f,
            awbIntensity = 0f
        )

        assertFalse(resolved.anyManualEffectActive)
        assertTrue(resolved.warnings.none { it.contains("PhysicalNoiseModelSettingsStore") })
    }
}
