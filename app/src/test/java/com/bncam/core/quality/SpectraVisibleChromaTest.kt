package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraVisibleChromaTest {
    @Test
    fun nativeStatsProduceStableMilestoneFourTrace() {
        val stats = mapOf(
            "spectraVisibleChromaActivationSource" to "PHYSICAL_NOISE_BASELINE",
            "spectraVisibleChromaArchitecture" to "SPECTRA_CONTEXT_FUSION_POST_TONE_VISIBLE_CHROMA",
            "spectraVisibleChromaInputStage" to "POST_TONE_VIBRANCE_COLOR_MANAGEMENT_LINEAR_RGB",
            "spectraVisibleChromaOutputStage" to "PRE_QUANTIZATION_LINEAR_RGB",
            "spectraVisibleChromaEnabled" to "true",
            "spectraVisibleChromaApplied" to "true",
            "spectraVisibleChromaPlanStatus" to "VISIBLE_CHROMA_PLAN_READY",
            "spectraVisibleChromaResultStatus" to "SPECTRA_CONTEXT_FUSION_VISIBLE_CHROMA_APPLIED",
            "spectraVisibleChromaMethod" to "OPPONENT_RG_BG_COVARIANCE_GUIDED_5X5",
            "spectraVisibleChromaModelConfidence" to "0.75",
            "spectraVisibleChromaAuthority" to "0.42",
            "spectraVisibleChromaChangedPixelCount" to "1200",
            "spectraVisibleChromaChangedPixelFraction" to "0.12",
            "spectraVisibleChromaMeanAcceptance" to "0.31",
            "spectraVisibleChromaAcceptanceP90" to "0.72",
            "spectraVisibleChromaInputVarianceRG" to "0.004",
            "spectraVisibleChromaOutputVarianceRG" to "0.0028",
            "spectraVisibleChromaInputResidualSampleCount" to "4096",
            "spectraVisibleChromaOutputResidualSampleCount" to "4096",
            "spectraVisibleChromaPixelBackend" to "TILED_NEON",
            "spectraVisibleChromaPixelBackendSelectionReason" to
                "PRODUCTION_FRAME_PIXEL_NEON_SELF_TEST_PASSED",
            "spectraVisibleChromaPixelSimdSelfTestPerformed" to "true",
            "spectraVisibleChromaPixelSimdSelfTestPassed" to "true",
            "spectraVisibleChromaPixelSimdSelfTestMaximumAbsoluteDelta" to "0.000001",
            "spectraVisibleChromaPixelLatencyBenchmarkPerformed" to "true",
            "spectraVisibleChromaPixelLatencyBenchmarkPassed" to "true",
            "spectraVisibleChromaPixelScalarBenchmarkMs" to "1.20",
            "spectraVisibleChromaPixelNeonBenchmarkMs" to "0.60",
            "spectraVisibleChromaPixelBenchmarkSpeedup" to "2.0",
            "spectraVisibleChromaOpponentVectorizedPixelCount" to "8192",
            "spectraVisibleChromaOpponentScalarPixelCount" to "256",
            "spectraVisibleChromaOpponentPeakScratchBytes" to "73984",
            "spectraVisibleChromaOpponentTileCount" to "48",
            "spectraVisibleChromaOpponentTileSize" to "64",
            "spectraVisibleChromaOpponentTileBuildMs" to "4.5",
            "spectraVisibleChromaMutexFreeTileReduction" to "true"
        )

        val trace = SpectraVisibleChromaTrace.fromNativeStats(stats)
        assertTrue(trace.enabled)
        assertTrue(trace.applied)
        assertEquals("PHYSICAL_NOISE_BASELINE", trace.activationSource)
        assertEquals(1200L, trace.changedPixelCount)
        assertEquals(0.12, trace.changedPixelFraction, 1e-9)
        assertEquals(0.31, trace.meanAcceptance, 1e-9)
        assertEquals(0.0028, trace.outputVarianceRG, 1e-9)
        assertEquals("TILED_NEON", trace.pixelBackend)
        assertTrue(trace.pixelSimdSelfTestPassed)
        assertTrue(trace.pixelLatencyBenchmarkPerformed)
        assertTrue(trace.pixelLatencyBenchmarkPassed)
        assertEquals(2.0, trace.pixelBenchmarkSpeedup, 0.0)
        assertEquals(8192L, trace.opponentVectorizedPixelCount)
        assertEquals(48, trace.opponentTileCount)
        assertTrue(trace.mutexFreeTileReduction)
        assertTrue(trace.toTraceMap().containsKey("noRegret"))
        assertTrue(trace.toTraceMap().containsKey("pixelBackend"))
    }

    @Test
    fun unsafeValuesAreBoundedAndOffRemainsOff() {
        val trace = SpectraVisibleChromaTrace.fromNativeStats(
            mapOf(
                "spectraVisibleChromaEnabled" to "false",
                "spectraVisibleChromaApplied" to "false",
                "spectraVisibleChromaAuthority" to "4.0",
                "spectraVisibleChromaChangedPixelFraction" to "-2.0",
                "spectraVisibleChromaMeanAcceptance" to "NaN",
                "spectraVisibleChromaCovarianceCorrelation" to "3.0",
                "spectraVisibleChromaEdgePreservationScore" to "2.0",
                "spectraVisibleChromaOversmoothingScore" to "-1.0"
            )
        )
        assertFalse(trace.enabled)
        assertFalse(trace.applied)
        assertEquals(1.0, trace.authority, 0.0)
        assertEquals(0.0, trace.changedPixelFraction, 0.0)
        assertEquals(0.0, trace.meanAcceptance, 0.0)
        assertEquals(1.0, trace.covarianceCorrelation, 0.0)
        assertEquals(1.0, trace.edgePreservationScore, 0.0)
        assertEquals(0.0, trace.oversmoothingScore, 0.0)
    }
}
