package com.bncam.core.capture

import android.graphics.RectF
import android.media.Image
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.Subject
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Immutable capture-ready confidence mask for one foreground subject.
 *
 * The Float32 confidence values are copied byte-for-byte from ML Kit into a direct buffer. BnCam
 * deliberately performs no thresholding, feathering, blur, morphology or other pixel processing
 * on the CPU; those operations belong to the Vulkan portrait pipeline.
 */
data class PortraitMaskArtifact(
    val confidenceMask: ByteBuffer,
    val maskWidth: Int,
    val maskHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val subjectBoundsNormalized: RectF,
    val timestampNs: Long,
    val rotationDegrees: Int,
    val selectionReason: String
) {
    val floatCount: Int get() = maskWidth * maskHeight

    fun duplicateMask(): ByteBuffer = confidenceMask.asReadOnlyBuffer()
        .order(ByteOrder.nativeOrder())
        .apply { position(0) }

    fun ageMs(nowNs: Long): Long = ((nowNs - timestampNs).coerceAtLeast(0L) / 1_000_000L)
}

/** Selection input in the same normalized, rotation-corrected viewfinder space as Focus Track. */
data class PortraitSubjectSeed(
    val tapX: Float? = null,
    val tapY: Float? = null,
    val trackedBounds: RectF? = null
)

/**
 * Semantic subject acquisition only. All visual mask refinement and bokeh are Vulkan-owned.
 * Multiple-subject confidence masks let a tap/Focus Track target select a cup, pet, person or
 * other prominent foreground object instead of treating the entire foreground as one blob.
 */
class PortraitSubjectSegmenter {
    private val subjectResultOptions = SubjectSegmenterOptions.SubjectResultOptions.Builder()
        .enableConfidenceMask()
        .build()

    private val options = SubjectSegmenterOptions.Builder()
        .enableMultipleSubjects(subjectResultOptions)
        .build()

    private val segmenter: SubjectSegmenter = SubjectSegmentation.getClient(options)

    init {
        // Warm the optional Play-services model in the background. Failure is fail-safe: captures
        // remain normal images until a mask becomes available.
        segmenter.initTask
    }

    suspend fun segmentMediaImage(
        image: Image,
        rotationDegrees: Int,
        timestampNs: Long,
        seed: PortraitSubjectSeed
    ): PortraitMaskArtifact? {
        val input = InputImage.fromMediaImage(image, rotationDegrees)
        val sourceWidth = if (rotationDegrees == 90 || rotationDegrees == 270) image.height else image.width
        val sourceHeight = if (rotationDegrees == 90 || rotationDegrees == 270) image.width else image.height
        return segment(input, sourceWidth, sourceHeight, timestampNs, rotationDegrees, seed)
    }

    suspend fun segmentNv21(
        bytes: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        timestampNs: Long,
        seed: PortraitSubjectSeed
    ): PortraitMaskArtifact? {
        val input = InputImage.fromByteArray(
            bytes,
            width,
            height,
            rotationDegrees,
            InputImage.IMAGE_FORMAT_NV21
        )
        val sourceWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
        val sourceHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
        return segment(input, sourceWidth, sourceHeight, timestampNs, rotationDegrees, seed)
    }

    private suspend fun segment(
        input: InputImage,
        sourceWidth: Int,
        sourceHeight: Int,
        timestampNs: Long,
        rotationDegrees: Int,
        seed: PortraitSubjectSeed
    ): PortraitMaskArtifact? {
        if (sourceWidth <= 1 || sourceHeight <= 1) return null
        val result = suspendCancellableCoroutine { continuation ->
            segmenter.process(input)
                .addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
                .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
        }
        val selected = selectSubject(result.subjects, sourceWidth, sourceHeight, seed) ?: return null
        val mask = selected.subject.confidenceMask ?: return null
        val expectedFloats = selected.subject.width * selected.subject.height
        if (expectedFloats <= 0 || mask.capacity() < expectedFloats) return null

        val direct = ByteBuffer.allocateDirect(expectedFloats * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        val destination = direct.asFloatBuffer()
        val source = mask.duplicate().apply {
            position(0)
            limit(expectedFloats)
        }
        destination.put(source)
        direct.position(0)
        direct.limit(expectedFloats * Float.SIZE_BYTES)

        return PortraitMaskArtifact(
            confidenceMask = direct.asReadOnlyBuffer().order(ByteOrder.nativeOrder()),
            maskWidth = selected.subject.width,
            maskHeight = selected.subject.height,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            subjectBoundsNormalized = selected.bounds,
            timestampNs = timestampNs,
            rotationDegrees = rotationDegrees,
            selectionReason = selected.reason
        )
    }

    private data class Candidate(
        val subject: Subject,
        val bounds: RectF,
        val reason: String,
        val score: Float
    )

    private fun selectSubject(
        subjects: List<Subject>,
        sourceWidth: Int,
        sourceHeight: Int,
        seed: PortraitSubjectSeed
    ): Candidate? {
        if (subjects.isEmpty()) return null
        val candidates = subjects.mapNotNull { subject ->
            if (subject.width <= 0 || subject.height <= 0) return@mapNotNull null
            val left = subject.startX.toFloat() / sourceWidth
            val top = subject.startY.toFloat() / sourceHeight
            val right = (subject.startX + subject.width).toFloat() / sourceWidth
            val bottom = (subject.startY + subject.height).toFloat() / sourceHeight
            val bounds = RectF(
                left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
                right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f)
            )
            if (bounds.width() <= 0f || bounds.height() <= 0f) null
            else Candidate(subject, bounds, "AUTO_PROMINENT", 0f)
        }
        if (candidates.isEmpty()) return null

        val tracked = seed.trackedBounds?.takeUnless { it.isEmpty }
        if (tracked != null) {
            return candidates.maxByOrNull { candidate ->
                3.2f * intersectionOverUnion(candidate.bounds, tracked) -
                    0.8f * centerDistance(candidate.bounds, tracked)
            }?.let { it.copy(reason = "FOCUS_TRACK_TARGET") }
        }

        val tapX = seed.tapX
        val tapY = seed.tapY
        if (tapX != null && tapY != null) {
            val contained = candidates.filter { it.bounds.contains(tapX, tapY) }
            if (contained.isNotEmpty()) {
                // Prefer the most specific subject containing the actual tap, rather than a large
                // foreground region that happens to overlap it.
                return contained.minByOrNull { it.bounds.width() * it.bounds.height() }
                    ?.copy(reason = "TAP_TARGET")
            }
            val nearest = candidates.minByOrNull { pointToRectDistance(tapX, tapY, it.bounds) }
            if (nearest != null && pointToRectDistance(tapX, tapY, nearest.bounds) <= 0.22f) {
                return nearest.copy(reason = "TAP_NEAREST_TARGET")
            }
        }

        return candidates.maxByOrNull { candidate ->
            val area = candidate.bounds.width() * candidate.bounds.height()
            val centerX = candidate.bounds.centerX() - 0.5f
            val centerY = candidate.bounds.centerY() - 0.5f
            val centerPenalty = kotlin.math.sqrt(centerX * centerX + centerY * centerY)
            area * 1.35f - centerPenalty * 0.22f
        }?.copy(reason = "AUTO_PROMINENT")
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 1e-6f) intersection / union else 0f
    }

    private fun centerDistance(a: RectF, b: RectF): Float {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun pointToRectDistance(x: Float, y: Float, rect: RectF): Float {
        val dx = when {
            x < rect.left -> rect.left - x
            x > rect.right -> x - rect.right
            else -> 0f
        }
        val dy = when {
            y < rect.top -> rect.top - y
            y > rect.bottom -> y - rect.bottom
            else -> 0f
        }
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun close() = segmenter.close()
}

/** Immutable shutter-time ownership of the latest semantic mask. */
data class PortraitCaptureContext(
    val requested: Boolean = false,
    val mask: PortraitMaskArtifact? = null,
    val targetBoundsNormalized: RectF? = null,
    val status: String = "DISABLED"
) {
    val available: Boolean get() = requested && mask != null && targetBoundsNormalized != null
}
