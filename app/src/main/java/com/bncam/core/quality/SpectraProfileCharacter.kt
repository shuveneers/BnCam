package com.bncam.core.quality

/**
 * Named SPECTRA characters are convenience projections onto the user-facing
 * profile controls. The adaptive SPECTRA engine itself always runs at its
 * calibrated master authority when enabled; there is intentionally no second
 * user-facing master-strength control on top of the component controls.
 */
data class SpectraProfileCharacterValues(
    val dynamicIso: Float,
    val luma: Float,
    val chroma: Float,
    val detailProtection: Float,
    val lowFrequency: Float
)

data class SpectraProfileCharacter(
    val name: String,
    val description: String,
    val values: SpectraProfileCharacterValues
)

object SpectraProfileDefaults {
    const val ENABLED = false
    const val DYNAMIC_ISO = 0.45f
    /**
     * Internal neutral master-authority bias. 0 means: use 100% of the calibrated
     * adaptive authority resolved by the physical-noise model. It is not a UI control.
     * Kept as a compatibility constant because older .bnc profiles may still contain
     * spectra_profile_strength.
     */
    const val STRENGTH = 0.00f
    const val LUMA = 0.20f
    const val CHROMA = 0.60f
    const val DETAIL_PROTECTION = 0.35f
    const val LOW_FREQUENCY = 0.60f

    fun values(): SpectraProfileCharacterValues = SpectraProfileCharacterValues(
        dynamicIso = DYNAMIC_ISO,
        luma = LUMA,
        chroma = CHROMA,
        detailProtection = DETAIL_PROTECTION,
        lowFrequency = LOW_FREQUENCY
    )
}

object SpectraProfileCharacters {
    const val CUSTOM = "Custom"

    val natural = SpectraProfileCharacter(
        name = "Natural",
        description = "Balanced sensor cleanup with restrained chroma recovery and strong texture retention.",
        values = SpectraProfileCharacterValues(
            dynamicIso = SpectraProfileDefaults.DYNAMIC_ISO,
            luma = SpectraProfileDefaults.LUMA,
            chroma = SpectraProfileDefaults.CHROMA,
            detailProtection = SpectraProfileDefaults.DETAIL_PROTECTION,
            lowFrequency = SpectraProfileDefaults.LOW_FREQUENCY
        )
    )
    val clean = SpectraProfileCharacter(
        name = "Clean",
        description = "Cleaner surfaces and colour while retaining edge and texture context.",
        values = SpectraProfileCharacterValues(
            dynamicIso = 0.65f,
            luma = 0.55f,
            chroma = 0.85f,
            detailProtection = 0.25f,
            lowFrequency = 0.80f
        )
    )
    val texture = SpectraProfileCharacter(
        name = "Texture",
        description = "Prioritises microtexture and natural grain while still suppressing false colour.",
        values = SpectraProfileCharacterValues(
            dynamicIso = 0.30f,
            luma = -0.10f,
            chroma = 0.40f,
            detailProtection = 0.65f,
            lowFrequency = 0.35f
        )
    )
    val night = SpectraProfileCharacter(
        name = "Night",
        description = "Strong low-light chroma and blotch cleanup with bounded luminance smoothing.",
        values = SpectraProfileCharacterValues(
            dynamicIso = 0.75f,
            luma = 0.35f,
            chroma = 0.90f,
            detailProtection = 0.15f,
            lowFrequency = 0.92f
        )
    )

    val presets: List<SpectraProfileCharacter> = listOf(natural, clean, texture, night)
    val names: List<String> = presets.map { it.name } + CUSTOM

    fun byName(name: String): SpectraProfileCharacter? = presets.firstOrNull { it.name == name }

    fun infer(values: SpectraProfileCharacterValues, tolerance: Float = 0.015f): String {
        val safeTolerance = tolerance.coerceAtLeast(0f)
        return presets.firstOrNull { preset -> preset.values.near(values, safeTolerance) }?.name ?: CUSTOM
    }

    private fun SpectraProfileCharacterValues.near(other: SpectraProfileCharacterValues, tolerance: Float): Boolean =
        kotlin.math.abs(dynamicIso - other.dynamicIso) <= tolerance &&
            kotlin.math.abs(luma - other.luma) <= tolerance &&
            kotlin.math.abs(chroma - other.chroma) <= tolerance &&
            kotlin.math.abs(detailProtection - other.detailProtection) <= tolerance &&
            kotlin.math.abs(lowFrequency - other.lowFrequency) <= tolerance
}
