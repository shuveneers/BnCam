package com.bncam.data.profile

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Resolves only the portable frame-source capabilities needed during .bnc transfer.
 * No hardware calibration values are exported or imported here.
 */
object BncProfileDeviceCapabilities {
    fun inspect(context: Context, cameraId: String): BncTargetCapabilities {
        if (cameraId.isBlank()) return BncTargetCapabilities.UNKNOWN
        return runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@runCatching BncTargetCapabilities.UNKNOWN
            val available = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val rawCapability = available.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
            buildSet {
                if (!map.getOutputSizes(ImageFormat.YUV_420_888).isNullOrEmpty()) add("YUV")
                if (rawCapability && !map.getOutputSizes(ImageFormat.RAW10).isNullOrEmpty()) add("RAW10")
                if (rawCapability && !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()) add("RAW_SENSOR")
            }.let { supported ->
                if (supported.isEmpty()) BncTargetCapabilities.UNKNOWN
                else BncTargetCapabilities(supportedFrameSources = supported)
            }
        }.getOrDefault(BncTargetCapabilities.UNKNOWN)
    }
}
