package com.bncam.core.quality

import com.bncam.data.settings.PhysicalNoiseModelRuntimeRegistry
import com.bncam.data.settings.StableLensKey

/**
 * Immutable shutter-time snapshot of BnCam's physical sensor noise model.
 *
 * This object is independent of SPECTRA. OEM/System/Manual/Preset is resolved exactly once for a
 * capture identity and then frozen. Generic RAW processing, temporal fusion and variance consumers
 * may always use effectiveS/effectiveO. SPECTRA is only an optional downstream consumer.
 */
class NoiseModelSnapshotV3(
    val lensKey: String,
    val sourceFormat: String,
    val iso: Int,
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
    val chromaUserScale: Float,
    val lumaUserScale: Float,
    val spectraMode: String = "Legacy",
    /** Legacy/SPECTRA adaptation confidence; not the authority switch for the physical model. */
    val signalModelConfidence: Float = 1.0f,
    val sourceFlags: Long = 0L,
    val timestampNs: Long = 0L,
    private val physicalAuthorityLocked: Boolean = false,
    physicalNoiseRequestedSource: String = "UNRESOLVED",
    physicalNoiseEffectiveSource: String = "UNRESOLVED",
    physicalNoiseProvenance: String = "Physical noise runtime not resolved",
    physicalNoiseFallbackReason: String? = null,
    physicalNoiseEffectiveModelIso: Double? = null,
    physicalNoiseDynamicIsoEnabled: Boolean = false,
    physicalNoiseDynamicIsoCoefficient: Double? = null,
    physicalNoiseIsoStep: Double? = null,
    physicalNoisePresetName: String? = null,
    physicalNoiseSettingsReady: Boolean = true
) {
    private val _blackLevel: FloatArray = blackLevel.clone()
    private val _cameraS: DoubleArray = cameraS.clone()
    private val _cameraO: DoubleArray = cameraO.clone()

    private val runtimeResolution = if (!physicalAuthorityLocked) {
        PhysicalNoiseModelRuntimeRegistry.resolve(
            lensId = lensKey,
            captureIso = iso,
            cfaName = cfaName,
            cameraS = _cameraS,
            cameraO = _cameraO
        )
    } else {
        null
    }

    private val _effectiveS: DoubleArray =
        runtimeResolution?.resolved?.effectiveS ?: effectiveS.clone()
    private val _effectiveO: DoubleArray =
        runtimeResolution?.resolved?.effectiveO ?: effectiveO.clone()

    val physicalNoiseRequestedSource: String = runtimeResolution?.requestedSource?.name
        ?: physicalNoiseRequestedSource
    val physicalNoiseEffectiveSource: String = runtimeResolution?.effectiveSource?.name
        ?: physicalNoiseEffectiveSource
    val physicalNoiseProvenance: String = runtimeResolution?.resolved?.provenance
        ?: physicalNoiseProvenance
    val physicalNoiseFallbackReason: String? = runtimeResolution?.fallbackReason
        ?: physicalNoiseFallbackReason
    val physicalNoiseEffectiveModelIso: Double? = runtimeResolution?.resolved?.effectiveNoiseModelIso
        ?: physicalNoiseEffectiveModelIso
    val physicalNoiseDynamicIsoEnabled: Boolean = runtimeResolution?.resolved?.dynamicIsoEnabled
        ?: physicalNoiseDynamicIsoEnabled
    val physicalNoiseDynamicIsoCoefficient: Double? = runtimeResolution?.resolved?.dynamicIsoCoefficient
        ?: physicalNoiseDynamicIsoCoefficient
    val physicalNoiseIsoStep: Double? = runtimeResolution?.resolved?.isoStep
        ?: physicalNoiseIsoStep
    val physicalNoisePresetName: String? = runtimeResolution?.resolvedPresetName
        ?: physicalNoisePresetName
    val physicalNoiseSettingsReady: Boolean = runtimeResolution?.settingsReady
        ?: physicalNoiseSettingsReady
    private val physicalNoiseSourceResolved: Boolean =
        physicalNoiseEffectiveSource.uppercase() in setOf("OEM", "SYSTEM", "MANUAL", "PRESET")

    val physicalNoiseModelAvailable: Boolean =
        physicalNoiseSourceResolved &&
            _effectiveS.size >= 4 && _effectiveO.size >= 4 &&
            _effectiveS.take(4).all { it.isFinite() && it >= 0.0 } &&
            _effectiveO.take(4).all { it.isFinite() && it >= 0.0 } &&
            (_effectiveS.take(4).any { it > 0.0 } || _effectiveO.take(4).any { it > 0.0 })

    /**
     * Confidence of the physical model itself. This deliberately does not depend on whether the
     * optional SPECTRA add-on is enabled or on the retired Off/Auto/Manual compatibility mode.
     * A resolved OEM/System/Manual/Preset source with finite non-zero S/O is deterministic authority
     * for the capture. Unresolved/legacy constructor arrays can never become physical authority just
     * because they contain non-zero values; SPECTRA Off cannot reduce a resolved model's confidence.
     */
    val physicalNoiseModelConfidence: Float = if (physicalNoiseModelAvailable) 1.0f else 0.0f

    val blackLevel: FloatArray get() = _blackLevel.clone()
    val cameraS: DoubleArray get() = _cameraS.clone()
    val cameraO: DoubleArray get() = _cameraO.clone()
    val effectiveS: DoubleArray get() = _effectiveS.clone()
    val effectiveO: DoubleArray get() = _effectiveO.clone()

    val stableLensKey: StableLensKey
        get() = StableLensKey.fromString(lensKey)

    init {
        require(lensKey.isNotBlank()) { "lensKey cannot be blank" }
        require(whiteLevel > 0) { "whiteLevel must be > 0" }
    }

    fun physicalNoiseState(): PhysicalNoiseState = PhysicalNoiseState.from(this)

    /** Optional add-on state only; never use this to decide physical-noise availability. */
    fun isSpectraActive(): Boolean =
        !spectraMode.equals("Legacy", ignoreCase = true) && !spectraMode.equals("Off", ignoreCase = true)

    fun copy(
        lensKey: String = this.lensKey,
        sourceFormat: String = this.sourceFormat,
        iso: Int = this.iso,
        exposureTimeNs: Long = this.exposureTimeNs,
        postRawSensitivityBoost: Int? = this.postRawSensitivityBoost,
        cfaPattern: Int = this.cfaPattern,
        cfaName: String = this.cfaName,
        whiteLevel: Int = this.whiteLevel,
        blackLevel: FloatArray = this.blackLevel,
        cameraS: DoubleArray = this.cameraS,
        cameraO: DoubleArray = this.cameraO,
        effectiveS: DoubleArray = this.effectiveS,
        effectiveO: DoubleArray = this.effectiveO,
        chromaUserScale: Float = this.chromaUserScale,
        lumaUserScale: Float = this.lumaUserScale,
        spectraMode: String = this.spectraMode,
        signalModelConfidence: Float = this.signalModelConfidence,
        sourceFlags: Long = this.sourceFlags,
        timestampNs: Long = this.timestampNs
    ): NoiseModelSnapshotV3 {
        val captureIdentityChanged =
            lensKey != this.lensKey || iso != this.iso || cfaPattern != this.cfaPattern || cfaName != this.cfaName
        return NoiseModelSnapshotV3(
        lensKey = lensKey,
        sourceFormat = sourceFormat,
        iso = iso,
        exposureTimeNs = exposureTimeNs,
        postRawSensitivityBoost = postRawSensitivityBoost,
        cfaPattern = cfaPattern,
        cfaName = cfaName,
        whiteLevel = whiteLevel,
        blackLevel = blackLevel,
        cameraS = cameraS,
        cameraO = cameraO,
        effectiveS = effectiveS,
        effectiveO = effectiveO,
        chromaUserScale = chromaUserScale,
        lumaUserScale = lumaUserScale,
        spectraMode = spectraMode,
        signalModelConfidence = signalModelConfidence,
        sourceFlags = sourceFlags,
        timestampNs = timestampNs,
        physicalAuthorityLocked = !captureIdentityChanged,
        physicalNoiseRequestedSource = physicalNoiseRequestedSource,
        physicalNoiseEffectiveSource = physicalNoiseEffectiveSource,
        physicalNoiseProvenance = physicalNoiseProvenance,
        physicalNoiseFallbackReason = physicalNoiseFallbackReason,
        physicalNoiseEffectiveModelIso = physicalNoiseEffectiveModelIso,
        physicalNoiseDynamicIsoEnabled = physicalNoiseDynamicIsoEnabled,
        physicalNoiseDynamicIsoCoefficient = physicalNoiseDynamicIsoCoefficient,
        physicalNoiseIsoStep = physicalNoiseIsoStep,
        physicalNoisePresetName = physicalNoisePresetName,
        physicalNoiseSettingsReady = physicalNoiseSettingsReady
        )
    }

    companion object {
        fun createNeutral(
            lensKey: String = "lens_v2_default",
            sourceFormat: String = "RAW10",
            iso: Int = 100,
            exposureTimeNs: Long = 10_000_000L,
            cfaPattern: Int = 0,
            cfaName: String = "RGGB",
            whiteLevel: Int = 1023,
            blackLevel: FloatArray = floatArrayOf(64f, 64f, 64f, 64f)
        ): NoiseModelSnapshotV3 = NoiseModelSnapshotV3(
            lensKey = StableLensKey.fromString(lensKey).value,
            sourceFormat = sourceFormat,
            iso = iso,
            exposureTimeNs = exposureTimeNs,
            postRawSensitivityBoost = null,
            cfaPattern = cfaPattern,
            cfaName = cfaName,
            whiteLevel = whiteLevel,
            blackLevel = blackLevel.clone(),
            cameraS = DoubleArray(4) { 0.0 },
            cameraO = DoubleArray(4) { 0.0 },
            effectiveS = DoubleArray(4) { 0.0 },
            effectiveO = DoubleArray(4) { 0.0 },
            chromaUserScale = 1.0f,
            lumaUserScale = 1.0f,
            spectraMode = "Legacy",
            signalModelConfidence = 1.0f,
            sourceFlags = 0L,
            timestampNs = System.nanoTime(),
            physicalAuthorityLocked = true,
            physicalNoiseRequestedSource = "NEUTRAL",
            physicalNoiseEffectiveSource = "NEUTRAL",
            physicalNoiseProvenance = "Neutral test/default snapshot",
            physicalNoiseSettingsReady = true
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NoiseModelSnapshotV3

        if (lensKey != other.lensKey) return false
        if (sourceFormat != other.sourceFormat) return false
        if (iso != other.iso) return false
        if (exposureTimeNs != other.exposureTimeNs) return false
        if (postRawSensitivityBoost != other.postRawSensitivityBoost) return false
        if (cfaPattern != other.cfaPattern) return false
        if (cfaName != other.cfaName) return false
        if (whiteLevel != other.whiteLevel) return false
        if (!_blackLevel.contentEquals(other._blackLevel)) return false
        if (!_cameraS.contentEquals(other._cameraS)) return false
        if (!_cameraO.contentEquals(other._cameraO)) return false
        if (!_effectiveS.contentEquals(other._effectiveS)) return false
        if (!_effectiveO.contentEquals(other._effectiveO)) return false
        if (chromaUserScale != other.chromaUserScale) return false
        if (lumaUserScale != other.lumaUserScale) return false
        if (spectraMode != other.spectraMode) return false
        if (signalModelConfidence != other.signalModelConfidence) return false
        if (sourceFlags != other.sourceFlags) return false
        if (timestampNs != other.timestampNs) return false
        if (physicalNoiseRequestedSource != other.physicalNoiseRequestedSource) return false
        if (physicalNoiseEffectiveSource != other.physicalNoiseEffectiveSource) return false
        if (physicalNoiseProvenance != other.physicalNoiseProvenance) return false
        if (physicalNoiseFallbackReason != other.physicalNoiseFallbackReason) return false
        if (physicalNoiseEffectiveModelIso != other.physicalNoiseEffectiveModelIso) return false
        if (physicalNoiseDynamicIsoEnabled != other.physicalNoiseDynamicIsoEnabled) return false
        if (physicalNoiseDynamicIsoCoefficient != other.physicalNoiseDynamicIsoCoefficient) return false
        if (physicalNoiseIsoStep != other.physicalNoiseIsoStep) return false
        if (physicalNoisePresetName != other.physicalNoisePresetName) return false
        if (physicalNoiseSettingsReady != other.physicalNoiseSettingsReady) return false

        return true
    }

    override fun hashCode(): Int {
        var result = lensKey.hashCode()
        result = 31 * result + sourceFormat.hashCode()
        result = 31 * result + iso
        result = 31 * result + exposureTimeNs.hashCode()
        result = 31 * result + (postRawSensitivityBoost ?: 0)
        result = 31 * result + cfaPattern
        result = 31 * result + cfaName.hashCode()
        result = 31 * result + whiteLevel
        result = 31 * result + _blackLevel.contentHashCode()
        result = 31 * result + _cameraS.contentHashCode()
        result = 31 * result + _cameraO.contentHashCode()
        result = 31 * result + _effectiveS.contentHashCode()
        result = 31 * result + _effectiveO.contentHashCode()
        result = 31 * result + chromaUserScale.hashCode()
        result = 31 * result + lumaUserScale.hashCode()
        result = 31 * result + spectraMode.hashCode()
        result = 31 * result + signalModelConfidence.hashCode()
        result = 31 * result + sourceFlags.hashCode()
        result = 31 * result + timestampNs.hashCode()
        result = 31 * result + physicalNoiseRequestedSource.hashCode()
        result = 31 * result + physicalNoiseEffectiveSource.hashCode()
        result = 31 * result + physicalNoiseProvenance.hashCode()
        result = 31 * result + (physicalNoiseFallbackReason?.hashCode() ?: 0)
        result = 31 * result + (physicalNoiseEffectiveModelIso?.hashCode() ?: 0)
        result = 31 * result + physicalNoiseDynamicIsoEnabled.hashCode()
        result = 31 * result + (physicalNoiseDynamicIsoCoefficient?.hashCode() ?: 0)
        result = 31 * result + (physicalNoiseIsoStep?.hashCode() ?: 0)
        result = 31 * result + (physicalNoisePresetName?.hashCode() ?: 0)
        result = 31 * result + physicalNoiseSettingsReady.hashCode()
        return result
    }
}
