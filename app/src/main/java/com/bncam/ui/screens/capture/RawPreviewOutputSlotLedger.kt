package com.bncam.ui.screens.capture

import java.util.concurrent.atomic.AtomicReferenceArray

internal enum class RawPreviewOutputSlotState {
    AVAILABLE,
    IN_FLIGHT,
    PENDING_GL_FENCE,
    CLOSED
}

internal data class RawPreviewOutputSlotSnapshot(
    val available: Int,
    val inFlight: Int,
    val pendingFence: Int,
    val closed: Int,
    val total: Int
)

/**
 * Pure ownership ledger for the finite RAW-preview output-slot pool.
 *
 * GPU backing quarantine is deliberately not represented here: an OutputSlot whose AHardwareBuffer
 * is quarantined is still a valid CPU-visible slot. The ledger therefore models slot ownership only,
 * while RawPreviewRenderer separately guards whether a slot may use its GPU backing.
 */
internal class RawPreviewOutputSlotLedger(private val slotCount: Int) {
    private val states = AtomicReferenceArray<RawPreviewOutputSlotState>(slotCount)

    init {
        require(slotCount > 0)
        for (index in 0 until slotCount) states.set(index, RawPreviewOutputSlotState.AVAILABLE)
        assertInternalInvariant()
    }

    fun state(slotId: Int): RawPreviewOutputSlotState = states.get(valid(slotId))

    fun tryAcquire(slotId: Int): Boolean = states.compareAndSet(
        valid(slotId),
        RawPreviewOutputSlotState.AVAILABLE,
        RawPreviewOutputSlotState.IN_FLIGHT
    )

    fun markPendingFence(slotId: Int): Boolean = states.compareAndSet(
        valid(slotId),
        RawPreviewOutputSlotState.IN_FLIGHT,
        RawPreviewOutputSlotState.PENDING_GL_FENCE
    )

    /**
     * Returns an in-flight or fence-pending slot to CPU service. Idempotent duplicate returns are
     * rejected so the ConcurrentLinkedQueue can never acquire duplicate entries for one slot.
     */
    fun releaseToAvailable(slotId: Int): Boolean {
        val id = valid(slotId)
        while (true) {
            when (val current = states.get(id)) {
                RawPreviewOutputSlotState.IN_FLIGHT,
                RawPreviewOutputSlotState.PENDING_GL_FENCE -> {
                    if (states.compareAndSet(id, current, RawPreviewOutputSlotState.AVAILABLE)) return true
                }
                RawPreviewOutputSlotState.AVAILABLE,
                RawPreviewOutputSlotState.CLOSED -> return false
            }
        }
    }

    fun close(slotId: Int) {
        states.set(valid(slotId), RawPreviewOutputSlotState.CLOSED)
    }

    fun snapshot(): RawPreviewOutputSlotSnapshot {
        var available = 0
        var inFlight = 0
        var pending = 0
        var closed = 0
        for (index in 0 until slotCount) {
            val state = states.get(index)
                ?: error("RAW preview output slot $index has no ownership state")
            when (state) {
                RawPreviewOutputSlotState.AVAILABLE -> available++
                RawPreviewOutputSlotState.IN_FLIGHT -> inFlight++
                RawPreviewOutputSlotState.PENDING_GL_FENCE -> pending++
                RawPreviewOutputSlotState.CLOSED -> closed++
            }
        }
        return RawPreviewOutputSlotSnapshot(
            available = available,
            inFlight = inFlight,
            pendingFence = pending,
            closed = closed,
            total = available + inFlight + pending + closed
        )
    }

    fun assertInternalInvariant() {
        check(snapshot().total == slotCount) {
            "RAW preview output-slot accounting lost ownership: ${snapshot()} expected=$slotCount"
        }
    }

    private fun valid(slotId: Int): Int {
        require(slotId in 0 until slotCount) { "RAW preview output slot out of range: $slotId/$slotCount" }
        return slotId
    }
}
