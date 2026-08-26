package com.bncam.core.vulkan

enum class VulkanIspStageId {
    HIGHLIGHT_RECOVERY,
    LUMA_DENOISE,
    CHROMA_DENOISE,
    SPATIAL_NOISE_REDUCTION,
    COLOR_NOISE_SUPPRESSION,
    EXPOSURE_TONE,
    CONTRAST_VIBRANCE,
    SHARPENING,
    CROP_ROTATE_OUTPUT_CONVERT;

    companion object {
        fun fromNativeName(name: String): VulkanIspStageId = when (name.uppercase()) {
            "HIGHLIGHT_RECOVERY" -> HIGHLIGHT_RECOVERY
            "LUMA_DENOISE" -> LUMA_DENOISE
            "CHROMA_DENOISE" -> CHROMA_DENOISE
            "SPATIAL_NOISE_REDUCTION" -> SPATIAL_NOISE_REDUCTION
            "COLOR_NOISE_SUPPRESSION" -> COLOR_NOISE_SUPPRESSION
            "EXPOSURE_TONE" -> EXPOSURE_TONE
            "CONTRAST_VIBRANCE" -> CONTRAST_VIBRANCE
            "SHARPENING" -> SHARPENING
            else -> CROP_ROTATE_OUTPUT_CONVERT
        }
    }
}

data class IspStageDiagnosticsModel(
    val stageId: VulkanIspStageId,
    val sourceFunction: String,
    val inputFormat: String,
    val outputFormat: String,
    val parameters: String,
    val gpuTimeNs: Long,
    val cpuBypassCount: Int,
    val enabled: Boolean
)

data class VulkanIspGraphModel(
    val graphId: String,
    val stages: List<IspStageDiagnosticsModel>,
    val intermediateReadbackCount: Int, // Must be 0
    val finalJpegReadbackCount: Int     // Must be 1
)
