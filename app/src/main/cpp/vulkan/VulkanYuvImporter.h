#pragma once

#include "VulkanResourceContracts.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

struct YuvImportDiagnostics {
    VulkanResourceIdentity identity;
    std::uint32_t ahbWidth = 0;
    std::uint32_t ahbHeight = 0;
    std::uint32_t ahbLayers = 0;
    std::uint32_t ahbFormat = 0;
    std::uint64_t ahbUsage = 0;
    std::uint64_t allocationSize = 0;
    std::uint32_t memoryTypeBits = 0;
    VkFormat vkFormat = VK_FORMAT_UNDEFINED;
    std::uint64_t externalFormat = 0;
    std::uint64_t formatFeatures = 0;
    bool dedicatedAllocationRequired = false;
    bool ycbcrConversionRequired = false;
    ImportPath requestedPath = ImportPath::DIRECT_AHARDWAREBUFFER_IMPORT;
    ImportPath resolvedPath = ImportPath::UNSUPPORTED;
    bool acquireFenceProvided = false;
    bool acquireFenceImported = false;
    bool acquireFenceConsumed = false;
    bool releaseFenceRequired = false;
    bool releaseFenceExported = false;
    std::string submissionIdentity;
    std::uint32_t poolAllocationCount = 0;
    std::uint32_t poolReuseCount = 0;
    std::uint32_t releaseCount = 0;
    std::string failureReason;
    bool success = false;
};

struct YuvImportResult {
    bool success = false;
    YuvImportDiagnostics diagnostics;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    VkSamplerYcbcrConversion ycbcrConversion = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    std::string failureReason;
};

class VulkanYuvImporter {
public:
    VulkanYuvImporter() = default;
    ~VulkanYuvImporter();

    VulkanYuvImporter(const VulkanYuvImporter&) = delete;
    VulkanYuvImporter& operator=(const VulkanYuvImporter&) = delete;

    YuvImportResult importYuvBuffer(
        VkInstance instance,
        VkDevice device,
        VkPhysicalDevice physicalDevice,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        AHardwareBuffer* hardwareBuffer,
        std::uint64_t generationId,
        int acquireFenceFd = -1
    );

    void releaseYuvResource(VkDevice device, YuvImportResult& resource);

    std::uint32_t getPoolAllocationCount() const noexcept;
    std::uint32_t getPoolReuseCount() const noexcept;
    std::uint32_t getReleaseCount() const noexcept;

private:
    std::mutex mutex_;
    std::uint32_t poolAllocationCount_ = 0;
    std::uint32_t poolReuseCount_ = 0;
    std::uint32_t releaseCount_ = 0;

    struct ConversionCacheKey {
        std::uint64_t externalFormat;
        std::uint32_t vkFormat;
        bool operator<(const ConversionCacheKey& other) const {
            if (externalFormat != other.externalFormat) return externalFormat < other.externalFormat;
            return vkFormat < other.vkFormat;
        }
    };

    std::map<ConversionCacheKey, VkSamplerYcbcrConversion> conversionCache_;
    std::map<ConversionCacheKey, VkSampler> samplerCache_;
};

}  // namespace bncam::vulkan
