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
 * Separates physical Camera2 S/O availability from optional SPECTRA adaptation.
 *
 * The physical baseline is valid when the camera supplied a usable SENSOR_NOISE_PROFILE
 * and the resolved calibration confirms that the model is valid in the normalized Bayer
 * domain. SPECTRA Off therefore does not mean "no physical noise model".
 *
 * Adaptive S/O re-fitting remains SPECTRA-owned. In the physical baseline the native
 * temporal observer receives fixed adaptation bounds [1,1].
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
        val adaptive = spectraProcessingEnabled &&
            !spectraModeName.isNullOrBlank() &&
            !spectraModeName.equals("Legacy", ignoreCase = true) &&
            !spectraModeName.equals("Off", ignoreCase = true)

        val physicalCameraModelValid =
            cameraNoiseProfilePresent &&
            cameraNoiseProfileValid &&
            effectiveNoiseProfileApplied &&
            normalizationCalibrationValid &&
            cfaSupportedForBayerNoiseModel

        // Preserve the established Manual/Auto SPECTRA path even when it is not sourced
        // from Camera2. The new physical path is stricter and requires Camera2 authority.
        if (!adaptive && !physicalCameraModelValid) {
            return TemporalNoiseModelDecision.disabled("NO_VALID_CAMERA2_PHYSICAL_SO")
        }

        val snapshotS = snapshotEffectiveS?.takeIf(::validFour)
        val snapshotO = snapshotEffectiveO?.takeIf(::validFour)
        val profile = effectiveNoiseProfile?.takeIf { values ->
            values.size >= 8 && values.take(8).all { it.isFinite() && it >= 0.0 }
        }
        val profileS = profile?.let { DoubleArray(4) { ch -> it[ch * 2] } }
        val profileO = profile?.let { DoubleArray(4) { ch -> it[ch * 2 + 1] } }

        val s = snapshotS ?: profileS
        val o = snapshotO ?: profileO
        if (s == null || o == null || !hasEnergy(s, o)) {
            return TemporalNoiseModelDecision.disabled("VALIDITY_FLAGS_WITHOUT_USABLE_SO")
        }

        val mode = if (adaptive) {
            if (spectraModeName.equals("Manual", ignoreCase = true)) 2 else 1
        } else {
            0
        }
        val confidence = (snapshotConfidence ?: 1.0f)
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0f, 1.0f)
            ?: 1.0f

        return TemporalNoiseModelDecision(
            enabled = true,
            adaptiveSpectraCalibration = adaptive,
            spectraMode = mode,
            effectiveS = s.copyOf(4),
            effectiveO = o.copyOf(4),
            confidence = confidence,
            authoritySource = if (adaptive) {
                "SPECTRA_ADAPTIVE_SO"
            } else {
                "PHYSICAL_CAMERA2_FIXED_SO"
            }
        )
    }

    private fun validFour(values: DoubleArray): Boolean =
        values.size >= 4 && values.take(4).all { it.isFinite() && it >= 0.0 }

    private fun hasEnergy(s: DoubleArray, o: DoubleArray): Boolean =
        s.take(4).any { it > 0.0 } || o.take(4).any { it > 0.0 }
}
