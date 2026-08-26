package com.bncam.core.capture

import android.hardware.camera2.CaptureResult
import com.bncam.core.buffer.ZslFramePair
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class PredictiveAfEstimate(
    val predictedFocusDistance: Float,
    val focusVelocityDioptersPerSec: Float,
    val isMovingTowardsCamera: Boolean,
    val confidence: Float
)

/**
 * Short-horizon lens-trajectory estimator. It predicts only from verified, temporally ordered
 * warm-buffer metadata and never overrides Camera2 AF by itself.
 */
class PredictiveAfTracker {
    private val maxHistory = 12
    private val history = mutableListOf<FocusHistoryPoint>()

    data class FocusHistoryPoint(
        val timestampNs: Long,
        val focusDistance: Float,
        val focusScore: Float,
        val focusConfidence: Float,
        val lensMoving: Boolean
    )

    @Synchronized
    fun updateHistory(frame: ZslFramePair) {
        val distance = frame.lensFocusDistance
        if (frame.timestamp <= 0L || !distance.isFinite() || distance < 0f) return
        // 0 diopters is a valid Camera2 focus position (optical infinity) and must remain usable.
        val lastTimestampNs = history.lastOrNull()?.timestampNs
        if (lastTimestampNs != null && frame.timestamp <= lastTimestampNs) return

        history.add(
            FocusHistoryPoint(
                timestampNs = frame.timestamp,
                focusDistance = distance,
                focusScore = frame.focusScore.coerceAtLeast(0f),
                focusConfidence = frame.focusConfidence.coerceIn(0f, 1f),
                lensMoving = frame.lensState == CaptureResult.LENS_STATE_MOVING
            )
        )
        if (history.size > maxHistory) history.removeAt(0)
    }

    @Synchronized
    fun predictFocusDistance(targetTimestampNs: Long): PredictiveAfEstimate {
        if (history.size < 3) return stationaryFallback()

        val recent = history.takeLast(8)
        val newest = recent.last()
        val oldest = recent.first()
        val spanSec = (newest.timestampNs - oldest.timestampNs) / 1_000_000_000f
        if (spanSec !in 0.015f..1.5f) return stationaryFallback()

        // Least-squares slope is less sensitive to a single AF metadata jump than endpoint-only
        // velocity, while remaining tiny control/statistics work.
        val t0 = oldest.timestampNs
        var sumT = 0.0
        var sumD = 0.0
        for (point in recent) {
            sumT += (point.timestampNs - t0) / 1_000_000_000.0
            sumD += point.focusDistance.toDouble()
        }
        val meanT = sumT / recent.size
        val meanD = sumD / recent.size
        var covariance = 0.0
        var timeVariance = 0.0
        var residualSq = 0.0
        for (point in recent) {
            val t = (point.timestampNs - t0) / 1_000_000_000.0
            covariance += (t - meanT) * (point.focusDistance - meanD)
            timeVariance += (t - meanT) * (t - meanT)
        }
        if (timeVariance <= 1e-9) return stationaryFallback()
        val velocity = (covariance / timeVariance).toFloat()
        for (point in recent) {
            val t = (point.timestampNs - t0) / 1_000_000_000.0
            val fitted = meanD + velocity * (t - meanT)
            val residual = point.focusDistance - fitted
            residualSq += residual * residual
        }
        val rmsResidual = sqrt(residualSq / recent.size).toFloat()

        val projectionSec = ((targetTimestampNs - newest.timestampNs) / 1_000_000_000f)
            .coerceIn(0f, 0.20f)
        val predicted = max(0f, newest.focusDistance + velocity * projectionSec)

        val sampleFactor = ((recent.size - 2) / 6f).coerceIn(0f, 1f)
        val spanFactor = (spanSec / 0.18f).coerceIn(0f, 1f)
        val qualityFactor = recent.map { it.focusConfidence }.average().toFloat().coerceIn(0f, 1f)
        val residualFactor = (1f - rmsResidual / 0.35f).coerceIn(0f, 1f)
        val movingPenalty = if (recent.last().lensMoving) 0.72f else 1f
        val futureAgePenalty = (1f - projectionSec / 0.30f).coerceIn(0.45f, 1f)
        val confidence = (
            sampleFactor * spanFactor * (0.35f + 0.65f * qualityFactor) *
                residualFactor * movingPenalty * futureAgePenalty
            ).coerceIn(0f, 1f)

        return PredictiveAfEstimate(
            predictedFocusDistance = predicted,
            focusVelocityDioptersPerSec = velocity,
            isMovingTowardsCamera = velocity > 0f,
            confidence = confidence
        )
    }

    @Synchronized
    fun clear() {
        history.clear()
    }

    private fun stationaryFallback(): PredictiveAfEstimate {
        val last = history.lastOrNull()
        return PredictiveAfEstimate(
            predictedFocusDistance = last?.focusDistance ?: 0f,
            focusVelocityDioptersPerSec = 0f,
            isMovingTowardsCamera = false,
            confidence = 0f
        )
    }
}
