package com.bncam.ui.screens.capture

import org.junit.Assert.*
import org.junit.Test

class RawPreviewPendingFramesTest {
    @Test fun imageAheadOfMetadataDoesNotStarveOlderMatchingFrame() {
        val queue = RawPreviewPendingFrames<Long> { it }
        queue.offer(1L, 4)
        queue.offer(2L, 4)
        assertNull(queue.poll({ false }, { false }).ready)
        val result = queue.poll({ it == 1L }, { false })
        assertEquals(1L, result.ready)
        assertTrue(result.discarded.isEmpty())
        assertEquals(2L, queue.poll({ true }, { false }).ready)
    }

    @Test fun newestCompletePairWinsWithoutLosingNewerUnpairedFrame() {
        val queue = RawPreviewPendingFrames<Long> { it }
        (1L..4L).forEach { queue.offer(it, 4) }
        val result = queue.poll({ it <= 3L }, { false })
        assertEquals(3L, result.ready)
        assertEquals(listOf(1L, 2L), result.discarded)
        assertEquals(listOf(4L), queue.clear())
    }

    @Test fun missingMetadataExpiresWithoutRenderingFallbackAndCapacityIsBounded() {
        val queue = RawPreviewPendingFrames<Long> { it }
        (1L..4L).forEach { queue.offer(it, 4) }
        assertEquals(listOf(1L), queue.offer(5L, 4))
        val result = queue.poll({ false }, { it < 5L })
        assertNull(result.ready)
        assertEquals(listOf(2L, 3L, 4L), result.discarded)
        assertEquals(listOf(5L), queue.clear())
        assertEquals(0, queue.size)
    }

    @Test fun legacyLatestOnlyAndRequeueOrderingRemainIntact() {
        val queue = RawPreviewPendingFrames<Long> { it }
        queue.offer(2L, 1)
        assertEquals(listOf(1L), queue.offer(1L, 1))
        assertEquals(2L, queue.poll({ true }, { false }).ready)
        assertEquals(0, queue.size)
    }
}
