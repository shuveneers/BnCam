package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraDownstreamIspTest {
    @Test
    fun `native downstream statistics produce immutable schema 11 trace`() {
        val trace = SpectraDownstreamIspTrace.fromNativeStats(
            mapOf(
                "spectraDownstreamArchitecture" to "MILESTONE_7_NOISE_AWARE_DOWNSTREAM_ISP",
                "spectraDownstreamInputStage" to "POST_QUANTIZATION_8BIT_PRE_SHARPEN",
                "spectraDownstreamOutputStage" to "FINAL_JPEG_PRE_ENCODE",
                "spectraDownstreamResultStatus" to
                    "MILESTONE_7_NOISE_AWARE_DOWNSTREAM_APPLIED",
                "spectraDownstreamSpectraAware" to "true",
                "spectraDownstreamEnabled" to "true",
                "spectraDownstreamApplied" to "true",
                "spectraDownstreamResidualSource" to
                    "PROPAGATED_PLUS_BOUNDED_PRE_SHARPEN_MEASUREMENT",
                "spectraDownstreamModelConfidence" to "0.86",
                "spectraDownstreamSigmaY" to "0.012",
                "spectraDownstreamMaximumAmount" to "0.08",
                "spectraDownstreamAuthorityP10" to "0.01",
                "spectraDownstreamAuthorityP50" to "0.04",
                "spectraDownstreamAuthorityP90" to "0.07",
                "spectraDownstreamChangedPixelCount" to "1200",
                "spectraDownstreamChangedPixelFraction" to "0.12",
                "spectraDownstreamMeasuredVarianceGainY" to "1.03",
                "spectraDownstreamPredictedVarianceGainY" to "1.05",
                "spectraDownstreamProcessingTimeMs" to "8.5",
                "spectraDownstreamPropagationMs" to "0.08"
            )
        )

        assertTrue(trace.spectraAware)
        assertTrue(trace.enabled)
        assertTrue(trace.applied)
        assertEquals(0.86, trace.modelConfidence, 0.0)
        assertEquals(1200L, trace.changedPixelCount)
        assertEquals(1.03, trace.measuredVarianceGainY, 0.0)
        assertTrue(trace.toTraceMap().containsKey("authority"))
        assertTrue(trace.toTraceMap().containsKey("localContrast"))
        assertTrue(trace.toTraceMap().containsKey("timing"))
    }

    @Test
    fun `invalid values are bounded without inventing activity`() {
        val trace = SpectraDownstreamIspTrace(
            enabled = false,
            applied = false,
            modelConfidence = Double.NaN,
            sigmaY = -4.0,
            changedPixelFraction = 4.0,
            measuredVarianceGainY = Double.POSITIVE_INFINITY,
            predictedVarianceGainY = -2.0,
            processingTimeMs = -1.0
        ).sanitized()

        assertFalse(trace.enabled)
        assertFalse(trace.applied)
        assertEquals(0.0, trace.modelConfidence, 0.0)
        assertEquals(0.0, trace.sigmaY, 0.0)
        assertEquals(1.0, trace.changedPixelFraction, 0.0)
        assertEquals(0.0, trace.measuredVarianceGainY, 0.0)
        assertEquals(0.0, trace.predictedVarianceGainY, 0.0)
        assertEquals(0.0, trace.processingTimeMs, 0.0)
    }
}
