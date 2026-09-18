package com.bncam.core.quality

import kotlin.math.abs
import kotlin.math.max

enum class WhiteBalanceConvergence {
    UNKNOWN,
    INACTIVE,
    SEARCHING,
    CONVERGED,
    LOCKED
}

enum class WhiteBalanceObservationSource {
    CAMERA2,
    PHYSICAL_SCENE
}

class StableWhiteBalanceSnapshot(
    gains: FloatArray,
    colorMatrix: FloatArray? = null,
    val confidence: Float,
    val scopeKey: String,
    val pipelineGeneration: Int,
    val acceptedSampleCount: Int,
    val convergence: WhiteBalanceConvergence,
    val temporalDelta: Float = 0f,
    val sceneChangeDetected: Boolean = false,
    val source: WhiteBalanceObservationSource = WhiteBalanceObservationSource.CAMERA2,
    val dataAuthority: Float = 0f,
    val neutralSupport: Float = 0f,
    val mixedLightScore: Float = 0f,
    val priorDisagreement: Float = 0f,
    val validTileCount: Int = 0,
    val sensorTimestampNs: Long = 0L,
    val calibrationSource: String = "CAMERA2_EXACT_FRAME",
    val calibrationFingerprint: String = "UNAVAILABLE",
    val calibrationAuthority: Float = 0f,
    val grGbRatio: Float? = null,
    val greenEvenOddRatio: Float = 1f
) {
    private val storedGains: FloatArray = gains.copyOf()
    private val storedColorMatrix: FloatArray? = colorMatrix?.copyOf()
    val gains: FloatArray
        get() = storedGains.copyOf()

    fun copyGains(): FloatArray = storedGains.copyOf()
    fun copyColorMatrix(): FloatArray? = storedColorMatrix?.copyOf()
}

/**
 * Single process-local temporal owner for System-Auto RAW white balance.
 *
 * Camera2 is the bootstrap/fallback prior. Once the Vulkan pre-WB observer supplies trustworthy
 * PhysicalAwbEstimator evidence, the physical estimate becomes the temporal target while exact
 * Camera2 metadata continues to provide the independent prior and current CCM. If physical
 * evidence disappears for a sustained interval, Camera2 regains bounded fallback authority.
 */
class WhiteBalanceStateEngine {
    private val lock = Any()

    private var scopeKey: String? = null
    private var pipelineGeneration: Int = -1
    private var candidate: FloatArray? = null
    private var candidateColorMatrix: FloatArray? = null
    private var candidateConsistentSamples: Int = 0
    private var candidateConfidenceSum: Float = 0f

    private var stable: FloatArray? = null
    private var stableColorMatrix: FloatArray? = null
    private var stableConfidence: Float = 0f
    private var stableAcceptedSamples: Int = 0
    private var stableConvergence: WhiteBalanceConvergence = WhiteBalanceConvergence.UNKNOWN
    private var stableSource: WhiteBalanceObservationSource = WhiteBalanceObservationSource.CAMERA2
    private var stableDataAuthority: Float = 0f
    private var stableNeutralSupport: Float = 0f
    private var stableMixedLightScore: Float = 0f
    private var stablePriorDisagreement: Float = 0f
    private var stableValidTileCount: Int = 0
    private var stableSensorTimestampNs: Long = 0L
    private var stableCalibrationSource: String = "CAMERA2_EXACT_FRAME"
    private var stableCalibrationFingerprint: String = "UNAVAILABLE"
    private var stableCalibrationAuthority: Float = 0f
    private var stableGrGbRatio: Float? = null
    private var stableGreenEvenOddRatio: Float = 1f

    private var lastTemporalDelta: Float = 0f
    private var sceneChangeDetected: Boolean = false
    private var largeDeltaCandidate: FloatArray? = null
    private var largeDeltaConsistentSamples: Int = 0

    private var physicalAuthorityActive: Boolean = false
    private var camera2ObservationsSincePhysical: Int = Int.MAX_VALUE

    fun observe(
        scopeKey: String,
        pipelineGeneration: Int,
        gains: FloatArray,
        convergence: WhiteBalanceConvergence,
        colorMatrix: FloatArray? = null,
        sensorTimestampNs: Long = 0L,
        calibrationSource: String = "CAMERA2_EXACT_FRAME",
        calibrationFingerprint: String = "UNAVAILABLE",
        calibrationAuthority: Float = 0f,
        grGbRatio: Float? = null,
        greenEvenOddRatio: Float? = null
    ): StableWhiteBalanceSnapshot? = synchronized(lock) {
        val safe = sanitizeGains(gains) ?: return@synchronized snapshotLocked()
        val safeMatrix = sanitizeColorMatrix(colorMatrix)
        transitionScope(scopeKey, pipelineGeneration)

        var movementAuthority = 1.0f
        if (physicalAuthorityActive && stable != null) {
            camera2ObservationsSincePhysical = (camera2ObservationsSincePhysical + 1).coerceAtMost(Int.MAX_VALUE - 1)
            // Camera2 remains available as exact-frame prior metadata, but while physical evidence
            // is fresh it may not independently move either half of the temporal colour pair.
            // The next physical observation supplies the matching exact-frame matrix and moves
            // gains + CCM together through observeInternal().
            if (camera2ObservationsSincePhysical <= CAMERA2_FALLBACK_AFTER_OBSERVATIONS) {
                return@synchronized snapshotLocked()
            }
            physicalAuthorityActive = false
            // Physical evidence has disappeared for a sustained period. Hand authority back to
            // Camera2 deliberately rather than snapping to the latest HAL solution.
            movementAuthority = 0.40f
        }

        observeInternal(
            gains = safe,
            colorMatrix = safeMatrix,
            convergence = convergence,
            observationConfidence = convergenceConfidence(convergence),
            movementAuthority = movementAuthority,
            source = WhiteBalanceObservationSource.CAMERA2,
            dataAuthority = 0f,
            neutralSupport = 0f,
            mixedLightScore = 0f,
            priorDisagreement = 0f,
            validTileCount = 0,
            sensorTimestampNs = sensorTimestampNs,
            calibrationSource = calibrationSource,
            calibrationFingerprint = calibrationFingerprint,
            calibrationAuthority = calibrationAuthority.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
            grGbRatio = grGbRatio?.takeIf { it.isFinite() && it in 0.50f..2.0f },
            greenEvenOddRatio = greenEvenOddRatio
                ?.takeIf { it.isFinite() && it in 0.50f..2.0f }
                ?: (safe[1] / max(1.0e-4f, safe[2])).coerceIn(0.50f, 2.0f)
        )
    }

    fun observePhysical(
        scopeKey: String,
        pipelineGeneration: Int,
        finalGains: FloatArray,
        colorMatrix: FloatArray?,
        confidence: Float,
        dataAuthority: Float,
        neutralSupport: Float,
        mixedLightScore: Float,
        priorDisagreement: Float,
        validTileCount: Int,
        dataReady: Boolean,
        sensorTimestampNs: Long,
        calibrationSource: String = "UNAVAILABLE",
        calibrationFingerprint: String = "UNAVAILABLE",
        calibrationAuthority: Float = 0f,
        grGbRatio: Float? = null,
        greenEvenOddRatio: Float = 1f
    ): StableWhiteBalanceSnapshot? = synchronized(lock) {
        transitionScope(scopeKey, pipelineGeneration)
        if (!dataReady) return@synchronized snapshotLocked()
        // Camera2 owns bootstrap. Physical scene evidence may steer only an already-validated
        // prior, preventing the first asynchronous GPU estimate from replacing startup colour.
        if (stable == null) return@synchronized null
        val safe = sanitizeGains(finalGains) ?: return@synchronized snapshotLocked()
        val safeMatrix = sanitizeColorMatrix(colorMatrix)
        val safeConfidence = confidence.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val safeAuthority = dataAuthority.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val safeNeutral = neutralSupport.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val safeMixed = mixedLightScore.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f
        val safeDisagreement = priorDisagreement.takeIf(Float::isFinite)?.coerceIn(0f, 2.5f) ?: 2.5f
        if (safeConfidence < MIN_PHYSICAL_CONFIDENCE || safeAuthority < MIN_PHYSICAL_DATA_AUTHORITY) {
            return@synchronized snapshotLocked()
        }

        val disagreementDamping = 1f - 0.20f * smoothstep(0.30f, 0.95f, safeDisagreement)
        val mixedLightDamping = 1f - 0.45f * safeMixed
        val movementAuthority = ((0.45f + 0.55f * safeAuthority) *
            disagreementDamping * mixedLightDamping).coerceIn(0.12f, 1f)
        val convergence = if (safeConfidence >= 0.68f && safeAuthority >= 0.30f) {
            WhiteBalanceConvergence.CONVERGED
        } else {
            WhiteBalanceConvergence.SEARCHING
        }

        physicalAuthorityActive = true
        camera2ObservationsSincePhysical = 0
        observeInternal(
            gains = safe,
            colorMatrix = safeMatrix,
            convergence = convergence,
            observationConfidence = safeConfidence,
            movementAuthority = movementAuthority,
            source = WhiteBalanceObservationSource.PHYSICAL_SCENE,
            dataAuthority = safeAuthority,
            neutralSupport = safeNeutral,
            mixedLightScore = safeMixed,
            priorDisagreement = safeDisagreement,
            validTileCount = validTileCount.coerceAtLeast(0),
            sensorTimestampNs = sensorTimestampNs,
            calibrationSource = calibrationSource.ifBlank { "UNAVAILABLE" },
            calibrationFingerprint = calibrationFingerprint.ifBlank { "UNAVAILABLE" },
            calibrationAuthority = calibrationAuthority.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
            grGbRatio = grGbRatio?.takeIf { it.isFinite() && it in 0.50f..2.0f },
            greenEvenOddRatio = greenEvenOddRatio.takeIf(Float::isFinite)?.coerceIn(0.50f, 2.0f) ?: 1f
        )
    }

    fun snapshot(scopeKey: String? = null): StableWhiteBalanceSnapshot? = synchronized(lock) {
        if (scopeKey != null && this.scopeKey != scopeKey) return@synchronized null
        snapshotLocked()
    }

    private fun observeInternal(
        gains: FloatArray,
        colorMatrix: FloatArray?,
        convergence: WhiteBalanceConvergence,
        observationConfidence: Float,
        movementAuthority: Float,
        source: WhiteBalanceObservationSource,
        dataAuthority: Float,
        neutralSupport: Float,
        mixedLightScore: Float,
        priorDisagreement: Float,
        validTileCount: Int,
        sensorTimestampNs: Long,
        calibrationSource: String,
        calibrationFingerprint: String,
        calibrationAuthority: Float,
        grGbRatio: Float?,
        greenEvenOddRatio: Float
    ): StableWhiteBalanceSnapshot? {
        val confidence = observationConfidence.coerceIn(0f, 1f)
        val authority = movementAuthority.coerceIn(0f, 1f)

        if (stable == null) {
            updateStartupCandidate(gains, colorMatrix, confidence, convergence)
            val meanCandidateConfidence = if (candidateConsistentSamples > 0) {
                candidateConfidenceSum / candidateConsistentSamples.toFloat()
            } else 0f
            val enoughSamples = candidateConsistentSamples >= MIN_STARTUP_SAMPLES
            val trustworthyState = meanCandidateConfidence >= MIN_STARTUP_MEAN_CONFIDENCE
            if (enoughSamples && trustworthyState) {
                stable = candidate?.copyOf()
                stableColorMatrix = candidateColorMatrix?.copyOf()
                stableConfidence = meanCandidateConfidence.coerceIn(0f, 1f)
                stableAcceptedSamples = candidateConsistentSamples
                stableConvergence = convergence
                lastTemporalDelta = 0f
                sceneChangeDetected = false
                clearSceneChangeCandidate()
                updateStableEvidence(
                    source, dataAuthority, neutralSupport, mixedLightScore,
                    priorDisagreement, validTileCount, sensorTimestampNs,
                    calibrationSource, calibrationFingerprint, calibrationAuthority,
                    grGbRatio, greenEvenOddRatio
                )
            }
            return snapshotLocked()
        }

        val previous = stable ?: return null
        val delta = maxRelativeDelta(previous, gains)
        lastTemporalDelta = delta
        updateSceneChangeCandidate(gains, delta, confidence, authority)

        if (delta < HYSTERESIS_RELATIVE_DELTA) {
            stableColorMatrix = blendMatrix(stableColorMatrix, colorMatrix, 0.10f * confidence)
            stableConfidence = (0.90f * stableConfidence + 0.10f * confidence).coerceIn(0f, 1f)
            stableAcceptedSamples += 1
            stableConvergence = convergence
            updateStableEvidence(
                source, dataAuthority, neutralSupport, mixedLightScore,
                priorDisagreement, validTileCount, sensorTimestampNs,
                calibrationSource, calibrationFingerprint, calibrationAuthority,
                grGbRatio, greenEvenOddRatio
            )
            return snapshotLocked()
        }

        val baseAlpha = when {
            sceneChangeDetected -> 0.34f
            delta >= 0.35f -> 0.24f
            delta >= 0.15f -> 0.16f
            else -> 0.10f
        }
        val confidenceScale = 0.25f + 0.75f * confidence
        val alpha = (baseAlpha * confidenceScale * authority).coerceIn(0.005f, 0.38f)
        val baseMaxStepFraction = when {
            sceneChangeDetected -> 0.090f
            delta >= 0.35f -> 0.070f
            delta >= 0.15f -> 0.045f
            else -> 0.025f
        }
        val maxStepFraction = baseMaxStepFraction * (0.45f + 0.55f * authority)
        stable = FloatArray(4) { index ->
            val desiredStep = (gains[index] - previous[index]) * alpha
            val maxStep = max(0.008f, previous[index] * maxStepFraction)
            (previous[index] + desiredStep.coerceIn(-maxStep, maxStep)).coerceIn(MIN_GAIN, MAX_GAIN)
        }
        stableColorMatrix = blendMatrix(stableColorMatrix, colorMatrix, alpha)
        stableConfidence = (0.84f * stableConfidence + 0.16f * confidence).coerceIn(0f, 1f)
        stableAcceptedSamples += 1
        stableConvergence = convergence
        updateStableEvidence(
            source, dataAuthority, neutralSupport, mixedLightScore,
            priorDisagreement, validTileCount, sensorTimestampNs,
            calibrationSource, calibrationFingerprint, calibrationAuthority,
            grGbRatio, greenEvenOddRatio
        )
        return snapshotLocked()
    }

    private fun updateSceneChangeCandidate(
        sample: FloatArray,
        delta: Float,
        confidence: Float,
        authority: Float
    ) {
        val armed = stableAcceptedSamples >= SCENE_CHANGE_ARM_AFTER_SAMPLES
        val strongEvidence = confidence >= SCENE_CHANGE_MIN_CONFIDENCE && authority >= SCENE_CHANGE_MIN_AUTHORITY
        if (!armed || !strongEvidence || delta < SCENE_CHANGE_DELTA) {
            sceneChangeDetected = false
            clearSceneChangeCandidate()
            return
        }
        val previousCandidate = largeDeltaCandidate
        if (previousCandidate == null ||
            maxRelativeDelta(previousCandidate, sample) > SCENE_CHANGE_CANDIDATE_CONSISTENCY_DELTA
        ) {
            largeDeltaCandidate = sample.copyOf()
            largeDeltaConsistentSamples = 1
            sceneChangeDetected = false
            return
        }
        largeDeltaCandidate = FloatArray(4) { index ->
            previousCandidate[index] + (sample[index] - previousCandidate[index]) * 0.35f
        }
        largeDeltaConsistentSamples += 1
        sceneChangeDetected = largeDeltaConsistentSamples >= SCENE_CHANGE_CONFIRM_SAMPLES
    }

    private fun clearSceneChangeCandidate() {
        largeDeltaCandidate = null
        largeDeltaConsistentSamples = 0
    }

    private fun updateStableEvidence(
        source: WhiteBalanceObservationSource,
        dataAuthority: Float,
        neutralSupport: Float,
        mixedLightScore: Float,
        priorDisagreement: Float,
        validTileCount: Int,
        sensorTimestampNs: Long,
        calibrationSource: String,
        calibrationFingerprint: String,
        calibrationAuthority: Float,
        grGbRatio: Float?,
        greenEvenOddRatio: Float
    ) {
        stableSource = source
        stableDataAuthority = dataAuthority.coerceIn(0f, 1f)
        stableNeutralSupport = neutralSupport.coerceIn(0f, 1f)
        stableMixedLightScore = mixedLightScore.coerceIn(0f, 1f)
        stablePriorDisagreement = priorDisagreement.coerceAtLeast(0f)
        stableValidTileCount = validTileCount.coerceAtLeast(0)
        if (sensorTimestampNs > 0L) stableSensorTimestampNs = sensorTimestampNs
        stableCalibrationSource = calibrationSource.ifBlank { "UNAVAILABLE" }
        stableCalibrationFingerprint = calibrationFingerprint.ifBlank { "UNAVAILABLE" }
        stableCalibrationAuthority = calibrationAuthority.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        stableGrGbRatio = grGbRatio?.takeIf { it.isFinite() && it in 0.50f..2.0f }
        stableGreenEvenOddRatio = greenEvenOddRatio.takeIf(Float::isFinite)?.coerceIn(0.50f, 2.0f) ?: 1f
    }

    private fun transitionScope(nextScopeKey: String, nextGeneration: Int) {
        if (scopeKey != nextScopeKey) {
            scopeKey = nextScopeKey
            pipelineGeneration = nextGeneration
            candidate = null
            candidateColorMatrix = null
            candidateConsistentSamples = 0
            candidateConfidenceSum = 0f
            stable = null
            stableColorMatrix = null
            stableConfidence = 0f
            stableAcceptedSamples = 0
            stableConvergence = WhiteBalanceConvergence.UNKNOWN
            stableSource = WhiteBalanceObservationSource.CAMERA2
            stableDataAuthority = 0f
            stableNeutralSupport = 0f
            stableMixedLightScore = 0f
            stablePriorDisagreement = 0f
            stableValidTileCount = 0
            stableSensorTimestampNs = 0L
            stableCalibrationSource = "CAMERA2_EXACT_FRAME"
            stableCalibrationFingerprint = "UNAVAILABLE"
            stableCalibrationAuthority = 0f
            stableGrGbRatio = null
            stableGreenEvenOddRatio = 1f
            lastTemporalDelta = 0f
            sceneChangeDetected = false
            clearSceneChangeCandidate()
            physicalAuthorityActive = false
            camera2ObservationsSincePhysical = Int.MAX_VALUE
            return
        }
        if (pipelineGeneration != nextGeneration) {
            pipelineGeneration = nextGeneration
            candidate = null
            candidateColorMatrix = null
            candidateConsistentSamples = 0
            candidateConfidenceSum = 0f
            lastTemporalDelta = 0f
            sceneChangeDetected = false
            clearSceneChangeCandidate()
            // Same physical camera: preserve the last valid color state through a session rebuild.
            stableConfidence = (stableConfidence * 0.90f).coerceIn(0f, 1f)
            camera2ObservationsSincePhysical = 0
        }
    }

    private fun updateStartupCandidate(
        sample: FloatArray,
        colorMatrix: FloatArray?,
        confidence: Float,
        convergence: WhiteBalanceConvergence
    ) {
        val previousCandidate = candidate
        if (previousCandidate == null || maxRelativeDelta(previousCandidate, sample) > STARTUP_CONSISTENCY_DELTA) {
            candidate = sample.copyOf()
            candidateColorMatrix = colorMatrix?.copyOf()
            candidateConsistentSamples = 1
            candidateConfidenceSum = confidence
            return
        }
        val alpha = if (convergence == WhiteBalanceConvergence.CONVERGED ||
            convergence == WhiteBalanceConvergence.LOCKED) 0.45f else 0.30f
        candidate = FloatArray(4) { index ->
            (previousCandidate[index] + (sample[index] - previousCandidate[index]) * alpha)
                .coerceIn(MIN_GAIN, MAX_GAIN)
        }
        candidateColorMatrix = blendMatrix(candidateColorMatrix, colorMatrix, alpha)
        candidateConsistentSamples += 1
        candidateConfidenceSum += confidence
    }

    private fun snapshotLocked(): StableWhiteBalanceSnapshot? {
        val gains = stable ?: return null
        val scope = scopeKey ?: return null
        return StableWhiteBalanceSnapshot(
            gains = gains.copyOf(),
            colorMatrix = stableColorMatrix?.copyOf(),
            confidence = stableConfidence,
            scopeKey = scope,
            pipelineGeneration = pipelineGeneration,
            acceptedSampleCount = stableAcceptedSamples,
            convergence = stableConvergence,
            temporalDelta = lastTemporalDelta,
            sceneChangeDetected = sceneChangeDetected,
            source = stableSource,
            dataAuthority = stableDataAuthority,
            neutralSupport = stableNeutralSupport,
            mixedLightScore = stableMixedLightScore,
            priorDisagreement = stablePriorDisagreement,
            validTileCount = stableValidTileCount,
            sensorTimestampNs = stableSensorTimestampNs,
            calibrationSource = stableCalibrationSource,
            calibrationFingerprint = stableCalibrationFingerprint,
            calibrationAuthority = stableCalibrationAuthority,
            grGbRatio = stableGrGbRatio,
            greenEvenOddRatio = stableGreenEvenOddRatio
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

    private fun sanitizeColorMatrix(matrix: FloatArray?): FloatArray? {
        if (matrix == null || matrix.size < 9) return null
        val out = matrix.copyOf(9)
        return out.takeIf { RawColorTransformEngine.validateSensorToLinearSrgbMatrix(it).valid }
    }

    private fun blendMatrix(previous: FloatArray?, target: FloatArray?, alpha: Float): FloatArray? {
        if (target == null) return previous?.copyOf()
        if (previous == null) return target.copyOf()
        val a = alpha.coerceIn(0f, 1f)
        val blended = FloatArray(9) { index ->
            previous[index] + (target[index] - previous[index]) * a
        }
        return if (RawColorTransformEngine.validateSensorToLinearSrgbMatrix(blended).valid) {
            blended
        } else {
            target.copyOf()
        }
    }

    private fun maxRelativeDelta(a: FloatArray, b: FloatArray): Float =
        (0 until 4).maxOf { index -> abs(a[index] - b[index]) / max(0.25f, a[index]) }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        if (!(edge1 > edge0)) return if (value >= edge1) 1f else 0f
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

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
        private const val MIN_PHYSICAL_CONFIDENCE = 0.10f
        private const val MIN_PHYSICAL_DATA_AUTHORITY = 0.05f
        private const val CAMERA2_FALLBACK_AFTER_OBSERVATIONS = 45
        private const val SCENE_CHANGE_ARM_AFTER_SAMPLES = 20
        private const val SCENE_CHANGE_DELTA = 0.30f
        private const val SCENE_CHANGE_MIN_CONFIDENCE = 0.58f
        private const val SCENE_CHANGE_MIN_AUTHORITY = 0.28f
        private const val SCENE_CHANGE_CANDIDATE_CONSISTENCY_DELTA = 0.12f
        private const val SCENE_CHANGE_CONFIRM_SAMPLES = 2
    }
}
