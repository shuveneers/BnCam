package com.bncam.data.settings

import com.bncam.core.capture.OutputPolicy

enum class DngSourcePolicy {
    ANCHOR_RAW,
    FUSED_RAW
}

data class OutputModeDngConfig(
    val dngSourcePolicy: DngSourcePolicy = DngSourcePolicy.ANCHOR_RAW,
    val dngMasterFrameCount: Int = 1
)

data class OutputModeSettings(
    val jpegOnly: OutputModeDngConfig = OutputModeDngConfig(dngSourcePolicy = DngSourcePolicy.ANCHOR_RAW, dngMasterFrameCount = 1),
    val rawPlusJpeg: OutputModeDngConfig = OutputModeDngConfig(dngSourcePolicy = DngSourcePolicy.ANCHOR_RAW, dngMasterFrameCount = 1),
    val rawOnly: OutputModeDngConfig = OutputModeDngConfig(dngSourcePolicy = DngSourcePolicy.ANCHOR_RAW, dngMasterFrameCount = 1)
) {
    fun getConfigForOutputPolicy(outputPolicy: OutputPolicy): OutputModeDngConfig {
        return when (outputPolicy) {
            OutputPolicy.JPEG -> jpegOnly
            OutputPolicy.JPEG_PLUS_RAW -> rawPlusJpeg
            OutputPolicy.RAW_ONLY -> rawOnly
        }
    }
}
