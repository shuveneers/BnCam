package com.bncam.core.quality

import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

enum class FocusConfidenceState {
    CONFIDENT_SHARP,
    CONFIDENT_SOFT,
    INDETERMINATE
}

data class FocusMetrics(
    val focusScore: Float,
    val focusConfidence: Float,
    val confidenceState: FocusConfidenceState,
    val afState: Int,
    val lensFocusDistance: Float,
    val lensState: Int,
    val afRegion: Rect?,
    val timestamp: Long,
    val isNoiseDominated: Boolean = false,
    val subjectRoiUsed: Boolean = false
)

/**
 * Lightweight focus evidence for near-ZSL selection and AF verification.
 *
 * This deliberately stays a bounded statistics pass rather than a full-frame image-processing
 * stage: expensive live image reconstruction remains GPU/Vulkan-owned. The analyzer samples a
 * fixed-order budget from the exact warm-buffer frame so capture ranking never needs a second
 * full-resolution copy or conversion.
 */
object FocusConfidenceEngine {
    private const val TARGET_SAMPLE_LOCATIONS = 12_000
    private const val MIN_NORMALIZED_CONTRAST = 0.025f
    private const val SHARP_DETAIL_RATIO = 0.115f
    private const val SOFT_DETAIL_RATIO = 0.060f

    fun evaluate(
        image: Image,
        metadata: TotalCaptureResult?,
        roiSensor: Rect? = null,
        activeSensorArray: Rect? = null,
        motionPenalty: Float = 0f
    ): FocusMetrics {
        val timestamp = metadata?.get(CaptureResult.SENSOR_TIMESTAMP) ?: image.timestamp
        val afState = metadata?.get(CaptureResult.CONTROL_AF_STATE)
            ?: CaptureResult.CONTROL_AF_STATE_INACTIVE
        val lensDistance = metadata?.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
        val lensState = metadata?.get(CaptureResult.LENS_STATE)
            ?: CaptureResult.LENS_STATE_STATIONARY
        val iso = metadata?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100

        val subjectRoiUsed = roiSensor != null && !roiSensor.isEmpty
        val roi = calculateImageRoi(image, roiSensor, activeSensorArray)
        val evidence = when (image.format) {
            ImageFormat.YUV_420_888 -> evaluateYuvPlane(image, roi)
            ImageFormat.RAW10 -> evaluateRaw10Plane(image, roi)
            ImageFormat.RAW_SENSOR -> evaluateRawSensorPlane(image, roi)
            else -> FocusEvidence.EMPTY
        }

        val noiseFloor = genericNormalizedNoiseFloor(iso)
        val noiseAdjustedDetail = max(0f, evidence.detail - noiseFloor)
        val detailRatio = noiseAdjustedDetail / max(evidence.contrast, 0.015f)
        val noiseShare = if (evidence.detail > 1e-6f) noiseFloor / evidence.detail else 1f
        val isNoiseDominated = evidence.contrast < MIN_NORMALIZED_CONTRAST || noiseShare >= 0.72f

        val confidenceState = when {
            evidence.sampleCount < 64 || isNoiseDominated -> FocusConfidenceState.INDETERMINATE
            detailRatio >= SHARP_DETAIL_RATIO -> FocusConfidenceState.CONFIDENT_SHARP
            detailRatio <= SOFT_DETAIL_RATIO -> FocusConfidenceState.CONFIDENT_SOFT
            else -> FocusConfidenceState.INDETERMINATE
        }

        val textureConfidence = ((evidence.contrast - MIN_NORMALIZED_CONTRAST) / 0.10f)
            .coerceIn(0f, 1f)
        val sharpnessConfidence = when (confidenceState) {
            FocusConfidenceState.CONFIDENT_SHARP ->
                ((detailRatio - SHARP_DETAIL_RATIO) / 0.20f + 0.60f).coerceIn(0f, 1f)
            FocusConfidenceState.CONFIDENT_SOFT ->
                ((SOFT_DETAIL_RATIO - detailRatio) / SOFT_DETAIL_RATIO * 0.40f + 0.55f)
                    .coerceIn(0f, 1f)
            FocusConfidenceState.INDETERMINATE -> 0.25f * textureConfidence
        }
        val lensSettledFactor = if (lensState == CaptureResult.LENS_STATE_MOVING) 0.55f else 1f
        val motionFactor = 1f - motionPenalty.coerceIn(0f, 0.80f)
        val confidence = (sharpnessConfidence * textureConfidence * lensSettledFactor * motionFactor)
            .coerceIn(0f, 1f)

        return FocusMetrics(
            focusScore = noiseAdjustedDetail,
            focusConfidence = confidence,
            confidenceState = confidenceState,
            afState = afState,
            lensFocusDistance = lensDistance,
            lensState = lensState,
            afRegion = roiSensor?.let(::Rect),
            timestamp = timestamp,
            isNoiseDominated = isNoiseDominated,
            subjectRoiUsed = subjectRoiUsed
        )
    }

    private data class FocusEvidence(
        val detail: Float,
        val contrast: Float,
        val sampleCount: Int
    ) {
        companion object {
            val EMPTY = FocusEvidence(0f, 0f, 0)
        }
    }

    private fun genericNormalizedNoiseFloor(iso: Int): Float {
        val isoGain = sqrt((iso.coerceAtLeast(50) / 100f).coerceAtLeast(0.5f))
        return (0.0015f * isoGain).coerceIn(0.0010f, 0.018f)
    }

    private fun calculateImageRoi(image: Image, roiSensor: Rect?, activeSensorArray: Rect?): Rect {
        val width = image.width
        val height = image.height
        if (width < 8 || height < 8) return Rect(0, 0, width, height)

        if (roiSensor != null && activeSensorArray != null &&
            activeSensorArray.width() > 0 && activeSensorArray.height() > 0
        ) {
            val clipped = Rect(roiSensor)
            if (clipped.intersect(activeSensorArray) && !clipped.isEmpty) {
                val scaleX = width.toFloat() / activeSensorArray.width().toFloat()
                val scaleY = height.toFloat() / activeSensorArray.height().toFloat()
                val left = ((clipped.left - activeSensorArray.left) * scaleX).toInt()
                    .coerceIn(0, width - 4)
                val top = ((clipped.top - activeSensorArray.top) * scaleY).toInt()
                    .coerceIn(0, height - 4)
                val right = ((clipped.right - activeSensorArray.left) * scaleX).toInt()
                    .coerceIn(left + 4, width)
                val bottom = ((clipped.bottom - activeSensorArray.top) * scaleY).toInt()
                    .coerceIn(top + 4, height)
                return Rect(left, top, right, bottom)
            }
        }

        val marginX = width / 5
        val marginY = height / 5
        return Rect(marginX, marginY, width - marginX, height - marginY)
    }

    private fun boundedStep(roi: Rect, cfaEven: Boolean): Int {
        val locations = roi.width().toLong().coerceAtLeast(1L) * roi.height().toLong().coerceAtLeast(1L)
        var step = ceil(sqrt(locations.toDouble() / TARGET_SAMPLE_LOCATIONS.toDouble())).toInt()
            .coerceAtLeast(if (cfaEven) 2 else 1)
        if (cfaEven && step % 2 != 0) step++
        return step
    }

    private fun evaluateYuvPlane(image: Image, roi: Rect): FocusEvidence {
        val plane = image.planes.firstOrNull() ?: return FocusEvidence.EMPTY
        val buffer = plane.buffer ?: return FocusEvidence.EMPTY
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val step = boundedStep(roi, cfaEven = false)

        var sumLaplacian = 0.0
        var sumPixel = 0.0
        var sumPixelSq = 0.0
        var count = 0

        val startX = roi.left.coerceIn(1, image.width - 2)
        val endX = roi.right.coerceIn(startX + 1, image.width - 1)
        val startY = roi.top.coerceIn(1, image.height - 2)
        val endY = roi.bottom.coerceIn(startY + 1, image.height - 1)

        for (y in startY until endY step step) {
            val row = y * rowStride
            val above = (y - 1) * rowStride
            val below = (y + 1) * rowStride
            for (x in startX until endX step step) {
                val center = u8(buffer, row + x * pixelStride)
                val left = u8(buffer, row + (x - 1) * pixelStride)
                val right = u8(buffer, row + (x + 1) * pixelStride)
                val top = u8(buffer, above + x * pixelStride)
                val bottom = u8(buffer, below + x * pixelStride)
                sumLaplacian += abs(4 * center - left - right - top - bottom)
                sumPixel += center
                sumPixelSq += center.toDouble() * center.toDouble()
                count++
            }
        }
        if (count == 0) return FocusEvidence.EMPTY
        val mean = sumPixel / count
        val variance = max(0.0, sumPixelSq / count - mean * mean)
        return FocusEvidence(
            detail = (sumLaplacian / count / 1020.0).toFloat(),
            contrast = (sqrt(variance) / 255.0).toFloat(),
            sampleCount = count
        )
    }

    private fun evaluateRaw10Plane(image: Image, roi: Rect): FocusEvidence {
        val plane = image.planes.firstOrNull() ?: return FocusEvidence.EMPTY
        val buffer = plane.buffer ?: return FocusEvidence.EMPTY
        val rowStride = plane.rowStride
        val step = boundedStep(roi, cfaEven = true)
        return evaluateCfa(
            image = image,
            roi = roi,
            step = step,
            read = { x, y -> readRaw10(buffer, rowStride, x, y) }
        )
    }

    private fun evaluateRawSensorPlane(image: Image, roi: Rect): FocusEvidence {
        val plane = image.planes.firstOrNull() ?: return FocusEvidence.EMPTY
        val buffer = plane.buffer?.duplicate()?.order(ByteOrder.nativeOrder()) ?: return FocusEvidence.EMPTY
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.takeIf { it >= 2 } ?: 2
        val step = boundedStep(roi, cfaEven = true)
        return evaluateCfa(
            image = image,
            roi = roi,
            step = step,
            read = { x, y ->
                val offset = y * rowStride + x * pixelStride
                if (offset < 0 || offset + 1 >= buffer.limit()) 0
                else buffer.getShort(offset).toInt() and 0xFFFF
            }
        )
    }

    private inline fun evaluateCfa(
        image: Image,
        roi: Rect,
        step: Int,
        read: (x: Int, y: Int) -> Int
    ): FocusEvidence {
        val startX = ((roi.left + 1) / 2 * 2).coerceIn(2, image.width - 6)
        val endX = (roi.right / 2 * 2).coerceIn(startX + 2, image.width - 4)
        val startY = ((roi.top + 1) / 2 * 2).coerceIn(2, image.height - 6)
        val endY = (roi.bottom / 2 * 2).coerceIn(startY + 2, image.height - 4)

        var sumGradient = 0.0
        var sumPixel = 0.0
        var sumPixelSq = 0.0
        var peak = 0
        var count = 0

        for (y in startY until endY step step) {
            for (x in startX until endX step step) {
                // Evaluate every site in the local 2x2 Bayer cell. The previous implementation
                // sampled only the even/even phase, so RAW focus confidence could accidentally be
                // driven by only R, G or B depending on the sensor CFA origin. Peaking/AF evidence
                // must be phase-neutral and must not change character between CFA layouts.
                for (phaseY in 0..1) {
                    for (phaseX in 0..1) {
                        val px = x + phaseX
                        val py = y + phaseY
                        val center = read(px, py)
                        val right = read(px + 2, py)
                        val down = read(px, py + 2)
                        if (center <= 0 && right <= 0 && down <= 0) continue
                        sumGradient += (abs(center - right) + abs(center - down)) * 0.5
                        sumPixel += center
                        sumPixelSq += center.toDouble() * center.toDouble()
                        peak = max(peak, max(center, max(right, down)))
                        count++
                    }
                }
            }
        }
        if (count == 0 || peak <= 0) return FocusEvidence.EMPTY
        val mean = sumPixel / count
        val variance = max(0.0, sumPixelSq / count - mean * mean)
        val scale = peak.toDouble().coerceAtLeast(64.0)
        return FocusEvidence(
            detail = (sumGradient / count / scale).toFloat().coerceIn(0f, 1f),
            contrast = (sqrt(variance) / scale).toFloat().coerceIn(0f, 1f),
            sampleCount = count
        )
    }

    private fun readRaw10(buffer: ByteBuffer, rowStride: Int, x: Int, y: Int): Int {
        if (x < 0 || y < 0) return 0
        val group = x / 4
        val lane = x and 3
        val base = y * rowStride + group * 5
        val lowIndex = base + 4
        val msbIndex = base + lane
        if (base < 0 || lowIndex >= buffer.limit() || msbIndex >= buffer.limit()) return 0
        val msb = u8(buffer, msbIndex)
        val packedLow = u8(buffer, lowIndex)
        val low = (packedLow shr (lane * 2)) and 0x03
        return (msb shl 2) or low
    }

    private fun u8(buffer: ByteBuffer, offset: Int): Int {
        if (offset < 0 || offset >= buffer.limit()) return 0
        return buffer.get(offset).toInt() and 0xFF
    }
}
