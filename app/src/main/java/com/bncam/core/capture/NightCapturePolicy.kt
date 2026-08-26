package com.bncam.core.capture

import kotlin.math.max
import kotlin.math.min

enum class NightSceneClass {
    DIM,
    DARK,
    VERY_DARK,
    EXTREME,
    UNRESOLVED
}

data class NightCapturePlan(
    val sceneClass: NightSceneClass,
    val measuredIso: Int?,
    val measuredExposureNs: Long?,
    val profileRequestedFrames: Int,
    val adaptiveMinimumFrames: Int,
    val requestedFrames: Int,
    val runtimeMaximumFrames: Int,
    val reason: String
)

/**
 * Transient Night-mode capture policy.
 *
 * Night never changes profile render/ISP values. It only asks the existing Multi Frame route for a
 * sufficient number of frames. The profile can always request more than the adaptive floor; the
 * runtime/product capacity authorities remain the final clamp.
 */
object NightCapturePolicy {
    fun resolve(
        origin: FrameOrigin,
        measuredIso: Int?,
        measuredExposureNs: Long?,
        profileRequestedFrames: Int,
        runtimeSafeMaximum: Int
    ): NightCapturePlan {
        val productMaximum = FrameCapacityPolicy.maximumProcessingFrames(origin)
        val runtimeMaximum = min(productMaximum, runtimeSafeMaximum.coerceAtLeast(1))
        val safeProfileFrames = profileRequestedFrames.coerceAtLeast(1)

        val validIso = measuredIso?.takeIf { it > 0 }
        val validExposure = measuredExposureNs?.takeIf { it > 0L }
        val exposureProduct = if (validIso != null && validExposure != null) {
            (validExposure.toDouble() / 1_000_000_000.0) * (validIso.toDouble() / 100.0)
        } else {
            null
        }

        val sceneClass = when {
            exposureProduct == null -> NightSceneClass.UNRESOLVED
            exposureProduct <= 0.08 -> NightSceneClass.DIM
            exposureProduct <= 0.30 -> NightSceneClass.DARK
            exposureProduct <= 1.00 -> NightSceneClass.VERY_DARK
            else -> NightSceneClass.EXTREME
        }

        val adaptiveMinimum = when (origin) {
            FrameOrigin.YUV,
            FrameOrigin.RAW10 -> when (sceneClass) {
                NightSceneClass.DIM -> 6
                NightSceneClass.DARK -> 8
                NightSceneClass.VERY_DARK -> 10
                NightSceneClass.EXTREME -> 12
                NightSceneClass.UNRESOLVED -> 8
            }
            FrameOrigin.RAW_SENSOR -> when (sceneClass) {
                NightSceneClass.DIM -> 4
                NightSceneClass.DARK -> 5
                NightSceneClass.VERY_DARK -> 7
                NightSceneClass.EXTREME -> 9
                NightSceneClass.UNRESOLVED -> 5
            }
        }

        val requested = max(safeProfileFrames, adaptiveMinimum)
            .coerceAtMost(runtimeMaximum)
            .coerceAtLeast(1)

        return NightCapturePlan(
            sceneClass = sceneClass,
            measuredIso = validIso,
            measuredExposureNs = validExposure,
            profileRequestedFrames = safeProfileFrames,
            adaptiveMinimumFrames = adaptiveMinimum,
            requestedFrames = requested,
            runtimeMaximumFrames = runtimeMaximum,
            reason = buildString {
                append("night_multi_frame")
                append(";scene=").append(sceneClass.name)
                append(";iso=").append(validIso ?: "unknown")
                append(";exposureNs=").append(validExposure ?: "unknown")
                append(";profileFrames=").append(safeProfileFrames)
                append(";adaptiveFloor=").append(adaptiveMinimum)
                append(";runtimeMax=").append(runtimeMaximum)
                append(";requested=").append(requested)
            }
        )
    }
}
