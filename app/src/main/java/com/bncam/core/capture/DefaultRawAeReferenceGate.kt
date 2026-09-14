package com.bncam.core.capture

/**
 * Prevents default RAW AE from anchoring its photometric target to stale pre-convergence metering.
 * The HAL AE state and scene-linear meter are asynchronous, so an AE-CONVERGED result may arrive
 * before the RAW statistic produced under that converged exposure.
 */
class DefaultRawAeReferenceGate(
    private val minimumStableResults: Int = 2
) {
    private var generation: Int = -1
    private var stableSinceElapsedNs: Long = 0L
    private var consecutiveStableResults: Int = 0

    @Synchronized
    fun reset(currentGeneration: Int) {
        generation = currentGeneration
        stableSinceElapsedNs = 0L
        consecutiveStableResults = 0
    }

    @Synchronized
    fun observeAeState(currentGeneration: Int, stable: Boolean, nowElapsedNs: Long) {
        ensureGeneration(currentGeneration)
        if (!stable) {
            stableSinceElapsedNs = 0L
            consecutiveStableResults = 0
            return
        }
        if (consecutiveStableResults == 0) stableSinceElapsedNs = nowElapsedNs
        consecutiveStableResults++
    }

    @Synchronized
    fun canAccept(
        currentGeneration: Int,
        metering: DefaultRawMeteringSnapshot?
    ): Boolean {
        ensureGeneration(currentGeneration)
        val sample = metering ?: return false
        return consecutiveStableResults >= minimumStableResults.coerceAtLeast(1) &&
            stableSinceElapsedNs > 0L &&
            sample.pipelineGeneration == currentGeneration &&
            sample.valid &&
            sample.freshness == DefaultRawMeteringFreshness.FRESH &&
            sample.measurementElapsedRealtimeNs >= stableSinceElapsedNs &&
            sample.controllerLuma?.let { it.isFinite() && it > 0f } == true
    }

    @Synchronized
    fun summary(currentGeneration: Int): String {
        ensureGeneration(currentGeneration)
        return "stableResults=$consecutiveStableResults;stableSinceElapsedNs=$stableSinceElapsedNs"
    }

    private fun ensureGeneration(currentGeneration: Int) {
        if (generation != currentGeneration) reset(currentGeneration)
    }
}

/** Keeps one photometric set-point across API36 -> manual fallback route changes. */
object DefaultRawTargetContinuity {
    fun resolveFallbackTarget(
        fallbackTargetLuma: Float?,
        photometricTargetLuma: Float?,
        observedLuma: Float?
    ): Float? = sequenceOf(fallbackTargetLuma, photometricTargetLuma, observedLuma)
        .firstOrNull { it != null && it.isFinite() && it > 0f }
}
