package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone2CalibrationSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun stageLocalMeasurementsAreComparedInMatchingLinearDomains() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val header = File(app, "src/main/cpp/SpectraNoiseCalibration.h").readText()

        assertTrue(cpp.contains("measureLinearResidualFlatRegions("))
        assertTrue(cpp.contains("\"POST_DEMOSAIC_RGB\""))
        assertTrue(cpp.contains("\"POST_COLOUR_MATRIX_RGB_AFTER_NONNEGATIVE_CLAMP\""))
        assertTrue(cpp.contains("spectraMeasuredPostDemosaic"))
        assertTrue(cpp.contains("spectraMeasuredPostColourTransform"))
        assertTrue(cpp.contains("spectraPostDemosaicCalibration"))
        assertTrue(cpp.contains("spectraPostColourTransformCalibration"))
        assertTrue(cpp.contains("FLAT_REGION_CROSS_5_LINEAR_RGB_TILE_MEDIAN"))
        assertTrue(header.contains("comparePredictionToObservation"))
        assertTrue(header.contains("READY_OBSERVATION_ONLY_NO_COEFFICIENT_UPDATE"))
        assertTrue(header.contains("POST_COLOUR_MATRIX_RGB_AFTER_NONNEGATIVE_CLAMP"))
        assertTrue(header.contains("STAGE_MISMATCH"))
    }

    @Test
    fun calibrationRatiosNeverChangeRuntimeCoefficientsAutomatically() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val header = File(app, "src/main/cpp/SpectraNoiseCalibration.h").readText()
        val residual = File(
            app,
            "src/main/java/com/bncam/core/quality/ResidualNoiseState.kt"
        ).readText()

        assertTrue(cpp.contains("MILESTONE_2B_STAGE_OBSERVATIONS_READY_NO_AUTO_CALIBRATION"))
        assertTrue(header.contains("OBSERVATION_READY_NO_AUTO_CALIBRATION"))
        assertTrue(residual.contains("DISABLED_OBSERVATION_ONLY"))
        assertFalse(cpp.contains("demosaicNoiseTransferMatrix ="))
        assertFalse(cpp.contains("measuredToPredictedChroma *="))
        assertFalse(cpp.contains("postDemosaicCalibration.measuredToPredictedChroma *"))
    }

    @Test
    fun newMeasurementsAreIncludedInReconciledTimingAndTraceSchema() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()

        assertTrue(cpp.contains("spectraMeasuredPostDemosaicResidualMs"))
        assertTrue(cpp.contains("spectraMeasuredPostColourTransformResidualMs"))
        assertTrue(cpp.contains("rawIspAccountedMs"))
        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(schemaVersion >= 7)
        assertTrue(trace.contains("\"calibration\""))
        assertTrue(trace.contains("spectraMeasuredPostDemosaicResidualMs"))
        assertTrue(trace.contains("spectraMeasuredPostColourTransformResidualMs"))
    }
}
