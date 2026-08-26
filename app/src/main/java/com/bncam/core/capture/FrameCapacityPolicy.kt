package com.bncam.core.capture

import android.graphics.ImageFormat

enum class FrameCapacityPurpose {
    WARM_BUFFER,
    SELECTABLE_CANDIDATES,
    PROCESSING_FRAMES,
    DNG_MASTER_FRAMES
}

enum class FrameCapacityResolutionReason {
    AS_REQUESTED,
    CONFIGURED_MAXIMUM,
    RUNTIME_SAFE_MAXIMUM,
    PRODUCT_SOURCE_MAXIMUM,
    SINGLE_FRAME_RESTRICTION,
    SOURCE_NOT_APPLICABLE,
    OUTPUT_NOT_APPLICABLE,
    MULTIPLE_LIMITS
}

data class FrameCapacityResolution(
    val purpose: FrameCapacityPurpose,
    val frameOrigin: FrameOrigin,
    val captureMode: CaptureMode,
    val requestedValue: Int,
    val configuredMaximum: Int,
    val runtimeSafeMaximum: Int,
    val productMaximum: Int,
    val effectiveValue: Int,
    val resolutionReason: FrameCapacityResolutionReason,
    val limitingReasons: List<FrameCapacityResolutionReason>
) {
    val wasClamped: Boolean
        get() = effectiveValue != requestedValue

    init {
        require(requestedValue >= 0)
        require(configuredMaximum >= 0)
        require(runtimeSafeMaximum >= 0)
        require(productMaximum >= 0)
        require(effectiveValue >= 0)
    }
}

/**
 * The single product and runtime authority for warm-buffer, selection, processing and DNG counts.
 *
 * The runtime-safe warm capacity is supplied by [com.bncam.core.buffer.CaptureBufferBudget].
 * It is deliberately kept separate from the product target so diagnostics never present a target
 * as a hardware specification. It is an application limit resolved from the current dimensions,
 * memory budget and product constraints.
 */
object FrameCapacityPolicy {
    const val YUV_WARM_BUFFER_TARGET = 35
    const val RAW10_WARM_BUFFER_TARGET = 35
    const val RAW_SENSOR_WARM_BUFFER_TARGET = 15

    const val YUV_PROCESSING_MAXIMUM = 25
    const val RAW10_PROCESSING_MAXIMUM = 25
    const val RAW_SENSOR_PROCESSING_MAXIMUM = 10

    fun frameOrigin(format: Int): FrameOrigin = when (format) {
        ImageFormat.YUV_420_888 -> FrameOrigin.YUV
        ImageFormat.RAW10 -> FrameOrigin.RAW10
        ImageFormat.RAW_SENSOR -> FrameOrigin.RAW_SENSOR
        else -> throw UnsupportedCaptureContractException("Unsupported frame format '$format'.")
    }

    fun warmBufferTarget(origin: FrameOrigin): Int = when (origin) {
        FrameOrigin.YUV -> YUV_WARM_BUFFER_TARGET
        FrameOrigin.RAW10 -> RAW10_WARM_BUFFER_TARGET
        FrameOrigin.RAW_SENSOR -> RAW_SENSOR_WARM_BUFFER_TARGET
    }

    fun maximumSelectableCandidates(origin: FrameOrigin): Int = warmBufferTarget(origin)

    fun maximumProcessingFrames(origin: FrameOrigin): Int = when (origin) {
        FrameOrigin.YUV -> YUV_PROCESSING_MAXIMUM
        FrameOrigin.RAW10 -> RAW10_PROCESSING_MAXIMUM
        FrameOrigin.RAW_SENSOR -> RAW_SENSOR_PROCESSING_MAXIMUM
    }

    fun maximumDngMasterFrames(origin: FrameOrigin): Int = when (origin) {
        FrameOrigin.YUV -> 0
        FrameOrigin.RAW10 -> RAW10_PROCESSING_MAXIMUM
        FrameOrigin.RAW_SENSOR -> RAW_SENSOR_PROCESSING_MAXIMUM
    }

    fun resolveWarmBuffer(
        origin: FrameOrigin,
        runtimeSafeMaximum: Int,
        configuredMaximum: Int = warmBufferTarget(origin),
        captureMode: CaptureMode = CaptureMode.SINGLE
    ): FrameCapacityResolution = resolve(
        purpose = FrameCapacityPurpose.WARM_BUFFER,
        origin = origin,
        captureMode = captureMode,
        requestedValue = warmBufferTarget(origin),
        configuredMaximum = configuredMaximum,
        runtimeSafeMaximum = runtimeSafeMaximum,
        productMaximum = warmBufferTarget(origin)
    )

    fun resolveSelectableCandidates(
        origin: FrameOrigin,
        captureMode: CaptureMode,
        requestedValue: Int,
        runtimeSafeMaximum: Int,
        configuredMaximum: Int = maximumSelectableCandidates(origin)
    ): FrameCapacityResolution = resolve(
        purpose = FrameCapacityPurpose.SELECTABLE_CANDIDATES,
        origin = origin,
        captureMode = captureMode,
        requestedValue = requestedValue,
        configuredMaximum = configuredMaximum,
        runtimeSafeMaximum = runtimeSafeMaximum,
        productMaximum = maximumSelectableCandidates(origin)
    )

    fun resolveProcessingFrames(
        origin: FrameOrigin,
        captureMode: CaptureMode,
        requestedValue: Int,
        runtimeSafeMaximum: Int,
        configuredMaximum: Int = maximumProcessingFrames(origin)
    ): FrameCapacityResolution {
        if (captureMode == CaptureMode.SINGLE) {
            return forcedResolution(
                purpose = FrameCapacityPurpose.PROCESSING_FRAMES,
                origin = origin,
                captureMode = captureMode,
                requestedValue = requestedValue,
                configuredMaximum = configuredMaximum,
                runtimeSafeMaximum = runtimeSafeMaximum,
                productMaximum = 1,
                forcedValue = 1.coerceAtMost(runtimeSafeMaximum.coerceAtLeast(1)),
                reason = FrameCapacityResolutionReason.SINGLE_FRAME_RESTRICTION
            )
        }
        return resolve(
            purpose = FrameCapacityPurpose.PROCESSING_FRAMES,
            origin = origin,
            captureMode = captureMode,
            requestedValue = requestedValue,
            configuredMaximum = configuredMaximum,
            runtimeSafeMaximum = runtimeSafeMaximum,
            productMaximum = maximumProcessingFrames(origin)
        )
    }

    fun resolveDngMasterFrames(
        origin: FrameOrigin,
        captureMode: CaptureMode,
        requestedValue: Int,
        runtimeSafeMaximum: Int,
        configuredMaximum: Int = maximumDngMasterFrames(origin),
        outputPolicy: OutputPolicy = OutputPolicy.JPEG_PLUS_RAW
    ): FrameCapacityResolution {
        if (origin == FrameOrigin.YUV) {
            return forcedResolution(
                purpose = FrameCapacityPurpose.DNG_MASTER_FRAMES,
                origin = origin,
                captureMode = captureMode,
                requestedValue = requestedValue,
                configuredMaximum = 0,
                runtimeSafeMaximum = runtimeSafeMaximum,
                productMaximum = 0,
                forcedValue = 0,
                reason = FrameCapacityResolutionReason.SOURCE_NOT_APPLICABLE
            )
        }
        if (!outputPolicy.producesRaw) {
            return forcedResolution(
                purpose = FrameCapacityPurpose.DNG_MASTER_FRAMES,
                origin = origin,
                captureMode = captureMode,
                requestedValue = 0,
                configuredMaximum = configuredMaximum,
                runtimeSafeMaximum = runtimeSafeMaximum,
                productMaximum = 0,
                forcedValue = 0,
                reason = FrameCapacityResolutionReason.OUTPUT_NOT_APPLICABLE
            )
        }
        if (captureMode == CaptureMode.SINGLE) {
            return forcedResolution(
                purpose = FrameCapacityPurpose.DNG_MASTER_FRAMES,
                origin = origin,
                captureMode = captureMode,
                requestedValue = requestedValue,
                configuredMaximum = configuredMaximum,
                runtimeSafeMaximum = runtimeSafeMaximum,
                productMaximum = 1,
                forcedValue = 1.coerceAtMost(runtimeSafeMaximum.coerceAtLeast(1)),
                reason = FrameCapacityResolutionReason.SINGLE_FRAME_RESTRICTION
            )
        }
        return resolve(
            purpose = FrameCapacityPurpose.DNG_MASTER_FRAMES,
            origin = origin,
            captureMode = captureMode,
            requestedValue = requestedValue,
            configuredMaximum = configuredMaximum,
            runtimeSafeMaximum = runtimeSafeMaximum,
            productMaximum = maximumDngMasterFrames(origin)
        )
    }

    private fun resolve(
        purpose: FrameCapacityPurpose,
        origin: FrameOrigin,
        captureMode: CaptureMode,
        requestedValue: Int,
        configuredMaximum: Int,
        runtimeSafeMaximum: Int,
        productMaximum: Int
    ): FrameCapacityResolution {
        val safeRequested = requestedValue.coerceAtLeast(1)
        val safeConfigured = configuredMaximum.coerceAtLeast(1)
        val safeRuntime = runtimeSafeMaximum.coerceAtLeast(1)
        val safeProduct = productMaximum.coerceAtLeast(1)
        val effective = minOf(safeRequested, safeConfigured, safeRuntime, safeProduct)
        val reasons = buildList {
            if (safeRequested > safeConfigured) add(FrameCapacityResolutionReason.CONFIGURED_MAXIMUM)
            if (safeRequested > safeRuntime) add(FrameCapacityResolutionReason.RUNTIME_SAFE_MAXIMUM)
            if (safeRequested > safeProduct) add(FrameCapacityResolutionReason.PRODUCT_SOURCE_MAXIMUM)
        }.distinct()
        return FrameCapacityResolution(
            purpose = purpose,
            frameOrigin = origin,
            captureMode = captureMode,
            requestedValue = requestedValue.coerceAtLeast(0),
            configuredMaximum = configuredMaximum.coerceAtLeast(0),
            runtimeSafeMaximum = runtimeSafeMaximum.coerceAtLeast(0),
            productMaximum = productMaximum.coerceAtLeast(0),
            effectiveValue = effective,
            resolutionReason = when (reasons.size) {
                0 -> FrameCapacityResolutionReason.AS_REQUESTED
                1 -> reasons.single()
                else -> FrameCapacityResolutionReason.MULTIPLE_LIMITS
            },
            limitingReasons = reasons
        )
    }

    private fun forcedResolution(
        purpose: FrameCapacityPurpose,
        origin: FrameOrigin,
        captureMode: CaptureMode,
        requestedValue: Int,
        configuredMaximum: Int,
        runtimeSafeMaximum: Int,
        productMaximum: Int,
        forcedValue: Int,
        reason: FrameCapacityResolutionReason
    ) = FrameCapacityResolution(
        purpose = purpose,
        frameOrigin = origin,
        captureMode = captureMode,
        requestedValue = requestedValue.coerceAtLeast(0),
        configuredMaximum = configuredMaximum.coerceAtLeast(0),
        runtimeSafeMaximum = runtimeSafeMaximum.coerceAtLeast(0),
        productMaximum = productMaximum.coerceAtLeast(0),
        effectiveValue = forcedValue.coerceAtLeast(0),
        resolutionReason = if (
            requestedValue == forcedValue &&
            reason != FrameCapacityResolutionReason.SOURCE_NOT_APPLICABLE &&
            reason != FrameCapacityResolutionReason.OUTPUT_NOT_APPLICABLE
        ) {
            FrameCapacityResolutionReason.AS_REQUESTED
        } else {
            reason
        },
        limitingReasons = if (
            requestedValue == forcedValue &&
            reason != FrameCapacityResolutionReason.SOURCE_NOT_APPLICABLE &&
            reason != FrameCapacityResolutionReason.OUTPUT_NOT_APPLICABLE
        ) {
            emptyList()
        } else {
            listOf(reason)
        }
    )
}
