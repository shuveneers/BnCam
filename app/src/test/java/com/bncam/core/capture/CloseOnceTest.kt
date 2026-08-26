package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseOnceTest {
    @Test
    fun imageEquivalentIsClosedExactlyOnceOnSuccess() {
        var closeCount = 0
        val owner = CloseOnce { closeCount++ }
        owner.close()
        owner.close()
        assertTrue(owner.isClosed)
        assertEquals(1, closeCount)
    }

    @Test
    fun imageEquivalentIsClosedExactlyOnceAfterFailure() {
        var closeCount = 0
        val owner = CloseOnce { closeCount++ }
        try {
            throw IllegalStateException("processing failed")
        } catch (_: IllegalStateException) {
        } finally {
            owner.close()
        }
        assertEquals(1, closeCount)
    }
}
