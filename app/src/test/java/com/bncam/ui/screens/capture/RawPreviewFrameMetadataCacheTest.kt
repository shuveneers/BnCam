package com.bncam.ui.screens.capture

import org.junit.Test
import kotlin.test.*

class RawPreviewFrameMetadataCacheTest {
    private fun key(timestamp: Long, generation: Int = 7, sensor: String = "physical0",
                    source: ViewfinderEffectiveSource = ViewfinderEffectiveSource.RAW_SENSOR) =
        RawPreviewFrameMetadataCache.Key(generation, sensor, source, timestamp)

    @Test fun resultFirstAndImageFirstUseOnlyExactTimestamp() {
        val cache = RawPreviewFrameMetadataCache()
        val black = floatArrayOf(4f, 5f, 6f, 7f)
        cache.record(key(101), RawPreviewFrameMetadataCache.Entry(black, 1023), 1L)
        black[0] = 999f
        assertEquals(4f, cache.match(key(101), 2L).exact!!.black!![0])
        assertNull(cache.match(key(102), 2L).exact)
        assertFalse(cache.hasExact(key(103), 2L)) // Image arrives first.
        cache.record(key(103), RawPreviewFrameMetadataCache.Entry(null, 1000), 3L)
        assertEquals(1000, cache.match(key(103), 4L).exact!!.white)
    }

    @Test fun trustedBlackNeverCrossesSensorGenerationDomainOrFutureTimestamp() {
        val cache = RawPreviewFrameMetadataCache()
        cache.record(key(105), RawPreviewFrameMetadataCache.Entry(floatArrayOf(8f, 8f, 8f, 8f), null), 1L)
        assertNull(cache.match(key(104), 2L).trustedBlack)
        assertEquals(8f, cache.match(key(106), 2L).trustedBlack!![0])
        assertNull(cache.match(key(106, sensor = "physical1"), 2L).trustedBlack)
        assertNull(cache.match(key(106, generation = 8), 2L).trustedBlack)
        assertNull(cache.match(key(106, source = ViewfinderEffectiveSource.RAW10), 2L).trustedBlack)
    }

    @Test fun lensMapIsExactImmutableBoundedAndExpires() {
        val cache = RawPreviewFrameMetadataCache(capacity = 2, maxAgeNs = 10L)
        val map = FloatArray(16) { 1.25f }
        cache.record(key(1), RawPreviewFrameMetadataCache.Entry(null, null, map, 2, 2), 1L)
        map[0] = 9f
        assertEquals(1.25f, cache.match(key(1), 2L).exact!!.lensMap!![0])
        assertNull(cache.match(key(2), 2L).exact) // Never borrow an older map.
        cache.record(key(2), RawPreviewFrameMetadataCache.Entry(null, null), 3L)
        cache.record(key(3), RawPreviewFrameMetadataCache.Entry(null, null), 4L)
        assertEquals(2, cache.size())
        assertNull(cache.match(key(1), 4L).exact)
        assertNull(cache.match(key(3), 20L).exact)
        assertEquals(0, cache.size())
    }
}
