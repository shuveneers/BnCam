package com.bncam.core.runtime

/**
 * Stage names intentionally mirror the end-to-end RAW viewfinder health pipeline without importing
 * UI-layer health classes into core runtime policy.
 */
enum class RawPreviewDownstreamFailureStage {
    CAMERA_CAPTURE_RESULT,
    RAW_IMAGE_READER,
    RENDERER_OFFER,
    RENDERER_PUBLICATION,
    GL_ACCEPT,
    GL_DRAW,
    EGL_PRESENTATION,
    RGB_OUTPUT
}

enum class RawPreviewDownstreamRecovery {
    NONE,
    PRIME_CANONICAL_PRIMARY,
    FORCE_RENDERER_CPU_FALLBACK,
    RECOVER_GL,
    RECOVER_EGL,
    DEFER_TO_TRANSPORT_OWNER,
    DIAGNOSTIC_ONLY
}

data class RawPreviewDownstreamFailureInput(
    val stage: RawPreviewDownstreamFailureStage,
    val stageFrameProducerKind: RawPreviewProducerKind?,
    val exactDownstreamChainCoherent: Boolean,
    val canonicalPrimaryFresh: Boolean,
    val rawPreviewSupportConfigured: Boolean,
    val rawPreviewSupportRepeating: Boolean,
    val rawPreviewSupportDisabled: Boolean,
    val rawPreviewSupportPresentationAuthorityActive: Boolean,
    val rawPreviewSupportInputFresh: Boolean
)

data class RawPreviewDownstreamFailureDecision(
    val revokeRawPreviewSupportDisplayAuthority: Boolean,
    val isolateRawPreviewSupportRequestTarget: Boolean,
    val recovery: RawPreviewDownstreamRecovery,
    val reason: String
)

/**
 * Producer-aware failure containment for the RAW viewfinder after ImageReader transport.
 *
 * Key rule: a frame being supplied by RAW_PREVIEW_SUPPORT does not prove the producer caused a
 * later renderer/GL/EGL failure. Shared downstream stages therefore never remove the support
 * Camera2 output merely because the latest exact frame came from support. They may revoke only its
 * display takeover authority so PRIMARY_BUFFER can feed fallback frames while local recovery runs.
 *
 * The one stage where support can be isolated at request level here is RENDERER_OFFER: an exact
 * support ImageReader frame is present, canonical PRIMARY_BUFFER is independently healthy, support
 * had already earned display authority, but the frame did not even cross the producer-to-renderer
 * hand-off. That containment is optional-stream failover, not CameraCaptureSession recovery.
 */
object RawPreviewDownstreamFailurePolicy {
    fun resolve(input: RawPreviewDownstreamFailureInput): RawPreviewDownstreamFailureDecision {
        val supportFrame = input.stageFrameProducerKind == RawPreviewProducerKind.CUSTOM_IMAGE_READER
        val supportActive = input.rawPreviewSupportConfigured &&
            input.rawPreviewSupportRepeating &&
            !input.rawPreviewSupportDisabled
        val exactSupportAttribution = supportFrame && input.exactDownstreamChainCoherent
        val canonicalFallbackAvailable = input.canonicalPrimaryFresh
        val mayRevokeSupportAuthority = exactSupportAttribution &&
            supportActive &&
            canonicalFallbackAvailable &&
            input.rawPreviewSupportPresentationAuthorityActive

        return when (input.stage) {
            RawPreviewDownstreamFailureStage.CAMERA_CAPTURE_RESULT,
            RawPreviewDownstreamFailureStage.RAW_IMAGE_READER ->
                RawPreviewDownstreamFailureDecision(
                    revokeRawPreviewSupportDisplayAuthority = false,
                    isolateRawPreviewSupportRequestTarget = false,
                    recovery = RawPreviewDownstreamRecovery.DEFER_TO_TRANSPORT_OWNER,
                    reason = "transport_stage_owned_elsewhere"
                )

            RawPreviewDownstreamFailureStage.RENDERER_OFFER -> {
                val isolateSupport = mayRevokeSupportAuthority && input.rawPreviewSupportInputFresh
                RawPreviewDownstreamFailureDecision(
                    revokeRawPreviewSupportDisplayAuthority = mayRevokeSupportAuthority,
                    isolateRawPreviewSupportRequestTarget = isolateSupport,
                    recovery = RawPreviewDownstreamRecovery.PRIME_CANONICAL_PRIMARY,
                    reason = when {
                        isolateSupport -> "support_frame_failed_before_renderer_handoff"
                        exactSupportAttribution && canonicalFallbackAvailable ->
                            "support_frame_handoff_unproven_prime_canonical"
                        else -> "renderer_offer_stall_prime_canonical"
                    }
                )
            }

            RawPreviewDownstreamFailureStage.RENDERER_PUBLICATION ->
                RawPreviewDownstreamFailureDecision(
                    revokeRawPreviewSupportDisplayAuthority = mayRevokeSupportAuthority,
                    isolateRawPreviewSupportRequestTarget = false,
                    recovery = RawPreviewDownstreamRecovery.FORCE_RENDERER_CPU_FALLBACK,
                    reason = if (mayRevokeSupportAuthority) {
                        "support_frame_entered_shared_renderer_revoke_authority_then_recover_renderer"
                    } else {
                        "shared_renderer_publication_stall"
                    }
                )

            RawPreviewDownstreamFailureStage.GL_ACCEPT,
            RawPreviewDownstreamFailureStage.GL_DRAW ->
                RawPreviewDownstreamFailureDecision(
                    revokeRawPreviewSupportDisplayAuthority = mayRevokeSupportAuthority,
                    isolateRawPreviewSupportRequestTarget = false,
                    recovery = RawPreviewDownstreamRecovery.RECOVER_GL,
                    reason = if (mayRevokeSupportAuthority) {
                        "support_frame_reached_shared_gl_revoke_authority_then_recover_gl"
                    } else {
                        "shared_gl_stall"
                    }
                )

            RawPreviewDownstreamFailureStage.EGL_PRESENTATION ->
                RawPreviewDownstreamFailureDecision(
                    revokeRawPreviewSupportDisplayAuthority = mayRevokeSupportAuthority,
                    isolateRawPreviewSupportRequestTarget = false,
                    recovery = RawPreviewDownstreamRecovery.RECOVER_EGL,
                    reason = if (mayRevokeSupportAuthority) {
                        "support_frame_reached_shared_egl_revoke_authority_then_recover_egl"
                    } else {
                        "shared_egl_presentation_stall"
                    }
                )

            RawPreviewDownstreamFailureStage.RGB_OUTPUT -> {
                val failBackToCanonical = mayRevokeSupportAuthority
                RawPreviewDownstreamFailureDecision(
                    revokeRawPreviewSupportDisplayAuthority = failBackToCanonical,
                    isolateRawPreviewSupportRequestTarget = false,
                    recovery = if (failBackToCanonical) {
                        RawPreviewDownstreamRecovery.PRIME_CANONICAL_PRIMARY
                    } else {
                        RawPreviewDownstreamRecovery.DIAGNOSTIC_ONLY
                    },
                    reason = if (failBackToCanonical) {
                        "signal_backed_black_support_output_revoke_authority_and_probe_canonical"
                    } else {
                        "signal_backed_black_output_diagnostic_only"
                    }
                )
            }
        }
    }
}
