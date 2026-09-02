package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultRawExposureRealizationEvaluatorTest {
    @Test
    fun `api36 target within five percent is realized`() {
        val truth = DefaultRawExposureRealizationEvaluator.evaluate(
            route = DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY,
            pipelineGeneration = 7,
            controlRequestEpoch = 11L,
            requestedAeMode = 1,
            resultAeMode = 1,
            requestedPriorityMode = 2,
            resultPriorityMode = 2,
            requestedExposureNs = 30_000_000L,
            actualExposureNs = 28_800_000L,
            requestedIso = null,
            actualIso = 900,
            actualFrameDurationNs = 33_333_333L
        )
        assertEquals("API36_EXPOSURE_REALIZED", truth.status)
    }

    @Test
    fun `api36 materially shorter exposure is not realized`() {
        val truth = DefaultRawExposureRealizationEvaluator.evaluate(
            DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY, 1, 2L,
            1, 1, 2, 2, 30_000_000L, 20_000_000L, null, 1200, 33_333_333L
        )
        assertEquals("API36_EXPOSURE_NOT_REALIZED_SHORT", truth.status)
    }

    @Test
    fun `manual fallback verifies both shutter and iso`() {
        val truth = DefaultRawExposureRealizationEvaluator.evaluate(
            DefaultRawExposureRoute.MANUAL_FALLBACK, 1, 3L,
            0, 0, null, null, 20_000_000L, 20_100_000L, 1600, 1590, 20_100_000L
        )
        assertEquals("MANUAL_EXPOSURE_REALIZED", truth.status)
    }
}
