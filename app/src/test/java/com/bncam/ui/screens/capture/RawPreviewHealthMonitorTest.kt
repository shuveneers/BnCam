package com.bncam.ui.screens.capture

import com.bncam.core.runtime.RawPreviewProducerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewHealthMonitorTest {
    private val canonical = RawPreviewProducerKind.CANONICAL_RING
    private val support = RawPreviewProducerKind.CUSTOM_IMAGE_READER

    private fun startHealthy(
        generation: Int,
        now: Long,
        present: Boolean = false,
        producerKind: RawPreviewProducerKind = canonical,
        timestampNs: Long = 1L
    ) {
        RawPreviewHealthMonitor.route("RAW_SENSOR", generation, 33_333_333L, now)
        RawPreviewHealthMonitor.captureResultProgress(
            generation, timestampNs, 33_333_333L, 10_000_000L, now + 1
        )
        RawPreviewHealthMonitor.imageReaderProgress(
            generation, timestampNs, producerKind, now + 2
        )
        RawPreviewHealthMonitor.rendererOffer(
            generation, timestampNs, producerKind, now + 3
        )
        RawPreviewHealthMonitor.rendererPublication(
            generation = generation,
            sensorTimestampNs = timestampNs,
            producerKind = producerKind,
            frameEglGeneration = 4,
            rgbMin = 0.01f,
            rgbMax = 0.8f,
            rgbMean = 0.2f,
            slotHealth = "available=3",
            nowElapsedNs = now + 4
        )
        RawPreviewHealthMonitor.glAccepted(
            generation, timestampNs, producerKind, 4, now + 5
        )
        RawPreviewHealthMonitor.glDraw(
            generation, timestampNs, producerKind, 4, now + 6
        )
        if (present) {
            RawPreviewHealthMonitor.displayPresented(
                generation, timestampNs, producerKind, now + 7
            )
        }
    }

    @Test
    fun expectedIntervalUsesExposureAndSixFrameThreshold() {
        val g = 101
        val start = 1_000_000_000L
        RawPreviewHealthMonitor.route("RAW10", g, 20_000_000L, start)
        RawPreviewHealthMonitor.captureResultProgress(g, 1L, 40_000_000L, 250_000_000L, start + 1)
        val snapshot = RawPreviewHealthMonitor.snapshot(start + 2)
        assertEquals(250_000_000L, snapshot.expectedIntervalNs)
        assertEquals(1_500_000_000L, snapshot.stallThresholdNs)
    }

    @Test
    fun firstStaleStageWinsAndAggregateImageReaderStageDoesNotGuessProducer() {
        val g = 102
        val start = 2_000_000_000L
        startHealthy(g, start)
        val threshold = RawPreviewHealthMonitor.snapshot(start + 10).stallThresholdNs
        val staleNow = start + threshold + 100L
        RawPreviewHealthMonitor.captureResultProgress(g, 2L, 33_333_333L, 10_000_000L, staleNow)
        val snapshot = RawPreviewHealthMonitor.snapshot(staleNow)
        assertEquals(RawPreviewHealthStage.RAW_IMAGE_READER, snapshot.stage)
        assertNull(snapshot.stageFrameHint)
    }

    @Test
    fun firstPresentationIsRequiredAfterBoundedGlGraceWindowAndCarriesExactProducer() {
        val g = 103
        val start = 3_000_000_000L
        startHealthy(g, start, present = false, producerKind = support, timestampNs = 11L)
        val initial = RawPreviewHealthMonitor.snapshot(start + 10)
        assertFalse(initial.actualPresentationObserved)
        assertTrue(initial.firstGlDrawElapsedNs > 0L)
        assertEquals(RawPreviewHealthStage.HEALTHY, initial.stage)

        val now = initial.firstGlDrawElapsedNs + initial.stallThresholdNs + 1
        RawPreviewHealthMonitor.captureResultProgress(g, 12L, 33_333_333L, 10_000_000L, now)
        RawPreviewHealthMonitor.imageReaderProgress(g, 12L, support, now)
        RawPreviewHealthMonitor.rendererOffer(g, 12L, support, now)
        RawPreviewHealthMonitor.rendererPublication(
            g, 12L, support, 4, 0.01f, 0.8f, 0.2f, "available=3", now
        )
        RawPreviewHealthMonitor.glAccepted(g, 12L, support, 4, now)
        RawPreviewHealthMonitor.glDraw(g, 12L, support, 4, now)
        val stalled = RawPreviewHealthMonitor.snapshot(now)
        assertEquals(RawPreviewHealthStage.EGL_PRESENTATION, stalled.stage)
        assertEquals(support, stalled.stageFrameHint?.producerKind)
        assertEquals(12L, stalled.stageFrameHint?.sensorTimestampNs)
    }

    @Test
    fun actualPresentationClearsFirstPresentationFaultAndThenUsesRollingFreshness() {
        val g = 109
        val start = 8_000_000_000L
        startHealthy(g, start, present = false)
        RawPreviewHealthMonitor.displayPresented(g, 1L, canonical, start + 20)
        val healthy = RawPreviewHealthMonitor.snapshot(start + 21)
        assertTrue(healthy.actualPresentationObserved)
        assertEquals(RawPreviewHealthStage.HEALTHY, healthy.stage)
        assertEquals(canonical, healthy.lastDisplayPresentedFrame?.producerKind)

        val now = start + 20 + healthy.stallThresholdNs + 1
        RawPreviewHealthMonitor.captureResultProgress(g, 2L, 33_333_333L, 10_000_000L, now)
        RawPreviewHealthMonitor.imageReaderProgress(g, 2L, canonical, now)
        RawPreviewHealthMonitor.rendererOffer(g, 2L, canonical, now)
        RawPreviewHealthMonitor.rendererPublication(
            g, 2L, canonical, 4, 0.01f, 0.8f, 0.2f, "available=3", now
        )
        RawPreviewHealthMonitor.glAccepted(g, 2L, canonical, 4, now)
        RawPreviewHealthMonitor.glDraw(g, 2L, canonical, 4, now)
        assertEquals(RawPreviewHealthStage.EGL_PRESENTATION, RawPreviewHealthMonitor.snapshot(now).stage)
    }

    @Test
    fun staleGenerationCannotOverwriteCurrentProgress() {
        val start = 4_000_000_000L
        startHealthy(104, start)
        RawPreviewHealthMonitor.route("RAW_SENSOR", 105, 33_333_333L, start + 100)
        RawPreviewHealthMonitor.imageReaderProgress(104, 99L, support, start + 200)
        val snapshot = RawPreviewHealthMonitor.snapshot(start + 201)
        assertEquals(105, snapshot.pipelineGeneration)
        assertEquals(0L, snapshot.lastImageReaderElapsedNs)
        assertNull(snapshot.lastImageReaderFrame)
    }

    @Test
    fun impossibleBlackRequiresThreeSignalBackedFramesBeforeClassification() {
        val g = 106
        val start = 5_000_000_000L
        startHealthy(g, start)

        repeat(2) { index ->
            RawPreviewHealthMonitor.rendererPublication(
                g, 20L + index, canonical, 4, 0f, 0f, 0f, "available=3", start + 20 + index,
                normalizedRawMax = 0.25f,
                sceneP50 = 0.05f
            )
        }
        assertEquals(RawPreviewHealthStage.HEALTHY, RawPreviewHealthMonitor.snapshot(start + 23).stage)

        RawPreviewHealthMonitor.rendererPublication(
            g, 22L, canonical, 4, 0f, 0f, 0f, "available=3", start + 24,
            normalizedRawMax = 0.25f,
            sceneP50 = 0.05f
        )
        val snapshot = RawPreviewHealthMonitor.snapshot(start + 25)
        assertEquals(RawPreviewHealthStage.RGB_OUTPUT, snapshot.stage)
        assertTrue(snapshot.impossibleBlackConfirmed)
        assertEquals(3, snapshot.consecutiveImpossibleBlackFrames)
        assertEquals(canonical, snapshot.stageFrameHint?.producerKind)
    }

    @Test
    fun trulyDarkRawInputNeverConfirmsImpossibleBlack() {
        val g = 107
        val start = 6_000_000_000L
        startHealthy(g, start)

        repeat(6) { index ->
            RawPreviewHealthMonitor.rendererPublication(
                g, 30L + index, canonical, 4, 0f, 0f, 0f, "available=3", start + 20 + index,
                normalizedRawMax = 0.001f,
                sceneP50 = 0.0001f
            )
        }
        val snapshot = RawPreviewHealthMonitor.snapshot(start + 30)
        assertEquals(RawPreviewHealthStage.HEALTHY, snapshot.stage)
        assertFalse(snapshot.impossibleBlackConfirmed)
        assertEquals(0, snapshot.consecutiveImpossibleBlackFrames)
    }

    @Test
    fun oneGoodOutputClearsBlackFaultQuorum() {
        val g = 108
        val start = 7_000_000_000L
        startHealthy(g, start)

        repeat(2) { index ->
            RawPreviewHealthMonitor.rendererPublication(
                g, 40L + index, canonical, 4, 0f, 0f, 0f, "available=3", start + 20 + index,
                normalizedRawMax = 0.2f,
                sceneP50 = 0.04f
            )
        }
        RawPreviewHealthMonitor.rendererPublication(
            g, 42L, canonical, 4, 0.01f, 0.2f, 0.05f, "available=3", start + 30,
            normalizedRawMax = 0.2f,
            sceneP50 = 0.04f
        )
        val snapshot = RawPreviewHealthMonitor.snapshot(start + 31)
        assertEquals(RawPreviewHealthStage.HEALTHY, snapshot.stage)
        assertEquals(0, snapshot.consecutiveImpossibleBlackFrames)
    }

    @Test
    fun sameTimestampFromDifferentProducersRemainsDistinctAcrossDownstreamStages() {
        val g = 110
        val start = 9_000_000_000L
        RawPreviewHealthMonitor.route("RAW_SENSOR", g, 33_333_333L, start)
        RawPreviewHealthMonitor.captureResultProgress(g, 55L, 33_333_333L, 10_000_000L, start + 1)
        RawPreviewHealthMonitor.imageReaderProgress(g, 55L, canonical, start + 2)
        RawPreviewHealthMonitor.rendererOffer(g, 55L, canonical, start + 3)
        RawPreviewHealthMonitor.rendererPublication(
            g, 55L, canonical, 4, 0.01f, 0.5f, 0.1f, "available=3", start + 4
        )
        RawPreviewHealthMonitor.glAccepted(g, 55L, canonical, 4, start + 5)
        RawPreviewHealthMonitor.glDraw(g, 55L, canonical, 4, start + 6)
        RawPreviewHealthMonitor.displayPresented(g, 55L, canonical, start + 7)

        // A support copy of the exact same sensor timestamp may legitimately enter later.
        RawPreviewHealthMonitor.imageReaderProgress(g, 55L, support, start + 8)
        RawPreviewHealthMonitor.rendererOffer(g, 55L, support, start + 9)
        RawPreviewHealthMonitor.rendererPublication(
            g, 55L, support, 4, 0.01f, 0.5f, 0.1f, "available=3", start + 10
        )
        val snapshot = RawPreviewHealthMonitor.snapshot(start + 11)
        assertEquals(support, snapshot.lastRendererPublicationFrame?.producerKind)
        assertEquals(canonical, snapshot.lastDisplayPresentedFrame?.producerKind)
        assertFalse(snapshot.exactDownstreamChainCoherent)
    }
    @Test
    fun sameSensorTimestampFromTwoProducersCountsOnceForBlackFaultQuorum() {
        val g = 111
        val start = 10_000_000_000L
        startHealthy(g, start)

        RawPreviewHealthMonitor.rendererPublication(
            g, 70L, canonical, 4, 0f, 0f, 0f, "available=3", start + 20,
            normalizedRawMax = 0.25f, sceneP50 = 0.05f
        )
        RawPreviewHealthMonitor.rendererPublication(
            g, 70L, support, 4, 0f, 0f, 0f, "available=3", start + 21,
            normalizedRawMax = 0.25f, sceneP50 = 0.05f
        )
        var snapshot = RawPreviewHealthMonitor.snapshot(start + 22)
        assertEquals(1, snapshot.consecutiveImpossibleBlackFrames)
        assertFalse(snapshot.impossibleBlackConfirmed)

        RawPreviewHealthMonitor.rendererPublication(
            g, 71L, canonical, 4, 0f, 0f, 0f, "available=3", start + 23,
            normalizedRawMax = 0.25f, sceneP50 = 0.05f
        )
        snapshot = RawPreviewHealthMonitor.snapshot(start + 24)
        assertEquals(2, snapshot.consecutiveImpossibleBlackFrames)
        assertFalse(snapshot.impossibleBlackConfirmed)
    }

}
