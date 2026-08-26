package com.bncam.core.quality

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpectraOffOnTruthSourceContractTest {
    @Test
    fun spectraOffAndOnRemainMeaningfullyDifferentWithoutDiscardingPhysicalNoiseTruth() {
        val root = File(System.getProperty("user.dir"))
        val defaults = File(root, "src/main/java/com/bncam/core/quality/SpectraProfileCharacter.kt").readText()
        val calibration = File(root, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()
        val baseline = File(root, "src/main/cpp/SpectraPhysicalBaselineNr.h").readText()
        val isp = File(root, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(defaults.contains("const val ENABLED = false"))
        assertTrue(defaults.contains("const val DYNAMIC_ISO = 0.40f"))
        assertTrue(defaults.contains("const val STRENGTH = 0.18f"))
        assertTrue(defaults.contains("const val CHROMA = 0.45f"))
        assertTrue(defaults.contains("const val LOW_FREQUENCY = 0.50f"))

        assertTrue(calibration.contains("physicalNoiseMode"))
        assertTrue(calibration.contains("spectraProcessingMode"))
        assertTrue(calibration.contains("spectraProcessingRequested"))

        assertTrue(baseline.contains("plan.lumaFraction = 0.72f"))
        assertTrue(baseline.contains("plan.chromaFraction = 0.90f"))
        assertTrue(baseline.contains("spectraContextFusionActive"))

        assertTrue(isp.contains("const bool spectraNoiseActive = meta.calibration.spectraProcessingMode != 0"))
        assertTrue(isp.contains("resolveMultiscaleChromaResidualConsensus"))
        assertTrue(isp.contains("resolveLowFrequencyCorrectionCeiling"))
    }
}
