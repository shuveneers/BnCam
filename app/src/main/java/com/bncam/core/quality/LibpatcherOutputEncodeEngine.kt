package com.bncam.core.quality

import kotlin.math.roundToInt

data class OutputEncodeResult(
    val post: PostProcessingQualityConfig,
    val jpegEncodeSettingsApplied: Boolean,
    val jpegQuality: Int,
    val jpegQualitySource: String,
    val neutralControlsSkipped: List<String>,
    val activeControls: List<String>
) {
    fun debugPairs(): List<Pair<String, String>> = listOf(
        "JPEG Encode Settings Applied" to jpegEncodeSettingsApplied.toString(),
        "JPEG Quality" to jpegQuality.toString(),
        "JPEG Quality Source" to jpegQualitySource,
        "Output Encode Active Controls" to activeControls.joinToString().ifBlank { "none" },
        "Output Encode Neutral Controls Skipped" to neutralControlsSkipped.joinToString().ifBlank { "none" }
    )
}

object LibpatcherOutputEncodeEngine {
    private const val JPEG_QUALITY_TITLE = "JPEG Quality"

    fun applyOutputEncode(
        base: PostProcessingQualityConfig,
        resolved: ResolvedIspSettings
    ): OutputEncodeResult {
        val outputSettings = resolved.outputEncodeSettings
        val activeControls = mutableListOf<String>()
        val neutralControls = mutableListOf<String>()

        val jpegSetting = outputSettings.firstOrNull { it.title == JPEG_QUALITY_TITLE }
        val jpegRealValue = (jpegSetting?.value as? ResolvedLibpatcherValue.FloatValue)?.value
        val jpegQuality = if (jpegRealValue != null) {
            val quality = jpegRealValue.roundToInt().coerceIn(80, 100)
            if (jpegSetting.source == "profile override") {
                activeControls += "$JPEG_QUALITY_TITLE=$quality (active override)"
            } else {
                neutralControls += "$JPEG_QUALITY_TITLE=$quality (default)"
            }
            quality
        } else {
            neutralControls += JPEG_QUALITY_TITLE
            base.jpegQuality.coerceIn(80, 100)
        }

        return OutputEncodeResult(
            post = base.copy(jpegQuality = jpegQuality),
            jpegEncodeSettingsApplied = jpegRealValue != null,
            jpegQuality = jpegQuality,
            jpegQualitySource = if (jpegSetting != null) {
                "${jpegSetting.source}; ${jpegSetting.mappedRuntimeValue}"
            } else {
                "existing base quality ${base.jpegQuality}; profile value not resolved"
            },
            neutralControlsSkipped = neutralControls,
            activeControls = activeControls
        )
    }
}
