#pragma once

#include "../SpectraNeuralProductionPolicy.h"
#include "../SpectraNeuralEffectTelemetry.h"
#include "VulkanNeuralRawDenoiseBackend.h"
#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <array>
#include <cstdint>
#include <mutex>
#include <string>

namespace bncam::vulkan::neural {

struct NeuralProductionGpuRequest {
    VkBuffer normalizedBayerInput = VK_NULL_HANDLE;
    std::uint64_t normalizedBayerBytes = 0u;
    bncam::spectra::neural::NeuralProductionPreparedContext prepared{};
    bncam::spectra::neural::SpectraNeuralConditioningConfig conditioningConfig{};
    bncam::spectra::neural::NeuralRuntimeReadiness runtimeReadiness{};

    // Compact Camera2 lens-shading metadata, never full-frame pixels.
    const float* remainingLscMap = nullptr;
    std::uint32_t remainingLscWidth = 0u;
    std::uint32_t remainingLscHeight = 0u;
    std::uint32_t remainingLscChannels = 0u;
    std::uint64_t remainingLscGeneration = 0u;

    std::uint64_t generationId = 0u;
};

struct NeuralProductionGpuResult {
    bool attempted = false;
    bool success = false;
    bool neuralPublished = false;
    bool originalPublished = true;
    bncam::spectra::neural::NeuralBypassReason bypassReason =
            bncam::spectra::neural::NeuralBypassReason::None;
    bncam::spectra::neural::NeuralBackendFailureCode failureCode =
            bncam::spectra::neural::NeuralBackendFailureCode::None;

    VkBuffer downstreamBayerBuffer = VK_NULL_HANDLE;
    std::uint64_t downstreamBayerBytes = 0u;
    std::uint64_t residentOutputGeneration = 0u;

    VkBuffer posteriorVariancePacked = VK_NULL_HANDLE;
    // GPU-reduced posterior mean variance in canonical R/G1/G2/B order. Phase 6 shares
    // one compact 13xvec4 summary per 32x32 packed region with effect telemetry; the CPU
    // never sees the full posterior or full neural residual image.
    bool posteriorSummaryReady = false;
    std::array<float, 4> posteriorMeanVarianceCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::uint64_t compactPosteriorReadbackBytes = 0u;

    bool effectTelemetryReady = false;
    bncam::spectra::neural::SpectraNeuralEffectTelemetry effectTelemetry{};
    std::uint64_t compactEffectReadbackBytes = 0u;
    float effectSummaryMs = 0.0f;
    std::string effectTelemetryStatus = "NOT_RUN";
    VkBuffer originalSaturationMaskPacked = VK_NULL_HANDLE;
    VkBuffer originalHeadroomPacked = VK_NULL_HANDLE;
    std::uint32_t packedWidth = 0u;
    std::uint32_t packedHeight = 0u;

    std::uint32_t neuralKernelDispatches = 0u;
    std::uint32_t bridgeKernelDispatches = 0u;
    std::uint64_t compactMetadataUploadBytes = 0u;
    std::uint64_t persistentGpuBytes = 0u;
    std::uint64_t fullFrameCpuReadbackBytes = 0u;
    bool cpuFallbackUsed = false;
    float totalWallMs = 0.0f;
    std::string status = "NOT_RUN";
};

class VulkanNeuralRawProductionBridge final {
public:
    VulkanNeuralRawProductionBridge() = default;
    ~VulkanNeuralRawProductionBridge() = default;
    VulkanNeuralRawProductionBridge(const VulkanNeuralRawProductionBridge&) = delete;
    VulkanNeuralRawProductionBridge& operator=(const VulkanNeuralRawProductionBridge&) = delete;

    NeuralProductionGpuResult execute(
            VkDevice device,
            VkQueue queue,
            VkCommandPool commandPool,
            VulkanAllocatorOwner& allocatorOwner,
            std::mutex& queueMutex,
            VulkanNeuralRawDenoiseBackend& neuralBackend,
            const NeuralProductionGpuRequest& request) noexcept;

    void destroy(VkDevice device) noexcept;

private:
    struct Buffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mapped = nullptr;
        std::uint64_t capacityBytes = 0u;
    };

    bool initializeLocked(VkDevice device, VkCommandPool commandPool, std::string& failure) noexcept;
    bool ensureBufferLocked(VmaAllocator allocator, std::uint64_t bytes, bool hostVisible,
                            Buffer& buffer, std::string& failure) noexcept;
    bool uploadRemainingLscLocked(VmaAllocator allocator, const NeuralProductionGpuRequest& request,
                                  std::uint64_t& uploadedBytes, std::string& failure) noexcept;
    bool dispatchBridgeLocked(VkDevice device, VkQueue queue, std::mutex& queueMutex,
                              VkBuffer input, VkBuffer output, VkBuffer auxiliary0, VkBuffer auxiliary1,
                              const bncam::spectra::neural::CanonicalBayerPackContract& cfa,
                              std::uint32_t rawWidth, std::uint32_t rawHeight,
                              std::uint32_t mode,
                              const std::array<float, 4>& shotS,
                              const std::array<float, 4>& readO,
                              std::string& failure) noexcept;
    void freeBufferLocked(Buffer& buffer) noexcept;
    void destroyLocked(VkDevice device) noexcept;

    std::mutex mutex_;
    VkDevice device_ = VK_NULL_HANDLE;
    VmaAllocator allocator_ = nullptr;
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkShaderModule shaderModule_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;

    Buffer packedInput_{};
    Buffer cleanPacked_{};
    Buffer posteriorPacked_{};
    Buffer effectSummary_{};
    Buffer saturationMask_{};
    Buffer headroom_{};
    Buffer downstreamMosaic_{};
    Buffer lscMap_{};
    std::uint64_t lscGeneration_ = 0u;
    std::uint64_t generationCounter_ = 0u;
};

} // namespace bncam::vulkan::neural
