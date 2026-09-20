package com.bncam.core.runtime

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic RAW-preview admission is producer-local.
 *
 * PRIMARY_BUFFER and RAW_PREVIEW_SUPPORT are independent Camera2 outputs and may arrive with
 * different transport latency. A newer timestamp from the optional support stream must therefore
 * never reject an older-but-still-new canonical frame that is needed for fallback display.
 */
class RawPreviewProducerTimestampGate {
    private val latestCanonical = AtomicLong(Long.MIN_VALUE)
    private val latestSupport = AtomicLong(Long.MIN_VALUE)

    fun reset() {
        latestCanonical.set(Long.MIN_VALUE)
        latestSupport.set(Long.MIN_VALUE)
    }

    fun accept(producerKind: RawPreviewProducerKind, sensorTimestampNs: Long): Boolean {
        if (sensorTimestampNs <= 0L) return false
        val gate = when (producerKind) {
            RawPreviewProducerKind.CANONICAL_RING -> latestCanonical
            RawPreviewProducerKind.CUSTOM_IMAGE_READER -> latestSupport
        }
        while (true) {
            val previous = gate.get()
            if (sensorTimestampNs <= previous) return false
            if (gate.compareAndSet(previous, sensorTimestampNs)) return true
        }
    }

    internal fun latest(producerKind: RawPreviewProducerKind): Long = when (producerKind) {
        RawPreviewProducerKind.CANONICAL_RING -> latestCanonical.get()
        RawPreviewProducerKind.CUSTOM_IMAGE_READER -> latestSupport.get()
    }
}
