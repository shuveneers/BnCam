package com.bncam.core.quality

/**
 * SPECTRA-independent view of the physical shutter-time noise authority.
 *
 * Channel order is always canonical R, Gr, Gb, B. The physical model is valid when the resolved
 * S/O vectors are finite, non-negative and contain non-zero noise energy. Camera S/O is retained
 * only as OEM evidence; consumers must use effectiveS/effectiveO.
 */

/**
 * Canonical Kotlin/JNI physical-noise payload contract.
 *
 * The order is always [S_R,O_R,S_Gr,O_Gr,S_Gb,O_Gb,S_B,O_B], independent of RAW10/RAW_SENSOR
 * storage layout or mosaic-site order. Callers must decide availability before packing; this object
 * owns serialization only so every RAW route crosses JNI with the exact same channel order.
 */
internal object PhysicalNoiseSoContract {
    const val CHANNEL_COUNT: Int = 4
    const val VALUE_COUNT: Int = CHANNEL_COUNT * 2

    fun pack(effectiveS: DoubleArray, effectiveO: DoubleArray): DoubleArray {
        require(effectiveS.size >= CHANNEL_COUNT && effectiveO.size >= CHANNEL_COUNT) {
            "physical S/O requires four canonical R/Gr/Gb/B channels"
        }
        return doubleArrayOf(
            effectiveS[0], effectiveO[0],
            effectiveS[1], effectiveO[1],
            effectiveS[2], effectiveO[2],
            effectiveS[3], effectiveO[3]
        )
    }
}

class PhysicalNoiseState private constructor(
    val lensKey: String,
    val sourceFormat: String,
    val captureIso: Int,
    val exposureTimeNs: Long,
    val postRawSensitivityBoost: Int?,
    val cfaPattern: Int,
    val cfaName: String,
    val whiteLevel: Int,
    blackLevel: FloatArray,
    cameraS: DoubleArray,
    cameraO: DoubleArray,
    effectiveS: DoubleArray,
    effectiveO: DoubleArray,
    val requestedSource: String,
    val effectiveSource: String,
    val provenance: String,
    val fallbackReason: String?,
    val effectiveModelIso: Double?,
    val dynamicIsoEnabled: Boolean,
    val dynamicIsoCoefficient: Double?,
    val isoStep: Double?,
    val presetName: String?,
    val settingsReady: Boolean,
    val modelAvailable: Boolean,
    val confidence: Float,
    val sourceFlags: Long,
    val timestampNs: Long
) {
    private val _blackLevel = blackLevel.clone()
    private val _cameraS = cameraS.clone()
    private val _cameraO = cameraO.clone()
    private val _effectiveS = effectiveS.clone()
    private val _effectiveO = effectiveO.clone()

    val blackLevel: FloatArray get() = _blackLevel.clone()
    val cameraS: DoubleArray get() = _cameraS.clone()
    val cameraO: DoubleArray get() = _cameraO.clone()
    val effectiveS: DoubleArray get() = _effectiveS.clone()
    val effectiveO: DoubleArray get() = _effectiveO.clone()

    init {
        require(lensKey.isNotBlank()) { "lensKey cannot be blank" }
        require(whiteLevel > 0) { "whiteLevel must be > 0" }
        require(_blackLevel.size >= 4) { "blackLevel must contain four canonical channels" }
        require(_cameraS.size >= 4 && _cameraO.size >= 4) { "camera S/O must contain four canonical channels" }
        require(_effectiveS.size >= 4 && _effectiveO.size >= 4) { "effective S/O must contain four canonical channels" }
        require(confidence.isFinite() && confidence in 0.0f..1.0f) { "confidence must be finite in [0,1]" }
    }

    /** Canonical JNI payload [S_R,O_R,S_Gr,O_Gr,S_Gb,O_Gb,S_B,O_B]. */
    fun toInterleavedProfileOrNull(): DoubleArray? {
        if (!modelAvailable) return null
        return PhysicalNoiseSoContract.pack(_effectiveS, _effectiveO)
    }

    /** Physical variance in the normalized RAW domain: Var(x)=S*x+O. */
    fun variance(channel: Int, normalizedSignal: Double): Double {
        require(channel in 0..3) { "channel must be canonical R/Gr/Gb/B index 0..3" }
        if (!modelAvailable || !normalizedSignal.isFinite()) return 0.0
        val x = normalizedSignal.coerceAtLeast(0.0)
        return (_effectiveS[channel] * x + _effectiveO[channel]).coerceAtLeast(0.0)
    }

    /**
     * Phase-9 high-signal authority trace.
     *
     * Keys are intentionally source/authority oriented instead of exposing the historical
     * SensorNoiseProfile mirror. The current product contract has no separate model-ISO override:
     * the physical model uses capture ISO directly or Dynamic ISO upstream in the resolver.
     */
    fun tracePairs(): List<Pair<String, String>> = listOf(
        "Selected Source" to requestedSource,
        "Effective Source" to effectiveSource,
        "Preset Name / ID" to (presetName ?: "none"),
        "Capture ISO" to captureIso.toString(),
        "Effective Noise ISO" to (effectiveModelIso?.toString() ?: "OEM_DIRECT"),
        "Dynamic ISO Mode" to if (dynamicIsoEnabled) "DYNAMIC" else "CAPTURE_ISO",
        "Dynamic ISO Coefficient" to (dynamicIsoCoefficient?.toString() ?: "N/A"),
        "CFA" to "$cfaPattern / $cfaName",
        "S R" to _effectiveS[0].toString(),
        "S Gr" to _effectiveS[1].toString(),
        "S Gb" to _effectiveS[2].toString(),
        "S B" to _effectiveS[3].toString(),
        "O R" to _effectiveO[0].toString(),
        "O Gr" to _effectiveO[1].toString(),
        "O Gb" to _effectiveO[2].toString(),
        "O B" to _effectiveO[3].toString(),
        "Model Valid" to modelAvailable.toString(),
        "Confidence" to confidence.toString(),
        "Fallback Reason" to (fallbackReason ?: "none"),
        "Frozen Capture State" to "true",
        "Settings Ready" to settingsReady.toString(),
        "Provenance" to provenance
    )

    companion object {
        fun from(snapshot: NoiseModelSnapshotV3): PhysicalNoiseState = PhysicalNoiseState(
            lensKey = snapshot.lensKey,
            sourceFormat = snapshot.sourceFormat,
            captureIso = snapshot.iso,
            exposureTimeNs = snapshot.exposureTimeNs,
            postRawSensitivityBoost = snapshot.postRawSensitivityBoost,
            cfaPattern = snapshot.cfaPattern,
            cfaName = snapshot.cfaName,
            whiteLevel = snapshot.whiteLevel,
            blackLevel = snapshot.blackLevel,
            cameraS = snapshot.cameraS,
            cameraO = snapshot.cameraO,
            effectiveS = snapshot.effectiveS,
            effectiveO = snapshot.effectiveO,
            requestedSource = snapshot.physicalNoiseRequestedSource,
            effectiveSource = snapshot.physicalNoiseEffectiveSource,
            provenance = snapshot.physicalNoiseProvenance,
            fallbackReason = snapshot.physicalNoiseFallbackReason,
            effectiveModelIso = snapshot.physicalNoiseEffectiveModelIso,
            dynamicIsoEnabled = snapshot.physicalNoiseDynamicIsoEnabled,
            dynamicIsoCoefficient = snapshot.physicalNoiseDynamicIsoCoefficient,
            isoStep = snapshot.physicalNoiseIsoStep,
            presetName = snapshot.physicalNoisePresetName,
            settingsReady = snapshot.physicalNoiseSettingsReady,
            modelAvailable = snapshot.physicalNoiseModelAvailable,
            confidence = snapshot.physicalNoiseModelConfidence,
            sourceFlags = snapshot.sourceFlags,
            timestampNs = snapshot.timestampNs
        )
    }
}
