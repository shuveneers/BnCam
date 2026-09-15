package com.bncam.core.runtime

/** Identifies which Camera2-side producer supplied a RAW preview frame to the renderer. */
enum class RawPreviewProducerKind {
    CANONICAL_RING,
    CUSTOM_IMAGE_READER
}

/**
 * Generation-scoped authority gate for the optional custom RAW preview producer.
 *
 * DELTA 0217A ownership rule:
 * receiving, rendering or publishing a custom RAW frame is not display proof. Canonical warm-ring
 * preview remains authoritative until the exact custom generation has been presented by EGL.
 */
class RawPreviewProducerAuthorityTracker {
    private var generation: Int = -1
    private var customPresentationProven: Boolean = false
    private var customPublishedFrames: Long = 0L
    private var customPresentedFrames: Long = 0L

    @Synchronized
    fun reset(currentGeneration: Int) {
        generation = currentGeneration
        customPresentationProven = false
        customPublishedFrames = 0L
        customPresentedFrames = 0L
    }

    /** Observational only. Renderer publication must never suppress the canonical producer. */
    @Synchronized
    fun customRendererPublished(currentGeneration: Int) {
        ensureGeneration(currentGeneration)
        customPublishedFrames++
    }

    /** Grants takeover authority only after EGL presentation of the exact custom generation. */
    @Synchronized
    fun customFramePresented(currentGeneration: Int): Boolean {
        ensureGeneration(currentGeneration)
        customPresentationProven = true
        customPresentedFrames++
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
            "customPublishedFrames=$customPublishedFrames;customPresentedFrames=$customPresentedFrames"
    }

    /** Read-only diagnostic view. This must never reset or grant producer authority. */
    @Synchronized
    fun diagnosticSummary(currentGeneration: Int): String =
        if (generation == currentGeneration) {
            "trackerGeneration=$generation;generationMatch=true;" +
                "customPresentationProven=$customPresentationProven;" +
                "customPublishedFrames=$customPublishedFrames;customPresentedFrames=$customPresentedFrames"
        } else {
            "trackerGeneration=$generation;requestedGeneration=$currentGeneration;generationMatch=false;" +
                "customPresentationProven=false;customPublishedFrames=0;customPresentedFrames=0;" +
                "status=STALE_GENERATION"
        }

    private fun ensureGeneration(currentGeneration: Int) {
        if (generation != currentGeneration) reset(currentGeneration)
    }
}
