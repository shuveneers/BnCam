package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLensTuningSettingsTest {
    @Test
    fun `scientific noise notation retains channel positions and Double precision`() {
        val parsed = parseStoredDoubles("1.25e-6,2.5E-8,NaN,4.75e-9", 4)

        assertEquals(1.25e-6, parsed[0], 0.0)
        assertEquals(2.5e-8, parsed[1], 0.0)
        assertEquals(0.0, parsed[2], 0.0)
        assertEquals(4.75e-9, parsed[3], 0.0)
    }

    @Test
    fun `manual S O reaches authoritative lens resolver`() {
        val resolved = LensHardwareSettingsResolver.resolve(
            lensId = "0",
            noiseModelType = LensHardwareTuningModes.MANUAL,
            noiseAString = "",
            noiseBString = "",
            noiseCString = "",
            noiseDString = "",
            isoStepString = "",
            isoNrStyle = "Default",
            dynamicIsoCoeff = 0f,
            manualIsoValueString = "",
            blackLevelMode = LensHardwareTuningModes.AUTO,
            dynamicBlackLevel = 50f,
            manualBlackLevelsString = "",
            colorMatrixMode = "System",
            manualColorMatrixString = "",
            awbProfile = "System",
            awbRatio = "Auto",
            awbTemp = 0f,
            awbIntensity = 0f,
            manualNoiseSoString = "1e-6,2e-8,3e-6,4e-8,5e-6,6e-8,7e-6,8e-8"
        )

        assertEquals(2, resolved.noiseModelNativeMode)
        assertEquals(8, resolved.manualNoiseProfile.size)
        assertEquals(1e-6, resolved.manualNoiseProfile[0], 0.0)
        assertEquals(8e-8, resolved.manualNoiseProfile[7], 0.0)
    }

    @Test
    fun `noise mode defaults Off and preserves explicit Auto or Manual`() {
        assertEquals(LensHardwareTuningModes.OFF, LensNoiseModelSettings().sanitized().mode)
        assertEquals(
            LensHardwareTuningModes.AUTO,
            LensNoiseModelSettings(mode = LensHardwareTuningModes.AUTO).sanitized().mode
        )
        assertEquals(
            LensHardwareTuningModes.MANUAL,
            LensNoiseModelSettings(mode = LensHardwareTuningModes.MANUAL).sanitized().mode
        )
    }

    @Test
    fun `dynamic ISO coefficient follows displayed two decimal contract`() {
        assertEquals(0.0f, sanitizeDynamicIsoCoefficient(0.003218884f), 0.0f)
        assertEquals(0.25f, sanitizeDynamicIsoCoefficient(0.249f), 0.0f)
        assertEquals(1.0f, sanitizeDynamicIsoCoefficient(1.5f), 0.0f)
        assertEquals(0.0f, sanitizeDynamicIsoCoefficient(Float.NaN), 0.0f)
    }

    @Test
    fun `manual decimal black levels remain floating point in resolver`() {
        val resolved = LensHardwareSettingsResolver.resolve(
            lensId = "0",
            noiseModelType = "Default",
            noiseAString = "",
            noiseBString = "",
            noiseCString = "",
            noiseDString = "",
            isoStepString = "",
            isoNrStyle = "Default",
            dynamicIsoCoeff = 0f,
            manualIsoValueString = "",
            blackLevelMode = LensHardwareTuningModes.MANUAL,
            dynamicBlackLevel = 50f,
            manualBlackLevelsString = "64.25,64.50,63.80,64.10",
            colorMatrixMode = "System",
            manualColorMatrixString = "",
            awbProfile = "System",
            awbRatio = "Auto",
            awbTemp = 0f,
            awbIntensity = 0f
        )

        assertEquals(2, resolved.blackLevelNativeMode)
        assertEquals(64.25f, resolved.manualBlackLevels[0], 0f)
        assertEquals(63.80f, resolved.manualBlackLevels[2], 0f)
        assertTrue(resolved.nativeManualBlackLevels()!!.all { it.isFinite() })
    }
}
