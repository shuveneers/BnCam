package com.bncam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bncam.core.capture.*
import com.bncam.core.output.CaptureProcessingQueue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CaptureTerminalBackpressureDeviceTest {
    @Test fun queueCompletionReconcilesALateAttemptBinding() {
        val reservation = CaptureProcessingQueue.tryReserve("QualificationLateBinding", System.nanoTime())!!
        var callbacks = 0
        try {
            reservation.fail("validation_failure_before_registration")
            CaptureProcessingQueue.onTerminal(reservation.workId) { terminal ->
                callbacks++
                assertEquals(reservation.workId, terminal.workId)
                assertEquals("validation_failure_before_registration", terminal.failureReason)
            }
            reservation.fail("duplicate")
            assertEquals(1, callbacks)
        } finally {
            reservation.fail("validation_cleanup")
        }
    }

    @Test fun heldProductionReservationsRejectEveryExcessTriggerExactlyOnce() {
        val records = mutableListOf<CaptureTerminalRecord>()
        val ledger = CaptureTerminalAccounting(terminal = records::add)
        val coordinator = CaptureAttemptCoordinator(accounting = ledger)
        val held = mutableListOf<Pair<Long, CaptureProcessingQueue.Reservation>>()
        val ids = mutableSetOf<Long>()
        try {
            repeat(100) {
                val trigger = ledger.receive("connected_backpressure")
                assertTrue(ids.add(trigger.id))
                val id = coordinator.begin(CaptureAttemptContext(
                    "fixture", null, "VALIDATION", "RAW_SENSOR", "HELD_RESERVATION", "SINGLE", 1, 8, 1, trigger
                ))!!
                val reservation = CaptureProcessingQueue.tryReserve("QualificationHeld", trigger.timestampNs)
                if (reservation == null) {
                    coordinator.finish(id, CaptureAttemptResult.REJECTED, "processing_queue_full")
                } else {
                    coordinator.markSubmitted(id, reservation.workId)
                    held.add(id to reservation)
                }
            }
            assertEquals(3, held.size)
        } finally {
            held.forEach { (id, reservation) ->
                reservation.fail("validation_reservation_released")
                coordinator.finish(id, CaptureAttemptResult.CANCELLED, "validation_reservation_released")
                assertNull(coordinator.finish(id, CaptureAttemptResult.SUCCESS, "late_duplicate"))
            }
        }
        assertEquals(100, records.size)
        assertEquals(ids, records.map { it.trigger.id }.toSet())
        assertEquals(97, records.count { it.outcome == CaptureOutcome.REJECTED && it.reason == "processing_queue_full" })
        assertEquals(3, records.count { it.outcome == CaptureOutcome.CANCELLED })
        val output = JSONArray()
        records.forEach { record -> output.put(JSONObject().apply {
            put("captureId", record.trigger.id); put("timestampNs", record.trigger.timestampNs)
            put("status", record.outcome.name); put("reason", record.reason)
        }) }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "terminal_backpressure.json")
            .writeText(JSONObject().put("triggered", 100).put("queueAdmitted", 3).put("records", output).toString(2))
    }
}
