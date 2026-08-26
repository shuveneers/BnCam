package com.bncam.core.quality

import com.bncam.data.settings.LensHardwareSettingsResolver
import com.bncam.data.settings.ResolvedLensHardwareSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SensorNoiseCalibrationTest {

    @Test
    fun testMapperMathDefaultsAndBounds() {
        // Test default 0.00 adjustments
        val factorDefault = SensorNoiseCalibrationMapper.noiseModelCalibrationFactor(0.0)
        val chromaStopsDefault = SensorNoiseCalibrationMapper.effectiveChromaAuthorityStops(0.0)
        val lumaStopsDefault = SensorNoiseCalibrationMapper.effectiveLumaAuthorityStops(0.0)

        assertEquals(1.0, factorDefault, 1e-5)
        assertEquals(4.0, chromaStopsDefault, 1e-5)
        assertEquals(2.25, lumaStopsDefault, 1e-5)

        // Test extreme -1.00 adjustments
        val factorMin = SensorNoiseCalibrationMapper.noiseModelCalibrationFactor(-1.0)
        val chromaStopsMin = SensorNoiseCalibrationMapper.effectiveChromaAuthorityStops(-1.0)
        val lumaStopsMin = SensorNoiseCalibrationMapper.effectiveLumaAuthorityStops(-1.0)

        assertEquals(0.25, factorMin, 1e-5)
        assertEquals(0.0, chromaStopsMin, 1e-5)
        assertEquals(0.0, lumaStopsMin, 1e-5)

        // Test extreme +1.00 adjustments
        val factorMax = SensorNoiseCalibrationMapper.noiseModelCalibrationFactor(1.0)
        val chromaStopsMax = SensorNoiseCalibrationMapper.effectiveChromaAuthorityStops(1.0)
        val lumaStopsMax = SensorNoiseCalibrationMapper.effectiveLumaAuthorityStops(1.0)

        assertEquals(4.0, factorMax, 1e-5)
        assertEquals(5.0, chromaStopsMax, 1e-5)
        assertEquals(3.50, lumaStopsMax, 1e-5)
    }

    @Test
    fun testOuterRingAuthorityGuards() {
        // Guard 1: Mode == "Off" -> outerRingAuthority MUST be 0.0
        val outerOff = SensorNoiseCalibrationMapper.outerRingAuthority("Off", 1.0, 4.0)
        assertEquals(0.0, outerOff, 1e-5)

        // Guard 2: dynamicIsoCoeff == 0.0 -> outerRingAuthority MUST be 0.0
        val outerZeroCoeff = SensorNoiseCalibrationMapper.outerRingAuthority("Auto", 0.0, 4.0)
        assertEquals(0.0, outerZeroCoeff, 1e-5)

        // Guard 3: effectiveChromaStops == 0.0 -> outerRingAuthority MUST be 0.0
        val outerZeroStops = SensorNoiseCalibrationMapper.outerRingAuthority("Auto", 1.0, 0.0)
        assertEquals(0.0, outerZeroStops, 1e-5)

        // Active progressive outer ring
        val outerAuto050 = SensorNoiseCalibrationMapper.outerRingAuthority("Auto", 0.50, 4.0)
        val outerAuto100 = SensorNoiseCalibrationMapper.outerRingAuthority("Auto", 1.00, 4.0)

        assertTrue(outerAuto050 in 0.0..1.0)
        assertTrue(outerAuto100 > outerAuto050)
    }

    @Test
    fun testSnapshotToMapperAgreement() {
        val snapshot: ResolvedLensHardwareSettings = LensHardwareSettingsResolver.resolve(
            lensId = "0",
            noiseModelType = "Auto",
            noiseAString = "",
            noiseBString = "",
            noiseCString = "",
            noiseDString = "",
            isoStepString = "",
            isoNrStyle = "Default",
            dynamicIsoCoeff = 0.5f,
            manualIsoValueString = "",
            blackLevelMode = "Auto",
            dynamicBlackLevel = 0f,
            manualBlackLevelsString = "",
            colorMatrixMode = "System",
            manualColorMatrixString = "",
            awbProfile = "System",
            awbRatio = "Auto",
            awbTemp = 0f,
            awbIntensity = 0f,
            noiseCalibrationAdj = 0.5f,
            chromaAuthorityAdj = 0.2f,
            lumaAuthorityAdj = -0.4f
        )

        val mapperFactor = SensorNoiseCalibrationMapper.noiseModelCalibrationFactor(0.5).toFloat()
        val mapperChromaStops = SensorNoiseCalibrationMapper.effectiveChromaAuthorityStops(0.2).toFloat()
        val mapperLumaStops = SensorNoiseCalibrationMapper.effectiveLumaAuthorityStops(-0.4).toFloat()

        assertEquals(mapperFactor, snapshot.noiseModelCalibrationFactor, 1e-4f)
        assertEquals(mapperChromaStops, snapshot.effectiveChromaAuthorityStops, 1e-4f)
        assertEquals(mapperLumaStops, snapshot.effectiveLumaAuthorityStops, 1e-4f)
    }
}
