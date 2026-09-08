#include "VulkanNeuralRemainingLscBackend.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif

#ifndef BNCAM_NEURAL_REMAINING_LSC_SHADER_AVAILABLE
#define BNCAM_NEURAL_REMAINING_LSC_SHADER_AVAILABLE 0
#endif
#if BNCAM_NEURAL_REMAINING_LSC_SHADER_AVAILABLE
#include "NeuralRemainingLscSpirv.h"
#endif

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <vector>

namespace bncam::vulkan::neural {
namespace {
using Clock = std::chrono::steady_clock;

float elapsedMs(const Clock::time_point& start) noexcept {
    return std::chrono::duration<float, std::milli>(Clock::now() - start).count();
}

constexpr std::uint64_t kGenerationPrefix = 0x4c53430000000000ull; // "LSC"

struct PushConstants {
    std::uint32_t frameWidth = 0u;
    std::uint32_t frameHeight = 0u;
    std::uint32_t sensorCfaPattern = 0u;
    std::int32_t cfaOffsetX = 0;
    std::int32_t cfaOffsetY = 0;
    std::uint32_t lensColumns = 0u;
    std::uint32_t lensRows = 0u;
    std::uint32_t lensEnabled = 0u;
    std::uint32_t mode = 0u;
    std::uint32_t sampleColumns = 0u;
    std::uint32_t sampleRows = 0u;
    std::uint32_t sampleStride = 1u;
};
static_assert(sizeof(PushConstants) == 48u, "remaining LSC push constant layout mismatch");

std::uint32_t autoStrideFor(std::uint32_t width, std::uint32_t height) noexcept {
    if (width < 9u || height < 9u) return 1u;
    constexpr double kTargetSamples = 50000.0;
    const std::uint64_t interior = static_cast<std::uint64_t>(width - 8u) *
            static_cast<std::uint64_t>(height - 8u);
    return std::max<std::uint32_t>(1u, static_cast<std::uint32_t>(std::floor(
            std::sqrt(static_cast<double>(interior) / kTargetSamples))));
}

float percentileSorted(const std::vector<float>& values, float percentile) noexcept {
    if (values.empty()) return 0.0f;
    const std::size_t index = std::min(
            values.size() - 1u,
            static_cast<std::size_t>(std::floor(
                    std::clamp(percentile, 0.0f, 1.0f) *
                    static_cast<float>(values.size() - 1u))));
    return values[index];
}
} // namespace

bool VulkanNeuralRemainingLscBackend::productionKernelConnected() const noexcept {
#if BNCAM_NEURAL_REMAINING_LSC_SHADER_AVAILABLE
    return !getNeuralRemainingLscSpirv().empty();
#else
    return false;
#endif
}

bool VulkanNeuralRemainingLscBackend::pipelineInitialized() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return initialized_;
}

bool VulkanNeuralRemainingLscBackend::ensureBufferLocked(
        VmaAllocator allocator,
        std::uint64_t bytes,
        std::uint32_t hostAccess,
        Buffer& buffer,
        bool& reallocated,
        std::string& failureReason) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)hostAccess; (void)buffer; (void)reallocated;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u ||
        bytes > static_cast<std::uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        failureReason = "INVALID_REMAINING_LSC_BUFFER_REQUEST";
        return false;
    }
    const bool mappedRequired = hostAccess != 0u;
    if (buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr &&
        buffer.capacityBytes >= bytes && (!mappedRequired || buffer.mapped != nullptr)) {
        return true;
    }
    freeBufferLocked(buffer);

    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = static_cast<VkDeviceSize>(bytes);
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo allocationInfo{};
    allocationInfo.usage = mappedRequired
            ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    allocationInfo.flags = mappedRequired
            ? hostAccess | VMA_ALLOCATION_CREATE_MAPPED_BIT : 0u;
    VmaAllocationInfo allocationResult{};
    const VkResult create = vmaCreateBuffer(
            allocator, &info, &allocationInfo,
            &buffer.buffer, &buffer.allocation, &allocationResult);
    if (create != VK_SUCCESS || buffer.buffer == VK_NULL_HANDLE || buffer.allocation == nullptr ||
        (mappedRequired && allocationResult.pMappedData == nullptr)) {
        buffer = {};
        failureReason = "vmaCreateBuffer_remaining_lsc_failed_" + std::to_string(create);
        return false;
    }
    buffer.mapped = allocationResult.pMappedData;
    buffer.capacityBytes = bytes;
    reallocated = true;
    ++allocationGeneration_;
    return true;
#endif
}

bool VulkanNeuralRemainingLscBackend::ensureLensMapLocked(
        VmaAllocator allocator,
        const NeuralRemainingLscRequest& request,
        bool& reallocated,
        float& uploadMs,
        std::string& failureReason) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)request; (void)reallocated; (void)uploadMs;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    const bool valid = request.lensShadingMap != nullptr &&
            request.lensShadingColumns > 0u && request.lensShadingRows > 0u;
    const std::uint64_t floats = valid
            ? static_cast<std::uint64_t>(request.lensShadingColumns) *
                    request.lensShadingRows * 4u
            : 4u;
    const std::uint64_t bytes = floats * sizeof(float);
    const bool reusable = lensMap_.buffer != VK_NULL_HANDLE && lensMap_.allocation != nullptr &&
            lensMap_.mapped != nullptr && lensMap_.capacityBytes >= bytes;
    if (!ensureBufferLocked(
            allocator, bytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT,
            lensMap_, reallocated, failureReason)) {
        return false;
    }
    const bool generationMatches = valid && request.lensShadingGenerationId != 0u &&
            request.lensShadingGenerationId == lensMapGenerationId_ &&
            request.lensShadingColumns == lensMapColumns_ &&
            request.lensShadingRows == lensMapRows_;
    if (generationMatches && reusable) {
        uploadMs = 0.0f;
        return true;
    }

    const auto start = Clock::now();
    if (valid) {
        std::memcpy(lensMap_.mapped, request.lensShadingMap, static_cast<std::size_t>(bytes));
        lensMapGenerationId_ = request.lensShadingGenerationId;
        lensMapColumns_ = request.lensShadingColumns;
        lensMapRows_ = request.lensShadingRows;
    } else {
        float* gains = static_cast<float*>(lensMap_.mapped);
        gains[0] = gains[1] = gains[2] = gains[3] = 1.0f;
        lensMapGenerationId_ = 0u;
        lensMapColumns_ = 0u;
        lensMapRows_ = 0u;
    }
    vmaFlushAllocation(allocator, lensMap_.allocation, 0u, static_cast<VkDeviceSize>(bytes));
    uploadMs = elapsedMs(start);
    return true;
#endif
}

void VulkanNeuralRemainingLscBackend::updateDescriptorSetLocked(
        VkDevice device, VkBuffer source) noexcept {
    VkDescriptorBufferInfo infos[5]{};
    infos[0].buffer = source;
    infos[1].buffer = output_.buffer;
    infos[2].buffer = lensMap_.buffer;
    infos[3].buffer = autoSamples_.buffer;
    infos[4].buffer = telemetry_.buffer;
    for (auto& info : infos) info.range = VK_WHOLE_SIZE;

    VkWriteDescriptorSet writes[5]{};
    for (std::uint32_t i = 0u; i < 5u; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, 5u, writes, 0u, nullptr);
}

bool VulkanNeuralRemainingLscBackend::initializeLocked(
        VkDevice device, VkCommandPool commandPool,
        std::string& failureReason) noexcept {
    if (initialized_) {
        if (device == initializedDevice_ && commandPool == initializedCommandPool_) return true;
        failureReason = "REMAINING_LSC_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_NEURAL_REMAINING_LSC_SHADER_AVAILABLE
    (void)device; (void)commandPool;
    failureReason = "REMAINING_LSC_SHADER_NOT_COMPILED";
    return false;
#else
    const auto& spirv = getNeuralRemainingLscSpirv();
    if (device == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE || spirv.empty()) {
        failureReason = "REMAINING_LSC_INITIALIZATION_INPUT_INVALID";
        return false;
    }

    VkDescriptorSetLayoutBinding bindings[5]{};
    for (std::uint32_t i = 0u; i < 5u; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo descriptorInfo{};
    descriptorInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorInfo.bindingCount = 5u;
    descriptorInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorSetLayout_remaining_lsc_failed";
        destroyLocked(device);
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.size = sizeof(PushConstants);
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1u;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout_;
    pipelineLayoutInfo.pushConstantRangeCount = 1u;
    pipelineLayoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device, &pipelineLayoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "vkCreatePipelineLayout_remaining_lsc_failed";
        destroyLocked(device);
        return false;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_remaining_lsc_failed";
        destroyLocked(device);
        return false;
    }
    VkPipelineShaderStageCreateInfo stage{};
    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = shaderModule_;
    stage.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = stage;
    pipelineInfo.layout = pipelineLayout_;
    if (vkCreateComputePipelines(device, VK_NULL_HANDLE, 1u, &pipelineInfo, nullptr, &pipeline_) != VK_SUCCESS) {
        failureReason = "vkCreateComputePipelines_remaining_lsc_failed";
        destroyLocked(device);
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 5u;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1u;
    poolInfo.poolSizeCount = 1u;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorPool_remaining_lsc_failed";
        destroyLocked(device);
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1u;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "vkAllocateDescriptorSets_remaining_lsc_failed";
        destroyLocked(device);
        return false;
    }

    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &commandInfo, &commandBuffer_) != VK_SUCCESS) {
        failureReason = "vkAllocateCommandBuffers_remaining_lsc_failed";
        destroyLocked(device);
        return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence_) != VK_SUCCESS) {
        failureReason = "vkCreateFence_remaining_lsc_failed";
        destroyLocked(device);
        return false;
    }
    VkQueryPoolCreateInfo queryInfo{};
    queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryInfo.queryCount = 4u;
    if (vkCreateQueryPool(device, &queryInfo, nullptr, &queryPool_) != VK_SUCCESS) {
        queryPool_ = VK_NULL_HANDLE;
    }

    initialized_ = true;
    initializedDevice_ = device;
    initializedCommandPool_ = commandPool;
    failureReason.clear();
    return true;
#endif
}

NeuralRemainingLscResult VulkanNeuralRemainingLscBackend::execute(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const NeuralRemainingLscRequest& request) noexcept {
    NeuralRemainingLscResult result{};
    result.attempted = true;
    result.pipelineAvailable = productionKernelConnected();
    const auto totalStart = Clock::now();

    const std::uint64_t pixelCount = static_cast<std::uint64_t>(request.frameWidth) * request.frameHeight;
    const std::uint64_t imageBytes = pixelCount * sizeof(float);
    const std::uint64_t clipWidth = (static_cast<std::uint64_t>(request.frameWidth) + 1u) / 2u;
    const std::uint64_t clipHeight = (static_cast<std::uint64_t>(request.frameHeight) + 1u) / 2u;
    const std::uint64_t clipBytes = clipWidth * clipHeight * sizeof(float);
    const std::uint64_t transportBytes = imageBytes + clipBytes;
    result.imageBytes = imageBytes;
    result.sourceClipConfidenceMapBytes = clipBytes;
    result.residentTransportBytes = transportBytes;

    if (request.bayerInput == VK_NULL_HANDLE || request.sourceClipTransport == VK_NULL_HANDLE ||
        request.frameWidth == 0u || request.frameHeight == 0u ||
        request.bayerInputBytes < imageBytes || request.sourceClipTransportBytes < transportBytes ||
        device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE) {
        result.status = "REMAINING_LSC_INVALID_INPUT";
        result.failureReason = "INVALID_REMAINING_LSC_REQUEST_OR_SOURCE_CLIP_TRANSPORT";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_NEURAL_REMAINING_LSC_SHADER_AVAILABLE
    (void)physicalDevice; (void)allocatorOwner;
    result.status = "REMAINING_LSC_BUILD_SUPPORT_UNAVAILABLE";
    result.failureReason = !BNCAM_VMA_HEADER_AVAILABLE
            ? "VMA_HEADER_NOT_AVAILABLE" : "REMAINING_LSC_SHADER_NOT_COMPILED";
    result.totalMs = elapsedMs(totalStart);
    return result;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    std::string failure;
    if (!initializeLocked(device, commandPool, failure)) {
        result.status = "REMAINING_LSC_INITIALIZATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr) {
        result.status = "REMAINING_LSC_ALLOCATOR_UNAVAILABLE";
        result.failureReason = "VMA_ALLOCATOR_NOT_READY";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    const std::uint32_t autoStride = autoStrideFor(request.frameWidth, request.frameHeight);
    const std::uint32_t autoColumns = request.frameWidth >= 9u
            ? ((request.frameWidth - 9u) / autoStride) + 1u : 0u;
    const std::uint32_t autoRows = request.frameHeight >= 9u
            ? ((request.frameHeight - 9u) / autoStride) + 1u : 0u;
    const std::uint64_t autoCount = request.collectAutoSceneMetrics
            ? static_cast<std::uint64_t>(autoColumns) * autoRows : 0u;
    const std::uint64_t autoBytes = std::max<std::uint64_t>(
            4u * sizeof(float), autoCount * 4u * sizeof(float));
    constexpr std::uint64_t telemetryBytes = 4u * sizeof(std::uint32_t);

    bool reallocated = false;
    const std::uint32_t readAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    if (!ensureBufferLocked(allocator_, transportBytes, 0u, output_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, autoBytes, readAccess, autoSamples_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, telemetryBytes, readAccess, telemetry_, reallocated, failure) ||
        !ensureLensMapLocked(allocator_, request, reallocated, result.lensMapUploadMs, failure)) {
        result.status = "REMAINING_LSC_BUFFER_ALLOCATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = output_.capacityBytes + lensMap_.capacityBytes +
            autoSamples_.capacityBytes + telemetry_.capacityBytes;
    updateDescriptorSetLocked(device, request.bayerInput);

    std::memset(telemetry_.mapped, 0, static_cast<std::size_t>(telemetryBytes));
    vmaFlushAllocation(allocator_, telemetry_.allocation, 0u, static_cast<VkDeviceSize>(telemetryBytes));

    if (vkResetFences(device, 1u, &fence_) != VK_SUCCESS ||
        vkResetCommandBuffer(commandBuffer_, 0u) != VK_SUCCESS) {
        result.status = "REMAINING_LSC_RESET_FAILED";
        result.failureReason = "FENCE_OR_COMMAND_RESET_FAILED";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "REMAINING_LSC_COMMAND_BEGIN_FAILED";
        result.failureReason = "vkBeginCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    VkBufferMemoryBarrier sourceReady[2]{};
    sourceReady[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    sourceReady[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    sourceReady[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    sourceReady[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceReady[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceReady[0].buffer = request.bayerInput;
    sourceReady[0].offset = 0u;
    sourceReady[0].size = static_cast<VkDeviceSize>(imageBytes);
    sourceReady[1] = sourceReady[0];
    sourceReady[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    sourceReady[1].buffer = request.sourceClipTransport;
    sourceReady[1].offset = static_cast<VkDeviceSize>(imageBytes);
    sourceReady[1].size = static_cast<VkDeviceSize>(clipBytes);
    vkCmdPipelineBarrier(commandBuffer_,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
            0u, 0u, nullptr, 2u, sourceReady, 0u, nullptr);

    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 4u);
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 0u);
    }
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                            0u, 1u, &descriptorSet_, 0u, nullptr);

    PushConstants push{};
    push.frameWidth = request.frameWidth;
    push.frameHeight = request.frameHeight;
    push.sensorCfaPattern = std::min(request.sensorCfaPattern, 3u);
    push.cfaOffsetX = request.cfaOffsetX;
    push.cfaOffsetY = request.cfaOffsetY;
    push.lensColumns = request.lensShadingColumns;
    push.lensRows = request.lensShadingRows;
    push.lensEnabled = request.lensShadingMap != nullptr &&
            request.lensShadingColumns > 0u && request.lensShadingRows > 0u ? 1u : 0u;
    push.mode = 0u;
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                  (request.frameHeight + 15u) / 16u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
    }

    VkBufferMemoryBarrier outputReady{};
    outputReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    outputReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outputReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    outputReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputReady.buffer = output_.buffer;
    outputReady.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0u, 0u, nullptr, 1u, &outputReady, 0u, nullptr);

    // Preserve original sensor-clipping provenance verbatim. Neural pixels never
    // regenerate this evidence.
    VkBufferCopy clipCopy{};
    clipCopy.srcOffset = static_cast<VkDeviceSize>(imageBytes);
    clipCopy.dstOffset = static_cast<VkDeviceSize>(imageBytes);
    clipCopy.size = static_cast<VkDeviceSize>(clipBytes);
    vkCmdCopyBuffer(commandBuffer_, request.sourceClipTransport, output_.buffer, 1u, &clipCopy);

    if (autoCount > 0u) {
        push.mode = 1u;
        push.sampleColumns = autoColumns;
        push.sampleRows = autoRows;
        push.sampleStride = autoStride;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (autoColumns + 15u) / 16u,
                      (autoRows + 15u) / 16u, 1u);
    }
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 2u);
    }

    VkBufferMemoryBarrier hostReady[2]{};
    hostReady[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    hostReady[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    hostReady[0].dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    hostReady[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostReady[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostReady[0].buffer = telemetry_.buffer;
    hostReady[0].size = VK_WHOLE_SIZE;
    hostReady[1] = hostReady[0];
    hostReady[1].buffer = autoSamples_.buffer;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr,
                         autoCount > 0u ? 2u : 1u, hostReady, 0u, nullptr);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool_, 3u);
    }

    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "REMAINING_LSC_COMMAND_END_FAILED";
        result.failureReason = "vkEndCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer_;
    const auto submitStart = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS) {
        result.status = "REMAINING_LSC_SUBMIT_FAILED";
        result.failureReason = "vkQueueSubmit_failed";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    const VkResult wait = vkWaitForFences(device, 1u, &fence_, VK_TRUE, 4'000'000'000ull);
    result.synchronizationMs = elapsedMs(submitStart);
    if (wait != VK_SUCCESS) {
        result.submissionMayRemainInFlight = true;
        result.status = "GPU_STALLED";
        result.failureReason = "vkWaitForFences_remaining_lsc_" + std::to_string(wait);
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    if (queryPool_ != VK_NULL_HANDLE) {
        std::uint64_t timestamps[4]{0u, 0u, 0u, 0u};
        if (vkGetQueryPoolResults(device, queryPool_, 0u, 4u, sizeof(timestamps), timestamps,
                                  sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physicalDevice, &properties);
            const double msPerTick = static_cast<double>(properties.limits.timestampPeriod) / 1.0e6;
            if (timestamps[1] >= timestamps[0]) {
                result.lscKernelMs = static_cast<float>(
                        static_cast<double>(timestamps[1] - timestamps[0]) * msPerTick);
            }
            if (timestamps[2] >= timestamps[1]) {
                result.autoSceneKernelMs = static_cast<float>(
                        static_cast<double>(timestamps[2] - timestamps[1]) * msPerTick);
            }
        }
    }

    vmaInvalidateAllocation(allocator_, telemetry_.allocation, 0u,
                            static_cast<VkDeviceSize>(telemetryBytes));
    const auto* telemetry = static_cast<const std::uint32_t*>(telemetry_.mapped);
    result.lensCorrectedPixelCount = telemetry[0];
    result.overRangePixelCount = telemetry[1];
    float maxGain = 1.0f;
    std::memcpy(&maxGain, &telemetry[2], sizeof(maxGain));
    result.lensMaximumGain = std::isfinite(maxGain) ? std::max(1.0f, maxGain) : 1.0f;
    result.lensShadingApplied = push.lensEnabled != 0u;

    const auto reductionStart = Clock::now();
    if (autoCount > 0u) {
        vmaInvalidateAllocation(allocator_, autoSamples_.allocation, 0u,
                                static_cast<VkDeviceSize>(autoCount * 4u * sizeof(float)));
        const float* samples = static_cast<const float*>(autoSamples_.mapped);
        std::vector<float> signals;
        std::vector<float> gradients;
        signals.reserve(static_cast<std::size_t>(autoCount));
        gradients.reserve(static_cast<std::size_t>(autoCount));
        double gradientSum = 0.0;
        std::uint64_t edgeCount = 0u;
        std::uint64_t coherentCount = 0u;
        std::uint64_t lowSignalCount = 0u;
        for (std::uint64_t i = 0u; i < autoCount; ++i) {
            const float* record = samples + static_cast<std::size_t>(i) * 4u;
            if (!std::isfinite(record[0]) || !std::isfinite(record[1])) continue;
            signals.push_back(record[0]);
            gradients.push_back(record[1]);
            gradientSum += record[1];
            if (record[1] >= 0.025f) ++edgeCount;
            if (record[2] >= 0.5f) ++coherentCount;
            if (record[3] >= 0.5f) ++lowSignalCount;
        }
        if (!signals.empty()) {
            std::sort(signals.begin(), signals.end());
            std::sort(gradients.begin(), gradients.end());
            result.autoSceneMetricsReady = true;
            result.autoSceneSampleCount = signals.size();
            result.autoSceneMedianSignal = percentileSorted(signals, 0.50f);
            result.autoSceneMeanGradient = static_cast<float>(
                    gradientSum / static_cast<double>(signals.size()));
            result.autoSceneP90Gradient = percentileSorted(gradients, 0.90f);
            const float denominator = static_cast<float>(signals.size());
            result.autoSceneEdgeFraction = static_cast<float>(edgeCount) / denominator;
            result.autoSceneCoherentEdgeFraction = static_cast<float>(coherentCount) / denominator;
            result.autoSceneLowSignalFraction = static_cast<float>(lowSignalCount) / denominator;
        }
    }
    result.autoSceneReductionCpuMs = elapsedMs(reductionStart);

    result.sourceClipConfidenceMapPreserved = true;
    result.success = true;
    ++generationCounter_;
    residentOutputGeneration_ = kGenerationPrefix | (generationCounter_ & 0x00000000ffffffffull);
    residentOutputBytes_ = transportBytes;
    residentOutputWidth_ = request.frameWidth;
    residentOutputHeight_ = request.frameHeight;
    result.residentOutputGeneration = residentOutputGeneration_;
    result.status = result.lensShadingApplied
            ? "NEURAL_REMAINING_LSC_RESIDENT_DEMOSAIC_HANDOFF_READY"
            : "NEURAL_REMAINING_LSC_IDENTITY_RESIDENT_DEMOSAIC_HANDOFF_READY";
    result.failureReason = "none";
    result.totalMs = elapsedMs(totalStart);
    return result;
#endif
}

bool VulkanNeuralRemainingLscBackend::resolveResidentOutput(
        std::uint64_t generation,
        VkBuffer& buffer,
        std::uint64_t& bytes,
        std::uint32_t& width,
        std::uint32_t& height) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
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

void VulkanNeuralRemainingLscBackend::freeBufferLocked(Buffer& buffer) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr && buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, buffer.buffer, buffer.allocation);
    }
#endif
    buffer = {};
}

void VulkanNeuralRemainingLscBackend::destroyLocked(VkDevice device) noexcept {
    freeBufferLocked(output_);
    freeBufferLocked(lensMap_);
    freeBufferLocked(autoSamples_);
    freeBufferLocked(telemetry_);
    allocator_ = nullptr;
    if (device != VK_NULL_HANDLE) {
        if (queryPool_ != VK_NULL_HANDLE) vkDestroyQueryPool(device, queryPool_, nullptr);
        if (fence_ != VK_NULL_HANDLE) vkDestroyFence(device, fence_, nullptr);
        if (commandBuffer_ != VK_NULL_HANDLE && initializedCommandPool_ != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(device, initializedCommandPool_, 1u, &commandBuffer_);
        }
        if (descriptorPool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool_, nullptr);
        if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline_, nullptr);
        if (shaderModule_ != VK_NULL_HANDLE) vkDestroyShaderModule(device, shaderModule_, nullptr);
        if (pipelineLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout_, nullptr);
        if (descriptorSetLayout_ != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorSetLayout_, nullptr);
    }
    initialized_ = false;
    initializedDevice_ = VK_NULL_HANDLE;
    initializedCommandPool_ = VK_NULL_HANDLE;
    descriptorSetLayout_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    shaderModule_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    descriptorPool_ = VK_NULL_HANDLE;
    descriptorSet_ = VK_NULL_HANDLE;
    commandBuffer_ = VK_NULL_HANDLE;
    fence_ = VK_NULL_HANDLE;
    queryPool_ = VK_NULL_HANDLE;
    lensMapGenerationId_ = 0u;
    lensMapColumns_ = 0u;
    lensMapRows_ = 0u;
    residentOutputGeneration_ = 0u;
    residentOutputBytes_ = 0u;
    residentOutputWidth_ = 0u;
    residentOutputHeight_ = 0u;
}

void VulkanNeuralRemainingLscBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

} // namespace bncam::vulkan::neural
