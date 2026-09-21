#pragma once

#include <cstdint>

namespace bncam::vulkan {

enum class RawPreviewComputeSlotPhase : std::uint8_t {
    FREE,
    RECORDING,
    SUBMITTED,
    GPU_COMPLETE
};

// The renderer separately owns publication and GL retirement. This ledger covers only the
// Vulkan compute lifetime, and is guarded by VulkanRawPreviewBackend::mutex_.
class RawPreviewComputeSlotLifecycle final {
public:
    bool begin(std::int32_t generation, std::int64_t timestampNs) {
        if (phase_ != RawPreviewComputeSlotPhase::FREE) return false;
        generation_ = generation;
        timestampNs_ = timestampNs;
        phase_ = RawPreviewComputeSlotPhase::RECORDING;
        return true;
    }

    void cancelRecording() {
        if (phase_ == RawPreviewComputeSlotPhase::RECORDING) reset();
    }

    bool submit() {
        if (phase_ != RawPreviewComputeSlotPhase::RECORDING) return false;
        phase_ = RawPreviewComputeSlotPhase::SUBMITTED;
        return true;
    }

    bool submittedFor(std::int32_t generation, std::int64_t timestampNs) const {
        return phase_ == RawPreviewComputeSlotPhase::SUBMITTED &&
               generation_ == generation && timestampNs_ == timestampNs;
    }

    bool complete() {
        if (phase_ != RawPreviewComputeSlotPhase::SUBMITTED) return false;
        phase_ = RawPreviewComputeSlotPhase::GPU_COMPLETE;
        return true;
    }

    bool recycle() {
        if (phase_ != RawPreviewComputeSlotPhase::GPU_COMPLETE) return false;
        reset();
        return true;
    }

    RawPreviewComputeSlotPhase phase() const { return phase_; }

private:
    void reset() {
        phase_ = RawPreviewComputeSlotPhase::FREE;
        generation_ = 0;
        timestampNs_ = 0;
    }

    RawPreviewComputeSlotPhase phase_ = RawPreviewComputeSlotPhase::FREE;
    std::int32_t generation_ = 0;
    std::int64_t timestampNs_ = 0;
};

} // namespace bncam::vulkan
