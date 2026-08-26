package com.bncam.core.capture

import java.util.concurrent.atomic.AtomicBoolean

/** Idempotent ownership guard used at ImageReader boundaries. */
class CloseOnce(private val closeAction: () -> Unit) {
    private val closed = AtomicBoolean(false)

    fun close(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        closeAction()
        return true
    }

    val isClosed: Boolean get() = closed.get()
}
