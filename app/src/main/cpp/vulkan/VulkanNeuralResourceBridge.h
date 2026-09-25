#pragma once

#include "../NeuralRawDenoiseBackend.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <cstdint>
#include <string>

namespace bncam::vulkan::neural {

enum class NeuralGpuInputPath : std::uint8_t {
    None = 0,
    ResidentVulkanBuffer = 1,
    DirectAhbBlobBuffer = 2,
    GpuStagingFallback = 3,
};

struct NeuralResolvedGpuInput {
    bool valid = false;
    NeuralGpuInputPath path = NeuralGpuInputPath::None;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory importedMemory = VK_NULL_HANDLE;
    AHardwareBuffer* acquiredAhb = nullptr;
    VkSemaphore waitSemaphore = VK_NULL_HANDLE; // borrowed, never destroyed here
    std::uint64_t byteOffset = 0;
    std::uint64_t accessibleBytes = 0;
    bool dedicatedAllocation = false;
    bool dedicatedAllocationRequired = false;
    bool externalImportCapabilityVerified = false;
    std::string failureReason;
};

class VulkanNeuralResourceBridge final {
public:
    static NeuralResolvedGpuInput resolveInput(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            const bncam::spectra::neural::NeuralResourceView& primary,
            const bncam::spectra::neural::NeuralResourceView& gpuFallback) noexcept;

    static void releaseImported(VkDevice device, NeuralResolvedGpuInput& input) noexcept;

private:
    static NeuralResolvedGpuInput importAhbBlob(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            AHardwareBuffer* ahb,
            const bncam::spectra::neural::NeuralResourceView& view) noexcept;
};

} // namespace bncam::vulkan::neural
