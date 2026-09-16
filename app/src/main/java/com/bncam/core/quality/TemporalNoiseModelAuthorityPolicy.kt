package com.bncam.core.quality

internal data class TemporalNoiseModelDecision(
    val enabled: Boolean,
    val adaptiveSpectraCalibration: Boolean,
    val spectraMode: Int,
    val effectiveS: DoubleArray,
    val effectiveO: DoubleArray,
    val confidence: Float,
    val authoritySource: String
) {
    companion object {
        fun disabled(reason: String) = TemporalNoiseModelDecision(
            enabled = false,
            adaptiveSpectraCalibration = false,
            spectraMode = 0,
            effectiveS = DoubleArray(4),
            effectiveO = DoubleArray(4),
            confidence = 0.0f,
            authoritySource = reason
        )
    }
}

/**
 * Final temporal/native consumer policy after physical-noise authority migration.
 *
 * Production authority is exclusively the immutable shutter-time physical snapshot. Legacy
 * FinalSensorCalibration S/O, Camera2-validity flags and retired Off/Auto/Manual mode strings are
 * accepted in the call signature only until the JNI transport is simplified; they can no longer
 * create, replace or rescue physical authority.
 *
 * SPECTRA is a downstream add-on. Its effective on/off decision changes only SPECTRA processing;
 * it never changes the physical S/O vectors or their confidence.
 */
internal object TemporalNoiseModelAuthorityPolicy {
    fun resolve(
        spectraProcessingEnabled: Boolean,
        spectraModeName: String?,
        snapshotEffectiveS: DoubleArray?,
        snapshotEffectiveO: DoubleArray?,
        snapshotConfidence: Float?,
        effectiveNoiseProfile: DoubleArray?,
        effectiveNoiseProfileApplied: Boolean,
        cameraNoiseProfilePresent: Boolean,
        cameraNoiseProfileValid: Boolean,
        normalizationCalibrationValid: Boolean,
        cfaSupportedForBayerNoiseModel: Boolean
    ): TemporalNoiseModelDecision {
        val snapshotS = snapshotEffectiveS?.takeIf(::validFour)
        val snapshotO = snapshotEffectiveO?.takeIf(::validFour)
        if (snapshotS == null || snapshotO == null || !hasEnergy(snapshotS, snapshotO)) {
            return TemporalNoiseModelDecision.disabled("PHYSICAL_SHUTTER_SNAPSHOT_REQUIRED")
        }

        // The frozen physical snapshot is deterministic capture authority. SPECTRA fit/observer
        // confidence is a separate processing metric and must never reduce physical confidence.
        val physicalConfidence = 1.0f
        val spectraEnabled = spectraProcessingEnabled

        // Keep compatibility parameters referenced while old call sites/JNI fields still exist.
        // None of them is allowed to participate in the authority decision anymore.
        @Suppress("UNUSED_VARIABLE")
        val retiredCompatibilityInputs = listOf(
            spectraModeName,
            snapshotConfidence,
            effectiveNoiseProfile?.size,
            effectiveNoiseProfileApplied,
            cameraNoiseProfilePresent,
            cameraNoiseProfileValid,
            normalizationCalibrationValid,
            cfaSupportedForBayerNoiseModel
        )

        return TemporalNoiseModelDecision(
            enabled = true,
            adaptiveSpectraCalibration = spectraEnabled,
            spectraMode = if (spectraEnabled) 1 else 0,
            effectiveS = snapshotS.copyOf(4),
            effectiveO = snapshotO.copyOf(4),
            confidence = physicalConfidence,
            authoritySource = if (spectraEnabled) {
                "SPECTRA_ADDON_CONSUMES_PHYSICAL_SHUTTER_SO"
            } else {
                "PHYSICAL_SHUTTER_SNAPSHOT_FIXED_SO"
            }
        )
    }

    private fun validFour(values: DoubleArray): Boolean =
        values.size >= 4 && values.take(4).all { it.isFinite() && it >= 0.0 }

    private fun hasEnergy(s: DoubleArray, o: DoubleArray): Boolean =
        s.take(4).any { it > 0.0 } || o.take(4).any { it > 0.0 }
}
