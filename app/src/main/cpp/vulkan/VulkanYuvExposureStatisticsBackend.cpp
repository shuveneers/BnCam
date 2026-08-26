#include "VulkanYuvExposureStatisticsBackend.h"
#include "VulkanPipelineCacheRegistry.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif

#ifndef BNCAM_YUV_EXPOSURE_STATISTICS_SHADER_AVAILABLE
#define BNCAM_YUV_EXPOSURE_STATISTICS_SHADER_AVAILABLE 0
#endif
#if BNCAM_YUV_EXPOSURE_STATISTICS_SHADER_AVAILABLE
#include "YuvExposureStatisticsSpirv.h"
#endif

#include <chrono>
#include <cstring>
#include <vector>

namespace bncam::vulkan {
namespace {
using Clock = std::chrono::steady_clock;
float elapsedMs(const Clock::time_point& start) {
    return std::chrono::duration<float, std::milli>(Clock::now() - start).count();
}
struct StatisticsPush {
    std::uint32_t sampleWidth;
    std::uint32_t sampleHeight;
    std::uint32_t sampleCount;
    std::uint32_t reserved;
};
static_assert(sizeof(StatisticsPush) == 16u);
} // namespace

bool VulkanYuvExposureStatisticsBackend::ensureHostBufferLocked(
        VmaAllocator allocator,
        std::uint64_t bytes,
        PersistentBuffer& target,
        std::string& failureReason) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)target;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u) {
        failureReason = "YUV_EXPOSURE_STATS_BUFFER_REQUEST_INVALID";
        return false;
    }
    if (target.buffer != VK_NULL_HANDLE && target.allocation != nullptr &&
        target.capacityBytes >= bytes && target.mapped != nullptr) {
        return true;
    }
    destroyBufferLocked(target);
    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = static_cast<VkDeviceSize>(bytes);
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VmaAllocationCreateInfo allocationInfo{};
    allocationInfo.usage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST;
    allocationInfo.flags = VMA_ALLOCATION_CREATE_MAPPED_BIT |
                           VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    VmaAllocationInfo info{};
    const VkResult result = vmaCreateBuffer(
            allocator, &bufferInfo, &allocationInfo,
            &target.buffer, &target.allocation, &info);
    if (result != VK_SUCCESS || target.buffer == VK_NULL_HANDLE ||
        target.allocation == nullptr || info.pMappedData == nullptr) {
        target = {};
        failureReason = "YUV_EXPOSURE_STATS_VMA_BUFFER_FAILED_" + std::to_string(result);
        return false;
    }
    target.mapped = info.pMappedData;
    target.capacityBytes = bytes;
    return true;
#endif
}

void VulkanYuvExposureStatisticsBackend::destroyBufferLocked(PersistentBuffer& buffer) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr && buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, buffer.buffer, buffer.allocation);
    }
#endif
    buffer = {};
}

void VulkanYuvExposureStatisticsBackend::destroyLocked(VkDevice device) noexcept {
    destroyBufferLocked(input_);
    destroyBufferLocked(statistics_);
    if (device != VK_NULL_HANDLE) {
        if (descriptorPool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool_, nullptr);
        if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline_, nullptr);
        if (shaderModule_ != VK_NULL_HANDLE) vkDestroyShaderModule(device, shaderModule_, nullptr);
        if (pipelineLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout_, nullptr);
        if (descriptorSetLayout_ != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorSetLayout_, nullptr);
    }
    descriptorPool_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    shaderModule_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    descriptorSetLayout_ = VK_NULL_HANDLE;
    descriptorSet_ = VK_NULL_HANDLE;
    allocator_ = nullptr;
    initializedDevice_ = VK_NULL_HANDLE;
    initialized_ = false;
}

void VulkanYuvExposureStatisticsBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanYuvExposureStatisticsBackend::initializeLocked(
        VkDevice device,
        std::string& failureReason) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device) return true;
        failureReason = "YUV_EXPOSURE_STATS_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_YUV_EXPOSURE_STATISTICS_SHADER_AVAILABLE
    (void)device;
    failureReason = "YUV_EXPOSURE_STATS_SHADER_NOT_COMPILED";
    return false;
#else
    if (device == VK_NULL_HANDLE) {
        failureReason = "YUV_EXPOSURE_STATS_DEVICE_INVALID";
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[2]{};
    for (std::uint32_t i = 0u; i < 2u; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo setInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    setInfo.bindingCount = 2u;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device, &setInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "YUV_EXPOSURE_STATS_DESCRIPTOR_LAYOUT_FAILED";
        destroyLocked(device);
        return false;
    }
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0u;
    pushRange.size = sizeof(StatisticsPush);
    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount = 1u;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;
    layoutInfo.pushConstantRangeCount = 1u;
    layoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "YUV_EXPOSURE_STATS_PIPELINE_LAYOUT_FAILED";
        destroyLocked(device);
        return false;
    }
    const std::vector<std::uint32_t>& spirv = getYuvExposureStatisticsSpirv();
    if (spirv.empty()) {
        failureReason = "YUV_EXPOSURE_STATS_SHADER_EMPTY";
        destroyLocked(device);
        return false;
    }
    VkShaderModuleCreateInfo moduleInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    moduleInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    moduleInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &moduleInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "YUV_EXPOSURE_STATS_SHADER_MODULE_FAILED";
        destroyLocked(device);
        return false;
    }
    VkPipelineShaderStageCreateInfo stageInfo{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = shaderModule_;
    stageInfo.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pipelineInfo.stage = stageInfo;
    pipelineInfo.layout = pipelineLayout_;
    if (VulkanPipelineCacheRegistry::createComputePipelines(device, 1u, &pipelineInfo, nullptr, &pipeline_) != VK_SUCCESS) {
        failureReason = "YUV_EXPOSURE_STATS_PIPELINE_FAILED";
        destroyLocked(device);
        return false;
    }
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 2u;
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 1u;
    poolInfo.poolSizeCount = 1u;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        failureReason = "YUV_EXPOSURE_STATS_DESCRIPTOR_POOL_FAILED";
        destroyLocked(device);
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1u;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "YUV_EXPOSURE_STATS_DESCRIPTOR_SET_FAILED";
        destroyLocked(device);
        return false;
    }
    initializedDevice_ = device;
    initialized_ = true;
    return true;
#endif
}

YuvExposureStatisticsResult VulkanYuvExposureStatisticsBackend::execute(
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const YuvExposureStatisticsRequest& request) noexcept {
    YuvExposureStatisticsResult out{};
    out.attempted = true;
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)device; (void)computeQueue; (void)commandPool; (void)allocatorOwner; (void)request;
    out.failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return out;
#else
    if (device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE ||
        request.packedSamples == nullptr || request.sampleCount == 0u ||
        request.sampleWidth == 0u || request.sampleHeight == 0u ||
        request.sampleCount != static_cast<std::size_t>(request.sampleWidth) * request.sampleHeight ||
        request.sampleCount > 65536u) {
        out.failureReason = "YUV_EXPOSURE_STATS_REQUEST_INVALID";
        return out;
    }
    const std::uint64_t inputBytes = request.sampleCount * sizeof(std::uint32_t);
    constexpr std::uint64_t statisticsBytes = kYuvExposureStatisticsWords * sizeof(std::uint32_t);
    std::lock_guard<std::mutex> lock(mutex_);
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr) {
        out.failureReason = "YUV_EXPOSURE_STATS_VMA_ALLOCATOR_UNAVAILABLE";
        return out;
    }
    if (!initializeLocked(device, out.failureReason) ||
        !ensureHostBufferLocked(allocator_, inputBytes, input_, out.failureReason) ||
        !ensureHostBufferLocked(allocator_, statisticsBytes, statistics_, out.failureReason)) {
        return out;
    }

    const auto uploadStart = Clock::now();
    std::memcpy(input_.mapped, request.packedSamples, static_cast<std::size_t>(inputBytes));
    std::memset(statistics_.mapped, 0, static_cast<std::size_t>(statisticsBytes));
    vmaFlushAllocation(allocator_, input_.allocation, 0u, inputBytes);
    vmaFlushAllocation(allocator_, statistics_.allocation, 0u, statisticsBytes);
    out.inputUploadMs = elapsedMs(uploadStart);

    VkDescriptorBufferInfo descriptorBuffers[2]{};
    descriptorBuffers[0] = {input_.buffer, 0u, inputBytes};
    descriptorBuffers[1] = {statistics_.buffer, 0u, statisticsBytes};
    VkWriteDescriptorSet writes[2]{};
    for (std::uint32_t i = 0u; i < 2u; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &descriptorBuffers[i];
    }
    vkUpdateDescriptorSets(device, 2u, writes, 0u, nullptr);

    VkCommandBuffer command = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo commandInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &commandInfo, &command) != VK_SUCCESS) {
        out.failureReason = "YUV_EXPOSURE_STATS_COMMAND_ALLOC_FAILED";
        return out;
    }
    auto freeCommand = [&]() {
        if (command != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(device, commandPool, 1u, &command);
            command = VK_NULL_HANDLE;
        }
    };
    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(command, &beginInfo) != VK_SUCCESS) {
        out.failureReason = "YUV_EXPOSURE_STATS_COMMAND_BEGIN_FAILED";
        freeCommand();
        return out;
    }
    VkMemoryBarrier hostWriteBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    hostWriteBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    hostWriteBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_HOST_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            1u, &hostWriteBarrier,
            0u, nullptr,
            0u, nullptr);

    const StatisticsPush push{
        request.sampleWidth,
        request.sampleHeight,
        static_cast<std::uint32_t>(request.sampleCount),
        0u,
    };
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0u, 1u,
                            &descriptorSet_, 0u, nullptr);
    vkCmdPushConstants(command, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    const std::uint32_t groups = (push.sampleCount + 63u) / 64u;
    const auto gpuStart = Clock::now();
    vkCmdDispatch(command, groups, 1u, 1u);
    VkMemoryBarrier hostReadBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    hostReadBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    hostReadBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT,
            0u,
            1u, &hostReadBarrier,
            0u, nullptr,
            0u, nullptr);
    if (vkEndCommandBuffer(command) != VK_SUCCESS) {
        out.failureReason = "YUV_EXPOSURE_STATS_COMMAND_END_FAILED";
        freeCommand();
        return out;
    }
    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) {
        out.failureReason = "YUV_EXPOSURE_STATS_FENCE_CREATE_FAILED";
        freeCommand();
        return out;
    }
    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1u;
    submitInfo.pCommandBuffers = &command;
    if (vkQueueSubmit(computeQueue, 1u, &submitInfo, fence) != VK_SUCCESS) {
        out.failureReason = "YUV_EXPOSURE_STATS_QUEUE_SUBMIT_FAILED";
        vkDestroyFence(device, fence, nullptr);
        freeCommand();
        return out;
    }
    const auto syncStart = Clock::now();
    const VkResult waitResult = vkWaitForFences(device, 1u, &fence, VK_TRUE, 1'500'000'000ull);
    out.gpuSynchronizationMs = elapsedMs(syncStart);
    if (waitResult != VK_SUCCESS) {
        out.submissionMayRemainInFlight = true;
        out.failureReason = waitResult == VK_TIMEOUT
                ? "YUV_EXPOSURE_STATS_FENCE_TIMEOUT"
                : "YUV_EXPOSURE_STATS_FENCE_WAIT_FAILED";
        return out;
    }
    out.gpuExecutionWallMs = elapsedMs(gpuStart);
    vkDestroyFence(device, fence, nullptr);
    freeCommand();

    const auto readbackStart = Clock::now();
    vmaInvalidateAllocation(allocator_, statistics_.allocation, 0u, statisticsBytes);
    std::memcpy(out.statistics.data(), statistics_.mapped, static_cast<std::size_t>(statisticsBytes));
    out.readbackMs = elapsedMs(readbackStart);
    out.backend = "VULKAN_YUV_EXPOSURE_STATISTICS";
    out.failureReason = "none";
    out.success = true;
    return out;
#endif
}

} // namespace bncam::vulkan
