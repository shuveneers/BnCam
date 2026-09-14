package com.bncam.ui.screens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewFrameLifecycleLedgerTest {
    @Test
    fun `gpu frame follows acquisition import submission presentation release order`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        assertTrue(ledger.acquired("RAW10", 4, 100L, 1L))
        assertTrue(ledger.gpuImported(4, 100L, 2L))
        assertTrue(ledger.inputReleased(4, 100L, 3L))
        assertTrue(ledger.submitted(4, 100L, 4L))
        assertTrue(ledger.presented(4, 100L, 5L))
        assertTrue(ledger.released(4, 100L, 6L, "gl_fence_signaled"))
        val snapshot = ledger.snapshot(4, 100L)!!
        assertEquals(RawPreviewFrameLifecycleState.RELEASED, snapshot.state)
        assertTrue(snapshot.presentationObserved)
        assertFalse(snapshot.inputRetained)
        assertEquals(0, snapshot.transitionErrorCount)
    }

    @Test
    fun `cpu fallback may submit without gpu import`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        ledger.acquired("RAW_SENSOR", 1, 200L, 1L)
        assertTrue(ledger.submitted(1, 200L, 2L))
        assertTrue(ledger.released(1, 200L, 3L, "cpu_upload_complete"))
    }

    @Test
    fun `invalid transition is rejected and counted`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        ledger.acquired("RAW10", 1, 300L, 1L)
        assertFalse(ledger.presented(1, 300L, 2L))
        assertEquals(1, ledger.snapshot(1, 300L)!!.transitionErrorCount)
    }

    @Test
    fun `release is terminal but late presentation telemetry may still be recorded`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        ledger.acquired("RAW10", 2, 400L, 1L)
        ledger.submitted(2, 400L, 2L)
        ledger.released(2, 400L, 3L, "safe_recycle")
        assertTrue(ledger.presented(2, 400L, 4L))
        val snapshot = ledger.snapshot(2, 400L)!!
        assertEquals(RawPreviewFrameLifecycleState.RELEASED, snapshot.state)
        assertTrue(snapshot.presentationObserved)
    }

    @Test
    fun `generation release closes only matching active records`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        ledger.acquired("RAW10", 7, 500L, 1L)
        ledger.acquired("RAW10", 8, 600L, 1L)
        assertEquals(1, ledger.releaseGeneration(7, 2L, "route_change"))
        assertEquals(RawPreviewFrameLifecycleState.RELEASED, ledger.snapshot(7, 500L)!!.state)
        assertEquals(RawPreviewFrameLifecycleState.ACQUIRED, ledger.snapshot(8, 600L)!!.state)
    }

    @Test
    fun `duplicate live acquire cannot create a second owner`() {
        val ledger = RawPreviewFrameLifecycleLedger()
        assertTrue(ledger.acquired("RAW_SENSOR", 9, 700L, 1L))
        assertFalse(ledger.acquired("RAW_SENSOR", 9, 700L, 2L))
        assertEquals(1, ledger.activeCount())
    }
}
