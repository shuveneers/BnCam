package com.bncam.ui.screens.capture

import java.util.LinkedHashMap

internal enum class RawPreviewFrameLifecycleState {
    ACQUIRED,
    GPU_IMPORTED,
    SUBMITTED,
    PRESENTED,
    RELEASED
}

internal data class RawPreviewFrameLifecycleSnapshot(
    val source: String,
    val pipelineGeneration: Int,
    val sensorTimestampNs: Long,
    val state: RawPreviewFrameLifecycleState,
    val acquiredElapsedNs: Long,
    val gpuImportedElapsedNs: Long,
    val submittedElapsedNs: Long,
    val presentedElapsedNs: Long,
    val inputReleasedElapsedNs: Long,
    val releasedElapsedNs: Long,
    val releaseReason: String,
    val transitionErrorCount: Int
) {
    val inputRetained: Boolean get() = inputReleasedElapsedNs <= 0L
    val outputOwned: Boolean get() = state != RawPreviewFrameLifecycleState.RELEASED
    val presentationObserved: Boolean get() = presentedElapsedNs > 0L

    fun summary(): String =
        "source=$source;generation=$pipelineGeneration;sensorTimestampNs=$sensorTimestampNs;" +
            "state=$state;inputRetained=$inputRetained;outputOwned=$outputOwned;" +
            "presented=$presentationObserved;releaseReason=$releaseReason;" +
            "transitionErrors=$transitionErrorCount"
}

/**
 * Sensor-frame ownership ledger spanning Camera2 AHB retain, Vulkan import, GLES submission,
 * display presentation and final output-slot release.
 *
 * The input AHardwareBuffer reference may be released as soon as native rendering finishes; that
 * is tracked separately from output ownership, which remains alive until GL/display no longer need
 * the produced frame. The ledger is diagnostic + invariant enforcement only: it never waits on the
 * camera or GPU and cannot introduce viewfinder back-pressure.
 */
internal class RawPreviewFrameLifecycleLedger(
    private val maxHistory: Int = 96
) {
    private data class Key(val generation: Int, val timestampNs: Long)
    private data class MutableRecord(
        val source: String,
        val generation: Int,
        val timestampNs: Long,
        var state: RawPreviewFrameLifecycleState,
        var acquiredNs: Long,
        var gpuImportedNs: Long = 0L,
        var submittedNs: Long = 0L,
        var presentedNs: Long = 0L,
        var inputReleasedNs: Long = 0L,
        var releasedNs: Long = 0L,
        var releaseReason: String = "none",
        var transitionErrors: Int = 0
    )

    private val records = LinkedHashMap<Key, MutableRecord>()

    @Synchronized
    fun acquired(source: String, generation: Int, timestampNs: Long, nowNs: Long): Boolean {
        if (generation < 0 || timestampNs <= 0L) return false
        val key = Key(generation, timestampNs)
        val existing = records[key]
        if (existing != null && existing.state != RawPreviewFrameLifecycleState.RELEASED) {
            existing.transitionErrors++
            return false
        }
        records[key] = MutableRecord(
            source = source.ifBlank { "RAW" },
            generation = generation,
            timestampNs = timestampNs,
            state = RawPreviewFrameLifecycleState.ACQUIRED,
            acquiredNs = nowNs.coerceAtLeast(0L)
        )
        trimHistory()
        return true
    }

    @Synchronized
    fun gpuImported(generation: Int, timestampNs: Long, nowNs: Long): Boolean =
        transition(generation, timestampNs, nowNs, RawPreviewFrameLifecycleState.GPU_IMPORTED) {
            it.state == RawPreviewFrameLifecycleState.ACQUIRED
        }

    @Synchronized
    fun submitted(generation: Int, timestampNs: Long, nowNs: Long): Boolean =
        transition(generation, timestampNs, nowNs, RawPreviewFrameLifecycleState.SUBMITTED) {
            it.state == RawPreviewFrameLifecycleState.ACQUIRED ||
                it.state == RawPreviewFrameLifecycleState.GPU_IMPORTED
        }

    @Synchronized
    fun presented(generation: Int, timestampNs: Long, nowNs: Long): Boolean {
        val record = records[Key(generation, timestampNs)] ?: return false
        if (record.presentedNs > 0L) return true
        return when (record.state) {
            RawPreviewFrameLifecycleState.SUBMITTED -> {
                record.presentedNs = nowNs.coerceAtLeast(0L)
                record.state = RawPreviewFrameLifecycleState.PRESENTED
                true
            }
            // EGL presentation telemetry can arrive after a frame has already been proven safe to
            // recycle. Record the late present timestamp without resurrecting released ownership.
            RawPreviewFrameLifecycleState.RELEASED -> {
                record.presentedNs = nowNs.coerceAtLeast(0L)
                true
            }
            else -> {
                record.transitionErrors++
                false
            }
        }
    }

    @Synchronized
    fun inputReleased(generation: Int, timestampNs: Long, nowNs: Long): Boolean {
        val record = records[Key(generation, timestampNs)] ?: return false
        if (record.inputReleasedNs > 0L) return false
        record.inputReleasedNs = nowNs.coerceAtLeast(0L)
        return true
    }

    @Synchronized
    fun released(generation: Int, timestampNs: Long, nowNs: Long, reason: String): Boolean {
        val record = records[Key(generation, timestampNs)] ?: return false
        if (record.state == RawPreviewFrameLifecycleState.RELEASED) return false
        record.state = RawPreviewFrameLifecycleState.RELEASED
        record.releasedNs = nowNs.coerceAtLeast(0L)
        record.releaseReason = reason.ifBlank { "unspecified" }
        trimHistory()
        return true
    }

    @Synchronized
    fun snapshot(generation: Int, timestampNs: Long): RawPreviewFrameLifecycleSnapshot? =
        records[Key(generation, timestampNs)]?.toSnapshot()

    @Synchronized
    fun latest(): RawPreviewFrameLifecycleSnapshot? = records.values.lastOrNull()?.toSnapshot()

    @Synchronized
    fun activeCount(): Int = records.values.count { it.state != RawPreviewFrameLifecycleState.RELEASED }

    @Synchronized
    fun releaseGeneration(generation: Int, nowNs: Long, reason: String): Int {
        var released = 0
        records.values.forEach { record ->
            if (record.generation == generation && record.state != RawPreviewFrameLifecycleState.RELEASED) {
                record.state = RawPreviewFrameLifecycleState.RELEASED
                record.releasedNs = nowNs.coerceAtLeast(0L)
                record.releaseReason = reason.ifBlank { "generation_released" }
                released++
            }
        }
        trimHistory()
        return released
    }

    private fun transition(
        generation: Int,
        timestampNs: Long,
        nowNs: Long,
        next: RawPreviewFrameLifecycleState,
        allowed: (MutableRecord) -> Boolean
    ): Boolean {
        val record = records[Key(generation, timestampNs)] ?: return false
        if (!allowed(record)) {
            record.transitionErrors++
            return false
        }
        record.state = next
        when (next) {
            RawPreviewFrameLifecycleState.GPU_IMPORTED -> record.gpuImportedNs = nowNs.coerceAtLeast(0L)
            RawPreviewFrameLifecycleState.SUBMITTED -> record.submittedNs = nowNs.coerceAtLeast(0L)
            RawPreviewFrameLifecycleState.PRESENTED -> record.presentedNs = nowNs.coerceAtLeast(0L)
            RawPreviewFrameLifecycleState.RELEASED -> record.releasedNs = nowNs.coerceAtLeast(0L)
            RawPreviewFrameLifecycleState.ACQUIRED -> Unit
        }
        return true
    }

    private fun MutableRecord.toSnapshot() = RawPreviewFrameLifecycleSnapshot(
        source = source,
        pipelineGeneration = generation,
        sensorTimestampNs = timestampNs,
        state = state,
        acquiredElapsedNs = acquiredNs,
        gpuImportedElapsedNs = gpuImportedNs,
        submittedElapsedNs = submittedNs,
        presentedElapsedNs = presentedNs,
        inputReleasedElapsedNs = inputReleasedNs,
        releasedElapsedNs = releasedNs,
        releaseReason = releaseReason,
        transitionErrorCount = transitionErrors
    )

    private fun trimHistory() {
        val limit = maxHistory.coerceAtLeast(8)
        if (records.size <= limit) return
        val iterator = records.entries.iterator()
        while (records.size > limit && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.state == RawPreviewFrameLifecycleState.RELEASED) iterator.remove()
        }
    }
}

/** Process-local bridge so renderer and GL owner report into one generation/timestamp ledger. */
internal object RawPreviewFrameLifecycleRegistry {
    private val ledger = RawPreviewFrameLifecycleLedger()

    fun acquired(source: String, generation: Int, timestampNs: Long, nowNs: Long) =
        ledger.acquired(source, generation, timestampNs, nowNs)
    fun gpuImported(generation: Int, timestampNs: Long, nowNs: Long) =
        ledger.gpuImported(generation, timestampNs, nowNs)
    fun submitted(generation: Int, timestampNs: Long, nowNs: Long) =
        ledger.submitted(generation, timestampNs, nowNs)
    fun presented(generation: Int, timestampNs: Long, nowNs: Long) =
        ledger.presented(generation, timestampNs, nowNs)
    fun inputReleased(generation: Int, timestampNs: Long, nowNs: Long) =
        ledger.inputReleased(generation, timestampNs, nowNs)
    fun released(generation: Int, timestampNs: Long, nowNs: Long, reason: String) =
        ledger.released(generation, timestampNs, nowNs, reason)
    fun releaseGeneration(generation: Int, nowNs: Long, reason: String) =
        ledger.releaseGeneration(generation, nowNs, reason)
    fun snapshot(generation: Int, timestampNs: Long) = ledger.snapshot(generation, timestampNs)
    fun latest() = ledger.latest()
    fun activeCount() = ledger.activeCount()
}
