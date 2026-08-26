#pragma once

#include "VulkanRuntimeContracts.h"
#include "VulkanValidationCollector.h"
#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan.h>

#include <cstdint>
#include <cstdlib>
#include <utility>

namespace bncam::vulkan {

struct OwnedRuntimeHandles {
    VkInstance instance = VK_NULL_HANDLE;
    VkDebugUtilsMessengerEXT debugMessenger = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue computeQueue = VK_NULL_HANDLE;
    VkQueue previewQueue = VK_NULL_HANDLE;
    std::uint32_t computeQueueFamilyIndex = UINT32_MAX;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandPool previewCommandPool = VK_NULL_HANDLE;
    VkCommandPool rawMultiFrameCommandPool = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkPipelineCache pipelineCache = VK_NULL_HANDLE;
    VulkanAllocatorOwner allocator;

    OwnedRuntimeHandles() = default;
    OwnedRuntimeHandles(const OwnedRuntimeHandles&) = delete;
    OwnedRuntimeHandles& operator=(const OwnedRuntimeHandles&) = delete;

    OwnedRuntimeHandles(OwnedRuntimeHandles&& other) noexcept { takeFrom(other); }

    OwnedRuntimeHandles& operator=(OwnedRuntimeHandles&& other) noexcept {
        if (this == &other) return *this;
        // Raw Vulkan handles cannot be overwritten safely. Runtime code must destroy the current
        // ownership before moving another handle set into it. Failing fast prevents silent leaks.
        if (!empty()) std::abort();
        takeFrom(other);
        return *this;
    }

    bool hasInstance() const noexcept { return instance != VK_NULL_HANDLE; }
    bool hasDevice() const noexcept { return device != VK_NULL_HANDLE; }
    bool empty() const noexcept {
        return instance == VK_NULL_HANDLE &&
            debugMessenger == VK_NULL_HANDLE &&
            physicalDevice == VK_NULL_HANDLE &&
            device == VK_NULL_HANDLE &&
            computeQueue == VK_NULL_HANDLE &&
            previewQueue == VK_NULL_HANDLE &&
            commandPool == VK_NULL_HANDLE &&
            previewCommandPool == VK_NULL_HANDLE &&
            rawMultiFrameCommandPool == VK_NULL_HANDLE &&
            descriptorPool == VK_NULL_HANDLE &&
            pipelineCache == VK_NULL_HANDLE &&
            !allocator.isReady();
    }

    bool complete() const noexcept {
        return instance != VK_NULL_HANDLE &&
            physicalDevice != VK_NULL_HANDLE &&
            device != VK_NULL_HANDLE &&
            computeQueue != VK_NULL_HANDLE &&
            computeQueueFamilyIndex != UINT32_MAX &&
            commandPool != VK_NULL_HANDLE &&
            descriptorPool != VK_NULL_HANDLE &&
            pipelineCache != VK_NULL_HANDLE &&
            allocator.isReady();
    }

private:
    void takeFrom(OwnedRuntimeHandles& other) noexcept {
        instance = std::exchange(other.instance, VK_NULL_HANDLE);
        debugMessenger = std::exchange(other.debugMessenger, VK_NULL_HANDLE);
        physicalDevice = std::exchange(other.physicalDevice, VK_NULL_HANDLE);
        device = std::exchange(other.device, VK_NULL_HANDLE);
        computeQueue = std::exchange(other.computeQueue, VK_NULL_HANDLE);
        previewQueue = std::exchange(other.previewQueue, VK_NULL_HANDLE);
        computeQueueFamilyIndex = std::exchange(other.computeQueueFamilyIndex, UINT32_MAX);
        commandPool = std::exchange(other.commandPool, VK_NULL_HANDLE);
        previewCommandPool = std::exchange(other.previewCommandPool, VK_NULL_HANDLE);
        rawMultiFrameCommandPool = std::exchange(other.rawMultiFrameCommandPool, VK_NULL_HANDLE);
        descriptorPool = std::exchange(other.descriptorPool, VK_NULL_HANDLE);
        pipelineCache = std::exchange(other.pipelineCache, VK_NULL_HANDLE);
        allocator = std::move(other.allocator);
    }
};

struct BootstrapResult {
    BootstrapResult() = default;
    BootstrapResult(const BootstrapResult&) = delete;
    BootstrapResult& operator=(const BootstrapResult&) = delete;
    BootstrapResult(BootstrapResult&&) noexcept = default;
    BootstrapResult& operator=(BootstrapResult&&) noexcept = default;

    bool success = false;
    bool unavailable = true;
    OwnedRuntimeHandles handles;
    CapabilitySnapshot capabilities = CapabilitySnapshot::notScanned();
    RuntimeFailure failure;
};

/**
 * Pure implementation injection point for Phase 3A. Runtime ownership, JNI, diagnostics and
 * destruction ordering are already fixed elsewhere; the next agent implements only these calls.
 */
class VulkanRuntimeBootstrap final {
public:
    static BootstrapResult initialize(
        const RuntimeConfig& config,
        ValidationCollector& validationCollector
    ) noexcept;

    /**
     * Required destruction order:
     * 1. stop submissions / wait boundedly for in-flight work;
     * 2. destroy pipelines and future resource pools;
     * 3. destroy pipeline cache, descriptor pool and command pool;
     * 4. destroy VMA allocator;
     * 5. destroy logical device;
     * 6. destroy debug messenger;
     * 7. destroy Vulkan instance.
     */
    static RuntimeFailure destroy(OwnedRuntimeHandles& handles) noexcept;
};

}  // namespace bncam::vulkan
