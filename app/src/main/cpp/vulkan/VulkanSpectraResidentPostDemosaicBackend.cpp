#include "VulkanSpectraResidentPostDemosaicBackend.h"
#include "VulkanPipelineCacheRegistry.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_SPECTRA_POST_DEMOSAIC_SHADER_AVAILABLE
#define BNCAM_SPECTRA_POST_DEMOSAIC_SHADER_AVAILABLE 0
#endif

#if BNCAM_SPECTRA_POST_DEMOSAIC_SHADER_AVAILABLE
#include "SpectraResidentPostDemosaicSpirv.h"
#endif

#include "VulkanRuntime.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>

namespace bncam::vulkan {
namespace {
using Clock = std::chrono::steady_clock;

float elapsedMs(Clock::time_point started) {
    return static_cast<float>(std::chrono::duration<double, std::milli>(
            Clock::now() - started).count());
}

struct alignas(16) PushConstants {
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::uint32_t inputOriginY = 0;
    std::uint32_t inputRows = 0;
    std::uint32_t outputOriginY = 0;
    std::uint32_t outputRows = 0;
    std::uint32_t gridWidth = 0;
    std::uint32_t gridHeight = 0;
    std::uint32_t mode = 0;
    std::uint32_t spectraNoiseActive = 0;
    std::uint32_t visibleChromaEnabled = 0;
    float meanSpatialSigma = 0.0f;
    float appliedLumaSigma = 0.0f;
    float lumaRangeThresholdMean = 0.0f;
    float chromaRangeThresholdMean = 0.0f;
    float outerRingAuthority = 0.0f;
    float profileNrColor = 0.0f;
    float chromaNrStrength = 0.0f;
    float chromaUserScale = 1.0f;
    float downstreamChromaAuthority = 1.0f;
    float noiseModelMultiplier = 1.0f;
    float configuredDynamicIsoCoeff = 0.0f;
    float downstreamLumaAuthority = 1.0f;
    float visibleSigmaY = 0.0f;
    float visibleAuthority = 0.0f;
    float visibleMaximumCorrection = 0.0f;
    float inverse00 = 0.0f;
    float inverse01 = 0.0f;
    float inverse11 = 0.0f;
    float padding1 = 0.0f;
    float padding2 = 0.0f;
    float padding3 = 0.0f;
};
static_assert(sizeof(PushConstants) == 128u,
              "resident post-demosaic push layout mismatch");

[[maybe_unused]] bool finitePositive(float value) {
    return std::isfinite(value) && value > 0.0f;
}

[[maybe_unused]] bool rangeContained(std::uint32_t outerOrigin, std::uint32_t outerRows,
                    std::uint32_t innerOrigin, std::uint32_t innerRows) {
    const std::uint64_t outerEnd = static_cast<std::uint64_t>(outerOrigin) + outerRows;
    const std::uint64_t innerEnd = static_cast<std::uint64_t>(innerOrigin) + innerRows;
    return innerOrigin >= outerOrigin && innerEnd <= outerEnd;
}

[[maybe_unused]] bool multiplyChecked(std::uint64_t lhs, std::uint64_t rhs, std::uint64_t& out) {
    if (lhs == 0u || rhs == 0u) {
        out = 0u;
        return true;
    }
    if (lhs > std::numeric_limits<std::uint64_t>::max() / rhs) return false;
    out = lhs * rhs;
    return true;
}

[[maybe_unused]] bool addChecked(std::uint64_t lhs, std::uint64_t rhs, std::uint64_t& out) {
    if (lhs > std::numeric_limits<std::uint64_t>::max() - rhs) return false;
    out = lhs + rhs;
    return true;
}

[[maybe_unused]] bool packedBgr8RowBytes(std::uint32_t width, std::uint64_t& out) {
    std::uint64_t rawBytes = 0u;
    std::uint64_t padded = 0u;
    if (!multiplyChecked(static_cast<std::uint64_t>(width), 3u, rawBytes) ||
        !addChecked(rawBytes, 3u, padded)) {
        return false;
    }
    out = (padded / 4u) * 4u;
    return out >= rawBytes;
}
} // namespace

bool VulkanSpectraResidentPostDemosaicBackend::productionKernelConnected() const noexcept {
#if BNCAM_SPECTRA_POST_DEMOSAIC_SHADER_AVAILABLE
    return !getSpectraResidentPostDemosaicSpirv().empty();
#else
    return false;
#endif
}

bool VulkanSpectraResidentPostDemosaicBackend::pipelineInitialized() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return initialized_;
}

bool VulkanSpectraResidentPostDemosaicBackend::ensureBufferLocked(
        VmaAllocator allocator,
        std::uint64_t bytes,
        std::uint32_t hostAccess,
        PersistentBuffer& buffer,
        bool& reallocated,
        std::string& failureReason
) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void) allocator; (void) bytes; (void) hostAccess; (void) buffer; (void) reallocated;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u ||
        bytes > static_cast<std::uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        failureReason = "INVALID_POST_DEMOSAIC_BUFFER_REQUEST";
        return false;
    }
    const bool mappedRequired = hostAccess != 0u;
    const bool reusable = buffer.buffer != VK_NULL_HANDLE &&
            buffer.allocation != nullptr && buffer.capacityBytes >= bytes &&
            (!mappedRequired || buffer.mapped != nullptr);
    if (reusable) return true;

    if (buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, buffer.buffer, buffer.allocation);
    }
    buffer = {};
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = static_cast<VkDeviceSize>(bytes);
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo allocationInfo{};
    allocationInfo.usage = mappedRequired
            ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST
            : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    allocationInfo.flags = mappedRequired
            ? hostAccess | VMA_ALLOCATION_CREATE_MAPPED_BIT
            : 0u;
    VmaAllocationInfo allocationResult{};
    const VkResult result = vmaCreateBuffer(
            allocator, &info, &allocationInfo,
            &buffer.buffer, &buffer.allocation, &allocationResult);
    if (result != VK_SUCCESS || buffer.buffer == VK_NULL_HANDLE ||
        buffer.allocation == nullptr ||
        (mappedRequired && allocationResult.pMappedData == nullptr)) {
        buffer = {};
        failureReason = "vmaCreateBuffer_resident_post_demosaic_failed_" +
                std::to_string(result);
        return false;
    }
    buffer.mapped = allocationResult.pMappedData;
    buffer.capacityBytes = bytes;
    reallocated = true;
    allocationGeneration_++;
    return true;
#endif
}

void VulkanSpectraResidentPostDemosaicBackend::destroyBuffersLocked() noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr) {
        for (PersistentBuffer* buffer : {
                &input_, &intermediate_, &output_, &spatialMap_,
                &spatialTelemetry_, &visibleTelemetry_}) {
            if (buffer->buffer != VK_NULL_HANDLE && buffer->allocation != nullptr) {
                vmaDestroyBuffer(allocator_, buffer->buffer, buffer->allocation);
            }
            *buffer = {};
        }
    }
#endif
    allocator_ = nullptr;
}

void VulkanSpectraResidentPostDemosaicBackend::destroyLocked(VkDevice device) noexcept {
    destroyBuffersLocked();
    if (device != VK_NULL_HANDLE) {
        if (queryPool_ != VK_NULL_HANDLE) vkDestroyQueryPool(device, queryPool_, nullptr);
        if (fence_ != VK_NULL_HANDLE) vkDestroyFence(device, fence_, nullptr);
        if (descriptorPool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool_, nullptr);
        if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline_, nullptr);
        if (shaderModule_ != VK_NULL_HANDLE) vkDestroyShaderModule(device, shaderModule_, nullptr);
        if (pipelineLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout_, nullptr);
        if (descriptorSetLayout_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout_, nullptr);
        }
    }
    initialized_ = false;
    initializedDevice_ = VK_NULL_HANDLE;
    initializedCommandPool_ = VK_NULL_HANDLE;
    descriptorSetLayout_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    shaderModule_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    descriptorPool_ = VK_NULL_HANDLE;
    descriptorSets_ = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    commandBuffer_ = VK_NULL_HANDLE;
    fence_ = VK_NULL_HANDLE;
    queryPool_ = VK_NULL_HANDLE;
    descriptorBindingsInitialized_ = false;
    spatialGenerationId_ = 0u;
    spatialGenerationBytes_ = 0u;
}

void VulkanSpectraResidentPostDemosaicBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanSpectraResidentPostDemosaicBackend::initializeLocked(
        VkDevice device,
        VkCommandPool commandPool,
        std::string& failureReason
) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device && initializedCommandPool_ == commandPool) return true;
        failureReason = "POST_DEMOSAIC_BACKEND_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_SPECTRA_POST_DEMOSAIC_SHADER_AVAILABLE
    (void) device; (void) commandPool;
    failureReason = "RESIDENT_POST_DEMOSAIC_SHADER_NOT_COMPILED";
    return false;
#else
    const auto& spirv = getSpectraResidentPostDemosaicSpirv();
    if (device == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE || spirv.empty()) {
        failureReason = "POST_DEMOSAIC_INITIALIZATION_INPUT_INVALID";
        return false;
    }

    VkDescriptorSetLayoutBinding bindings[4]{};
    for (std::uint32_t i = 0; i < 4u; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo descriptorInfo{};
    descriptorInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorInfo.bindingCount = 4u;
    descriptorInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr,
                                    &descriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorSetLayout_post_demosaic_failed";
        destroyLocked(device);
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.size = sizeof(PushConstants);
    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1u;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;
    layoutInfo.pushConstantRangeCount = 1u;
    layoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr,
                               &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "vkCreatePipelineLayout_post_demosaic_failed";
        destroyLocked(device);
        return false;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &shaderInfo, nullptr,
                             &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_post_demosaic_failed";
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
    if (VulkanPipelineCacheRegistry::createComputePipelines(device, 1u, &pipelineInfo,
                                 nullptr, &pipeline_) != VK_SUCCESS) {
        failureReason = "vkCreateComputePipelines_post_demosaic_failed";
        destroyLocked(device);
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 8u;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 2u;
    poolInfo.poolSizeCount = 1u;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr,
                               &descriptorPool_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorPool_post_demosaic_failed";
        destroyLocked(device);
        return false;
    }
    const std::array<VkDescriptorSetLayout, 2> layouts{
            descriptorSetLayout_, descriptorSetLayout_};
    VkDescriptorSetAllocateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setInfo.descriptorPool = descriptorPool_;
    setInfo.descriptorSetCount = 2u;
    setInfo.pSetLayouts = layouts.data();
    if (vkAllocateDescriptorSets(device, &setInfo,
                                 descriptorSets_.data()) != VK_SUCCESS) {
        failureReason = "vkAllocateDescriptorSets_post_demosaic_failed";
        destroyLocked(device);
        return false;
    }

    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &commandInfo,
                                 &commandBuffer_) != VK_SUCCESS) {
        failureReason = "vkAllocateCommandBuffers_post_demosaic_failed";
        destroyLocked(device);
        return false;
    }

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence_) != VK_SUCCESS) {
        failureReason = "vkCreateFence_post_demosaic_failed";
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
    return true;
#endif
}

void VulkanSpectraResidentPostDemosaicBackend::updateDescriptorSetsLocked(
        VkDevice device,
        VkBuffer inputOverride,
        std::uint64_t inputOverrideBytes
) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE && BNCAM_SPECTRA_POST_DEMOSAIC_SHADER_AVAILABLE
    VkDescriptorBufferInfo spatialInfos[4]{};
    const VkBuffer spatialInputBuffer = inputOverride != VK_NULL_HANDLE
            ? inputOverride : input_.buffer;
    const std::uint64_t spatialInputBytes = inputOverride != VK_NULL_HANDLE
            ? inputOverrideBytes : input_.capacityBytes;
    spatialInfos[0] = {spatialInputBuffer, 0u, static_cast<VkDeviceSize>(spatialInputBytes)};
    spatialInfos[1] = {intermediate_.buffer, 0u,
                       static_cast<VkDeviceSize>(intermediate_.capacityBytes)};
    spatialInfos[2] = {spatialMap_.buffer, 0u,
                       static_cast<VkDeviceSize>(spatialMap_.capacityBytes)};
    spatialInfos[3] = {spatialTelemetry_.buffer, 0u,
                       static_cast<VkDeviceSize>(spatialTelemetry_.capacityBytes)};

    VkDescriptorBufferInfo visibleInfos[4]{};
    visibleInfos[0] = {intermediate_.buffer, 0u,
                       static_cast<VkDeviceSize>(intermediate_.capacityBytes)};
    visibleInfos[1] = {output_.buffer, 0u, static_cast<VkDeviceSize>(output_.capacityBytes)};
    visibleInfos[2] = {spatialMap_.buffer, 0u,
                       static_cast<VkDeviceSize>(spatialMap_.capacityBytes)};
    visibleInfos[3] = {visibleTelemetry_.buffer, 0u,
                       static_cast<VkDeviceSize>(visibleTelemetry_.capacityBytes)};

    VkWriteDescriptorSet writes[8]{};
    for (std::uint32_t set = 0; set < 2u; ++set) {
        VkDescriptorBufferInfo* infos = set == 0u ? spatialInfos : visibleInfos;
        for (std::uint32_t binding = 0; binding < 4u; ++binding) {
            const std::uint32_t index = set * 4u + binding;
            writes[index].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[index].dstSet = descriptorSets_[set];
            writes[index].dstBinding = binding;
            writes[index].descriptorCount = 1u;
            writes[index].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[index].pBufferInfo = &infos[binding];
        }
    }
    vkUpdateDescriptorSets(device, 8u, writes, 0u, nullptr);
    descriptorBindingsInitialized_ = true;
#else
    (void) device;
    (void) inputOverride;
    (void) inputOverrideBytes;
    descriptorBindingsInitialized_ = false;
#endif
}

SpectraResidentPostDemosaicResult
VulkanSpectraResidentPostDemosaicBackend::execute(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const SpectraResidentPostDemosaicRequest& request
) noexcept {
    SpectraResidentPostDemosaicResult result{};
    result.attempted = true;
    result.pipelineAvailable = productionKernelConnected();
    const auto totalStarted = Clock::now();

#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_POST_DEMOSAIC_SHADER_AVAILABLE
    (void) physicalDevice; (void) device; (void) computeQueue; (void) commandPool;
    (void) allocatorOwner; (void) request;
    result.status = "RESIDENT_POST_DEMOSAIC_UNAVAILABLE";
    result.failureReason = !BNCAM_VMA_HEADER_AVAILABLE
            ? "VMA_HEADER_NOT_AVAILABLE"
            : "RESIDENT_POST_DEMOSAIC_SHADER_NOT_COMPILED";
    result.totalMs = elapsedMs(totalStarted);
    return result;
#else
    const bool residentInputUsed = request.residentInputBuffer != VK_NULL_HANDLE;
    if ((!residentInputUsed && request.rgbData == nullptr) || request.spatialSigma == nullptr ||
        request.frameWidth == 0u || request.frameHeight == 0u ||
        request.inputRows == 0u || request.intermediateRows == 0u ||
        request.outputRows == 0u || request.gridWidth == 0u ||
        request.gridHeight == 0u ||
        request.rowStrideFloats < static_cast<std::size_t>(request.frameWidth) * 3u ||
        !rangeContained(request.inputOriginY, request.inputRows,
                        request.intermediateOriginY, request.intermediateRows) ||
        !rangeContained(request.intermediateOriginY, request.intermediateRows,
                        request.outputOriginY, request.outputRows) ||
        static_cast<std::uint64_t>(request.inputOriginY) + request.inputRows >
                request.frameHeight ||
        !finitePositive(request.meanSpatialSigma) ||
        !std::isfinite(request.legacySharpenAmount) || request.legacySharpenAmount < 0.0f ||
        (request.visibleChromaEnabled && (
                !finitePositive(request.visibleSigmaY) ||
                !std::isfinite(request.inverse00) ||
                !std::isfinite(request.inverse01) ||
                !std::isfinite(request.inverse11)))) {
        result.status = "INVALID_RESIDENT_POST_DEMOSAIC_REQUEST";
        result.failureReason = "FRAME_RANGES_STRIDE_OR_NOISE_PARAMETERS_INVALID";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }

    std::uint64_t floatRowBytes = 0u;
    std::uint64_t requiredInputBytes = 0u;
    std::uint64_t packedRowBytes = 0u;
    if (!multiplyChecked(request.frameWidth, 3u * sizeof(float), floatRowBytes) ||
        !multiplyChecked(floatRowBytes, request.inputRows, requiredInputBytes) ||
        !multiplyChecked(floatRowBytes, request.intermediateRows, result.intermediateBytes) ||
        !packedBgr8RowBytes(request.frameWidth, packedRowBytes)) {
        result.status = "RESIDENT_POST_DEMOSAIC_SIZE_OVERFLOW";
        result.failureReason = "RGB_OR_BGR8_BUFFER_SIZE_OVERFLOW";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.outputRowStrideBytes = request.packedBgr8Publication
            ? packedRowBytes : floatRowBytes;
    if (!multiplyChecked(result.outputRowStrideBytes, request.outputRows, result.outputBytes)) {
        result.status = "RESIDENT_POST_DEMOSAIC_SIZE_OVERFLOW";
        result.failureReason = "PUBLICATION_BUFFER_SIZE_OVERFLOW";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    if (residentInputUsed && request.residentInputBytes < requiredInputBytes) {
        result.status = "INVALID_RESIDENT_POST_DEMOSAIC_REQUEST";
        result.failureReason = "RESIDENT_RGB_BUFFER_TOO_SMALL";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.residentInputUsed = residentInputUsed;
    result.packedBgr8Published = request.packedBgr8Publication;
    result.floatOutputFallback = !request.packedBgr8Publication;
    // inputBytes is transfer accounting, not the logical buffer span. A resident handoff uploads 0 bytes.
    result.inputBytes = residentInputUsed ? 0u : requiredInputBytes;
    std::uint64_t spatialElements = 0u;
    std::uint64_t requiredSpatialMapBytes = 0u;
    if (!multiplyChecked(request.gridWidth, request.gridHeight, spatialElements) ||
        !multiplyChecked(spatialElements, sizeof(float), requiredSpatialMapBytes)) {
        result.status = "RESIDENT_POST_DEMOSAIC_SIZE_OVERFLOW";
        result.failureReason = "SPATIAL_MAP_SIZE_OVERFLOW";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    constexpr std::uint64_t telemetryBytes = 16u * sizeof(std::uint32_t);

    std::lock_guard<std::mutex> lock(mutex_);
    if (!initializeLocked(device, commandPool, result.failureReason)) {
        result.status = "RESIDENT_POST_DEMOSAIC_INITIALIZATION_FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr) {
        result.status = "RESIDENT_POST_DEMOSAIC_ALLOCATOR_UNAVAILABLE";
        result.failureReason = "AUTHORITATIVE_VMA_ALLOCATOR_NULL";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }

    bool inputReallocated = false;
    bool intermediateReallocated = false;
    bool outputReallocated = false;
    bool spatialReallocated = false;
    bool spatialTelemetryReallocated = false;
    bool visibleTelemetryReallocated = false;
    if ((!residentInputUsed && !ensureBufferLocked(allocator_, requiredInputBytes,
            VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT,
            input_, inputReallocated, result.failureReason)) ||
        !ensureBufferLocked(allocator_, result.intermediateBytes, 0u,
            intermediate_, intermediateReallocated, result.failureReason) ||
        !ensureBufferLocked(allocator_, result.outputBytes,
            VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT,
            output_, outputReallocated, result.failureReason) ||
        !ensureBufferLocked(allocator_, requiredSpatialMapBytes,
            VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT,
            spatialMap_, spatialReallocated, result.failureReason) ||
        !ensureBufferLocked(allocator_, telemetryBytes,
            VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT,
            spatialTelemetry_, spatialTelemetryReallocated, result.failureReason) ||
        !ensureBufferLocked(allocator_, telemetryBytes,
            VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT,
            visibleTelemetry_, visibleTelemetryReallocated, result.failureReason)) {
        result.status = "RESIDENT_POST_DEMOSAIC_BUFFER_ALLOCATION_FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    const bool reallocated = inputReallocated || intermediateReallocated ||
            outputReallocated || spatialReallocated ||
            spatialTelemetryReallocated || visibleTelemetryReallocated;
    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = (residentInputUsed ? 0u : input_.capacityBytes) +
            intermediate_.capacityBytes + output_.capacityBytes + spatialMap_.capacityBytes +
            spatialTelemetry_.capacityBytes + visibleTelemetry_.capacityBytes;

    const auto packingStarted = Clock::now();
    if (!residentInputUsed) {
        float* packed = static_cast<float*>(input_.mapped);
        const std::size_t packedRowFloats = static_cast<std::size_t>(request.frameWidth) * 3u;
        if (request.rowStrideFloats == packedRowFloats) {
            std::memcpy(packed, request.rgbData,
                        static_cast<std::size_t>(requiredInputBytes));
        } else {
            for (std::uint32_t row = 0; row < request.inputRows; ++row) {
                const float* source = request.rgbData +
                        static_cast<std::size_t>(row) * request.rowStrideFloats;
                std::memcpy(packed + static_cast<std::size_t>(row) * packedRowFloats,
                            source, packedRowFloats * sizeof(float));
            }
        }
        vmaFlushAllocation(allocator_, input_.allocation, 0u, requiredInputBytes);
    }
    const bool uploadSpatialMap = spatialReallocated ||
            spatialGenerationId_ != request.generationId ||
            spatialGenerationBytes_ != requiredSpatialMapBytes;
    if (uploadSpatialMap) {
        std::memcpy(spatialMap_.mapped, request.spatialSigma,
                    static_cast<std::size_t>(requiredSpatialMapBytes));
        vmaFlushAllocation(allocator_, spatialMap_.allocation, 0u,
                           requiredSpatialMapBytes);
        spatialGenerationId_ = request.generationId;
        spatialGenerationBytes_ = requiredSpatialMapBytes;
        result.spatialMapBytes = requiredSpatialMapBytes;
    }
    std::memset(spatialTelemetry_.mapped, 0, static_cast<std::size_t>(telemetryBytes));
    std::memset(visibleTelemetry_.mapped, 0, static_cast<std::size_t>(telemetryBytes));
    vmaFlushAllocation(allocator_, spatialTelemetry_.allocation, 0u, telemetryBytes);
    vmaFlushAllocation(allocator_, visibleTelemetry_.allocation, 0u, telemetryBytes);
    result.inputPackingMs = elapsedMs(packingStarted);

    // Rebind every dispatch because a previous capture may have used the alternate
    // resident-input path while the next one uses the host-staging fallback (or vice versa).
    updateDescriptorSetsLocked(
            device,
            residentInputUsed ? request.residentInputBuffer : VK_NULL_HANDLE,
            residentInputUsed ? request.residentInputBytes : 0u);

    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "POST_DEMOSAIC_COMMAND_RECORDING_FAILED";
        result.failureReason = "vkBeginCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }

    result.timestampQueryUsed = queryPool_ != VK_NULL_HANDLE;
    if (result.timestampQueryUsed) {
        vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 4u);
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                            queryPool_, 0u);
    }
    if (residentInputUsed) {
        VkBufferMemoryBarrier residentInputBarrier{};
        residentInputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        residentInputBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        residentInputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        residentInputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        residentInputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        residentInputBarrier.buffer = request.residentInputBuffer;
        residentInputBarrier.offset = 0u;
        residentInputBarrier.size = static_cast<VkDeviceSize>(request.residentInputBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &residentInputBarrier, 0u, nullptr);
    }
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);

    PushConstants push{};
    push.frameWidth = request.frameWidth;
    push.frameHeight = request.frameHeight;
    push.gridWidth = request.gridWidth;
    push.gridHeight = request.gridHeight;
    push.meanSpatialSigma = request.meanSpatialSigma;
    push.appliedLumaSigma = request.appliedLumaSigma;
    push.lumaRangeThresholdMean = request.lumaRangeThresholdMean;
    push.chromaRangeThresholdMean = request.chromaRangeThresholdMean;
    push.outerRingAuthority = std::clamp(request.outerRingAuthority, 0.0f, 1.0f);
    push.profileNrColor = std::clamp(request.profileNrColor, 0.0f, 1.0f);
    push.chromaNrStrength = std::clamp(request.chromaNrStrength, 0.0f, 1.0f);
    push.chromaUserScale = request.chromaUserScale;
    push.downstreamChromaAuthority = std::clamp(request.downstreamChromaAuthority, 0.0f, 1.0f);
    push.noiseModelMultiplier = request.noiseModelMultiplier;
    push.configuredDynamicIsoCoeff = std::clamp(request.configuredDynamicIsoCoeff, 0.0f, 1.0f);
    push.downstreamLumaAuthority = std::clamp(request.downstreamLumaAuthority, 0.0f, 1.0f);
    // Mode 0 multiplexes mode-1-only slots for the six Lightroom NR controls, keeping
    // the Vulkan push-constant contract at 128 bytes. Every slot is restored before mode 1.
    push.visibleSigmaY = std::clamp(request.profileNrLuminance, 0.0f, 1.0f);
    push.visibleAuthority = std::clamp(request.profileNrLuminanceDetail, 0.0f, 1.0f);
    push.visibleMaximumCorrection = std::clamp(request.profileNrLuminanceContrast, 0.0f, 1.0f);
    push.inverse00 = std::clamp(request.profileNrColorDetail, 0.0f, 1.0f);
    push.inverse01 = std::clamp(request.profileNrColorSmoothness, 0.0f, 1.0f);
    push.inverse11 = request.inverse11;
    push.padding1 = residentInputUsed ? 1.0f : 0.0f;
    push.padding2 = std::clamp(request.profileSpectraLuma, -1.0f, 1.0f);
    push.padding3 = std::clamp(request.profileSpectraDetail, -1.0f, 1.0f);
    push.spectraNoiseActive = request.spectraNoiseActive ? 1u : 0u;
    push.visibleChromaEnabled = request.visibleChromaEnabled ? 1u : 0u;

    push.mode = 0u;
    push.inputOriginY = request.inputOriginY;
    push.inputRows = request.inputRows;
    push.outputOriginY = request.intermediateOriginY;
    push.outputRows = request.intermediateRows;
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE,
                            pipelineLayout_, 0u, 1u, &descriptorSets_[0], 0u, nullptr);
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                  (request.intermediateRows + 15u) / 16u, 1u);
    if (result.timestampQueryUsed) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            queryPool_, 1u);
    }

    VkBufferMemoryBarrier intermediateBarrier{};
    intermediateBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    intermediateBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    intermediateBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    intermediateBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    intermediateBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    intermediateBarrier.buffer = intermediate_.buffer;
    intermediateBarrier.offset = 0u;
    intermediateBarrier.size = static_cast<VkDeviceSize>(result.intermediateBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                         0u, nullptr, 1u, &intermediateBarrier, 0u, nullptr);
    if (result.timestampQueryUsed) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            queryPool_, 2u);
    }

    push.mode = 1u;
    push.visibleSigmaY = request.visibleSigmaY;
    push.visibleAuthority = request.visibleChromaEnabled
            ? std::clamp(request.visibleAuthority, 0.0f, 0.96f)
            : std::clamp(request.legacySharpenAmount, 0.0f, 0.30f);
    push.visibleMaximumCorrection = request.visibleMaximumCorrection;
    push.inverse00 = request.inverse00;
    push.inverse01 = request.inverse01;
    push.inverse11 = request.inverse11;
    // Mode 1 no longer consumes these mode-0 spatial-NR slots. Reuse four slots for
    // the Lightroom Detail tuple without growing the 128-byte push layout.
    push.appliedLumaSigma = std::clamp(request.profileDetailAmount, 0.0f, 1.0f);
    push.lumaRangeThresholdMean = std::clamp(request.profileDetailRadius, 0.50f, 3.00f);
    push.chromaRangeThresholdMean = std::clamp(request.profileDetailDetail, 0.0f, 1.0f);
    push.outerRingAuthority = std::clamp(request.profileDetailMasking, 0.0f, 1.0f);
    push.profileNrColor = 0.0f;
    push.chromaNrStrength = 0.0f;
    // FASE 15: mode 1 consumes padding2 only as a publication mode switch.
    // true => exact-LUT packed BGR8/sRGB, false => legacy FP32 publication fallback.
    push.padding2 = request.packedBgr8Publication ? 1.0f : 0.0f;
    // The visible-chroma pass consumes the strip-local intermediate buffer, not the
    // upstream full-frame resident tone buffer. Restore strip-local addressing before
    // the second dispatch even when mode 0 used a resident full-frame source.
    push.padding1 = 0.0f;
    push.inputOriginY = request.intermediateOriginY;
    push.inputRows = request.intermediateRows;
    push.outputOriginY = request.outputOriginY;
    push.outputRows = request.outputRows;
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE,
                            pipelineLayout_, 0u, 1u, &descriptorSets_[1], 0u, nullptr);
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                  (request.outputRows + 15u) / 16u, 1u);
    if (result.timestampQueryUsed) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                            queryPool_, 3u);
    }

    VkBufferMemoryBarrier hostBarriers[3]{};
    for (auto& barrier : hostBarriers) {
        barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    }
    hostBarriers[0].buffer = output_.buffer;
    hostBarriers[0].size = static_cast<VkDeviceSize>(result.outputBytes);
    hostBarriers[1].buffer = spatialTelemetry_.buffer;
    hostBarriers[1].size = static_cast<VkDeviceSize>(telemetryBytes);
    hostBarriers[2].buffer = visibleTelemetry_.buffer;
    hostBarriers[2].size = static_cast<VkDeviceSize>(telemetryBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u,
                         0u, nullptr, 3u, hostBarriers, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "POST_DEMOSAIC_COMMAND_RECORDING_FAILED";
        result.failureReason = "vkEndCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer_;
    const auto syncStarted = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS ||
        vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("PostDemosaic");
        result.status = "GPU_STALLED";
        result.failureReason = "post_demosaic_queue_submit_or_wait_timeout";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.synchronizationMs = elapsedMs(syncStarted);

    if (result.timestampQueryUsed) {
        std::uint64_t timestamps[4]{};
        if (vkGetQueryPoolResults(device, queryPool_, 0u, 4u, sizeof(timestamps),
                timestamps, sizeof(std::uint64_t),
                VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            timestamps[1] >= timestamps[0] && timestamps[3] >= timestamps[2] &&
            timestamps[3] >= timestamps[0]) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physicalDevice, &properties);
            const double toMs = static_cast<double>(properties.limits.timestampPeriod) / 1.0e6;
            result.spatialKernelMs = static_cast<float>(
                    static_cast<double>(timestamps[1] - timestamps[0]) * toMs);
            result.visibleKernelMs = static_cast<float>(
                    static_cast<double>(timestamps[3] - timestamps[2]) * toMs);
            result.gpuKernelMs = static_cast<float>(
                    static_cast<double>(timestamps[3] - timestamps[0]) * toMs);
        } else {
            result.timestampQueryUsed = false;
        }
    }
    if (!result.timestampQueryUsed) {
        result.gpuKernelMs = result.synchronizationMs;
        result.spatialKernelMs = 0.0f;
        result.visibleKernelMs = 0.0f;
    }

    const auto readbackStarted = Clock::now();
    vmaInvalidateAllocation(allocator_, output_.allocation, 0u, result.outputBytes);
    vmaInvalidateAllocation(allocator_, spatialTelemetry_.allocation, 0u, telemetryBytes);
    vmaInvalidateAllocation(allocator_, visibleTelemetry_.allocation, 0u, telemetryBytes);
    result.outputMappedPointer = output_.mapped;
    std::memcpy(result.spatialCounters.data(), spatialTelemetry_.mapped,
                static_cast<std::size_t>(telemetryBytes));
    std::memcpy(result.visibleCounters.data(), visibleTelemetry_.mapped,
                static_cast<std::size_t>(telemetryBytes));
    result.readbackMs = elapsedMs(readbackStarted);

    const float fenceOverhead = result.timestampQueryUsed
            ? std::max(0.0f, result.synchronizationMs - result.gpuKernelMs)
            : result.synchronizationMs;
    result.transferAndSyncMs = result.inputPackingMs + fenceOverhead + result.readbackMs;
    const bool profileDetailSharpenRequested = request.profileDetailAmount > 1.0e-6f;
    result.legacySharpenApplied = !request.visibleChromaEnabled &&
            (request.legacySharpenAmount > 1.0e-6f || profileDetailSharpenRequested);
    // Mode 1 always works in quantized sRGB when visible chroma is disabled. Packed
    // publication also sRGB-encodes the linear visible-chroma result through the exact LUT.
    result.outputSrgbEncoded = request.packedBgr8Publication || !request.visibleChromaEnabled;
    result.success = true;
    if (request.packedBgr8Publication) {
        result.status = residentInputUsed
                ? "SPECTRA_POST_DEMOSAIC_GPU_RESIDENT_TONE_BGR8_PUBLICATION_READY"
                : "SPECTRA_POST_DEMOSAIC_GPU_RESIDENT_BGR8_PUBLICATION_READY";
    } else {
        result.status = result.legacySharpenApplied
                ? "SPECTRA_POST_DEMOSAIC_GPU_RESIDENT_LEGACY_SHARPEN_PUBLICATION_READY"
                : (residentInputUsed
                ? "SPECTRA_POST_DEMOSAIC_GPU_RESIDENT_TONE_HANDOFF_READY"
                : "SPECTRA_POST_DEMOSAIC_GPU_RESIDENT_CHAIN_READY");
    }
    result.failureReason = "none";
    result.totalMs = elapsedMs(totalStarted);
    return result;
#endif
}

} // namespace bncam::vulkan
