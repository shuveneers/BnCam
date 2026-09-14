package com.bncam.core.runtime

/** Identifies which Camera2-side producer supplied a RAW preview frame to the renderer. */
enum class RawPreviewProducerKind {
    CANONICAL_RING,
    CUSTOM_IMAGE_READER
}

/**
 * Generation-scoped authority gate for the optional custom RAW preview producer.
 *
 * A custom ImageReader receiving a HardwareBuffer is not proof that the buffer can be imported,
 * rendered and published by the RAW preview renderer. Canonical warm-ring preview must therefore
 * remain authoritative until one custom frame has actually completed renderer publication.
 */
class RawPreviewProducerAuthorityTracker {
    private var generation: Int = -1
    private var customPublicationProven: Boolean = false
    private var customPublishedFrames: Long = 0L

    @Synchronized
    fun reset(currentGeneration: Int) {
        generation = currentGeneration
        customPublicationProven = false
        customPublishedFrames = 0L
    }

    @Synchronized
    fun customRendererPublished(currentGeneration: Int): Boolean {
        ensureGeneration(currentGeneration)
        customPublicationProven = true
        customPublishedFrames++
        return true
    }

    @Synchronized
    fun maySuppressCanonical(currentGeneration: Int, customInputFresh: Boolean): Boolean {
        ensureGeneration(currentGeneration)
        return customPublicationProven && customInputFresh
    }

    @Synchronized
    fun summary(currentGeneration: Int): String {
        ensureGeneration(currentGeneration)
        return "customPublicationProven=$customPublicationProven;customPublishedFrames=$customPublishedFrames"
    }

    private fun ensureGeneration(currentGeneration: Int) {
        if (generation != currentGeneration) reset(currentGeneration)
    }
}
