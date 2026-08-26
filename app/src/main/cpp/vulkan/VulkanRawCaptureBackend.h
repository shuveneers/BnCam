#pragma once

#include "VulkanVmaIntegration.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include <array>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

enum class RawCaptureSourceFormat : std::uint32_t {
    RAW10 = 0u,
    RAW_SENSOR = 1u,
};

struct RawCaptureCanonicalizeRequest {
    AHardwareBuffer* hardwareBuffer = nullptr;
    RawCaptureSourceFormat sourceFormat = RawCaptureSourceFormat::RAW10;
    std::uint32_t cropLeft = 0u;
    std::uint32_t cropTop = 0u;
    std::uint32_t cropWidth = 0u;
    std::uint32_t cropHeight = 0u;
    std::uint32_t nativeWhite = 1u;
    std::uint32_t payloadWhite = 1u;
    std::array<std::uint32_t, 4> nativeBlack{0u, 0u, 0u, 0u};
    std::array<std::uint32_t, 4> payloadBlack{0u, 0u, 0u, 0u};
    std::uint64_t generationId = 0u;
};

struct RawCaptureCanonicalizeResult {
    bool attempted = false;
    bool success = false;
    std::uint32_t width = 0u;
    std::uint32_t height = 0u;
    std::uint32_t sourceRowStrideBytes = 0u;
    std::uint32_t sourcePixelStrideBytes = 0u;
    std::uint64_t sourceBytes = 0u;
    std::uint64_t cpuStagingCopyBytes = 0u;
    std::uint64_t fullFrameGpuReadbackBytes = 0u;
    bool cpuFullFrameRawUnpack = false;
    bool cpuFallbackUsed = false;
    bool submissionMayRemainInFlight = false;
    float inputStagingMs = 0.0f;
    float gpuKernelAndSyncMs = 0.0f;
    float readbackMs = 0.0f;
    float totalMs = 0.0f;
    std::string inputImportPath = "NOT_ATTEMPTED";
    std::string rawUnpackBackend = "NOT_ATTEMPTED";
    std::string failureReason = "none";
    bool residentOutputProduced = false;
    std::uint64_t residentOutputGeneration = 0u;
    std::uint64_t residentOutputBytes = 0u;
    std::vector<std::uint16_t> outputRaw16;
};

class VulkanRawCaptureBackend final {
public:
    VulkanRawCaptureBackend() = default;
    ~VulkanRawCaptureBackend() = default;

    VulkanRawCaptureBackend(const VulkanRawCaptureBackend&) = delete;
    VulkanRawCaptureBackend& operator=(const VulkanRawCaptureBackend&) = delete;

    RawCaptureCanonicalizeResult canonicalize(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue computeQueue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            const RawCaptureCanonicalizeRequest& request) noexcept;

    bool resolveResidentOutput(
            std::uint64_t generation,
            VkBuffer& buffer,
            std::uint64_t& bytes,
            std::uint32_t& width,
            std::uint32_t& height) const noexcept;

    void destroy(VkDevice device) noexcept;

private:
    struct PersistentBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    bool ensureInitializedLocked(VkDevice device, std::string& failureReason) noexcept;
    bool ensureBufferLocked(
            VmaAllocator allocator,
            std::uint64_t bytes,
            bool hostVisible,
            PersistentBuffer& target,
            std::string& failureReason) noexcept;
    void destroyLocked(VkDevice device) noexcept;

    mutable std::mutex mutex_;
    VmaAllocator allocator_ = nullptr;
    VkDevice initializedDevice_ = VK_NULL_HANDLE;
    bool initialized_ = false;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    PersistentBuffer staging_{};
    PersistentBuffer canonical_{};
    PersistentBuffer readback_{};
    std::uint64_t residentOutputGeneration_ = 0u;
    std::uint64_t residentOutputBytes_ = 0u;
    std::uint32_t residentOutputWidth_ = 0u;
    std::uint32_t residentOutputHeight_ = 0u;
    std::uint64_t generationCounter_ = 0u;
};

}  // namespace bncam::vulkan
