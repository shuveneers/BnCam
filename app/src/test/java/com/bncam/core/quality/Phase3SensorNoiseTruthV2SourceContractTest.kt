package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3SensorNoiseTruthV2SourceContractTest {
    @Test
    fun `raw physical denoise authority is calibrated SO truth and format neutral`() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val physical = File("src/main/cpp/SpectraPhysicalBaselineNr.h").readText()

        assertTrue(physical.contains("resolvePhysicalChromaBaseStrength("))
        assertTrue(physical.contains("meanSensorNoiseVariance"))
        assertTrue(physical.contains("calibrationFactor"))
        assertTrue(physical.contains("modelConfidence"))
        assertFalse(physical.contains("bool isRaw10,\n        float renderExposureGain"))

        val pass2 = isp.substringAfter("// === OUT-OF-PLACE PASS 2: CHROMA NR AND QUANTIZATION ===")
            .substringBefore("const auto finalOutputPassStart")
        assertTrue(pass2.contains("g_threadLocalIspStats.meanSensorNoiseVariance"))
        assertTrue(pass2.contains("uiConfig.noiseModelCalibrationFactor"))
        assertTrue(pass2.contains("maximumDenoiseCeiling = 0.48f"))
        assertFalse(pass2.contains("sensorMaxAnalogIso"))
        assertFalse(pass2.contains("sensorMaxIso = 6400"))
        assertFalse(pass2.contains("log2(referenceFrameIso / sensorMinIso)"))
    }

    @Test
    fun `dynamic iso consumes physical noise truth instead of estimating noise from iso`() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val physical = File("src/main/cpp/SpectraPhysicalBaselineNr.h").readText()

        assertTrue(physical.contains("resolveNoiseTruthDynamicHeadroomFraction("))
        assertTrue(isp.contains("dynamicIsoActivationSource=calibrated_so_noise_pressure"))
        assertTrue(isp.contains("resolveNoiseTruthDynamicHeadroomFraction("))
        assertFalse(isp.contains("resolvedDynamicIsoMultiplier"))
        assertFalse(isp.contains("resolvedNoiseModelMultiplier"))
    }

    @Test
    fun `advanced calibration controls reach native raw production config`() {
        val imageUtils = File("src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val nativeBridge = File("src/main/cpp/native-lib.cpp").readText()
        val calibration = File("src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()

        assertTrue(imageUtils.contains("noiseModelCalibrationFactor = qualityConfig?.lensHardwareSettings?.noiseModelCalibrationFactor"))
        assertTrue(imageUtils.contains("effectiveChromaAuthorityStops = qualityConfig?.lensHardwareSettings?.effectiveChromaAuthorityStops"))
        assertTrue(nativeBridge.contains("cfg.noiseModelCalibrationFactor = std::isfinite(noiseModelCalibrationFactor)"))
        assertFalse(nativeBridge.contains("cfg.noiseModelCalibrationFactor = 1.0f;"))
        assertTrue(calibration.contains("manualNoiseSingleAnchorScaled -> 0.65f"))
    }
}
