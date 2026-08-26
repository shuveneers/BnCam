package com.bncam.core.runtime

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import com.bncam.core.quality.CfaArrangementDescriptor

/**
 * Immutable RAW capture geometry derived strictly from SensorToRawBufferTransform.
 */
data class CaptureRawGeometry(
    val format: Int,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val transform: SensorToRawBufferTransform,
    val pixelArrayRect: Rect,
    val preCorrectionActiveRect: Rect,
    val activeArrayRect: Rect,
    val visibleRawRect: Rect,
    val cropLeft: Int,
    val cropTop: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val packedRowBytes: Int,
    val cfaArrangement: CfaArrangementDescriptor,
    val cfaPhaseX: Int,
    val cfaPhaseY: Int,
    val sensorOrientation: Int,
    val captureAspectRatio: Float
) {
    companion object {
        fun create(
            characteristics: CameraCharacteristics,
            format: Int,
            bufferWidth: Int,
            bufferHeight: Int,
            rowStride: Int,
            pixelStride: Int = 1
        ): CaptureRawGeometry {
            val transform = SensorToRawBufferTransform.create(
                characteristics = characteristics,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight
            )

            val packedRow = when (format) {
                android.graphics.ImageFormat.RAW10 -> (bufferWidth * 10 + 7) / 8
                android.graphics.ImageFormat.RAW_SENSOR -> bufferWidth * 2
                else -> bufferWidth
            }

            val cfaEnum = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
            val baseCfa = CfaArrangementDescriptor.from(cfaEnum)
            val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val aspect = if (transform.bufferCropHeight > 0) {
                transform.bufferCropWidth.toFloat() / transform.bufferCropHeight.toFloat()
            } else {
                4f / 3f
            }

            return CaptureRawGeometry(
                format = format,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                transform = transform,
                pixelArrayRect = transform.pixelArrayRect,
                preCorrectionActiveRect = transform.preCorrectionActiveRect,
                activeArrayRect = transform.activeArrayRect,
                visibleRawRect = transform.bufferActiveRect,
                cropLeft = transform.bufferCropLeft,
                cropTop = transform.bufferCropTop,
                cropWidth = transform.bufferCropWidth,
                cropHeight = transform.bufferCropHeight,
                rowStride = rowStride.coerceAtLeast(packedRow),
                pixelStride = pixelStride,
                packedRowBytes = packedRow,
                cfaArrangement = baseCfa,
                cfaPhaseX = transform.cfaOffsetX,
                cfaPhaseY = transform.cfaOffsetY,
                sensorOrientation = orientation,
                captureAspectRatio = aspect
            )
        }
    }
}

/**
 * Viewfinder/Preview RAW geometry handling viewport cropping, scaling, and Vulkan output dimensions independently.
 */
data class PreviewRawGeometry(
    val captureGeometry: CaptureRawGeometry,
    val displayRotation: Int,
    val previewOutputWidth: Int,
    val previewOutputHeight: Int,
    val previewAspectRatio: Float
) {
    companion object {
        fun create(
            captureGeometry: CaptureRawGeometry,
            displayRotation: Int = 0,
            targetPreviewMaxWidth: Int = RawPreviewResolutionPolicy.QUALITY_MAX_WIDTH,
            targetPreviewMaxHeight: Int = RawPreviewResolutionPolicy.QUALITY_MAX_HEIGHT
        ): PreviewRawGeometry {
            val aspect = captureGeometry.captureAspectRatio
            val targetW: Int
            val targetH: Int
            if (aspect >= 1.0f) {
                targetW = targetPreviewMaxWidth
                targetH = (targetPreviewMaxWidth / aspect).toInt().coerceAtLeast(2) and 0xFFFE
            } else {
                targetH = targetPreviewMaxHeight
                targetW = (targetPreviewMaxHeight * aspect).toInt().coerceAtLeast(2) and 0xFFFE
            }

            return PreviewRawGeometry(
                captureGeometry = captureGeometry,
                displayRotation = displayRotation,
                previewOutputWidth = targetW,
                previewOutputHeight = targetH,
                previewAspectRatio = aspect
            )
        }
    }
}

/**
 * Unified RawStreamGeometry wrapper maintaining backwards compatibility.
 */
data class RawStreamGeometry(
    val captureGeometry: CaptureRawGeometry,
    val previewGeometry: PreviewRawGeometry
) {
    /**
     * Returns a geometry contract whose local RAW origin starts at [visibleRect]. The update is
     * transactional: capture crop, transform, CFA phase, aspect ratio and preview dimensions are
     * all derived from the same rect so preview, RAW processing and DNG publication cannot drift.
     */
    fun withVisibleRawRect(visibleRect: Rect): RawStreamGeometry {
        val bounds = Rect(0, 0, captureGeometry.bufferWidth, captureGeometry.bufferHeight)
        val safe = Rect(visibleRect)
        require(safe.intersect(bounds) && safe.width() >= 2 && safe.height() >= 2) {
            "Visible RAW rect must lie inside buffer bounds: requested=$visibleRect bounds=$bounds"
        }

        val old = captureGeometry
        val deltaX = safe.left - old.cropLeft
        val deltaY = safe.top - old.cropTop
        val phaseX = (old.cfaPhaseX xor (deltaX and 1)) and 1
        val phaseY = (old.cfaPhaseY xor (deltaY and 1)) and 1
        val updatedTransform = old.transform.copy(
            bufferActiveRect = Rect(safe),
            bufferCropLeft = safe.left,
            bufferCropTop = safe.top,
            bufferCropWidth = safe.width(),
            bufferCropHeight = safe.height(),
            cfaOffsetX = phaseX,
            cfaOffsetY = phaseY
        )
        val updatedCapture = old.copy(
            transform = updatedTransform,
            visibleRawRect = Rect(safe),
            cropLeft = safe.left,
            cropTop = safe.top,
            cropWidth = safe.width(),
            cropHeight = safe.height(),
            cfaPhaseX = phaseX,
            cfaPhaseY = phaseY,
            captureAspectRatio = safe.width().toFloat() / safe.height().toFloat()
        )
        val updatedPreview = PreviewRawGeometry.create(
            captureGeometry = updatedCapture,
            displayRotation = previewGeometry.displayRotation,
            targetPreviewMaxWidth = RawPreviewResolutionPolicy.QUALITY_MAX_WIDTH,
            targetPreviewMaxHeight = RawPreviewResolutionPolicy.QUALITY_MAX_HEIGHT
        )
        return RawStreamGeometry(updatedCapture, updatedPreview)
    }
    val format: Int get() = captureGeometry.format
    val bufferWidth: Int get() = captureGeometry.bufferWidth
    val bufferHeight: Int get() = captureGeometry.bufferHeight
    val pixelArrayRect: Rect get() = captureGeometry.pixelArrayRect
    val preCorrectionActiveRect: Rect get() = captureGeometry.preCorrectionActiveRect
    val activeArrayRect: Rect get() = captureGeometry.activeArrayRect
    val visibleRawRect: Rect get() = captureGeometry.visibleRawRect
    val cropLeft: Int get() = captureGeometry.cropLeft
    val cropTop: Int get() = captureGeometry.cropTop
    val cropWidth: Int get() = captureGeometry.cropWidth
    val cropHeight: Int get() = captureGeometry.cropHeight
    val rowStride: Int get() = captureGeometry.rowStride
    val pixelStride: Int get() = captureGeometry.pixelStride
    val packedRowBytes: Int get() = captureGeometry.packedRowBytes
    val cfaArrangement: CfaArrangementDescriptor get() = captureGeometry.cfaArrangement
    val cfaPhaseX: Int get() = captureGeometry.cfaPhaseX
    val cfaPhaseY: Int get() = captureGeometry.cfaPhaseY
    val sensorOrientation: Int get() = captureGeometry.sensorOrientation
    val displayRotation: Int get() = previewGeometry.displayRotation
    val visibleAspectRatio: Float get() = captureGeometry.captureAspectRatio
    val previewOutputWidth: Int get() = previewGeometry.previewOutputWidth
    val previewOutputHeight: Int get() = previewGeometry.previewOutputHeight

    companion object {
        fun create(
            characteristics: CameraCharacteristics,
            format: Int,
            bufferWidth: Int,
            bufferHeight: Int,
            rowStride: Int,
            pixelStride: Int = 1,
            displayRotation: Int = 0,
            targetPreviewMaxWidth: Int = RawPreviewResolutionPolicy.QUALITY_MAX_WIDTH,
            targetPreviewMaxHeight: Int = RawPreviewResolutionPolicy.QUALITY_MAX_HEIGHT
        ): RawStreamGeometry {
            val capture = CaptureRawGeometry.create(
                characteristics = characteristics,
                format = format,
                bufferWidth = bufferWidth,
                bufferHeight = bufferHeight,
                rowStride = rowStride,
                pixelStride = pixelStride
            )
            val preview = PreviewRawGeometry.create(
                captureGeometry = capture,
                displayRotation = displayRotation,
                targetPreviewMaxWidth = targetPreviewMaxWidth,
                targetPreviewMaxHeight = targetPreviewMaxHeight
            )
            return RawStreamGeometry(captureGeometry = capture, previewGeometry = preview)
        }
    }

    fun debugDiagnostics(): List<Pair<String, String>> = listOf(
        "Source Dimensions" to "${bufferWidth}x${bufferHeight}",
        "Pixel Array Rect" to "${pixelArrayRect.left},${pixelArrayRect.top}-${pixelArrayRect.right},${pixelArrayRect.bottom}",
        "Pre-Correction Active Rect" to "${preCorrectionActiveRect.left},${preCorrectionActiveRect.top}-${preCorrectionActiveRect.right},${preCorrectionActiveRect.bottom}",
        "Active Array Rect" to "${activeArrayRect.left},${activeArrayRect.top}-${activeArrayRect.right},${activeArrayRect.bottom}",
        "Buffer Crop Offset/Size" to "L:$cropLeft T:$cropTop W:$cropWidth H:$cropHeight",
        "Row Stride / Packed Bytes" to "$rowStride / $packedRowBytes",
        "CFA Arrangement / Phase" to "${cfaArrangement.cfaName} (Phase +X:$cfaPhaseX, +Y:$cfaPhaseY)",
        "Sensor Orientation / Display" to "$sensorOrientation / $displayRotation",
        "Visible Aspect Ratio" to String.format(java.util.Locale.US, "%.4f", visibleAspectRatio),
        "Preview Target Output" to "${previewOutputWidth}x${previewOutputHeight}"
    )
}
