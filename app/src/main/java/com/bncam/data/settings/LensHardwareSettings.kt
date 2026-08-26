package com.bncam.data.settings

import java.util.Locale
import kotlin.math.abs

private fun Float.format3(): String = String.format(Locale.US, "%.3f", this)
private fun Float.format5(): String = String.format(Locale.US, "%.5f", this)
private fun List<Float>.formatList(): String = joinToString(prefix = "[", postfix = "]") { it.format5() }
private fun List<Double>.formatDoubleList(): String = joinToString(prefix = "[", postfix = "]") { it.toString() }
private fun List<Int>.formatIntList(): String = joinToString(prefix = "[", postfix = "]")

object LensHardwareModes {
    const val NOISE_OFF = "Off"
    const val NOISE_AUTO = "Auto"
    const val NOISE_DEFAULT = NOISE_OFF
    const val NOISE_MANUAL = "Manual"
    const val BLACK_AUTO = "Auto"
    const val BLACK_DYNAMIC = "Dynamic"
    const val BLACK_MANUAL = "Manual"
    const val COLOR_SYSTEM = "System"
    const val COLOR_MANUAL = "Manual"
    const val AWB_SYSTEM = "System"
    const val AWB_AUTO_RATIO = "Auto"
}

data class ResolvedLensHardwareSettings(
    val lensId: String,
    val snapshotSource: String,
    val warnings: List<String>,

    val noiseModelType: String,
    val noiseModelNativeMode: Int,
    val noiseA: List<Float>,
    val noiseB: List<Float>,
    val noiseC: List<Float>,
    val noiseD: List<Float>,
    /** Camera2 S/O pairs in sensor mosaic-position order. */
    val manualNoiseProfile: List<Double> = emptyList(),
    val isoStep: Float,
    val isoNrStyle: String,
    val isoNrNativeMode: Int,
    val dynamicIsoCoeff: Float,
    val manualIsoValue: Float,

    val blackLevelMode: String,
    val blackLevelNativeMode: Int,
    val dynamicBlackLevelPercent: Float,
    val manualBlackLevels: List<Float>,

    val colorMatrixMode: String,
    val colorMatrixNativeMode: Int,
    val manualColorMatrix: List<Float>,
    val colorMatrixValidationPassed: Boolean,
    val colorMatrixRejectReason: String,

    val awbProfile: String,
    val awbRatioRaw: String,
    val awbRatio: Float,
    val awbTemp: Float,
    val awbIntensity: Float,
    val awbNativeMode: Int,

    val noiseModelCalibrationAdjustment: Float = 0.0f,
    val dynamicChromaAuthorityAdjustment: Float = 0.0f,
    val dynamicLumaAuthorityAdjustment: Float = 0.0f
) {
    val noiseModelCalibrationFactor: Float
        get() = com.bncam.core.quality.SensorNoiseCalibrationMapper.noiseModelCalibrationFactor(noiseModelCalibrationAdjustment.toDouble()).toFloat()

    val effectiveChromaAuthorityStops: Float
        get() = com.bncam.core.quality.SensorNoiseCalibrationMapper.effectiveChromaAuthorityStops(dynamicChromaAuthorityAdjustment.toDouble()).toFloat()

    val effectiveLumaAuthorityStops: Float
        get() = com.bncam.core.quality.SensorNoiseCalibrationMapper.effectiveLumaAuthorityStops(dynamicLumaAuthorityAdjustment.toDouble()).toFloat()

    val chromaUserScale: Float
        get() = com.bncam.core.quality.SensorNoiseCalibrationMapper.chromaUserScale(dynamicIsoCoeff.toDouble(), effectiveChromaAuthorityStops.toDouble()).toFloat()

    val lumaUserScale: Float
        get() = com.bncam.core.quality.SensorNoiseCalibrationMapper.lumaUserScale(dynamicIsoCoeff.toDouble(), effectiveLumaAuthorityStops.toDouble()).toFloat()

    val outerRingAuthority: Float
        get() = com.bncam.core.quality.SensorNoiseCalibrationMapper.outerRingAuthority(
            when (noiseModelNativeMode) { 1 -> "Auto"; 2 -> "Manual"; else -> "Off" },
            dynamicIsoCoeff.toDouble(),
            effectiveChromaAuthorityStops.toDouble()
        ).toFloat()
    val settingsResolved: Boolean = true
    val nativeHardwareConfigPushedExpected: Boolean = true
    val anyManualEffectActive: Boolean =
        noiseModelNativeMode == 2 ||
            blackLevelNativeMode != 0 ||
            colorMatrixNativeMode != 0 ||
            awbNativeMode != 0 ||
            isoNrNativeMode != 0

    fun effectiveBlackLevels(defaultLevels: List<Int>, whiteLevel: Int): List<Int> {
        val safeWhite = whiteLevel.coerceAtLeast(2)
        val base = if (defaultLevels.size >= 4) defaultLevels.take(4) else listOf(64, 64, 64, 64)
        return when (blackLevelNativeMode) {
            1 -> {
                val factor = (dynamicBlackLevelPercent / 100.0f).coerceIn(0.0f, 2.0f)
                base.map { (it.toFloat() * factor).toInt().coerceIn(0, safeWhite - 1) }
            }
            2 -> manualBlackLevels.take(4).map { it.toInt().coerceIn(0, safeWhite - 1) }
            else -> base.map { it.coerceIn(0, safeWhite - 1) }
        }
    }

    fun blackLevelSource(defaultSource: String, defaultLevels: List<Int>, whiteLevel: Int): String {
        return when (blackLevelNativeMode) {
            1 -> "Lens ID Dynamic black level override ${dynamicBlackLevelPercent.format3()}% of base ${defaultLevels.formatIntList()} from $defaultSource"
            2 -> "Lens ID Manual black level override ${effectiveBlackLevels(defaultLevels, whiteLevel).formatIntList()}"
            else -> defaultSource
        }
    }

    fun effectiveWhiteBalance(defaultRed: Float, defaultGreenEven: Float, defaultGreenOdd: Float, defaultBlue: Float): FloatArray {
        if (awbNativeMode == 0) return floatArrayOf(defaultRed, defaultGreenEven, defaultGreenOdd, defaultBlue)
        val temp = (awbTemp * awbIntensity).coerceIn(-1.0f, 1.0f)
        val ratio = awbRatio.coerceIn(0.25f, 1.75f)
        val red = (defaultRed * ratio * (1.0f + temp)).coerceIn(0.1f, 10.0f)
        val blue = (defaultBlue * (2.0f - ratio) * (1.0f - temp)).coerceIn(0.1f, 10.0f)
        return floatArrayOf(red, defaultGreenEven.coerceIn(0.1f, 10.0f), defaultGreenOdd.coerceIn(0.1f, 10.0f), blue)
    }

    fun whiteBalanceSource(defaultSource: String): String {
        return if (awbNativeMode == 0) defaultSource else "Lens ID AWB override profile=$awbProfile ratio=$awbRatioRaw temp=${awbTemp.format3()} intensity=${awbIntensity.format3()}"
    }

    fun effectiveColorMatrix(defaultMatrix: FloatArray): FloatArray {
        return if (colorMatrixNativeMode == 1 && colorMatrixValidationPassed && manualColorMatrix.size == 9) {
            manualColorMatrix.toFloatArray()
        } else {
            defaultMatrix.takeIf { it.size == 9 } ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
    }

    fun colorMatrixSource(defaultSource: String): String {
        return if (colorMatrixNativeMode == 1 && colorMatrixValidationPassed) {
            "Lens ID Manual color matrix override"
        } else {
            defaultSource
        }
    }

    fun colorMatrixNote(defaultNote: String): String {
        return if (colorMatrixNativeMode == 1 && colorMatrixValidationPassed) {
            "Manual 3x3 Lens ID color matrix applied after RAW demosaic and as YUV JPEG color approximation."
        } else if (colorMatrixMode.equals(LensHardwareModes.COLOR_MANUAL, ignoreCase = true)) {
            "Lens ID manual color matrix rejected: $colorMatrixRejectReason. $defaultNote"
        } else if (!colorMatrixMode.equals(LensHardwareModes.COLOR_SYSTEM, ignoreCase = true)) {
            "Legacy Lens ID color preset '$colorMatrixMode' is stored but inactive; visible preset loaders are not exposed. $defaultNote"
        } else {
            defaultNote
        }
    }

    fun nativeNoiseA(): FloatArray? = noiseA.takeIf { noiseModelNativeMode != 0 && it.size == 4 }?.toFloatArray()
    fun nativeNoiseB(): FloatArray? = noiseB.takeIf { noiseModelNativeMode != 0 && it.size == 4 }?.toFloatArray()
    fun nativeNoiseC(): FloatArray? = noiseC.takeIf { noiseModelNativeMode != 0 && it.size == 4 }?.toFloatArray()
    fun nativeNoiseD(): FloatArray? = noiseD.takeIf { noiseModelNativeMode != 0 && it.size == 4 }?.toFloatArray()
    fun nativeManualNoiseProfile(): DoubleArray? = manualNoiseProfile
        .takeIf { noiseModelNativeMode == 2 && (it.size == 2 || it.size == 8) }
        ?.toDoubleArray()
    fun nativeManualBlackLevels(): FloatArray? = manualBlackLevels.takeIf { blackLevelNativeMode == 2 && it.size == 4 }?.toFloatArray()
    fun nativeManualColorMatrix(): FloatArray? = manualColorMatrix.takeIf { colorMatrixNativeMode == 1 && it.size == 9 }?.toFloatArray()

    fun applicabilityForRoute(routeLabel: String): List<Pair<String, String>> {
        val rawRoute = routeLabel.contains("RAW", ignoreCase = true)
        val yuvRoute = routeLabel.contains("YUV", ignoreCase = true)
        return listOf(
            "Black Level Applied To" to when {
                rawRoute && blackLevelNativeMode != 0 -> "RAW_MASTER, RAW_JPEG, DNG_DESCRIPTION_ONLY"
                yuvRoute && blackLevelNativeMode != 0 -> "NOT_APPLICABLE_RAW_ONLY"
                else -> "NOT_APPLICABLE_OR_AUTO"
            },
            "Noise Model Applied To" to when {
                rawRoute && noiseModelNativeMode == 2 -> "RAW_DOMAIN_NATIVE_ISP_DENOISE_SCALING_MANUAL_SO"
                rawRoute && noiseModelNativeMode == 1 -> "RAW_DOMAIN_NATIVE_ISP_DENOISE_SCALING_CAMERA2_SO"
                yuvRoute && noiseModelNativeMode != 0 -> "NOT_APPLICABLE_RAW_NOISE_MODEL"
                else -> "NOT_APPLIED_OFF"
            },
            "Color Matrix Applied To" to when {
                rawRoute && colorMatrixNativeMode != 0 -> "RAW_JPEG_AFTER_DEMOSAIC, DNG_DESCRIPTION_ONLY"
                yuvRoute && colorMatrixNativeMode != 0 -> "YUV_JPEG_APPROXIMATION"
                else -> "SYSTEM_OR_NOT_APPLICABLE"
            },
            "AWB Applied To" to when {
                rawRoute && awbNativeMode != 0 -> "RAW_PREPROCESS_WB, DNG_DESCRIPTION_ONLY"
                yuvRoute && awbNativeMode != 0 -> "YUV_JPEG_APPROXIMATION"
                else -> "SYSTEM_OR_NOT_APPLICABLE"
            },
            "DNG Standard Tags Overridden" to "false",
            "DNG Description Includes Lens Hardware Settings" to "true"
        )
    }

    fun debugPairs(routeLabel: String = "UNKNOWN"): List<Pair<String, String>> {
        val manualNoiseActive = noiseModelNativeMode == 2
        val systemNoiseText = "inactive; Android SENSOR_NOISE_PROFILE is resolved by SensorCalibrationResolver"
        val effectiveBlackText = when (blackLevelNativeMode) {
            1 -> "dynamic ${dynamicBlackLevelPercent.format3()}% of metadata baseline"
            2 -> manualBlackLevels.formatList()
            else -> "resolved from SensorCalibrationResolver metadata baseline"
        }
        return listOf(
            "Lens Hardware Settings Enabled/Resolved" to settingsResolved.toString(),
            "Lens Hardware Lens ID" to lensId,
            "Lens Hardware Snapshot Source" to snapshotSource,
            "Native Hardware Config Pushed" to nativeHardwareConfigPushedExpected.toString(),
            "Native Hardware Config Lens ID" to lensId,
            "Native Hardware Config Version/Fingerprint" to fingerprint(),
            "Black Level Mode" to blackLevelMode,
            "Effective Black Levels" to effectiveBlackText,
            "Noise Model Mode" to noiseModelType,
            "Manual Noise S/O" to if (manualNoiseActive) manualNoiseProfile.formatDoubleList() else systemNoiseText,
            "Noise A/B/C/D Legacy Preset Active" to manualNoiseActive.toString(),
            "Noise A" to if (manualNoiseActive) noiseA.formatList() else systemNoiseText,
            "Noise B" to if (manualNoiseActive) noiseB.formatList() else systemNoiseText,
            "Noise C" to if (manualNoiseActive) noiseC.formatList() else systemNoiseText,
            "Noise D" to if (manualNoiseActive) noiseD.formatList() else systemNoiseText,
            "Sensor Noise Profile Truth Source" to "See Sensor Calibration Summary > Sensor Noise Profile Source/Values S/O",
            "ISO NR Style" to isoNrStyle,
            "Dynamic ISO Coefficient" to dynamicIsoCoeff.format3(),
            "Manual ISO Value" to manualIsoValue.format3(),
            "Color Matrix Mode" to colorMatrixMode,
            "Effective Color Matrix" to if (colorMatrixNativeMode == 1) manualColorMatrix.formatList() else "system/metadata",
            "Color Matrix Validation Passed" to colorMatrixValidationPassed.toString(),
            "Color Matrix Reject Reason" to colorMatrixRejectReason,
            "AWB Mode/Profile" to awbProfile,
            "AWB Ratio" to awbRatioRaw,
            "AWB Temperature" to awbTemp.format3(),
            "AWB Intensity" to awbIntensity.format3()
        ) + applicabilityForRoute(routeLabel) + warnings.mapIndexed { index, warning ->
            "Lens Hardware Warning ${index + 1}" to warning
        }
    }

    fun dngDescription(): String {
        return listOf(
            "lensId=$lensId",
            "source=$snapshotSource",
            "blackMode=$blackLevelMode",
            "blackManual=${if (blackLevelNativeMode == 2) manualBlackLevels.formatList() else "system/auto"}",
            "noiseMode=$noiseModelType",
            "noiseSO=${if (noiseModelNativeMode == 2) manualNoiseProfile.formatDoubleList() else if (noiseModelNativeMode == 1) "auto/per-frame" else "off"}",
            "noiseA=${if (noiseModelNativeMode != 0) noiseA.formatList() else "inactive; see SENSOR_NOISE_PROFILE debug"}",
            "noiseB=${if (noiseModelNativeMode != 0) noiseB.formatList() else "inactive; see SENSOR_NOISE_PROFILE debug"}",
            "colorMode=$colorMatrixMode",
            "colorMatrix=${if (colorMatrixNativeMode == 1) manualColorMatrix.formatList() else "system/metadata"}",
            "awbProfile=$awbProfile",
            "awbRatio=$awbRatioRaw",
            "awbTemp=${awbTemp.format3()}",
            "awbIntensity=${awbIntensity.format3()}",
            "dngStandardTagsOverridden=false",
            "dngDescriptionOnly=true"
        ).joinToString("|")
    }

    fun fingerprint(): String {
        val canonical = listOf(
            "lensId=$lensId",
            "snapshotSource=$snapshotSource",
            "noiseModelType=$noiseModelType",
            "noiseModelNativeMode=$noiseModelNativeMode",
            "noiseA=${noiseA.joinToString(",")}",
            "noiseB=${noiseB.joinToString(",")}",
            "noiseC=${noiseC.joinToString(",")}",
            "noiseD=${noiseD.joinToString(",")}",
            "manualNoiseProfile=${manualNoiseProfile.joinToString(",")}",
            "isoStep=$isoStep",
            "isoNrStyle=$isoNrStyle",
            "isoNrNativeMode=$isoNrNativeMode",
            "dynamicIsoCoeff=$dynamicIsoCoeff",
            "manualIsoValue=$manualIsoValue",
            "blackLevelMode=$blackLevelMode",
            "blackLevelNativeMode=$blackLevelNativeMode",
            "dynamicBlackLevelPercent=$dynamicBlackLevelPercent",
            "manualBlackLevels=${manualBlackLevels.joinToString(",")}",
            "colorMatrixMode=$colorMatrixMode",
            "colorMatrixNativeMode=$colorMatrixNativeMode",
            "manualColorMatrix=${manualColorMatrix.joinToString(",")}",
            "colorMatrixValidationPassed=$colorMatrixValidationPassed",
            "colorMatrixRejectReason=$colorMatrixRejectReason",
            "awbProfile=$awbProfile",
            "awbRatioRaw=$awbRatioRaw",
            "awbRatio=$awbRatio",
            "awbTemp=$awbTemp",
            "awbIntensity=$awbIntensity",
            "awbNativeMode=$awbNativeMode",
            "noiseModelCalibrationAdjustment=$noiseModelCalibrationAdjustment",
            "dynamicChromaAuthorityAdjustment=$dynamicChromaAuthorityAdjustment",
            "dynamicLumaAuthorityAdjustment=$dynamicLumaAuthorityAdjustment"
        ).joinToString("\n")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

object LensHardwareSettingsResolver {
    fun resolve(
        lensId: String,
        noiseModelType: String,
        noiseAString: String,
        noiseBString: String,
        noiseCString: String,
        noiseDString: String,
        isoStepString: String,
        isoNrStyle: String,
        dynamicIsoCoeff: Float,
        manualIsoValueString: String,
        blackLevelMode: String,
        dynamicBlackLevel: Float,
        manualBlackLevelsString: String,
        colorMatrixMode: String,
        manualColorMatrixString: String,
        awbProfile: String,
        awbRatio: String,
        awbTemp: Float,
        awbIntensity: Float,
        manualNoiseSoString: String = "",
        noiseCalibrationAdj: Float = 0.0f,
        chromaAuthorityAdj: Float = 0.0f,
        lumaAuthorityAdj: Float = 0.0f
    ): ResolvedLensHardwareSettings {
        val warnings = mutableListOf<String>()
        fun warn(message: String) { warnings.add(message) }

        val safeNoiseType = when {
            noiseModelType.equals(LensHardwareModes.NOISE_AUTO, ignoreCase = true) -> LensHardwareModes.NOISE_AUTO
            noiseModelType.equals(LensHardwareModes.NOISE_MANUAL, ignoreCase = true) -> LensHardwareModes.NOISE_MANUAL
            else -> LensHardwareModes.NOISE_OFF
        }
        val noiseA = parseFloatList(noiseAString, 4, "Noise A", ::warn)
        val noiseB = parseFloatList(noiseBString, 4, "Noise B", ::warn)
        val noiseC = parseFloatList(noiseCString, 4, "Noise C", ::warn)
        val noiseD = parseFloatList(noiseDString, 4, "Noise D", ::warn)
        val manualNoiseSo = parseNonNegativeDoubleList(
            raw = manualNoiseSoString,
            validCounts = setOf(2, 8),
            label = "Manual Noise S/O",
            warn = ::warn
        )
        val noiseReady = manualNoiseSo.size == 2 || manualNoiseSo.size == 8
        val noiseNativeMode = when {
            safeNoiseType == LensHardwareModes.NOISE_OFF -> 0
            safeNoiseType == LensHardwareModes.NOISE_AUTO -> 1
            safeNoiseType == LensHardwareModes.NOISE_MANUAL && noiseReady -> 2
            safeNoiseType.equals(LensHardwareModes.NOISE_MANUAL, ignoreCase = true) -> {
                warn("Manual noise model is incomplete or invalid: one MONO pair or four Bayer S/O pairs are required. Native noise override disabled.")
                0
            }
            else -> 0
        }

        val safeIsoNrStyle = normalizeChoice(isoNrStyle, "Default")
        val isoStep = parseSingleFloat(isoStepString, 0f, "ISO Step", ::warn).coerceAtLeast(0f)
        val manualIso = parseSingleFloat(manualIsoValueString, 0f, "Manual ISO Value", ::warn).coerceAtLeast(0f)
        val isoNrNativeMode = when (safeIsoNrStyle) {
            "Dynamic ISO coefficient" -> 1
            "Manual ISO value" -> if (manualIso > 0f) 2 else {
                warn("Manual ISO value mode selected but ISO value is missing or invalid. Native ISO override disabled.")
                0
            }
            else -> 0
        }

        val safeBlackMode = normalizeChoice(blackLevelMode, LensHardwareModes.BLACK_AUTO)
        val manualBl = parseFloatList(manualBlackLevelsString, 4, "Manual Black Levels", ::warn)
        val blackNativeMode = when (safeBlackMode) {
            LensHardwareModes.BLACK_DYNAMIC -> 1
            LensHardwareModes.BLACK_MANUAL -> if (manualBl.size == 4) 2 else {
                warn("Manual black level selected but four valid values were not provided. Native black level override disabled.")
                0
            }
            else -> 0
        }

        val safeColorMode = normalizeChoice(colorMatrixMode, LensHardwareModes.COLOR_SYSTEM)
        val manualCm = parseFloatList(manualColorMatrixString, 9, "Manual Color Matrix", ::warn)
        val cmValidation = validateColorMatrix(manualCm)
        if (safeColorMode == LensHardwareModes.COLOR_MANUAL && !cmValidation.first) warn(cmValidation.second)
        if (!safeColorMode.equals(LensHardwareModes.COLOR_SYSTEM, ignoreCase = true) &&
            !safeColorMode.equals(LensHardwareModes.COLOR_MANUAL, ignoreCase = true)
        ) {
            warn("Legacy color matrix preset '$safeColorMode' is stored, but preset loaders are not exposed. System metadata color matrix is used.")
        }
        val colorNativeMode = if (safeColorMode == LensHardwareModes.COLOR_MANUAL && manualCm.size == 9 && cmValidation.first) 1 else 0

        val safeAwbProfile = normalizeChoice(awbProfile, LensHardwareModes.AWB_SYSTEM)
        val safeAwbRatioRaw = normalizeChoice(awbRatio, LensHardwareModes.AWB_AUTO_RATIO)
        val ratio = if (safeAwbRatioRaw.equals(LensHardwareModes.AWB_AUTO_RATIO, ignoreCase = true)) {
            1.0f
        } else {
            parseSingleFloat(safeAwbRatioRaw, 1.0f, "AWB Ratio", ::warn)
        }.coerceIn(0.25f, 1.75f)
        val temp = awbTemp.coerceIn(-1.0f, 1.0f)
        val intensity = awbIntensity.coerceIn(0.0f, 1.0f)
        if (!safeAwbProfile.equals(LensHardwareModes.AWB_SYSTEM, ignoreCase = true) &&
            !safeAwbProfile.equals("Manual", ignoreCase = true)
        ) {
            warn("Legacy AWB profile '$safeAwbProfile' is stored, but preset loaders are not exposed. The profile name alone no longer activates a white-balance override.")
        }
        if (intensity > 0.0001f && abs(temp) <= 0.0001f && safeAwbRatioRaw.equals(LensHardwareModes.AWB_AUTO_RATIO, ignoreCase = true)) {
            warn("White intensity was changed, but AWB temperature is 0 and ratio is Auto. This is intentionally treated as neutral to avoid green/magenta casts.")
        }
        val awbActive = !safeAwbRatioRaw.equals(LensHardwareModes.AWB_AUTO_RATIO, ignoreCase = true) ||
            (abs(temp) > 0.0001f && intensity > 0.0001f)

        return ResolvedLensHardwareSettings(
            lensId = lensId,
            snapshotSource = "DataStore lens hardware settings",
            warnings = warnings,
            noiseModelType = safeNoiseType,
            noiseModelNativeMode = noiseNativeMode,
            noiseA = if (noiseA.size == 4) noiseA else listOf(0f, 0f, 0f, 0f),
            noiseB = if (noiseB.size == 4) noiseB else listOf(0f, 0f, 0f, 0f),
            noiseC = if (noiseC.size == 4) noiseC else listOf(0f, 0f, 0f, 0f),
            noiseD = if (noiseD.size == 4) noiseD else listOf(0f, 0f, 0f, 0f),
            manualNoiseProfile = manualNoiseSo,
            isoStep = isoStep,
            isoNrStyle = safeIsoNrStyle,
            isoNrNativeMode = isoNrNativeMode,
            dynamicIsoCoeff = dynamicIsoCoeff.coerceIn(0.0f, 1.0f),
            manualIsoValue = manualIso,
            blackLevelMode = safeBlackMode,
            blackLevelNativeMode = blackNativeMode,
            dynamicBlackLevelPercent = dynamicBlackLevel.coerceIn(0.0f, 100.0f),
            manualBlackLevels = if (manualBl.size == 4) manualBl else listOf(0f, 0f, 0f, 0f),
            colorMatrixMode = safeColorMode,
            colorMatrixNativeMode = colorNativeMode,
            manualColorMatrix = if (manualCm.size == 9) manualCm else listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            colorMatrixValidationPassed = cmValidation.first,
            colorMatrixRejectReason = cmValidation.second,
            awbProfile = safeAwbProfile,
            awbRatioRaw = safeAwbRatioRaw,
            awbRatio = ratio,
            awbTemp = temp,
            awbIntensity = intensity,
            awbNativeMode = if (awbActive) 1 else 0,
            noiseModelCalibrationAdjustment = noiseCalibrationAdj.coerceIn(-1.0f, 1.0f),
            dynamicChromaAuthorityAdjustment = chromaAuthorityAdj.coerceIn(-1.0f, 1.0f),
            dynamicLumaAuthorityAdjustment = lumaAuthorityAdj.coerceIn(-1.0f, 1.0f)
        )
    }

    private fun normalizeChoice(value: String, fallback: String): String {
        val trimmed = value.trim()
        return if (trimmed.isBlank()) fallback else trimmed
    }

    private fun parseSingleFloat(raw: String, fallback: Float, label: String, warn: (String) -> Unit): Float {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return fallback
        return trimmed.replace(',', '.').toFloatOrNull() ?: run {
            warn("$label value '$raw' could not be parsed. Fallback $fallback used.")
            fallback
        }
    }

    private fun parseFloatList(raw: String, expectedCount: Int, label: String, warn: (String) -> Unit): List<Float> {
        if (raw.isBlank()) return emptyList()
        val parts = raw.split(',', ';', '|', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size != expectedCount) {
            warn("$label expected $expectedCount values but found ${parts.size}. Raw='$raw'.")
            return emptyList()
        }
        val parsed = parts.map { it.replace(',', '.').toFloatOrNull() }
        if (parsed.any { it == null || !it.isFinite() }) {
            warn("$label contains an invalid or non-finite number. Raw='$raw'.")
            return emptyList()
        }
        return parsed.filterNotNull()
    }

    private fun parseNonNegativeDoubleList(
        raw: String,
        validCounts: Set<Int>,
        label: String,
        warn: (String) -> Unit
    ): List<Double> {
        if (raw.isBlank()) return emptyList()
        val parts = raw.split(',', ';', '|', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size !in validCounts) {
            warn("$label expected ${validCounts.sorted()} values but found ${parts.size}.")
            return emptyList()
        }
        val parsed = parts.map { it.toDoubleOrNull() }
        if (parsed.any { it == null || !it.isFinite() || it < 0.0 }) {
            warn("$label contains a negative, invalid, NaN, or infinite value.")
            return emptyList()
        }
        return parsed.filterNotNull()
    }

    private fun validateColorMatrix(values: List<Float>): Pair<Boolean, String> {
        if (values.isEmpty()) return true to "system matrix"
        if (values.size != 9) return false to "Manual color matrix must contain exactly 9 numbers."
        if (values.any { !it.isFinite() }) return false to "Manual color matrix contains non-finite values."
        val maxAbs = values.maxOf { abs(it) }
        if (maxAbs > 4.0f) return false to "Manual color matrix rejected because at least one coefficient exceeds ±4.0."
        val diagonal = abs(values[0]) + abs(values[4]) + abs(values[8])
        if (diagonal < 0.15f) return false to "Manual color matrix rejected because diagonal gain is too close to zero."
        return true to "none"
    }
}
