package com.bncam.core.capture

import com.bncam.core.engine.CaptureStrategy

enum class CaptureMode {
    SINGLE,
    MULTI,
    EXPERIMENTAL;

    companion object {
        fun from(strategy: CaptureStrategy): CaptureMode = when (strategy) {
            CaptureStrategy.SINGLE_FRAME_ZSL -> SINGLE
            CaptureStrategy.MULTI_FRAME_ZSL -> MULTI
            CaptureStrategy.HDR_ENHANCED -> MULTI
        }
    }
}

enum class FrameOrigin {
    YUV,
    RAW10,
    RAW_SENSOR;

    companion object {
        fun parse(value: String): FrameOrigin = entries.firstOrNull { it.name == value.trim().uppercase() }
            ?: throw UnsupportedCaptureContractException("Unknown frame origin '$value'.")
    }
}

enum class OutputPolicy(val persistedValue: String, val displayName: String) {
    JPEG("JPEG", "JPEG"),
    JPEG_PLUS_RAW("JPEG + RAW", "JPEG + RAW"),
    RAW_ONLY("RAW-only", "RAW-only");

    val producesJpeg: Boolean get() = this != RAW_ONLY
    val producesRaw: Boolean get() = this != JPEG

    companion object {
        fun parse(value: String?): OutputPolicy = when (value?.trim()?.uppercase()?.replace(" ", "")) {
            null, "", "JPEG" -> JPEG
            "RAW+JPEG", "JPEG+RAW", "JPEG_PLUS_RAW" -> JPEG_PLUS_RAW
            "RAW-ONLY", "RAW_ONLY", "RAWONLY", "RAW" -> RAW_ONLY
            else -> throw UnsupportedCaptureContractException("Unknown output policy '$value'.")
        }
    }
}

object CaptureOutputPolicyResolver {
    /**
     * The output policy is stored globally, while the selected frame origin is profile-specific.
     * Preserve JPEG capture when switching from a RAW profile configured for JPEG + RAW to YUV:
     * YUV has no DNG payload, so only the inapplicable RAW side of the request is removed.
     *
     * RAW_ONLY remains unchanged and is rejected by [CaptureRoutePlanner], because silently
     * producing a JPEG for an explicitly RAW-only request would violate the selected policy.
     */
    fun effectivePolicy(
        frameOrigin: FrameOrigin,
        requestedPolicy: OutputPolicy
    ): OutputPolicy = if (
        frameOrigin == FrameOrigin.YUV &&
        requestedPolicy == OutputPolicy.JPEG_PLUS_RAW
    ) {
        OutputPolicy.JPEG
    } else {
        requestedPolicy
    }
}

data class CaptureCapabilities(
    val yuv: Boolean,
    val raw10: Boolean,
    val rawSensor: Boolean,
    val camera2RawCapability: Boolean
) {
    fun supports(origin: FrameOrigin): Boolean = when (origin) {
        FrameOrigin.YUV -> yuv
        FrameOrigin.RAW10 -> camera2RawCapability && raw10
        FrameOrigin.RAW_SENSOR -> camera2RawCapability && rawSensor
    }
}

data class PerformanceDebugPolicy(
    val shotLoggingEnabled: Boolean,
    val qualificationEnabled: Boolean = false,
    val rawDebugExportsEnabled: Boolean = false
)

data class CaptureRequestPlan(
    val captureMode: CaptureMode,
    val frameOrigin: FrameOrigin,
    val outputPolicy: OutputPolicy,
    val renderProfileId: String,
    val cameraId: String,
    val capabilities: CaptureCapabilities,
    val debugPolicy: PerformanceDebugPolicy,
    val route: CaptureRoute
) {
    init {
        require(renderProfileId.isNotBlank()) { "A render profile is required for every capture plan." }
        require(cameraId.isNotBlank()) { "A camera id is required for every capture plan." }
    }

    fun logValue(): String =
        "CAPTURE_PLAN route=${route.id} captureMode=$captureMode frameOrigin=$frameOrigin " +
            "outputPolicy=$outputPolicy renderProfile=$renderProfileId cameraId=$cameraId " +
            "jpeg=${outputPolicy.producesJpeg} raw=${outputPolicy.producesRaw}"
}

sealed class CaptureRoute(val id: String) {
    data object SingleYuvJpeg : CaptureRoute("SingleYuvJpegPath")
    data object SingleRaw10Jpeg : CaptureRoute("SingleRaw10JpegPath")
    data object SingleRawSensorJpeg : CaptureRoute("SingleRawSensorJpegPath")
    data object SingleRaw10RawOnly : CaptureRoute("SingleRaw10RawOnlyPath")
    data object SingleRawSensorRawOnly : CaptureRoute("SingleRawSensorRawOnlyPath")
    data object MultiYuvJpeg : CaptureRoute("MultiYuvJpegPath")
    data object MultiRaw10MasterRaw16Jpeg : CaptureRoute("MultiRaw10MasterRaw16JpegPath")
    data object MultiRawSensorMasterRaw16Jpeg : CaptureRoute("MultiRawSensorMasterRaw16JpegPath")
    data object MultiRaw10RawOnly : CaptureRoute("MultiRaw10RawOnlyPath")
    data object MultiRawSensorRawOnly : CaptureRoute("MultiRawSensorRawOnlyPath")
    data object FutureExperimental : CaptureRoute("FutureExperimentalPath")
}

class UnsupportedCaptureContractException(message: String) : IllegalArgumentException(message)

object CaptureRoutePlanner {
    fun plan(
        captureMode: CaptureMode,
        frameOrigin: FrameOrigin,
        outputPolicy: OutputPolicy,
        renderProfileId: String,
        cameraId: String,
        capabilities: CaptureCapabilities,
        debugPolicy: PerformanceDebugPolicy
    ): CaptureRequestPlan {
        if (!capabilities.supports(frameOrigin)) {
            throw UnsupportedCaptureContractException(
                "Camera '$cameraId' does not expose the required $frameOrigin Camera2 stream capability."
            )
        }
        if (frameOrigin == FrameOrigin.YUV && outputPolicy.producesRaw) {
            throw UnsupportedCaptureContractException(
                "$outputPolicy is not applicable to YUV. Select RAW10 or RAW_SENSOR, or choose JPEG."
            )
        }

        val route = when (captureMode) {
            CaptureMode.SINGLE -> when (frameOrigin) {
                FrameOrigin.YUV -> CaptureRoute.SingleYuvJpeg
                FrameOrigin.RAW10 -> if (outputPolicy == OutputPolicy.RAW_ONLY) {
                    CaptureRoute.SingleRaw10RawOnly
                } else {
                    CaptureRoute.SingleRaw10Jpeg
                }
                FrameOrigin.RAW_SENSOR -> if (outputPolicy == OutputPolicy.RAW_ONLY) {
                    CaptureRoute.SingleRawSensorRawOnly
                } else {
                    CaptureRoute.SingleRawSensorJpeg
                }
            }
            CaptureMode.MULTI -> when (frameOrigin) {
                FrameOrigin.YUV -> CaptureRoute.MultiYuvJpeg
                FrameOrigin.RAW10 -> if (outputPolicy == OutputPolicy.RAW_ONLY) {
                    CaptureRoute.MultiRaw10RawOnly
                } else {
                    CaptureRoute.MultiRaw10MasterRaw16Jpeg
                }
                FrameOrigin.RAW_SENSOR -> if (outputPolicy == OutputPolicy.RAW_ONLY) {
                    CaptureRoute.MultiRawSensorRawOnly
                } else {
                    CaptureRoute.MultiRawSensorMasterRaw16Jpeg
                }
            }
            CaptureMode.EXPERIMENTAL -> CaptureRoute.FutureExperimental
        }

        return CaptureRequestPlan(
            captureMode = captureMode,
            frameOrigin = frameOrigin,
            outputPolicy = outputPolicy,
            renderProfileId = renderProfileId,
            cameraId = cameraId,
            capabilities = capabilities,
            debugPolicy = debugPolicy,
            route = route
        )
    }
}
