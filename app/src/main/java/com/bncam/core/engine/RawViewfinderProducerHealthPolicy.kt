package com.bncam.core.engine

/**
 * Which Camera2-side producer currently owns the visible RAW viewfinder input path.
 *
 * PRIMARY_BUFFER is BnCam's canonical near-ZSL/capture producer. RAW_PREVIEW_SUPPORT is optional
 * acceleration/support only and may become display-authoritative only after a frame from that
 * producer has actually been presented.
 */
enum class RawViewfinderProducerPath {
    INACTIVE,
    PRIMARY_BUFFER,
    RAW_PREVIEW_SUPPORT
}

data class RawViewfinderProducerHealthInput(
    val rawViewfinderTargeted: Boolean,
    val generationMatches: Boolean,
    val canonicalFreshCompleteFrames: Int,
    val rawPreviewSupportConfigured: Boolean,
    val rawPreviewSupportRepeating: Boolean,
    val rawPreviewSupportDisabled: Boolean,
    val rawPreviewSupportPresentationProven: Boolean,
    val rawPreviewSupportLastFrameElapsedNs: Long,
    val routeStartedElapsedNs: Long,
    val nowElapsedNs: Long,
    val stallThresholdNs: Long
) {
    init {
        require(canonicalFreshCompleteFrames >= 0) { "canonicalFreshCompleteFrames must be >= 0" }
        require(nowElapsedNs >= 0L) { "nowElapsedNs must be >= 0" }
        require(stallThresholdNs > 0L) { "stallThresholdNs must be > 0" }
    }
}

data class RawViewfinderProducerHealthDecision(
    val activeProducer: RawViewfinderProducerPath,
    val canonicalPrimaryFresh: Boolean,
    val rawPreviewSupportExpected: Boolean,
    val rawPreviewSupportInputFresh: Boolean,
    val rawPreviewSupportStartupGraceElapsed: Boolean,
    val disableRawPreviewSupport: Boolean,
    val disableReason: String?
)

/**
 * Pure policy separating canonical capture-producer health from optional RAW support health.
 *
 * Important invariants:
 * - PRIMARY_BUFFER health is never inferred from RAW_PREVIEW_SUPPORT progress.
 * - RAW_PREVIEW_SUPPORT may never mask a stalled canonical capture producer.
 * - A configured support output that never produces its first frame is removed from repeating
 *   membership after one bounded liveness window, without rebuilding the CameraCaptureSession.
 * - A support stream that still produces frames but has not reached EGL presentation is not blamed
 *   at the ImageReader layer; renderer/GL/presentation recovery retains ownership of that failure.
 */
object RawViewfinderProducerHealthPolicy {
    fun resolve(input: RawViewfinderProducerHealthInput): RawViewfinderProducerHealthDecision {
        if (!input.rawViewfinderTargeted || !input.generationMatches) {
            return RawViewfinderProducerHealthDecision(
                activeProducer = RawViewfinderProducerPath.INACTIVE,
                canonicalPrimaryFresh = false,
                rawPreviewSupportExpected = false,
                rawPreviewSupportInputFresh = false,
                rawPreviewSupportStartupGraceElapsed = false,
                disableRawPreviewSupport = false,
                disableReason = null
            )
        }

        val canonicalFresh = input.canonicalFreshCompleteFrames > 0
        val supportExpected =
            input.rawPreviewSupportConfigured &&
                input.rawPreviewSupportRepeating &&
                !input.rawPreviewSupportDisabled

        val supportLastFrame = input.rawPreviewSupportLastFrameElapsedNs
        val supportInputFresh = supportExpected && supportLastFrame > 0L &&
            input.nowElapsedNs >= supportLastFrame &&
            input.nowElapsedNs - supportLastFrame <= input.stallThresholdNs

        val routeAgeNs = if (input.routeStartedElapsedNs > 0L && input.nowElapsedNs >= input.routeStartedElapsedNs) {
            input.nowElapsedNs - input.routeStartedElapsedNs
        } else {
            0L
        }
        val startupGraceElapsed = supportExpected &&
            input.routeStartedElapsedNs > 0L &&
            routeAgeNs > input.stallThresholdNs

        val missingFirstSupportFrame = supportExpected &&
            supportLastFrame <= 0L &&
            startupGraceElapsed
        val supportStalledAfterProgress = supportExpected &&
            supportLastFrame > 0L &&
            !supportInputFresh

        val disableReason = when {
            missingFirstSupportFrame -> "RAW_PREVIEW_SUPPORT_NO_FIRST_FRAME"
            supportStalledAfterProgress -> "RAW_PREVIEW_SUPPORT_INPUT_STALLED"
            else -> null
        }

        val activeProducer = if (
            supportExpected &&
            input.rawPreviewSupportPresentationProven &&
            supportInputFresh
        ) {
            RawViewfinderProducerPath.RAW_PREVIEW_SUPPORT
        } else {
            RawViewfinderProducerPath.PRIMARY_BUFFER
        }

        return RawViewfinderProducerHealthDecision(
            activeProducer = activeProducer,
            canonicalPrimaryFresh = canonicalFresh,
            rawPreviewSupportExpected = supportExpected,
            rawPreviewSupportInputFresh = supportInputFresh,
            rawPreviewSupportStartupGraceElapsed = startupGraceElapsed,
            disableRawPreviewSupport = disableReason != null,
            disableReason = disableReason
        )
    }
}
