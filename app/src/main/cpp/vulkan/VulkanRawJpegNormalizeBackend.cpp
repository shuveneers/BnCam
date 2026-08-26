#include "VulkanRawJpegNormalizeBackend.h"
#include "VulkanPipelineCacheRegistry.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif

#ifndef BNCAM_RAW_JPEG_NORMALIZE_SHADER_AVAILABLE
#define BNCAM_RAW_JPEG_NORMALIZE_SHADER_AVAILABLE 0
#endif
#if BNCAM_RAW_JPEG_NORMALIZE_SHADER_AVAILABLE
#include "RawJpegNormalizeSpirv.h"
#endif

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <vector>

namespace bncam::vulkan {
namespace {
using Clock = std::chrono::steady_clock;
float elapsedMs(const Clock::time_point& start) {
    return std::chrono::duration<float, std::milli>(Clock::now() - start).count();
}

struct NormalizePush {
    std::uint32_t inputWidth;
    std::uint32_t inputHeight;
    std::uint32_t cropLeft;
    std::uint32_t cropTop;
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t phaseX;
    std::uint32_t phaseY;
    float whiteLevel;
    float padding[3];
    float blackLevels[4];
};
static_assert(sizeof(NormalizePush) == 64u);
} // namespace

bool VulkanRawJpegNormalizeBackend::ensureBufferLocked(
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
        failureReason = "RAW_JPEG_NORMALIZE_BUFFER_REQUEST_INVALID";
        return false;
    }
    if (target.buffer != VK_NULL_HANDLE && target.allocation != nullptr && target.capacityBytes >= bytes) {
        return true;
    }
    destroyBufferLocked(target);
    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = static_cast<VkDeviceSize>(bytes);
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
                       VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                       VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VmaAllocationCreateInfo allocationInfo{};
    allocationInfo.usage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    const VkResult result = vmaCreateBuffer(
            allocator, &bufferInfo, &allocationInfo,
            &target.buffer, &target.allocation, nullptr);
    if (result != VK_SUCCESS || target.buffer == VK_NULL_HANDLE || target.allocation == nullptr) {
        target = {};
        failureReason = "RAW_JPEG_NORMALIZE_VMA_BUFFER_FAILED_" + std::to_string(result);
        return false;
    }
    target.capacityBytes = bytes;
    return true;
#endif
}

void VulkanRawJpegNormalizeBackend::destroyBufferLocked(PersistentBuffer& buffer) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr && buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, buffer.buffer, buffer.allocation);
    }
#endif
    buffer = {};
}

bool VulkanRawJpegNormalizeBackend::ensureSubmissionResourcesLocked(
        VkDevice device,
        VkCommandPool commandPool,
        bool& created,
        std::string& failureReason) noexcept {
    created = false;
    if (submissionResourcesUnsafe_) {
        failureReason = "RAW_JPEG_NORMALIZE_SUBMISSION_RESOURCES_QUARANTINED";
        return false;
    }
    if (reusableCommandBuffer_ != VK_NULL_HANDLE && reusableFence_ != VK_NULL_HANDLE &&
        submissionCommandPool_ == commandPool) {
        return true;
    }
    if (reusableCommandBuffer_ != VK_NULL_HANDLE || reusableFence_ != VK_NULL_HANDLE) {
        destroySubmissionResourcesLocked(device);
    }

    VkCommandBufferAllocateInfo commandInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    const VkResult commandResult = vkAllocateCommandBuffers(
            device, &commandInfo, &reusableCommandBuffer_);
    if (commandResult != VK_SUCCESS) {
        reusableCommandBuffer_ = VK_NULL_HANDLE;
        failureReason = "RAW_JPEG_NORMALIZE_COMMAND_ALLOC_FAILED_" + std::to_string(commandResult);
        return false;
    }
    submissionCommandPool_ = commandPool;

    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    const VkResult fenceResult = vkCreateFence(device, &fenceInfo, nullptr, &reusableFence_);
    if (fenceResult != VK_SUCCESS) {
        vkFreeCommandBuffers(device, submissionCommandPool_, 1u, &reusableCommandBuffer_);
        reusableCommandBuffer_ = VK_NULL_HANDLE;
        submissionCommandPool_ = VK_NULL_HANDLE;
        failureReason = "RAW_JPEG_NORMALIZE_FENCE_CREATE_FAILED_" + std::to_string(fenceResult);
        return false;
    }
    created = true;
    return true;
}

void VulkanRawJpegNormalizeBackend::destroySubmissionResourcesLocked(VkDevice device) noexcept {
    if (device != VK_NULL_HANDLE) {
        if (reusableFence_ != VK_NULL_HANDLE) {
            vkDestroyFence(device, reusableFence_, nullptr);
        }
        if (reusableCommandBuffer_ != VK_NULL_HANDLE && submissionCommandPool_ != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(device, submissionCommandPool_, 1u, &reusableCommandBuffer_);
        }
    }
    reusableFence_ = VK_NULL_HANDLE;
    reusableCommandBuffer_ = VK_NULL_HANDLE;
    submissionCommandPool_ = VK_NULL_HANDLE;
    submissionResourcesUnsafe_ = false;
}

void VulkanRawJpegNormalizeBackend::destroyLocked(VkDevice device) noexcept {
    destroySubmissionResourcesLocked(device);
    destroyBufferLocked(output_);
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
    boundInputBuffer_ = VK_NULL_HANDLE;
    boundInputRange_ = 0u;
    boundOutputBuffer_ = VK_NULL_HANDLE;
    boundOutputRange_ = 0u;
    allocator_ = nullptr;
    initializedDevice_ = VK_NULL_HANDLE;
    initialized_ = false;
    residentOutputGeneration_ = 0u;
    residentOutputBytes_ = 0u;
    residentOutputWidth_ = 0u;
    residentOutputHeight_ = 0u;
}

void VulkanRawJpegNormalizeBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanRawJpegNormalizeBackend::resolveResidentOutput(
        std::uint64_t generation,
        VkBuffer& buffer,
        std::uint64_t& bytes,
        std::uint32_t& width,
        std::uint32_t& height) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    buffer = VK_NULL_HANDLE;
    bytes = 0u;
    width = 0u;
    height = 0u;
    if (generation == 0u || generation != residentOutputGeneration_ ||
        output_.buffer == VK_NULL_HANDLE || residentOutputBytes_ == 0u ||
        residentOutputWidth_ == 0u || residentOutputHeight_ == 0u) {
        return false;
    }
    buffer = output_.buffer;
    bytes = residentOutputBytes_;
    width = residentOutputWidth_;
    height = residentOutputHeight_;
    return true;
}

bool VulkanRawJpegNormalizeBackend::initializeLocked(
        VkDevice device,
        std::string& failureReason) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device) return true;
        failureReason = "RAW_JPEG_NORMALIZE_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_RAW_JPEG_NORMALIZE_SHADER_AVAILABLE
    (void)device;
    failureReason = "RAW_JPEG_NORMALIZE_SHADER_NOT_COMPILED";
    return false;
#else
    if (device == VK_NULL_HANDLE) {
        failureReason = "RAW_JPEG_NORMALIZE_DEVICE_INVALID";
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
        failureReason = "RAW_JPEG_NORMALIZE_DESCRIPTOR_LAYOUT_FAILED";
        destroyLocked(device);
        return false;
    }
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0u;
    pushRange.size = sizeof(NormalizePush);
    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount = 1u;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;
    layoutInfo.pushConstantRangeCount = 1u;
    layoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "RAW_JPEG_NORMALIZE_PIPELINE_LAYOUT_FAILED";
        destroyLocked(device);
        return false;
    }
    const std::vector<std::uint32_t>& spirv = getRawJpegNormalizeSpirv();
    if (spirv.empty()) {
        failureReason = "RAW_JPEG_NORMALIZE_SHADER_EMPTY";
        destroyLocked(device);
        return false;
    }
    VkShaderModuleCreateInfo moduleInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    moduleInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    moduleInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &moduleInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "RAW_JPEG_NORMALIZE_SHADER_MODULE_FAILED";
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
        failureReason = "RAW_JPEG_NORMALIZE_PIPELINE_FAILED";
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
        failureReason = "RAW_JPEG_NORMALIZE_DESCRIPTOR_POOL_FAILED";
        destroyLocked(device);
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1u;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "RAW_JPEG_NORMALIZE_DESCRIPTOR_SET_FAILED";
        destroyLocked(device);
        return false;
    }
    initializedDevice_ = device;
    initialized_ = true;
    return true;
#endif
}

RawJpegNormalizeResult VulkanRawJpegNormalizeBackend::execute(
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const RawJpegNormalizeRequest& request) noexcept {
    RawJpegNormalizeResult out{};
    out.attempted = true;
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)device; (void)computeQueue; (void)commandPool; (void)allocatorOwner; (void)request;
    out.failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return out;
#else
    if (device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE ||
        request.canonicalRawBuffer == VK_NULL_HANDLE || request.inputWidth == 0u || request.inputHeight == 0u ||
        request.width == 0u || request.height == 0u ||
        request.cropLeft + request.width > request.inputWidth ||
        request.cropTop + request.height > request.inputHeight ||
        request.generationId == 0u || !std::isfinite(request.whiteLevel) || request.whiteLevel <= 0.0f) {
        out.failureReason = "RAW_JPEG_NORMALIZE_REQUEST_INVALID";
        return out;
    }
    const std::uint64_t inputPixels = static_cast<std::uint64_t>(request.inputWidth) * request.inputHeight;
    const std::uint64_t rawBytes = inputPixels * sizeof(std::uint16_t);
    const std::uint64_t pixels = static_cast<std::uint64_t>(request.width) * request.height;
    const std::uint64_t outputBytes = pixels * sizeof(float);
    if (request.canonicalRawBytes < rawBytes) {
        out.failureReason = "RAW_JPEG_NORMALIZE_INPUT_LENGTH_INVALID";
        return out;
    }
    for (float black : request.blackLevels) {
        if (!std::isfinite(black) || black < 0.0f || black >= request.whiteLevel) {
            out.failureReason = "RAW_JPEG_NORMALIZE_LEVELS_INVALID";
            return out;
        }
    }

    std::lock_guard<std::mutex> lock(mutex_);
    residentOutputGeneration_ = 0u;
    residentOutputBytes_ = 0u;
    residentOutputWidth_ = 0u;
    residentOutputHeight_ = 0u;
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr) {
        out.failureReason = "RAW_JPEG_NORMALIZE_VMA_ALLOCATOR_UNAVAILABLE";
        return out;
    }
    if (!initializeLocked(device, out.failureReason) ||
        !ensureBufferLocked(allocator_, outputBytes, output_, out.failureReason)) {
        return out;
    }

    VkDescriptorBufferInfo inputInfo{request.canonicalRawBuffer, 0u, static_cast<VkDeviceSize>(rawBytes)};
    VkDescriptorBufferInfo outputInfo{output_.buffer, 0u, static_cast<VkDeviceSize>(outputBytes)};
    VkWriteDescriptorSet writes[2]{};
    std::uint32_t writeCount = 0u;
    if (boundInputBuffer_ != inputInfo.buffer || boundInputRange_ != inputInfo.range) {
        auto& write = writes[writeCount++];
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = descriptorSet_;
        write.dstBinding = 0u;
        write.descriptorCount = 1u;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &inputInfo;
        boundInputBuffer_ = inputInfo.buffer;
        boundInputRange_ = inputInfo.range;
    } else {
        ++out.descriptorBindingsReused;
    }
    if (boundOutputBuffer_ != outputInfo.buffer || boundOutputRange_ != outputInfo.range) {
        auto& write = writes[writeCount++];
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = descriptorSet_;
        write.dstBinding = 1u;
        write.descriptorCount = 1u;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &outputInfo;
        boundOutputBuffer_ = outputInfo.buffer;
        boundOutputRange_ = outputInfo.range;
    } else {
        ++out.descriptorBindingsReused;
    }
    if (writeCount > 0u) {
        vkUpdateDescriptorSets(device, writeCount, writes, 0u, nullptr);
        out.descriptorUpdates = writeCount;
    }

    bool submissionResourcesCreated = false;
    if (!ensureSubmissionResourcesLocked(
                device, commandPool, submissionResourcesCreated, out.failureReason)) {
        return out;
    }
    out.commandBufferAllocations = submissionResourcesCreated ? 1u : 0u;
    out.fenceCreations = submissionResourcesCreated ? 1u : 0u;
    out.reusedSubmissionResources = !submissionResourcesCreated;
    VkCommandBuffer command = reusableCommandBuffer_;
    const VkResult commandReset = vkResetCommandBuffer(command, 0u);
    if (commandReset != VK_SUCCESS) {
        out.failureReason = "RAW_JPEG_NORMALIZE_COMMAND_RESET_FAILED_" + std::to_string(commandReset);
        return out;
    }
    ++out.commandBufferResets;
    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(command, &beginInfo) != VK_SUCCESS) {
        out.failureReason = "RAW_JPEG_NORMALIZE_COMMAND_BEGIN_FAILED";
        return out;
    }

    VkBufferMemoryBarrier inputBarrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    inputBarrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    inputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    inputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarrier.buffer = request.canonicalRawBuffer;
    inputBarrier.offset = 0u;
    inputBarrier.size = static_cast<VkDeviceSize>(rawBytes);
    vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u, 0u, nullptr, 1u, &inputBarrier, 0u, nullptr);

    NormalizePush push{};
    push.inputWidth = request.inputWidth;
    push.inputHeight = request.inputHeight;
    push.cropLeft = request.cropLeft;
    push.cropTop = request.cropTop;
    push.width = request.width;
    push.height = request.height;
    push.phaseX = request.cfaOffsetX & 1u;
    push.phaseY = request.cfaOffsetY & 1u;
    push.whiteLevel = request.whiteLevel;
    std::copy(request.blackLevels.begin(), request.blackLevels.end(), push.blackLevels);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(
            command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
            0u, 1u, &descriptorSet_, 0u, nullptr);
    vkCmdPushConstants(command, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    const auto kernelStart = Clock::now();
    vkCmdDispatch(command, (request.width + 15u) / 16u, (request.height + 15u) / 16u, 1u);

    VkBufferMemoryBarrier outputBarrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    outputBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    outputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputBarrier.buffer = output_.buffer;
    outputBarrier.offset = 0u;
    outputBarrier.size = static_cast<VkDeviceSize>(outputBytes);
    vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u, 0u, nullptr, 1u, &outputBarrier, 0u, nullptr);

    if (vkEndCommandBuffer(command) != VK_SUCCESS) {
        out.failureReason = "RAW_JPEG_NORMALIZE_COMMAND_END_FAILED";
        return out;
    }
    const VkResult fenceReset = vkResetFences(device, 1u, &reusableFence_);
    if (fenceReset != VK_SUCCESS) {
        out.failureReason = "RAW_JPEG_NORMALIZE_FENCE_RESET_FAILED_" + std::to_string(fenceReset);
        return out;
    }
    ++out.fenceResets;
    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1u;
    submitInfo.pCommandBuffers = &command;
    if (vkQueueSubmit(computeQueue, 1u, &submitInfo, reusableFence_) != VK_SUCCESS) {
        out.failureReason = "RAW_JPEG_NORMALIZE_QUEUE_SUBMIT_FAILED";
        return out;
    }
    ++out.queueSubmissions;
    const auto syncStart = Clock::now();
    const VkResult wait = vkWaitForFences(device, 1u, &reusableFence_, VK_TRUE, 1'500'000'000ull);
    out.gpuSynchronizationMs = elapsedMs(syncStart);
    if (wait != VK_SUCCESS) {
        submissionResourcesUnsafe_ = true;
        out.submissionMayRemainInFlight = true;
        out.failureReason = wait == VK_TIMEOUT
                ? "RAW_JPEG_NORMALIZE_FENCE_TIMEOUT"
                : "RAW_JPEG_NORMALIZE_FENCE_WAIT_FAILED";
        return out;
    }
    out.gpuKernelWallMs = elapsedMs(kernelStart);

    residentOutputGeneration_ = request.generationId;
    residentOutputBytes_ = outputBytes;
    residentOutputWidth_ = request.width;
    residentOutputHeight_ = request.height;
    out.residentOutputProduced = true;
    out.residentOutputGeneration = request.generationId;
    out.backend = "VULKAN_RAW_JPEG_NORMALIZE_EXACT";
    out.success = true;
    out.failureReason = "none";
    return out;
#endif
}

} // namespace bncam::vulkan
