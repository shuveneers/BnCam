package com.bncam.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewProducerAuthorityPolicyTest {
    @Test
    fun `custom input arrival alone never suppresses canonical preview`() {
        val tracker = RawPreviewProducerAuthorityTracker()
        tracker.reset(7)
        assertFalse(tracker.maySuppressCanonical(7, customInputFresh = true))
    }

    @Test
    fun `renderer publication alone never grants custom producer authority`() {
        val tracker = RawPreviewProducerAuthorityTracker()
        tracker.reset(7)
        tracker.customRendererPublished(7)
        assertFalse(tracker.maySuppressCanonical(7, customInputFresh = true))
    }

    @Test
    fun `egl presentation grants custom producer authority`() {
        val tracker = RawPreviewProducerAuthorityTracker()
        tracker.reset(7)
        tracker.customRendererPublished(7)
        tracker.customFramePresented(7)
        assertTrue(tracker.maySuppressCanonical(7, customInputFresh = true))
    }

    @Test
    fun `stale custom input never suppresses canonical even after presentation`() {
        val tracker = RawPreviewProducerAuthorityTracker()
        tracker.reset(7)
        tracker.customFramePresented(7)
        assertFalse(tracker.maySuppressCanonical(7, customInputFresh = false))
    }

    @Test
    fun `new generation revokes prior custom authority`() {
        val tracker = RawPreviewProducerAuthorityTracker()
        tracker.reset(7)
        tracker.customFramePresented(7)
        assertTrue(tracker.maySuppressCanonical(7, customInputFresh = true))
        assertFalse(tracker.maySuppressCanonical(8, customInputFresh = true))
    }

    @Test
    fun `diagnostic read reports presentation truth without granting authority`() {
        val tracker = RawPreviewProducerAuthorityTracker()
        tracker.reset(7)
        tracker.customRendererPublished(7)
        val summary = tracker.diagnosticSummary(7)
        assertTrue(summary.contains("customPresentationProven=false"))
        assertTrue(summary.contains("customPublishedFrames=1"))
        assertFalse(tracker.maySuppressCanonical(7, customInputFresh = true))
    }

    @Test
    fun `stale diagnostic read does not reset current generation authority`() {
        val tracker = RawPreviewProducerAuthorityTracker()
        tracker.reset(7)
        tracker.customFramePresented(7)
        val summary = tracker.diagnosticSummary(8)
        assertTrue(summary.contains("generationMatch=false"))
        assertTrue(summary.contains("STALE_GENERATION"))
        assertTrue(tracker.maySuppressCanonical(7, customInputFresh = true))
    }
}
