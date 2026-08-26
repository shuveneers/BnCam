package com.bncam.core.engine

/**
 * Resolves Camera2 zoom semantics for a YUV stream that is forced onto a hidden physical
 * ultra-wide child of a logical multi-camera.
 *
 * Camera2 SCALER_CROP_REGION cannot zoom out below logical 1.0x. CONTROL_ZOOM_RATIO can. When a
 * physical ultra-wide output is attached to the logical parent while the request remains at 1.0x,
 * some HALs legitimately crop that physical YUV stream until it matches the logical camera's 1.0x
 * field of view. RAW does not share that post-RAW crop behavior, so this policy is YUV-only.
 */
data class PhysicalYuvFullFovDecision(
    val enabled: Boolean,
    val nativeZoomRatio: Float,
    val maximumZoomRatio: Float,
    val reason: String
) {
    fun resolveEffectiveZoom(relativeDigitalZoom: Float): Float {
        if (!enabled) return 1f
        val safeRelative = relativeDigitalZoom.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
        return (nativeZoomRatio * safeRelative).coerceIn(nativeZoomRatio, maximumZoomRatio)
    }
}

object PhysicalYuvFullFovPolicy {
    fun resolve(
        isYuv: Boolean,
        physicalCameraId: String?,
        lensRole: String?,
        minimumLogicalZoomRatio: Float?,
        maximumLogicalZoomRatio: Float?
    ): PhysicalYuvFullFovDecision {
        if (!isYuv) {
            return disabled("NON_YUV_ROUTE")
        }
        if (physicalCameraId.isNullOrBlank()) {
            return disabled("DIRECT_OR_LOGICAL_CAMERA_ROUTE")
        }
        if (!lensRole.orEmpty().contains("ultra", ignoreCase = true)) {
            return disabled("PHYSICAL_ROUTE_IS_NOT_ULTRA_WIDE")
        }

        val minZoom = minimumLogicalZoomRatio
        val maxZoom = maximumLogicalZoomRatio
        if (minZoom == null || maxZoom == null || !minZoom.isFinite() || !maxZoom.isFinite()) {
            return disabled("LOGICAL_ZOOM_RATIO_RANGE_UNAVAILABLE")
        }
        if (minZoom <= 0f || maxZoom < 1f || minZoom > maxZoom) {
            return disabled("LOGICAL_ZOOM_RATIO_RANGE_INVALID")
        }
        if (minZoom >= 0.999f) {
            return disabled("LOGICAL_CAMERA_HAS_NO_ZOOM_OUT_RANGE")
        }

        return PhysicalYuvFullFovDecision(
            enabled = true,
            nativeZoomRatio = minZoom,
            maximumZoomRatio = maxZoom,
            reason = "LOGICAL_ZOOM_OUT_TO_PHYSICAL_ULTRA_WIDE_NATIVE_FOV"
        )
    }

    private fun disabled(reason: String) = PhysicalYuvFullFovDecision(
        enabled = false,
        nativeZoomRatio = 1f,
        maximumZoomRatio = 1f,
        reason = reason
    )
}
