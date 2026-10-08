package com.bncam.core.capture

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class CaptureOutcome { PUBLISHED, FAILURE, REJECTED, CANCELLED }

data class CaptureTrigger(val id: Long, val timestampNs: Long, val timestampUtcMs: Long, val source: String)

data class CaptureTerminalRecord(
    val session: String,
    val trigger: CaptureTrigger,
    val admitted: Boolean,
    val outcome: CaptureOutcome,
    val reason: String,
    val completedNs: Long,
    val context: String
)

/** One authority from received shutter through publication. Only outstanding triggers are retained.
 * Callback receives an immutable terminal record; repeated completion is a no-op.
 * A process kill cannot promise a terminal write: exported scopes must check for missing IDs.
 */
class CaptureTerminalAccounting(
    private val clockNs: () -> Long = System::nanoTime,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    val session: String = UUID.randomUUID().toString(),
    private val terminal: (CaptureTerminalRecord) -> Unit = {}
) {
    private data class Pending(val trigger: CaptureTrigger, var admitted: Boolean = false, var context: String = "")
    private val nextId = AtomicLong()
    private val pending = linkedMapOf<Long, Pending>()

    @Synchronized fun receive(source: String): CaptureTrigger {
        val trigger = CaptureTrigger(nextId.incrementAndGet(), clockNs(), wallClockMs(), source)
        pending[trigger.id] = Pending(trigger)
        return trigger
    }

    @Synchronized fun admit(trigger: CaptureTrigger, context: String) {
        pending[trigger.id]?.also { it.admitted = true; it.context = context }
    }

    @Synchronized fun finish(trigger: CaptureTrigger, outcome: CaptureOutcome, reason: String): Boolean {
        require(reason.isNotBlank())
        val entry = pending.remove(trigger.id) ?: return false
        terminal(CaptureTerminalRecord(session, entry.trigger, entry.admitted, outcome, reason, clockNs(), entry.context))
        return true
    }

    @Synchronized fun cancelUnadmitted(reason: String) {
        pending.values.filter { !it.admitted }.map { it.trigger }.forEach {
            finish(it, CaptureOutcome.CANCELLED, reason)
        }
    }

    @Synchronized fun finishUnadmitted(trigger: CaptureTrigger, reason: String) {
        if (pending[trigger.id]?.admitted == false) finish(trigger, CaptureOutcome.CANCELLED, reason)
    }

    @Synchronized fun isOutstanding(trigger: CaptureTrigger): Boolean = pending.containsKey(trigger.id)

    companion object {
        /** Initialized by Application; Android-free instances are used by regression tests. */
        lateinit var process: CaptureTerminalAccounting
    }
}
