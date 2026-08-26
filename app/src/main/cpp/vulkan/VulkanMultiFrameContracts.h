#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace bncam::vulkan {

enum class FrameRole : std::int32_t {
    ANCHOR = 0,
    SUPPORT = 1
};

inline const char* toString(FrameRole role) noexcept {
    switch (role) {
        case FrameRole::ANCHOR: return "ANCHOR";
        case FrameRole::SUPPORT: return "SUPPORT";
    }
    return "SUPPORT";
}

enum class FrameState : std::int32_t {
    SELECTED = 0,
    GPU_READY = 1,
    ALIGNMENT_PENDING = 2,
    ALIGNED = 3,
    REJECTED = 4,
    FUSION_PENDING = 5,
    FUSED = 6,
    RELEASE_PENDING = 7,
    RELEASED = 8,
    FAILED = 9
};

inline const char* toString(FrameState state) noexcept {
    switch (state) {
        case FrameState::SELECTED: return "SELECTED";
        case FrameState::GPU_READY: return "GPU_READY";
        case FrameState::ALIGNMENT_PENDING: return "ALIGNMENT_PENDING";
        case FrameState::ALIGNED: return "ALIGNED";
        case FrameState::REJECTED: return "REJECTED";
        case FrameState::FUSION_PENDING: return "FUSION_PENDING";
        case FrameState::FUSED: return "FUSED";
        case FrameState::RELEASE_PENDING: return "RELEASE_PENDING";
        case FrameState::RELEASED: return "RELEASED";
        case FrameState::FAILED: return "FAILED";
    }
    return "FAILED";
}

struct VulkanMotionVector {
    float dx = 0.0f;
    float dy = 0.0f;
    float alignmentResponse = 0.0f;
    bool cfaParityPreserved = true;
    bool accepted = false;
    std::string rejectionReason;
};

struct VulkanFrameAcceptance {
    std::string frameId;
    bool acceptedForAlignment = false;
    bool acceptedForFusion = false;
    float weight = 1.0f;
    std::string reason;
};

struct VulkanFrameResource {
    std::string frameId;
    std::uint64_t generationId = 0;
    std::uint64_t timestampNs = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t rowStrideBytes = 0;
    std::string bayerPattern; // "RGGB", "GRBG", "GBRG", "BGGR", or "YUV"
    FrameRole role = FrameRole::SUPPORT;
    FrameState state = FrameState::SELECTED;
    float selectionScore = 1.0f;
    std::string selectionReason;
    std::string vulkanResourceIdentity;
    VulkanMotionVector motionVector;
};

struct VulkanFrameSet {
    std::string frameSetId;
    std::uint64_t generationId = 0;
    std::uint32_t requestedFrames = 0;
    std::uint32_t effectiveFrames = 0;
    std::vector<VulkanFrameResource> frames;
    std::string anchorFrameId;
};

struct MultiFrameDiagnostics {
    std::uint32_t requestedFrames = 0;
    std::uint32_t effectiveFrames = 0;
    std::uint32_t selectedFrames = 0;
    std::string anchorFrame;
    std::uint32_t supportFrames = 0;
    std::uint32_t GPUReadyFrames = 0;
    std::uint32_t alignmentAcceptedFrames = 0;
    std::uint32_t alignmentRejectedFrames = 0;
    std::uint32_t fusionAcceptedFrames = 0;
    std::uint32_t fusionRejectedFrames = 0;
    std::vector<VulkanMotionVector> motionVectors;
    std::vector<VulkanFrameAcceptance> frameAcceptance;
    std::uint32_t supportReadbackCount = 0;     // Must be 0
    std::uint32_t intermediateReadbackCount = 0;// Must be 0
    std::uint32_t finalReadbackCount = 1;       // 1 final readback for RGB output
    std::uint32_t poolAllocations = 0;
    std::uint32_t poolReuse = 0;
    bool fallback = false;
    std::string failureReason;
};

}  // namespace bncam::vulkan
