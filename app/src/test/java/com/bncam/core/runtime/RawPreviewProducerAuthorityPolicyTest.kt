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
}
