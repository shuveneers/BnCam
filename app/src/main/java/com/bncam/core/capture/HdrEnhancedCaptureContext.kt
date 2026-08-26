package com.bncam.core.capture

import com.bncam.core.buffer.FrameLease
import java.util.Locale

/**
 * One exact Camera2 frame from a deliberate HDR Enhanced post-shutter acquisition.
 * All quality values are nullable by design: unavailable evidence is never replaced by a
 * plausible default.
 */
data class HdrEnhancedFrame(
    val index: Int,
    val semanticTag: String,
    val lease: FrameLease,
    val actualExposureTimeNs: Long,
    val actualSensitivityIso: Int,
    val timestampNs: Long,
    val requestSubmittedElapsedRealtimeNs: Long,
    val captureSequenceId: Int,
    val captureFrameNumber: Long,
    val sharpnessScore: Float?,
    val motionScore: Float?,
    val clippingFraction: Float?,
    val focusConfidence: Float?,
    val provenanceValid: Boolean,
    val isAuxiliary: Boolean
) : AutoCloseable {
    override fun close() {
        lease.release()
    }
}

/**
 * Scalar-only best-base policy. It never reads full images on CPU and never fabricates missing
 * sharpness, motion, focus, clipping, or alignment confidence.
 */
object HdrEnhancedBaseSelector {

    data class CandidateScore(
        val index: Int,
        val compositeScore: Float?,
        val sharpness: Float?,
        val motion: Float?,
        val focusConfidence: Float?,
        val alignmentConfidence: Float?,
        val evidenceWeight: Float
    )

    data class BaseSelectionResult(
        val selectedBaseIndex: Int,
        val candidateScores: List<CandidateScore>,
        val selectionReason: String,
        val measuredEvidenceAvailable: Boolean
    )

    private fun format(value: Float?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.3f", it) } ?: "UNAVAILABLE"

    fun selectBestBase(
        mainFrames: List<HdrEnhancedFrame>,
        pairwiseAlignmentConfidences: Map<Int, Float> = emptyMap()
    ): BaseSelectionResult {
        if (mainFrames.isEmpty()) {
            return BaseSelectionResult(
                selectedBaseIndex = -1,
                candidateScores = emptyList(),
                selectionReason = "no_main_frames",
                measuredEvidenceAvailable = false
            )
        }

        // FocusConfidenceEngine.focusScore is a measured normalized-detail scalar, not a 0..100
        // percentage. Normalize sharpness only relative to this exact burst so no synthetic absolute
        // scale or placeholder quality value is introduced.
        val measuredSharpness = mainFrames.mapNotNull { frame ->
            frame.sharpnessScore?.takeIf { it.isFinite() && it >= 0f }
        }
        val sharpnessMax = measuredSharpness.maxOrNull()?.takeIf { it > 0f }

        val candidates = mainFrames.map { frame ->
            var weightedScore = 0f
            var weight = 0f

            val sharpness = frame.sharpnessScore
                ?.takeIf { it.isFinite() && it >= 0f }
                ?.let { value -> sharpnessMax?.let { maximum -> (value / maximum).coerceIn(0f, 1f) } }
            if (sharpness != null) {
                weightedScore += sharpness * 0.40f
                weight += 0.40f
            }

            val focus = frame.focusConfidence
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
            if (focus != null) {
                weightedScore += focus * 0.35f
                weight += 0.35f
            }

            val motion = frame.motionScore
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
            if (motion != null) {
                weightedScore += (1f - motion) * 0.15f
                weight += 0.15f
            }

            val alignment = pairwiseAlignmentConfidences[frame.index]
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
            if (alignment != null) {
                weightedScore += alignment * 0.10f
                weight += 0.10f
            }

            var normalized = if (weight > 0f) weightedScore / weight else null
            val clipping = frame.clippingFraction
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
            if (normalized != null && clipping != null && clipping > 0.05f) {
                normalized *= 0.5f
            }
            if (!frame.provenanceValid && normalized != null) {
                normalized *= 0.25f
            }

            CandidateScore(
                index = frame.index,
                compositeScore = normalized,
                sharpness = sharpness,
                motion = motion,
                focusConfidence = focus,
                alignmentConfidence = alignment,
                evidenceWeight = weight
            )
        }

        val evidenceCandidates = candidates.filter { it.compositeScore != null }
        val selected = evidenceCandidates.maxWithOrNull(
            compareBy<CandidateScore> { it.compositeScore ?: Float.NEGATIVE_INFINITY }
                .thenBy { it.evidenceWeight }
        )
        if (selected == null) {
            val temporalMiddle = mainFrames[mainFrames.lastIndex / 2].index
            return BaseSelectionResult(
                selectedBaseIndex = temporalMiddle,
                candidateScores = candidates,
                selectionReason = "measured_quality_unavailable_temporal_middle",
                measuredEvidenceAvailable = false
            )
        }

        val scoreSummary = candidates.joinToString("; ") { candidate ->
            "frame_${candidate.index}:score=${format(candidate.compositeScore)}," +
                "sharpness=${format(candidate.sharpness)},motion=${format(candidate.motion)}," +
                "focus=${format(candidate.focusConfidence)},align=${format(candidate.alignmentConfidence)}"
        }
        return BaseSelectionResult(
            selectedBaseIndex = selected.index,
            candidateScores = candidates,
            selectionReason = "measured_scalar_quality [$scoreSummary]",
            measuredEvidenceAvailable = true
        )
    }

    fun selectBestBaseIndex(
        mainFrames: List<HdrEnhancedFrame>,
        pairwiseAlignmentConfidences: Map<Int, Float> = emptyMap()
    ): Int = selectBestBase(mainFrames, pairwiseAlignmentConfidences).selectedBaseIndex
}

/**
 * Exact post-shutter HDR Enhanced acquisition. Ownership of every lease transfers atomically to
 * the processing runner once this context is handed off.
 */
class HdrEnhancedCaptureContext(
    val mainFrames: List<HdrEnhancedFrame>,
    val auxFrames: List<HdrEnhancedFrame>,
    val plan: HdrEnhancedCapturePlan,
    val userShutterTimestampNs: Long,
    val burstSubmitElapsedRealtimeNs: Long,
    val sensorTimestampSource: String,
    val selectedBaseIndex: Int,
    val baseSelectionReason: String,
    val baseSelectionMeasuredEvidenceAvailable: Boolean
) : AutoCloseable {

    val allFrames: List<HdrEnhancedFrame> = mainFrames + auxFrames

    val selectedBaseFrame: HdrEnhancedFrame
        get() = mainFrames.firstOrNull { it.index == selectedBaseIndex }
            ?: error("HDR Enhanced context has no selected base frame.")

    override fun close() {
        allFrames.forEach { frame ->
            try {
                frame.close()
            } catch (_: Exception) {
            }
        }
    }
}
