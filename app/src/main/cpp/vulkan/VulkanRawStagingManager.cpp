#include "VulkanRawStagingManager.h"
#include "VulkanRuntime.h"
#include "VulkanVmaIntegration.h"
#include "vk_mem_alloc.h"

#include <android/hardware_buffer.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cstring>

namespace bncam::vulkan {

VulkanRawStagingManager::~VulkanRawStagingManager() {
    // Note: Pooled VMA buffers destroyed during VulkanRuntime shutdown
}

std::uint32_t VulkanRawStagingManager::getPoolAllocationCount() const noexcept {
    return poolAllocationCount_;
}

std::uint32_t VulkanRawStagingManager::getPoolReuseCount() const noexcept {
    return poolReuseCount_;
}

std::uint32_t VulkanRawStagingManager::getNativeLockCount() const noexcept {
    return nativeLockCount_;
}

std::uint32_t VulkanRawStagingManager::getNativeUnlockCount() const noexcept {
    return nativeUnlockCount_;
}

std::uint32_t VulkanRawStagingManager::getReleaseCount() const noexcept {
    return releaseCount_;
}

RawStagingResult VulkanRawStagingManager::stageRaw10Buffer(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    AHardwareBuffer* hardwareBuffer,
    std::uint64_t generationId
) {
    RawStagingResult result{};
    result.diagnostics.identity.resourceId = "raw10-staging-" + std::to_string(generationId);
    result.diagnostics.identity.generationId = generationId;
    result.diagnostics.identity.type = ResourceType::PERSISTENT_STAGING_BUFFER;
    result.diagnostics.identity.state = ResourceState::IMPORTING;
    result.diagnostics.identity.path = ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING;
    result.diagnostics.sourceFormatName = "RAW10";
    result.diagnostics.androidFormat = 37; // AHARDWAREBUFFER_FORMAT_RAW10

    if (hardwareBuffer == nullptr || device == VK_NULL_HANDLE) {
        result.failureReason = "Invalid hardware buffer pointer or Vulkan device handle.";
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    AHardwareBuffer_acquire(hardwareBuffer);
    result.diagnostics.acquireCount++;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hardwareBuffer, &desc);
    result.diagnostics.width = desc.width;
    result.diagnostics.height = desc.height;
    result.diagnostics.rowStrideBytes = desc.stride > 0 ? (desc.stride * 10 / 8) : ((desc.width * 10 + 7) / 8);
    result.diagnostics.packedRowBytes = (desc.width * 10 + 7) / 8;
    result.diagnostics.sourceBytes = static_cast<std::uint64_t>(result.diagnostics.packedRowBytes) * desc.height;
    result.diagnostics.allocationSize = result.diagnostics.sourceBytes;

    void* nativePtr = nullptr;
    const int lockRes = AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &nativePtr);
    if (lockRes != 0 || nativePtr == nullptr) {
        AHardwareBuffer_release(hardwareBuffer);
        result.failureReason = "AHardwareBuffer_lock failed with error code " + std::to_string(lockRes);
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        nativeLockCount_++;
    }
    result.diagnostics.nativeLockCount = nativeLockCount_;

    // Acquire pooled VMA staging buffer
    StagingPoolKey key{37, desc.width, desc.height, result.diagnostics.packedRowBytes};
    PooledStagingBuffer pooledBuf{};

    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto& poolList = pool_[key];
        bool foundReusable = false;
        for (auto& item : poolList) {
            if (!item.inUse && item.capacityBytes >= result.diagnostics.sourceBytes) {
                item.inUse = true;
                pooledBuf = item;
                foundReusable = true;
                poolReuseCount_++;
                break;
            }
        }

        if (!foundReusable) {
            // Allocate new VMA staging buffer if not present in pool
            VkBufferCreateInfo bufInfo{};
            bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
            bufInfo.size = result.diagnostics.sourceBytes;
            bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

            VmaAllocationCreateInfo allocCreateInfo{};
            allocCreateInfo.usage = VMA_MEMORY_USAGE_CPU_TO_GPU;
            allocCreateInfo.flags = VMA_ALLOCATION_CREATE_MAPPED_BIT;

            VmaAllocationInfo allocInfo{};
            VkBuffer newBuf = VK_NULL_HANDLE;
            VmaAllocation newAlloc = nullptr;

            if (allocatorOwner.isReady()) {
                const VkResult vmaRes = vmaCreateBuffer(
                    allocatorOwner.handle(),
                    &bufInfo,
                    &allocCreateInfo,
                    &newBuf,
                    &newAlloc,
                    &allocInfo
                );

                if (vmaRes == VK_SUCCESS && newBuf != VK_NULL_HANDLE) {
                    pooledBuf.buffer = newBuf;
                    pooledBuf.allocation = newAlloc;
                    pooledBuf.mappedData = allocInfo.pMappedData;
                    pooledBuf.capacityBytes = result.diagnostics.sourceBytes;
                    pooledBuf.inUse = true;
                    poolList.push_back(pooledBuf);
                    poolAllocationCount_++;
                }
            }
        }
    }

    if (pooledBuf.buffer == VK_NULL_HANDLE || pooledBuf.mappedData == nullptr) {
        AHardwareBuffer_unlock(hardwareBuffer, nullptr);
        AHardwareBuffer_release(hardwareBuffer);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            nativeUnlockCount_++;
        }
        result.failureReason = "VMA staging buffer allocation or mapping failed.";
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    // Copy exact packed RAW10 bytes natively without Java pixel array round-trip
    std::memcpy(pooledBuf.mappedData, nativePtr, result.diagnostics.sourceBytes);
    result.diagnostics.copiedBytes = result.diagnostics.sourceBytes;

    AHardwareBuffer_unlock(hardwareBuffer, nullptr);
    AHardwareBuffer_release(hardwareBuffer);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        nativeUnlockCount_++;
    }
    result.diagnostics.nativeUnlockCount = nativeUnlockCount_;

    // Perform GPU transfer/barrier completion wait if command pool is valid
    if (commandPool != VK_NULL_HANDLE && computeQueue != VK_NULL_HANDLE) {
        VkCommandBufferAllocateInfo cmdAllocInfo{};
        cmdAllocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cmdAllocInfo.commandPool = commandPool;
        cmdAllocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cmdAllocInfo.commandBufferCount = 1;

        VkCommandBuffer cmdBuffer = VK_NULL_HANDLE;
        if (vkAllocateCommandBuffers(device, &cmdAllocInfo, &cmdBuffer) == VK_SUCCESS) {
            VkCommandBufferBeginInfo beginInfo{};
            beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

            vkBeginCommandBuffer(cmdBuffer, &beginInfo);

            VkBufferMemoryBarrier barrier{};
            barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.buffer = pooledBuf.buffer;
            barrier.offset = 0;
            barrier.size = VK_WHOLE_SIZE;

            vkCmdPipelineBarrier(
                cmdBuffer,
                VK_PIPELINE_STAGE_HOST_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                0, nullptr,
                1, &barrier,
                0, nullptr
            );

            vkEndCommandBuffer(cmdBuffer);

            VkFenceCreateInfo fenceInfo{};
            fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;

            VkFence fence = VK_NULL_HANDLE;
            if (vkCreateFence(device, &fenceInfo, nullptr, &fence) == VK_SUCCESS) {
                VkSubmitInfo submitInfo{};
                submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                submitInfo.commandBufferCount = 1;
                submitInfo.pCommandBuffers = &cmdBuffer;

                if (vkQueueSubmit(computeQueue, 1, &submitInfo, fence) == VK_SUCCESS) {
                    if (vkWaitForFences(device, 1, &fence, VK_TRUE, 1'500'000'000ull) == VK_SUCCESS) {
                        result.diagnostics.gpuCompletion = true;
                        result.diagnostics.submissionIdentity = "raw10-staging-sub-1";
                    } else {
                        VulkanRuntime::instance().markGpuStalled("RAW10_STAGING");
                        vkDestroyFence(device, fence, nullptr);
                        vkFreeCommandBuffers(device, commandPool, 1, &cmdBuffer);
                        result.success = false;
                        result.failureReason = "GPU_STALLED";
                        result.diagnostics.failureReason = result.failureReason;
                        return result;
                    }
                }
                vkDestroyFence(device, fence, nullptr);
            }
            vkFreeCommandBuffers(device, commandPool, 1, &cmdBuffer);
        }
    }

    result.buffer = pooledBuf.buffer;
    result.allocation = pooledBuf.allocation;
    result.mappedData = pooledBuf.mappedData;
    result.byteSize = result.diagnostics.sourceBytes;

    result.diagnostics.poolIdentity = "vma-staging-pool-raw10";
    result.diagnostics.poolAllocationCount = poolAllocationCount_;
    result.diagnostics.poolReuseCount = poolReuseCount_;
    result.diagnostics.identity.state = ResourceState::VULKAN_READY;
    result.success = true;
    result.diagnostics.success = true;
    return result;
}

RawStagingResult VulkanRawStagingManager::stageRawSensorMemory(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    VkQueue computeQueue,
    VkCommandPool commandPool,
    const void* raw16MemoryPtr,
    std::uint32_t width,
    std::uint32_t height,
    std::uint32_t rowStrideBytes,
    std::uint64_t generationId
) {
    RawStagingResult result{};
    result.diagnostics.identity.resourceId = "rawsensor-staging-" + std::to_string(generationId);
    result.diagnostics.identity.generationId = generationId;
    result.diagnostics.identity.type = ResourceType::PERSISTENT_STAGING_BUFFER;
    result.diagnostics.identity.state = ResourceState::IMPORTING;
    result.diagnostics.identity.path = ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING;
    result.diagnostics.sourceFormatName = "RAW_SENSOR";
    result.diagnostics.androidFormat = 32; // AHARDWAREBUFFER_FORMAT_RAW16

    if (raw16MemoryPtr == nullptr || device == VK_NULL_HANDLE || width == 0 || height == 0) {
        result.failureReason = "Invalid RAW_SENSOR memory pointer or dimensions.";
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    result.diagnostics.width = width;
    result.diagnostics.height = height;
    result.diagnostics.rowStrideBytes = rowStrideBytes > 0 ? rowStrideBytes : (width * 2);
    result.diagnostics.packedRowBytes = width * 2;
    result.diagnostics.sourceBytes = static_cast<std::uint64_t>(result.diagnostics.rowStrideBytes) * height;
    result.diagnostics.allocationSize = result.diagnostics.sourceBytes;

    StagingPoolKey key{32, width, height, result.diagnostics.rowStrideBytes};
    PooledStagingBuffer pooledBuf{};

    {
        std::lock_guard<std::mutex> lock(mutex_);
        auto& poolList = pool_[key];
        bool foundReusable = false;
        for (auto& item : poolList) {
            if (!item.inUse && item.capacityBytes >= result.diagnostics.sourceBytes) {
                item.inUse = true;
                pooledBuf = item;
                foundReusable = true;
                poolReuseCount_++;
                break;
            }
        }

        if (!foundReusable) {
            VkBufferCreateInfo bufInfo{};
            bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
            bufInfo.size = result.diagnostics.sourceBytes;
            bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

            VmaAllocationCreateInfo allocCreateInfo{};
            allocCreateInfo.usage = VMA_MEMORY_USAGE_CPU_TO_GPU;
            allocCreateInfo.flags = VMA_ALLOCATION_CREATE_MAPPED_BIT;

            VmaAllocationInfo allocInfo{};
            VkBuffer newBuf = VK_NULL_HANDLE;
            VmaAllocation newAlloc = nullptr;

            if (allocatorOwner.isReady()) {
                const VkResult vmaRes = vmaCreateBuffer(
                    allocatorOwner.handle(),
                    &bufInfo,
                    &allocCreateInfo,
                    &newBuf,
                    &newAlloc,
                    &allocInfo
                );

                if (vmaRes == VK_SUCCESS && newBuf != VK_NULL_HANDLE) {
                    pooledBuf.buffer = newBuf;
                    pooledBuf.allocation = newAlloc;
                    pooledBuf.mappedData = allocInfo.pMappedData;
                    pooledBuf.capacityBytes = result.diagnostics.sourceBytes;
                    pooledBuf.inUse = true;
                    poolList.push_back(pooledBuf);
                    poolAllocationCount_++;
                }
            }
        }
    }

    if (pooledBuf.buffer == VK_NULL_HANDLE || pooledBuf.mappedData == nullptr) {
        result.failureReason = "VMA staging buffer allocation failed for RAW_SENSOR.";
        result.diagnostics.failureReason = result.failureReason;
        result.diagnostics.identity.state = ResourceState::FAILED;
        return result;
    }

    std::memcpy(pooledBuf.mappedData, raw16MemoryPtr, result.diagnostics.sourceBytes);
    result.diagnostics.copiedBytes = result.diagnostics.sourceBytes;

    if (commandPool != VK_NULL_HANDLE && computeQueue != VK_NULL_HANDLE) {
        VkCommandBufferAllocateInfo cmdAllocInfo{};
        cmdAllocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cmdAllocInfo.commandPool = commandPool;
        cmdAllocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cmdAllocInfo.commandBufferCount = 1;

        VkCommandBuffer cmdBuffer = VK_NULL_HANDLE;
        if (vkAllocateCommandBuffers(device, &cmdAllocInfo, &cmdBuffer) == VK_SUCCESS) {
            VkCommandBufferBeginInfo beginInfo{};
            beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

            vkBeginCommandBuffer(cmdBuffer, &beginInfo);

            VkBufferMemoryBarrier barrier{};
            barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.buffer = pooledBuf.buffer;
            barrier.offset = 0;
            barrier.size = VK_WHOLE_SIZE;

            vkCmdPipelineBarrier(
                cmdBuffer,
                VK_PIPELINE_STAGE_HOST_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                0, nullptr,
                1, &barrier,
                0, nullptr
            );

            vkEndCommandBuffer(cmdBuffer);

            VkFenceCreateInfo fenceInfo{};
            fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;

            VkFence fence = VK_NULL_HANDLE;
            if (vkCreateFence(device, &fenceInfo, nullptr, &fence) == VK_SUCCESS) {
                VkSubmitInfo submitInfo{};
                submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
                submitInfo.commandBufferCount = 1;
                submitInfo.pCommandBuffers = &cmdBuffer;

                if (vkQueueSubmit(computeQueue, 1, &submitInfo, fence) == VK_SUCCESS) {
                    if (vkWaitForFences(device, 1, &fence, VK_TRUE, 1'500'000'000ull) == VK_SUCCESS) {
                        result.diagnostics.gpuCompletion = true;
                        result.diagnostics.submissionIdentity = "rawsensor-staging-sub-1";
                    } else {
                        VulkanRuntime::instance().markGpuStalled("RAWSENSOR_STAGING");
                        vkDestroyFence(device, fence, nullptr);
                        vkFreeCommandBuffers(device, commandPool, 1, &cmdBuffer);
                        result.success = false;
                        result.failureReason = "GPU_STALLED";
                        result.diagnostics.failureReason = result.failureReason;
                        return result;
                    }
                }
                vkDestroyFence(device, fence, nullptr);
            }
            vkFreeCommandBuffers(device, commandPool, 1, &cmdBuffer);
        }
    }

    result.buffer = pooledBuf.buffer;
    result.allocation = pooledBuf.allocation;
    result.mappedData = pooledBuf.mappedData;
    result.byteSize = result.diagnostics.sourceBytes;

    result.diagnostics.poolIdentity = "vma-staging-pool-rawsensor";
    result.diagnostics.poolAllocationCount = poolAllocationCount_;
    result.diagnostics.poolReuseCount = poolReuseCount_;
    result.diagnostics.identity.state = ResourceState::VULKAN_READY;
    result.success = true;
    result.diagnostics.success = true;
    return result;
}

void VulkanRawStagingManager::releaseStagingResource(
    VkDevice device,
    VulkanAllocatorOwner& allocatorOwner,
    RawStagingResult& resource
) {
    if (resource.buffer == VK_NULL_HANDLE) return;

    std::lock_guard<std::mutex> lock(mutex_);
    for (auto& pair : pool_) {
        for (auto& item : pair.second) {
            if (item.buffer == resource.buffer) {
                item.inUse = false;
                break;
            }
        }
    }

    releaseCount_++;
    resource.diagnostics.releaseCount = releaseCount_;
    resource.diagnostics.identity.state = ResourceState::RELEASED;
}

}  // namespace bncam::vulkan
