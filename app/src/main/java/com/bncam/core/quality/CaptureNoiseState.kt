package com.bncam.core.quality

/**
 * Immutable shutter-time source of truth for SPECTRA 2 observability.
 *
 * This contract deliberately keeps Camera2 black levels in mosaic order while S/O arrays use
 * canonical R, G1, G2, B order. It is derived from the existing [NoiseModelSnapshotV3] so the
 * proven capture path remains unchanged during Milestone 1.
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
    /** Physical/effective sensor-model confidence. It does not become zero merely because SPECTRA is Off. */
    val modelConfidence: Float,
    val timestampNs: Long
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

    /** Camera2 physical S/O availability; independent from whether SPECTRA mutates pixels. */
    val physicalNoiseModelAvailable: Boolean
        get() = validSoModel(cameraSValues, cameraOValues)

    /** Effective S/O availability after an explicit manual/fusion authority may have replaced Camera2. */
    val effectiveNoiseModelAvailable: Boolean
        get() = validSoModel(effectiveSValues, effectiveOValues)

    /** Pixel-mutation authority remains separate from sensor-model measurement authority. */
    val spectraProcessingEnabled: Boolean
        get() = profileSpectraSettings.spectraEnabled && modelConfidence > 0f

    /** Processing confidence is zero when mutation is disabled; physical model confidence is retained above. */
    val spectraProcessingConfidence: Float
        get() = if (spectraProcessingEnabled) modelConfidence.coerceIn(0f, 1f) else 0f

    init {
        require(lensKey.isNotBlank()) { "lensKey cannot be blank" }
        require(whiteLevel > 0) { "whiteLevel must be > 0" }
        require(blackLevels.size == 4) { "black levels must contain four mosaic-order values" }
        require(cameraSValues.size == 4 && cameraOValues.size == 4) {
            "camera S/O must use canonical R,G1,G2,B order"
        }
        require(effectiveSValues.size == 4 && effectiveOValues.size == 4) {
            "effective S/O must use canonical R,G1,G2,B order"
        }
    }

    fun toTraceMap(): Map<String, Any?> = linkedMapOf(
        "lensKey" to lensKey,
        "sourceFormat" to sourceFormat,
        "sensorPixelMode" to sensorPixelMode,
        "captureIso" to captureIso,
        // SENSOR_SENSITIVITY is the RAW-domain sensitivity observation. Post-RAW boost is retained
        // as metadata/telemetry, but 0220 forbids folding it into pre-demosaic RAW noise evidence.
        "rawNoiseEvidenceIso" to captureIso,
        "exposureTimeNs" to exposureTimeNs,
        "postRawSensitivityBoost" to postRawSensitivityBoost,
        "postRawBoostAffectsRawNoiseEvidence" to false,
        "cfaPattern" to cfaPattern,
        "cfaName" to cfaName,
        "canonicalSoOrder" to "R,G1,G2,B",
        "blackLevelOrder" to "camera2_2x2_mosaic_order",
        "whiteLevel" to whiteLevel,
        "blackLevelMosaicOrder" to blackLevels.toList(),
        "cameraSCanonical" to cameraSValues.toList(),
        "cameraOCanonical" to cameraOValues.toList(),
        "effectiveSCanonical" to effectiveSValues.toList(),
        "effectiveOCanonical" to effectiveOValues.toList(),
        "physicalNoiseModelAvailable" to physicalNoiseModelAvailable,
        "effectiveNoiseModelAvailable" to effectiveNoiseModelAvailable,
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
        "noiseAuthorityContract" to "PHYSICAL_SO_PRIMARY_ISO_FALLBACK_NO_LENS_ID_STRENGTH_SHORTCUT",
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
            val cameraS = snapshot.cameraS
            val cameraO = snapshot.cameraO
            val effectiveS = snapshot.effectiveS
            val effectiveO = snapshot.effectiveO
            val effectiveModelValid = validSoModel(effectiveS, effectiveO)
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
                cameraSCanonical = cameraS,
                cameraOCanonical = cameraO,
                effectiveSCanonical = effectiveS,
                effectiveOCanonical = effectiveO,
                lensShadingAlreadyApplied = lensShadingAlreadyApplied,
                lensShadingMapFromMetadata = lensShadingMapFromMetadata,
                lensShadingMapColumns = lensShadingMapColumns,
                lensShadingMapRows = lensShadingMapRows,
                lensShadingGainP10 = lensShadingGainP10,
                lensShadingGainP50 = lensShadingGainP50,
                lensShadingGainP90 = lensShadingGainP90,
                profileSpectraSettings = profileNoiseTuning.sanitized(),
                modelSourceFlags = snapshot.sourceFlags,
                modelConfidence = if (effectiveModelValid) {
                    snapshot.signalModelConfidence.coerceIn(0f, 1f)
                } else {
                    0f
                },
                timestampNs = snapshot.timestampNs
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
