package com.bncam.ui.screens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewHealthMonitorTest {
    private fun startHealthy(generation: Int, now: Long, present: Boolean = false) {
        RawPreviewHealthMonitor.route("RAW_SENSOR", generation, 33_333_333L, now)
        RawPreviewHealthMonitor.captureResultProgress(generation, 1L, 33_333_333L, 10_000_000L, now + 1)
        RawPreviewHealthMonitor.imageReaderProgress(generation, 1L, now + 2)
        RawPreviewHealthMonitor.rendererOffer(generation, now + 3)
        RawPreviewHealthMonitor.rendererPublication(generation, 4, 0.01f, 0.8f, 0.2f, "available=3", now + 4)
        RawPreviewHealthMonitor.glAccepted(generation, 4, now + 5)
        RawPreviewHealthMonitor.glDraw(generation, 4, now + 6)
        if (present) RawPreviewHealthMonitor.displayPresented(generation, now + 7)
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
    fun firstStaleStageWins() {
        val g = 102
        val start = 2_000_000_000L
        startHealthy(g, start)
        val threshold = RawPreviewHealthMonitor.snapshot(start + 10).stallThresholdNs
        val staleNow = start + threshold + 100L
        // Keep Camera2 metadata alive but let ImageReader and everything downstream age out.
        RawPreviewHealthMonitor.captureResultProgress(g, 2L, 33_333_333L, 10_000_000L, staleNow)
        assertEquals(RawPreviewHealthStage.RAW_IMAGE_READER, RawPreviewHealthMonitor.snapshot(staleNow).stage)
    }

    @Test
    fun presentationIsRequiredOnlyAfterRealPresentationWasObserved() {
        val g = 103
        val start = 3_000_000_000L
        startHealthy(g, start, present = false)
        assertFalse(RawPreviewHealthMonitor.snapshot(start + 10).actualPresentationObserved)
        assertEquals(RawPreviewHealthStage.HEALTHY, RawPreviewHealthMonitor.snapshot(start + 10).stage)

        RawPreviewHealthMonitor.displayPresented(g, start + 20)
        val threshold = RawPreviewHealthMonitor.snapshot(start + 21).stallThresholdNs
        val now = start + 20 + threshold + 1
        RawPreviewHealthMonitor.captureResultProgress(g, 2L, 33_333_333L, 10_000_000L, now)
        RawPreviewHealthMonitor.imageReaderProgress(g, 2L, now)
        RawPreviewHealthMonitor.rendererOffer(g, now)
        RawPreviewHealthMonitor.rendererPublication(g, 4, 0.01f, 0.8f, 0.2f, "available=3", now)
        RawPreviewHealthMonitor.glAccepted(g, 4, now)
        RawPreviewHealthMonitor.glDraw(g, 4, now)
        assertEquals(RawPreviewHealthStage.EGL_PRESENTATION, RawPreviewHealthMonitor.snapshot(now).stage)
    }

    @Test
    fun staleGenerationCannotOverwriteCurrentProgress() {
        val start = 4_000_000_000L
        startHealthy(104, start)
        RawPreviewHealthMonitor.route("RAW_SENSOR", 105, 33_333_333L, start + 100)
        RawPreviewHealthMonitor.imageReaderProgress(104, 99L, start + 200)
        val snapshot = RawPreviewHealthMonitor.snapshot(start + 201)
        assertEquals(105, snapshot.pipelineGeneration)
        assertEquals(0L, snapshot.lastImageReaderElapsedNs)
    }

    @Test
    fun exactBlackRendererOutputIsClassifiedWithoutChangingExposure() {
        val g = 106
        val start = 5_000_000_000L
        startHealthy(g, start)
        RawPreviewHealthMonitor.rendererPublication(g, 4, 0f, 0f, 0f, "available=3", start + 20)
        val snapshot = RawPreviewHealthMonitor.snapshot(start + 21)
        assertEquals(RawPreviewHealthStage.RGB_OUTPUT, snapshot.stage)
        assertTrue(snapshot.outputRgbMax <= 1.0e-6f)
    }
}
