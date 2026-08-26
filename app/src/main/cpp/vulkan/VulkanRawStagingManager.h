#pragma once

#include "VulkanResourceContracts.h"
#include "VulkanVmaIntegration.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include <cstdint>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {

struct RawStagingDiagnostics {
    VulkanResourceIdentity identity;
    std::string sourceFormatName;         // "RAW10" or "RAW_SENSOR"
    std::uint32_t androidFormat = 0;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t rowStrideBytes = 0;
    std::uint32_t packedRowBytes = 0;
    std::uint64_t sourceBytes = 0;
    std::uint64_t copiedBytes = 0;
    std::uint64_t allocationSize = 0;
    ImportPath requestedPath = ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING;
    ImportPath resolvedPath = ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING;
    std::string poolIdentity;
    std::uint32_t poolAllocationCount = 0;
    std::uint32_t poolReuseCount = 0;
    std::uint32_t nativeLockCount = 0;
    std::uint32_t nativeUnlockCount = 0;
    std::uint32_t acquireCount = 0;
    std::uint32_t releaseCount = 0;
    std::string submissionIdentity;
    bool gpuCompletion = false;
    std::string failureReason;
    bool success = false;
};

struct RawStagingResult {
    bool success = false;
    RawStagingDiagnostics diagnostics;
    VkBuffer buffer = VK_NULL_HANDLE;
    VmaAllocation allocation = nullptr;
    void* mappedData = nullptr;
    std::uint64_t byteSize = 0;
    std::string failureReason;
};

class VulkanRawStagingManager {
public:
    VulkanRawStagingManager() = default;
    ~VulkanRawStagingManager();

    VulkanRawStagingManager(const VulkanRawStagingManager&) = delete;
    VulkanRawStagingManager& operator=(const VulkanRawStagingManager&) = delete;

    RawStagingResult stageRaw10Buffer(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        AHardwareBuffer* hardwareBuffer,
        std::uint64_t generationId
    );

    RawStagingResult stageRawSensorMemory(
        VkDevice device,
        VulkanAllocatorOwner& allocatorOwner,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        const void* raw16MemoryPtr,
        std::uint32_t width,
        std::uint32_t height,
        std::uint32_t rowStrideBytes,
        std::uint64_t generationId
    );

    void releaseStagingResource(VkDevice device, VulkanAllocatorOwner& allocatorOwner, RawStagingResult& resource);

    std::uint32_t getPoolAllocationCount() const noexcept;
    std::uint32_t getPoolReuseCount() const noexcept;
    std::uint32_t getNativeLockCount() const noexcept;
    std::uint32_t getNativeUnlockCount() const noexcept;
    std::uint32_t getReleaseCount() const noexcept;

private:
    std::mutex mutex_;
    std::uint32_t poolAllocationCount_ = 0;
    std::uint32_t poolReuseCount_ = 0;
    std::uint32_t nativeLockCount_ = 0;
    std::uint32_t nativeUnlockCount_ = 0;
    std::uint32_t releaseCount_ = 0;

    struct StagingPoolKey {
        std::uint32_t androidFormat;
        std::uint32_t width;
        std::uint32_t height;
        std::uint32_t rowStrideBytes;
        bool operator<(const StagingPoolKey& other) const {
            if (androidFormat != other.androidFormat) return androidFormat < other.androidFormat;
            if (width != other.width) return width < other.width;
            if (height != other.height) return height < other.height;
            return rowStrideBytes < other.rowStrideBytes;
        }
    };

    struct PooledStagingBuffer {
        VkBuffer buffer = VK_NULL_HANDLE;
        VmaAllocation allocation = nullptr;
        void* mappedData = nullptr;
        std::uint64_t capacityBytes = 0;
        bool inUse = false;
    };

    std::map<StagingPoolKey, std::vector<PooledStagingBuffer>> pool_;
};

}  // namespace bncam::vulkan
