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
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <vector>

namespace bncam::vulkan {
namespace {
using Clock = std::chrono::steady_clock;

float elapsedMs(const Clock::time_point& start) {
    return std::chrono::duration<float, std::milli>(Clock::now() - start).count();
}

struct NormalizePush {
    std::uint32_t inputWidth = 0u;
    std::uint32_t inputHeight = 0u;
    std::uint32_t cropLeft = 0u;
    std::uint32_t cropTop = 0u;
    std::uint32_t width = 0u;
    std::uint32_t height = 0u;
    std::uint32_t phaseX = 0u;
    std::uint32_t phaseY = 0u;

    float whiteLevel = 1.0f;
    std::uint32_t sensorCfaPattern = 0u;
    std::uint32_t noiseModelEnabled = 0u;
    std::uint32_t knownDefectCount = 0u;

    float blackLevels[4]{0.0f, 0.0f, 0.0f, 0.0f};
    float noiseS[4]{0.0f, 0.0f, 0.0f, 0.0f};
    float noiseO[4]{0.0f, 0.0f, 0.0f, 0.0f};

    std::uint32_t mode = 0u;
    std::uint32_t padding0 = 0u;
    std::uint32_t padding1 = 0u;
    std::uint32_t padding2 = 0u;
};
static_assert(sizeof(NormalizePush) == 112u, "P0 RAW normalize push layout mismatch");

constexpr std::uint64_t kTelemetryWords = 16u;
constexpr std::uint64_t kTelemetryBytes = kTelemetryWords * sizeof(std::uint32_t);

bool validNoiseModel(const RawJpegNormalizeRequest& request) noexcept {
    if (!request.noiseModelValid) return false;
    bool hasEnergy = false;
    for (std::size_t ch = 0; ch < 4u; ++ch) {
        const float s = request.effectiveS[ch];
        const float o = request.effectiveO[ch];
        if (!std::isfinite(s) || !std::isfinite(o) || s < 0.0f || o < 0.0f) return false;
        hasEnergy = hasEnergy || s > 0.0f || o > 0.0f;
    }
    return hasEnergy;
}
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
    if (target.buffer != VK_NULL_HANDLE && target.allocation != nullptr &&
        target.capacityBytes >= bytes) {
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

bool VulkanRawJpegNormalizeBackend::ensureMappedBufferLocked(
        VmaAllocator allocator,
        std::uint64_t bytes,
        std::uint32_t hostAccess,
        PersistentBuffer& target,
        std::string& failureReason) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)hostAccess; (void)target;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u) {
        failureReason = "RAW_JPEG_NORMALIZE_MAPPED_BUFFER_REQUEST_INVALID";
        return false;
    }
    if (target.buffer != VK_NULL_HANDLE && target.allocation != nullptr &&
        target.mapped != nullptr && target.capacityBytes >= bytes) {
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
    allocationInfo.usage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST;
    allocationInfo.flags = hostAccess | VMA_ALLOCATION_CREATE_MAPPED_BIT;
    VmaAllocationInfo allocationResult{};
    const VkResult result = vmaCreateBuffer(
            allocator, &bufferInfo, &allocationInfo,
            &target.buffer, &target.allocation, &allocationResult);
    if (result != VK_SUCCESS || target.buffer == VK_NULL_HANDLE ||
        target.allocation == nullptr || allocationResult.pMappedData == nullptr) {
        target = {};
        failureReason = "RAW_JPEG_NORMALIZE_MAPPED_VMA_BUFFER_FAILED_" + std::to_string(result);
        return false;
    }
    target.mapped = allocationResult.pMappedData;
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
    destroyBufferLocked(coordinates_);
    destroyBufferLocked(telemetry_);
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
    boundCoordinatesBuffer_ = VK_NULL_HANDLE;
    boundCoordinatesRange_ = 0u;
    boundTelemetryBuffer_ = VK_NULL_HANDLE;
    boundTelemetryRange_ = 0u;
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

    VkDescriptorSetLayoutBinding bindings[4]{};
    for (std::uint32_t i = 0u; i < 4u; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo setInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    setInfo.bindingCount = 4u;
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
    if (VulkanPipelineCacheRegistry::createComputePipelines(
            device, 1u, &pipelineInfo, nullptr, &pipeline_) != VK_SUCCESS) {
        failureReason = "RAW_JPEG_NORMALIZE_PIPELINE_FAILED";
        destroyLocked(device);
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 4u;
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
    if (request.knownDefectCount > pixels ||
        (request.knownDefectCount > 0u && request.knownDefectCoordinates == nullptr)) {
        out.failureReason = "RAW_JPEG_NORMALIZE_DEFECT_MAP_INVALID";
        return out;
    }
    for (std::uint32_t i = 0u; i < request.knownDefectCount; ++i) {
        const std::int32_t x = request.knownDefectCoordinates[i * 2u];
        const std::int32_t y = request.knownDefectCoordinates[i * 2u + 1u];
        if (x < 0 || y < 0 || x >= static_cast<std::int32_t>(request.width) ||
            y >= static_cast<std::int32_t>(request.height)) {
            out.failureReason = "RAW_JPEG_NORMALIZE_DEFECT_COORDINATE_OUT_OF_RANGE";
            return out;
        }
    }

    const bool noiseModelEnabled = validNoiseModel(request);
    out.noiseAdaptiveDefectDetectionEnabled = noiseModelEnabled;
    out.knownDefectMapPointCount = request.knownDefectCount;

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

    const std::uint64_t coordinateBytes = std::max<std::uint64_t>(
        2u * sizeof(std::int32_t),
        static_cast<std::uint64_t>(request.knownDefectCount) * 2u * sizeof(std::int32_t)
    );
    const std::uint32_t writeAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
    const std::uint32_t readWriteAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    if (!initializeLocked(device, out.failureReason) ||
        !ensureBufferLocked(allocator_, outputBytes, output_, out.failureReason) ||
        !ensureMappedBufferLocked(
            allocator_, coordinateBytes, writeAccess, coordinates_, out.failureReason) ||
        !ensureMappedBufferLocked(
            allocator_, kTelemetryBytes, readWriteAccess, telemetry_, out.failureReason)) {
        return out;
    }

    const auto uploadStart = Clock::now();
    std::memset(coordinates_.mapped, 0, static_cast<std::size_t>(coordinateBytes));
    if (request.knownDefectCount > 0u) {
        const std::size_t usedCoordinateBytes =
            static_cast<std::size_t>(request.knownDefectCount) * 2u * sizeof(std::int32_t);
        std::memcpy(coordinates_.mapped, request.knownDefectCoordinates, usedCoordinateBytes);
        out.sparseMetadataUploadBytes = usedCoordinateBytes;
    }
    std::memset(telemetry_.mapped, 0, static_cast<std::size_t>(kTelemetryBytes));
    vmaFlushAllocation(
        allocator_, coordinates_.allocation, 0u, static_cast<VkDeviceSize>(coordinateBytes));
    vmaFlushAllocation(
        allocator_, telemetry_.allocation, 0u, static_cast<VkDeviceSize>(kTelemetryBytes));
    out.sparseMetadataUploadMs = elapsedMs(uploadStart);

    VkDescriptorBufferInfo infos[4] = {
        {request.canonicalRawBuffer, 0u, static_cast<VkDeviceSize>(rawBytes)},
        {output_.buffer, 0u, static_cast<VkDeviceSize>(outputBytes)},
        {coordinates_.buffer, 0u, static_cast<VkDeviceSize>(coordinateBytes)},
        {telemetry_.buffer, 0u, static_cast<VkDeviceSize>(kTelemetryBytes)}
    };
    VkBuffer* boundBuffers[4] = {
        &boundInputBuffer_, &boundOutputBuffer_, &boundCoordinatesBuffer_, &boundTelemetryBuffer_
    };
    VkDeviceSize* boundRanges[4] = {
        &boundInputRange_, &boundOutputRange_, &boundCoordinatesRange_, &boundTelemetryRange_
    };
    VkWriteDescriptorSet writes[4]{};
    std::uint32_t writeCount = 0u;
    for (std::uint32_t binding = 0u; binding < 4u; ++binding) {
        if (*boundBuffers[binding] == infos[binding].buffer &&
            *boundRanges[binding] == infos[binding].range) {
            ++out.descriptorBindingsReused;
            continue;
        }
        auto& write = writes[writeCount++];
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = descriptorSet_;
        write.dstBinding = binding;
        write.descriptorCount = 1u;
        write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        write.pBufferInfo = &infos[binding];
        *boundBuffers[binding] = infos[binding].buffer;
        *boundRanges[binding] = infos[binding].range;
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

    VkBufferMemoryBarrier inputBarriers[3]{};
    inputBarriers[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    inputBarriers[0].srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    inputBarriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    inputBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarriers[0].buffer = request.canonicalRawBuffer;
    inputBarriers[0].offset = 0u;
    inputBarriers[0].size = static_cast<VkDeviceSize>(rawBytes);

    inputBarriers[1].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    inputBarriers[1].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    inputBarriers[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    inputBarriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarriers[1].buffer = coordinates_.buffer;
    inputBarriers[1].offset = 0u;
    inputBarriers[1].size = static_cast<VkDeviceSize>(coordinateBytes);

    inputBarriers[2].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    inputBarriers[2].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    inputBarriers[2].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    inputBarriers[2].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarriers[2].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarriers[2].buffer = telemetry_.buffer;
    inputBarriers[2].offset = 0u;
    inputBarriers[2].size = static_cast<VkDeviceSize>(kTelemetryBytes);

    vkCmdPipelineBarrier(
        command,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK_PIPELINE_STAGE_HOST_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0u, 0u, nullptr, 3u, inputBarriers, 0u, nullptr);

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
    push.sensorCfaPattern = std::min(request.sensorCfaPattern, 3u);
    push.noiseModelEnabled = noiseModelEnabled ? 1u : 0u;
    push.knownDefectCount = request.knownDefectCount;
    std::copy(request.blackLevels.begin(), request.blackLevels.end(), push.blackLevels);
    std::copy(request.effectiveS.begin(), request.effectiveS.end(), push.noiseS);
    std::copy(request.effectiveO.begin(), request.effectiveO.end(), push.noiseO);

    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(
            command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
            0u, 1u, &descriptorSet_, 0u, nullptr);

    push.mode = 0u;
    vkCmdPushConstants(
        command, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    const auto kernelStart = Clock::now();
    vkCmdDispatch(command, (request.width + 15u) / 16u, (request.height + 15u) / 16u, 1u);

    if (request.knownDefectCount > 0u) {
        VkBufferMemoryBarrier betweenPasses{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        betweenPasses.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        betweenPasses.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        betweenPasses.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        betweenPasses.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        betweenPasses.buffer = output_.buffer;
        betweenPasses.offset = 0u;
        betweenPasses.size = static_cast<VkDeviceSize>(outputBytes);
        vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u, 0u, nullptr, 1u, &betweenPasses, 0u, nullptr);

        push.mode = 1u;
        vkCmdPushConstants(
            command, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
        // y-dispatch is one workgroup; mode 1 explicitly permits only global y==0.
        vkCmdDispatch(command, (request.knownDefectCount + 15u) / 16u, 1u, 1u);
    }

    VkBufferMemoryBarrier finalBarriers[2]{};
    finalBarriers[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    finalBarriers[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    finalBarriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    finalBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    finalBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    finalBarriers[0].buffer = output_.buffer;
    finalBarriers[0].offset = 0u;
    finalBarriers[0].size = static_cast<VkDeviceSize>(outputBytes);

    finalBarriers[1].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    finalBarriers[1].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    finalBarriers[1].dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    finalBarriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    finalBarriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    finalBarriers[1].buffer = telemetry_.buffer;
    finalBarriers[1].offset = 0u;
    finalBarriers[1].size = static_cast<VkDeviceSize>(kTelemetryBytes);

    vkCmdPipelineBarrier(
        command,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
        0u, 0u, nullptr, 2u, finalBarriers, 0u, nullptr);

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
    const VkResult wait = vkWaitForFences(
        device, 1u, &reusableFence_, VK_TRUE, 1'500'000'000ull);
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

    vmaInvalidateAllocation(
        allocator_, telemetry_.allocation, 0u, static_cast<VkDeviceSize>(kTelemetryBytes));
    const auto* telemetry = static_cast<const std::uint32_t*>(telemetry_.mapped);
    out.knownDefectCorrectedPixelCount = telemetry[0];
    out.residualDefectCorrectedPixelCount = telemetry[1];
    out.knownDefectBorderSkipCount = telemetry[2];
    out.knownDefectInvalidCoordinateCount = telemetry[3];
    out.knownDefectMapApplied = out.knownDefectCorrectedPixelCount > 0u;

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
