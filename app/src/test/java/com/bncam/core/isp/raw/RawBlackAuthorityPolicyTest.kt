package com.bncam.core.isp.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBlackAuthorityPolicyTest {
    @Test
    fun `valid camera2 metadata overrides conflicting developed fallback`() {
        val decision = RawBlackAuthorityPolicy.resolve(
            metadataMosaicLevels = listOf(64f, 65f, 66f, 67f),
            metadataSource = "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL",
            cfaPattern = 0,
            fallbackCanonicalLevels = listOf(100f, 200f, 300f, 400f),
            fallbackSource = "Lens override"
        )

        assertTrue(decision.metadataAuthoritative)
        assertEquals(listOf(64f, 65f, 66f, 67f), decision.canonicalLevels)
        assertTrue(decision.source.startsWith("METADATA_FIRST:"))
        assertEquals("none", decision.fallbackReason)
    }

    @Test
    fun `metadata is canonicalized through the shared CFA mapper`() {
        val decision = RawBlackAuthorityPolicy.resolve(
            metadataMosaicLevels = listOf(4f, 3f, 2f, 1f),
            metadataSource = "CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN",
            cfaPattern = 3, // BGGR
            fallbackCanonicalLevels = listOf(9f, 9f, 9f, 9f),
            fallbackSource = "fallback"
        )

        assertTrue(decision.metadataAuthoritative)
        assertEquals(listOf(1f, 2f, 3f, 4f), decision.canonicalLevels)
    }

    @Test
    fun `missing metadata uses controlled fallback`() {
        val decision = RawBlackAuthorityPolicy.resolve(
            metadataMosaicLevels = null,
            metadataSource = "unavailable",
            cfaPattern = 0,
            fallbackCanonicalLevels = listOf(11f, 12f, 13f, 14f),
            fallbackSource = "calibrated fallback"
        )

        assertFalse(decision.metadataAuthoritative)
        assertEquals(listOf(11f, 12f, 13f, 14f), decision.canonicalLevels)
        assertEquals("camera2_black_metadata_unavailable", decision.fallbackReason)
        assertTrue(decision.source.startsWith("CONTROLLED_FALLBACK:"))
    }

    @Test
    fun `invalid metadata cannot become black authority`() {
        val decision = RawBlackAuthorityPolicy.resolve(
            metadataMosaicLevels = listOf(Float.NaN, 64f, 64f, 64f),
            metadataSource = "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL",
            cfaPattern = 0,
            fallbackCanonicalLevels = listOf(21f, 22f, 23f, 24f),
            fallbackSource = "calibrated fallback"
        )

        assertFalse(decision.metadataAuthoritative)
        assertEquals(listOf(21f, 22f, 23f, 24f), decision.canonicalLevels)
        assertEquals("camera2_black_metadata_invalid", decision.fallbackReason)
    }
}
