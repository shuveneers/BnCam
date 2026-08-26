package com.bncam.core.engine

import kotlin.math.max

/**
 * Pure policy for Focus Track responsiveness. Heavy image analysis stays in the preview/ML/Vulkan
 * pipeline; this object only owns scalar thresholds so the production behavior is regression-testable.
 */
object FocusTrackingPolicy {
    const val HARDWARE_UPDATE_INTERVAL_NS: Long = 24_000_000L
    const val FACE_UPDATE_INTERVAL_NS: Long = 80_000_000L
    const val LOST_PHASE_FRAME_THRESHOLD: Int = 14
    const val LOST_FRAME_CAP: Int = 30

    fun adaptiveAfRegionPct(boxWidthPct: Float, boxHeightPct: Float): Float =
        (max(boxWidthPct, boxHeightPct) * 0.48f).coerceIn(0.035f, 0.14f)

    fun reacquisitionSearchRadius(lostFrames: Int): Float =
        (0.11f + lostFrames.coerceAtMost(16) * 0.012f).coerceAtMost(0.30f)

    fun phaseForLostFrames(lostFrames: Int): FocusTrackingPhase =
        if (lostFrames.coerceAtLeast(0) >= LOST_PHASE_FRAME_THRESHOLD) {
            FocusTrackingPhase.LOST
        } else {
            FocusTrackingPhase.REACQUIRING
        }

    fun confidenceForLostFrames(lostFrames: Int): Float =
        (0.70f - lostFrames.coerceAtLeast(0) * 0.045f).coerceIn(0f, 0.65f)

    fun acquisitionConfidence(lostFrames: Int): Float =
        (0.35f - lostFrames.coerceAtLeast(0) * 0.015f).coerceAtLeast(0.10f)
}
