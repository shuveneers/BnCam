package com.bncam.data.settings

import java.util.Locale
import kotlin.math.abs

private fun Float.format3(): String = String.format(Locale.US, "%.3f", this)
private fun Float.format5(): String = String.format(Locale.US, "%.5f", this)
private fun List<Float>.formatList(): String = joinToString(prefix = "[", postfix = "]") { it.format5() }

object LensHardwareModes {
    // Legacy noise choices are retained only so old DataStore snapshots can still be decoded.
    // Physical noise authority is owned by PhysicalNoiseModelSettingsStore.
    const val NOISE_OFF = "Off"
    const val NOISE_AUTO = "Auto"
    const val NOISE_DEFAULT = NOISE_OFF
    const val NOISE_MANUAL = "Manual"

    // Legacy Black Level values are decode/import aliases only. Production authority is
    // BlackLevelSettingsStore -> RawBlackAuthorityPolicy.
    const val BLACK_SYSTEM = "System"
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

    // Compatibility storage only. Production physical-noise transport is always neutral here.
    val noiseModelType: String,
    val noiseModelNativeMode: Int,
    val noiseA: List<Float>,
    val noiseB: List<Float>,
    val noiseC: List<Float>,
    val noiseD: List<Float>,
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

    // Compatibility fields retained in the data shape; production resolver always neutralizes them.
    val noiseModelCalibrationAdjustment: Float = 0.0f,
    val dynamicChromaAuthorityAdjustment: Float = 0.0f,
    val dynamicLumaAuthorityAdjustment: Float = 0.0f
) {
    val noiseModelCalibrationFactor: Float
        get() = com.bncam.core.quality.SensorNoiseCalibrationMapper
            .noiseModelCalibrationFactor(noiseModelCalibrationAdjustment.toDouble()).toFloat()

    val effectiveChromaAuthorityStops: Float
        get() = com.bncam.core.quality.SensorNoiseCalibrationMapper
            .effectiveChromaAuthorityStops(dynamicChromaAuthorityAdjustment.toDouble()).toFloat()

    val effectiveLumaAuthorityStops: Float
        get() = com.bncam.core.quality.SensorNoiseCalibrationMapper
            .effectiveLumaAuthorityStops(dynamicLumaAuthorityAdjustment.toDouble()).toFloat()

    /**
     * Legacy Lens Hardware Dynamic ISO must never shape native RAW/SPECTRA processing anymore.
     * Physical Dynamic ISO is resolved upstream into explicit physical S/O.
     */
    val chromaUserScale: Float get() = 1.0f
    val lumaUserScale: Float get() = 1.0f
    val outerRingAuthority: Float get() = 0.0f

    val settingsResolved: Boolean = true
    val nativeHardwareConfigPushedExpected: Boolean = true

    val anyManualEffectActive: Boolean =
        colorMatrixNativeMode != 0 ||
            awbNativeMode != 0

    /**
     * Legacy compatibility projection only. Black Level v2 owns developed RAW.
     *
     * This ABI must never scale, replace or otherwise mutate the Camera2 baseline. Keeping the
     * method as an identity projection lets older callers compile while preventing a second
     * black-level owner from reappearing.
     */
    fun effectiveBlackLevels(defaultLevels: List<Int>, whiteLevel: Int): List<Int> {
        val safeWhite = whiteLevel.coerceAtLeast(2)
        val base = if (defaultLevels.size >= 4) defaultLevels.take(4) else listOf(64, 64, 64, 64)
        return base.map { it.coerceIn(0, safeWhite - 1) }
    }

    fun blackLevelSource(defaultSource: String, defaultLevels: List<Int>, whiteLevel: Int): String {
        @Suppress("UNUSED_VARIABLE")
        val compatibilityOnly = defaultLevels.size + whiteLevel
        return defaultSource
    }

    fun effectiveWhiteBalance(
        defaultRed: Float,
        defaultGreenEven: Float,
        defaultGreenOdd: Float,
        defaultBlue: Float
    ): FloatArray {
        if (awbNativeMode == 0) {
            return floatArrayOf(defaultRed, defaultGreenEven, defaultGreenOdd, defaultBlue)
        }
        val temp = (awbTemp * awbIntensity).coerceIn(-1.0f, 1.0f)
        val ratio = awbRatio.coerceIn(0.25f, 1.75f)
        val red = (defaultRed * ratio * (1.0f + temp)).coerceIn(0.1f, 10.0f)
        val blue = (defaultBlue * (2.0f - ratio) * (1.0f - temp)).coerceIn(0.1f, 10.0f)
        return floatArrayOf(
            red,
            defaultGreenEven.coerceIn(0.1f, 10.0f),
            defaultGreenOdd.coerceIn(0.1f, 10.0f),
            blue
        )
    }

    fun whiteBalanceSource(defaultSource: String): String =
        if (awbNativeMode == 0) defaultSource
        else "Lens ID AWB override profile=$awbProfile ratio=$awbRatioRaw temp=${awbTemp.format3()} intensity=${awbIntensity.format3()}"

    fun effectiveColorMatrix(defaultMatrix: FloatArray): FloatArray =
        if (colorMatrixNativeMode == 1 && colorMatrixValidationPassed && manualColorMatrix.size == 9) {
            manualColorMatrix.toFloatArray()
        } else {
            defaultMatrix.takeIf { it.size == 9 }
                ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }

    fun colorMatrixSource(defaultSource: String): String =
        if (colorMatrixNativeMode == 1 && colorMatrixValidationPassed) {
            "Lens ID Manual color matrix override"
        } else {
            defaultSource
        }

    fun colorMatrixNote(defaultNote: String): String = when {
        colorMatrixNativeMode == 1 && colorMatrixValidationPassed ->
            "Manual 3x3 Lens ID color matrix applied after RAW demosaic and as YUV JPEG color approximation."
        colorMatrixMode.equals(LensHardwareModes.COLOR_MANUAL, ignoreCase = true) ->
            "Lens ID manual color matrix rejected: $colorMatrixRejectReason. $defaultNote"
        !colorMatrixMode.equals(LensHardwareModes.COLOR_SYSTEM, ignoreCase = true) ->
            "Legacy Lens ID color preset '$colorMatrixMode' is stored but inactive; visible preset loaders are not exposed. $defaultNote"
        else -> defaultNote
    }

    // Hard production cutoff: these compatibility methods can no longer expose legacy noise data.
    fun nativeNoiseA(): FloatArray? = null
    fun nativeNoiseB(): FloatArray? = null
    fun nativeNoiseC(): FloatArray? = null
    fun nativeNoiseD(): FloatArray? = null
    fun nativeManualNoiseProfile(): DoubleArray? = null

    // Production cutoff: native/manual Black Level transport is retired.
    fun nativeManualBlackLevels(): FloatArray? = null

    fun nativeManualColorMatrix(): FloatArray? =
        manualColorMatrix.takeIf { colorMatrixNativeMode == 1 && it.size == 9 }?.toFloatArray()

    fun applicabilityForRoute(routeLabel: String): List<Pair<String, String>> {
        val rawRoute = routeLabel.contains("RAW", ignoreCase = true)
        val yuvRoute = routeLabel.contains("YUV", ignoreCase = true)
        return listOf(
            "Black Level Applied To" to when {
                rawRoute -> "BLACK_LEVEL_V2_RAW_AUTHORITY; LEGACY_LENS_HARDWARE_TRANSPORT_NOT_APPLIED"
                yuvRoute -> "NOT_APPLICABLE_RAW_ONLY"
                else -> "NOT_APPLICABLE"
            },
            "Legacy Lens Hardware Noise Applied To" to "NOT_APPLIED_RETIRED_USE_PHYSICAL_NOISE_MODEL",
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
        val effectiveBlackText =
            "legacy transport retired; developed RAW authority=BlackLevelSettingsStore/RawBlackAuthorityPolicy"
        return listOf(
            "Lens Hardware Settings Enabled/Resolved" to settingsResolved.toString(),
            "Lens Hardware Lens ID" to lensId,
            "Lens Hardware Snapshot Source" to snapshotSource,
            "Native Hardware Config Pushed" to nativeHardwareConfigPushedExpected.toString(),
            "Native Hardware Config Lens ID" to lensId,
            "Native Hardware Config Version/Fingerprint" to fingerprint(),
            "Black Level Mode" to "$blackLevelMode (legacy compatibility transport retired)",
            "Effective Black Levels" to effectiveBlackText,
            "Legacy Noise Model Transport" to "RETIRED_NEUTRAL; physical authority=PhysicalNoiseModelSettingsStore",
            "Legacy Noise Native Mode" to noiseModelNativeMode.toString(),
            "Legacy ISO NR Native Mode" to isoNrNativeMode.toString(),
            "Legacy Dynamic ISO Coefficient" to dynamicIsoCoeff.format3(),
            "Legacy Manual ISO Value" to manualIsoValue.format3(),
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

    fun dngDescription(): String = listOf(
        "lensId=$lensId",
        "source=$snapshotSource",
        "blackMode=legacy_transport_retired",
        "blackManual=not_applied_use_black_level_v2",
        "legacyNoiseTransport=retired-neutral",
        "physicalNoiseAuthority=separate-processing-snapshot",
        "colorMode=$colorMatrixMode",
        "colorMatrix=${if (colorMatrixNativeMode == 1) manualColorMatrix.formatList() else "system/metadata"}",
        "awbProfile=$awbProfile",
        "awbRatio=$awbRatioRaw",
        "awbTemp=${awbTemp.format3()}",
        "awbIntensity=${awbIntensity.format3()}",
        "dngStandardTagsOverridden=false",
        "dngDescriptionOnly=true"
    ).joinToString("|")

    fun fingerprint(): String {
        val canonical = listOf(
            "lensId=$lensId",
            "snapshotSource=$snapshotSource",
            "legacyNoiseTransport=retired-neutral",
            "noiseModelNativeMode=$noiseModelNativeMode",
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
    private const val RETIRED_NOISE_WARNING =
        "Legacy Lens Hardware noise/ISO-NR settings retained for migration only; physical noise authority is resolved by PhysicalNoiseModelSettingsStore."

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

        // Parse only enough legacy state to identify that migration data existed.
        val safeNoiseType = when {
            noiseModelType.equals(LensHardwareModes.NOISE_AUTO, ignoreCase = true) -> LensHardwareModes.NOISE_AUTO
            noiseModelType.equals(LensHardwareModes.NOISE_MANUAL, ignoreCase = true) -> LensHardwareModes.NOISE_MANUAL
            else -> LensHardwareModes.NOISE_OFF
        }
        val safeIsoNrStyle = normalizeChoice(isoNrStyle, "Default")
        val legacyNoiseRequested =
            safeNoiseType != LensHardwareModes.NOISE_OFF ||
                !safeIsoNrStyle.equals("Default", ignoreCase = true) ||
                dynamicIsoCoeff.takeIf { it.isFinite() }?.let { abs(it) > 1.0e-7f } == true ||
                manualIsoValueString.isNotBlank() ||
                isoStepString.isNotBlank() ||
                noiseAString.isNotBlank() || noiseBString.isNotBlank() ||
                noiseCString.isNotBlank() || noiseDString.isNotBlank() ||
                manualNoiseSoString.isNotBlank() ||
                abs(noiseCalibrationAdj) > 1.0e-7f ||
                abs(chromaAuthorityAdj) > 1.0e-7f ||
                abs(lumaAuthorityAdj) > 1.0e-7f
        if (legacyNoiseRequested) warn(RETIRED_NOISE_WARNING)

        // Parse legacy Black Level fields only as migration evidence. They must never enter the
        // SensorCalibration pixel-authority layer after BL6; Black Level v2 is applied later at the
        // resolved RAW-domain boundary.
        val safeBlackMode = normalizeChoice(blackLevelMode, LensHardwareModes.BLACK_AUTO)
        val manualBl = parseFloatList(manualBlackLevelsString, 4, "Manual Black Levels", ::warn)
        val legacyBlackTransportRequested =
            safeBlackMode.equals(LensHardwareModes.BLACK_DYNAMIC, ignoreCase = true) ||
                safeBlackMode.equals(LensHardwareModes.BLACK_MANUAL, ignoreCase = true) ||
                manualBlackLevelsString.isNotBlank() ||
                kotlin.math.abs(dynamicBlackLevel - 100.0f) > 1.0e-4f
        if (legacyBlackTransportRequested && kotlin.math.abs(dynamicBlackLevel - 100.0f) > 1.0e-4f) {
            warn(
                "Legacy Dynamic Black Level percentage=${dynamicBlackLevel.format3()}% is migration-only; " +
                    "production Black Level authority is BlackLevelSettingsStore/RawBlackAuthorityPolicy."
            )
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
        val colorNativeMode =
            if (safeColorMode == LensHardwareModes.COLOR_MANUAL && manualCm.size == 9 && cmValidation.first) 1 else 0

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
        if (intensity > 0.0001f && abs(temp) <= 0.0001f &&
            safeAwbRatioRaw.equals(LensHardwareModes.AWB_AUTO_RATIO, ignoreCase = true)
        ) {
            warn("White intensity was changed, but AWB temperature is 0 and ratio is Auto. This is intentionally treated as neutral to avoid green/magenta casts.")
        }
        val awbActive =
            !safeAwbRatioRaw.equals(LensHardwareModes.AWB_AUTO_RATIO, ignoreCase = true) ||
                (abs(temp) > 0.0001f && intensity > 0.0001f)

        return ResolvedLensHardwareSettings(
            lensId = lensId,
            snapshotSource = "DataStore lens hardware settings; legacy noise/black transport retired",
            warnings = warnings,

            // Production cutoff. Do not reintroduce legacy Lens Hardware noise authority here.
            noiseModelType = LensHardwareModes.NOISE_OFF,
            noiseModelNativeMode = 0,
            noiseA = listOf(0f, 0f, 0f, 0f),
            noiseB = listOf(0f, 0f, 0f, 0f),
            noiseC = listOf(0f, 0f, 0f, 0f),
            noiseD = listOf(0f, 0f, 0f, 0f),
            manualNoiseProfile = emptyList(),
            isoStep = 0.0f,
            isoNrStyle = "Default",
            isoNrNativeMode = 0,
            dynamicIsoCoeff = 0.0f,
            manualIsoValue = 0.0f,

            // Hard ABI cutoff. SensorCalibration still carries legacy fields in its data shape,
            // but it can now observe only neutral System values. System/Dynamic/Manual production
            // ownership lives exclusively in Black Level v2 at RawBlackDomainBinding.
            blackLevelMode = LensHardwareModes.BLACK_SYSTEM,
            blackLevelNativeMode = 0,
            dynamicBlackLevelPercent = 100.0f,
            manualBlackLevels = listOf(0f, 0f, 0f, 0f),

            colorMatrixMode = safeColorMode,
            colorMatrixNativeMode = colorNativeMode,
            manualColorMatrix = if (manualCm.size == 9) manualCm
            else listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            colorMatrixValidationPassed = cmValidation.first,
            colorMatrixRejectReason = cmValidation.second,

            awbProfile = safeAwbProfile,
            awbRatioRaw = safeAwbRatioRaw,
            awbRatio = ratio,
            awbTemp = temp,
            awbIntensity = intensity,
            awbNativeMode = if (awbActive) 1 else 0,

            // Retire hidden native noise-shaping controls together with the visible legacy path.
            noiseModelCalibrationAdjustment = 0.0f,
            dynamicChromaAuthorityAdjustment = 0.0f,
            dynamicLumaAuthorityAdjustment = 0.0f
        )
    }

    private fun normalizeChoice(value: String, fallback: String): String {
        val trimmed = value.trim()
        return if (trimmed.isBlank()) fallback else trimmed
    }

    private fun parseSingleFloat(
        raw: String,
        fallback: Float,
        label: String,
        warn: (String) -> Unit
    ): Float {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return fallback
        return trimmed.replace(',', '.').toFloatOrNull() ?: run {
            warn("$label value '$raw' could not be parsed. Fallback $fallback used.")
            fallback
        }
    }

    private fun parseFloatList(
        raw: String,
        expectedCount: Int,
        label: String,
        warn: (String) -> Unit
    ): List<Float> {
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
