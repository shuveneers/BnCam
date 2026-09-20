package com.bncam.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewDownstreamFailurePolicyTest {
    private fun input(
        stage: RawPreviewDownstreamFailureStage,
        producer: RawPreviewProducerKind? = RawPreviewProducerKind.CUSTOM_IMAGE_READER,
        coherent: Boolean = true,
        canonicalFresh: Boolean = true,
        supportConfigured: Boolean = true,
        supportRepeating: Boolean = true,
        supportDisabled: Boolean = false,
        supportAuthority: Boolean = true,
        supportInputFresh: Boolean = true
    ) = RawPreviewDownstreamFailureInput(
        stage = stage,
        stageFrameProducerKind = producer,
        exactDownstreamChainCoherent = coherent,
        canonicalPrimaryFresh = canonicalFresh,
        rawPreviewSupportConfigured = supportConfigured,
        rawPreviewSupportRepeating = supportRepeating,
        rawPreviewSupportDisabled = supportDisabled,
        rawPreviewSupportPresentationAuthorityActive = supportAuthority,
        rawPreviewSupportInputFresh = supportInputFresh
    )

    @Test
    fun `support renderer handoff failure isolates only optional support`() {
        val decision = RawPreviewDownstreamFailurePolicy.resolve(
            input(RawPreviewDownstreamFailureStage.RENDERER_OFFER)
        )
        assertTrue(decision.revokeRawPreviewSupportDisplayAuthority)
        assertTrue(decision.isolateRawPreviewSupportRequestTarget)
        assertEquals(RawPreviewDownstreamRecovery.PRIME_CANONICAL_PRIMARY, decision.recovery)
    }

    @Test
    fun `renderer publication failure revokes support authority but keeps support output configured`() {
        val decision = RawPreviewDownstreamFailurePolicy.resolve(
            input(RawPreviewDownstreamFailureStage.RENDERER_PUBLICATION)
        )
        assertTrue(decision.revokeRawPreviewSupportDisplayAuthority)
        assertFalse(decision.isolateRawPreviewSupportRequestTarget)
        assertEquals(RawPreviewDownstreamRecovery.FORCE_RENDERER_CPU_FALLBACK, decision.recovery)
    }

    @Test
    fun `support frame gl failure is treated as shared display failure`() {
        val decision = RawPreviewDownstreamFailurePolicy.resolve(
            input(RawPreviewDownstreamFailureStage.GL_DRAW)
        )
        assertTrue(decision.revokeRawPreviewSupportDisplayAuthority)
        assertFalse(decision.isolateRawPreviewSupportRequestTarget)
        assertEquals(RawPreviewDownstreamRecovery.RECOVER_GL, decision.recovery)
    }

    @Test
    fun `support frame egl failure never tears down support request target`() {
        val decision = RawPreviewDownstreamFailurePolicy.resolve(
            input(RawPreviewDownstreamFailureStage.EGL_PRESENTATION)
        )
        assertTrue(decision.revokeRawPreviewSupportDisplayAuthority)
        assertFalse(decision.isolateRawPreviewSupportRequestTarget)
        assertEquals(RawPreviewDownstreamRecovery.RECOVER_EGL, decision.recovery)
    }

    @Test
    fun `signal backed black support frame fails back to canonical without disabling support`() {
        val decision = RawPreviewDownstreamFailurePolicy.resolve(
            input(RawPreviewDownstreamFailureStage.RGB_OUTPUT)
        )
        assertTrue(decision.revokeRawPreviewSupportDisplayAuthority)
        assertFalse(decision.isolateRawPreviewSupportRequestTarget)
        assertEquals(RawPreviewDownstreamRecovery.PRIME_CANONICAL_PRIMARY, decision.recovery)
    }

    @Test
    fun `canonical downstream failure never mutates optional support authority`() {
        val decision = RawPreviewDownstreamFailurePolicy.resolve(
            input(
                stage = RawPreviewDownstreamFailureStage.GL_ACCEPT,
                producer = RawPreviewProducerKind.CANONICAL_RING
            )
        )
        assertFalse(decision.revokeRawPreviewSupportDisplayAuthority)
        assertFalse(decision.isolateRawPreviewSupportRequestTarget)
        assertEquals(RawPreviewDownstreamRecovery.RECOVER_GL, decision.recovery)
    }

    @Test
    fun `incoherent exact chain forbids producer specific containment`() {
        val decision = RawPreviewDownstreamFailurePolicy.resolve(
            input(
                stage = RawPreviewDownstreamFailureStage.RENDERER_OFFER,
                coherent = false
            )
        )
        assertFalse(decision.revokeRawPreviewSupportDisplayAuthority)
        assertFalse(decision.isolateRawPreviewSupportRequestTarget)
        assertEquals(RawPreviewDownstreamRecovery.PRIME_CANONICAL_PRIMARY, decision.recovery)
    }

    @Test
    fun `stale canonical primary forbids support takeover revocation`() {
        val decision = RawPreviewDownstreamFailurePolicy.resolve(
            input(
                stage = RawPreviewDownstreamFailureStage.EGL_PRESENTATION,
                canonicalFresh = false
            )
        )
        assertFalse(decision.revokeRawPreviewSupportDisplayAuthority)
        assertFalse(decision.isolateRawPreviewSupportRequestTarget)
        assertEquals(RawPreviewDownstreamRecovery.RECOVER_EGL, decision.recovery)
    }

    @Test
    fun `transport stages stay with transport owners`() {
        for (stage in listOf(
            RawPreviewDownstreamFailureStage.CAMERA_CAPTURE_RESULT,
            RawPreviewDownstreamFailureStage.RAW_IMAGE_READER
        )) {
            val decision = RawPreviewDownstreamFailurePolicy.resolve(input(stage))
            assertFalse(decision.revokeRawPreviewSupportDisplayAuthority)
            assertFalse(decision.isolateRawPreviewSupportRequestTarget)
            assertEquals(RawPreviewDownstreamRecovery.DEFER_TO_TRANSPORT_OWNER, decision.recovery)
        }
    }
}
