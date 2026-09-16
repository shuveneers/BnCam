package com.bncam.core.quality

/**
 * Immutable SPECTRA observability/input state derived from the physical shutter model.
 *
 * This class is not a physical-noise authority. [PhysicalNoiseState] owns the resolved
 * OEM/System/Manual/Preset S/O. This state only exposes a read-only copy to the optional SPECTRA
 * add-on together with SPECTRA-specific profile and lens-shading context.
 */
class CaptureNoiseState(
    val lensKey: String,
    val sourceFormat: String,
    val sensorPixelMode: String,
    val captureIso: Int,
    val exposureTimeNs: Long,
    val postRawSensitivityBoost: Int?,
    val cfaPattern: Int,
    val cfaName: String,
    val whiteLevel: Int,
    blackLevelMosaicOrder: FloatArray,
    cameraSCanonical: DoubleArray,
    cameraOCanonical: DoubleArray,
    effectiveSCanonical: DoubleArray,
    effectiveOCanonical: DoubleArray,
    val lensShadingAlreadyApplied: Boolean,
    val lensShadingMapFromMetadata: Boolean,
    val lensShadingMapColumns: Int?,
    val lensShadingMapRows: Int?,
    val lensShadingGainP10: Float?,
    val lensShadingGainP50: Float?,
    val lensShadingGainP90: Float?,
    val profileSpectraSettings: ProfileNoiseTuning,
    val modelSourceFlags: Long,
    /** Confidence of the physical input model, not SPECTRA fit/adaptation confidence. */
    val modelConfidence: Float,
    val timestampNs: Long,
    val physicalNoiseRequestedSource: String = "LEGACY",
    val physicalNoiseEffectiveSource: String = "LEGACY",
    val physicalNoiseProvenance: String = "legacy capture noise state",
    val physicalNoiseFallbackReason: String? = null,
    val physicalNoiseEffectiveModelIso: Double? = null,
    val physicalNoiseDynamicIsoEnabled: Boolean = false,
    val physicalNoiseDynamicIsoCoefficient: Double? = null,
    val physicalNoiseIsoStep: Double? = null,
    val physicalNoisePresetName: String? = null,
    val physicalNoiseSettingsReady: Boolean = true
) {
    private val blackLevels = blackLevelMosaicOrder.clone()
    private val cameraSValues = cameraSCanonical.clone()
    private val cameraOValues = cameraOCanonical.clone()
    private val effectiveSValues = effectiveSCanonical.clone()
    private val effectiveOValues = effectiveOCanonical.clone()

    val blackLevelMosaicOrder: FloatArray get() = blackLevels.clone()
    val cameraSCanonical: DoubleArray get() = cameraSValues.clone()
    val cameraOCanonical: DoubleArray get() = cameraOValues.clone()
    val effectiveSCanonical: DoubleArray get() = effectiveSValues.clone()
    val effectiveOCanonical: DoubleArray get() = effectiveOValues.clone()

    /** OEM/Camera2 S/O evidence only. It is not the selected physical-authority test. */
    val oemNoiseEvidenceAvailable: Boolean
        get() = validSoModel(cameraSValues, cameraOValues)

    /** Selected physical S/O availability after OEM/System/Manual/Preset resolution. */
    val physicalNoiseModelAvailable: Boolean
        get() = validSoModel(effectiveSValues, effectiveOValues)

    /**
     * Deprecated source-compatibility alias. New code must use [physicalNoiseModelAvailable].
     * It is intentionally omitted from the V2 trace schema.
     */
    @Deprecated("Use physicalNoiseModelAvailable")
    val effectiveNoiseModelAvailable: Boolean
        get() = physicalNoiseModelAvailable

    /** Pixel-mutation authority remains separate from physical-model availability. */
    val spectraProcessingEnabled: Boolean
        get() = profileSpectraSettings.spectraEnabled && physicalNoiseModelAvailable && modelConfidence > 0f

    /** Processing confidence is zero when the optional add-on is disabled. */
    val spectraProcessingConfidence: Float
        get() = if (spectraProcessingEnabled) modelConfidence.coerceIn(0f, 1f) else 0f

    init {
        require(lensKey.isNotBlank()) { "lensKey cannot be blank" }
        require(whiteLevel > 0) { "whiteLevel must be > 0" }
        require(blackLevels.size == 4) { "black levels must contain four values" }
        require(cameraSValues.size == 4 && cameraOValues.size == 4) {
            "camera S/O must use canonical R,Gr,Gb,B order"
        }
        require(effectiveSValues.size == 4 && effectiveOValues.size == 4) {
            "effective S/O must use canonical R,Gr,Gb,B order"
        }
        require(modelConfidence.isFinite() && modelConfidence in 0f..1f) {
            "modelConfidence must be finite in [0,1]"
        }
    }

    fun toTraceMap(): Map<String, Any?> = linkedMapOf(
        "lensKey" to lensKey,
        "sourceFormat" to sourceFormat,
        "sensorPixelMode" to sensorPixelMode,
        "captureIso" to captureIso,
        "rawNoiseEvidenceIso" to captureIso,
        "exposureTimeNs" to exposureTimeNs,
        "postRawSensitivityBoost" to postRawSensitivityBoost,
        "postRawBoostAffectsRawNoiseEvidence" to false,
        "cfaPattern" to cfaPattern,
        "cfaName" to cfaName,
        "canonicalSoOrder" to "R,Gr,Gb,B",
        "blackLevelOrder" to "camera2_2x2_mosaic_order",
        "whiteLevel" to whiteLevel,
        "blackLevelMosaicOrder" to blackLevels.toList(),
        "cameraSCanonical" to cameraSValues.toList(),
        "cameraOCanonical" to cameraOValues.toList(),
        "effectiveSCanonical" to effectiveSValues.toList(),
        "effectiveOCanonical" to effectiveOValues.toList(),
        "oemNoiseEvidenceAvailable" to oemNoiseEvidenceAvailable,
        "physicalNoiseModelAvailable" to physicalNoiseModelAvailable,
        "physicalNoiseRequestedSource" to physicalNoiseRequestedSource,
        "physicalNoiseEffectiveSource" to physicalNoiseEffectiveSource,
        "physicalNoiseProvenance" to physicalNoiseProvenance,
        "physicalNoiseFallbackReason" to physicalNoiseFallbackReason,
        "physicalNoiseEffectiveModelIso" to physicalNoiseEffectiveModelIso,
        "physicalNoiseDynamicIsoEnabled" to physicalNoiseDynamicIsoEnabled,
        "physicalNoiseDynamicIsoCoefficient" to physicalNoiseDynamicIsoCoefficient,
        "physicalNoiseDynamicIsoCoefficientRange" to "0.00..2.00",
        "physicalNoiseDynamicIsoFormula" to "ISO_NM=trunc(50+k*(ISO_capture-50))",
        "physicalNoiseIsoStep" to physicalNoiseIsoStep,
        "physicalNoisePresetName" to physicalNoisePresetName,
        "physicalNoiseSettingsReady" to physicalNoiseSettingsReady,
        "physicalNoiseTransportContract" to "V2_EXPLICIT_PHYSICAL_SO",
        "physicalNoiseSourceIdentityTransport" to "SNAPSHOT_METADATA_ONLY",
        "spectraIsoFallbackAuthority" to false,
        "spectraMaySynthesizePhysicalModel" to false,
        "lensShadingAlreadyApplied" to lensShadingAlreadyApplied,
        "lensShadingMapFromMetadata" to lensShadingMapFromMetadata,
        "lensShadingMapColumns" to lensShadingMapColumns,
        "lensShadingMapRows" to lensShadingMapRows,
        "lensShadingMapPayload" to "OWNED_BY_NATIVE_CAPTURE_METADATA",
        "lensShadingGainP10" to lensShadingGainP10,
        "lensShadingGainP50" to lensShadingGainP50,
        "lensShadingGainP90" to lensShadingGainP90,
        "profileSpectraSettings" to profileSpectraSettings.toTraceMap(),
        "modelSourceFlags" to modelSourceFlags,
        "modelConfidence" to modelConfidence,
        "spectraProcessingEnabled" to spectraProcessingEnabled,
        "spectraProcessingConfidence" to spectraProcessingConfidence,
        "noiseAuthorityContract" to "PHYSICAL_NOISE_STATE_OWNER_SPECTRA_READ_ONLY_CONSUMER",
        "spectraMayMutatePhysicalSo" to false,
        "timestampNs" to timestampNs
    )

    companion object {
        private fun validSoModel(s: DoubleArray, o: DoubleArray): Boolean =
            s.size == 4 && o.size == 4 &&
                s.all { it.isFinite() && it >= 0.0 } &&
                o.all { it.isFinite() && it >= 0.0 } &&
                (s.any { it > 1.0e-12 } || o.any { it > 1.0e-12 })

        fun from(
            snapshot: NoiseModelSnapshotV3,
            profileNoiseTuning: ProfileNoiseTuning,
            lensShadingAlreadyApplied: Boolean,
            lensShadingMapFromMetadata: Boolean = false,
            lensShadingMapColumns: Int? = null,
            lensShadingMapRows: Int? = null,
            lensShadingGainP10: Float? = null,
            lensShadingGainP50: Float? = null,
            lensShadingGainP90: Float? = null,
            sensorPixelMode: String = "UNAVAILABLE_IN_V3_SNAPSHOT"
        ): CaptureNoiseState {
            val physical = snapshot.physicalNoiseState()
            return CaptureNoiseState(
                lensKey = snapshot.lensKey,
                sourceFormat = snapshot.sourceFormat,
                sensorPixelMode = sensorPixelMode,
                captureIso = snapshot.iso,
                exposureTimeNs = snapshot.exposureTimeNs,
                postRawSensitivityBoost = snapshot.postRawSensitivityBoost,
                cfaPattern = snapshot.cfaPattern,
                cfaName = snapshot.cfaName,
                whiteLevel = snapshot.whiteLevel,
                blackLevelMosaicOrder = snapshot.blackLevel,
                cameraSCanonical = physical.cameraS,
                cameraOCanonical = physical.cameraO,
                effectiveSCanonical = physical.effectiveS,
                effectiveOCanonical = physical.effectiveO,
                lensShadingAlreadyApplied = lensShadingAlreadyApplied,
                lensShadingMapFromMetadata = lensShadingMapFromMetadata,
                lensShadingMapColumns = lensShadingMapColumns,
                lensShadingMapRows = lensShadingMapRows,
                lensShadingGainP10 = lensShadingGainP10,
                lensShadingGainP50 = lensShadingGainP50,
                lensShadingGainP90 = lensShadingGainP90,
                profileSpectraSettings = profileNoiseTuning.sanitized(),
                modelSourceFlags = snapshot.sourceFlags,
                modelConfidence = physical.confidence,
                timestampNs = snapshot.timestampNs,
                physicalNoiseRequestedSource = physical.requestedSource,
                physicalNoiseEffectiveSource = physical.effectiveSource,
                physicalNoiseProvenance = physical.provenance,
                physicalNoiseFallbackReason = physical.fallbackReason,
                physicalNoiseEffectiveModelIso = physical.effectiveModelIso,
                physicalNoiseDynamicIsoEnabled = physical.dynamicIsoEnabled,
                physicalNoiseDynamicIsoCoefficient = physical.dynamicIsoCoefficient,
                physicalNoiseIsoStep = physical.isoStep,
                physicalNoisePresetName = physical.presetName,
                physicalNoiseSettingsReady = physical.settingsReady
            )
        }
    }
}

internal fun ProfileNoiseTuning.toTraceMap(): Map<String, Any> = linkedMapOf(
    "spectraEnabled" to spectraEnabled,
    "neuralDenoiseStrength" to neuralDenoiseStrength,
    "neuralAdaptiveResponse" to neuralAdaptiveResponse,
    "spectraLuma" to spectraLuma,
    "spectraChroma" to spectraChroma,
    "spectraDetailProtection" to spectraDetailProtection,
    "spectraLowFrequency" to spectraLowFrequency
)
