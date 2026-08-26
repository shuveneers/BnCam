#pragma once

#include <cstdint>
#include <mutex>
#include <string>

namespace bncam::vulkan {

enum class VulkanLifecycleState : std::int32_t {
    UNINITIALIZED = 0,
    INITIALIZING = 1,
    READY = 2,
    CAPTURE_ACTIVE = 3,
    PROCESSING = 4,
    DRAINING = 5,
    SUSPENDED = 6,
    RECOVERING = 7,
    FAILED = 8,
    SHUTDOWN = 9
};

inline const char* toString(VulkanLifecycleState state) noexcept {
    switch (state) {
        case VulkanLifecycleState::UNINITIALIZED: return "UNINITIALIZED";
        case VulkanLifecycleState::INITIALIZING: return "INITIALIZING";
        case VulkanLifecycleState::READY: return "READY";
        case VulkanLifecycleState::CAPTURE_ACTIVE: return "CAPTURE_ACTIVE";
        case VulkanLifecycleState::PROCESSING: return "PROCESSING";
        case VulkanLifecycleState::DRAINING: return "DRAINING";
        case VulkanLifecycleState::SUSPENDED: return "SUSPENDED";
        case VulkanLifecycleState::RECOVERING: return "RECOVERING";
        case VulkanLifecycleState::FAILED: return "FAILED";
        case VulkanLifecycleState::SHUTDOWN: return "SHUTDOWN";
    }
    return "UNINITIALIZED";
}

struct ExplicitVulkanFailure {
    std::string captureId;
    std::uint64_t generationId = 0;
    std::string stage;
    std::string operation;
    std::int32_t vkResultCode = 0;
    std::string resourceIdentity;
    std::string runtimeIdentity;
    std::string outputPolicy;
    std::string jpegResult = "FAILED";
    std::string dngResult = "PRESERVED_IF_PERMITTED";
    std::string recoveryAction;
};

class VulkanLifecycleManager {
public:
    VulkanLifecycleManager() = default;
    ~VulkanLifecycleManager() = default;

    bool transitionTo(VulkanLifecycleState newState) {
        std::lock_guard<std::mutex> lock(mutex_);
        state_ = newState;
        return true;
    }

    VulkanLifecycleState getState() const noexcept {
        return state_;
    }

    bool isReady() const noexcept {
        return state_ == VulkanLifecycleState::READY || state_ == VulkanLifecycleState::CAPTURE_ACTIVE || state_ == VulkanLifecycleState::PROCESSING;
    }

    void recordDeviceLossFailure(const std::string& captureId, std::uint64_t generationId, const std::string& stage) {
        std::lock_guard<std::mutex> lock(mutex_);
        state_ = VulkanLifecycleState::RECOVERING;
        lastFailure_.captureId = captureId;
        lastFailure_.generationId = generationId;
        lastFailure_.stage = stage;
        lastFailure_.operation = "DeviceLossRecovery";
        lastFailure_.vkResultCode = -4; // VK_ERROR_DEVICE_LOST
        lastFailure_.recoveryAction = "RecreateVulkanRuntimeOnce";
        recoveryCount_++;
    }

    std::uint32_t getRecoveryCount() const noexcept { return recoveryCount_; }
    const ExplicitVulkanFailure& getLastFailure() const noexcept { return lastFailure_; }

private:
    std::mutex mutex_;
    VulkanLifecycleState state_ = VulkanLifecycleState::UNINITIALIZED;
    ExplicitVulkanFailure lastFailure_{};
    std::uint32_t recoveryCount_ = 0;
};

}  // namespace bncam::vulkan
