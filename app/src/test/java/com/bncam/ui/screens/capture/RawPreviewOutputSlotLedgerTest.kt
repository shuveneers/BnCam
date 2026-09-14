package com.bncam.ui.screens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewOutputSlotLedgerTest {
    @Test
    fun fenceCreationFailureFallsBackToCpuWithoutSlotStarvation() {
        val ledger = RawPreviewOutputSlotLedger(3)
        repeat(30) { index ->
            val slot = index % 3
            assertTrue("slot $slot should remain reusable", ledger.tryAcquire(slot))
            // Fence creation failure quarantines GPU backing in the renderer, but slot ownership
            // must return to AVAILABLE so its independent CPU RGBA store can continue service.
            assertTrue(ledger.releaseToAvailable(slot))
        }
        val snapshot = ledger.snapshot()
        assertEquals(3, snapshot.available)
        assertEquals(0, snapshot.inFlight)
        assertEquals(0, snapshot.pendingFence)
        assertEquals(3, snapshot.total)
    }

    @Test
    fun fencePollFailureReturnsPendingSlotToCpuService() {
        val ledger = RawPreviewOutputSlotLedger(3)
        assertTrue(ledger.tryAcquire(1))
        assertTrue(ledger.markPendingFence(1))
        assertEquals(RawPreviewOutputSlotState.PENDING_GL_FENCE, ledger.state(1))
        assertTrue(ledger.releaseToAvailable(1))
        assertEquals(RawPreviewOutputSlotState.AVAILABLE, ledger.state(1))
        assertTrue(ledger.tryAcquire(1))
    }

    @Test
    fun allThreeGpuBackingsCanFailWithoutLosingTheFiniteSlotPool() {
        val ledger = RawPreviewOutputSlotLedger(3)
        repeat(3) { slot ->
            assertTrue(ledger.tryAcquire(slot))
            assertTrue(ledger.releaseToAvailable(slot))
        }
        assertEquals(3, ledger.snapshot().available)
        repeat(12) { cycle ->
            val slot = cycle % 3
            assertTrue(ledger.tryAcquire(slot))
            assertTrue(ledger.releaseToAvailable(slot))
        }
        assertEquals(3, ledger.snapshot().available)
    }

    @Test
    fun duplicateReturnIsRejectedSoQueueCannotAcquireDuplicateOwnership() {
        val ledger = RawPreviewOutputSlotLedger(3)
        assertTrue(ledger.tryAcquire(0))
        assertTrue(ledger.releaseToAvailable(0))
        assertFalse(ledger.releaseToAvailable(0))
        assertEquals(3, ledger.snapshot().available)
    }

    @Test
    fun invalidStateCannotBeMarkedPendingFence() {
        val ledger = RawPreviewOutputSlotLedger(3)
        assertFalse(ledger.markPendingFence(0))
        assertEquals(RawPreviewOutputSlotState.AVAILABLE, ledger.state(0))
    }
}
