package com.bncam.core.runtime

/** Identifies which Camera2-side producer supplied a RAW preview frame to the renderer. */
enum class RawPreviewProducerKind {
    CANONICAL_RING,
    CUSTOM_IMAGE_READER
}

/**
 * Generation-scoped authority gate for the optional custom RAW preview producer.
 *
 * Receiving, rendering or publishing a custom RAW frame is not display proof. Canonical warm-ring
 * preview remains authoritative until the exact custom generation has been presented by EGL.
 *
 * Downstream recovery may revoke presentation authority without removing the configured Camera2
 * output. This is deliberately weaker than disabling RAW_PREVIEW_SUPPORT: it immediately allows
 * PRIMARY_BUFFER to feed the renderer again while the optional support stream remains available to
 * prove itself with a later successful EGL presentation.
 */
class RawPreviewProducerAuthorityTracker {
    private var generation: Int = -1
    private var customPresentationProven: Boolean = false
    private var customPublishedFrames: Long = 0L
    private var customPresentedFrames: Long = 0L
    private var customAuthorityRevocations: Long = 0L
    private var customPresentationRevokedThroughSensorTimestampNs: Long = Long.MIN_VALUE

    @Synchronized
    fun reset(currentGeneration: Int) {
        generation = currentGeneration
        customPresentationProven = false
        customPublishedFrames = 0L
        customPresentedFrames = 0L
        customAuthorityRevocations = 0L
        customPresentationRevokedThroughSensorTimestampNs = Long.MIN_VALUE
    }

    /** Observational only. Renderer publication must never suppress the canonical producer. */
    @Synchronized
    fun customRendererPublished(currentGeneration: Int) {
        ensureGeneration(currentGeneration)
        customPublishedFrames++
    }

    /**
     * Grants takeover authority only after EGL presentation of a support frame newer than any
     * frame explicitly revoked by downstream containment. This prevents a late EGL callback for
     * the exact failed frame from immediately undoing canonical fallback.
     */
    @Synchronized
    fun customFramePresented(currentGeneration: Int, sensorTimestampNs: Long): Boolean {
        ensureGeneration(currentGeneration)
        customPresentedFrames++
        if (sensorTimestampNs <= 0L || sensorTimestampNs <= customPresentationRevokedThroughSensorTimestampNs) {
            return false
        }
        customPresentationProven = true
        return true
    }

    /** Compatibility helper for tests/older callers without exact timestamp evidence. */
    @Synchronized
    fun customFramePresented(currentGeneration: Int): Boolean =
        customFramePresented(currentGeneration, Long.MAX_VALUE)

    /**
     * Revokes only display takeover authority for the current generation.
     *
     * The Camera2 support output remains configured/targeted. A later custom frame may earn
     * authority again by reaching actual EGL presentation. Returns true only when authority was
     * active and has actually been revoked.
     */
    @Synchronized
    fun revokeCustomPresentation(
        currentGeneration: Int,
        throughSensorTimestampNs: Long = Long.MIN_VALUE
    ): Boolean {
        ensureGeneration(currentGeneration)
        if (throughSensorTimestampNs > 0L) {
            customPresentationRevokedThroughSensorTimestampNs = maxOf(
                customPresentationRevokedThroughSensorTimestampNs,
                throughSensorTimestampNs
            )
        }
        if (!customPresentationProven) return false
        customPresentationProven = false
        customAuthorityRevocations++
        return true
    }

    @Synchronized
    fun maySuppressCanonical(currentGeneration: Int, customInputFresh: Boolean): Boolean {
        ensureGeneration(currentGeneration)
        return customPresentationProven && customInputFresh
    }

    @Synchronized
    fun summary(currentGeneration: Int): String {
        ensureGeneration(currentGeneration)
        return "customPresentationProven=$customPresentationProven;" +
            "customPublishedFrames=$customPublishedFrames;customPresentedFrames=$customPresentedFrames;" +
            "customAuthorityRevocations=$customAuthorityRevocations;" +
            "customPresentationRevokedThroughSensorTimestampNs=$customPresentationRevokedThroughSensorTimestampNs"
    }

    /** Read-only diagnostic view. This must never reset or grant producer authority. */
    @Synchronized
    fun diagnosticSummary(currentGeneration: Int): String =
        if (generation == currentGeneration) {
            "trackerGeneration=$generation;generationMatch=true;" +
                "customPresentationProven=$customPresentationProven;" +
                "customPublishedFrames=$customPublishedFrames;customPresentedFrames=$customPresentedFrames;" +
                "customAuthorityRevocations=$customAuthorityRevocations;" +
                "customPresentationRevokedThroughSensorTimestampNs=$customPresentationRevokedThroughSensorTimestampNs"
        } else {
            "trackerGeneration=$generation;requestedGeneration=$currentGeneration;generationMatch=false;" +
                "customPresentationProven=false;customPublishedFrames=0;customPresentedFrames=0;" +
                "customAuthorityRevocations=0;customPresentationRevokedThroughSensorTimestampNs=${Long.MIN_VALUE};" +
                "status=STALE_GENERATION"
        }

    private fun ensureGeneration(currentGeneration: Int) {
        if (generation != currentGeneration) reset(currentGeneration)
    }
}
