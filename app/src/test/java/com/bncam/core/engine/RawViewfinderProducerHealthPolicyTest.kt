package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawViewfinderProducerHealthPolicyTest {
    private val threshold = 1_000_000_000L

    private fun resolve(
        now: Long = 2_000_000_000L,
        routeStart: Long = 1_000_000_000L,
        canonicalFresh: Int = 2,
        supportConfigured: Boolean = true,
        supportRepeating: Boolean = true,
        supportDisabled: Boolean = false,
        supportPresented: Boolean = false,
        supportLastFrame: Long = 0L,
        rawTargeted: Boolean = true,
        generationMatches: Boolean = true
    ) = RawViewfinderProducerHealthPolicy.resolve(
        RawViewfinderProducerHealthInput(
            rawViewfinderTargeted = rawTargeted,
            generationMatches = generationMatches,
            canonicalFreshCompleteFrames = canonicalFresh,
            rawPreviewSupportConfigured = supportConfigured,
            rawPreviewSupportRepeating = supportRepeating,
            rawPreviewSupportDisabled = supportDisabled,
            rawPreviewSupportPresentationProven = supportPresented,
            rawPreviewSupportLastFrameElapsedNs = supportLastFrame,
            routeStartedElapsedNs = routeStart,
            nowElapsedNs = now,
            stallThresholdNs = threshold
        )
    )

    @Test
    fun `canonical remains display owner until custom support is actually presented`() {
        val decision = resolve(
            now = 1_500_000_000L,
            supportLastFrame = 1_450_000_000L,
            supportPresented = false
        )

        assertEquals(RawViewfinderProducerPath.PRIMARY_BUFFER, decision.activeProducer)
        assertTrue(decision.rawPreviewSupportInputFresh)
        assertFalse(decision.disableRawPreviewSupport)
    }

    @Test
    fun `fresh presented support may become display producer`() {
        val decision = resolve(
            now = 1_500_000_000L,
            supportLastFrame = 1_450_000_000L,
            supportPresented = true
        )

        assertEquals(RawViewfinderProducerPath.RAW_PREVIEW_SUPPORT, decision.activeProducer)
        assertTrue(decision.canonicalPrimaryFresh)
        assertFalse(decision.disableRawPreviewSupport)
    }

    @Test
    fun `support with no first frame is rejected after bounded startup window`() {
        val decision = resolve(now = 2_000_000_001L, supportLastFrame = 0L)

        assertTrue(decision.disableRawPreviewSupport)
        assertEquals("RAW_PREVIEW_SUPPORT_NO_FIRST_FRAME", decision.disableReason)
        assertEquals(RawViewfinderProducerPath.PRIMARY_BUFFER, decision.activeProducer)
    }

    @Test
    fun `support stall after progress falls back to canonical even if support was presented`() {
        val decision = resolve(
            now = 3_000_000_000L,
            routeStart = 1_000_000_000L,
            supportLastFrame = 1_500_000_000L,
            supportPresented = true
        )

        assertTrue(decision.disableRawPreviewSupport)
        assertEquals("RAW_PREVIEW_SUPPORT_INPUT_STALLED", decision.disableReason)
        assertEquals(RawViewfinderProducerPath.PRIMARY_BUFFER, decision.activeProducer)
    }

    @Test
    fun `configured but untargeted support is not judged stalled`() {
        val decision = resolve(
            now = 4_000_000_000L,
            supportRepeating = false,
            supportLastFrame = 0L
        )

        assertFalse(decision.rawPreviewSupportExpected)
        assertFalse(decision.disableRawPreviewSupport)
        assertEquals(RawViewfinderProducerPath.PRIMARY_BUFFER, decision.activeProducer)
    }

    @Test
    fun `custom support can never make canonical capture producer healthy`() {
        val decision = resolve(
            now = 1_500_000_000L,
            canonicalFresh = 0,
            supportLastFrame = 1_450_000_000L,
            supportPresented = true
        )

        assertEquals(RawViewfinderProducerPath.RAW_PREVIEW_SUPPORT, decision.activeProducer)
        assertFalse(decision.canonicalPrimaryFresh)
        assertTrue(decision.rawPreviewSupportInputFresh)
    }

    @Test
    fun `non raw route is inactive and does not disable support`() {
        val decision = resolve(rawTargeted = false, now = 8_000_000_000L)

        assertEquals(RawViewfinderProducerPath.INACTIVE, decision.activeProducer)
        assertFalse(decision.disableRawPreviewSupport)
    }
}
