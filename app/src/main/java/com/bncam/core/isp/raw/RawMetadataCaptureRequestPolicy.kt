package com.bncam.core.isp.raw

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest

data class RawHotPixelMapRequestDecision(
    val rawPipeline: Boolean,
    val capabilityReported: Boolean,
    val mapModeSupported: Boolean,
    val requested: Boolean,
    val reason: String
)

/** Camera2 request ownership for exact RAW defect-map metadata. */
object RawMetadataCaptureRequestPolicy {

    internal fun resolveHotPixelMapRequest(
        rawPipeline: Boolean,
        availableModes: BooleanArray?
    ): RawHotPixelMapRequestDecision {
        if (!rawPipeline) {
            return RawHotPixelMapRequestDecision(
                rawPipeline = false,
                capabilityReported = availableModes != null,
                mapModeSupported = false,
                requested = false,
                reason = "NON_RAW_PIPELINE"
            )
        }
        if (availableModes == null) {
            return RawHotPixelMapRequestDecision(
                rawPipeline = true,
                capabilityReported = false,
                mapModeSupported = false,
                requested = false,
                reason = "AVAILABLE_HOT_PIXEL_MAP_MODES_NOT_REPORTED"
            )
        }
        val supported = availableModes.any { it }
        return RawHotPixelMapRequestDecision(
            rawPipeline = true,
            capabilityReported = true,
            mapModeSupported = supported,
            requested = supported,
            reason = if (supported) "CAMERA2_HOT_PIXEL_MAP_ENABLED" else "CAMERA2_HOT_PIXEL_MAP_UNAVAILABLE"
        )
    }

    fun applyHotPixelMapRequest(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        frameSourceFormat: Int
    ): RawHotPixelMapRequestDecision {
        val rawPipeline = frameSourceFormat == ImageFormat.RAW10 ||
            frameSourceFormat == ImageFormat.RAW_SENSOR
        val modes = runCatching {
            characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_HOT_PIXEL_MAP_MODES)
        }.getOrNull()
        val decision = resolveHotPixelMapRequest(rawPipeline, modes)
        if (decision.requested) {
            builder.set(CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE, true)
        }
        return decision
    }
}
