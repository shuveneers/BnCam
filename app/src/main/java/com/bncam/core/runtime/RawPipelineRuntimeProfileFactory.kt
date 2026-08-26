package com.bncam.core.runtime

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Log
import android.util.Size
import com.bncam.core.vulkan.VulkanNativeBridge

object RawPipelineRuntimeProfileFactory {
    private const val TAG = "RuntimeProfileFactory"

    fun buildAndPublish(
        context: Context,
        logicalCameraId: String,
        physicalCameraId: String,
        format: Int,
        sessionGeneration: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rowStride: Int,
        userSelectedRawStream: String = "AUTO"
    ): RawPipelineRuntimeProfile? {
        return runCatching {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(physicalCameraId)

            val estimatedPixelStride = when (format) {
                ImageFormat.RAW10 -> 0
                ImageFormat.RAW_SENSOR -> 2
                else -> 1
            }
            val geometry = RawStreamGeometry.create(
                characteristics = characteristics,
                format = format,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                rowStride = rowStride,
                pixelStride = estimatedPixelStride,
                displayRotation = 0,
                targetPreviewMaxWidth = RawPreviewResolutionPolicy.QUALITY_MAX_WIDTH,
                targetPreviewMaxHeight = RawPreviewResolutionPolicy.QUALITY_MAX_HEIGHT
            )

            val bufferLayout = RawBufferLayout.createNormalized(
                format = format,
                width = bufferWidth,
                height = bufferHeight,
                rowStrideBytes = rowStride,
                pixelStrideBytes = estimatedPixelStride
            )

            val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val raw10Sizes = streamMap?.getOutputSizes(ImageFormat.RAW10) ?: emptyArray()
            val rawSensorSizes = streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: emptyArray()

            val minDurationNs = streamMap?.getOutputMinFrameDuration(format, Size(bufferWidth, bufferHeight)) ?: 33_333_333L

            // Detect whether dual simultaneous RAW outputs are supported by HAL capabilities
            val maxRawStreams = characteristics.get(CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW) ?: 1
            val dualRawSupported = maxRawStreams >= 2

            val capabilities = RawStreamCapabilities(
                format = format,
                captureSize = Size(bufferWidth, bufferHeight),
                previewSize = Size(geometry.previewOutputWidth, geometry.previewOutputHeight),
                isRaw10Supported = raw10Sizes.isNotEmpty(),
                isRawSensorSupported = rawSensorSizes.isNotEmpty(),
                supportsSimultaneousDualRawOutputs = dualRawSupported,
                minFrameDurationNs = minDurationNs
            )

            val aeRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val rangeList = aeRanges.map { Pair(it.lower, it.upper) }
            val preferredFps = rangeList.firstOrNull { it.first <= 30 && it.second >= 60 } ?: Pair(30, 60)
            val minFrameMs = minDurationNs / 1_000_000.0
            val maxFps = if (minDurationNs > 0) 1_000_000_000.0 / minDurationNs else 30.0

            val fpsState = FpsCapabilityState(
                availableTargetFpsRanges = rangeList,
                preferredFpsRange = preferredFps,
                minFrameDurationMs = minFrameMs,
                maxTheoreticalFps = maxFps
            )

            val vulkanSnapshot = runCatching { com.bncam.core.vulkan.VulkanRuntimeOwner.snapshot() }.getOrNull()
            val ahbExtSupported = vulkanSnapshot?.enabledExtensions?.any { it.contains("hardware_buffer", ignoreCase = true) } ?: true
            val vulkanCaps = VulkanImportCapabilities(
                raw10DirectAHardwareBufferImportable = ahbExtSupported,
                rawSensorDirectAHardwareBufferImportable = ahbExtSupported,
                yuvDirectAHardwareBufferImportable = ahbExtSupported,
                zeroCopyPreviewOutputSupported = ahbExtSupported,
                preferredImportMode = if (ahbExtSupported) "DIRECT_AHARDWAREBUFFER" else "STAGING_FALLBACK"
            )

            val effectiveSource = when (format) {
                ImageFormat.RAW10 -> "RAW10"
                ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
                else -> "YUV"
            }

            val sharingCapture = !dualRawSupported

            val previewConfig = PreviewConfiguration(
                viewfinderEffectiveSource = effectiveSource,
                rawStreamSelectedByUser = userSelectedRawStream,
                activePreviewStreamSize = Size(geometry.previewOutputWidth, geometry.previewOutputHeight),
                isSharingCaptureStream = sharingCapture
            )

            val sessionId = "${physicalCameraId}_fmt${format}_${bufferWidth}x${bufferHeight}_gen${sessionGeneration}"

            val profile = RawPipelineRuntimeProfile(
                sessionId = sessionId,
                logicalCameraId = logicalCameraId,
                physicalCameraId = physicalCameraId,
                format = format,
                sessionGeneration = sessionGeneration,
                geometry = geometry,
                bufferLayout = bufferLayout,
                capabilities = capabilities,
                fpsCapability = fpsState,
                vulkanCapabilities = vulkanCaps,
                previewConfig = previewConfig
            )

            RawPipelineRuntimeOwner.updateProfile(profile)
            RawPipelineRuntimeOwner.updateState { state ->
                state.copy(
                    sessionGeneration = sessionGeneration,
                    cadence = state.cadence.copy(
                        activeAeTargetFps = preferredFps
                    )
                )
            }

            PortabilityDiagnosticsReport.generateReport(context, profile, RawPipelineRuntimeOwner.getState())
            Log.i(TAG, "RawPipelineRuntimeProfile published: $sessionId")
            profile
        }.onFailure { t ->
            Log.e(TAG, "Failed to build RawPipelineRuntimeProfile", t)
        }.getOrNull()
    }
    /**
     * Refines the current RAW session from the first real Camera2 Image. Besides replacing the
     * estimated row/pixel stride, this resolves a HAL-declared or strongly evidenced visible RAW
     * payload rect before that frame reaches preview/capture consumers.
     */
    fun refinePublishedLayoutFromImage(image: android.media.Image, sessionGeneration: Int) {
        if (image.format != ImageFormat.RAW10 && image.format != ImageFormat.RAW_SENSOR) return
        val current = RawPipelineRuntimeOwner.getProfile() ?: return
        if (current.sessionGeneration != sessionGeneration ||
            current.format != image.format ||
            current.geometry.bufferWidth != image.width ||
            current.geometry.bufferHeight != image.height
        ) return

        val actualLayout = RawBufferLayout.fromImage(image)
        if (!actualLayout.isValid) {
            Log.e(TAG, "RAW_RUNTIME_LAYOUT_REJECTED generation=$sessionGeneration ${actualLayout.validationError}")
            return
        }

        val stageA = com.bncam.core.isp.raw.RawColumnStatsAuditor.auditImagePlaneStageA(
            stage = "Stage-A (Runtime Geometry Probe)",
            image = image,
            width = image.width,
            height = image.height
        )
        val imageCrop = runCatching { android.graphics.Rect(image.cropRect) }.getOrNull()
        val payloadDecision = RawVisiblePayloadResolver.resolve(
            bufferWidth = image.width,
            bufferHeight = image.height,
            existingVisibleRect = current.geometry.visibleRawRect,
            imageCropRect = imageCrop,
            stageA = stageA
        )

        var refinedGeometry = current.geometry
        if (payloadDecision.visibleRect != current.geometry.visibleRawRect) {
            refinedGeometry = current.geometry.withVisibleRawRect(payloadDecision.visibleRect)
        }

        if (refinedGeometry.rowStride != actualLayout.rowStrideBytes ||
            refinedGeometry.pixelStride != actualLayout.pixelStrideBytes
        ) {
            val capture = refinedGeometry.captureGeometry.copy(
                rowStride = actualLayout.rowStrideBytes,
                pixelStride = actualLayout.pixelStrideBytes
            )
            refinedGeometry = refinedGeometry.copy(
                captureGeometry = capture,
                previewGeometry = refinedGeometry.previewGeometry.copy(captureGeometry = capture)
            )
        }

        val geometryChanged = refinedGeometry != current.geometry
        val layoutChanged = current.bufferLayout != actualLayout
        if (geometryChanged || layoutChanged) {
            val updatedCapabilities = current.capabilities.copy(
                previewSize = Size(refinedGeometry.previewOutputWidth, refinedGeometry.previewOutputHeight)
            )
            val updatedPreviewConfig = current.previewConfig.copy(
                activePreviewStreamSize = Size(refinedGeometry.previewOutputWidth, refinedGeometry.previewOutputHeight)
            )
            RawPipelineRuntimeOwner.updateProfile(
                current.copy(
                    geometry = refinedGeometry,
                    bufferLayout = actualLayout,
                    capabilities = updatedCapabilities,
                    previewConfig = updatedPreviewConfig
                )
            )
        }

        val event =
            "generation=$sessionGeneration format=${image.format} size=${image.width}x${image.height} " +
                "rowStride=${actualLayout.rowStrideBytes} pixelStride=${actualLayout.pixelStrideBytes} " +
                "imageCrop=${imageCrop ?: "unavailable"} " +
                "resolvedCrop=${refinedGeometry.cropLeft},${refinedGeometry.cropTop}," +
                "${refinedGeometry.cropWidth}x${refinedGeometry.cropHeight} " +
                "source=${payloadDecision.source} confidence=" +
                String.format(java.util.Locale.US, "%.3f", payloadDecision.confidence) +
                " reason=${payloadDecision.reason}"
        Log.i(TAG, "RAW_RUNTIME_LAYOUT_REFINED $event")
        com.bncam.core.debug.DeviceTelemetryLogger.logEvent("RAW_VISIBLE_PAYLOAD_RESOLVED", event)
    }

}
