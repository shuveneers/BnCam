#include "VulkanSpectraOpponentBackend.h"
#include "VulkanPipelineCacheRegistry.h"
#include "VulkanRuntime.h"
#include "VulkanShaderBytecode.h"
#include "vk_mem_alloc.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif

namespace bncam::vulkan {
namespace {

using Clock = std::chrono::steady_clock;

float elapsedMs(Clock::time_point started) {
    return static_cast<float>(std::chrono::duration<double, std::milli>(
            Clock::now() - started
    ).count());
}

struct alignas(16) OpponentPushConstants {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t padding0 = 0;
    std::uint32_t padding1 = 0;
    // GLSL mat3 push constants are column-major with MatrixStride 16.
    std::array<float, 12> matrixColumns{
            0.2126f, 1.0f, 0.0f, 0.0f,
            0.7152f, -1.0f, -1.0f, 0.0f,
            0.0722f, 0.0f, 1.0f, 0.0f
    };
};
static_assert(sizeof(OpponentPushConstants) == 64u, "Opponent push constant layout must match SPIR-V");

struct BufferAllocation {
    VkBuffer buffer = VK_NULL_HANDLE;
    VmaAllocation allocation = nullptr;
    VmaAllocationInfo info{};
};

void destroyBuffer(VmaAllocator allocator, BufferAllocation& buffer) {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator != nullptr && buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator, buffer.buffer, buffer.allocation);
    }
#else
    (void) allocator;
#endif
    buffer = {};
}

bool createMappedBuffer(
        VmaAllocator allocator,
        VkDeviceSize bytes,
        VmaAllocationCreateFlags hostAccess,
        BufferAllocation& out,
        std::string& failureReason
) {
#if BNCAM_VMA_HEADER_AVAILABLE
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = bytes;
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo allocationInfo{};
    allocationInfo.usage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST;
    // This backend maps and fills/reads the storage buffers directly. Do not allow
    // VMA to substitute device-local transfer-only memory unless a staging path exists.
    allocationInfo.flags = hostAccess | VMA_ALLOCATION_CREATE_MAPPED_BIT;
    const VkResult result = vmaCreateBuffer(
            allocator,
            &bufferInfo,
            &allocationInfo,
            &out.buffer,
            &out.allocation,
            &out.info
    );
    if (result != VK_SUCCESS || out.buffer == VK_NULL_HANDLE ||
        out.allocation == nullptr || out.info.pMappedData == nullptr) {
        failureReason = "vmaCreateBuffer_mapped_storage_failed_vk_result_" +
                std::to_string(result);
        destroyBuffer(allocator, out);
        return false;
    }
    return true;
#else
    (void) allocator;
    (void) bytes;
    (void) hostAccess;
    (void) out;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#endif
}

} // namespace

bool VulkanSpectraOpponentBackend::productionKernelConnected() const noexcept {
    return spectraOpponentFeaturesSpirvAvailable();
}

bool VulkanSpectraOpponentBackend::pipelineInitialized() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return initialized_;
}

void VulkanSpectraOpponentBackend::destroyPersistentBuffersLocked() noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (persistentAllocator_ != nullptr) {
        if (persistentOutputBuffer_ != VK_NULL_HANDLE &&
            persistentOutputAllocation_ != nullptr) {
            vmaDestroyBuffer(
                    persistentAllocator_,
                    persistentOutputBuffer_,
                    persistentOutputAllocation_
            );
        }
        if (persistentInputBuffer_ != VK_NULL_HANDLE &&
            persistentInputAllocation_ != nullptr) {
            vmaDestroyBuffer(
                    persistentAllocator_,
                    persistentInputBuffer_,
                    persistentInputAllocation_
            );
        }
    }
#endif
    persistentAllocator_ = nullptr;
    persistentInputBuffer_ = VK_NULL_HANDLE;
    persistentInputAllocation_ = nullptr;
    persistentInputMapped_ = nullptr;
    persistentOutputBuffer_ = VK_NULL_HANDLE;
    persistentOutputAllocation_ = nullptr;
    persistentOutputMapped_ = nullptr;
    persistentBufferCapacityBytes_ = 0u;
}

bool VulkanSpectraOpponentBackend::ensurePersistentBuffersLocked(
        VmaAllocator allocator,
        std::uint64_t requiredBytes,
        SpectraOpponentExecutionResult& result,
        std::string& failureReason
) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void) allocator;
    (void) requiredBytes;
    (void) result;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || requiredBytes == 0u ||
        requiredBytes > static_cast<std::uint64_t>(
                std::numeric_limits<VkDeviceSize>::max()
        )) {
        failureReason = "INVALID_PERSISTENT_BUFFER_REQUEST";
        return false;
    }
    const bool reusable = persistentAllocator_ == allocator &&
            persistentInputBuffer_ != VK_NULL_HANDLE &&
            persistentOutputBuffer_ != VK_NULL_HANDLE &&
            persistentInputAllocation_ != nullptr &&
            persistentOutputAllocation_ != nullptr &&
            persistentInputMapped_ != nullptr &&
            persistentOutputMapped_ != nullptr &&
            persistentBufferCapacityBytes_ >= requiredBytes;
    if (reusable) {
        result.persistentBufferReuseHit = true;
        result.persistentBufferCapacityBytes = persistentBufferCapacityBytes_;
        result.persistentResidentBytes = persistentBufferCapacityBytes_ * 2u;
        result.persistentAllocationGeneration = persistentAllocationGeneration_;
        return true;
    }

    destroyPersistentBuffersLocked();
    BufferAllocation input{};
    BufferAllocation output{};
    if (!createMappedBuffer(
            allocator,
            static_cast<VkDeviceSize>(requiredBytes),
            VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT,
            input,
            failureReason
    ) || !createMappedBuffer(
            allocator,
            static_cast<VkDeviceSize>(requiredBytes),
            VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT,
            output,
            failureReason
    )) {
        destroyBuffer(allocator, output);
        destroyBuffer(allocator, input);
        destroyPersistentBuffersLocked();
        return false;
    }

    persistentAllocator_ = allocator;
    persistentInputBuffer_ = input.buffer;
    persistentInputAllocation_ = input.allocation;
    persistentInputMapped_ = input.info.pMappedData;
    persistentOutputBuffer_ = output.buffer;
    persistentOutputAllocation_ = output.allocation;
    persistentOutputMapped_ = output.info.pMappedData;
    persistentBufferCapacityBytes_ = requiredBytes;
    persistentAllocationGeneration_++;
    result.persistentBufferReallocated = true;
    result.persistentBufferCapacityBytes = persistentBufferCapacityBytes_;
    result.persistentResidentBytes = persistentBufferCapacityBytes_ * 2u;
    result.persistentAllocationGeneration = persistentAllocationGeneration_;
    failureReason.clear();
    return true;
#endif
}

bool VulkanSpectraOpponentBackend::initializeLocked(
        VkDevice device,
        std::string& failureReason
) noexcept {
    if (initialized_) return true;
    if (device == VK_NULL_HANDLE) {
        failureReason = "INVALID_VULKAN_DEVICE";
        return false;
    }
    const auto& spirv = getSpectraOpponentFeaturesSpirv();
    if (spirv.empty()) {
        failureReason = "SPECTRA_OPPONENT_SPIRV_PATCH_CONTRACT_FAILED";
        return false;
    }

    VkDescriptorSetLayoutBinding bindings[2]{};
    for (std::uint32_t index = 0; index < 2u; ++index) {
        bindings[index].binding = index;
        bindings[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[index].descriptorCount = 1;
        bindings[index].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo descriptorLayoutInfo{};
    descriptorLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorLayoutInfo.bindingCount = 2u;
    descriptorLayoutInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(
            device, &descriptorLayoutInfo, nullptr, &descriptorSetLayout_
    ) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorSetLayout_failed";
        destroyLocked(device);
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0u;
    pushRange.size = sizeof(OpponentPushConstants);
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1u;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout_;
    pipelineLayoutInfo.pushConstantRangeCount = 1u;
    pipelineLayoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(
            device, &pipelineLayoutInfo, nullptr, &pipelineLayout_
    ) != VK_SUCCESS) {
        failureReason = "vkCreatePipelineLayout_failed";
        destroyLocked(device);
        return false;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_spectra_opponent_failed";
        destroyLocked(device);
        return false;
    }

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    pipelineInfo.stage.module = shaderModule_;
    pipelineInfo.stage.pName = "main";
    pipelineInfo.layout = pipelineLayout_;
    if (VulkanPipelineCacheRegistry::createComputePipelines(device, 1u, &pipelineInfo, nullptr, &pipeline_
    ) != VK_SUCCESS) {
        failureReason = "vkCreateComputePipelines_spectra_opponent_failed";
        destroyLocked(device);
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 2u;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1u;
    poolInfo.poolSizeCount = 1u;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorPool_spectra_opponent_failed";
        destroyLocked(device);
        return false;
    }

    VkDescriptorSetAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocateInfo.descriptorPool = descriptorPool_;
    allocateInfo.descriptorSetCount = 1u;
    allocateInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocateInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "vkAllocateDescriptorSets_spectra_opponent_failed";
        destroyLocked(device);
        return false;
    }

    initialized_ = true;
    failureReason.clear();
    return true;
}

void VulkanSpectraOpponentBackend::destroyLocked(VkDevice device) noexcept {
    destroyPersistentBuffersLocked();
    if (device != VK_NULL_HANDLE) {
        if (descriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, descriptorPool_, nullptr);
        }
        if (pipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device, pipeline_, nullptr);
        }
        if (shaderModule_ != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, shaderModule_, nullptr);
        }
        if (pipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, pipelineLayout_, nullptr);
        }
        if (descriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout_, nullptr);
        }
    }
    descriptorSetLayout_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    shaderModule_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    descriptorPool_ = VK_NULL_HANDLE;
    descriptorSet_ = VK_NULL_HANDLE;
    initialized_ = false;
}

void VulkanSpectraOpponentBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

SpectraOpponentExecutionResult VulkanSpectraOpponentBackend::execute(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const SpectraOpponentExecutionRequest& request
) noexcept {
    SpectraOpponentExecutionResult result{};
    result.attempted = true;
    const auto totalStarted = Clock::now();
    std::lock_guard<std::mutex> lock(mutex_);

    if (physicalDevice == VK_NULL_HANDLE || device == VK_NULL_HANDLE ||
        computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE ||
        !allocatorOwner.isReady()) {
        result.failureReason = "VULKAN_RUNTIME_HANDLES_OR_VMA_NOT_READY";
        result.status = "FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    if (request.rgbData == nullptr || request.width == 0u || request.height == 0u ||
        request.rowStrideFloats < static_cast<std::size_t>(request.width) * 3u) {
        result.failureReason = "INVALID_SPECTRA_OPPONENT_INPUT";
        result.status = "FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    if (!initializeLocked(device, result.failureReason)) {
        result.status = "PIPELINE_INITIALIZATION_FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.pipelineAvailable = true;

#if !BNCAM_VMA_HEADER_AVAILABLE
    result.failureReason = "VMA_HEADER_NOT_AVAILABLE";
    result.status = "FAILED";
    result.totalMs = elapsedMs(totalStarted);
    return result;
#else
    const std::uint64_t pixels = static_cast<std::uint64_t>(request.width) * request.height;
    constexpr std::uint64_t kFloatsPerPixel = 4u;
    constexpr std::uint64_t kBytesPerPixel = kFloatsPerPixel * sizeof(float);
    if (pixels > static_cast<std::uint64_t>(
                    std::numeric_limits<std::size_t>::max() / kFloatsPerPixel
            ) || pixels > std::numeric_limits<std::uint64_t>::max() / kBytesPerPixel) {
        result.failureReason = "SPECTRA_OPPONENT_FRAME_TOO_LARGE";
        result.status = "FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.inputBytes = pixels * kBytesPerPixel;
    result.outputBytes = pixels * kBytesPerPixel;

    VmaAllocator allocator = allocatorOwner.handle();
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkQueryPool queryPool = VK_NULL_HANDLE;

    auto cleanup = [&]() {
        if (fence != VK_NULL_HANDLE) vkDestroyFence(device, fence, nullptr);
        if (queryPool != VK_NULL_HANDLE) vkDestroyQueryPool(device, queryPool, nullptr);
        if (commandBuffer != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(device, commandPool, 1u, &commandBuffer);
        }
    };

    if (!ensurePersistentBuffersLocked(
            allocator,
            result.inputBytes,
            result,
            result.failureReason
    )) {
        result.status = "PERSISTENT_BUFFER_ALLOCATION_FAILED";
        cleanup();
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.allocatedBytes = result.persistentResidentBytes;

    const auto packingStarted = Clock::now();
    float* packedInput = static_cast<float*>(persistentInputMapped_);
    for (std::uint32_t y = 0u; y < request.height; ++y) {
        const float* row = request.rgbData + static_cast<std::size_t>(y) * request.rowStrideFloats;
        for (std::uint32_t x = 0u; x < request.width; ++x) {
            const std::size_t source = static_cast<std::size_t>(x) * 3u;
            const std::size_t destination =
                    (static_cast<std::size_t>(y) * request.width + x) * 4u;
            packedInput[destination] = row[source];
            packedInput[destination + 1u] = row[source + 1u];
            packedInput[destination + 2u] = row[source + 2u];
            packedInput[destination + 3u] = 1.0f;
        }
    }
    vmaFlushAllocation(allocator, persistentInputAllocation_, 0u, result.inputBytes);
    result.inputPackingMs = elapsedMs(packingStarted);

    VkDescriptorBufferInfo inputDescriptor{};
    inputDescriptor.buffer = persistentInputBuffer_;
    inputDescriptor.offset = 0u;
    inputDescriptor.range = static_cast<VkDeviceSize>(result.inputBytes);
    VkDescriptorBufferInfo outputDescriptor{};
    outputDescriptor.buffer = persistentOutputBuffer_;
    outputDescriptor.offset = 0u;
    outputDescriptor.range = static_cast<VkDeviceSize>(result.outputBytes);
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descriptorSet_;
    writes[0].dstBinding = 0u;
    writes[0].descriptorCount = 1u;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[0].pBufferInfo = &inputDescriptor;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descriptorSet_;
    writes[1].dstBinding = 1u;
    writes[1].descriptorCount = 1u;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[1].pBufferInfo = &outputDescriptor;
    vkUpdateDescriptorSets(device, 2u, writes, 0u, nullptr);

    VkCommandBufferAllocateInfo commandAllocate{};
    commandAllocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandAllocate.commandPool = commandPool;
    commandAllocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandAllocate.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &commandAllocate, &commandBuffer) != VK_SUCCESS) {
        result.failureReason = "vkAllocateCommandBuffers_failed";
        result.status = "COMMAND_BUFFER_ALLOCATION_FAILED";
        cleanup();
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }

    VkQueryPoolCreateInfo queryInfo{};
    queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryInfo.queryCount = 2u;
    result.timestampQueryUsed = vkCreateQueryPool(
            device, &queryInfo, nullptr, &queryPool
    ) == VK_SUCCESS;

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer, &beginInfo) != VK_SUCCESS) {
        result.failureReason = "vkBeginCommandBuffer_failed";
        result.status = "COMMAND_RECORDING_FAILED";
        cleanup();
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    if (result.timestampQueryUsed) {
        vkCmdResetQueryPool(commandBuffer, queryPool, 0u, 2u);
        vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, 0u);
    }
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            pipelineLayout_,
            0u,
            1u,
            &descriptorSet_,
            0u,
            nullptr
    );
    OpponentPushConstants push{};
    push.width = request.width;
    push.height = request.height;
    vkCmdPushConstants(
            commandBuffer,
            pipelineLayout_,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0u,
            sizeof(push),
            &push
    );
    vkCmdDispatch(
            commandBuffer,
            (request.width + 15u) / 16u,
            (request.height + 15u) / 16u,
            1u
    );
    if (result.timestampQueryUsed) {
        vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, 1u);
    }
    VkBufferMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.buffer = persistentOutputBuffer_;
    barrier.offset = 0u;
    barrier.size = static_cast<VkDeviceSize>(result.outputBytes);
    vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT,
            0u,
            0u,
            nullptr,
            1u,
            &barrier,
            0u,
            nullptr
    );
    if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
        result.failureReason = "vkEndCommandBuffer_failed";
        result.status = "COMMAND_RECORDING_FAILED";
        cleanup();
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) {
        result.failureReason = "vkCreateFence_failed";
        result.status = "FENCE_CREATION_FAILED";
        cleanup();
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer;
    const auto synchronizationStarted = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence) != VK_SUCCESS ||
        vkWaitForFences(device, 1u, &fence, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("OpponentBackend");
        result.failureReason = "VULKAN_SPECTRA_OPPONENT_SUBMISSION_OR_WAIT_FAILED";
        result.status = "GPU_STALLED";
        cleanup();
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.synchronizationMs = elapsedMs(synchronizationStarted);

    if (result.timestampQueryUsed) {
        std::uint64_t timestamps[2]{0u, 0u};
        if (vkGetQueryPoolResults(
                device,
                queryPool,
                0u,
                2u,
                sizeof(timestamps),
                timestamps,
                sizeof(std::uint64_t),
                VK_QUERY_RESULT_64_BIT
        ) == VK_SUCCESS && timestamps[1] >= timestamps[0]) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physicalDevice, &properties);
            result.gpuKernelMs = static_cast<float>(
                    static_cast<double>(timestamps[1] - timestamps[0]) *
                    static_cast<double>(properties.limits.timestampPeriod) / 1.0e6
            );
        } else {
            result.timestampQueryUsed = false;
        }
    }
    if (!result.timestampQueryUsed) {
        result.gpuKernelMs = result.synchronizationMs;
    }

    const auto readbackStarted = Clock::now();
    vmaInvalidateAllocation(allocator, persistentOutputAllocation_, 0u, result.outputBytes);
    try {
        result.opponentRgba.resize(
                static_cast<std::size_t>(pixels) * static_cast<std::size_t>(kFloatsPerPixel)
        );
    } catch (...) {
        result.failureReason = "SPECTRA_OPPONENT_CPU_READBACK_ALLOCATION_FAILED";
        result.status = "READBACK_ALLOCATION_FAILED";
        cleanup();
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    std::memcpy(
            result.opponentRgba.data(),
            persistentOutputMapped_,
            static_cast<std::size_t>(result.outputBytes)
    );
    result.readbackMs = elapsedMs(readbackStarted);
    const float queueAndFenceOverheadMs = result.timestampQueryUsed
            ? std::max(0.0f, result.synchronizationMs - result.gpuKernelMs)
            : result.synchronizationMs;
    result.transferAndSyncMs = result.inputPackingMs +
            queueAndFenceOverheadMs + result.readbackMs;
    result.success = true;
    result.status = "SPECTRA_FP32_OPPONENT_FEATURES_READY";
    cleanup();
    result.totalMs = elapsedMs(totalStarted);
    return result;
#endif
}

} // namespace bncam::vulkan
