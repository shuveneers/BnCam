package com.bncam.data.settings

import java.util.Locale

object ProfileAwbModes {
    const val SYSTEM_AUTO = "System"
    const val BRAND_REFERENCE = "Brand"
    const val MANUAL_KELVIN = "Manual"
}

object ProfileAwbModels {
    const val PLANCKIAN = "Planckian Blackbody"
    const val CIE_DAYLIGHT = "CIE Daylight"
}

object ProfileIspKeys {
    // Lightroom-style profile tone controls. These are ISP/render controls only; Camera2 exposure
    // strategy (shutter/ISO/EV acquisition) has a separate authority and must never read these keys.
    const val TONE_EXPOSURE = "tone_exposure"
    const val TONE_HIGHLIGHTS = "tone_highlights"
    const val TONE_SHADOWS = "tone_shadows"
    const val TONE_WHITES = "tone_whites"
    const val TONE_BLACKS = "tone_blacks"
    const val TONE_CONTRAST = "tone_contrast"
    const val LOCAL_TONE_BIAS = "tone_local_tone_bias"

    // Profile V3 planned tonal controls. These keys are persisted/exported so the UI contract is
    // stable before the dedicated Vulkan processing stages are connected. They MUST NOT affect
    // RenderQualityConfig until their own backend contracts are implemented.
    const val TONE_GAMMA_CONTRAST = "tone_gamma_contrast"
    const val TONE_DEHAZE = "tone_dehaze"
    const val TONE_CLARITY = "tone_clarity"

    // Profile-scoped SPECTRA steering. A value of 0.00 is neutral and inherits
    // the physical lens model unchanged; negative values weaken, positive values strengthen.
    const val SPECTRA_ENABLED = "spectra_profile_enabled"
    const val SPECTRA_DYNAMIC_ISO = "spectra_profile_dynamic_iso"
    const val SPECTRA_STRENGTH = "spectra_profile_strength"
    const val SPECTRA_LUMA = "spectra_profile_luma"
    const val SPECTRA_CHROMA = "spectra_profile_chroma"
    const val SPECTRA_DETAIL = "spectra_profile_detail"
    const val SPECTRA_LOW_FREQUENCY = "spectra_profile_low_frequency"

    // Lightroom-style Detail > Noise Reduction. These are creative post-demosaic controls and
    // remain independent from SPECTRA's physical/adaptive RAW noise-model authority.
    const val DETAIL_NR_LUMINANCE = "detail_nr_luminance"
    const val DETAIL_NR_LUMINANCE_DETAIL = "detail_nr_luminance_detail"
    const val DETAIL_NR_LUMINANCE_CONTRAST = "detail_nr_luminance_contrast"
    const val DETAIL_NR_COLOR = "detail_nr_color"
    const val DETAIL_NR_COLOR_DETAIL = "detail_nr_color_detail"
    const val DETAIL_NR_COLOR_SMOOTHNESS = "detail_nr_color_smoothness"

    // Lightroom-style Presence controls. Tonal contrast remains owned by Tone Contrast.
    const val PRESENCE_VIBRANCE = "presence_vibrance"
    const val PRESENCE_SATURATION = "presence_saturation"
    const val PRESENCE_COLOR_FRINGE_SUPPRESSION = "presence_color_fringe_suppression"

    // Lightroom-style Detail > Sharpening controls. These four keys are the only portable
    // profile authority for sharpening. The renderer may derive internal implementation
    // coefficients from them, but those coefficients are never stored as profile settings.
    const val DETAIL_SHARPENING_METHOD = "detail_sharpening_method"
    const val DETAIL_SHARPENING_AMOUNT = "detail_sharpening_amount"
    const val DETAIL_SHARPENING_RADIUS = "detail_sharpening_radius" // legacy Normal backend radius; hidden in V3 UI
    const val DETAIL_SHARPENING_EDGE = "detail_sharpening_edge"
    const val DETAIL_SHARPENING_DETAIL = "detail_sharpening_detail"
    const val DETAIL_SHARPENING_MASKING = "detail_sharpening_masking"

    const val POLYSHARP_GAIN = "polysharp_gain"
    const val POLYSHARP_MACRO_GAIN = "polysharp_macro_gain"
    const val POLYSHARP_MICRO_GAIN = "polysharp_micro_gain"
    const val POLYSHARP_MAX_DETAIL = "polysharp_max_detail"
    const val POLYSHARP_RADIUS_SMALL = "polysharp_radius_small"
    const val POLYSHARP_RADIUS_MEDIUM = "polysharp_radius_medium"
    const val POLYSHARP_RADIUS_LARGE = "polysharp_radius_large"
}

object ProfileSharpnessMethods {
    const val NORMAL = "Normal Sharpness"
    const val POLYSHARP = "Polysharp"
    val values: List<String> = listOf(NORMAL, POLYSHARP)

    fun sanitize(value: String): String = if (value == POLYSHARP) POLYSHARP else NORMAL
}

object ProfilePlannedDefaults {
    const val GAMMA_CONTRAST = 0f
    const val DEHAZE = 0f
    const val CLARITY = 0f
    const val COLOR_FRINGE_SUPPRESSION = 0f

    // Phase 12 baseline: every profile-owned sharpness control is neutral by default.
    // Capture-detail recovery is a separate physical Phase-11 owner and does not read these values.
    const val EDGE_SHARPNESS = 0.00f
    const val POLYSHARP_GAIN = 0.00f
    const val POLYSHARP_MACRO_GAIN = 0.00f
    const val POLYSHARP_MICRO_GAIN = 0.00f
    const val POLYSHARP_MAX_DETAIL = 0.00f
    const val POLYSHARP_RADIUS_SMALL = 0.00f
    const val POLYSHARP_RADIUS_MEDIUM = 0.00f
    const val POLYSHARP_RADIUS_LARGE = 0.00f
}

/** Lightroom-style Detail defaults. UI displays Amount/Detail/Masking as 0..100. */
object ProfileDetailDefaults {
    const val AMOUNT = 0.00f
    const val RADIUS = 0.00f
    const val DETAIL = 0.00f
    const val MASKING = 0.00f

    const val MIN_RADIUS = 0.50f
    const val MAX_RADIUS = 3.00f
}


object ProfileNoiseReductionDefaults {
    const val LUMINANCE = 0.00f
    const val LUMINANCE_DETAIL = 0.50f
    const val LUMINANCE_CONTRAST = 0.00f
    const val COLOR = 0.00f
    const val COLOR_DETAIL = 0.50f
    const val COLOR_SMOOTHNESS = 0.50f
}

data class ProfileNoiseReductionSettings(
    val luminance: Float = ProfileNoiseReductionDefaults.LUMINANCE,
    val luminanceDetail: Float = ProfileNoiseReductionDefaults.LUMINANCE_DETAIL,
    val luminanceContrast: Float = ProfileNoiseReductionDefaults.LUMINANCE_CONTRAST,
    val color: Float = ProfileNoiseReductionDefaults.COLOR,
    val colorDetail: Float = ProfileNoiseReductionDefaults.COLOR_DETAIL,
    val colorSmoothness: Float = ProfileNoiseReductionDefaults.COLOR_SMOOTHNESS
) {
    fun sanitized(): ProfileNoiseReductionSettings = copy(
        luminance = luminance.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: ProfileNoiseReductionDefaults.LUMINANCE,
        luminanceDetail = luminanceDetail.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: ProfileNoiseReductionDefaults.LUMINANCE_DETAIL,
        luminanceContrast = luminanceContrast.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: ProfileNoiseReductionDefaults.LUMINANCE_CONTRAST,
        color = color.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: ProfileNoiseReductionDefaults.COLOR,
        colorDetail = colorDetail.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: ProfileNoiseReductionDefaults.COLOR_DETAIL,
        colorSmoothness = colorSmoothness.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: ProfileNoiseReductionDefaults.COLOR_SMOOTHNESS
    )
}

data class ProfileDetailSettings(
    val amount: Float = ProfileDetailDefaults.AMOUNT,
    val radius: Float = ProfileDetailDefaults.RADIUS,
    val detail: Float = ProfileDetailDefaults.DETAIL,
    val masking: Float = ProfileDetailDefaults.MASKING
) {
    fun sanitized(): ProfileDetailSettings {
        val safeAmount = amount.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: ProfileDetailDefaults.AMOUNT
        val storedRadius = radius.takeIf { it.isFinite() }?.coerceIn(0f, ProfileDetailDefaults.MAX_RADIUS)
            ?: ProfileDetailDefaults.RADIUS
        return copy(
            amount = safeAmount,
            // Global Sharpness is a standalone signed control. Radius/Detail/Masking are persisted
            // independently and never gate or scale Global Sharpness.
            radius = storedRadius,
            // Detail is a standalone signed microtexture control. It never scales Global
            // Sharpness or Edge; 0 is exact neutral, negative reduces qualified microtexture,
            // positive restores/enhances only statistically credible fine structure.
            detail = detail.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: ProfileDetailDefaults.DETAIL,
            masking = masking.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: ProfileDetailDefaults.MASKING
        )
    }
}

data class ProfileAwbSettings(
    val mode: String = ProfileAwbModes.SYSTEM_AUTO,
    val brand: String = "Canon",
    val preset: String = "Daylight",
    val kelvin: Int = 5200,
    val illuminantModel: String = ProfileAwbModels.CIE_DAYLIGHT,
    val tint: Float = 0f,
    /**
     * Profile V3 AWB intensity. 1.00 is the normal resolved correction, 0.00 neutralizes the
     * profile/system correction, and values above 1.00 apply a bounded stronger correction.
     * The persisted field name is retained for .bnc/DataStore compatibility.
     */
    val referenceIntensity: Float = 1f
) {
    fun sanitized(): ProfileAwbSettings = copy(
        mode = mode.takeIf { it in MODES } ?: ProfileAwbModes.SYSTEM_AUTO,
        brand = brand.takeIf { it in BRANDS } ?: "Canon",
        preset = preset.takeIf { it in PRESETS } ?: "Daylight",
        kelvin = kelvin.coerceIn(2000, 10000),
        illuminantModel = illuminantModel.takeIf { it in MODELS } ?: ProfileAwbModels.CIE_DAYLIGHT,
        tint = tint.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f,
        referenceIntensity = referenceIntensity.takeIf { it.isFinite() }?.coerceIn(0f, 1.5f) ?: 1f
    )

    fun summary(): String = when (mode) {
        ProfileAwbModes.BRAND_REFERENCE -> "$brand reference · $preset · $kelvin K · ${String.format(java.util.Locale.US, "%.0f%%", referenceIntensity * 100f)}"
        ProfileAwbModes.MANUAL_KELVIN -> "Manual Kelvin · $kelvin K · $illuminantModel · ${String.format(java.util.Locale.US, "%.0f%%", referenceIntensity * 100f)}"
        else -> "System / Auto · ${String.format(java.util.Locale.US, "%.0f%%", referenceIntensity * 100f)}"
    }

    companion object {
        val MODES = listOf(
            ProfileAwbModes.SYSTEM_AUTO,
            ProfileAwbModes.BRAND_REFERENCE,
            ProfileAwbModes.MANUAL_KELVIN
        )
        val BRANDS = listOf("Canon", "Nikon", "Leica", "Fujifilm")
        val PRESETS = listOf("Daylight", "Cloudy", "Shade", "Tungsten / Incandescent", "Fluorescent", "Flash")
        val MODELS = listOf(ProfileAwbModels.PLANCKIAN, ProfileAwbModels.CIE_DAYLIGHT)
    }
}

object LensHardwareTuningModes {
    const val OFF = "Off"
    const val AUTO = "Auto"
    const val MANUAL = "Manual"
}

data class LensNoiseModelSettings(
    val mode: String = LensHardwareTuningModes.OFF,
    /** Mosaic-position order: S0,O0,S1,O1,S2,O2,S3,O3; MONO uses the first pair. */
    val values: List<Double> = List(8) { 0.0 }
) {
    fun sanitized(): LensNoiseModelSettings {
        val safe = values.take(8).map { if (it.isFinite() && it >= 0.0) it else 0.0 }.toMutableList()
        while (safe.size < 8) safe += 0.0
        return copy(
            mode = when {
                mode.equals(LensHardwareTuningModes.AUTO, ignoreCase = true) -> LensHardwareTuningModes.AUTO
                mode.equals(LensHardwareTuningModes.MANUAL, ignoreCase = true) -> LensHardwareTuningModes.MANUAL
                else -> LensHardwareTuningModes.OFF
            },
            values = safe
        )
    }

    fun encoded(): String = sanitized().values.joinToString(",") { it.toString() }

    fun summary(cfaName: String, autoSource: String? = null): String = when (mode) {
        LensHardwareTuningModes.MANUAL ->
            "Manual · $cfaName · ${if (cfaName.startsWith("MONO")) "1-channel" else "4-channel"} S/O"
        LensHardwareTuningModes.AUTO -> "On · ${autoSource ?: "Camera2 metadata"}"
        else -> LensHardwareTuningModes.OFF
    }
}

data class LensBlackLevelSettings(
    val mode: String = LensHardwareTuningModes.AUTO,
    /** Mosaic-position order matching the sensor CFA's 2x2 tile. */
    val values: List<Double> = List(4) { 0.0 }
) {
    fun sanitized(): LensBlackLevelSettings {
        val safe = values.take(4).map { if (it.isFinite() && it >= 0.0) it else 0.0 }.toMutableList()
        while (safe.size < 4) safe += 0.0
        return copy(
            mode = if (mode == LensHardwareTuningModes.MANUAL) mode else LensHardwareTuningModes.AUTO,
            values = safe
        )
    }

    fun encoded(): String = sanitized().values.joinToString(",") { it.toString() }

    fun summary(autoSource: String? = null): String = if (mode == LensHardwareTuningModes.MANUAL) {
        "Manual · " + sanitized().values.joinToString(" / ") { formatDecimal(it) }
    } else {
        "Auto · ${autoSource ?: "Dynamic metadata"}"
    }
}

private fun formatDecimal(value: Double): String {
    val text = String.format(Locale.US, "%.4f", value)
    return text.trimEnd('0').trimEnd('.').ifBlank { "0" }
}

internal fun parseStoredDoubles(raw: String, count: Int): List<Double> {
    val fields = raw.split(',')
    return List(count) { index ->
        fields.getOrNull(index)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
    }
}
