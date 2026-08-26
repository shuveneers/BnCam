package com.bncam.core.capture

import android.graphics.ImageFormat

data class WarmBufferReadinessRequirement(
    val purpose: String,
    val requiredCompleteFrames: Int,
    val streamHealthFreshnessWindowMs: Double
)

/**
 * The single authority for deciding when a warm ImageReader route is populated and alive.
 * Stream-health freshness is intentionally separate from shutter-relative candidate freshness.
 */
object WarmBufferReadinessPolicy {
    fun streamHealth(
        format: Int,
        bufferCapacity: Int = Int.MAX_VALUE
    ): WarmBufferReadinessRequirement {
        val baseline = when (format) {
            ImageFormat.RAW_SENSOR -> 2
            ImageFormat.RAW10,
            ImageFormat.YUV_420_888 -> 3
            else -> 1
        }
        return WarmBufferReadinessRequirement(
            purpose = "STREAM_HEALTH",
            requiredCompleteFrames = baseline.coerceAtMost(bufferCapacity.coerceAtLeast(1)),
            streamHealthFreshnessWindowMs =
                if (format == ImageFormat.RAW_SENSOR) 500.0 else 350.0
        )
    }

    fun captureRoute(
        format: Int,
        captureMode: CaptureMode,
        requestedFrameCount: Int,
        bufferCapacity: Int
    ): WarmBufferReadinessRequirement {
        val capacity = bufferCapacity.coerceAtLeast(1)
        val required = when (captureMode) {
            CaptureMode.SINGLE,
            CaptureMode.EXPERIMENTAL ->
                streamHealth(format, capacity).requiredCompleteFrames
            CaptureMode.MULTI -> {
                val routeMaximum =
                    FrameCapacityPolicy.maximumProcessingFrames(
                        FrameCapacityPolicy.frameOrigin(format)
                    )
                val requestedTarget = requestedFrameCount.coerceIn(1, minOf(capacity, routeMaximum))
                // Multi-frame count is a quality target, not shutter-admission authority. Once the
                // user has pressed the shutter, frames that arrive later cannot become genuine
                // pre-shutter Near-ZSL support for that same press. Requiring 3 (or the full target)
                // therefore turns a perfectly valid one/two-frame pre-shutter set into a wait that
                // cannot improve it and can end in an empty shutter-relative selection. One complete
                // frame is enough to guarantee an anchor/output; the runner consumes every additional
                // eligible pre-shutter frame already present, up to requestedTarget.
                minOf(requestedTarget, 1)
            }
        }
        return WarmBufferReadinessRequirement(
            purpose = "CAPTURE_ROUTE_${captureMode.name}",
            requiredCompleteFrames = required,
            streamHealthFreshnessWindowMs =
                if (format == ImageFormat.RAW_SENSOR) 500.0 else 350.0
        )
    }
}

object ShutterCandidateFreshnessPolicy {
    const val GENUINE_NEAR_ZSL_WINDOW_MS = 120.0
}
