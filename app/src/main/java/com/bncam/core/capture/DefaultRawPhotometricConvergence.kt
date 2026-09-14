package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.log2

/**
 * Photometric convergence state for the default RAW auto-exposure route.
 *
 * Allocation readiness and photometric convergence are intentionally separate:
 * - allocationReady means a shutter/authority plan can be submitted;
 * - photometricConverged means fresh scene feedback is near target and the active Camera2/manual
 *   request has been realized.
 *
 * This tracker is diagnostics/state only. It never blocks capture.
 */
data class DefaultRawPhotometricConvergenceSnapshot(
    val generation: Int,
    val route: DefaultRawExposureRoute,
    val allocationReady: Boolean,
    val photometricConverged: Boolean,
    val targetLuma: Float?,
    val observedLuma: Float?,
    val exposureErrorEv: Float?,
    val consecutiveConvergedSamples: Int,
    val realizationStatus: String?,
    val aeStable: Boolean,
    val lastMeaningfulSceneChangeElapsedNs: Long?,
    val timeSinceLastMeaningfulSceneChangeNs: Long?,
    val reason: String
) {
    fun summary(): String =
        "generation=$generation;route=$route;allocationReady=$allocationReady;" +
            "photometricConverged=$photometricConverged;" +
            "targetLuma=${targetLuma ?: "unavailable"};observedLuma=${observedLuma ?: "unavailable"};" +
            "exposureErrorEv=${exposureErrorEv ?: "unavailable"};" +
            "consecutiveConvergedSamples=$consecutiveConvergedSamples;" +
            "realizationStatus=${realizationStatus ?: "unavailable"};aeStable=$aeStable;" +
            "lastMeaningfulSceneChangeElapsedNs=${lastMeaningfulSceneChangeElapsedNs ?: "unavailable"};" +
            "timeSinceLastMeaningfulSceneChangeNs=${timeSinceLastMeaningfulSceneChangeNs ?: "unavailable"};" +
            "reason=$reason"
}

class DefaultRawPhotometricConvergenceTracker(
    private val convergenceThresholdEv: Float = 0.25f,
    private val meaningfulSceneChangeThresholdEv: Float = 0.75f,
    private val requiredConsecutiveSamples: Int = 2
) {
    private var generation: Int = -1
    private var route: DefaultRawExposureRoute = DefaultRawExposureRoute.AE_BOOTSTRAP
    private var consecutiveConvergedSamples: Int = 0
    private var previousErrorEv: Float? = null
    private var lastMeaningfulSceneChangeElapsedNs: Long? = null

    @Synchronized
    fun reset(
        currentGeneration: Int,
        nowElapsedNs: Long = 0L
    ): DefaultRawPhotometricConvergenceSnapshot {
        generation = currentGeneration
        route = DefaultRawExposureRoute.AE_BOOTSTRAP
        consecutiveConvergedSamples = 0
        previousErrorEv = null
        lastMeaningfulSceneChangeElapsedNs = null
        return snapshot(
            allocationReady = false,
            photometricConverged = false,
            targetLuma = null,
            observedLuma = null,
            exposureErrorEv = null,
            realizationStatus = null,
            aeStable = false,
            nowElapsedNs = nowElapsedNs,
            reason = "state_reset"
        )
    }

    @Synchronized
    fun observe(
        currentGeneration: Int,
        currentRoute: DefaultRawExposureRoute,
        allocationReady: Boolean,
        targetLuma: Float?,
        observedLuma: Float?,
        realizationStatus: String?,
        aeStable: Boolean,
        nowElapsedNs: Long
    ): DefaultRawPhotometricConvergenceSnapshot {
        ensureGeneration(currentGeneration, nowElapsedNs)
        if (currentRoute != route) {
            route = currentRoute
            consecutiveConvergedSamples = 0
        }

        val errorEv = exposureErrorEv(targetLuma, observedLuma)
        val previous = previousErrorEv
        if (isMeaningfulSceneChange(previous, errorEv)) {
            lastMeaningfulSceneChangeElapsedNs = nowElapsedNs.coerceAtLeast(0L)
            consecutiveConvergedSamples = 0
        }
        previousErrorEv = errorEv

        val realizationReady = when (currentRoute) {
            DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY ->
                realizationStatus == "API36_EXPOSURE_REALIZED"
            DefaultRawExposureRoute.MANUAL_FALLBACK ->
                realizationStatus == "MANUAL_EXPOSURE_REALIZED"
            DefaultRawExposureRoute.AE_BOOTSTRAP ->
                realizationStatus == "AE_BOOTSTRAP_OBSERVED"
        }
        val aeAuthorityStable = when (currentRoute) {
            DefaultRawExposureRoute.MANUAL_FALLBACK -> true
            DefaultRawExposureRoute.API36_EXPOSURE_TIME_PRIORITY,
            DefaultRawExposureRoute.AE_BOOTSTRAP -> aeStable
        }
        val photometryNearTarget = errorEv != null && abs(errorEv) <= convergenceThresholdEv
        val convergedSample =
            allocationReady && realizationReady && aeAuthorityStable && photometryNearTarget

        consecutiveConvergedSamples = if (convergedSample) {
            (consecutiveConvergedSamples + 1).coerceAtMost(requiredConsecutiveSamples.coerceAtLeast(1))
        } else {
            0
        }
        val converged = consecutiveConvergedSamples >= requiredConsecutiveSamples.coerceAtLeast(1)

        val reason = when {
            !allocationReady -> "allocation_not_ready"
            errorEv == null -> "fresh_photometry_unavailable"
            !realizationReady -> "request_not_realized"
            !aeAuthorityStable -> "ae_authority_not_stable"
            !photometryNearTarget -> "photometric_error_outside_threshold"
            !converged -> "photometry_near_target_confirming"
            else -> "photometrically_converged"
        }

        return snapshot(
            allocationReady = allocationReady,
            photometricConverged = converged,
            targetLuma = targetLuma,
            observedLuma = observedLuma,
            exposureErrorEv = errorEv,
            realizationStatus = realizationStatus,
            aeStable = aeStable,
            nowElapsedNs = nowElapsedNs,
            reason = reason
        )
    }

    private fun ensureGeneration(currentGeneration: Int, nowElapsedNs: Long) {
        if (generation != currentGeneration) reset(currentGeneration, nowElapsedNs)
    }

    private fun isMeaningfulSceneChange(previous: Float?, current: Float?): Boolean {
        if (current == null || abs(current) < meaningfulSceneChangeThresholdEv) return false
        if (previous == null || abs(previous) < meaningfulSceneChangeThresholdEv) return true
        if ((previous < 0f) != (current < 0f)) return true
        return abs(current - previous) >= meaningfulSceneChangeThresholdEv
    }

    private fun snapshot(
        allocationReady: Boolean,
        photometricConverged: Boolean,
        targetLuma: Float?,
        observedLuma: Float?,
        exposureErrorEv: Float?,
        realizationStatus: String?,
        aeStable: Boolean,
        nowElapsedNs: Long,
        reason: String
    ): DefaultRawPhotometricConvergenceSnapshot {
        val sceneChange = lastMeaningfulSceneChangeElapsedNs
        val sinceSceneChange = if (sceneChange != null && nowElapsedNs >= sceneChange) {
            nowElapsedNs - sceneChange
        } else {
            null
        }
        return DefaultRawPhotometricConvergenceSnapshot(
            generation = generation,
            route = route,
            allocationReady = allocationReady,
            photometricConverged = photometricConverged,
            targetLuma = targetLuma,
            observedLuma = observedLuma,
            exposureErrorEv = exposureErrorEv,
            consecutiveConvergedSamples = consecutiveConvergedSamples,
            realizationStatus = realizationStatus,
            aeStable = aeStable,
            lastMeaningfulSceneChangeElapsedNs = sceneChange,
            timeSinceLastMeaningfulSceneChangeNs = sinceSceneChange,
            reason = reason
        )
    }

    companion object {
        fun exposureErrorEv(targetLuma: Float?, observedLuma: Float?): Float? {
            if (targetLuma == null || observedLuma == null ||
                !targetLuma.isFinite() || targetLuma <= 0f ||
                !observedLuma.isFinite() || observedLuma <= 0f
            ) return null
            return log2(
                (targetLuma.toDouble() / observedLuma.toDouble()).coerceIn(1.0 / 32.0, 32.0)
            ).toFloat()
        }
    }
}
