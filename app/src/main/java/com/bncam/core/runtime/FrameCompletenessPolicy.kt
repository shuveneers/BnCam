package com.bncam.core.runtime

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import com.bncam.core.capture.FrameRequestProvenance

enum class FrameCompletenessStatus(val isComplete: Boolean, val description: String) {
    COMPLETE(true, "Frame is fully valid with image hardware buffer, metadata, and provenance"),
    MISSING_PIXELS(false, "Image payload is missing or empty"),
    MISSING_HARDWARE_BUFFER(false, "HardwareBuffer handle is missing or closed"),
    MISSING_METADATA(false, "TotalCaptureResult metadata is missing"),
    INVALID_GENERATION(false, "Pipeline generation mismatch"),
    INVALID_PROVENANCE(false, "Frame request provenance status unproven or mismatched"),
    INVALID_TIMESTAMP(false, "Sensor timestamp is zero or non-monotonic"),
    UNSUPPORTED_LAYOUT(false, "Row layout or stride is invalid")
}

/**
 * Universal format-aware frame completeness evaluation policy.
 * Used identically across FrameRingBuffer, ShutterCandidateCollector, runners, and readiness gates.
 */
object FrameCompletenessPolicy {

    fun evaluate(
        timestampNs: Long,
        image: Image?,
        hardwareBuffer: HardwareBuffer?,
        metadata: TotalCaptureResult?,
        provenance: FrameRequestProvenance?,
        format: Int,
        generationId: Int,
        activeGeneration: Int,
        controlRequestEpoch: Long = 0L
    ): FrameCompletenessStatus {
        if (generationId != activeGeneration) {
            return FrameCompletenessStatus.INVALID_GENERATION
        }

        if (timestampNs <= 0L) {
            return FrameCompletenessStatus.INVALID_TIMESTAMP
        }

        if (format == ImageFormat.YUV_420_888) {
            if (image == null && hardwareBuffer == null) {
                return FrameCompletenessStatus.MISSING_PIXELS
            }
        } else {
            if (hardwareBuffer == null) {
                return FrameCompletenessStatus.MISSING_HARDWARE_BUFFER
            }
        }

        if (metadata == null) {
            return FrameCompletenessStatus.MISSING_METADATA
        }

        // Exact provenance check
        if (provenance == null ||
            provenance.identity.pipelineGeneration != generationId ||
            provenance.identity.controlRequestEpoch <= 0L ||
            (controlRequestEpoch > 0L && provenance.identity.controlRequestEpoch != controlRequestEpoch) ||
            provenance.snapshot.identity != provenance.identity
        ) {
            return FrameCompletenessStatus.INVALID_PROVENANCE
        }

        // Format-specific buffer validation
        when (format) {
            ImageFormat.RAW10, ImageFormat.RAW_SENSOR -> {
                if (hardwareBuffer != null && (hardwareBuffer.width <= 0 || hardwareBuffer.height <= 0)) {
                    return FrameCompletenessStatus.MISSING_PIXELS
                }
            }
            ImageFormat.YUV_420_888 -> {
                val hasValidPixels = (hardwareBuffer != null && hardwareBuffer.width > 0 && hardwareBuffer.height > 0) ||
                        (image != null && image.width > 0 && image.height > 0)
                if (!hasValidPixels) {
                    return FrameCompletenessStatus.MISSING_PIXELS
                }
            }
        }

        return FrameCompletenessStatus.COMPLETE
    }
}
