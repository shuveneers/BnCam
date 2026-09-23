package com.bncam.core.quality

import android.graphics.ImageFormat
import android.util.Log
import android.hardware.camera2.CameraCharacteristics
import com.bncam.data.settings.ResolvedLensHardwareSettings
import com.bncam.data.settings.LensAwbCalibrationRuntimeRegistry
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
    val awbCalibrationSource: String = "UNAVAILABLE",
    val awbCalibrationFingerprint: String = "UNAVAILABLE",
    val awbCalibrationAuthority: Float = 0f,
    val awbGrGbRatio: Float? = null,
    val awbGreenEvenOddRatio: Float = 1f,
    val awbRuntimeSettingsReady: Boolean = false,
    val awbRequestedMode: String = "UNAVAILABLE",
    val awbRequestedPresetId: Int = -1,
    val awbRequestedPresetName: String = "UNAVAILABLE",
    val awbRequestedRgCoefficient: Float = 1f,
    val awbRequestedBgCoefficient: Float = 1f,
    val awbRequestedGreenSplitMode: String = "UNAVAILABLE",
    val awbRequestedManualGrGbRatio: Float = 1f,
    val awbRequestedFingerprint: String = "UNAVAILABLE",
    val awbExplicitDevelopedAuthority: Boolean = false,

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
        pairs.add("WB Gains [R,G_even,G_odd,B]" to effectiveWbGains.formatArray5())
        pairs.add("WB Applied" to effectiveWbApplied.toString())
        pairs.add("WB Applied To" to wbAppliedTo())
        pairs.add("AWB Calibration Owner" to "Lens ID hardware settings")
        pairs.add("AWB Calibration Source" to awbCalibrationSource)
        pairs.add("AWB Calibration Fingerprint" to awbCalibrationFingerprint)
        pairs.add("AWB Calibration Authority" to awbCalibrationAuthority.format6())
        pairs.add("AWB Calibration GR/GB Ratio" to (awbGrGbRatio?.format6() ?: "not supplied"))
        pairs.add("AWB Applied G_even/G_odd Ratio" to awbGreenEvenOddRatio.format6())
        pairs.add("AWB Runtime Settings Ready" to awbRuntimeSettingsReady.toString())
        pairs.add("AWB Requested Mode" to awbRequestedMode)
        pairs.add("AWB Requested Preset ID" to awbRequestedPresetId.toString())
        pairs.add("AWB Requested Preset" to awbRequestedPresetName)
        pairs.add("AWB Requested RG Coefficient" to awbRequestedRgCoefficient.format6())
        pairs.add("AWB Requested BG Coefficient" to awbRequestedBgCoefficient.format6())
        pairs.add("AWB Requested Green Split Mode" to awbRequestedGreenSplitMode)
        pairs.add("AWB Requested Manual GR/GB" to awbRequestedManualGrGbRatio.format6())
        pairs.add("AWB Requested Fingerprint" to awbRequestedFingerprint)
        pairs.add("AWB Explicit Developed Authority" to awbExplicitDevelopedAuthority.toString())
        pairs.add("AWB Profile-owned State" to "none")
        pairs.add("DNG Developed AWB Override" to "false")

        pairs.add("Normalization Calibration Valid" to normalizationCalibrationValid.toString())
        pairs.add("CFA Bayer Noise Model Supported" to cfaSupportedForBayerNoiseModel.toString())
        pairs.add("Physical Noise Runtime Owner" to "PhysicalNoiseState")
        pairs.add("Neural Denoise Effective Enabled" to spectraProcessingEnabled.toString())
        // Phase 9: FinalSensorCalibration still mirrors the frozen physical profile for transport
        // compatibility, but it is not a second source/resolver and must never be presented as one.
        pairs.add("Legacy Physical S/O Mirror Present (Telemetry Only)" to hasNoiseProfile.toString())
        pairs.add("Legacy Physical S/O Mirror Valid (Telemetry Only)" to noiseProfileValid.toString())
        pairs.add("Legacy Physical S/O Mirror Applied (Telemetry Only)" to effectiveNoiseProfileApplied.toString())
        pairs.add("Legacy Physical S/O Mirror Source (Telemetry Only)" to effectiveNoiseProfileSource)
        pairs.add("Legacy Physical S/O Mirror Fallback (Telemetry Only)" to effectiveNoiseProfileFallbackReason)
        pairs.add("Legacy Physical S/O Mirror Not Applied Reason (Telemetry Only)" to noiseProfileNotAppliedReason)
        pairs.add("Legacy Physical S/O Mirror Formula (Telemetry Only)" to effectiveNoiseProfileFormula)
        pairs.add("Legacy Physical S/O Mirror Pair Count (Telemetry Only)" to effectiveNoiseProfilePairCount.toString())
        pairs.add("Legacy Physical S/O Mirror Channel Count (Telemetry Only)" to effectiveNoiseProfileChannelCount.toString())
        pairs.add("Legacy Physical S/O Mirror CFA Mapping (Telemetry Only)" to effectiveNoiseProfileChannelMap)
        pairs.add("Legacy Physical S/O Mirror Values (Telemetry Only)" to (effectiveNoiseProfile?.formatDoubleArray() ?: "missing"))
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
        awbMode = "System",
        awbProfile = "RetiredLegacyTransport",
        awbRatio = 1f,
        awbTemp = 0f,
        awbIntensity = 0f,
        warnings = warnings
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
        liveWhiteBalanceKelvin: Int? = null,
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
            liveWhiteBalanceKelvin,
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
        liveWhiteBalanceKelvin: Int?,
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

        val manualNoiseAnchorIso = 100.0
        val manualNoiseGainRatio = 1.0
        val manualNoiseSingleAnchorScaled = false

        val cameraNoiseCanonical = base.baseNoiseProfile?.let { mosaicNoiseToCanonical(it, base.cfaPattern) }

        // FinalSensorCalibration carries OEM/Camera2 evidence only. Physical source selection
        // (OEM/System/Manual/Preset + Dynamic ISO) happens later and exactly once in PhysicalNoiseState.
        val spectraProcessingRequested = profileNoiseTuning?.spectraEnabled ?: false
        // Legacy FinalSensorCalibration is no longer a physical source selector. It carries only
        // Camera2/OEM evidence until withPhysicalNoiseAuthority() installs the frozen capture model.
        val effectiveNoiseMode = if (cameraNoiseCanonical?.isNotEmpty() == true) "Auto" else "Off"
        val spectraProcessingEnabled = spectraProcessingRequested
        val noiseValues = cameraNoiseCanonical
        if (override.manualNoiseValues != null) {
            warnings.add("Legacy LensOverride manual S/O ignored; PhysicalNoiseState is the sole physical noise authority")
        }

        val hasNoiseProfile = base.hasNoiseProfile
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
        val liveManualWhiteBalance = liveWhiteBalanceKelvin?.let { requestedKelvin ->
            val target = ManualWhiteBalanceTarget(
                kelvin = requestedKelvin,
                illuminantModel = if (requestedKelvin >= 4000) "CIE Daylight" else "Planckian Blackbody",
                tint = 0f
            )
            runCatching { RawColorTransformEngine.computeManualWhiteBalance(characteristics, target) }
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
        if (liveWhiteBalanceKelvin != null && liveManualWhiteBalance == null) {
            warnings.add(
                "Live manual WB ${liveWhiteBalanceKelvin}K rejected by the sensor color-calibration safety envelope; " +
                    "falling back to Lens ID/Camera2 automatic white balance for this capture."
            )
        }
        if (override.awbMode != "System") {
            warnings.add("Retired legacy Lens Hardware AWB override ignored; Lens ID AWB Calibration is the sole persistent AWB owner")
        }

        val systemAwbRequested = liveManualWhiteBalance == null
        val exactFrameCamera2Wb = base.baseWbAppliedByDefault &&
            base.baseWbSource.contains("CaptureResult.COLOR_CORRECTION_GAINS", ignoreCase = true)
        val exactFrameCamera2Ccm = base.baseColorMatrixApplied &&
            base.baseColorMatrixSource.contains("CaptureResult.COLOR_CORRECTION_TRANSFORM", ignoreCase = true)
        val exactFrameCamera2ColorPair = systemAwbRequested && exactFrameCamera2Wb && exactFrameCamera2Ccm

        // P0 AWB authority fix: Lens ID AWB calibration must constrain the exact-frame Camera2
        // prior directly. Previously it only participated in the optional physical-scene observer,
        // while this resolver normally selected base.baseWbGains first; that made extreme lens
        // calibration values a complete no-op in captured JPEGs.
        val lensAwbRuntime = if (systemAwbRequested && exactFrameCamera2Wb) {
            LensAwbCalibrationRuntimeRegistry.resolve(base.lensId)
        } else {
            null
        }
        val resolvedLensAwbCalibration = lensAwbRuntime?.let { runtime ->
            runCatching { AwbCalibrationEngine.resolve(runtime.settings, characteristics) }.getOrNull()
        }
        val calibratedExactFrameAwb = if (
            systemAwbRequested && exactFrameCamera2Wb && resolvedLensAwbCalibration?.valid == true && lensAwbRuntime != null
        ) {
            AwbCalibrationEngine.applyToCamera2Prior(
                camera2Gains = base.baseWbGains,
                calibration = resolvedLensAwbCalibration,
                settings = lensAwbRuntime.settings,
                cfaPattern = base.cfaPattern
            )
        } else {
            null
        }
        if (systemAwbRequested && exactFrameCamera2Wb && lensAwbRuntime?.settingsReady == true && calibratedExactFrameAwb == null) {
            warnings.add(
                "Lens ID AWB calibration could not be applied to exact-frame Camera2 WB; " +
                    "source=${resolvedLensAwbCalibration?.source ?: "unavailable"} " +
                    "reason=${resolvedLensAwbCalibration?.warning ?: "unavailable"}."
            )
        }
        val requestedAwbSettings = lensAwbRuntime?.settings?.sanitized()
        val explicitLensAwbAuthority = requestedAwbSettings?.let(
            AwbCalibrationEngine::hasExplicitDevelopedAuthority
        ) == true
        val requestedAwbPreset = requestedAwbSettings
            ?.takeIf { it.mode == com.bncam.data.settings.LensAwbCalibrationModes.BNCAM_PRESET }
            ?.let { com.bncam.data.settings.BnCamAwbPresetCatalog.byId(it.presetId) }

        // Lens Auto uses the single temporal owner's recent physical scene solution only when it
        // carries a coherent WB+CCM pair. Camera2 remains the exact-frame prior/fallback whenever
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

        if (systemAwbRequested && exactFrameCamera2Wb.xor(exactFrameCamera2Ccm)) {
            warnings.add(
                "Incomplete selected-frame Camera2 color pair: exactWb=$exactFrameCamera2Wb exactCcm=$exactFrameCamera2Ccm. " +
                    "Historical stable WB is not mixed with current-frame color metadata."
            )
        }

        val wb = when {
            liveManualWhiteBalance != null -> liveManualWhiteBalance.bayerWbGains.copyOf()
            explicitLensAwbAuthority && calibratedExactFrameAwb != null -> calibratedExactFrameAwb.gains.copyOf()
            stablePhysicalSystemAutoPair != null -> stablePhysicalSystemAutoPair.copyGains()
            calibratedExactFrameAwb != null -> calibratedExactFrameAwb.gains.copyOf()
            exactFrameCamera2Wb -> base.baseWbGains.copyOf()
            stableSystemAutoWbFallback != null -> stableSystemAutoWbFallback.copyGains()
            else -> base.baseWbGains.copyOf()
        }
        val wbSource = when {
            liveManualWhiteBalance != null ->
                "Live manual WB ${liveManualWhiteBalance.targetKelvin}K / ${liveManualWhiteBalance.illuminantModel} / Camera2 calibration matrices"
            explicitLensAwbAuthority && calibratedExactFrameAwb != null ->
                "Lens ID explicit AWB calibration ${calibratedExactFrameAwb.calibrationSource} " +
                    "authority=${String.format(Locale.US, "%.3f", calibratedExactFrameAwb.calibrationAuthority)} + Camera2 exact-frame CCM"
            stablePhysicalSystemAutoPair != null ->
                "BnCam Lens AWB physical scene confidence=${String.format(Locale.US, "%.3f", stablePhysicalSystemAutoPair.confidence)} " +
                    "dataAuthority=${String.format(Locale.US, "%.3f", stablePhysicalSystemAutoPair.dataAuthority)} " +
                    "mixedLight=${String.format(Locale.US, "%.3f", stablePhysicalSystemAutoPair.mixedLightScore)}"
            calibratedExactFrameAwb != null ->
                "Lens ID AWB calibration ${calibratedExactFrameAwb.calibrationSource} " +
                    "authority=${String.format(Locale.US, "%.3f", calibratedExactFrameAwb.calibrationAuthority)} + Camera2 exact-frame CCM"
            exactFrameCamera2ColorPair ->
                "CaptureResult exact-frame color pair: COLOR_CORRECTION_GAINS + COLOR_CORRECTION_TRANSFORM"
            exactFrameCamera2Wb ->
                "Exact-frame Camera2 WB gains with non-frame CCM fallback: ${base.baseColorMatrixSource}"
            stableSystemAutoWbFallback != null ->
                "BnCam stable Camera2 AWB bootstrap confidence=${String.format(Locale.US, "%.3f", stableSystemAutoWbFallback.confidence)} samples=${stableSystemAutoWbFallback.acceptedSampleCount}"
            else -> base.baseWbSource
        }

        val awbCalibrationSource = when {
            liveManualWhiteBalance != null -> "LIVE_MANUAL_KELVIN_TRANSIENT_OVERRIDE"
            explicitLensAwbAuthority && calibratedExactFrameAwb != null -> calibratedExactFrameAwb.calibrationSource
            stablePhysicalSystemAutoPair != null -> stablePhysicalSystemAutoPair.calibrationSource
            calibratedExactFrameAwb != null -> calibratedExactFrameAwb.calibrationSource
            exactFrameCamera2Wb -> "CAMERA2_EXACT_FRAME"
            stableSystemAutoWbFallback != null -> stableSystemAutoWbFallback.calibrationSource
            else -> "CAMERA2_BASE_METADATA"
        }
        val awbCalibrationFingerprint = when {
            liveManualWhiteBalance != null -> "transient:${liveManualWhiteBalance.targetKelvin}K:${liveManualWhiteBalance.illuminantModel}"
            explicitLensAwbAuthority && calibratedExactFrameAwb != null -> calibratedExactFrameAwb.calibrationFingerprint
            stablePhysicalSystemAutoPair != null -> stablePhysicalSystemAutoPair.calibrationFingerprint
            calibratedExactFrameAwb != null -> calibratedExactFrameAwb.calibrationFingerprint
            stableSystemAutoWbFallback != null -> stableSystemAutoWbFallback.calibrationFingerprint
            else -> "UNAVAILABLE"
        }
        val awbCalibrationAuthority = when {
            liveManualWhiteBalance != null -> 1f
            explicitLensAwbAuthority && calibratedExactFrameAwb != null -> calibratedExactFrameAwb.calibrationAuthority
            stablePhysicalSystemAutoPair != null -> stablePhysicalSystemAutoPair.calibrationAuthority
            calibratedExactFrameAwb != null -> calibratedExactFrameAwb.calibrationAuthority
            else -> 0f
        }
        val awbGrGbRatio = when {
            explicitLensAwbAuthority && calibratedExactFrameAwb != null -> calibratedExactFrameAwb.grGbRatio
            stablePhysicalSystemAutoPair != null -> stablePhysicalSystemAutoPair.grGbRatio
            else -> calibratedExactFrameAwb?.grGbRatio
        }
        val awbGreenEvenOddRatio = when {
            explicitLensAwbAuthority && calibratedExactFrameAwb != null -> calibratedExactFrameAwb.greenEvenOddRatio
            stablePhysicalSystemAutoPair != null -> stablePhysicalSystemAutoPair.greenEvenOddRatio
            calibratedExactFrameAwb != null -> calibratedExactFrameAwb.greenEvenOddRatio
            wb.size >= 3 && wb[1].isFinite() && wb[2].isFinite() && wb[1] > 1.0e-4f && wb[2] > 1.0e-4f ->
                (wb[1] / wb[2]).coerceIn(0.50f, 2.0f)
            else -> 1f
        }

        // WB gains and the post-demosaic sensor->linear-sRGB matrix form one colorimetric pair.
        // A temporary live Kelvin target gets a calibration-derived paired CCM; Lens Auto uses
        // the exact/temporal Camera2 pair. No profile-owned AWB state exists anymore.
        val liveManualColorMatrix = liveManualWhiteBalance?.mPostCompensated?.copyOf()?.takeIf {
            RawColorTransformEngine.validateSensorToLinearSrgbMatrix(it).valid
        }
        val colorMatrix = when {
            manualColorOverrideActive -> override.manualColorMatrix
            liveManualWhiteBalance != null && liveManualColorMatrix != null -> liveManualColorMatrix
            explicitLensAwbAuthority && calibratedExactFrameAwb != null -> base.baseColorMatrix
            stablePhysicalSystemAutoPair != null -> stablePhysicalSystemAutoPair.copyColorMatrix()
            else -> base.baseColorMatrix
        }
        val colorSource = when {
            manualColorOverrideActive -> "Lens ID Manual color matrix override"
            liveManualWhiteBalance != null && liveManualColorMatrix != null ->
                "Live manual WB ${liveManualWhiteBalance.targetKelvin}K paired post-WB sensor->linear-sRGB matrix"
            explicitLensAwbAuthority && calibratedExactFrameAwb != null && exactFrameCamera2Ccm ->
                "Camera2 exact-frame CCM + explicit Lens ID calibrated exact-frame WB gains"
            stablePhysicalSystemAutoPair != null ->
                "BnCam Lens AWB temporally paired Camera2 CCM"
            calibratedExactFrameAwb != null && exactFrameCamera2Ccm ->
                "Camera2 exact-frame CCM + Lens ID calibrated exact-frame WB gains"
            exactFrameCamera2ColorPair ->
                "CaptureResult exact-frame color pair: COLOR_CORRECTION_TRANSFORM + COLOR_CORRECTION_GAINS"
            else -> base.baseColorMatrixSource
        }
        val colorApplied = when {
            manualColorOverrideActive -> true
            liveManualWhiteBalance != null && liveManualColorMatrix != null -> true
            stablePhysicalSystemAutoPair != null -> colorMatrix != null
            else -> colorMatrix != null && !base.baseColorMatrixIdentityFallbackUsed
        }
        val identityFallback = !manualColorOverrideActive &&
            !(liveManualWhiteBalance != null && liveManualColorMatrix != null) &&
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
            "Noise Model Applied To" to if (noiseValues != null) "RAW_DOMAIN_NATIVE_ISP_PHYSICAL_SO" else "NOT_APPLIED_MISSING_OR_INVALID",
            "Lens AWB Calibration Applied To" to "DEVELOPED_RAW_PRE_DEMOSAIC_GREEN_SPLIT_AND_POST_DEMOSAIC_RB_CCM",
            "DNG Developed AWB/CCM Overridden" to "false",
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
            spectraMode = if (spectraProcessingEnabled) "On" else "Off",
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
            effectiveNoiseProfileSource = if (effectiveNoiseMode == "Off") "Off" else base.baseNoiseProfileSource,
            effectiveNoiseProfileFallbackReason = base.baseNoiseProfileFallbackReason,
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
            awbCalibrationSource = awbCalibrationSource,
            awbCalibrationFingerprint = awbCalibrationFingerprint,
            awbCalibrationAuthority = awbCalibrationAuthority,
            awbGrGbRatio = awbGrGbRatio,
            awbGreenEvenOddRatio = awbGreenEvenOddRatio,
            awbRuntimeSettingsReady = lensAwbRuntime?.settingsReady == true,
            awbRequestedMode = requestedAwbSettings?.mode ?: "UNAVAILABLE",
            awbRequestedPresetId = requestedAwbSettings?.presetId ?: -1,
            awbRequestedPresetName = requestedAwbPreset?.name ?: "UNAVAILABLE",
            awbRequestedRgCoefficient = requestedAwbSettings?.rgCoefficient ?: 1f,
            awbRequestedBgCoefficient = requestedAwbSettings?.bgCoefficient ?: 1f,
            awbRequestedGreenSplitMode = requestedAwbSettings?.greenSplitMode ?: "UNAVAILABLE",
            awbRequestedManualGrGbRatio = requestedAwbSettings?.manualGrGbRatio ?: 1f,
            awbRequestedFingerprint = requestedAwbSettings?.fingerprint() ?: "UNAVAILABLE",
            awbExplicitDevelopedAuthority = explicitLensAwbAuthority,
            effectiveColorMatrix = colorMatrix,
            effectiveColorMatrixSource = colorSource,
            effectiveColorMatrixApplied = colorApplied,
            effectiveColorMatrixIdentityFallbackUsed = identityFallback,
            effectiveColorMatrixRejectReason = when {
                override.manualColorMatrix != null -> "none"
                liveManualWhiteBalance != null && liveManualColorMatrix != null -> "none"
                liveManualWhiteBalance != null -> "live_manual_color_matrix_invalid_fallback_to_base"
                else -> base.baseColorMatrixRejectReason
            },
            effectiveColorMatrixNote = when {
                override.manualColorMatrix != null -> "Manual valid 3x3 matrix applied"
                liveManualWhiteBalance != null && liveManualColorMatrix != null ->
                    "Live Kelvin uses coherent Camera2 calibration-derived WB + post-WB sensor-to-linear-sRGB matrix"
                liveManualWhiteBalance != null ->
                    "Live Kelvin color matrix unavailable; base sensor matrix fallback retained"
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

        // Comparative exact-frame trust uses only standards-complete static candidates from
        // this same immutable SensorMetadata snapshot. No camera id or lens role participates.
        val staticColorReferences = candidates
            .filter { it.source.contains("SENSOR_FORWARD_MATRIX") }
            .mapNotNull { it.values }
            .filter { RawColorTransformEngine.validateSensorToLinearSrgbMatrix(it).valid }
        val exactFrameWbForTrust = sensorMetadata.colorCorrectionGainsField.value
            ?.takeIf { sensorMetadata.colorCorrectionGainsField.isValid && it.size >= 4 }
            ?.take(4)
            ?.toFloatArray()

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

            val validation = if (candidate.source.contains("COLOR_CORRECTION_TRANSFORM")) {
                val direct = RawColorTransformEngine.validateExactFrameCamera2ColorTransform(values)
                val trust = if (direct.valid) {
                    RawColorTransformEngine.evaluateExactFrameAgainstStaticCalibration(
                        exactFrame = values,
                        wbRggb = exactFrameWbForTrust,
                        staticCandidates = staticColorReferences
                    )
                } else {
                    null
                }
                val accepted = direct.valid && (trust?.trusted != false)
                Triple(
                    accepted,
                    direct.score,
                    if (accepted) {
                        "valid; ${direct.reason}; comparativeTrust=${trust?.reason ?: "not_run"}; " +
                            "neutralAxisDeviation=" +
                            String.format(Locale.US, "%.5f", direct.neutralAxisDeviation) +
                            "; opponentGain=" +
                            String.format(Locale.US, "%.4f", trust?.exactOpponentGain ?: Float.NaN) +
                            "; staticOpponentGain=" +
                            String.format(Locale.US, "%.4f", trust?.referenceOpponentGain ?: Float.NaN) +
                            "; amplificationRatio=" +
                            String.format(Locale.US, "%.4f", trust?.amplificationRatio ?: Float.NaN) +
                            "; matrixShapeDistance=" +
                            String.format(Locale.US, "%.5f", trust?.matrixShapeDistance ?: Float.NaN) +
                            "; staticSpread=" +
                            String.format(Locale.US, "%.5f", trust?.staticCalibrationSpread ?: Float.NaN)
                    } else {
                        trust?.let {
                            "${it.reason}; opponentGain=" +
                                String.format(Locale.US, "%.4f", it.exactOpponentGain) +
                                "; staticOpponentGain=" +
                                String.format(Locale.US, "%.4f", it.referenceOpponentGain) +
                                "; amplificationRatio=" +
                                String.format(Locale.US, "%.4f", it.amplificationRatio) +
                                "; matrixShapeDistance=" +
                                String.format(Locale.US, "%.5f", it.matrixShapeDistance) +
                                "; staticSpread=" +
                                String.format(Locale.US, "%.5f", it.staticCalibrationSpread)
                        } ?: direct.reason
                    }
                )
            } else {
                validateColorMatrix(values)
            }
            if (validation.first) {
                val legacyCounterfactual = legacyNeutralNormalizedMatrix(values)
                val priorRejectionContext = rejected
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(separator = " || ", prefix = "; priorRejectedCandidates=")
                    .orEmpty()
                return MatrixResolution(
                    values = values.copyOf(),
                    source = candidate.source,
                    applied = true,
                    identityFallbackUsed = false,
                    rejectReason = "none",
                    note = "Phase8 validated matrix accepted without row normalization; ${candidate.note}; " +
                            "validationScore=${validation.second.format5()}$priorRejectionContext",
                    preNormalizationValues = values.copyOf(),
                    neutralNormalizationApplied = false,
                    legacyNeutralNormalizedValues = legacyCounterfactual
                )
            }
            if (candidate.source.contains("COLOR_CORRECTION_TRANSFORM")) {
                Log.i(
                    "SensorCalibration",
                    "ADAPTIVE_RAW_COLOR_AUTHORITY rejected exact-frame Camera2 CCM; " +
                        "reason=${validation.third}; trying same-sensor standards-complete static calibration"
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
