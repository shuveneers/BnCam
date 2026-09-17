package com.bncam.core.quality

/**
 * Named neural-denoise characters are transparent projections onto the four
 * user-visible shaping controls. There is no hidden preset state: editing any control
 * after selecting a character naturally resolves back to Custom.
 *
 * Adaptive response is intentionally not a profile control. When Neural Denoise is enabled,
 * sigma/SNR adaptation runs at full authority (1.0) for every character.
 */
data class SpectraProfileCharacterValues(
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

    // Neural On/Off owns the global writeback gate. When enabled, master authority and
    // sigma/SNR adaptive response are both fixed at 100%.
    const val MASTER_AUTHORITY = 1.00f
    const val ADAPTIVE_RESPONSE = 1.00f

    // Existing signed component storage is retained so old .bnc profiles remain compatible.
    const val LUMA = 0.20f
    const val CHROMA = 0.60f
    const val DETAIL_PROTECTION = 0.35f
    const val LOW_FREQUENCY = 0.60f

    // Legacy persistence default only. No legacy Dynamic-ISO value participates in runtime.
    const val STRENGTH = 0.00f

    fun values(): SpectraProfileCharacterValues = SpectraProfileCharacterValues(
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
        description = "Balanced neural cleanup with restrained luma/chroma shaping and strong texture retention.",
        values = SpectraProfileCharacterValues(
            luma = SpectraProfileDefaults.LUMA,
            chroma = SpectraProfileDefaults.CHROMA,
            detailProtection = SpectraProfileDefaults.DETAIL_PROTECTION,
            lowFrequency = SpectraProfileDefaults.LOW_FREQUENCY
        )
    )
    val clean = SpectraProfileCharacter(
        name = "Clean",
        description = "Cleaner surfaces and colour with stronger luma/chroma and low-frequency cleanup.",
        values = SpectraProfileCharacterValues(
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
            luma = -0.10f,
            chroma = 0.40f,
            detailProtection = 0.65f,
            lowFrequency = 0.35f
        )
    )
    val night = SpectraProfileCharacter(
        name = "Night",
        description = "Strong low-light chroma and low-frequency cleanup with bounded luminance smoothing.",
        values = SpectraProfileCharacterValues(
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
        kotlin.math.abs(luma - other.luma) <= tolerance &&
            kotlin.math.abs(chroma - other.chroma) <= tolerance &&
            kotlin.math.abs(detailProtection - other.detailProtection) <= tolerance &&
            kotlin.math.abs(lowFrequency - other.lowFrequency) <= tolerance
}
