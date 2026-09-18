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
    init {
        require(id >= 0)
        require(name.isNotBlank())
        require(redGainScale.isFinite() && redGainScale in 0.50f..1.50f)
        require(blueGainScale.isFinite() && blueGainScale in 0.50f..1.50f)
    }
}

/**
 * BnCam-native AWB response presets.
 *
 * These are not RGB looks and do not replace the color matrix. Each preset starts from the
 * active lens' exact-frame Camera2 white balance and changes how strongly the illuminant is
 * neutralized. Auto remains the default and performs scene refinement; selecting a preset is an
 * explicit developed-WB choice and therefore remains deterministic for the JPEG pipeline.
 */
object BnCamAwbPresetCatalog {
    val all: List<BnCamAwbPreset> = listOf(
        BnCamAwbPreset(0, "Neutral", BnCamAwbPresetGroup.NATURAL, 1.000f, 1.000f,
            "Keeps the exact-frame neutral point without an additional warm/cool bias."),
        BnCamAwbPreset(1, "Daylight", BnCamAwbPresetGroup.NATURAL, 1.020f, 0.990f,
            "Small daylight warmth while keeping neutral subjects close to neutral."),
        BnCamAwbPreset(2, "Cloudy", BnCamAwbPresetGroup.NATURAL, 1.050f, 0.970f,
            "Moderately warmer response for cool overcast daylight."),
        BnCamAwbPreset(3, "Shade", BnCamAwbPresetGroup.NATURAL, 1.080f, 0.950f,
            "Stronger warm compensation for blue-biased open shade."),
        BnCamAwbPreset(4, "Balanced Indoor", BnCamAwbPresetGroup.NATURAL, 1.010f, 1.030f,
            "Balanced indoor response with a mild blue-side correction."),

        BnCamAwbPreset(5, "Tungsten", BnCamAwbPresetGroup.WARM_LIGHT, 0.900f, 1.160f,
            "Strong warm-light neutralization with a deliberate blue-gain increase."),
        BnCamAwbPreset(6, "Warm LED", BnCamAwbPresetGroup.WARM_LIGHT, 0.940f, 1.120f,
            "Less aggressive than Tungsten for modern warm LED lighting."),
        BnCamAwbPreset(7, "Candlelight", BnCamAwbPresetGroup.WARM_LIGHT, 0.980f, 1.070f,
            "Corrects the warm cast while preserving more of the scene atmosphere."),
        BnCamAwbPreset(8, "Warm Mixed", BnCamAwbPresetGroup.WARM_LIGHT, 0.960f, 1.100f,
            "Compromise response for mixed warm lamps and neutral ambient light."),
        BnCamAwbPreset(9, "Sunset Preserve", BnCamAwbPresetGroup.WARM_LIGHT, 1.060f, 0.950f,
            "Retains sunset warmth instead of fully neutralizing it."),

        BnCamAwbPreset(10, "Cool LED", BnCamAwbPresetGroup.COOL_COMPLEX, 1.100f, 0.920f,
            "Warms a strongly cool LED scene without changing the color matrix."),
        BnCamAwbPreset(11, "Fluorescent", BnCamAwbPresetGroup.COOL_COMPLEX, 1.080f, 1.080f,
            "Raises red and blue relative to green to counter a typical greenish fluorescent cast."),
        BnCamAwbPreset(12, "Cool Mixed", BnCamAwbPresetGroup.COOL_COMPLEX, 1.070f, 0.960f,
            "Moderate correction for mixed cool artificial lighting."),
        BnCamAwbPreset(13, "Overcast", BnCamAwbPresetGroup.COOL_COMPLEX, 1.050f, 0.980f,
            "Gentle warming for cool flat daylight."),
        BnCamAwbPreset(14, "Snow / Blue Hour", BnCamAwbPresetGroup.COOL_COMPLEX, 1.120f, 0.900f,
            "Strong correction for very blue ambient light while preserving a natural neutral point.")
    )

    fun byId(id: Int): BnCamAwbPreset? = all.firstOrNull { it.id == id }
    fun inGroup(group: BnCamAwbPresetGroup): List<BnCamAwbPreset> = all.filter { it.group == group }
}
