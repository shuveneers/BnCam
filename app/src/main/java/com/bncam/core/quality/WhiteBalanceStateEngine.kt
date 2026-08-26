package com.bncam.core.quality

import kotlin.math.abs
import kotlin.math.max

/** Camera/HAL convergence classification translated by the Camera2 owner. */
enum class WhiteBalanceConvergence {
    UNKNOWN,
    INACTIVE,
    SEARCHING,
    CONVERGED,
    LOCKED
}

/**
 * Immutable capture/viewfinder snapshot. The gain array is defensively copied at every boundary.
 */
class StableWhiteBalanceSnapshot(
    gains: FloatArray,
    val confidence: Float,
    val scopeKey: String,
    val pipelineGeneration: Int,
    val acceptedSampleCount: Int,
    val convergence: WhiteBalanceConvergence
) {
    private val storedGains: FloatArray = gains.copyOf()
    val gains: FloatArray
        get() = storedGains.copyOf()

    fun copyGains(): FloatArray = storedGains.copyOf()
}

/**
 * Process-local BnCam auto-WB state.
 *
 * This class owns only the compact 4-channel gain state. Camera2 remains the neutral estimator;
 * no Camera2 session/request lifecycle is owned here. Startup needs multiple mutually-consistent
 * observations before a new lens publishes a stable solution. Session-generation changes for the
 * same physical camera retain the previous stable solution, preventing a Settings/profile return
 * from flashing an unvalidated first-frame colour cast.
 */
class WhiteBalanceStateEngine {
    private val lock = Any()

    private var scopeKey: String? = null
    private var pipelineGeneration: Int = -1
    private var candidate: FloatArray? = null
    private var candidateConsistentSamples: Int = 0
    private var candidateConfidenceSum: Float = 0f
    private var lastCandidateConvergence: WhiteBalanceConvergence = WhiteBalanceConvergence.UNKNOWN

    private var stable: FloatArray? = null
    private var stableConfidence: Float = 0f
    private var stableAcceptedSamples: Int = 0
    private var stableConvergence: WhiteBalanceConvergence = WhiteBalanceConvergence.UNKNOWN

    fun observe(
        scopeKey: String,
        pipelineGeneration: Int,
        gains: FloatArray,
        convergence: WhiteBalanceConvergence
    ): StableWhiteBalanceSnapshot? = synchronized(lock) {
        val safe = sanitizeGains(gains) ?: return@synchronized snapshotLocked()
        transitionScope(scopeKey, pipelineGeneration)

        if (stable == null) {
            updateStartupCandidate(safe, convergence)
            val observationConfidence = convergenceConfidence(convergence)
            val meanCandidateConfidence = if (candidateConsistentSamples > 0) {
                candidateConfidenceSum / candidateConsistentSamples.toFloat()
            } else 0f
            val enoughSamples = candidateConsistentSamples >= MIN_STARTUP_SAMPLES
            val trustworthyState = convergence == WhiteBalanceConvergence.CONVERGED ||
                convergence == WhiteBalanceConvergence.LOCKED ||
                meanCandidateConfidence >= MIN_STARTUP_MEAN_CONFIDENCE
            if (enoughSamples && trustworthyState) {
                stable = candidate?.copyOf()
                stableConfidence = max(observationConfidence, meanCandidateConfidence).coerceIn(0f, 1f)
                stableAcceptedSamples = candidateConsistentSamples
                stableConvergence = convergence
            }
            return@synchronized snapshotLocked()
        }

        val previous = stable ?: return@synchronized null
        val delta = maxRelativeDelta(previous, safe)
        val observationConfidence = convergenceConfidence(convergence)

        // Hysteresis: Camera2 gain quantisation and tiny estimator oscillations should not create
        // visible colour shimmer or cause needless RAW-preview config changes.
        if (delta < HYSTERESIS_RELATIVE_DELTA) {
            stableConfidence = (0.90f * stableConfidence + 0.10f * observationConfidence)
                .coerceIn(0f, 1f)
            stableAcceptedSamples += 1
            stableConvergence = convergence
            return@synchronized snapshotLocked()
        }

        val baseAlpha = when {
            delta >= 0.35f -> 0.24f
            delta >= 0.15f -> 0.16f
            else -> 0.10f
        }
        val confidenceScale = when (convergence) {
            WhiteBalanceConvergence.CONVERGED,
            WhiteBalanceConvergence.LOCKED -> 1.0f
            WhiteBalanceConvergence.SEARCHING -> 0.55f
            WhiteBalanceConvergence.INACTIVE -> 0.35f
            WhiteBalanceConvergence.UNKNOWN -> 0.45f
        }
        val maxStepFraction = when {
            delta >= 0.35f -> 0.070f
            delta >= 0.15f -> 0.045f
            else -> 0.025f
        }
        val alpha = baseAlpha * confidenceScale
        val next = FloatArray(4) { index ->
            val desiredStep = (safe[index] - previous[index]) * alpha
            val maxStep = max(0.01f, previous[index] * maxStepFraction)
            (previous[index] + desiredStep.coerceIn(-maxStep, maxStep)).coerceIn(MIN_GAIN, MAX_GAIN)
        }
        stable = next
        stableConfidence = (0.84f * stableConfidence + 0.16f * observationConfidence)
            .coerceIn(0f, 1f)
        stableAcceptedSamples += 1
        stableConvergence = convergence
        snapshotLocked()
    }

    fun snapshot(scopeKey: String? = null): StableWhiteBalanceSnapshot? = synchronized(lock) {
        if (scopeKey != null && this.scopeKey != scopeKey) return@synchronized null
        snapshotLocked()
    }

    private fun transitionScope(nextScopeKey: String, nextGeneration: Int) {
        if (scopeKey != nextScopeKey) {
            scopeKey = nextScopeKey
            pipelineGeneration = nextGeneration
            candidate = null
            candidateConsistentSamples = 0
            candidateConfidenceSum = 0f
            lastCandidateConvergence = WhiteBalanceConvergence.UNKNOWN
            stable = null
            stableConfidence = 0f
            stableAcceptedSamples = 0
            stableConvergence = WhiteBalanceConvergence.UNKNOWN
            return
        }
        if (pipelineGeneration != nextGeneration) {
            // Same physical camera, new session/generation: keep the last stable colour solution,
            // but discard startup candidates from the retired session.
            pipelineGeneration = nextGeneration
            candidate = null
            candidateConsistentSamples = 0
            candidateConfidenceSum = 0f
            lastCandidateConvergence = WhiteBalanceConvergence.UNKNOWN
            stableConfidence = (stableConfidence * 0.90f).coerceIn(0f, 1f)
        }
    }

    private fun updateStartupCandidate(
        sample: FloatArray,
        convergence: WhiteBalanceConvergence
    ) {
        val previousCandidate = candidate
        if (previousCandidate == null || maxRelativeDelta(previousCandidate, sample) > STARTUP_CONSISTENCY_DELTA) {
            candidate = sample.copyOf()
            candidateConsistentSamples = 1
            candidateConfidenceSum = convergenceConfidence(convergence)
            lastCandidateConvergence = convergence
            return
        }
        val alpha = if (convergence == WhiteBalanceConvergence.CONVERGED ||
            convergence == WhiteBalanceConvergence.LOCKED) 0.45f else 0.30f
        candidate = FloatArray(4) { index ->
            (previousCandidate[index] + (sample[index] - previousCandidate[index]) * alpha)
                .coerceIn(MIN_GAIN, MAX_GAIN)
        }
        candidateConsistentSamples += 1
        candidateConfidenceSum += convergenceConfidence(convergence)
        lastCandidateConvergence = convergence
    }

    private fun snapshotLocked(): StableWhiteBalanceSnapshot? {
        val gains = stable ?: return null
        val scope = scopeKey ?: return null
        return StableWhiteBalanceSnapshot(
            gains = gains.copyOf(),
            confidence = stableConfidence,
            scopeKey = scope,
            pipelineGeneration = pipelineGeneration,
            acceptedSampleCount = stableAcceptedSamples,
            convergence = stableConvergence
        )
    }

    private fun convergenceConfidence(convergence: WhiteBalanceConvergence): Float = when (convergence) {
        WhiteBalanceConvergence.LOCKED -> 1.0f
        WhiteBalanceConvergence.CONVERGED -> 0.95f
        WhiteBalanceConvergence.SEARCHING -> 0.45f
        WhiteBalanceConvergence.UNKNOWN -> 0.35f
        WhiteBalanceConvergence.INACTIVE -> 0.20f
    }

    private fun sanitizeGains(gains: FloatArray): FloatArray? {
        if (gains.size < 4) return null
        val out = gains.copyOf(4)
        if (out.any { !it.isFinite() || it !in MIN_GAIN..MAX_GAIN }) return null
        val green = max(0.25f, 0.5f * (out[1] + out[2]))
        val redBlueRatio = out[0] / max(0.01f, out[3])
        if (redBlueRatio !in MIN_RED_BLUE_RATIO..MAX_RED_BLUE_RATIO) return null
        if (out[0] / green !in MIN_CHROMATIC_TO_GREEN..MAX_CHROMATIC_TO_GREEN) return null
        if (out[3] / green !in MIN_CHROMATIC_TO_GREEN..MAX_CHROMATIC_TO_GREEN) return null
        return out
    }

    private fun maxRelativeDelta(a: FloatArray, b: FloatArray): Float =
        (0 until 4).maxOf { index -> abs(a[index] - b[index]) / max(0.25f, a[index]) }

    companion object {
        private const val MIN_GAIN = 0.25f
        private const val MAX_GAIN = 6.0f
        private const val MIN_RED_BLUE_RATIO = 0.15f
        private const val MAX_RED_BLUE_RATIO = 6.67f
        private const val MIN_CHROMATIC_TO_GREEN = 0.20f
        private const val MAX_CHROMATIC_TO_GREEN = 5.0f
        private const val MIN_STARTUP_SAMPLES = 3
        private const val MIN_STARTUP_MEAN_CONFIDENCE = 0.60f
        private const val STARTUP_CONSISTENCY_DELTA = 0.18f
        private const val HYSTERESIS_RELATIVE_DELTA = 0.012f
    }
}
