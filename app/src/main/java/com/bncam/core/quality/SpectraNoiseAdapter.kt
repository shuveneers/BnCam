package com.bncam.core.quality

/**
 * Immutable input contract for the optional SPECTRA add-on.
 *
 * SPECTRA consumes the already-resolved [PhysicalNoiseState]. It never selects OEM/System/Manual/
 * Preset, never evaluates A/B/C/D, never changes Dynamic ISO and never writes back into the
 * physical S/O authority. Any SPECTRA adaptation remains local to SPECTRA processing.
 */
class SpectraNoiseInputState private constructor(
    val requested: Boolean,
    val enabled: Boolean,
    val disabledReason: String,
    val physicalRequestedSource: String,
    val physicalEffectiveSource: String,
    val physicalProvenance: String,
    val physicalConfidence: Float,
    effectiveS: DoubleArray,
    effectiveO: DoubleArray
) {
    private val _effectiveS = effectiveS.clone()
    private val _effectiveO = effectiveO.clone()

    val effectiveS: DoubleArray get() = _effectiveS.clone()
    val effectiveO: DoubleArray get() = _effectiveO.clone()

    fun tracePairs(): List<Pair<String, String>> = listOf(
        "SPECTRA Requested" to requested.toString(),
        "SPECTRA Enabled" to enabled.toString(),
        "SPECTRA Disabled Reason" to disabledReason,
        "SPECTRA Physical Input Requested Source" to physicalRequestedSource,
        "SPECTRA Physical Input Effective Source" to physicalEffectiveSource,
        "SPECTRA Physical Input Provenance" to physicalProvenance,
        "SPECTRA Physical Input Confidence" to physicalConfidence.toString(),
        "SPECTRA Input S" to _effectiveS.take(4).joinToString(prefix = "[", postfix = "]"),
        "SPECTRA Input O" to _effectiveO.take(4).joinToString(prefix = "[", postfix = "]"),
        "SPECTRA May Mutate Physical S/O" to "false"
    )

    companion object {
        fun from(physical: PhysicalNoiseState, requested: Boolean): SpectraNoiseInputState {
            val enabled = requested && physical.modelAvailable && physical.confidence > 0.0f
            val reason = when {
                !requested -> "PROFILE_SPECTRA_DISABLED"
                !physical.modelAvailable -> "PHYSICAL_NOISE_MODEL_UNAVAILABLE"
                physical.confidence <= 0.0f -> "PHYSICAL_NOISE_MODEL_CONFIDENCE_ZERO"
                else -> "none"
            }
            return SpectraNoiseInputState(
                requested = requested,
                enabled = enabled,
                disabledReason = reason,
                physicalRequestedSource = physical.requestedSource,
                physicalEffectiveSource = physical.effectiveSource,
                physicalProvenance = physical.provenance,
                physicalConfidence = physical.confidence,
                effectiveS = physical.effectiveS,
                effectiveO = physical.effectiveO
            )
        }
    }
}

/**
 * Converts the profile SPECTRA request into an effective add-on state using the frozen physical
 * model. Only SPECTRA-specific compatibility fields are changed. Physical S/O and its provenance
 * are left byte-for-byte unchanged.
 */
fun FinalSensorCalibration.withSpectraNoiseAdapter(): FinalSensorCalibration {
    val snapshot = noiseSnapshot
    if (snapshot == null) {
        if (!spectraProcessingEnabled) return this
        return copy(
            spectraProcessingEnabled = false,
            pipelineWarnings = pipelineWarnings
                .filterNot { it.startsWith("SpectraNoiseAdapter:") }
                .plus("SpectraNoiseAdapter: requested=true; enabled=false; reason=NO_PHYSICAL_SNAPSHOT")
        )
    }

    val physical = snapshot.physicalNoiseState()
    // At this point spectraProcessingEnabled is the profile request intent. The adapter turns it
    // into effective execution authority without modifying the physical model.
    val input = SpectraNoiseInputState.from(
        physical = physical,
        requested = spectraProcessingEnabled
    )
    val warning = "SpectraNoiseAdapter: requested=${input.requested}; enabled=${input.enabled}; " +
        "physicalSource=${input.physicalEffectiveSource}; reason=${input.disabledReason}; " +
        "physicalSOImmutable=true"

    return copy(
        spectraProcessingEnabled = input.enabled,
        pipelineWarnings = pipelineWarnings
            .filterNot { it.startsWith("SpectraNoiseAdapter:") }
            .plus(warning)
    )
}
