package com.bncam.data.settings

enum class BnCamAwbPresetGroup(val sectionTitle: String) {
    NATURAL("Natural / neutral"),
    WARM_LIGHT("Warm light"),
    COOL_COMPLEX("Cool / complex light")
}

data class BnCamAwbPreset(
    val id: Int,
    val name: String,
    val group: BnCamAwbPresetGroup,
    /** Multiplier applied to the exact-frame Camera2 red gain. */
    val redGainScale: Float,
    /** Multiplier applied to the exact-frame Camera2 blue gain. */
    val blueGainScale: Float,
    val description: String
) {
    /**
     * Strength is a blend from Camera2 exact-frame AWB (0.00) to this preset (1.00).
     * Interpolating the multiplier itself keeps the endpoint contract exact and monotonic.
     */
    fun redScaleAt(strength: Float): Float = scaleAt(redGainScale, strength)
    fun blueScaleAt(strength: Float): Float = scaleAt(blueGainScale, strength)

    private fun scaleAt(fullScale: Float, strength: Float): Float {
        val safe = strength.takeIf(Float::isFinite)?.coerceIn(0.0f, 1.0f) ?: 1.0f
        return 1.0f + (fullScale - 1.0f) * safe
    }

    init {
        require(id >= 0)
        require(name.isNotBlank())
        require(redGainScale.isFinite() && redGainScale in 0.50f..1.50f)
        require(blueGainScale.isFinite() && blueGainScale in 0.50f..1.50f)
    }
}

/**
 * BnCam-native developed-WB tone presets.
 *
 * The preset name describes the intended OUTPUT tone, not the illuminant that should be
 * neutralized. Every preset starts from the exact-frame Camera2 white balance and applies a
 * restrained red/blue bias on top of that neutral point. Full-strength presets stay within +/-10% per channel. This keeps the presets
 * scene-forming and profile-friendly without turning AWB into a second color-grading stage.
 *
 * Direction contract:
 * - warmer output: redGainScale > blueGainScale
 * - cooler output: blueGainScale > redGainScale
 * - Neutral remains 1.0 / 1.0
 */
object BnCamAwbPresetCatalog {
    val all: List<BnCamAwbPreset> = listOf(
        BnCamAwbPreset(0, "Neutral", BnCamAwbPresetGroup.NATURAL, 1.000f, 1.000f,
            "Keeps the exact-frame Camera2 white balance unchanged."),
        BnCamAwbPreset(1, "Daylight", BnCamAwbPresetGroup.NATURAL, 1.020f, 0.990f,
            "Very light warm daylight tone while keeping neutrals close to neutral."),
        BnCamAwbPreset(2, "Cloudy", BnCamAwbPresetGroup.NATURAL, 1.040f, 0.975f,
            "Gentle warmth for a softer overcast-daylight rendering."),
        BnCamAwbPreset(3, "Shade", BnCamAwbPresetGroup.NATURAL, 1.060f, 0.960f,
            "Clearly warm, but still restrained, open-shade rendering."),
        BnCamAwbPreset(4, "Balanced Indoor", BnCamAwbPresetGroup.NATURAL, 1.015f, 1.000f,
            "Near-neutral indoor tone with only a very small warm bias."),

        BnCamAwbPreset(5, "Tungsten", BnCamAwbPresetGroup.WARM_LIGHT, 1.075f, 0.935f,
            "Warm tungsten-style rendering without the former strong blue correction."),
        BnCamAwbPreset(6, "Warm LED", BnCamAwbPresetGroup.WARM_LIGHT, 1.060f, 0.945f,
            "Warm LED rendering with a visible but moderate warm bias."),
        BnCamAwbPreset(7, "Candlelight", BnCamAwbPresetGroup.WARM_LIGHT, 1.100f, 0.910f,
            "The warmest built-in preset, intended to retain candlelight atmosphere."),
        BnCamAwbPreset(8, "Warm Mixed", BnCamAwbPresetGroup.WARM_LIGHT, 1.050f, 0.955f,
            "Moderate warm bias for mixed warm and neutral lighting."),
        BnCamAwbPreset(9, "Sunset Preserve", BnCamAwbPresetGroup.WARM_LIGHT, 1.085f, 0.925f,
            "Preserves visible sunset warmth without pushing orange tones excessively."),

        BnCamAwbPreset(10, "Cool LED", BnCamAwbPresetGroup.COOL_COMPLEX, 0.945f, 1.060f,
            "Cool LED-style rendering with a moderate blue-side bias."),
        BnCamAwbPreset(11, "Fluorescent", BnCamAwbPresetGroup.COOL_COMPLEX, 1.010f, 1.045f,
            "Small blue/magenta-side bias for fluorescent scenes without a large temperature shift."),
        BnCamAwbPreset(12, "Cool Mixed", BnCamAwbPresetGroup.COOL_COMPLEX, 0.960f, 1.050f,
            "Gentle cool rendering for mixed cool artificial lighting."),
        BnCamAwbPreset(13, "Overcast", BnCamAwbPresetGroup.COOL_COMPLEX, 0.980f, 1.030f,
            "Slightly cool, subdued overcast rendering."),
        BnCamAwbPreset(14, "Snow / Blue Hour", BnCamAwbPresetGroup.COOL_COMPLEX, 0.920f, 1.100f,
            "The coolest built-in preset, retaining a clear snow or blue-hour atmosphere.")
    )

    fun byId(id: Int): BnCamAwbPreset? = all.firstOrNull { it.id == id }
    fun inGroup(group: BnCamAwbPresetGroup): List<BnCamAwbPreset> = all.filter { it.group == group }
}
