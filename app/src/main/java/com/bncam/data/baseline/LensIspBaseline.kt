package com.bncam.data.baseline

enum class SourceFormat {
    YUV,
    RAW10,
    RAW_SENSOR
}

enum class ShootingMode {
    SINGLE_FRAME,
    MULTI_FRAME
}

data class LensIspBaselineKey(
    val stableLensKey: String,
    val sourceFormat: SourceFormat,
    val shootingMode: ShootingMode,
    val baselineSchemaVersion: Int = BASELINE_SCHEMA_VERSION
) {
    companion object {
        const val BASELINE_SCHEMA_VERSION = 1
    }
}

data class LensIspBaseline(
    val key: LensIspBaselineKey,
    val baselineRevision: Long = 1L,

    // White Balance & Color
    val awbTempOffsetBaseline: Float = 0.00f,
    val awbTintOffsetBaseline: Float = 0.00f,
    val saturationBaseline: Float = 1.00f,
    val vibranceBaseline: Float = 0.00f,

    // Noise Reduction
    val lumaDenoiseBaseline: Float = 0.05f,
    val chromaDenoiseBaseline: Float = 0.05f,
    val postColorDenoiseBaseline: Float = 0.05f,

    // Tone & Gamma
    val exposureBiasBaseline: Float = 0.00f,
    val shadowsBaseline: Float = 0.00f,
    val midtonesBaseline: Float = 0.00f,
    val highlightsBaseline: Float = 0.00f,
    val contrastBaseline: Float = 1.00f,

    // Detail & Sharpening
    val sharpeningBaseline: Float = 0.20f,
    val edgeProtectionBaseline: Float = 0.10f,
    val haloProtectionBaseline: Float = 0.10f,
    val textureEnhancementBaseline: Float = 0.00f,

    // Phone Assistance
    val phoneSensorSafeContributionBaseline: Float = 0.05f
) {
    companion object {
        fun createDefault(key: LensIspBaselineKey): LensIspBaseline {
            // Adjust defaults per format and shooting mode if needed
            val luma = when (key.sourceFormat) {
                SourceFormat.RAW10 -> if (key.shootingMode == ShootingMode.MULTI_FRAME) 0.03f else 0.08f
                SourceFormat.RAW_SENSOR -> if (key.shootingMode == ShootingMode.MULTI_FRAME) 0.02f else 0.05f
                SourceFormat.YUV -> 0.00f
            }
            val chroma = when (key.sourceFormat) {
                SourceFormat.RAW10 -> if (key.shootingMode == ShootingMode.MULTI_FRAME) 0.04f else 0.10f
                SourceFormat.RAW_SENSOR -> if (key.shootingMode == ShootingMode.MULTI_FRAME) 0.03f else 0.06f
                SourceFormat.YUV -> 0.00f
            }

            return LensIspBaseline(
                key = key,
                lumaDenoiseBaseline = luma,
                chromaDenoiseBaseline = chroma,
                postColorDenoiseBaseline = chroma
            )
        }
    }
}
