package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BnAutoPublishedTargetTest {
    private val limits = BnAutoSensorLimits(
        ExposureBounds(100, 6400, 100_000L, 2_000_000_000L),
        2_000_000_000L, 33_000_000L, 40_000_000L, 800
    )

    private fun frame(timestamp: Long): BnAutoObservation = BnAutoObservation(
        timestampNs = timestamp, exposureNs = 10_000_000L, iso = 100,
        p20 = 0.00001, p80 = 0.0001, p98 = 0.0002,
        channelClipFractions = List(4) { 0.0 },
        sampleCount = 3072, spatialHighlightFraction = 0.0
    )

    @Test fun rejectedVirtualProposalsDoNotCompound() {
        val engine = BnAutoExposureEngine()
        val committed = engine.current("main", limits)
        val first = engine.observe("main", limits, frame(1), committedDecision = committed)
        val second = engine.observe("main", limits, frame(2), committedDecision = committed)
        val third = engine.observe("main", limits, frame(3), committedDecision = committed)
        assertEquals(first.exposureProduct, second.exposureProduct, 0.0001)
        assertEquals(first.exposureProduct, third.exposureProduct, 0.0001)
    }

    @Test fun acceptedButUnrealizedProposalCannotAdvanceFeedback() {
        val engine = BnAutoExposureEngine()
        val committed = engine.current("main", limits)
        val first = engine.observe("main", limits, frame(1), committedDecision = committed)
        val second = engine.observe("main", limits, frame(2), committedDecision = first)
        assertEquals("Only a realized physical frame may advance exposure feedback",
            first.exposureProduct, second.exposureProduct, 0.0001)
    }
}
