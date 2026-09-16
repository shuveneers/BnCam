package com.bncam.core.quality

/**
 * Mirrors frozen physical shutter-time S/O into the capture-local FinalSensorCalibration mirror.
 *
 * Native transport no longer carries a physical mode/source integer: validated frozen S/O is the
 * complete native physical-noise contract. OEM/System/Manual/Preset source identity remains only
 * in PhysicalNoiseState. The old Kotlin noiseModelMode field is retained as a non-authoritative
 * compatibility mirror for older diagnostics; SPECTRA enablement remains independent.
 */
fun FinalSensorCalibration.withPhysicalNoiseAuthority(): FinalSensorCalibration {
    val snapshot = noiseSnapshot ?: return this
    val physical = snapshot.physicalNoiseState()
    val interleaved = physical.toInterleavedProfileOrNull()
    val canApply = interleaved != null && normalizationCalibrationValid && cfaSupportedForBayerNoiseModel

    val notAppliedReason = when {
        interleaved == null -> "physical_shutter_noise_model_unavailable"
        !normalizationCalibrationValid -> "missing_valid_black_or_white_level"
        !cfaSupportedForBayerNoiseModel -> "unsupported_cfa_layout_${base.cfaName}"
        else -> "none"
    }

    val physicalSource = buildString {
        append("Physical shutter noise model ")
        append(physical.effectiveSource)
        append(" [")
        append(physical.provenance)
        append(']')
    }
    val physicalFallback = physical.fallbackReason ?: "None"
    val bridgeWarning = "PhysicalNoiseAuthorityBridge: source=${physical.effectiveSource}; " +
        "requested=${physical.requestedSource}; modelAvailable=${physical.modelAvailable}; " +
        "applied=$canApply; transport=V2_EXPLICIT_PHYSICAL_SO; sourceIdentityInLegacyCalibrationMode=false; " +
        "spectraIsoFallback=false; SPECTRA remains independent=${spectraProcessingEnabled}"
    val warnings = pipelineWarnings
        .filterNot { it.startsWith("PhysicalNoiseAuthorityBridge:") }
        .plus(bridgeWarning)

    val updatedApplicability = applicability.map { (key, value) ->
        if (key == "Noise Model Applied To") {
            key to if (canApply) {
                "RAW_DOMAIN_NATIVE_ISP_PHYSICAL_SHUTTER_SO"
            } else {
                "NOT_APPLIED_$notAppliedReason"
            }
        } else {
            key to value
        }
    }

    return copy(
        // Remove retired direct-S/O override state from every downstream consumer. Persistence is
        // intentionally untouched for migration/forensics; only the capture-local runtime object is
        // sanitized here.
        override = override.copy(
            noiseMode = "PhysicalNoiseState",
            noisePresetName = physical.effectiveSource,
            manualNoiseValues = null
        ),
        // Legacy Kotlin diagnostic mirror only; it is no longer transported through JNI.
        noiseModelMode = if (interleaved != null) "Auto" else "Off",
        effectiveNoiseProfile = interleaved,
        effectiveNoiseProfileSource = physicalSource,
        effectiveNoiseProfileFallbackReason = physicalFallback,
        hasNoiseProfile = interleaved != null,
        noiseProfileValid = interleaved != null,
        effectiveNoiseProfileApplied = canApply,
        noiseProfileNotAppliedReason = notAppliedReason,
        manualNoiseAnchorIso = 100.0,
        manualNoiseGainRatio = 1.0,
        manualNoiseSingleAnchorScaled = false,
        effectiveNoiseProfilePairCount = if (interleaved != null) 4 else 0,
        effectiveNoiseProfileChannelCount = if (interleaved != null) 4 else 0,
        effectiveNoiseProfileFormula = "variance = S * x + O (physical shutter snapshot)",
        effectiveNoiseProfileChannelMap = "Canonical R,Gr,Gb,B from PhysicalNoiseState",
        applicability = updatedApplicability,
        pipelineWarnings = warnings
    )
}

private const val PHYSICAL_NOISE_TEMPORAL_FUSION_SOURCE_FLAG: Long = 1L shl 9

/**
 * Propagates only the generic multi-frame variance reduction into the physical residual model.
 *
 * The canonical transport names are `physicalFusionVarianceScale` and
 * `physicalEffectiveFrameCount`. Historical native `spectra*` names are accepted only as read-only
 * compatibility aliases until the native merge telemetry is renamed. No SPECTRA fit/observer
 * coefficient is consumed here.
 */
fun FinalSensorCalibration.withPhysicalMergeStats(stats: String): FinalSensorCalibration {
    if (stats.isBlank()) return this
    val snapshot = noiseSnapshot ?: return this
    val physical = snapshot.physicalNoiseState()
    if (!physical.modelAvailable) return this

    val values = stats.split(';')
        .mapNotNull { entry ->
            val split = entry.indexOf('=')
            if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
        }
        .toMap()
    val fusionVarianceScale = (values["physicalFusionVarianceScale"]
        ?: values["spectraFusionVarianceScale"])
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }
        ?.coerceIn(0.04, 1.0)
        ?: 1.0
    if (kotlin.math.abs(fusionVarianceScale - 1.0) < 1.0e-9) return this

    val oldS = snapshot.effectiveS
    val oldO = snapshot.effectiveO
    if (oldS.size < 4 || oldO.size < 4) return this
    val propagatedS = DoubleArray(4) { channel ->
        (oldS[channel] * fusionVarianceScale).coerceAtLeast(0.0)
    }
    val propagatedO = DoubleArray(4) { channel ->
        (oldO[channel] * fusionVarianceScale).coerceAtLeast(0.0)
    }
    if (propagatedS.any { !it.isFinite() } || propagatedO.any { !it.isFinite() }) return this

    val propagatedSnapshot = snapshot.copy(
        effectiveS = propagatedS,
        effectiveO = propagatedO,
        sourceFlags = snapshot.sourceFlags or PHYSICAL_NOISE_TEMPORAL_FUSION_SOURCE_FLAG
    )
    val propagatedProfile = DoubleArray(8) { index ->
        val channel = index / 2
        if (index % 2 == 0) propagatedS[channel] else propagatedO[channel]
    }
    val effectiveFrames = (values["physicalEffectiveFrameCount"]
        ?: values["spectraEffectiveFrameCount"])
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 1.0 }
        ?: 1.0
    val warning = "PhysicalNoisePropagation: fusionVarianceScale=$fusionVarianceScale; " +
        "effectiveFrames=$effectiveFrames; SPECTRA fit coefficients ignored"
    val physicalProfileWasApplied = effectiveNoiseProfileApplied

    return copy(
        effectiveNoiseProfile = propagatedProfile,
        effectiveNoiseProfileSource = "$effectiveNoiseProfileSource + PHYSICAL_TEMPORAL_FUSION",
        effectiveNoiseProfileApplied = physicalProfileWasApplied,
        effectiveNoiseProfilePairCount = 4,
        effectiveNoiseProfileChannelCount = 4,
        noiseSnapshot = propagatedSnapshot,
        pipelineWarnings = pipelineWarnings
            .filterNot { it.startsWith("PhysicalNoisePropagation:") }
            .plus(warning)
    )
}

/**
 * Re-freezes the physical model for the exact shutter identity before any RAW merge or native
 * render consumes it. A changed ISO intentionally triggers the Phase-5 physical resolver again;
 * copies later in the same capture identity stay locked.
 */
fun FinalSensorCalibration.withPhysicalCaptureIdentity(
    sourceFormat: String,
    captureIso: Int,
    exposureTimeNs: Long,
    postRawSensitivityBoost: Int?,
    cfaPattern: Int
): FinalSensorCalibration {
    val snapshot = noiseSnapshot ?: return this
    val resolvedIso = captureIso.takeIf { it > 0 } ?: snapshot.iso
    val resolvedExposure = exposureTimeNs.takeIf { it > 0L } ?: snapshot.exposureTimeNs
    val resolvedBoost = postRawSensitivityBoost ?: snapshot.postRawSensitivityBoost
    val resolvedSource = sourceFormat.ifBlank { snapshot.sourceFormat }
    val resolvedCfa = cfaPattern.takeIf { it >= 0 } ?: snapshot.cfaPattern
    val shutterSnapshot = snapshot.copy(
        sourceFormat = resolvedSource,
        iso = resolvedIso,
        exposureTimeNs = resolvedExposure,
        postRawSensitivityBoost = resolvedBoost,
        cfaPattern = resolvedCfa
    )
    return if (shutterSnapshot == snapshot) this else copy(noiseSnapshot = shutterSnapshot)
}
