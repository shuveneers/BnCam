package com.bncam.core.quality

import android.graphics.ImageFormat
import android.util.Log
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
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

    // Phase 1 color-truth instrumentation. The processing path continues to use
    // baseColorMatrix exactly as before; these fields only preserve what metadata supplied
    // before BnCam's existing neutral row normalization changed it.
    val baseColorMatrixPreNormalization: FloatArray? = null,
    val baseColorMatrixNeutralNormalizationApplied: Boolean = false
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
    val noiseSnapshot: NoiseModelSnapshotV3? = null
) {
    fun debugPairs(): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        pairs.add("Sensor Calibration Summary" to "V2 central metadata baseline")
        pairs.add("Base Layer" to "CameraCharacteristics + CaptureResult")
        pairs.add("Adjustment Layer" to adjustmentLayerSummary())
        pairs.add("Final Layer" to "Base merged with validated per-lens adjustment values")
        pairs.add("Capture Mode / Frame Source" to base.frameSource)
        pairs.add("Input Raw Domain" to rawInputDomain.name)
        pairs.add("ISP Working Domain" to ispWorkingDomain.name)
        pairs.add("Lens ID" to base.lensId)
        pairs.add("Physical Camera ID" to (base.physicalCameraId ?: "not reported"))
        pairs.add("Calibration Profile ID" to base.calibrationProfileId)
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
        pairs.add("Color Matrix Metadata Pre-Normalization Values" to
                (base.baseColorMatrixPreNormalization?.formatArray5() ?: "missing"))
        pairs.add("Color Matrix Neutral Row Normalization Applied" to
                base.baseColorMatrixNeutralNormalizationApplied.toString())
        pairs.add("Color Matrix Pre-Normalization Row Sums" to
                (base.baseColorMatrixPreNormalization?.matrixRowSums()?.formatArray5() ?: "missing"))
        pairs.add("Color Matrix Effective Row Sums" to
                (effectiveColorMatrix?.matrixRowSums()?.formatArray5() ?: "missing"))
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

/**
 * Applies capture-integrated SPECTRA temporal observation and fusion variance to the
 * immutable shutter-time calibration used by the JPEG ISP. The RAW16/DNG payload is
 * never changed here; only the post-fusion noise expectation is updated.
 */
fun FinalSensorCalibration.withSpectraMergeStats(stats: String): FinalSensorCalibration {
    val snapshot = noiseSnapshot ?: return this
    if (!snapshot.isSpectraActive() || stats.isBlank()) return this

    val values = stats.split(';')
        .mapNotNull { entry ->
            val split = entry.indexOf('=')
            if (split <= 0) null else entry.substring(0, split) to entry.substring(split + 1)
        }
        .toMap()
    if (!values["spectraEnabled"].equals("true", ignoreCase = true)) return this

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
        val compatibilityKey = "spectraScale$channel"
        val specificKey = if (slope) "spectraSScale$channel" else "spectraOScale$channel"
        val compatibilityScale = value(compatibilityKey, 1.0).coerceIn(0.75, 1.25)
        val hasIndependentFit = values.containsKey(specificKey)
        val rawScale = value(specificKey, compatibilityScale).coerceIn(0.75, 1.25)
        val fitConfidence = value("spectraFitConfidence$channel", 0.0).coerceIn(0.0, 1.0)
        val fitPhysicalScore = value("spectraFitPhysicalScore$channel", fitConfidence).coerceIn(0.0, 1.0)
        // Older native telemetry exposed one common scale and already incorporated its
        // confidence before serialization. Preserve that contract. New telemetry fits
        // S and O independently and therefore earns authority from regression quality.
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
        sourceFlags = snapshot.sourceFlags or SPECTRA_FUSION_SOURCE_FLAG
    )
    val effectiveFrames = value("spectraEffectiveFrameCount", 1.0).coerceAtLeast(1.0)
    val meanFitConfidence = (0 until 4)
        .map { channel -> value("spectraFitConfidence$channel", 0.0).coerceIn(0.0, 1.0) }
        .average()
    val staticP50 = value("spectraStaticProbabilityP50", 0.0).coerceIn(0.0, 1.0)
    val forwardBackward = value("spectraForwardBackwardConsistency", 0.0).coerceIn(0.0, 1.0)
    val persistentPattern = value("spectraPersistentPatternFraction", 0.0).coerceIn(0.0, 1.0)
    val fitStability = value("spectraFitStabilityConfidence", 0.0).coerceIn(0.0, 1.0)
    val warning = "SPECTRA capture integration: observerSamples=$observerSamples, " +
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
        effectiveNoiseProfileSource = "$effectiveNoiseProfileSource + SPECTRA_CAPTURE_FUSION",
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
        captureResult: CaptureResult?,
        lensSettings: ResolvedLensHardwareSettings?,
        profileAwbSettings: ProfileAwbSettings? = null,
        profileNoiseTuning: ProfileNoiseTuning? = null,
        stableAutoWhiteBalance: StableWhiteBalanceSnapshot? = null
    ): FinalSensorCalibration {
        val resolvedPhysicalCameraId = physicalCameraId ?: resolvePhysicalCameraId(captureResult)
        val base = buildBaseCalibration(lensId, resolvedPhysicalCameraId, frameSourceFormat, characteristics, captureResult)
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
     * Produces the denoise model for an averaged/fused master from each selected frame's exact
     * calibration. Independent-frame mean variance is sum(variance_i) / n², so the resulting
     * S/O coefficients are summed and divided by n² rather than copied from the anchor.
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
        val fusedS = DoubleArray(4) { channel -> combined.getOrNull(channel * 2) ?: 0.0 }
        val fusedO = DoubleArray(4) { channel -> combined.getOrNull(channel * 2 + 1) ?: 0.0 }
        val anchorSnapshot = anchor.noiseSnapshot
        val fusedSnapshot = anchorSnapshot?.copy(
            effectiveS = fusedS,
            effectiveO = fusedO,
            sourceFlags = anchorSnapshot.sourceFlags or 0x1L
        )

        return anchor.copy(
            effectiveNoiseProfile = combined,
            effectiveNoiseProfileSource = "Fused per-frame exact Camera2/manual S/O ($frameCount frames, $fallbackCount explicit zero fallbacks)",
            effectiveNoiseProfileFallbackReason = if (fallbackCount == 0) "None" else "$fallbackCount selected frame(s) had unavailable or invalid S/O",
            effectiveNoiseProfileApplied = true,
            effectiveNoiseProfilePairCount = coefficientCount / 2,
            effectiveNoiseProfileChannelCount = coefficientCount / 2,
            noiseSnapshot = fusedSnapshot,
            pipelineWarnings = anchor.pipelineWarnings + (
                "Fused denoise S/O derived from $frameCount selected frames using per-frame radiometric weights w_i and exact black/white normalization; anchor metadata was not reused for support frames" +
                    if (fallbackCount > 0) "; $fallbackCount frame(s) had no S/O and used explicit zero fallback" else ""
                )
        ).also(LensCalibrationTelemetry::record)
    }

    private fun resolvePhysicalCameraId(captureResult: CaptureResult?): String? {
        val physicalResults = (captureResult as? TotalCaptureResult)?.physicalCameraResults ?: return null
        return physicalResults.keys.sorted().firstOrNull()
    }

    private fun buildBaseCalibration(
        lensId: String,
        physicalCameraId: String?,
        frameSourceFormat: Int,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult?
    ): BaseSensorCalibration {
        val warnings = mutableListOf<String>()
        val (domain, label, bitDepth) = when (frameSourceFormat) {
            ImageFormat.RAW10 -> Triple(RawDomain.RAW10_PACKED_10BIT, "RAW10", 10)
            ImageFormat.RAW_SENSOR -> Triple(RawDomain.RAW_SENSOR_16BIT, "RAW_SENSOR", 16)
            ImageFormat.YUV_420_888 -> Triple(RawDomain.UNKNOWN, "YUV", 8)
            else -> Triple(RawDomain.UNKNOWN, "UNKNOWN($frameSourceFormat)", 8)
        }
        val appliedDomain = when (domain) {
            RawDomain.RAW10_PACKED_10BIT -> "RAW10"
            RawDomain.RAW_SENSOR_16BIT -> "RAW16_OR_RAW_SENSOR"
            RawDomain.MASTER_RAW16_NORMALIZED -> "MASTER_RAW16_NORMALIZED"
            RawDomain.UNKNOWN -> "UNKNOWN_OR_YUV"
        }

        val cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1
        val sensorTimestamp = captureResult?.get(CaptureResult.SENSOR_TIMESTAMP)
        val sensorSensitivityIso = captureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val sensorExposureTimeNs = captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val postRawSensitivityBoost = try {
            captureResult?.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
        } catch (_: Throwable) {
            100
        }

        val staticWhite = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
        val dynamicWhite = captureResult?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
        val metadataWhite = dynamicWhite ?: staticWhite
        val fallbackWhite = if (domain == RawDomain.RAW10_PACKED_10BIT) 1023 else if (domain == RawDomain.RAW_SENSOR_16BIT) 65535 else 255
        val rawWhite = metadataWhite ?: fallbackWhite.also {
            warnings.add("Missing SENSOR_INFO_WHITE_LEVEL/SENSOR_DYNAMIC_WHITE_LEVEL; using format fallback white=$it")
        }
        val whiteScale = if (domain == RawDomain.RAW10_PACKED_10BIT && rawWhite > 1023) 1023f / rawWhite.toFloat() else 1f
        val appliedWhite = if (domain == RawDomain.RAW10_PACKED_10BIT && rawWhite > 1023) 1023 else rawWhite.coerceAtLeast(1)

        val staticBlackPattern = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val dynamicBlackPattern = captureResult?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        // Camera2 dynamic black levels (SENSOR_DYNAMIC_BLACK_LEVEL) are in 2x2 mosaic tile order
        // [row0_col0, row0_col1, row1_col0, row1_col1], matching SENSOR_BLACK_LEVEL_PATTERN.
        val rawBlackValues = when {
            dynamicBlackPattern != null && dynamicBlackPattern.size >= 4 -> FloatArray(4) {
                dynamicBlackPattern[it].finiteOrNull() ?: 0f
            }
            staticBlackPattern != null -> floatArrayOf(
                staticBlackPattern.getOffsetForIndex(0, 0).toFloat(),
                staticBlackPattern.getOffsetForIndex(1, 0).toFloat(),
                staticBlackPattern.getOffsetForIndex(0, 1).toFloat(),
                staticBlackPattern.getOffsetForIndex(1, 1).toFloat()
            )
            else -> {
                warnings.add("Missing SENSOR_DYNAMIC_BLACK_LEVEL and SENSOR_BLACK_LEVEL_PATTERN; black subtraction uses no-op 0 fallback")
                floatArrayOf(0f, 0f, 0f, 0f)
            }
        }
        // Native ISP indexes black levels by canonical R/Gr/Gb/B color plane.
        val appliedBlack = mosaicToCanonical(rawBlackValues, cfa).map { value ->
            (value * whiteScale).coerceIn(0f, appliedWhite.coerceAtLeast(2) - 1f)
        }.toFloatArray()
        if (domain == RawDomain.RAW10_PACKED_10BIT && rawWhite > 1023) {
            warnings.add("White/black metadata scaled from metadata domain white=$rawWhite to RAW10 domain white=1023 with scale=${whiteScale.format6()}")
        }
        if (dynamicBlackPattern == null && staticBlackPattern != null) {
            warnings.add("Dynamic black unavailable; static SENSOR_BLACK_LEVEL_PATTERN used")
        }

        val cfaSupportedForBayer = cfa in listOf(
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR
        )
        val normalizationCalibrationValid = metadataWhite != null && (dynamicBlackPattern != null || staticBlackPattern != null)

        val noise = resolveSensorNoiseProfile(captureResult, warnings)
        val wb = resolveWhiteBalanceGains(captureResult, warnings)
        val colorMatrix = resolveColorCorrectionMatrix(characteristics, captureResult, warnings)

        val hasNoiseProfile = noise.values != null
        val noiseProfileValid = noise.values != null && noise.values.all { it.isFinite() && it >= 0.0 }
        val baseNoiseApplied = hasNoiseProfile && noiseProfileValid && normalizationCalibrationValid && cfaSupportedForBayer

        return BaseSensorCalibration(
            lensId = lensId,
            physicalCameraId = physicalCameraId,
            calibrationProfileId = if (!physicalCameraId.isNullOrBlank() && physicalCameraId != lensId) {
                "$lensId/$physicalCameraId"
            } else {
                lensId
            },
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
            baseWhiteLevelSource = when {
                dynamicWhite != null -> "CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL"
                staticWhite != null -> "CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL"
                else -> "missing/fallback"
            },
            baseWhiteLevelRawMetadata = metadataWhite,
            baseWhiteLevelAppliedDomain = appliedDomain,
            baseWhiteLevelScaleFactor = whiteScale,
            dynamicWhiteLevelAvailable = dynamicWhite != null,
            baseBlackLevels = appliedBlack,
            baseBlackLevelSource = when {
                dynamicBlackPattern != null -> "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL"
                staticBlackPattern != null -> "CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN"
                else -> "missing/fallback_noop_0"
            },
            baseBlackLevelRawMetadataValues = rawBlackValues,
            baseBlackLevelAppliedDomain = appliedDomain,
            baseBlackLevelScaleFactor = whiteScale,
            dynamicBlackLevelAvailable = dynamicBlackPattern != null,
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
            baseColorMatrixNeutralNormalizationApplied = colorMatrix.neutralNormalizationApplied
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

        val stableSystemAutoWb = stableAutoWhiteBalance?.takeIf { snapshot ->
            snapshot.confidence >= 0.55f && snapshot.gains.size >= 4 &&
                snapshot.gains.take(4).all { gain -> gain.isFinite() && gain in 0.25f..6.0f }
        }

        if (safeProfileAwb != null && safeProfileAwb.mode != ProfileAwbModes.SYSTEM_AUTO && sensorAwareProfileAwb == null) {
            warnings.add(
                "Profile manual WB rejected by safe sensor-gain envelope; falling back to capture-result Camera2 AWB. " +
                    "The stored profile is preserved and cannot brick RAW preview/startup."
            )
        }

        val stableSystemAutoWbAllowed = stableSystemAutoWb?.takeIf {
            when {
                safeProfileAwb?.mode == ProfileAwbModes.SYSTEM_AUTO -> true
                safeProfileAwb == null && override.awbMode == "System" -> true
                else -> false
            }
        }
        val wb = when {
            sensorAwareProfileAwb != null -> sensorAwareProfileAwb.bayerWbGains.copyOf()
            stableSystemAutoWbAllowed != null -> stableSystemAutoWbAllowed.copyGains()
            safeProfileAwb?.mode == ProfileAwbModes.SYSTEM_AUTO -> base.baseWbGains
            safeProfileAwb != null -> base.baseWbGains
            override.awbMode != "System" -> {
                warnings.add("Legacy Lens ID AWB override applied because no profile AWB snapshot was supplied")
                applyAwbOverride(base.baseWbGains, override)
            }
            else -> base.baseWbGains
        }
        val wbSource = when {
            sensorAwareProfileAwb != null ->
                "Sensor-aware profile WB ${sensorAwareProfileAwb.targetKelvin}K / ${sensorAwareProfileAwb.illuminantModel} / Camera2 calibration matrices"
            stableSystemAutoWbAllowed != null ->
                "BnCam stable CaptureResult AWB confidence=${String.format(Locale.US, "%.3f", stableSystemAutoWbAllowed.confidence)} samples=${stableSystemAutoWbAllowed.acceptedSampleCount}"
            safeProfileAwb?.mode == ProfileAwbModes.SYSTEM_AUTO -> base.baseWbSource
            safeProfileAwb != null -> "Profile WB fallback -> ${base.baseWbSource}"
            override.awbMode != "System" -> "Legacy Lens ID AWB override profile=${override.awbProfile} over ${base.baseWbSource}"
            else -> base.baseWbSource
        }

        // White balance and sensor colour conversion are deliberately kept as two separate
        // operations. The per-frame Camera2 colour transform is already calibrated for this
        // physical sensor and has proven stable on the active HAL. Replacing that entire matrix
        // merely because the user selected a Kelvin target can turn a valid WB adjustment into a
        // large green/magenta rotation (and has caused unstable RAW-preview configurations on
        // some devices). Manual/profile WB therefore changes the sensor-domain WB diagonal only;
        // the proven per-frame colour matrix remains authoritative unless the user explicitly
        // supplied a Lens-ID manual matrix.
        val colorMatrix = when {
            manualColorOverrideActive -> override.manualColorMatrix
            else -> base.baseColorMatrix
        }
        val colorSource = when {
            manualColorOverrideActive -> "Lens ID Manual color matrix override"
            sensorAwareProfileAwb != null -> "${base.baseColorMatrixSource} + sensor-aware profile WB gains (${sensorAwareProfileAwb.targetKelvin}K)"
            else -> base.baseColorMatrixSource
        }
        val colorApplied = when {
            manualColorOverrideActive -> true
            else -> colorMatrix != null && !base.baseColorMatrixIdentityFallbackUsed
        }
        val identityFallback = !manualColorOverrideActive && base.baseColorMatrixIdentityFallbackUsed

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
                sensorAwareProfileAwb != null -> "none"
                else -> base.baseColorMatrixRejectReason
            },
            effectiveColorMatrixNote = when {
                override.manualColorMatrix != null -> "Manual valid 3x3 matrix applied"
                sensorAwareProfileAwb != null -> "Stable per-frame sensor color matrix retained; profile Kelvin changes WB gains only"
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

    private fun resolveSensorNoiseProfile(captureResult: CaptureResult?, warnings: MutableList<String>): NoiseResolution {
        val channelMap = "Android SENSOR_NOISE_PROFILE coefficient pairs flattened as S0,O0,S1,O1...; channel order follows Android CFA planes for the capture result"
        val profile = try {
            captureResult?.get(CaptureResult.SENSOR_NOISE_PROFILE)
        } catch (t: Throwable) {
            warnings.add("Reading SENSOR_NOISE_PROFILE failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (profile == null || profile.isEmpty()) {
            warnings.add("Missing SENSOR_NOISE_PROFILE; sensor noise baseline will use conservative no-op fallback")
            return NoiseResolution(null, "missing/null", "CaptureResult.SENSOR_NOISE_PROFILE unavailable", 0, 0, channelMap)
        }

        val pairCount = profile.size
        val out = DoubleArray(pairCount * 2)
        var valid = true
        for (i in profile.indices) {
            val pair = profile[i]
            val signal = pair.first
            val offset = pair.second
            if (signal == null || offset == null || !signal.isFinite() || !offset.isFinite()) {
                valid = false
                break
            }
            out[i * 2] = signal
            out[i * 2 + 1] = offset
        }
        if (!valid || out.any { !it.isFinite() || it < 0.0 } || out.all { abs(it) < 1.0e-12 }) {
            warnings.add("Invalid SENSOR_NOISE_PROFILE; received $pairCount pairs but values were non-finite or all zero")
            return NoiseResolution(null, "invalid/non-finite-or-all-zero", "Camera2 S/O was negative, non-finite, or all zero", pairCount, pairCount, channelMap)
        }
        return NoiseResolution(out, "CaptureResult.SENSOR_NOISE_PROFILE", "None", pairCount, pairCount, channelMap)
    }

    private data class WbResolution(val values: FloatArray, val source: String, val applied: Boolean)

    private fun resolveWhiteBalanceGains(captureResult: CaptureResult?, warnings: MutableList<String>): WbResolution {
        val gains = try {
            captureResult?.get(CaptureResult.COLOR_CORRECTION_GAINS)
        } catch (t: Throwable) {
            warnings.add("Reading COLOR_CORRECTION_GAINS failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (gains != null) {
            val out = floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
            if (out.all { it.isFinite() && it > 0f }) {
                return WbResolution(out, "CaptureResult.COLOR_CORRECTION_GAINS", true)
            }
            warnings.add("Invalid COLOR_CORRECTION_GAINS; neutral fallback used")
        } else {
            warnings.add("AWB gains unavailable; neutral fallback used")
        }
        return WbResolution(floatArrayOf(1f, 1f, 1f, 1f), "fallback/neutral_1_1_1_1", false)
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
        val neutralNormalizationApplied: Boolean
    )

    private data class MatrixCandidate(
        val source: String,
        val values: FloatArray?,
        val note: String,
        val declaredInputSpace: String,
        val declaredOutputSpace: String,
        val xyzToSrgbApplied: Boolean,
        val chromaticAdaptationApplied: Boolean,
        val sourcePriority: Int
    )

    private fun resolveColorCorrectionMatrix(
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult?,
        warnings: MutableList<String>
    ): MatrixResolution {
        val rejected = mutableListOf<String>()
        val candidates = mutableListOf<MatrixCandidate>()

        // 1. CaptureResult.COLOR_CORRECTION_TRANSFORM (Direct sensor RGB to linear sRGB, no XYZ->sRGB)
        candidates.add(MatrixCandidate(
            source = "CaptureResult.COLOR_CORRECTION_TRANSFORM -> linear_sRGB",
            values = captureResult?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { colorSpaceTransformToArray(it) },
            note = "capture_result_sensor_rgb_to_linear_srgb_direct",
            declaredInputSpace = "sensor_RGB",
            declaredOutputSpace = "linear_sRGB",
            xyzToSrgbApplied = false,
            chromaticAdaptationApplied = false,
            sourcePriority = 1
        ))

        // 2. CameraCharacteristics.SENSOR_FORWARD_MATRIX2 (Reference sensor to XYZ D50, requires XYZ->sRGB)
        candidates.add(MatrixCandidate(
            source = "CameraCharacteristics.SENSOR_FORWARD_MATRIX2 -> XYZ_D50 -> Bradford_D65 -> linear_sRGB",
            values = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let {
                multiply3x3(RawColorTransformEngine.xyzD50ToLinearSrgbMatrix(), colorSpaceTransformToArray(it))
            },
            note = "forward_matrix2_xyz_d50_bradford_d65_to_srgb",
            declaredInputSpace = "reference_sensor_RGB",
            declaredOutputSpace = "CIE_XYZ_D50",
            xyzToSrgbApplied = true,
            chromaticAdaptationApplied = true,
            sourcePriority = 2
        ))

        // 3. CameraCharacteristics.SENSOR_FORWARD_MATRIX1
        candidates.add(MatrixCandidate(
            source = "CameraCharacteristics.SENSOR_FORWARD_MATRIX1 -> XYZ_D50 -> Bradford_D65 -> linear_sRGB",
            values = characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let {
                multiply3x3(RawColorTransformEngine.xyzD50ToLinearSrgbMatrix(), colorSpaceTransformToArray(it))
            },
            note = "forward_matrix1_xyz_d50_bradford_d65_to_srgb",
            declaredInputSpace = "reference_sensor_RGB",
            declaredOutputSpace = "CIE_XYZ_D50",
            xyzToSrgbApplied = true,
            chromaticAdaptationApplied = true,
            sourcePriority = 3
        ))

        // 4. inverse(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)
        candidates.add(MatrixCandidate(
            source = "inverse(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) -> XYZ -> linear_sRGB",
            values = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.let { invert3x3(colorSpaceTransformToArray(it)) }?.let { multiply3x3(xyzToSrgbD65Matrix(), it) },
            note = "inverse_color_transform2_xyz_to_srgb",
            declaredInputSpace = "reference_sensor_RGB",
            declaredOutputSpace = "CIE_XYZ",
            xyzToSrgbApplied = true,
            chromaticAdaptationApplied = false,
            sourcePriority = 4
        ))

        // 5. inverse(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)
        candidates.add(MatrixCandidate(
            source = "inverse(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) -> XYZ -> linear_sRGB",
            values = characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.let { invert3x3(colorSpaceTransformToArray(it)) }?.let { multiply3x3(xyzToSrgbD65Matrix(), it) },
            note = "inverse_color_transform1_xyz_to_srgb",
            declaredInputSpace = "reference_sensor_RGB",
            declaredOutputSpace = "CIE_XYZ",
            xyzToSrgbApplied = true,
            chromaticAdaptationApplied = false,
            sourcePriority = 5
        ))

        for (candidate in candidates) {
            val values = candidate.values
            if (values == null) continue

            // --- DETAILED VALIDATION METRICS ---
            val rowSums = FloatArray(3) { row -> values[row * 3] + values[row * 3 + 1] + values[row * 3 + 2] }
            val det = values[0] * (values[4] * values[8] - values[5] * values[7]) -
                      values[1] * (values[3] * values[8] - values[5] * values[6]) +
                      values[2] * (values[3] * values[7] - values[4] * values[6])
            val neutralGray = FloatArray(3) { row -> rowSums[row] * 0.5f }
            val expectedOne = FloatArray(3) { row -> rowSums[row] }
            val clippingRisk = rowSums.maxOrNull() ?: 1.0f

            // Log detailed candidate parameters
            Log.i("SensorCalibration", "Auditing matrix candidate [${candidate.source}]: " +
                    "inputSpace=${candidate.declaredInputSpace}, outputSpace=${candidate.declaredOutputSpace}, " +
                    "xyzToSrgbApplied=${candidate.xyzToSrgbApplied}, chromaticAdaptationApplied=${candidate.chromaticAdaptationApplied}, " +
                    "priority=${candidate.sourcePriority}, determinant=${String.format(Locale.US, "%.4f", det)}, " +
                    "rowSums=[${rowSums.joinToString(", ")}], neutralGray=[${neutralGray.joinToString(", ")}], " +
                    "expectedOne=[${expectedOne.joinToString(", ")}], clippingRisk=${String.format(Locale.US, "%.2f", clippingRisk)}")

            // --- AUDITOR WARNING CHECKS ---
            if (candidate.source.contains("COLOR_CORRECTION_TRANSFORM") && candidate.xyzToSrgbApplied) {
                warnings.add("ERROR: CaptureResult.COLOR_CORRECTION_TRANSFORM must not be multiplied by XYZ->sRGB matrix")
            }
            if (candidate.source.contains("FORWARD") && !candidate.xyzToSrgbApplied) {
                warnings.add("ERROR: Forward matrix in XYZ space applied directly as sRGB matrix")
            }
            if (candidate.source.contains("COLOR_CORRECTION_TRANSFORM") && candidate.declaredOutputSpace.contains("XYZ")) {
                warnings.add("ERROR: sRGB matrix processed with XYZ->sRGB transformation")
            }
            if (!candidate.chromaticAdaptationApplied && candidate.declaredOutputSpace.contains("D50")) {
                warnings.add("DEBUG_WARNING: D50->D65 chromatic adaptation ignored; using D65 approximation for forward matrix")
            }

            val normalized = neutralNormalizedMatrix(values)
            if (normalized == null) {
                rejected.add("${candidate.source}: normalization failed")
                continue
            }

            val validation = validateColorMatrix(normalized)
            if (validation.first) {
                return MatrixResolution(
                    values = normalized,
                    source = candidate.source,
                    applied = true,
                    identityFallbackUsed = false,
                    rejectReason = "none",
                    note = "validated metadata matrix accepted; ${candidate.note}; validationScore=${validation.second.format5()}",
                    preNormalizationValues = values.copyOf(),
                    neutralNormalizationApplied = true
                )
            }
            rejected.add("${candidate.source}: ${validation.third}")
        }

        val reason = if (rejected.isEmpty()) "No usable camera color metadata matrix was present" else rejected.joinToString(" | ")
        warnings.add("Identity matrix fallback used: $reason")
        return MatrixResolution(
            values = IDENTITY_3X3.copyOf(),
            source = "CONTROLLED_IDENTITY_FALLBACK",
            applied = false,
            identityFallbackUsed = true,
            rejectReason = reason,
            note = "Identity fallback used only because no valid metadata matrix was available",
            preNormalizationValues = null,
            neutralNormalizationApplied = false
        )
    }

    private fun colorSpaceTransformToArray(transform: ColorSpaceTransform): FloatArray {
        val values = FloatArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                val rational = transform.getElement(column, row)
                val denominator = rational.denominator
                values[row * 3 + column] = if (denominator == 0) {
                    if (row == column) 1f else 0f
                } else {
                    rational.numerator.toFloat() / denominator.toFloat()
                }
            }
        }
        return values
    }

    private fun xyzToSrgbD65Matrix(): FloatArray = floatArrayOf(
        3.2404542f, -1.5371385f, -0.4985314f,
        -0.9692660f, 1.8760108f, 0.0415560f,
        0.0556434f, -0.2040259f, 1.0572252f
    )

    private fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray? {
        if (a.size != 9 || b.size != 9) return null
        val out = FloatArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                var sum = 0f
                for (k in 0..2) sum += a[row * 3 + k] * b[k * 3 + column]
                out[row * 3 + column] = sum
            }
        }
        return out
    }

    private fun invert3x3(m: FloatArray): FloatArray? {
        if (m.size != 9 || m.any { !it.isFinite() }) return null
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
        if (!det.isFinite() || abs(det) < 0.00001f) return null
        val invDet = 1f / det
        return floatArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * invDet,
            (m[2] * m[7] - m[1] * m[8]) * invDet,
            (m[1] * m[5] - m[2] * m[4]) * invDet,
            (m[5] * m[6] - m[3] * m[8]) * invDet,
            (m[0] * m[8] - m[2] * m[6]) * invDet,
            (m[2] * m[3] - m[0] * m[5]) * invDet,
            (m[3] * m[7] - m[4] * m[6]) * invDet,
            (m[1] * m[6] - m[0] * m[7]) * invDet,
            (m[0] * m[4] - m[1] * m[3]) * invDet
        )
    }

    private fun neutralNormalizedMatrix(values: FloatArray): FloatArray? {
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
        if (values.size != 9) return Triple(false, Float.MAX_VALUE, "expected 9 values, got ${values.size}")
        if (values.any { !it.isFinite() }) return Triple(false, Float.MAX_VALUE, "non-finite matrix value")
        if (values.all { abs(it) < 0.0001f }) return Triple(false, Float.MAX_VALUE, "all-zero matrix")
        val isIdentity = values.indices.all { i -> abs(values[i] - IDENTITY_3X3[i]) < 0.0001f }
        if (isIdentity) return Triple(false, Float.MAX_VALUE, "identity matrix is not accepted as metadata matrix")

        val rowAbs = FloatArray(3) { row -> abs(values[row * 3]) + abs(values[row * 3 + 1]) + abs(values[row * 3 + 2]) }
        if (rowAbs.any { !it.isFinite() || it < 0.05f || it > 8.0f }) {
            return Triple(false, Float.MAX_VALUE, "row gain outside safe range ${rowAbs.toList()}")
        }
        val det = values[0] * (values[4] * values[8] - values[5] * values[7]) -
            values[1] * (values[3] * values[8] - values[5] * values[6]) +
            values[2] * (values[3] * values[7] - values[4] * values[6])
        if (!det.isFinite() || abs(det) < 0.001f || abs(det) > 24.0f) {
            return Triple(false, Float.MAX_VALUE, "determinant outside safe range ${String.format(Locale.US, "%.5f", det)}")
        }
        val maxAbs = values.maxOf { abs(it) }
        val negativeEnergy = values.filter { it < 0f }.sumOf { abs(it).toDouble() }.toFloat()
        val score = negativeEnergy * 0.08f + abs(maxAbs - 1.0f) * 0.04f
        return Triple(true, score, "valid")
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
