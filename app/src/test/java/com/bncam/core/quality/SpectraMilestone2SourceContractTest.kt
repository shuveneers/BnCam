package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraMilestone2SourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/IspCore.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun covariancePropagationIsImplementedForEveryRequestedIspStage() {
        val app = appDir()
        val propagation = File(app, "src/main/cpp/SpectraNoisePropagation.h").readText()
        val header = File(app, "src/main/cpp/IspCore.h").readText()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()

        assertTrue(propagation.contains("multiply("))
        assertTrue(propagation.contains("propagateAwb("))
        assertTrue(propagation.contains("propagateDemosaic("))
        assertTrue(propagation.contains("propagateOpponentGains("))
        assertTrue(propagation.contains("piecewiseLinearCurveDerivative("))
        assertTrue(propagation.contains("propagateColourMatrix("))
        assertTrue(propagation.contains("A_SIGMA_AT_PRE_NONNEGATIVE_CLAMP"))
        assertTrue(header.contains("SYMMETRIC_PSD_BOUNDED"))
        // Value-initialize all nine entries without a manually counted initializer list.
        assertTrue(header.contains("std::array<float, 9> covarianceRgb{};"))
        assertFalse(header.contains("std::array<float, 9> covarianceRgb{0.0f,"))

        assertTrue(header.contains("spectra2::NoiseState preDemosaic"))
        assertTrue(header.contains("spectra2::NoiseState postDemosaic"))
        assertTrue(header.contains("spectra2::NoiseState postAwb"))
        assertTrue(header.contains("spectra2::NoiseState postColourTransform"))
        assertTrue(header.contains("spectra2::NoiseState postTone"))

        assertTrue(cpp.contains("propagateDemosaic("))
        assertTrue(cpp.contains("propagateAwb("))
        assertTrue(cpp.contains("spectraPostDemosaic"))
        assertTrue(cpp.contains("spectraPostAwb"))
        assertTrue(cpp.contains("spectraPostColourTransform"))
        assertTrue(cpp.contains("spectraPostTone"))
        assertTrue(cpp.contains("spectraToneCurveDerivative"))
        assertTrue(cpp.contains("spectraSectionCurveDerivative"))
        assertTrue(cpp.contains("spectraGammaCurveDerivative"))
        assertTrue(cpp.contains("spectraTotalToneDerivative"))
    }

    @Test
    fun visiblePredictionMeasurementAndPassTwoTargetAreExportedWithoutNewJniSurface() {
        val app = appDir()
        val cpp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val trace = File(app, "src/main/java/com/bncam/core/quality/NoiseModelTrace.kt").readText()
        val residual = File(app, "src/main/java/com/bncam/core/quality/ResidualNoiseState.kt").readText()
        val nativeBridge = File(app, "src/main/cpp/native-lib.cpp").readText()

        assertTrue(cpp.contains("spectraPredictedVisibleVarianceRG"))
        assertTrue(cpp.contains("spectraPredictedVisibleVarianceBG"))
        assertTrue(cpp.contains("spectraMeasuredPostIspMethod"))
        assertTrue(cpp.contains("spectraMeasuredPostIspFilterEnergyGain"))
        assertTrue(cpp.contains("spectraMeasuredPostIspVarianceRG"))
        assertTrue(cpp.contains("spectraMeasuredPostIspVarianceBG"))
        assertTrue(cpp.contains("spectraMeasuredPostIspCovarianceRgBg"))
        assertTrue(cpp.contains("spectraPass2VisibleTargetReady"))
        assertTrue(cpp.contains("spectraPass2VisibleTargetStatus"))
        assertTrue(cpp.contains("OBSERVATION_ONLY_NO_SKIP_AUTHORITY_MILESTONE_2"))
        assertTrue(cpp.contains("spectraPass2VisibleTargetPreventedSkip"))
        assertTrue(cpp.contains("spectraPass2VisibleChromaAmplification"))
        assertTrue(cpp.contains("CAMERA2_SO_WITH_LENS_SHADING_GAIN_SQUARED"))
        assertTrue(cpp.contains("POST_LENS_SHADING_PRE_DEMOSAIC"))
        assertTrue(cpp.contains("spectraDemosaicPropagationMs"))
        assertTrue(cpp.contains("spectraAwbPropagationMs"))
        assertTrue(cpp.contains("spectraColourTransformPropagationMs"))
        assertTrue(cpp.contains("spectraTonePropagationMs"))
        assertTrue(cpp.contains("spectraMeasuredVisibleResidualMs"))

        val schemaVersion = Regex("CURRENT_SCHEMA_VERSION\\s*=\\s*(\\d+)")
            .find(trace)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue(schemaVersion >= 7)
        assertTrue(trace.contains("\"noisePropagation\""))
        assertTrue(trace.contains("\"transitions\""))
        assertTrue(trace.contains("\"visiblePrediction\""))
        assertTrue(trace.contains("\"visibleMeasurement\""))
        assertTrue(trace.contains("\"pass2VisibleTarget\""))
        assertTrue(residual.contains("data class NoisePropagationStage"))
        assertTrue(residual.contains("data class NoiseDerivativeStatistics"))
        assertTrue(residual.contains("CONTROLLED_SCENE_PROXY_SPATIAL_CORRELATION_AND_TEXTURE_NOT_DECONVOLVED"))

        assertFalse(nativeBridge.contains("nativePropagateResidualNoise"))
        assertTrue(cpp.contains("<< \"; awbTimingMode=fused_with_colour_transform\""))
        assertTrue(cpp.contains("<< \"; colourTransformTimingMode=fused_with_awb\""))
    }
}
