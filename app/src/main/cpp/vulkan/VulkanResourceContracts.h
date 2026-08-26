#pragma once

#include "VulkanVmaForward.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include <cstdint>
#include <string>
#include <vector>

namespace bncam::vulkan {

enum class ResourceType {
    EXTERNAL_AHARDWAREBUFFER,
    VMA_OWNED_BUFFER,
    VMA_OWNED_IMAGE,
    PERSISTENT_STAGING_BUFFER,
    BORROWED_CAPTURE_RESOURCE,
};

enum class ResourceState {
    ACQUIRED,
    IMPORTING,
    VULKAN_READY,
    IN_FLIGHT,
    RELEASE_PENDING,
    RELEASED,
    FAILED,
};

enum class ImportPath {
    DIRECT_AHARDWAREBUFFER_IMPORT,
    NATIVE_LOCK_TO_PERSISTENT_STAGING,
    UNSUPPORTED,
};

const char* toString(ResourceType type) noexcept;
const char* toString(ResourceState state) noexcept;
const char* toString(ImportPath path) noexcept;

struct VulkanResourceIdentity {
    std::string resourceId;
    std::uint64_t generationId = 0;
    ResourceType type = ResourceType::BORROWED_CAPTURE_RESOURCE;
    ResourceState state = ResourceState::ACQUIRED;
    ImportPath path = ImportPath::UNSUPPORTED;
};

struct SourceImportDecision {
    std::string sourceName;                // e.g. "YUV", "RAW10", "RAW_SENSOR"
    std::uint32_t androidFormat = 0;       // e.g. AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420
    ImportPath resolvedPath = ImportPath::UNSUPPORTED;
    ImportPath fallbackPath = ImportPath::UNSUPPORTED;
    ResourceType ownership = ResourceType::EXTERNAL_AHARDWAREBUFFER;
    bool directImportEligible = false;
    bool requiredAcquireFence = true;
    bool requiredReleaseFence = true;
    std::string knownBlocker;
};

struct VulkanImportHandleContract {
    VulkanResourceIdentity identity;
    AHardwareBuffer* hardwareBuffer = nullptr;
    VkImage importedImage = VK_NULL_HANDLE;
    VkDeviceMemory importedMemory = VK_NULL_HANDLE;
    VkBuffer stagingBuffer = VK_NULL_HANDLE;
    VmaAllocation stagingAllocation = nullptr;
    std::uint64_t allocationSizeBytes = 0;
    std::uint64_t externalFormat = 0;
    bool isDedicatedAllocation = false;
    int acquireFenceFd = -1;
    int releaseFenceFd = -1;
    std::string failureReason;
};

SourceImportDecision resolveSourceImportDecision(
    const std::string& sourceName,
    std::uint32_t androidFormat,
    bool ahbExtensionSupported,
    bool externalMemorySupported,
    bool dedicatedAllocSupported,
    bool formatPropertiesQueried
);

}  // namespace bncam::vulkan
