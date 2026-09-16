package com.bncam.core.quality

import android.graphics.ImageFormat
import android.util.Log
import android.hardware.camera2.CameraCharacteristics
import com.bncam.data.settings.ProfileAwbModes
import com.bncam.data.settings.ProfileAwbSettings
import com.bncam.data.settings.ResolvedLensHardwareSettings
import java.util.Locale
import kotlin.math.abs

private fun Float.finiteOrNull(): Float? = takeIf { it.isFinite() }
private fun Float.format5(): String = String.format(Locale.US, "%.5f", this)
private fun Double.format5(): String = String.format(Locale.US, "%.5f", this)
private fun Float.format6(): String = String.format(Locale.US, "%.6f", this)
private fun FloatArray.formatArray5(): String = joinToString(prefix = "[", postfix = "]") { it.format5() }
private fun FloatArray.formatFloatArray(): String = joinToString(prefix = "[", postfix = "]") { it.format5() }
private fun DoubleArray.formatDoubleArray(): String = joinToString(prefix = "[", postfix = "]") { it.toString() }
private fun FloatArray.matrixRowSums(): FloatArray? = if (size == 9) {
    FloatArray(3) { row -> this[row * 3] + this[row * 3 + 1] + this[row * 3 + 2] }
} else {
    null
}

private fun matrixMaxAbsDelta(a: FloatArray?, b: FloatArray?): Float {
    if (a == null || b == null || a.size != 9 || b.size != 9) return 0.0f
    return a.indices.maxOf { index -> abs(a[index] - b[index]) }
}

private val IDENTITY_3X3 = floatArrayOf(
    1f, 0f, 0f,
    0f, 1f, 0f,
    0f, 0f, 1f
)

enum class RawDomain {
    RAW10_PACKED_10BIT,
    RAW_SENSOR_16BIT,
    MASTER_RAW16_NORMALIZED,
    UNKNOWN
}

data class BaseSensorCalibration(
    val lensId: String,
    val physicalCameraId: String?,
    val calibrationProfileId: String,
    val calibrationProfileBinding: CalibrationProfileBinding? = null,
    val frameSource: String,
    val cfaPattern: Int,
    val cfaName: String,
    val sensorOrientation: Int,
    val sensorTimestampNs: Long?,
    val sensorSensitivityIso: Int = 0,
    val sensorExposureTimeNs: Long = 0L,
    val postRawSensitivityBoost: Int = 100,
    val inputRawFormat: Int,
    val inputBitDepth: Int,
    val inputDomain: RawDomain,

    val baseWhiteLevel: Int,
    val baseWhiteLevelSource: String,
    val baseWhiteLevelRawMetadata: Int?,
    val baseWhiteLevelAppliedDomain: String,
    val baseWhiteLevelScaleFactor: Float,
    val dynamicWhiteLevelAvailable: Boolean,

    val baseBlackLevels: FloatArray,
    val baseBlackLevelSource: String,
    val baseBlackLevelRawMetadataValues: FloatArray,
    val baseBlackLevelAppliedDomain: String,
    val baseBlackLevelScaleFactor: Float,
    val dynamicBlackLevelAvailable: Boolean,
    val blackSubtractionApplied: Boolean,

    /** Android SENSOR_NOISE_PROFILE stored as [S0,O0,S1,O1,...]. */
    val baseNoiseProfile: DoubleArray?,
    val baseNoiseProfileSource: String,
    val baseNoiseProfileFallbackReason: String,
    val baseNoiseProfilePresent: Boolean,
    val baseNoiseProfileAppliedByDefault: Boolean,
    val baseNoiseProfileFormula: String,
    val baseNoiseProfilePairCount: Int,
    val baseNoiseProfileChannelCount: Int,
    val baseNoiseProfileChannelMap: String,
    val hasNoiseProfile: Boolean = false,
    val noiseProfileValid: Boolean = false,
    val normalizationCalibrationValid: Boolean = false,
    val cfaSupportedForBayerNoiseModel: Boolean = false,

    val baseWbGains: FloatArray,
    val baseWbSource: String,
    val baseWbAppliedByDefault: Boolean,

    val baseColorMatrix: FloatArray?,
    val baseColorMatrixSource: String,
    val baseColorMatrixApplied: Boolean,
    val baseColorMatrixIdentityFallbackUsed: Boolean,
    val baseColorMatrixRejectReason: String,
    val baseColorMatrixNote: String,

    val warnings: List<String>,

    // Color-truth provenance. Phase 8 keeps the camera-provided matrix unmodified for
    // production and retains the retired row-normalized form only as a diagnostic counterfactual.
    val baseColorMatrixPreNormalization: FloatArray? = null,
    val baseColorMatrixNeutralNormalizationApplied: Boolean = false,
    // Phase 8 diagnostic only: reproduces the retired row-normalized matrix for objective
    // A/B telemetry. It is never selected for production rendering.
    val baseColorMatrixLegacyNeutralNormalized: FloatArray? = null
)

data class LensOverrideLayer(
    val blackLevelMode: String,
    val dynamicBlackLevelPercent: Float,
    val manualBlackLevels: FloatArray?,

    val noiseMode: String,
    val noisePresetName: String,
    val manualNoiseValues: DoubleArray?,

    val colorMode: String,
    val colorPresetName: String,
    val manualColorMatrix: FloatArray?,

    val awbMode: String,
    val awbProfile: String,
    val awbRatio: Float,
    val awbTemp: Float,
    val awbIntensity: Float,
    val warnings: List<String>
)

data class FinalSensorCalibration(
    val base: BaseSensorCalibration,
    val override: LensOverrideLayer,

    val effectiveWhiteLevel: Int,
    val effectiveWhiteLevelSource: String,
    val effectiveWhiteLevelAppliedDomain: String,
    val effectiveWhiteLevelScaleFactor: Float,

    val effectiveBlackLevels: FloatArray,
    val effectiveBlackLevelSource: String,
    val blackLevelScaleFactor: Float,
    val effectiveBlackLevelAppliedDomain: String,
    val blackSubtractionApplied: Boolean,

    val noiseModelMode: String,
    val spectraProcessingEnabled: Boolean = false,
    val effectiveNoiseProfile: DoubleArray?,
    val effectiveNoiseProfileSource: String,
    val effectiveNoiseProfileFallbackReason: String,
    val hasNoiseProfile: Boolean = false,
    val noiseProfileValid: Boolean = false,
    val normalizationCalibrationValid: Boolean = false,
    val cfaSupportedForBayerNoiseModel: Boolean = false,
    val effectiveNoiseProfileApplied: Boolean = false,
    val noiseProfileNotAppliedReason: String = "none",
    val manualNoiseAnchorIso: Double = 100.0,
    val manualNoiseGainRatio: Double = 1.0,
    val manualNoiseSingleAnchorScaled: Boolean = false,
    val effectiveNoiseProfilePairCount: Int,
    val effectiveNoiseProfileChannelCount: Int,
    val effectiveNoiseProfileFormula: String,
    val effectiveNoiseProfileChannelMap: String,

    val effectiveWbGains: FloatArray,
    val effectiveWbSource: String,
    val effectiveWbApplied: Boolean,

    val effectiveColorMatrix: FloatArray?,
    val effectiveColorMatrixSource: String,
    val effectiveColorMatrixApplied: Boolean,
    val effectiveColorMatrixIdentityFallbackUsed: Boolean,
    val effectiveColorMatrixRejectReason: String,
    val effectiveColorMatrixNote: String,

    val rawInputDomain: RawDomain,
    val ispWorkingDomain: RawDomain = RawDomain.MASTER_RAW16_NORMALIZED,

    val applicability: List<Pair<String, String>>,
    val calibrationApplied: Boolean,
    val pipelineWarnings: List<String>,
    val noiseSnapshot: NoiseModelSnapshotV3? = null,

    // Residual-noise propagation after temporal fusion. These values describe the fused RAW
    // product only; they never rewrite the immutable shutter-time PhysicalNoiseState S/O.
    val physicalFusionVarianceScale: Double = 1.0,
    val physicalEffectiveFrameCount: Double = 1.0
) {
    fun debugPairs(): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        pairs.add("Sensor Calibration Summary" to "V2 central metadata baseline")
        pairs.add("Base Layer" to "Uniform SensorMetadata + exact authority CameraCharacteristics")
        pairs.add("Adjustment Layer" to adjustmentLayerSummary())
        pairs.add("Final Layer" to "Base merged with validated per-lens adjustment values")
        pairs.add("Capture Mode / Frame Source" to base.frameSource)
        pairs.add("Input Raw Domain" to rawInputDomain.name)
        pairs.add("ISP Working Domain" to ispWorkingDomain.name)
        pairs.add("Lens ID" to base.lensId)
        pairs.add("Physical Camera ID" to (base.physicalCameraId ?: "not reported"))
        val calibrationBinding = base.calibrationProfileBinding
        pairs.add("Calibration Authority ID" to (calibrationBinding?.provenance?.sensorAuthorityId ?: "UNAVAILABLE"))
        pairs.add("Calibration Profile ID" to base.calibrationProfileId)
        pairs.add("Calibration Profile Authority Match" to (calibrationBinding?.provenance?.authorityMatches?.toString() ?: "false"))
        pairs.add("Calibration Static Fingerprint" to (calibrationBinding?.staticCalibrationFingerprint ?: "UNAVAILABLE"))
        pairs.add("Calibration Characteristics Source ID" to (calibrationBinding?.provenance?.characteristicsSourceId ?: "UNAVAILABLE"))
        pairs.add("Calibration CaptureResult Source ID" to (calibrationBinding?.provenance?.captureResultSourceId ?: "UNAVAILABLE"))
        pairs.add("Calibration Binding Safe" to (calibrationBinding?.safeForProfileBinding?.toString() ?: "false"))
        pairs.add("Calibration Binding Reason" to (calibrationBinding?.rejectionReason ?: "CALIBRATION_BINDING_UNAVAILABLE"))
        pairs.add("Reference Illuminant 1" to (calibrationBinding?.referenceIlluminant1?.toString() ?: "UNAVAILABLE"))
        pairs.add("Reference Illuminant 2" to (calibrationBinding?.referenceIlluminant2?.toString() ?: "UNAVAILABLE"))
        pairs.add("Generic Calibration Fallback Used" to "false")
        pairs.add("Sensor Timestamp Ns" to (base.sensorTimestampNs?.toString() ?: "missing"))
        pairs.add("CFA Pattern" to "${base.cfaPattern} / ${base.cfaName}")
        pairs.add("Sensor Orientation" to base.sensorOrientation.toString())

        pairs.add("Black Level Source" to effectiveBlackLevelSource)
        pairs.add("Black Level Raw Metadata Values" to base.baseBlackLevelRawMetadataValues.formatArray5())
        pairs.add("Applied Black Levels" to effectiveBlackLevels.formatFloatArray())
        pairs.add("Applied Black Level Domain" to effectiveBlackLevelAppliedDomain)
        pairs.add("Black Level Scale Factor" to blackLevelScaleFactor.format6())
        pairs.add("Black Subtraction Applied" to blackSubtractionApplied.toString())

        pairs.add("White Level Source" to effectiveWhiteLevelSource)
        pairs.add("Metadata White Level" to (base.baseWhiteLevelRawMetadata?.toString() ?: "missing"))
        pairs.add("Applied White Level" to effectiveWhiteLevel.toString())
        pairs.add("White Level Applied" to (rawInputDomain == RawDomain.RAW10_PACKED_10BIT || rawInputDomain == RawDomain.RAW_SENSOR_16BIT).toString())
        pairs.add("Applied White Level Domain" to effectiveWhiteLevelAppliedDomain)
        pairs.add("White Level Scale Factor" to effectiveWhiteLevelScaleFactor.format6())
        pairs.add("Normalization Range" to "white - per-channel black")

        pairs.add("Color Matrix Source" to effectiveColorMatrixSource)
        pairs.add("Color Matrix Applied" to effectiveColorMatrixApplied.toString())
        pairs.add("Identity Fallback Used" to effectiveColorMatrixIdentityFallbackUsed.toString())
        pairs.add("Color Matrix Reject Reason" to effectiveColorMatrixRejectReason)
        pairs.add("Color Matrix Values" to (effectiveColorMatrix?.formatArray5() ?: "missing"))
        pairs.add("Color Matrix Original Metadata Values" to
                (base.baseColorMatrixPreNormalization?.formatArray5() ?: "missing"))
        pairs.add("Color Matrix Neutral Row Normalization Applied" to
                base.baseColorMatrixNeutralNormalizationApplied.toString())
        pairs.add("Color Matrix Original Row Sums" to
                (base.baseColorMatrixPreNormalization?.matrixRowSums()?.formatArray5() ?: "missing"))
        pairs.add("Color Matrix Effective Row Sums" to
                (effectiveColorMatrix?.matrixRowSums()?.formatArray5() ?: "missing"))
        pairs.add("Color Matrix Legacy Row-Normalized Counterfactual" to
                (base.baseColorMatrixLegacyNeutralNormalized?.formatArray5() ?: "missing"))
        pairs.add("Color Matrix Legacy Counterfactual Row Sums" to
                (base.baseColorMatrixLegacyNeutralNormalized?.matrixRowSums()?.formatArray5() ?: "missing"))
        pairs.add("Color Matrix Legacy Counterfactual Max Abs Delta" to
                matrixMaxAbsDelta(
                    base.baseColorMatrixPreNormalization,
                    base.baseColorMatrixLegacyNeutralNormalized
                ).format6())
        pairs.add("Color Matrix Production Contract" to
                "Camera2 direct transform preserved; no BnCam row normalization")
        pairs.add("Color Matrix Applied To" to colorMatrixAppliedTo())

        pairs.add("WB Source" to effectiveWbSource)
        pairs.add("WB Gains" to effectiveWbGains.formatArray5())
        pairs.add("WB Applied" to effectiveWbApplied.toString())
        pairs.add("WB Applied To" to wbAppliedTo())

        pairs.add("Sensor Noise Profile Present" to hasNoiseProfile.toString())
        pairs.add("Noise Profile Valid" to noiseProfileValid.toString())
        pairs.add("Normalization Calibration Valid" to normalizationCalibrationValid.toString())
        pairs.add("CFA Bayer Noise Model Supported" to cfaSupportedForBayerNoiseModel.toString())
        pairs.add("Noise Model Mode" to noiseModelMode)
        pairs.add("SPECTRA Processing Enabled" to spectraProcessingEnabled.toString())
        pairs.add("Sensor Noise Profile Source" to effectiveNoiseProfileSource)
        pairs.add("Sensor Noise Profile Fallback Reason" to effectiveNoiseProfileFallbackReason)
        pairs.add("Sensor Noise Profile Applied" to effectiveNoiseProfileApplied.toString())
        pairs.add("Sensor Noise Profile Not Applied Reason" to noiseProfileNotAppliedReason)
        pairs.add("Manual Noise Anchor ISO" to manualNoiseAnchorIso.format5())
        pairs.add("Manual Noise Gain Ratio" to manualNoiseGainRatio.format5())
        pairs.add("Manual Noise Single Anchor Scaled" to manualNoiseSingleAnchorScaled.toString())
        pairs.add("Sensor Noise Profile Formula" to effectiveNoiseProfileFormula)
        pairs.add("Sensor Noise Profile Pair Count" to effectiveNoiseProfilePairCount.toString())
        pairs.add("Sensor Noise Profile Channel Count" to effectiveNoiseProfileChannelCount.toString())
        pairs.add("Sensor Noise Profile CFA/Channel Mapping" to effectiveNoiseProfileChannelMap)
        pairs.add("Sensor Noise Profile Values S/O" to (effectiveNoiseProfile?.formatDoubleArray() ?: "missing"))
        pairs.add("Sensor Noise Profile Applied To" to if (effectiveNoiseProfileApplied) "RAW_DOMAIN_NATIVE_ISP_DENOISE_SCALING" else "NOT_APPLIED")
        pairs.add("Physical Fusion Variance Scale" to physicalFusionVarianceScale.toString())
        pairs.add("Physical Effective Frame Count" to physicalEffectiveFrameCount.toString())

        pairs.add("Native Calibration Applied" to calibrationApplied.toString())
        applicability.forEach { (key, value) -> pairs.add(key to value) }
        pipelineWarnings.forEachIndexed { i, warning ->
            pairs.add("Calibration Warning ${i + 1}" to warning)
        }
        return pairs
    }

    private fun colorMatrixAppliedTo(): String = when (rawInputDomain) {
        RawDomain.RAW10_PACKED_10BIT -> "RAW10_NATIVE_ISP"
        RawDomain.RAW_SENSOR_16BIT -> "RAW_SENSOR_NATIVE_ISP"
        RawDomain.MASTER_RAW16_NORMALIZED -> "MASTER_RAW16_NATIVE_ISP"
        RawDomain.UNKNOWN -> "UNKNOWN_OR_YUV"
    }

    private fun wbAppliedTo(): String = when (rawInputDomain) {
        RawDomain.RAW10_PACKED_10BIT -> "RAW10_RAW_PREPROCESS"
        RawDomain.RAW_SENSOR_16BIT -> "RAW_SENSOR_RAW_PREPROCESS"
        RawDomain.MASTER_RAW16_NORMALIZED -> "MASTER_RAW16_RAW_PREPROCESS"
        RawDomain.UNKNOWN -> "UNKNOWN_OR_YUV"
    }

    private fun adjustmentLayerSummary(): String = listOf(
        "black=${override.blackLevelMode}",
        "noise=${override.noiseMode}",
        "color=${override.colorMode}",
        "awb=${override.awbMode}"
    ).joinToString(";")
}

fun ResolvedLensHardwareSettings.toOverrideLayer(): LensOverrideLayer {
    val manualNoise = manualNoiseProfile
        .takeIf { noiseModelNativeMode == 2 && (it.size == 2 || it.size == 8) }
        ?.toDoubleArray()

    return LensOverrideLayer(
        blackLevelMode = blackLevelMode,
        dynamicBlackLevelPercent = dynamicBlackLevelPercent,
        manualBlackLevels = manualBlackLevels.takeIf { blackLevelNativeMode == 2 }?.toFloatArray(),
        noiseMode = when (noiseModelNativeMode) {
            1 -> "Auto"
            2 -> "Manual"
            else -> "Off"
        },
        noisePresetName = noiseModelType,
        manualNoiseValues = manualNoise,
        colorMode = colorMatrixMode,
        colorPresetName = colorMatrixMode,
        manualColorMatrix = manualColorMatrix.takeIf { colorMatrixNativeMode == 1 && colorMatrixValidationPassed && it.size == 9 }?.toFloatArray(),
        awbMode = if (awbNativeMode == 1) "Manual_Override" else "System",
        awbProfile = awbProfile,
        awbRatio = awbRatio,
        awbTemp = awbTemp,
        awbIntensity = awbIntensity,
        warnings = warnings
    )
}

private const val SPECTRA_FUSION_SOURCE_FLAG: Long = 1L shl 8
private const val PHYSICAL_TEMPORAL_FUSION_SOURCE_FLAG: Long = 1L shl 9

/**
 * Applies capture-integrated SPECTRA temporal observation and fusion variance to the
 * immutable shutter-time calibration used by the JPEG ISP. The RAW16/DNG payload is
 * never changed here; only the post-fusion noise expectation is updated.
 */
fun FinalSensorCalibration.withSpectraMergeStats(stats: String): FinalSensorCalibration {
    val snapshot = noiseSnapshot ?: return this
    if (stats.isBlank()) return this

    val values = stats.split(';')
        .mapNotNull { entry ->
            val split = entry.indexOf('=')
            if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
        }
        .toMap()
    val temporalModelEnabled =
        values["temporalNoiseModelEnabled"].equals("true", ignoreCase = true) ||
        values["spectraEnabled"].equals("true", ignoreCase = true)
    if (!temporalModelEnabled) return this
    val adaptiveSpectraCalibration =
        snapshot.isSpectraActive() &&
        values["spectraAdaptiveCalibrationEnabled"].equals("true", ignoreCase = true)

    fun value(key: String, fallback: Double): Double =
        values[key]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fallback

    val observerSamples = values["spectraObserverSamples"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val observerConfidence = value("spectraObserverConfidence", 0.0).coerceIn(0.0, 1.0)
    // A native observation contains one sample per accepted CFA location. Require a
    // meaningful population before granting full authority; one accepted pair must not
    // instantly drive the shutter-time calibration to a safety bound.
    val sampleWeight = (observerSamples.toDouble() / 4096.0).coerceIn(0.0, 1.0)
    val observerWeight = observerConfidence * sampleWeight
    val fusionVarianceScale = value("spectraFusionVarianceScale", 1.0).coerceIn(0.04, 1.0)
    if (observerSamples == 0 && kotlin.math.abs(fusionVarianceScale - 1.0) < 1.0e-6) return this

    val oldS = snapshot.effectiveS
    val oldO = snapshot.effectiveO
    if (oldS.size < 4 || oldO.size < 4) return this

    fun channelScale(channel: Int, slope: Boolean): Double {
        // Physical S/O remains fixed in shape. Multi-frame fusion lowers the expected
        // variance by the measured fusion factor, so both S and O scale equally.
        if (!adaptiveSpectraCalibration) {
            return fusionVarianceScale
        }

        val compatibilityKey = "spectraScale$channel"
        val specificKey = if (slope) "spectraSScale$channel" else "spectraOScale$channel"
        val compatibilityScale = value(compatibilityKey, 1.0).coerceIn(0.75, 1.25)
        val hasIndependentFit = values.containsKey(specificKey)
        val rawScale = value(specificKey, compatibilityScale).coerceIn(0.75, 1.25)
        val fitConfidence = value("spectraFitConfidence$channel", 0.0).coerceIn(0.0, 1.0)
        val fitPhysicalScore = value("spectraFitPhysicalScore$channel", fitConfidence).coerceIn(0.0, 1.0)
        val fitAuthority = if (hasIndependentFit) {
            (0.20 + 0.55 * fitConfidence + 0.25 * fitPhysicalScore).coerceIn(0.0, 1.0)
        } else {
            1.0
        }
        val channelAuthority = observerWeight * fitAuthority
        val smoothedObserverScale = 1.0 + (rawScale - 1.0) * channelAuthority
        return (smoothedObserverScale * fusionVarianceScale).coerceIn(0.04, 1.25)
    }

    val totalSScale = DoubleArray(4) { channel -> channelScale(channel, slope = true) }
    val totalOScale = DoubleArray(4) { channel -> channelScale(channel, slope = false) }
    val adaptedS = DoubleArray(4) { channel -> (oldS[channel] * totalSScale[channel]).coerceAtLeast(0.0) }
    val adaptedO = DoubleArray(4) { channel -> (oldO[channel] * totalOScale[channel]).coerceAtLeast(0.0) }
    if (adaptedS.any { !it.isFinite() } || adaptedO.any { !it.isFinite() }) return this

    val adaptedProfile = DoubleArray(8) { index ->
        val channel = index / 2
        if (index % 2 == 0) adaptedS[channel] else adaptedO[channel]
    }
    val mergedConfidence = maxOf(
        snapshot.signalModelConfidence,
        (observerConfidence * sampleWeight).toFloat()
    ).coerceIn(0.0f, 1.0f)
    val adaptedSnapshot = snapshot.copy(
        effectiveS = adaptedS,
        effectiveO = adaptedO,
        signalModelConfidence = mergedConfidence,
        sourceFlags = snapshot.sourceFlags or if (adaptiveSpectraCalibration) {
            SPECTRA_FUSION_SOURCE_FLAG
        } else {
            PHYSICAL_TEMPORAL_FUSION_SOURCE_FLAG
        }
    )
    val effectiveFrames = value("spectraEffectiveFrameCount", 1.0).coerceAtLeast(1.0)
    val meanFitConfidence = (0 until 4)
        .map { channel -> value("spectraFitConfidence$channel", 0.0).coerceIn(0.0, 1.0) }
        .average()
    val staticP50 = value("spectraStaticProbabilityP50", 0.0).coerceIn(0.0, 1.0)
    val forwardBackward = value("spectraForwardBackwardConsistency", 0.0).coerceIn(0.0, 1.0)
    val persistentPattern = value("spectraPersistentPatternFraction", 0.0).coerceIn(0.0, 1.0)
    val fitStability = value("spectraFitStabilityConfidence", 0.0).coerceIn(0.0, 1.0)
    val temporalAuthorityLabel = if (adaptiveSpectraCalibration) {
        "SPECTRA capture integration"
    } else {
        "Physical Camera2 temporal fusion"
    }
    val warning = "$temporalAuthorityLabel: observerSamples=$observerSamples, " +
        "observerConfidence=${String.format(Locale.US, "%.3f", observerConfidence)}, " +
        "fitConfidence=${String.format(Locale.US, "%.3f", meanFitConfidence)}, " +
        "staticP50=${String.format(Locale.US, "%.3f", staticP50)}, " +
        "forwardBackward=${String.format(Locale.US, "%.3f", forwardBackward)}, " +
        "fitStability=${String.format(Locale.US, "%.3f", fitStability)}, " +
        "persistentPattern=${String.format(Locale.US, "%.3f", persistentPattern)}, " +
        "effectiveFrames=${String.format(Locale.US, "%.2f", effectiveFrames)}, " +
        "fusionVarianceScale=${String.format(Locale.US, "%.4f", fusionVarianceScale)}"

    return copy(
        effectiveNoiseProfile = adaptedProfile,
        effectiveNoiseProfileSource = if (adaptiveSpectraCalibration) {
            "$effectiveNoiseProfileSource + SPECTRA_CAPTURE_FUSION"
        } else {
            "$effectiveNoiseProfileSource + PHYSICAL_TEMPORAL_FUSION"
        },
        noiseSnapshot = adaptedSnapshot,
        pipelineWarnings = pipelineWarnings + warning
    )
}

object SensorCalibrationResolver {

    fun resolve(
        lensId: String,
        physicalCameraId: String?,
        frameSourceFormat: Int,
        characteristics: CameraCharacteristics,
        sensorMetadata: SensorMetadata,
        lensSettings: ResolvedLensHardwareSettings?,
        profileAwbSettings: ProfileAwbSettings? = null,
        profileNoiseTuning: ProfileNoiseTuning? = null,
        stableAutoWhiteBalance: StableWhiteBalanceSnapshot? = null
    ): FinalSensorCalibration {
        val resolvedPhysicalCameraId = physicalCameraId
        val base = buildBaseCalibration(
            lensId = lensId,
            physicalCameraId = resolvedPhysicalCameraId,
            frameSourceFormat = frameSourceFormat,
            sensorMetadata = sensorMetadata
        )
        val override = lensSettings?.toOverrideLayer() ?: defaultOverrideLayer()
        return buildFinalCalibration(
            base,
            override,
            profileAwbSettings,
            profileNoiseTuning,
            stableAutoWhiteBalance,
            characteristics
        )
            .also(LensCalibrationTelemetry::record)
    }

    /**
     * Produces a compatibility residual-noise estimate for an averaged/fused master from each
     * selected frame's exact calibration. Independent-frame mean variance is sum(variance_i) / n².
     *
     * Crucially, this estimate is NOT physical sensor calibration: the capture-local
     * NoiseModelSnapshotV3/PhysicalNoiseState remains the immutable shutter-time authority.
     */
    fun combineNoiseForFusion(
        anchor: FinalSensorCalibration,
        selectedFrames: List<FinalSensorCalibration>,
        weights: List<Double>? = null
    ): FinalSensorCalibration {
        if (selectedFrames.size <= 1) return anchor
        val coefficientCount = selectedFrames.mapNotNull { it.effectiveNoiseProfile?.size }
            .minOrNull() ?: return anchor.copy(
                pipelineWarnings = anchor.pipelineWarnings +
                    "Fused noise model unavailable: every selected frame used the explicit no-profile fallback"
            )
        if (coefficientCount < 2) return anchor
        val frameCount = selectedFrames.size
        val fallbackCount = selectedFrames.count { it.effectiveNoiseProfile == null }
        
        // Normalize fusion weights w_i sum to 1.0 (default equal arithmetic average w_i = 1/n)
        val rawWeights = weights?.takeIf { it.size == frameCount } ?: List(frameCount) { 1.0 / frameCount }
        val sumWeights = rawWeights.sum().coerceAtLeast(1.0e-6)
        val w = rawWeights.map { it / sumWeights }

        val combined = DoubleArray(coefficientCount)
        for (channel in 0 until coefficientCount / 2) {
            val anchorBlack = anchor.effectiveBlackLevels.getOrElse(channel) { 0f }.toDouble()
            val anchorRange = (anchor.effectiveWhiteLevel.toDouble() - anchorBlack).coerceAtLeast(1.0)
            var weightedSlopeSum = 0.0
            var weightedOffsetSum = 0.0
            selectedFrames.forEachIndexed { i, frame ->
                val profile = frame.effectiveNoiseProfile ?: return@forEachIndexed
                val wi = w[i]
                val wiSq = wi * wi
                val slope = profile.getOrElse(channel * 2) { 0.0 }
                val offset = profile.getOrElse(channel * 2 + 1) { 0.0 }
                val frameBlack = frame.effectiveBlackLevels.getOrElse(channel) { 0f }.toDouble()
                val frameRange = (frame.effectiveWhiteLevel.toDouble() - frameBlack).coerceAtLeast(1.0)
                
                // Radiometric transform to anchor domain: x_frame = alpha * x_anchor + beta
                val alpha = anchorRange / frameRange
                val beta = (anchorBlack - frameBlack) / frameRange
                val sCommon = slope * alpha
                val oCommon = slope * beta + offset
                val sContrib = wiSq * sCommon
                val oContrib = wiSq * oCommon

                weightedSlopeSum += sContrib
                weightedOffsetSum += oContrib

                try {
                    Log.d("SensorCalibration", "Fusion noise frame[$i] timestamp=${frame.base.sensorTimestampNs}: " +
                            "weight=$wi, wiSq=$wiSq, alpha=$alpha, beta=$beta, sourceS=$slope, sourceO=$offset, " +
                            "commonS=$sCommon, commonO=$oCommon, weightedSContrib=$sContrib, weightedOContrib=$oContrib")
                } catch (_: Throwable) {
                    // Suppress unmocked Android Log calls in JVM test runners
                }
            }
            combined[channel * 2] = weightedSlopeSum.coerceAtLeast(0.0)
            combined[channel * 2 + 1] = weightedOffsetSum.coerceAtLeast(0.0)
        }
        return anchor.copy(
            effectiveNoiseProfile = combined,
            effectiveNoiseProfileSource = "Fused per-frame exact Camera2/manual S/O ($frameCount frames, $fallbackCount explicit zero fallbacks)",
            effectiveNoiseProfileFallbackReason = if (fallbackCount == 0) "None" else "$fallbackCount selected frame(s) had unavailable or invalid S/O",
            effectiveNoiseProfileApplied = true,
            effectiveNoiseProfilePairCount = coefficientCount / 2,
            effectiveNoiseProfileChannelCount = coefficientCount / 2,
            pipelineWarnings = anchor.pipelineWarnings + (
                "Fused residual-noise estimate derived from $frameCount selected frames using per-frame radiometric weights w_i and exact black/white normalization; frozen PhysicalNoiseState remains unchanged; anchor metadata was not reused for support frames" +
                    if (fallbackCount > 0) "; $fallbackCount frame(s) had no S/O and used explicit zero fallback" else ""
                )
        ).also(LensCalibrationTelemetry::record)
    }

    private fun buildBaseCalibration(
        lensId: String,
        physicalCameraId: String?,
        frameSourceFormat: Int,
        sensorMetadata: SensorMetadata
    ): BaseSensorCalibration {
        val warnings = mutableListOf<String>()
        val (domain, label, bitDepth) = when (frameSourceFormat) {
            ImageFormat.RAW10 -> Triple(RawDomain.RAW10_PACKED_10BIT, "RAW10", 10)
            ImageFormat.RAW_SENSOR -> Triple(RawDomain.RAW_SENSOR_16BIT, "RAW_SENSOR", 16)
            ImageFormat.YUV_420_888 -> Triple(RawDomain.UNKNOWN, "YUV", 8)
            else -> Triple(RawDomain.UNKNOWN, "UNKNOWN($frameSourceFormat)", 8)
        }
        val isRaw = domain == RawDomain.RAW10_PACKED_10BIT || domain == RawDomain.RAW_SENSOR_16BIT
        val appliedDomain = when (domain) {
            RawDomain.RAW10_PACKED_10BIT -> "RAW10"
            RawDomain.RAW_SENSOR_16BIT -> "RAW16_OR_RAW_SENSOR"
            RawDomain.MASTER_RAW16_NORMALIZED -> "MASTER_RAW16_NORMALIZED"
            RawDomain.UNKNOWN -> "UNKNOWN_OR_YUV"
        }

        if (isRaw && !sensorMetadata.coreRawMetadataValid) {
            throw SensorAuthorityUnavailableException(
                "UNSAFE_TO_PROCESS:${sensorMetadata.coreRawMetadataStatus}"
            )
        }
        if (sensorMetadata.logicalMetadataFallbackUsed || sensorMetadata.foreignSensorMetadataUsed) {
            throw SensorAuthorityUnavailableException("UNSAFE_TO_PROCESS:SENSOR_AUTHORITY_FALLBACK_FORBIDDEN")
        }
        if (sensorMetadata.sensorIdentity.sourceId != sensorMetadata.calibrationSourceId ||
            sensorMetadata.sensorIdentity.sourceId != sensorMetadata.characteristicsSourceId ||
            sensorMetadata.sensorIdentity.sourceId != sensorMetadata.captureResultSourceId
        ) {
            throw SensorAuthorityUnavailableException("UNSAFE_TO_PROCESS:SENSOR_METADATA_SOURCE_MISMATCH")
        }

        val cfa = sensorMetadata.cfa.value ?: -1
        val sensorOrientation = sensorMetadata.orientationField.value ?: -1
        val sensorTimestamp = sensorMetadata.timestampField.value
        val sensorSensitivityIso = sensorMetadata.sensitivityIsoField.value ?: 0
        val sensorExposureTimeNs = sensorMetadata.exposureTimeNsField.value ?: 0L
        val postRawSensitivityBoost = sensorMetadata.postRawSensitivityBoostField.value
            ?.takeIf { it > 0 }
            ?: 100.also {
                warnings.add(
                    "CONTROL_POST_RAW_SENSITIVITY_BOOST unavailable; explicit neutral 100% boost used"
                )
            }

        val metadataWhite = sensorMetadata.effectiveWhiteLevelField.value
        val rawWhite = when {
            metadataWhite != null && metadataWhite > 0 -> metadataWhite
            isRaw -> throw SensorAuthorityUnavailableException("UNSAFE_TO_PROCESS:WHITE_LEVEL_UNAVAILABLE")
            else -> 255.also {
                warnings.add("White level unavailable for non-RAW input; explicit 8-bit domain value 255 used")
            }
        }
        val whiteScale = if (domain == RawDomain.RAW10_PACKED_10BIT && rawWhite > 1023) {
            1023f / rawWhite.toFloat()
        } else {
            1f
        }
        val appliedWhite = if (domain == RawDomain.RAW10_PACKED_10BIT && rawWhite > 1023) {
            1023
        } else {
            rawWhite.coerceAtLeast(1)
        }

        val effectiveBlackList = sensorMetadata.effectiveBlackLevel.value
        val rawBlackValues = when {
            effectiveBlackList != null && effectiveBlackList.size >= 4 ->
                FloatArray(4) { effectiveBlackList[it] }
            isRaw -> throw SensorAuthorityUnavailableException("UNSAFE_TO_PROCESS:BLACK_LEVEL_UNAVAILABLE")
            else -> FloatArray(4).also {
                warnings.add("Black level unavailable for non-RAW input; explicit no-op zero level used")
            }
        }
        val appliedBlack = mosaicToCanonical(rawBlackValues, cfa).map { value ->
            (value * whiteScale).coerceIn(0f, appliedWhite.coerceAtLeast(2) - 1f)
        }.toFloatArray()
        if (domain == RawDomain.RAW10_PACKED_10BIT && rawWhite > 1023) {
            warnings.add(
                "White/black metadata scaled from metadata domain white=$rawWhite to RAW10 domain " +
                    "white=1023 with scale=${whiteScale.format6()}"
            )
        }
        if (!sensorMetadata.dynamicBlackLevel.isValid && sensorMetadata.staticBlackLevel.isValid) {
            warnings.add(
                "Dynamic black unavailable/invalid; exact-authority static SENSOR_BLACK_LEVEL_PATTERN used"
            )
        }

        val cfaSupportedForBayer = cfa in listOf(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR
        )
        val normalizationCalibrationValid =
            sensorMetadata.effectiveWhiteLevelField.isValid && sensorMetadata.effectiveBlackLevel.isValid

        val noise = resolveSensorNoiseProfile(sensorMetadata, warnings)
        val wb = resolveWhiteBalanceGains(sensorMetadata, warnings)
        val colorMatrix = resolveColorCorrectionMatrix(sensorMetadata, warnings)

        val hasNoiseProfile = noise.values != null
        val noiseProfileValid = noise.values != null && noise.values.all { it.isFinite() && it >= 0.0 }
        val baseNoiseApplied = hasNoiseProfile && noiseProfileValid &&
            normalizationCalibrationValid && cfaSupportedForBayer

        val calibrationProfileBinding = CalibrationProfileBinding.from(sensorMetadata)
        if (isRaw && !calibrationProfileBinding.provenance.safeForCalibration) {
            throw SensorAuthorityUnavailableException(
                "UNSAFE_TO_PROCESS:CALIBRATION_PROVENANCE_${calibrationProfileBinding.provenance.rejectionReason}"
            )
        }

        return BaseSensorCalibration(
            lensId = lensId,
            physicalCameraId = physicalCameraId,
            calibrationProfileId = calibrationProfileBinding.calibrationProfileId,
            calibrationProfileBinding = calibrationProfileBinding,
            frameSource = label,
            cfaPattern = cfa,
            cfaName = cfaName(cfa),
            sensorOrientation = sensorOrientation,
            sensorTimestampNs = sensorTimestamp,
            sensorSensitivityIso = sensorSensitivityIso,
            sensorExposureTimeNs = sensorExposureTimeNs,
            postRawSensitivityBoost = postRawSensitivityBoost,
            inputRawFormat = frameSourceFormat,
            inputBitDepth = bitDepth,
            inputDomain = domain,
            baseWhiteLevel = appliedWhite,
            baseWhiteLevelSource = sensorMetadata.effectiveWhiteLevelField.source,
            baseWhiteLevelRawMetadata = metadataWhite,
            baseWhiteLevelAppliedDomain = appliedDomain,
            baseWhiteLevelScaleFactor = whiteScale,
            dynamicWhiteLevelAvailable = sensorMetadata.dynamicWhiteLevelField.isValid,
            baseBlackLevels = appliedBlack,
            baseBlackLevelSource = sensorMetadata.effectiveBlackLevel.source,
            baseBlackLevelRawMetadataValues = rawBlackValues,
            baseBlackLevelAppliedDomain = appliedDomain,
            baseBlackLevelScaleFactor = whiteScale,
            dynamicBlackLevelAvailable = sensorMetadata.dynamicBlackLevel.isValid,
            blackSubtractionApplied = rawBlackValues.any { abs(it) > 0.0001f },
            baseNoiseProfile = noise.values,
            baseNoiseProfileSource = noise.source,
            baseNoiseProfileFallbackReason = noise.fallbackReason,
            baseNoiseProfilePresent = hasNoiseProfile,
            baseNoiseProfileAppliedByDefault = baseNoiseApplied,
            baseNoiseProfileFormula = NOISE_FORMULA,
            baseNoiseProfilePairCount = noise.pairCount,
            baseNoiseProfileChannelCount = noise.channelCount,
            baseNoiseProfileChannelMap = noise.channelMap,
            hasNoiseProfile = hasNoiseProfile,
            noiseProfileValid = noiseProfileValid,
            normalizationCalibrationValid = normalizationCalibrationValid,
            cfaSupportedForBayerNoiseModel = cfaSupportedForBayer,
            baseWbGains = wb.values,
            baseWbSource = wb.source,
            baseWbAppliedByDefault = wb.applied,
            baseColorMatrix = colorMatrix.values,
            baseColorMatrixSource = colorMatrix.source,
            baseColorMatrixApplied = colorMatrix.applied,
            baseColorMatrixIdentityFallbackUsed = colorMatrix.identityFallbackUsed,
            baseColorMatrixRejectReason = colorMatrix.rejectReason,
            baseColorMatrixNote = colorMatrix.note,
            warnings = warnings.distinct(),
            baseColorMatrixPreNormalization = colorMatrix.preNormalizationValues?.copyOf(),
            baseColorMatrixNeutralNormalizationApplied = colorMatrix.neutralNormalizationApplied,
            baseColorMatrixLegacyNeutralNormalized = colorMatrix.legacyNeutralNormalizedValues?.copyOf()
        )
    }

    private fun buildFinalCalibration(
        base: BaseSensorCalibration,
        override: LensOverrideLayer,
        profileAwbSettings: ProfileAwbSettings?,
        profileNoiseTuning: ProfileNoiseTuning? = null,
        stableAutoWhiteBalance: StableWhiteBalanceSnapshot? = null,
        characteristics: CameraCharacteristics
    ): FinalSensorCalibration {
        val warnings = mutableListOf<String>()
        warnings.addAll(base.warnings)
        warnings.addAll(override.warnings)

        val manualBlack = override.manualBlackLevels
        val finalBlack = when {
            override.blackLevelMode.equals("Manual", ignoreCase = true) && manualBlack != null && manualBlack.size >= 4 -> {
                warnings.add("Manual lens black level override active; values are expected in already-applied ${base.baseBlackLevelAppliedDomain} domain")
                mosaicToCanonical(manualBlack, base.cfaPattern).map { value ->
                    value.coerceIn(0f, base.baseWhiteLevel.coerceAtLeast(2) - 1f)
                }.toFloatArray()
            }
            override.blackLevelMode.equals("Dynamic", ignoreCase = true) -> {
                val scale = (override.dynamicBlackLevelPercent / 100.0f).coerceIn(0.0f, 2.0f)
                warnings.add("Dynamic lens black level override active; scale=${scale.format6()} over ${base.baseBlackLevelSource}")
                FloatArray(4) {
                    (base.baseBlackLevels[it] * scale).coerceIn(0f, base.baseWhiteLevel.coerceAtLeast(2) - 1f)
                }
            }
            else -> base.baseBlackLevels
        }
        val finalBlackSource = when {
            override.blackLevelMode.equals("Manual", ignoreCase = true) && manualBlack != null -> "Lens ID Manual black level override"
            override.blackLevelMode.equals("Dynamic", ignoreCase = true) -> "Lens ID Dynamic black level override ${override.dynamicBlackLevelPercent.format5()}%"
            else -> base.baseBlackLevelSource
        }

        val manualNoise = override.manualNoiseValues?.let { mosaicNoiseToCanonical(it, base.cfaPattern) }
        val manualNoiseAnchorIso = 100.0
        var manualNoiseGainRatio = 1.0
        var manualNoiseSingleAnchorScaled = false

        val cameraNoiseCanonical = base.baseNoiseProfile?.let { mosaicNoiseToCanonical(it, base.cfaPattern) }

        // Physical sensor-noise calibration and SPECTRA pixel processing are independent authorities.
        // Camera2 S/O remains available to the conventional RAW baseline even when the profile has
        // SPECTRA disabled. SPECTRA only owns the extra Context Fusion passes; it never owns whether
        // the physical variance model exists. An explicit Manual lens model remains authoritative.
        val spectraProcessingRequested = profileNoiseTuning?.spectraEnabled ?: false
        val noiseAuthority = NoiseModelAuthorityPolicy.resolve(
            lensNoiseMode = override.noiseMode,
            spectraRequested = spectraProcessingRequested,
            cameraNoiseProfileAvailable = cameraNoiseCanonical?.isNotEmpty() == true,
            manualNoiseProfileAvailable = manualNoise?.isNotEmpty() == true
        )
        val effectiveNoiseMode = noiseAuthority.physicalNoiseMode
        val spectraProcessingEnabled = noiseAuthority.spectraProcessingMode != "Off"

        val noiseValues = when (effectiveNoiseMode) {
            "Auto" -> cameraNoiseCanonical
            "Manual" -> {
                if (manualNoise != null) {
                    val postRawGain = (base.postRawSensitivityBoost.toDouble() / 100.0).coerceAtLeast(0.01)
                    val effectiveFrameIso = (base.sensorSensitivityIso.toDouble() * postRawGain).coerceAtLeast(1.0)
                    manualNoiseGainRatio = (effectiveFrameIso / manualNoiseAnchorIso).coerceIn(0.01, 500.0)
                    manualNoiseSingleAnchorScaled = true
                    warnings.add("singleAnchorManualNoiseScaling applied: gainRatio=${manualNoiseGainRatio.format5()} (effectiveFrameIso=$effectiveFrameIso, manualAnchorIso=$manualNoiseAnchorIso); warning: single-anchor approximation in use")
                    DoubleArray(manualNoise.size) { i ->
                        val isSlope = (i % 2 == 0)
                        val scale = if (isSlope) manualNoiseGainRatio else (manualNoiseGainRatio * manualNoiseGainRatio)
                        (manualNoise[i] * scale).coerceAtLeast(0.0)
                    }
                } else null
            }
            else -> null
        }
        if (override.manualNoiseValues != null && manualNoise == null) {
            warnings.add("Manual S/O noise model is unsupported for CFA ${base.cfaName}; no RGGB fallback was used")
        }

        val hasNoiseProfile = base.hasNoiseProfile || manualNoise != null
        val noiseProfileValid = noiseValues != null && noiseValues.isNotEmpty() && noiseValues.all { it.isFinite() && it >= 0.0 }
        val normalizationCalibrationValid = base.normalizationCalibrationValid
        val cfaSupportedForBayer = base.cfaSupportedForBayerNoiseModel
        val effectiveNoiseProfileApplied = hasNoiseProfile && noiseProfileValid && normalizationCalibrationValid && cfaSupportedForBayer

        val noiseProfileNotAppliedReason = when {
            effectiveNoiseMode == "Off" -> "noise_model_mode_off"
            !hasNoiseProfile -> "missing_sensor_noise_profile"
            !noiseProfileValid -> "invalid_noise_profile_values"
            !normalizationCalibrationValid -> "missing_valid_black_or_white_level"
            !cfaSupportedForBayer -> "unsupported_cfa_layout_${base.cfaName}"
            else -> "none"
        }

        val manualColorOverrideActive = override.manualColorMatrix != null
        val safeProfileAwb = profileAwbSettings?.sanitized()
        val sensorAwareProfileAwb = safeProfileAwb
            ?.takeIf { it.mode != ProfileAwbModes.SYSTEM_AUTO }
            ?.let { settings ->
                runCatching { RawColorTransformEngine.computeProfileWhiteBalance(characteristics, settings) }
                    .getOrNull()
                    ?.takeIf { solution ->
                        val gains = solution.bayerWbGains
                        val safeGains = gains.size >= 4 && gains.take(4).all { gain ->
                            gain.isFinite() && gain in 0.35f..4.50f
                        }
                        val redBlueRatio = if (gains.size >= 4 && gains[3] > 1.0e-4f) gains[0] / gains[3] else Float.NaN
                        solution.isValid && safeGains && redBlueRatio.isFinite() && redBlueRatio in 0.15f..6.67f
                    }
            }

        val systemAwbRequested = when {
            safeProfileAwb?.mode == ProfileAwbModes.SYSTEM_AUTO -> true
            safeProfileAwb == null && override.awbMode == "System" -> true
            else -> false
        }
        val exactFrameCamera2Wb = base.baseWbAppliedByDefault &&
            base.baseWbSource.contains("CaptureResult.COLOR_CORRECTION_GAINS", ignoreCase = true)
        val exactFrameCamera2Ccm = base.baseColorMatrixApplied &&
            base.baseColorMatrixSource.contains("CaptureResult.COLOR_CORRECTION_TRANSFORM", ignoreCase = true)
        val exactFrameCamera2ColorPair = systemAwbRequested && exactFrameCamera2Wb && exactFrameCamera2Ccm

        // System Auto uses the single temporal owner's recent physical scene solution only when
        // it carries a coherent WB+CCM pair. Camera2 remains the exact-frame prior/fallback whenever
        // physical evidence is unavailable, weak, stale or incomplete; no gain-only history is
        // mixed with a current-frame matrix.
        val stablePhysicalSystemAutoPair = stableAutoWhiteBalance?.takeIf { snapshot ->
            val selectedTimestampNs = base.sensorTimestampNs ?: 0L
            val ageNs = if (selectedTimestampNs > 0L && snapshot.sensorTimestampNs > 0L) {
                abs(selectedTimestampNs - snapshot.sensorTimestampNs)
            } else {
                Long.MAX_VALUE
            }
            val matrix = snapshot.copyColorMatrix()
            systemAwbRequested && snapshot.source == WhiteBalanceObservationSource.PHYSICAL_SCENE &&
                snapshot.confidence >= 0.55f && snapshot.dataAuthority >= 0.10f &&
                ageNs <= 1_000_000_000L && snapshot.gains.size >= 4 &&
                snapshot.gains.take(4).all { gain -> gain.isFinite() && gain in 0.25f..6.0f } &&
                matrix != null && matrix.size >= 9 &&
                RawColorTransformEngine.validateSensorToLinearSrgbMatrix(matrix.copyOf(9)).valid
        }
        val stableSystemAutoWbFallback = stableAutoWhiteBalance?.takeIf { snapshot ->
            systemAwbRequested && stablePhysicalSystemAutoPair == null &&
                !exactFrameCamera2Wb && !exactFrameCamera2Ccm &&
                snapshot.confidence >= 0.55f && snapshot.gains.size >= 4 &&
                snapshot.gains.take(4).all { gain -> gain.isFinite() && gain in 0.25f..6.0f }
        }

        if (safeProfileAwb != null && safeProfileAwb.mode != ProfileAwbModes.SYSTEM_AUTO && sensorAwareProfileAwb == null) {
            warnings.add(
                "Profile manual WB rejected by safe sensor-gain envelope; falling back to capture-result Camera2 AWB. " +
                    "The stored profile is preserved and cannot brick RAW preview/startup."
            )
        }
        if (systemAwbRequested && exactFrameCamera2Wb.xor(exactFrameCamera2Ccm)) {
            warnings.add(
                "Incomplete selected-frame Camera2 color pair: exactWb=$exactFrameCamera2Wb exactCcm=$exactFrameCamera2Ccm. " +
                    "Historical stable WB is not mixed with current-frame color metadata."
            )
        }

        val wb = when {
            sensorAwareProfileAwb != null -> sensorAwareProfileAwb.bayerWbGains.copyOf()
            stablePhysicalSystemAutoPair != null -> stablePhysicalSystemAutoPair.copyGains()
            systemAwbRequested && exactFrameCamera2Wb -> base.baseWbGains.copyOf()
            stableSystemAutoWbFallback != null -> stableSystemAutoWbFallback.copyGains()
            safeProfileAwb?.mode == ProfileAwbModes.SYSTEM_AUTO -> base.baseWbGains.copyOf()
            safeProfileAwb != null -> base.baseWbGains.copyOf()
            override.awbMode != "System" -> {
                warnings.add("Legacy Lens ID AWB override applied because no profile AWB snapshot was supplied")
                applyAwbOverride(base.baseWbGains, override)
            }
            else -> base.baseWbGains.copyOf()
        }
        val wbSource = when {
            sensorAwareProfileAwb != null ->
                "Sensor-aware profile WB ${sensorAwareProfileAwb.targetKelvin}K / ${sensorAwareProfileAwb.illuminantModel} / Camera2 calibration matrices"
            stablePhysicalSystemAutoPair != null ->
                "BnCam physical AWB confidence=${String.format(Locale.US, "%.3f", stablePhysicalSystemAutoPair.confidence)} " +
                    "dataAuthority=${String.format(Locale.US, "%.3f", stablePhysicalSystemAutoPair.dataAuthority)} " +
                    "mixedLight=${String.format(Locale.US, "%.3f", stablePhysicalSystemAutoPair.mixedLightScore)}"
            exactFrameCamera2ColorPair ->
                "CaptureResult exact-frame color pair: COLOR_CORRECTION_GAINS + COLOR_CORRECTION_TRANSFORM"
            systemAwbRequested && exactFrameCamera2Wb ->
                "Exact-frame Camera2 WB gains with non-frame CCM fallback: ${base.baseColorMatrixSource}"
            stableSystemAutoWbFallback != null ->
                "BnCam stable Camera2 AWB bootstrap confidence=${String.format(Locale.US, "%.3f", stableSystemAutoWbFallback.confidence)} samples=${stableSystemAutoWbFallback.acceptedSampleCount}"
            safeProfileAwb?.mode == ProfileAwbModes.SYSTEM_AUTO -> base.baseWbSource
            safeProfileAwb != null -> "Profile WB fallback -> ${base.baseWbSource}"
            override.awbMode != "System" -> "Legacy Lens ID AWB override profile=${override.awbProfile} over ${base.baseWbSource}"
            else -> base.baseWbSource
        }

        // WB gains and the post-demosaic sensor->linear-sRGB matrix are separate operations but
        // they form one colorimetric solution. System Auto uses the exact/temporal Camera2 pair.
        // Manual/profile Kelvin uses RawColorTransformEngine's calibration-derived post-WB matrix
        // so a new illuminant is never paired with a CCM solved for a different white point.
        val profileColorMatrix = sensorAwareProfileAwb?.mPostCompensated?.copyOf()?.takeIf {
            RawColorTransformEngine.validateSensorToLinearSrgbMatrix(it).valid
        }
        val colorMatrix = when {
            manualColorOverrideActive -> override.manualColorMatrix
            sensorAwareProfileAwb != null && profileColorMatrix != null -> profileColorMatrix
            stablePhysicalSystemAutoPair != null -> stablePhysicalSystemAutoPair.copyColorMatrix()
            else -> base.baseColorMatrix
        }
        val colorSource = when {
            manualColorOverrideActive -> "Lens ID Manual color matrix override"
            sensorAwareProfileAwb != null && profileColorMatrix != null ->
                "Sensor-aware profile WB ${sensorAwareProfileAwb.targetKelvin}K paired post-WB sensor->linear-sRGB matrix"
            stablePhysicalSystemAutoPair != null ->
                "BnCam physical AWB temporally paired Camera2 CCM"
            exactFrameCamera2ColorPair ->
                "CaptureResult exact-frame color pair: COLOR_CORRECTION_TRANSFORM + COLOR_CORRECTION_GAINS"
            else -> base.baseColorMatrixSource
        }
        val colorApplied = when {
            manualColorOverrideActive -> true
            sensorAwareProfileAwb != null && profileColorMatrix != null -> true
            stablePhysicalSystemAutoPair != null -> colorMatrix != null
            else -> colorMatrix != null && !base.baseColorMatrixIdentityFallbackUsed
        }
        val identityFallback = !manualColorOverrideActive &&
            !(sensorAwareProfileAwb != null && profileColorMatrix != null) &&
            stablePhysicalSystemAutoPair == null && base.baseColorMatrixIdentityFallbackUsed

        val applicability = listOf(
            "Black Level Applied To" to when (base.inputDomain) {
                RawDomain.RAW10_PACKED_10BIT -> "RAW10_NATIVE_ISP_BLACK_SUBTRACTION"
                RawDomain.RAW_SENSOR_16BIT -> "RAW_SENSOR_NATIVE_ISP_BLACK_SUBTRACTION"
                else -> "NOT_APPLICABLE_TO_YUV_OR_UNKNOWN"
            },
            "White Level Applied To" to when (base.inputDomain) {
                RawDomain.RAW10_PACKED_10BIT -> "RAW10_NATIVE_ISP_NORMALIZATION"
                RawDomain.RAW_SENSOR_16BIT -> "RAW_SENSOR_NATIVE_ISP_NORMALIZATION"
                else -> "NOT_APPLICABLE_TO_YUV_OR_UNKNOWN"
            },
            "Noise Model Applied To" to if (noiseValues != null) "RAW_DOMAIN_NATIVE_ISP_DENOISE_SCALING" else "NOT_APPLIED_MISSING_OR_INVALID",
            "DNG Standard Tags Overridden" to "false"
        )

        val baseS = DoubleArray(4) { i -> cameraNoiseCanonical?.getOrNull(i * 2) ?: 0.0 }
        val baseO = DoubleArray(4) { i -> cameraNoiseCanonical?.getOrNull(i * 2 + 1) ?: 0.0 }
        val effS = DoubleArray(4) { i -> noiseValues?.getOrNull(i * 2) ?: baseS[i] }
        val effO = DoubleArray(4) { i -> noiseValues?.getOrNull(i * 2 + 1) ?: baseO[i] }

        val snapshot = NoiseModelSnapshotV3(
            lensKey = com.bncam.data.settings.StableLensKey.fromString(base.calibrationProfileId).value,
            sourceFormat = base.frameSource,
            iso = base.sensorSensitivityIso,
            exposureTimeNs = base.sensorExposureTimeNs,
            postRawSensitivityBoost = base.postRawSensitivityBoost,
            cfaPattern = base.cfaPattern,
            cfaName = base.cfaName,
            whiteLevel = base.baseWhiteLevel,
            blackLevel = finalBlack.clone(),
            cameraS = baseS,
            cameraO = baseO,
            effectiveS = effS,
            effectiveO = effO,
            chromaUserScale = 1.0f,
            lumaUserScale = 1.0f,
            spectraMode = noiseAuthority.spectraProcessingMode,
            signalModelConfidence = when {
                !effectiveNoiseProfileApplied -> 0.0f
                effectiveNoiseMode == "Auto" -> 1.0f
                manualNoiseSingleAnchorScaled -> 0.65f
                else -> 0.75f
            },
            sourceFlags = 0L,
            timestampNs = base.sensorTimestampNs ?: System.nanoTime()
        )

        return FinalSensorCalibration(
            base = base,
            override = override,
            effectiveWhiteLevel = base.baseWhiteLevel,
            effectiveWhiteLevelSource = base.baseWhiteLevelSource,
            effectiveWhiteLevelAppliedDomain = base.baseWhiteLevelAppliedDomain,
            effectiveWhiteLevelScaleFactor = base.baseWhiteLevelScaleFactor,
            effectiveBlackLevels = finalBlack,
            effectiveBlackLevelSource = finalBlackSource,
            blackLevelScaleFactor = base.baseBlackLevelScaleFactor,
            effectiveBlackLevelAppliedDomain = base.baseBlackLevelAppliedDomain,
            blackSubtractionApplied = finalBlack.any { it > 0f },
            noiseModelMode = effectiveNoiseMode,
            spectraProcessingEnabled = spectraProcessingEnabled,
            effectiveNoiseProfile = noiseValues,
            effectiveNoiseProfileSource = when {
                effectiveNoiseMode == "Off" -> "Off"
                manualNoise != null -> "Lens ID Manual S/O (${base.cfaName})"
                else -> base.baseNoiseProfileSource
            },
            effectiveNoiseProfileFallbackReason = when {
                override.noiseMode == "Off" -> "None"
                manualNoise != null -> "None"
                else -> base.baseNoiseProfileFallbackReason
            },
            hasNoiseProfile = hasNoiseProfile,
            noiseProfileValid = noiseProfileValid,
            normalizationCalibrationValid = normalizationCalibrationValid,
            cfaSupportedForBayerNoiseModel = cfaSupportedForBayer,
            effectiveNoiseProfileApplied = effectiveNoiseProfileApplied,
            noiseProfileNotAppliedReason = noiseProfileNotAppliedReason,
            manualNoiseAnchorIso = manualNoiseAnchorIso,
            manualNoiseGainRatio = manualNoiseGainRatio,
            manualNoiseSingleAnchorScaled = manualNoiseSingleAnchorScaled,
            effectiveNoiseProfilePairCount = if (noiseValues != null) noiseValues.size / 2 else 0,
            effectiveNoiseProfileChannelCount = if (noiseValues != null) noiseValues.size / 2 else 0,
            effectiveNoiseProfileFormula = base.baseNoiseProfileFormula,
            effectiveNoiseProfileChannelMap = "Canonical R,Gr,Gb,B; converted from ${base.baseNoiseProfileChannelMap}",
            effectiveWbGains = wb,
            effectiveWbSource = wbSource,
            effectiveWbApplied = wb.size >= 4 && wb.all { it.isFinite() && it > 0f },
            effectiveColorMatrix = colorMatrix,
            effectiveColorMatrixSource = colorSource,
            effectiveColorMatrixApplied = colorApplied,
            effectiveColorMatrixIdentityFallbackUsed = identityFallback,
            effectiveColorMatrixRejectReason = when {
                override.manualColorMatrix != null -> "none"
                sensorAwareProfileAwb != null && profileColorMatrix != null -> "none"
                sensorAwareProfileAwb != null -> "profile_color_matrix_invalid_fallback_to_base"
                else -> base.baseColorMatrixRejectReason
            },
            effectiveColorMatrixNote = when {
                override.manualColorMatrix != null -> "Manual valid 3x3 matrix applied"
                sensorAwareProfileAwb != null && profileColorMatrix != null ->
                    "Profile Kelvin uses coherent Camera2 calibration-derived WB + post-WB sensor-to-linear-sRGB matrix"
                sensorAwareProfileAwb != null ->
                    "Profile color matrix unavailable; base sensor matrix fallback retained"
                else -> base.baseColorMatrixNote
            },
            rawInputDomain = base.inputDomain,
            ispWorkingDomain = RawDomain.MASTER_RAW16_NORMALIZED,
            applicability = applicability,
            calibrationApplied = base.inputDomain == RawDomain.RAW10_PACKED_10BIT || base.inputDomain == RawDomain.RAW_SENSOR_16BIT,
            pipelineWarnings = warnings.distinct(),
            noiseSnapshot = snapshot
        )
    }

    private fun defaultOverrideLayer() = LensOverrideLayer(
        blackLevelMode = "System",
        dynamicBlackLevelPercent = 100f,
        manualBlackLevels = null,
        noiseMode = "Off",
        noisePresetName = "Off",
        manualNoiseValues = null,
        colorMode = "System",
        colorPresetName = "Default",
        manualColorMatrix = null,
        awbMode = "System",
        awbProfile = "System",
        awbRatio = 1f,
        awbTemp = 0f,
        awbIntensity = 0f,
        warnings = emptyList()
    )

    private const val NOISE_FORMULA = "variance = S * x + O"

    private data class NoiseResolution(
        val values: DoubleArray?,
        val source: String,
        val fallbackReason: String,
        val pairCount: Int,
        val channelCount: Int,
        val channelMap: String
    )

    private fun resolveSensorNoiseProfile(sensorMetadata: SensorMetadata, warnings: MutableList<String>): NoiseResolution {
        val channelMap = "Android SENSOR_NOISE_PROFILE coefficient pairs flattened as S0,O0,S1,O1...; channel order follows Android CFA planes for the exact sensor-authority result"
        val field = sensorMetadata.noiseProfileSoField
        val profile = field.value
        if (!field.isValid || profile.isNullOrEmpty()) {
            val reason = if (field.reason.isBlank()) "SENSOR_NOISE_PROFILE_UNAVAILABLE" else field.reason
            warnings.add("SENSOR_NOISE_PROFILE unavailable/invalid from uniform SensorMetadata: $reason")
            return NoiseResolution(null, field.source, reason, (profile?.size ?: 0) / 2, (profile?.size ?: 0) / 2, channelMap)
        }

        val out = profile.toDoubleArray()
        val pairCount = out.size / 2
        if (out.size % 2 != 0 || out.any { !it.isFinite() || it < 0.0 } || out.all { abs(it) < 1.0e-12 }) {
            warnings.add("Invalid SENSOR_NOISE_PROFILE reached calibration despite uniform metadata validation")
            return NoiseResolution(null, field.source, "UNSAFE_SENSOR_NOISE_PROFILE", pairCount, pairCount, channelMap)
        }
        return NoiseResolution(out, field.source, "None", pairCount, pairCount, channelMap)
    }

    private data class WbResolution(val values: FloatArray, val source: String, val applied: Boolean)

    private fun resolveWhiteBalanceGains(sensorMetadata: SensorMetadata, warnings: MutableList<String>): WbResolution {
        val field = sensorMetadata.colorCorrectionGainsField
        val gains = field.value
        if (field.isValid && gains != null && gains.size == 4) {
            val out = gains.toFloatArray()
            if (out.all { it.isFinite() && it > 0f }) {
                return WbResolution(out, field.source, true)
            }
        }
        warnings.add("WB gains unavailable/invalid in uniform SensorMetadata; controlled neutral fallback used (${field.reason})")
        return WbResolution(
            floatArrayOf(1f, 1f, 1f, 1f),
            "CONTROLLED_NEUTRAL_WB_FALLBACK[${field.source}; ${field.validity}; ${field.reason}]",
            false
        )
    }

    private fun applyAwbOverride(base: FloatArray, override: LensOverrideLayer): FloatArray {
        val safe = if (base.size >= 4) base else floatArrayOf(1f, 1f, 1f, 1f)
        val temp = (override.awbTemp * override.awbIntensity).coerceIn(-1.0f, 1.0f)
        val ratio = override.awbRatio.coerceIn(0.25f, 1.75f)
        val red = (safe[0] * ratio * (1.0f + temp)).coerceIn(0.1f, 10.0f)
        val blue = (safe[3] * (2.0f - ratio) * (1.0f - temp)).coerceIn(0.1f, 10.0f)
        return floatArrayOf(red, safe[1].coerceIn(0.1f, 10.0f), safe[2].coerceIn(0.1f, 10.0f), blue)
    }

    private fun canonicalIndex(plane: ColorPlane): Int = when (plane) {
        ColorPlane.RED -> 0
        ColorPlane.GREEN_RED -> 1
        ColorPlane.GREEN_BLUE -> 2
        ColorPlane.BLUE -> 3
        ColorPlane.MONO -> 0
    }

    private fun mosaicToCanonical(values: FloatArray, cfa: Int): FloatArray {
        if (values.size < 4) return FloatArray(4) { values.getOrElse(0) { 0f } }
        val descriptor = CfaArrangementDescriptor.from(cfa)
        if (descriptor !is CfaArrangementDescriptor.Bayer) return values.copyOf(4)
        val out = FloatArray(4)
        descriptor.channels.forEach { channel ->
            out[canonicalIndex(channel.colorPlane)] = values[channel.mosaicIndex]
        }
        return out
    }

    private fun canonicalToMosaic(values: FloatArray, cfa: Int): FloatArray {
        if (values.size < 4) return FloatArray(4) { values.getOrElse(0) { 0f } }
        val descriptor = CfaArrangementDescriptor.from(cfa)
        if (descriptor !is CfaArrangementDescriptor.Bayer) return values.copyOf(4)
        val out = FloatArray(4)
        descriptor.channels.forEach { channel ->
            out[channel.mosaicIndex] = values[canonicalIndex(channel.colorPlane)]
        }
        return out
    }

    private fun mosaicNoiseToCanonical(values: DoubleArray, cfa: Int): DoubleArray? {
        return when (val descriptor = CfaArrangementDescriptor.from(cfa)) {
            is CfaArrangementDescriptor.Monochrome -> values.takeIf { it.size >= 2 }?.copyOf(2)
            is CfaArrangementDescriptor.Bayer -> {
                if (values.size < 8) return null
                DoubleArray(8).also { out ->
                    descriptor.channels.forEach { channel ->
                        val canonical = canonicalIndex(channel.colorPlane)
                        out[canonical * 2] = values[channel.mosaicIndex * 2]
                        out[canonical * 2 + 1] = values[channel.mosaicIndex * 2 + 1]
                    }
                }
            }
            is CfaArrangementDescriptor.Unsupported -> null
        }
    }

    private data class MatrixResolution(
        val values: FloatArray?,
        val source: String,
        val applied: Boolean,
        val identityFallbackUsed: Boolean,
        val rejectReason: String,
        val note: String,
        val preNormalizationValues: FloatArray?,
        val neutralNormalizationApplied: Boolean,
        val legacyNeutralNormalizedValues: FloatArray?
    )

    private data class MatrixCandidate(
        val source: String,
        val values: FloatArray?,
        val note: String,
        val declaredInputSpace: String,
        val declaredOutputSpace: String,
        val xyzToSrgbApplied: Boolean,
        val chromaticAdaptationApplied: Boolean,
        val deviceCalibrationApplied: Boolean,
        val sourcePriority: Int
    )

    private data class ForwardMatrixCandidateResolution(
        val values: FloatArray?,
        val deviceCalibrationApplied: Boolean,
        val calibrationLabel: String
    )

    private fun resolveForwardMatrixCandidate(
        forwardField: SensorMetadataValue<List<Float>>,
        calibrationField: SensorMetadataValue<List<Float>>
    ): ForwardMatrixCandidateResolution {
        val forward = forwardField.value?.takeIf { forwardField.isValid }?.toFloatArray()
        val calibration = calibrationField.value?.takeIf { calibrationField.isValid }?.toFloatArray()
        val resolved = RawColorTransformEngine.resolveActualSensorForwardMatrixToLinearSrgb(
            forwardMatrix = forward,
            calibrationTransform = calibration
        )
        return ForwardMatrixCandidateResolution(
            values = resolved.values,
            deviceCalibrationApplied = resolved.deviceCalibrationApplied,
            calibrationLabel = resolved.calibrationLabel
        )
    }

    private fun resolveColorCorrectionMatrix(
        sensorMetadata: SensorMetadata,
        warnings: MutableList<String>
    ): MatrixResolution {
        val rejected = mutableListOf<String>()
        val candidates = mutableListOf<MatrixCandidate>()

        // Camera2 defines this result as the actual sensor-RGB -> output linear-sRGB transform.
        // Phase 8 therefore validates and preserves it exactly; no BnCam row normalization,
        // XYZ conversion or chromatic adaptation is applied on top of it.
        candidates.add(MatrixCandidate(
            source = "${sensorMetadata.colorCorrectionTransformField.source} -> linear_sRGB",
            values = sensorMetadata.colorCorrectionTransformField.value
                ?.takeIf { sensorMetadata.colorCorrectionTransformField.isValid }
                ?.toFloatArray(),
            note = "capture_result_sensor_rgb_to_linear_srgb_direct_phase8_preserved",
            declaredInputSpace = "actual_sensor_RGB_after_WB",
            declaredOutputSpace = "linear_sRGB_D65",
            xyzToSrgbApplied = false,
            chromaticAdaptationApplied = false,
            deviceCalibrationApplied = false,
            sourcePriority = 1
        ))

        // ForwardMatrix is defined in reference-sensor space. Convert actual device-sensor RGB
        // back to the reference sensor with inverse(SENSOR_CALIBRATION_TRANSFORM), then apply
        // ForwardMatrix -> XYZ D50 -> Bradford D65 -> linear sRGB.
        val forward2 = resolveForwardMatrixCandidate(
            sensorMetadata.forwardMatrix2,
            sensorMetadata.cameraCalibration2
        )
        candidates.add(MatrixCandidate(
            source = "inverse(SENSOR_CALIBRATION_TRANSFORM2) -> SENSOR_FORWARD_MATRIX2 -> XYZ_D50 -> Bradford_D65 -> linear_sRGB",
            values = forward2.values,
            note = "forward_matrix2_${forward2.calibrationLabel}_xyz_d50_bradford_d65_to_srgb",
            declaredInputSpace = "actual_sensor_RGB_after_WB",
            declaredOutputSpace = "linear_sRGB_D65",
            xyzToSrgbApplied = true,
            chromaticAdaptationApplied = true,
            deviceCalibrationApplied = forward2.deviceCalibrationApplied,
            sourcePriority = 2
        ))

        val forward1 = resolveForwardMatrixCandidate(
            sensorMetadata.forwardMatrix1,
            sensorMetadata.cameraCalibration1
        )
        candidates.add(MatrixCandidate(
            source = "inverse(SENSOR_CALIBRATION_TRANSFORM1) -> SENSOR_FORWARD_MATRIX1 -> XYZ_D50 -> Bradford_D65 -> linear_sRGB",
            values = forward1.values,
            note = "forward_matrix1_${forward1.calibrationLabel}_xyz_d50_bradford_d65_to_srgb",
            declaredInputSpace = "actual_sensor_RGB_after_WB",
            declaredOutputSpace = "linear_sRGB_D65",
            xyzToSrgbApplied = true,
            chromaticAdaptationApplied = true,
            deviceCalibrationApplied = forward1.deviceCalibrationApplied,
            sourcePriority = 3
        ))

        // The old inverse(SENSOR_COLOR_TRANSFORM) fallbacks were not complete actual-sensor
        // transforms: ColorTransform maps XYZ -> reference sensor, so using its inverse without
        // the device calibration transform and illuminant adaptation is not a truthful substitute.
        // Android couples reference illuminants with ForwardMatrix metadata; if neither the direct
        // per-frame transform nor a valid ForwardMatrix route exists, use the explicit controlled
        // fallback instead of silently applying a partially specified matrix.

        for (candidate in candidates) {
            val values = candidate.values
            if (values == null) continue

            val rowSums = FloatArray(3) { row ->
                values[row * 3] + values[row * 3 + 1] + values[row * 3 + 2]
            }
            val det = values[0] * (values[4] * values[8] - values[5] * values[7]) -
                    values[1] * (values[3] * values[8] - values[5] * values[6]) +
                    values[2] * (values[3] * values[7] - values[4] * values[6])
            val neutralGray = FloatArray(3) { row -> rowSums[row] * 0.5f }
            val clippingRisk = rowSums.maxOrNull() ?: 1.0f

            Log.i(
                "SensorCalibration",
                "Phase8 matrix candidate [${candidate.source}]: " +
                        "inputSpace=${candidate.declaredInputSpace}, outputSpace=${candidate.declaredOutputSpace}, " +
                        "xyzToSrgbApplied=${candidate.xyzToSrgbApplied}, " +
                        "chromaticAdaptationApplied=${candidate.chromaticAdaptationApplied}, " +
                        "deviceCalibrationApplied=${candidate.deviceCalibrationApplied}, " +
                        "priority=${candidate.sourcePriority}, determinant=${String.format(Locale.US, "%.4f", det)}, " +
                        "rowSums=[${rowSums.joinToString(", ")}], neutralGray=[${neutralGray.joinToString(", ")}], " +
                        "clippingRisk=${String.format(Locale.US, "%.2f", clippingRisk)}"
            )

            if (candidate.source.contains("COLOR_CORRECTION_TRANSFORM") && candidate.xyzToSrgbApplied) {
                warnings.add("ERROR: CaptureResult.COLOR_CORRECTION_TRANSFORM must not be multiplied by XYZ->sRGB")
                continue
            }
            if (candidate.source.contains("SENSOR_FORWARD_MATRIX") && !candidate.xyzToSrgbApplied) {
                warnings.add("ERROR: ForwardMatrix must leave XYZ D50 through an explicit output transform")
                continue
            }

            val validation = validateColorMatrix(values)
            if (validation.first) {
                val legacyCounterfactual = legacyNeutralNormalizedMatrix(values)
                return MatrixResolution(
                    values = values.copyOf(),
                    source = candidate.source,
                    applied = true,
                    identityFallbackUsed = false,
                    rejectReason = "none",
                    note = "Phase8 validated matrix accepted without row normalization; ${candidate.note}; " +
                            "validationScore=${validation.second.format5()}",
                    preNormalizationValues = values.copyOf(),
                    neutralNormalizationApplied = false,
                    legacyNeutralNormalizedValues = legacyCounterfactual
                )
            }
            rejected.add("${candidate.source}: ${validation.third}")
        }

        val reason = if (rejected.isEmpty()) {
            "No usable standards-complete camera color metadata matrix was present"
        } else {
            rejected.joinToString(" | ")
        }
        warnings.add("Identity matrix fallback used: $reason")
        return MatrixResolution(
            values = IDENTITY_3X3.copyOf(),
            source = "CONTROLLED_IDENTITY_FALLBACK",
            applied = false,
            identityFallbackUsed = true,
            rejectReason = reason,
            note = "Identity fallback used only because no valid direct/ForwardMatrix color transform was available",
            preNormalizationValues = null,
            neutralNormalizationApplied = false,
            legacyNeutralNormalizedValues = null
        )
    }

    private fun legacyNeutralNormalizedMatrix(values: FloatArray): FloatArray? {
        if (values.size != 9 || values.any { !it.isFinite() }) return null
        val rowSums = FloatArray(3) { row -> values[row * 3] + values[row * 3 + 1] + values[row * 3 + 2] }
        if (rowSums.any { !it.isFinite() || abs(it) < 0.0001f }) return null
        val meanRowSum = rowSums.average().toFloat()
        if (!meanRowSum.isFinite() || abs(meanRowSum) < 0.0001f) return null
        val normalized = values.copyOf()
        for (row in 0..2) {
            val scale = meanRowSum / rowSums[row]
            for (column in 0..2) normalized[row * 3 + column] *= scale
        }
        return normalized
    }

    private fun validateColorMatrix(values: FloatArray): Triple<Boolean, Float, String> {
        val shared = RawColorTransformEngine.validateSensorToLinearSrgbMatrix(values)
        return Triple(
            shared.valid,
            shared.score,
            if (shared.valid) {
                "valid; neutralAxisDeviation=${String.format(Locale.US, "%.5f", shared.neutralAxisDeviation)}"
            } else {
                shared.reason
            }
        )
    }


    fun cfaName(cfa: Int): String = when (cfa) {
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB_NON_BAYER"
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO_NON_BAYER"
        else -> "UNKNOWN_OR_UNSUPPORTED"
    }
}
