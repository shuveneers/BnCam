package com.bncam.core.buffer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRingBufferEventTest {
    @Test
    fun generationChangePublishesMonotonicLifecycleEvents() = runBlocking {
        val ring = FrameRingBuffer(capacity = 4)
        val before = ring.currentEventSequence()

        ring.activateGeneration(7)

        val wakeEvent = ring.awaitEventAfter(before)
        val events = ring.eventsAfter(before)
        assertEquals(FrameRingEventType.GENERATION_CHANGED, wakeEvent.type)
        assertEquals(7, wakeEvent.pipelineGeneration)
        assertTrue(events.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        assertEquals(
            listOf(
                FrameRingEventType.BUFFER_CLEARED,
                FrameRingEventType.GENERATION_CHANGED
            ),
            events.map { it.type }
        )
    }

    @Test
    fun clearPublishesCaptureAbortSignalWithoutChangingGeneration() {
        val ring = FrameRingBuffer(capacity = 4)
        ring.activateGeneration(3)
        val before = ring.currentEventSequence()

        ring.clear()

        val event = ring.eventsAfter(before).single()
        assertEquals(FrameRingEventType.BUFFER_CLEARED, event.type)
        assertEquals(3, event.pipelineGeneration)
        assertEquals(3, ring.currentGeneration())
    }
}
