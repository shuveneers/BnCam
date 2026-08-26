package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraAnisotropicDetailTest {
    @Test
    fun nativeStatsProduceStableMilestoneFiveTrace() {
        val trace = SpectraAnisotropicDetailTrace.fromNativeStats(
            mapOf(
                "spectraAnisotropicDetailArchitecture" to
                    "MILESTONE_5_GREEN_GUIDED_STRUCTURE_TENSOR",
                "spectraAnisotropicDetailEnabled" to "true",
                "spectraAnisotropicDetailApplied" to "true",
                "spectraAnisotropicDetailStatus" to
                    "ANISOTROPIC_DETAIL_PRESERVATION_APPLIED",
                "spectraAnisotropicDetailEvaluatedPixelCount" to "1000",
                "spectraAnisotropicDetailValidTensorPixelCount" to "900",
                "spectraAnisotropicDetailConfidentTensorPixelCount" to "500",
                "spectraAnisotropicDetailFallbackPixelCount" to "500",
                "spectraAnisotropicDetailDirectionalChangedPixelCount" to "200",
                "spectraAnisotropicDetailConfidenceP50" to "0.42",
                "spectraAnisotropicDetailCoherenceP90" to "0.88",
                "spectraAnisotropicDetailMeanDirectionalAuthorityScale" to "0.61",
                "spectraAnisotropicDetailTensorFieldBuildMs" to "3.2",
                "spectraAnisotropicDetailDirectionalFilterMs" to "18.4"
            )
        )
        assertTrue(trace.enabled)
        assertTrue(trace.applied)
        assertEquals(1000L, trace.evaluatedPixelCount)
        assertEquals(500L, trace.confidentTensorPixelCount)
        assertEquals(0.42, trace.confidenceP50, 1e-9)
        assertEquals(0.88, trace.coherenceP90, 1e-9)
        assertTrue(trace.toTraceMap().containsKey("directionalSupport"))
        assertTrue(trace.toTraceMap().containsKey("tensor"))
    }

    @Test
    fun unsafeValuesAreBoundedAndOffRemainsOff() {
        val trace = SpectraAnisotropicDetailTrace.fromNativeStats(
            mapOf(
                "spectraAnisotropicDetailEnabled" to "false",
                "spectraAnisotropicDetailApplied" to "false",
                "spectraAnisotropicDetailValidTensorFraction" to "2.0",
                "spectraAnisotropicDetailFallbackFraction" to "-1.0",
                "spectraAnisotropicDetailConfidenceP50" to "NaN",
                "spectraAnisotropicDetailMeanDirectionalWeight" to "-4.0",
                "spectraAnisotropicDetailTensorFieldBuildMs" to "-2.0"
            )
        )
        assertFalse(trace.enabled)
        assertFalse(trace.applied)
        assertEquals(1.0, trace.validTensorFraction, 0.0)
        assertEquals(0.0, trace.fallbackFraction, 0.0)
        assertEquals(0.0, trace.confidenceP50, 0.0)
        assertEquals(0.0, trace.meanDirectionalWeight, 0.0)
        assertEquals(0.0, trace.tensorFieldBuildMs, 0.0)
    }
}
