package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraTemporalFusionTest {
    @Test
    fun `native stats parse into bounded temporal fusion trace`() {
        val trace = SpectraTemporalFusionTrace.fromNativeStats(
            mapOf(
                "spectraEnabled" to "true",
                "spectraAcceptedFramePairs" to "3",
                "spectraRejectedFramePairs" to "1",
                "spectraStaticProbabilityP50" to "0.82",
                "spectraForwardBackwardConsistency" to "0.91",
                "spectraRepeatedSupportConfidence" to "0.76",
                "spectraPersistentPatternFraction" to "0.35",
                "spectraIndependentNoiseFraction" to "0.65",
                "spectraFusionVarianceScale" to "0.50",
                "spectraEffectiveFrameCount" to "2.0",
                "spectraFitEstimator0" to "2",
                "spectraFitPhysicalScore0" to "0.88",
                "spectraFitStabilityConfidence" to "0.84",
                "spectraFitStability0" to "0.81",
                "spectraFitConsensusMs" to "0.4",
                "spectraTemporalObserverMs" to "4.5"
            )
        )

        assertTrue(trace.enabled)
        assertEquals(3, trace.acceptedFramePairs)
        assertEquals(0.82, trace.staticProbabilityP50, 0.0)
        assertEquals(0.35, trace.persistentPatternFraction, 0.0)
        assertEquals(0.50, trace.fusionVarianceScale, 0.0)
        assertEquals(2.0, trace.effectiveFrameCount, 0.0)
        assertEquals(2, trace.fitEstimator.first())
        assertEquals(0.84, trace.fitStabilityConfidence, 0.0)
        assertEquals(0.81, trace.fitStabilityByChannel.first(), 0.0)
        assertEquals(0.4, trace.fitConsensusMs, 0.0)
        assertEquals("CAPTURE_LOCAL_NON_PERSISTENT", trace.persistence)
    }

    @Test
    fun `invalid values are safely bounded`() {
        val trace = SpectraTemporalFusionTrace(
            staticProbabilityP50 = 4.0,
            normalizedInnovationVariance = -2.0,
            temporalCorrelation = 2.0,
            fusionVarianceScale = -1.0,
            effectiveFrameCount = 0.0,
            fitEstimator = listOf(99),
            fitPhysicalScore = listOf(Double.NaN)
        ).sanitized()

        assertEquals(1.0, trace.staticProbabilityP50, 0.0)
        assertEquals(0.0, trace.normalizedInnovationVariance, 0.0)
        assertEquals(1.0, trace.temporalCorrelation, 0.0)
        assertEquals(0.02, trace.fusionVarianceScale, 0.0)
        assertEquals(1.0, trace.effectiveFrameCount, 0.0)
        assertEquals(listOf(2, 0, 0, 0), trace.fitEstimator)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0), trace.fitPhysicalScore)
    }

    @Test
    fun `trace map exposes static fit correlation and fusion sections`() {
        val map = SpectraTemporalFusionTrace(enabled = true).toTraceMap()
        assertTrue(map.containsKey("staticProbability"))
        assertTrue(map.containsKey("normalizedInnovation"))
        assertTrue(map.containsKey("dualFit"))
        assertTrue(map.containsKey("correlation"))
        assertTrue(map.containsKey("fusion"))
        assertTrue(map.containsKey("timing"))
    }
}
