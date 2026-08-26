package com.bncam.core.runtime

import android.hardware.camera2.CameraCharacteristics
import android.util.Size

data class RawStreamCapabilities(
    val format: Int,
    val captureSize: Size,
    val previewSize: Size,
    val isRaw10Supported: Boolean,
    val isRawSensorSupported: Boolean,
    val supportsSimultaneousDualRawOutputs: Boolean,
    val minFrameDurationNs: Long
)

data class FpsCapabilityState(
    val availableTargetFpsRanges: List<Pair<Int, Int>>,
    val preferredFpsRange: Pair<Int, Int>,
    val minFrameDurationMs: Double,
    val maxTheoreticalFps: Double
)

data class VulkanImportCapabilities(
    val raw10DirectAHardwareBufferImportable: Boolean,
    val rawSensorDirectAHardwareBufferImportable: Boolean,
    val yuvDirectAHardwareBufferImportable: Boolean,
    val zeroCopyPreviewOutputSupported: Boolean,
    val preferredImportMode: String
)

data class PreviewConfiguration(
    val viewfinderEffectiveSource: String,
    val rawStreamSelectedByUser: String,
    val activePreviewStreamSize: Size,
    val isSharingCaptureStream: Boolean
)

/**
 * Immutable description of the current RAW session's topology, geometry and hardware capabilities.
 * Recreated ONLY when physical camera, format, stream size, or Camera2 session generation changes.
 */
data class RawPipelineRuntimeProfile(
    val sessionId: String,
    val logicalCameraId: String,
    val physicalCameraId: String,
    val format: Int,
    val sessionGeneration: Int,
    val geometry: RawStreamGeometry,
    val bufferLayout: RawBufferLayout,
    val capabilities: RawStreamCapabilities,
    val fpsCapability: FpsCapabilityState,
    val vulkanCapabilities: VulkanImportCapabilities,
    val previewConfig: PreviewConfiguration,
    val createdAtElapsedNs: Long = System.nanoTime()
) {
    fun summaryLines(): List<Pair<String, String>> = listOf(
        "Session ID" to sessionId,
        "Logical / Physical Camera" to "$logicalCameraId / $physicalCameraId",
        "Format / Generation" to "$format / gen=$sessionGeneration",
        "Geometry" to "${geometry.bufferWidth}x${geometry.bufferHeight} (Crop L:${geometry.cropLeft} T:${geometry.cropTop} ${geometry.cropWidth}x${geometry.cropHeight})",
        "Buffer Layout" to "rowStride=${bufferLayout.rowStrideBytes} packed=${bufferLayout.packedRowBytes} valid=${bufferLayout.isValid}",
        "Preview Config" to "Source=${previewConfig.viewfinderEffectiveSource} Stream=${previewConfig.activePreviewStreamSize.width}x${previewConfig.activePreviewStreamSize.height} Shared=${previewConfig.isSharingCaptureStream}",
        "Vulkan Import" to "RAW10:${vulkanCapabilities.raw10DirectAHardwareBufferImportable} RAW_SENSOR:${vulkanCapabilities.rawSensorDirectAHardwareBufferImportable} ZeroCopy:${vulkanCapabilities.zeroCopyPreviewOutputSupported}",
        "FPS Capability" to "Preferred=[${fpsCapability.preferredFpsRange.first},${fpsCapability.preferredFpsRange.second}] MaxTheoretical=${String.format(java.util.Locale.US, "%.1f", fpsCapability.maxTheoreticalFps)}fps"
    )
}
