package com.bncam.data.settings

data class NoiseCalibrationConfig(
    val mode: String = MODE_AUTO, // AUTO or MANUAL
    val noiseA: List<Float> = listOf(0f, 0f, 0f, 0f),
    val noiseB: List<Float> = listOf(0f, 0f, 0f, 0f),
    val noiseC: List<Float> = listOf(0f, 0f, 0f, 0f),
    val noiseD: List<Float> = listOf(0f, 0f, 0f, 0f),
    val isoStep: Float = 0f,
    val isoNrStyle: String = "Default",
    val dynamicIsoCoeff: Float = 0f,
    val manualIsoValue: Float = 0f
) {
    companion object {
        const val MODE_AUTO = "AUTO"
        const val MODE_MANUAL = "MANUAL"
    }
}

data class ColorTransformCalibrationConfig(
    val mode: String = MODE_AUTO, // AUTO or MANUAL
    val manualMatrix: List<Float> = listOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    ) // 3x3 RR, RG, RB, GR, GG, GB, BR, BG, BB
) {
    companion object {
        const val MODE_AUTO = "AUTO"
        const val MODE_MANUAL = "MANUAL"
    }
}

data class BlackLevelCalibrationConfig(
    val mode: String = MODE_AUTO, // AUTO or MANUAL
    val l1: Float = 64f,
    val l2: Float = 64f,
    val l3: Float = 64f,
    val l4: Float = 64f,
    val dynamicPercent: Float = 100f
) {
    val manualLevels: List<Float> get() = listOf(l1, l2, l3, l4)

    companion object {
        const val MODE_AUTO = "AUTO"
        const val MODE_MANUAL = "MANUAL"
    }
}

data class LensCalibrationConfig(
    val lensId: String,
    val noiseModel: NoiseCalibrationConfig = NoiseCalibrationConfig(),
    val colorTransform: ColorTransformCalibrationConfig = ColorTransformCalibrationConfig(),
    val blackLevel: BlackLevelCalibrationConfig = BlackLevelCalibrationConfig()
)
