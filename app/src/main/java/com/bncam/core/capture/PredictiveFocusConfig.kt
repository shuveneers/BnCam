package com.bncam.core.capture

/**
 * Centralized focus policy configuration object.
 *
 * Stores all focus solver thresholds, probe timing bounds, and background-dominance guard parameters
 * in one place, allowing fast tuning without code redesign.
 */
data class PredictiveFocusConfig(
    val coarseAfTimeoutMs: Long = 450L,
    val probeTimeoutMs: Long = 90L,
    val maxTransactionDurationMs: Long = 450L,
    val maxProbes: Int = 3,
    val backgroundRiseThresholdRatio: Float = 0.12f,     // Background full ROI score rise limit (+12%)
    val maskedTargetMinImprovementRatio: Float = 0.02f,  // Target mask required min improvement (+2%)
    val noiseAwareAbsFloor: Float = 0.002f,              // Noise floor absolute offset
    val noiseAwareRelRatio: Float = 0.04f,               // Noise floor relative ratio (+4%)
    val defaultProbeStepDiopters: Float = 0.08f,         // Initial diopter probe step
    val maxDiopterDisplacement: Float = 0.35f            // Max allowed diopter shift from coarse estimate
)
