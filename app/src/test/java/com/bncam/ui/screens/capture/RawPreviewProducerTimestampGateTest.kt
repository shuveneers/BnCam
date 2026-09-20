package com.bncam.ui.screens.capture

import com.bncam.core.runtime.RawPreviewProducerKind
import com.bncam.core.runtime.RawPreviewProducerTimestampGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewProducerTimestampGateTest {
    @Test
    fun `newer support timestamp cannot poison canonical fallback admission`() {
        val gate = RawPreviewProducerTimestampGate()
        assertTrue(gate.accept(RawPreviewProducerKind.CUSTOM_IMAGE_READER, 200L))
        assertTrue(gate.accept(RawPreviewProducerKind.CANONICAL_RING, 150L))
        assertFalse(gate.accept(RawPreviewProducerKind.CANONICAL_RING, 150L))
        assertTrue(gate.accept(RawPreviewProducerKind.CANONICAL_RING, 175L))
    }

    @Test
    fun `duplicate rejection remains monotonic within each producer`() {
        val gate = RawPreviewProducerTimestampGate()
        assertTrue(gate.accept(RawPreviewProducerKind.CANONICAL_RING, 100L))
        assertFalse(gate.accept(RawPreviewProducerKind.CANONICAL_RING, 99L))
        assertFalse(gate.accept(RawPreviewProducerKind.CANONICAL_RING, 100L))
        assertTrue(gate.accept(RawPreviewProducerKind.CUSTOM_IMAGE_READER, 99L))
    }

    @Test
    fun `reset clears both producer histories`() {
        val gate = RawPreviewProducerTimestampGate()
        assertTrue(gate.accept(RawPreviewProducerKind.CANONICAL_RING, 500L))
        assertTrue(gate.accept(RawPreviewProducerKind.CUSTOM_IMAGE_READER, 600L))
        gate.reset()
        assertTrue(gate.accept(RawPreviewProducerKind.CANONICAL_RING, 1L))
        assertTrue(gate.accept(RawPreviewProducerKind.CUSTOM_IMAGE_READER, 1L))
    }
}
