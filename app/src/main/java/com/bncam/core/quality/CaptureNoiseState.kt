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
        "exposureTimeNs" to exposureTimeNs,
        "postRawSensitivityBoost" to postRawSensitivityBoost,
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
        "timestampNs" to timestampNs
    )

    companion object {
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
        ): CaptureNoiseState = CaptureNoiseState(
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
            cameraSCanonical = snapshot.cameraS,
            cameraOCanonical = snapshot.cameraO,
            effectiveSCanonical = snapshot.effectiveS,
            effectiveOCanonical = snapshot.effectiveO,
            lensShadingAlreadyApplied = lensShadingAlreadyApplied,
            lensShadingMapFromMetadata = lensShadingMapFromMetadata,
            lensShadingMapColumns = lensShadingMapColumns,
            lensShadingMapRows = lensShadingMapRows,
            lensShadingGainP10 = lensShadingGainP10,
            lensShadingGainP50 = lensShadingGainP50,
            lensShadingGainP90 = lensShadingGainP90,
            profileSpectraSettings = profileNoiseTuning.sanitized(),
            modelSourceFlags = snapshot.sourceFlags,
            modelConfidence = if (snapshot.isSpectraActive()) {
                snapshot.signalModelConfidence.coerceIn(0f, 1f)
            } else {
                0f
            },
            timestampNs = snapshot.timestampNs
        )
    }
}

internal fun ProfileNoiseTuning.toTraceMap(): Map<String, Any> = linkedMapOf(
    "spectraStrength" to spectraStrength,
    "spectraLuma" to spectraLuma,
    "spectraChroma" to spectraChroma,
    "spectraDetailProtection" to spectraDetailProtection,
    "spectraLowFrequency" to spectraLowFrequency
)
