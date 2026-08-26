package com.bncam.core.capture

import com.bncam.core.engine.CaptureStrategy

/**
 * Pure policy resolver for capture authority.
 *
 * Enforces hard invariants:
 * 1. ACQUISITION STRATEGY (Single / Multi / Computational HDR / Night)
 *    ≠ PROCESSING FEATURE (Portrait / Ultra HDR)
 *    ≠ OUTPUT FORMAT (JPEG / Ultra HDR Packaging)
 * 2. Ultra HDR != Computational HDR. Ultra HDR NEVER implicitly activates Computational HDR,
 *    never requests an exposure bracket, and never changes Single to Multi.
 * 3. Portrait intent comes strictly from [ViewfinderMode.PORTRAIT].
 */
data class CaptureAuthorityResolution(
    val profileRequestedStrategy: CaptureStrategy,
    val effectiveCaptureStrategy: CaptureStrategy,
    val actualRunner: String,
    val overrideReason: String,
    val computationalHdrUserRequested: Boolean,
    val computationalHdrRequested: Boolean,
    val computationalHdrRouteEnabled: Boolean,
    val computationalHdrResolutionReason: String,
    val ultraHdrRequested: Boolean,
    val ultraHdrPackagingEnabled: Boolean,
    val portraitRequested: Boolean,
    val nightPlanActive: Boolean
)

object CaptureAuthorityResolver {
    fun resolve(
        profileStrategy: CaptureStrategy,
        viewfinderMode: ViewfinderMode,
        computationalHdrUserRequested: Boolean,
        ultraHdrRequested: Boolean,
        dedicatedFlashStill: Boolean,
        producesJpeg: Boolean
    ): CaptureAuthorityResolution {
        // Hard invariant: Ultra HDR toggle must not set computationalHdrRequested = true.
        // Computational HDR is app-level capture authority. Profiles only choose Single/Multi.
        val normalizedProfileStrategy = when (profileStrategy) {
            CaptureStrategy.MULTI_FRAME_ZSL -> CaptureStrategy.MULTI_FRAME_ZSL
            else -> CaptureStrategy.SINGLE_FRAME_ZSL
        }
        val computationalHdrRequested = computationalHdrUserRequested
        val nightOwnsAdaptiveMultiFrame = viewfinderMode == ViewfinderMode.NIGHT

        val computationalHdrRouteEnabled = computationalHdrRequested &&
            !dedicatedFlashStill &&
            !nightOwnsAdaptiveMultiFrame

        val computationalHdrResolutionReason = when {
            !computationalHdrRequested -> "not_requested"
            dedicatedFlashStill -> "dedicated_flash_still"
            nightOwnsAdaptiveMultiFrame -> "night_mode_owns_adaptive_multi_frame"
            else -> "eligible_for_hdr_enhanced"
        }

        val effectiveCaptureStrategy = when {
            dedicatedFlashStill -> CaptureStrategy.SINGLE_FRAME_ZSL
            computationalHdrRouteEnabled -> CaptureStrategy.HDR_ENHANCED
            viewfinderMode == ViewfinderMode.NIGHT -> CaptureStrategy.MULTI_FRAME_ZSL
            else -> normalizedProfileStrategy
        }

        val overrideReason = when {
            dedicatedFlashStill -> "dedicated_flash_still"
            computationalHdrRouteEnabled -> "computational_hdr_user_requested"
            viewfinderMode == ViewfinderMode.NIGHT -> "night_mode_active"
            else -> "none"
        }

        val actualRunner = when (effectiveCaptureStrategy) {
            CaptureStrategy.SINGLE_FRAME_ZSL -> "SingleFrameRunner"
            CaptureStrategy.MULTI_FRAME_ZSL -> "MultiFrameRunner"
            CaptureStrategy.HDR_ENHANCED -> "HdrEnhancedRunner"
        }

        val portraitRequested = viewfinderMode == ViewfinderMode.PORTRAIT
        val ultraHdrPackagingEnabled = ultraHdrRequested && producesJpeg

        return CaptureAuthorityResolution(
            profileRequestedStrategy = normalizedProfileStrategy,
            effectiveCaptureStrategy = effectiveCaptureStrategy,
            actualRunner = actualRunner,
            overrideReason = overrideReason,
            computationalHdrUserRequested = computationalHdrUserRequested,
            computationalHdrRequested = computationalHdrRequested,
            computationalHdrRouteEnabled = computationalHdrRouteEnabled,
            computationalHdrResolutionReason = computationalHdrResolutionReason,
            ultraHdrRequested = ultraHdrRequested,
            ultraHdrPackagingEnabled = ultraHdrPackagingEnabled,
            portraitRequested = portraitRequested,
            nightPlanActive = nightOwnsAdaptiveMultiFrame && !dedicatedFlashStill
        )
    }
}
