package com.bncam.core.isp.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBlackAuthorityPolicyTest {
    @Test
    fun `system prefers exact frame dynamic black when available`() {
        val decision = RawBlackAuthorityPolicy.resolveSystemDynamic(
            systemMosaicLevels = listOf(64f, 65f, 66f, 67f),
            systemSource = "CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN",
            dynamicMosaicLevels = listOf(60f, 61f, 62f, 63f),
            dynamicSource = "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL",
            cfaPattern = 0,
            fallbackCanonicalLevels = listOf(1f, 1f, 1f, 1f),
            fallbackSource = "fallback",
            requestedMode = RawBlackAuthorityMode.SYSTEM
        )

        assertEquals(listOf(60f, 61f, 62f, 63f), decision.canonicalLevels)
        assertTrue(decision.systemMetadataAvailable)
        assertTrue(decision.dynamicMetadataAvailable)
        assertTrue(decision.source.startsWith("SYSTEM_EXACT_FRAME_DYNAMIC:"))
    }

    @Test
    fun `dynamic zero strength is exactly system`() {
        val decision = dynamicDecision(0.0f)
        assertEquals(listOf(64f, 64f, 64f, 64f), decision.canonicalLevels)
        assertEquals(0.0f, decision.dynamicStrength, 0.0f)
    }

    @Test
    fun `dynamic half strength interpolates each cfa site`() {
        val decision = RawBlackAuthorityPolicy.resolveSystemDynamic(
            systemMosaicLevels = listOf(64f, 66f, 68f, 70f),
            systemSource = "static",
            dynamicMosaicLevels = listOf(60f, 62f, 72f, 74f),
            dynamicSource = "dynamic",
            cfaPattern = 0,
            fallbackCanonicalLevels = listOf(1f, 1f, 1f, 1f),
            fallbackSource = "fallback",
            requestedMode = RawBlackAuthorityMode.DYNAMIC,
            dynamicStrength = 0.5f
        )

        assertEquals(listOf(62f, 64f, 70f, 72f), decision.canonicalLevels)
        assertTrue(decision.source.contains("strength=0.50"))
    }

    @Test
    fun `dynamic full strength is exact same-frame dynamic`() {
        val decision = dynamicDecision(1.0f)
        assertEquals(listOf(60f, 61f, 62f, 63f), decision.canonicalLevels)
        assertTrue(decision.dynamicMetadataAvailable)
        assertEquals("none", decision.fallbackReason)
    }

    @Test
    fun `missing dynamic metadata returns system without percentage scaling`() {
        val decision = RawBlackAuthorityPolicy.resolveSystemDynamic(
            systemMosaicLevels = listOf(64f, 65f, 66f, 67f),
            systemSource = "static",
            dynamicMosaicLevels = null,
            dynamicSource = "unavailable",
            cfaPattern = 0,
            fallbackCanonicalLevels = listOf(10f, 20f, 30f, 40f),
            fallbackSource = "fallback",
            requestedMode = RawBlackAuthorityMode.DYNAMIC,
            dynamicStrength = 0.25f
        )

        assertEquals(listOf(64f, 65f, 66f, 67f), decision.canonicalLevels)
        assertFalse(decision.dynamicMetadataAvailable)
        assertEquals("dynamic_black_metadata_unavailable_system_fallback", decision.fallbackReason)
    }

    @Test
    fun `manual remains exact authority over both metadata vectors`() {
        val decision = RawBlackAuthorityPolicy.resolveSystemDynamic(
            systemMosaicLevels = listOf(64f, 64f, 64f, 64f),
            systemSource = "static",
            dynamicMosaicLevels = listOf(61f, 62f, 63f, 64f),
            dynamicSource = "dynamic",
            cfaPattern = 0,
            fallbackCanonicalLevels = listOf(100.25f, 101.5f, 102.75f, 103.125f),
            fallbackSource = "Black Level v2 Manual mosaic override",
            requestedMode = RawBlackAuthorityMode.MANUAL,
            dynamicStrength = 0.4f
        )

        assertEquals(listOf(100.25f, 101.5f, 102.75f, 103.125f), decision.canonicalLevels)
        assertTrue(decision.manualOverrideActive)
        assertFalse(decision.metadataAuthoritative)
    }

    @Test
    fun `legacy auto mode parses as system`() {
        assertEquals(RawBlackAuthorityMode.SYSTEM, RawBlackAuthorityPolicy.parseMode("Auto"))
        assertEquals(RawBlackAuthorityMode.SYSTEM, RawBlackAuthorityPolicy.parseMode("System"))
    }

    private fun dynamicDecision(strength: Float): RawBlackAuthorityDecision =
        RawBlackAuthorityPolicy.resolveSystemDynamic(
            systemMosaicLevels = listOf(64f, 64f, 64f, 64f),
            systemSource = "static",
            dynamicMosaicLevels = listOf(60f, 61f, 62f, 63f),
            dynamicSource = "dynamic",
            cfaPattern = 0,
            fallbackCanonicalLevels = listOf(1f, 1f, 1f, 1f),
            fallbackSource = "fallback",
            requestedMode = RawBlackAuthorityMode.DYNAMIC,
            dynamicStrength = strength
        )
    @Test
    fun `compatibility resolve cannot infer manual authority from legacy source text`() {
        val decision = RawBlackAuthorityPolicy.resolve(
            metadataMosaicLevels = listOf(64f, 65f, 66f, 67f),
            metadataSource = "CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN",
            cfaPattern = 0,
            fallbackCanonicalLevels = listOf(100f, 101f, 102f, 103f),
            fallbackSource = "Lens ID Manual black level override"
        )

        assertEquals(RawBlackAuthorityMode.SYSTEM, decision.mode)
        assertEquals(listOf(64f, 65f, 66f, 67f), decision.canonicalLevels)
        assertFalse(decision.manualOverrideActive)
    }

    @Test
    fun `compatibility resolve cannot infer dynamic authority from legacy source text`() {
        val decision = RawBlackAuthorityPolicy.resolve(
            metadataMosaicLevels = listOf(64f, 65f, 66f, 67f),
            metadataSource = "CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN",
            cfaPattern = 0,
            fallbackCanonicalLevels = listOf(10f, 10f, 10f, 10f),
            fallbackSource = "Lens ID Dynamic black level override 25%"
        )

        assertEquals(RawBlackAuthorityMode.SYSTEM, decision.mode)
        assertEquals(listOf(64f, 65f, 66f, 67f), decision.canonicalLevels)
    }

}
