package com.bncam.ui.screens.capture

import com.bncam.core.runtime.RawPreviewProducerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewFrameLifecycleLedgerTest {
    private val canonical = RawPreviewProducerKind.CANONICAL_RING
    private val support = RawPreviewProducerKind.CUSTOM_IMAGE_READER

    @Test
    fun `gpu frame follows acquisition import submission presentation release order`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        assertTrue(ledger.acquired("RAW10", 4, 100L, canonical, 1L))
        assertTrue(ledger.gpuImported(4, 100L, canonical, 2L))
        assertTrue(ledger.inputReleased(4, 100L, canonical, 3L))
        assertTrue(ledger.submitted(4, 100L, canonical, 4L))
        assertTrue(ledger.presented(4, 100L, canonical, 5L))
        assertTrue(ledger.released(4, 100L, canonical, 6L, "gl_fence_signaled"))
        val snapshot = ledger.snapshot(4, 100L, canonical)!!
        assertEquals(RawPreviewFrameLifecycleState.RELEASED, snapshot.state)
        assertEquals(canonical, snapshot.producerKind)
        assertTrue(snapshot.presentationObserved)
        assertFalse(snapshot.inputRetained)
        assertEquals(0, snapshot.transitionErrorCount)
    }

    @Test
    fun `cpu fallback may submit without gpu import`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        ledger.acquired("RAW_SENSOR", 1, 200L, canonical, 1L)
        assertTrue(ledger.submitted(1, 200L, canonical, 2L))
        assertTrue(ledger.released(1, 200L, canonical, 3L, "cpu_upload_complete"))
    }

    @Test
    fun `invalid transition is rejected and counted`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        ledger.acquired("RAW10", 1, 300L, canonical, 1L)
        assertFalse(ledger.presented(1, 300L, canonical, 2L))
        assertEquals(1, ledger.snapshot(1, 300L, canonical)!!.transitionErrorCount)
    }

    @Test
    fun `release is terminal but late presentation telemetry may still be recorded`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        ledger.acquired("RAW10", 2, 400L, canonical, 1L)
        ledger.submitted(2, 400L, canonical, 2L)
        ledger.released(2, 400L, canonical, 3L, "safe_recycle")
        assertTrue(ledger.presented(2, 400L, canonical, 4L))
        val snapshot = ledger.snapshot(2, 400L, canonical)!!
        assertEquals(RawPreviewFrameLifecycleState.RELEASED, snapshot.state)
        assertTrue(snapshot.presentationObserved)
    }

    @Test
    fun `generation release closes only matching active records`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        ledger.acquired("RAW10", 7, 500L, canonical, 1L)
        ledger.acquired("RAW10", 8, 600L, canonical, 1L)
        assertEquals(1, ledger.releaseGeneration(7, 2L, "route_change"))
        assertEquals(
            RawPreviewFrameLifecycleState.RELEASED,
            ledger.snapshot(7, 500L, canonical)!!.state
        )
        assertEquals(
            RawPreviewFrameLifecycleState.ACQUIRED,
            ledger.snapshot(8, 600L, canonical)!!.state
        )
    }

    @Test
    fun `duplicate live acquire cannot create a second owner for same producer`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        assertTrue(ledger.acquired("RAW_SENSOR", 9, 700L, canonical, 1L))
        assertFalse(ledger.acquired("RAW_SENSOR", 9, 700L, canonical, 2L))
        assertEquals(1, ledger.activeCount())
    }

    @Test
    fun `same sensor timestamp from canonical and support are independent frame identities`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        assertTrue(ledger.acquired("RAW_SENSOR", 10, 800L, canonical, 1L))
        assertTrue(ledger.acquired("RAW_SENSOR", 10, 800L, support, 2L))
        assertEquals(2, ledger.activeCount())

        assertTrue(ledger.submitted(10, 800L, support, 3L))
        assertTrue(ledger.presented(10, 800L, support, 4L))
        assertTrue(ledger.released(10, 800L, support, 5L, "support_presented"))

        val canonicalSnapshot = ledger.snapshot(10, 800L, canonical)!!
        val supportSnapshot = ledger.snapshot(10, 800L, support)!!
        assertEquals(RawPreviewFrameLifecycleState.ACQUIRED, canonicalSnapshot.state)
        assertEquals(RawPreviewFrameLifecycleState.RELEASED, supportSnapshot.state)
        assertEquals(canonical, canonicalSnapshot.producerKind)
        assertEquals(support, supportSnapshot.producerKind)
    }
}
