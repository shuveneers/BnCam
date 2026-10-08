package com.bncam.core.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureTerminalAccountingTest {
    private fun context(trigger: CaptureTrigger? = null) = CaptureAttemptContext(
        "0", null, "Main", "RAW_SENSOR", "ZSL", "SINGLE", 1, 8, 4, trigger
    )

    @Test fun everyBusyTriggerHasItsOwnTerminalIdAndResetCancelsOutstandingWork() {
        val records = mutableListOf<CaptureTerminalRecord>()
        val ledger = CaptureTerminalAccounting(terminal = records::add)
        val coordinator = CaptureAttemptCoordinator(accounting = ledger)
        val first = coordinator.begin(context())!!
        repeat(99) { assertNull(coordinator.begin(context())) }
        coordinator.forceReset("camera_closed")
        assertNull(coordinator.finish(first, CaptureAttemptResult.SUCCESS, "late_worker"))
        assertEquals(100, records.size)
        assertEquals(100, records.map { it.trigger.id }.toSet().size)
        assertEquals(99, records.count { it.outcome == CaptureOutcome.REJECTED })
        assertEquals(1, records.count { it.outcome == CaptureOutcome.CANCELLED })
        assertTrue(records.all { it.reason.isNotBlank() && it.completedNs >= it.trigger.timestampNs })
    }

    @Test fun detachedOutOfOrderCompletionAndDuplicateCallbacksPreserveIds() {
        val records = mutableListOf<CaptureTerminalRecord>()
        val ledger = CaptureTerminalAccounting(terminal = records::add)
        val coordinator = CaptureAttemptCoordinator(accounting = ledger)
        val ids = (1..100).map {
            val trigger = ledger.receive("stress")
            val id = coordinator.begin(context(trigger))!!
            assertEquals(trigger.id, id)
            coordinator.markSubmitted(id, id + 1000)
            id
        }
        ids.reversed().forEach { id ->
            val result = when (id % 4) {
                0L -> CaptureAttemptResult.REJECTED
                1L -> CaptureAttemptResult.SUCCESS
                2L -> CaptureAttemptResult.PROCESSING_FAILURE
                else -> CaptureAttemptResult.CANCELLED
            }
            assertNotNull(coordinator.finish(id, result, "stress_$result"))
            assertNull(coordinator.finish(id, result, "duplicate"))
        }
        assertEquals(ids.toSet(), records.map { it.trigger.id }.toSet())
        assertEquals(100, records.size)
        CaptureOutcome.entries.forEach { outcome -> assertEquals(25, records.count { it.outcome == outcome }) }
    }

    @Test fun disposingUiCancelsOnlyUndispatchedTriggers() {
        val records = mutableListOf<CaptureTerminalRecord>()
        val ledger = CaptureTerminalAccounting(terminal = records::add)
        val accepted = ledger.receive("ui")
        ledger.admit(accepted, "camera")
        ledger.receive("buffered_event")
        ledger.cancelUnadmitted("ui_disposed")
        assertEquals(1, records.size)
        assertTrue(ledger.isOutstanding(accepted))
        ledger.finish(accepted, CaptureOutcome.PUBLISHED, "published")
        assertEquals(2, records.size)
    }

    @Test fun cameraClosePreservesDetachedWorkUntilRealPublication() {
        val records = mutableListOf<CaptureTerminalRecord>()
        val ledger = CaptureTerminalAccounting(terminal = records::add)
        val coordinator = CaptureAttemptCoordinator(accounting = ledger)
        val id = coordinator.begin(context())!!
        coordinator.markSubmitted(id, 123)
        coordinator.forceReset("camera_closed")
        assertTrue(records.isEmpty())
        assertEquals(id, coordinator.findByWorkId(123))
        assertNotNull(coordinator.finish(id, CaptureAttemptResult.SUCCESS, "published_after_camera_close"))
        assertEquals(CaptureOutcome.PUBLISHED, records.single().outcome)
    }

    @Test fun triggerCancelledBeforeRouterDeliveryCannotStartWork() {
        val records = mutableListOf<CaptureTerminalRecord>()
        val ledger = CaptureTerminalAccounting(terminal = records::add)
        val trigger = ledger.receive("buffered_ui")
        ledger.cancelUnadmitted("screen_disposed")
        assertNull(CaptureAttemptCoordinator(accounting = ledger).begin(context(trigger)))
        assertEquals(1, records.size)
    }
}
