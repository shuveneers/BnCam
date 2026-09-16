package com.bncam.core.quality

/**
 * SPECTRA-independent view of the physical shutter-time noise authority.
 *
 * Channel order is always canonical R, Gr, Gb, B. The physical model is valid when the resolved
 * S/O vectors are finite, non-negative and contain non-zero noise energy. Camera S/O is retained
 * only as OEM evidence; consumers must use effectiveS/effectiveO.
 */
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

    /** Interleaved [S_R,O_R,S_Gr,O_Gr,S_Gb,O_Gb,S_B,O_B] for legacy/native transport. */
    fun toInterleavedProfileOrNull(): DoubleArray? {
        if (!modelAvailable) return null
        return DoubleArray(8) { index ->
            val channel = index / 2
            if (index % 2 == 0) _effectiveS[channel] else _effectiveO[channel]
        }
    }

    /** Physical variance in the normalized RAW domain: Var(x)=S*x+O. */
    fun variance(channel: Int, normalizedSignal: Double): Double {
        require(channel in 0..3) { "channel must be canonical R/Gr/Gb/B index 0..3" }
        if (!modelAvailable || !normalizedSignal.isFinite()) return 0.0
        val x = normalizedSignal.coerceAtLeast(0.0)
        return (_effectiveS[channel] * x + _effectiveO[channel]).coerceAtLeast(0.0)
    }

    fun tracePairs(): List<Pair<String, String>> = listOf(
        "Physical Noise Model Available" to modelAvailable.toString(),
        "Physical Noise Requested Source" to requestedSource,
        "Physical Noise Effective Source" to effectiveSource,
        "Physical Noise Provenance" to provenance,
        "Physical Noise Fallback Reason" to (fallbackReason ?: "none"),
        "Physical Noise Capture ISO" to captureIso.toString(),
        "Physical Noise Effective Model ISO" to (effectiveModelIso?.toString() ?: "OEM_DIRECT"),
        "Physical Noise Dynamic ISO Enabled" to dynamicIsoEnabled.toString(),
        "Physical Noise Dynamic ISO Coefficient" to (dynamicIsoCoefficient?.toString() ?: "N/A"),
        "Physical Noise ISO Step" to (isoStep?.toString() ?: "N/A"),
        "Physical Noise Preset" to (presetName ?: "none"),
        "Physical Noise Confidence" to confidence.toString(),
        "Physical Noise Canonical S" to _effectiveS.take(4).joinToString(prefix = "[", postfix = "]"),
        "Physical Noise Canonical O" to _effectiveO.take(4).joinToString(prefix = "[", postfix = "]")
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
