package com.bncam.core.quality

internal data class PhysicalTemporalNoiseDecision(
    val enabled: Boolean,
    val effectiveS: DoubleArray,
    val effectiveO: DoubleArray,
    val confidence: Float,
    val authoritySource: String
) {
    companion object {
        fun disabled(reason: String) = PhysicalTemporalNoiseDecision(
            enabled = false,
            effectiveS = DoubleArray(4),
            effectiveO = DoubleArray(4),
            confidence = 0.0f,
            authoritySource = reason
        )
    }
}

/**
 * Read-only temporal consumer policy for the frozen shutter-time physical noise model.
 *
 * This object never reads SPECTRA/Neural profile state and cannot enable adaptation. Temporal RAW
 * fusion may use the physical S/O vectors for weighting, but it must not select a source, alter
 * Dynamic ISO, fit replacement S/O, or let the SPECTRA toggle change physical behavior.
 */
internal object PhysicalTemporalNoisePolicy {
    fun resolve(
        snapshotEffectiveS: DoubleArray?,
        snapshotEffectiveO: DoubleArray?
    ): PhysicalTemporalNoiseDecision {
        val snapshotS = snapshotEffectiveS?.takeIf(::validFour)
        val snapshotO = snapshotEffectiveO?.takeIf(::validFour)
        if (snapshotS == null || snapshotO == null || !hasEnergy(snapshotS, snapshotO)) {
            return PhysicalTemporalNoiseDecision.disabled("PHYSICAL_SHUTTER_SNAPSHOT_REQUIRED")
        }

        return PhysicalTemporalNoiseDecision(
            enabled = true,
            effectiveS = snapshotS.copyOf(4),
            effectiveO = snapshotO.copyOf(4),
            confidence = 1.0f,
            authoritySource = "PHYSICAL_SHUTTER_SNAPSHOT_FIXED_SO"
        )
    }

    private fun validFour(values: DoubleArray): Boolean =
        values.size >= 4 && values.take(4).all { it.isFinite() && it >= 0.0 }

    private fun hasEnergy(s: DoubleArray, o: DoubleArray): Boolean =
        s.take(4).any { it > 0.0 } || o.take(4).any { it > 0.0 }
}
