#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace bncam::vulkan {

enum class IspStageId : std::int32_t {
    HIGHLIGHT_RECOVERY = 0,
    LUMA_DENOISE = 1,
    CHROMA_DENOISE = 2,
    SPATIAL_NOISE_REDUCTION = 3,
    COLOR_NOISE_SUPPRESSION = 4,
    EXPOSURE_TONE = 5,
    CONTRAST_VIBRANCE = 6,
    SHARPENING = 7,
    CROP_ROTATE_OUTPUT_CONVERT = 8
};

inline const char* toString(IspStageId stageId) noexcept {
    switch (stageId) {
        case IspStageId::HIGHLIGHT_RECOVERY: return "HIGHLIGHT_RECOVERY";
        case IspStageId::LUMA_DENOISE: return "LUMA_DENOISE";
        case IspStageId::CHROMA_DENOISE: return "CHROMA_DENOISE";
        case IspStageId::SPATIAL_NOISE_REDUCTION: return "SPATIAL_NOISE_REDUCTION";
        case IspStageId::COLOR_NOISE_SUPPRESSION: return "COLOR_NOISE_SUPPRESSION";
        case IspStageId::EXPOSURE_TONE: return "EXPOSURE_TONE";
        case IspStageId::CONTRAST_VIBRANCE: return "CONTRAST_VIBRANCE";
        case IspStageId::SHARPENING: return "SHARPENING";
        case IspStageId::CROP_ROTATE_OUTPUT_CONVERT: return "CROP_ROTATE_OUTPUT_CONVERT";
    }
    return "HIGHLIGHT_RECOVERY";
}

struct IspStageDiagnostics {
    std::string stageId;
    std::string sourceFunction;
    std::string inputFormat;  // e.g. "R32G32B32A32_SFLOAT"
    std::string outputFormat; // e.g. "BGR8_UNORM" for final JPEG input
    std::string parameters;
    std::uint64_t gpuTimeNs = 0;
    std::uint32_t cpuBypassCount = 0;
    bool enabled = true;
};

struct VulkanIspGraph {
    std::string graphId;
    std::vector<IspStageDiagnostics> stages;
    std::uint32_t intermediateReadbackCount = 0; // Must be 0
    std::uint32_t finalJpegReadbackCount = 1;      // Exactly 1 for final JPEG input
};

}  // namespace bncam::vulkan
