#include "VulkanSpectraRawFinalizeBackend.h"
#include "../RawGreenSplitPolicy.h"
#include "../RawAdaptiveExposurePolicy.h"
#include "VulkanPipelineCacheRegistry.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE
#define BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE 0
#endif

#if BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE
#include "SpectraRawFinalizeSpirv.h"
#endif

#include "VulkanRuntime.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>

namespace bncam::vulkan {
namespace {
using Clock = std::chrono::steady_clock;

float elapsedMs(const Clock::time_point& start) {
    return std::chrono::duration<float, std::milli>(Clock::now() - start).count();
}

constexpr std::uint32_t kExposureSummaryP10 = 6u;
constexpr std::uint32_t kExposureSummaryP25 = 7u;
constexpr std::uint32_t kExposureSummaryP50 = 8u;
constexpr std::uint32_t kExposureSummaryP75 = 9u;
constexpr std::uint32_t kExposureSummaryP90 = 10u;
constexpr std::uint32_t kExposureSummaryP95 = 11u;
constexpr std::uint32_t kExposureSummaryP99 = 12u;
constexpr std::uint32_t kExposureSummaryDr = 13u;
constexpr std::uint32_t kExposureSummaryLowerNeutral = 14u;
constexpr std::uint32_t kExposureSummaryUpperNeutral = 15u;
constexpr std::uint32_t kExposureSummaryAuthority = 16u;
constexpr std::uint32_t kExposureSummaryMinEv = 17u;
constexpr std::uint32_t kExposureSummaryP10Ev = 18u;
constexpr std::uint32_t kExposureSummaryP50Ev = 19u;
constexpr std::uint32_t kExposureSummaryP90Ev = 20u;
constexpr std::uint32_t kExposureSummaryMaxEv = 21u;
constexpr std::uint32_t kExposureSummaryMeanPosEv = 22u;
constexpr std::uint32_t kExposureSummaryMeanNegEv = 23u;
constexpr std::uint32_t kExposureSummaryPosFrac = 24u;
constexpr std::uint32_t kExposureSummaryNeuFrac = 25u;
constexpr std::uint32_t kExposureSummaryNegFrac = 26u;
constexpr std::uint32_t kExposureSummaryMeanGain2 = 27u;
constexpr std::uint32_t kExposureSummaryP90PosGain = 28u;
constexpr std::uint32_t kExposureSummaryStatus = 29u;
constexpr std::uint32_t kExposureTelemetryWords = 608u;

struct PushConstants {
    std::uint32_t frameWidth = 0u;
    std::uint32_t frameHeight = 0u;
    std::uint32_t effectiveCfaPattern = 0u;
    std::uint32_t sensorCfaPattern = 0u;
    std::int32_t cfaOffsetX = 0;
    std::int32_t cfaOffsetY = 0;
    std::uint32_t mode = 0u;
    std::uint32_t xStep = 2u;
    std::uint32_t yStep = 2u;
    std::uint32_t sampleColumns = 0u;
    std::uint32_t sampleRows = 0u;
    std::uint32_t lensColumns = 0u;
    std::uint32_t lensRows = 0u;
    std::uint32_t lensEnabled = 0u;
    float baseDefectThreshold = 0.050f;
    float greenEvenScale = 1.0f;
    float greenOddScale = 1.0f;
    float greenCalibrationRatio = 1.0f;
    std::uint32_t noiseModelEnabled = 0u;
    float s0 = 0.0f;
    float s1 = 0.0f;
    float s2 = 0.0f;
    float s3 = 0.0f;
    float o0 = 0.0f;
    float o1 = 0.0f;
    float o2 = 0.0f;
    float o3 = 0.0f;
};
static_assert(sizeof(PushConstants) == 108u, "raw finalize push constant layout mismatch");

float medianFromSamples(std::vector<float>& values) {
    if (values.empty()) return 0.0f;
    const std::size_t middle = values.size() / 2u;
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(middle), values.end());
    return values[middle];
}

std::uint32_t sampleCount(std::uint32_t dimension, std::uint32_t step) {
    if (dimension < 2u || step == 0u) return 0u;
    // CPU loop is: for (x = 0; x < dimension - 1; x += step).
    return ((dimension - 2u) / step) + 1u;
}
} // namespace

bool VulkanSpectraRawFinalizeBackend::productionKernelConnected() const noexcept {
#if BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE
    return !getSpectraRawFinalizeSpirv().empty();
#else
    return false;
#endif
}

bool VulkanSpectraRawFinalizeBackend::pipelineInitialized() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return initialized_;
}

bool VulkanSpectraRawFinalizeBackend::ensureBufferLocked(
        VmaAllocator allocator,
        std::uint64_t bytes,
        std::uint32_t hostAccess,
        PersistentBuffer& buffer,
        bool& reallocated,
        std::string& failureReason
) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)hostAccess; (void)buffer; (void)reallocated;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u ||
        bytes > static_cast<std::uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        failureReason = "INVALID_RAW_FINALIZE_BUFFER_REQUEST";
        return false;
    }
    const bool mappedRequired = hostAccess != 0u;
    const bool reusable = buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr &&
            buffer.capacityBytes >= bytes && (!mappedRequired || buffer.mapped != nullptr);
    if (reusable) return true;
    if (buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, buffer.buffer, buffer.allocation);
    }
    buffer = {};
    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = static_cast<VkDeviceSize>(bytes);
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VmaAllocationCreateInfo allocationInfo{};
    allocationInfo.usage = mappedRequired ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    allocationInfo.flags = mappedRequired ? hostAccess | VMA_ALLOCATION_CREATE_MAPPED_BIT : 0u;
    VmaAllocationInfo allocationResult{};
    const VkResult create = vmaCreateBuffer(
            allocator, &info, &allocationInfo, &buffer.buffer, &buffer.allocation, &allocationResult);
    if (create != VK_SUCCESS || buffer.buffer == VK_NULL_HANDLE || buffer.allocation == nullptr ||
        (mappedRequired && allocationResult.pMappedData == nullptr)) {
        buffer = {};
        failureReason = "vmaCreateBuffer_raw_finalize_failed_" + std::to_string(create);
        return false;
    }
    buffer.mapped = allocationResult.pMappedData;
    buffer.capacityBytes = bytes;
    reallocated = true;
    ++allocationGeneration_;
    return true;
#endif
}

bool VulkanSpectraRawFinalizeBackend::ensureLensMapLocked(
        VmaAllocator allocator,
        const SpectraRawFinalizeRequest& request,
        bool& reallocated,
        float& uploadMs,
        std::string& failureReason
) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)request; (void)reallocated; (void)uploadMs;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    const bool valid = request.lensShadingMap != nullptr &&
            request.lensShadingColumns > 0u && request.lensShadingRows > 0u;
    const std::uint64_t floats = valid
            ? static_cast<std::uint64_t>(request.lensShadingColumns) * request.lensShadingRows * 4u
            : 4u;
    const std::uint64_t bytes = floats * sizeof(float);
    const bool capacityWasEnough = lensMap_.buffer != VK_NULL_HANDLE && lensMap_.allocation != nullptr &&
            lensMap_.mapped != nullptr && lensMap_.capacityBytes >= bytes;
    if (!ensureBufferLocked(allocator, bytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT,
                            lensMap_, reallocated, failureReason)) {
        return false;
    }
    const bool generationMatches = valid && request.lensShadingGenerationId != 0u &&
            request.lensShadingGenerationId == lensMapGenerationId_ &&
            request.lensShadingColumns == lensMapColumns_ && request.lensShadingRows == lensMapRows_;
    if (generationMatches && capacityWasEnough) {
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

void VulkanSpectraRawFinalizeBackend::destroyBuffersLocked() noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr) {
        for (PersistentBuffer* buffer : {&inputStaging_, &output_, &readback_, &greenSamples_,
                                         &telemetry_, &lensMap_}) {
            if (buffer->buffer != VK_NULL_HANDLE && buffer->allocation != nullptr) {
                vmaDestroyBuffer(allocator_, buffer->buffer, buffer->allocation);
            }
            *buffer = {};
        }
    }
#endif
    allocator_ = nullptr;
}

void VulkanSpectraRawFinalizeBackend::destroyLocked(VkDevice device) noexcept {
    destroyBuffersLocked();
    if (device != VK_NULL_HANDLE) {
        if (queryPool_ != VK_NULL_HANDLE) vkDestroyQueryPool(device, queryPool_, nullptr);
        if (fence_ != VK_NULL_HANDLE) vkDestroyFence(device, fence_, nullptr);
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

void VulkanSpectraRawFinalizeBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanSpectraRawFinalizeBackend::initializeLocked(
        VkDevice device,
        VkCommandPool commandPool,
        std::string& failureReason
) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device && initializedCommandPool_ == commandPool) return true;
        failureReason = "RAW_FINALIZE_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE
    (void)device; (void)commandPool;
    failureReason = "RAW_FINALIZE_SHADER_NOT_COMPILED";
    return false;
#else
    const auto& spirv = getSpectraRawFinalizeSpirv();
    if (device == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE || spirv.empty()) {
        failureReason = "RAW_FINALIZE_INITIALIZATION_INPUT_INVALID";
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[5]{};
    for (std::uint32_t i = 0; i < 5u; ++i) {
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
        failureReason = "vkCreateDescriptorSetLayout_raw_finalize_failed"; destroyLocked(device); return false;
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
        failureReason = "vkCreatePipelineLayout_raw_finalize_failed"; destroyLocked(device); return false;
    }
    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_raw_finalize_failed"; destroyLocked(device); return false;
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
    if (VulkanPipelineCacheRegistry::createComputePipelines(device, 1u, &pipelineInfo, nullptr, &pipeline_) != VK_SUCCESS) {
        failureReason = "vkCreateComputePipelines_raw_finalize_failed"; destroyLocked(device); return false;
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
        failureReason = "vkCreateDescriptorPool_raw_finalize_failed"; destroyLocked(device); return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1u;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "vkAllocateDescriptorSets_raw_finalize_failed"; destroyLocked(device); return false;
    }
    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &commandInfo, &commandBuffer_) != VK_SUCCESS) {
        failureReason = "vkAllocateCommandBuffers_raw_finalize_failed"; destroyLocked(device); return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence_) != VK_SUCCESS) {
        failureReason = "vkCreateFence_raw_finalize_failed"; destroyLocked(device); return false;
    }
    VkQueryPoolCreateInfo queryInfo{};
    queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryInfo.queryCount = 6u;
    if (vkCreateQueryPool(device, &queryInfo, nullptr, &queryPool_) != VK_SUCCESS) queryPool_ = VK_NULL_HANDLE;
    initialized_ = true;
    initializedDevice_ = device;
    initializedCommandPool_ = commandPool;
    failureReason.clear();
    return true;
#endif
}

void VulkanSpectraRawFinalizeBackend::updateDescriptorSetLocked(VkDevice device, VkBuffer inputOverride) noexcept {
    VkDescriptorBufferInfo infos[5]{};
    infos[0].buffer = inputOverride != VK_NULL_HANDLE ? inputOverride : inputStaging_.buffer;
    infos[1].buffer = output_.buffer;
    infos[2].buffer = greenSamples_.buffer;
    infos[3].buffer = telemetry_.buffer;
    infos[4].buffer = lensMap_.buffer;
    for (auto& info : infos) info.range = VK_WHOLE_SIZE;
    VkWriteDescriptorSet writes[5]{};
    for (std::uint32_t i = 0; i < 5u; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, 5u, writes, 0u, nullptr);
}

SpectraRawFinalizeResult VulkanSpectraRawFinalizeBackend::execute(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const SpectraRawFinalizeRequest& request
) noexcept {
    return executeInternal(physicalDevice, device, computeQueue, commandPool, allocatorOwner,
                           VK_NULL_HANDLE, 0u, request);
}

SpectraRawFinalizeResult VulkanSpectraRawFinalizeBackend::executeFromResident(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        VkBuffer residentInputBuffer,
        std::uint64_t residentInputBytes,
        const SpectraRawFinalizeRequest& request
) noexcept {
    return executeInternal(physicalDevice, device, computeQueue, commandPool, allocatorOwner,
                           residentInputBuffer, residentInputBytes, request);
}

SpectraRawFinalizeResult VulkanSpectraRawFinalizeBackend::executeInternal(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        VkBuffer residentInputBuffer,
        std::uint64_t residentInputBytes,
        const SpectraRawFinalizeRequest& request
) noexcept {
    SpectraRawFinalizeResult result{};
    result.attempted = true;
    result.pipelineAvailable = productionKernelConnected();
    const auto totalStart = Clock::now();
    const bool residentInput = residentInputBuffer != VK_NULL_HANDLE;
    if ((!residentInput && request.mosaicData == nullptr) || request.frameWidth == 0u ||
        request.frameHeight == 0u || (!residentInput && request.rowStrideFloats < request.frameWidth) ||
        device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE) {
        result.status = "GPU_RAW_FINALIZE_INVALID_INPUT";
        result.failureReason = "INVALID_RAW_FINALIZE_REQUEST";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE
    (void)physicalDevice; (void)allocatorOwner; (void)residentInputBytes;
    result.status = "GPU_RAW_FINALIZE_BUILD_SUPPORT_UNAVAILABLE";
    result.failureReason = !BNCAM_VMA_HEADER_AVAILABLE ? "VMA_HEADER_NOT_AVAILABLE" : "RAW_FINALIZE_SHADER_NOT_COMPILED";
    result.totalMs = elapsedMs(totalStart);
    return result;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    std::string failure;
    if (!initializeLocked(device, commandPool, failure)) {
        result.status = "GPU_RAW_FINALIZE_INITIALIZATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    VmaAllocator allocator = allocatorOwner.handle();
    if (allocator == nullptr) {
        result.status = "GPU_RAW_FINALIZE_ALLOCATOR_UNAVAILABLE";
        result.failureReason = "VMA_ALLOCATOR_NOT_READY";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    allocator_ = allocator;
    const std::uint64_t pixelCount = static_cast<std::uint64_t>(request.frameWidth) * request.frameHeight;
    const std::uint64_t frameBytes = pixelCount * sizeof(float);
    if (residentInput && residentInputBytes < frameBytes) {
        result.status = "GPU_RAW_FINALIZE_RESIDENT_INPUT_SIZE_MISMATCH";
        result.failureReason = "RESIDENT_MOSAIC_BUFFER_SMALLER_THAN_FRAME";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    const std::uint32_t xStep = std::max(2u, (request.frameWidth / 100u) * 2u);
    const std::uint32_t yStep = std::max(2u, (request.frameHeight / 100u) * 2u);
    const std::uint32_t columns = sampleCount(request.frameWidth, xStep);
    const std::uint32_t rows = sampleCount(request.frameHeight, yStep);
    const std::uint64_t greenCount = static_cast<std::uint64_t>(columns) * rows;
    std::uint32_t autoStride = 1u;
    std::uint32_t autoColumns = 0u;
    std::uint32_t autoRows = 0u;
    if (request.frameWidth >= 9u && request.frameHeight >= 9u) {
        constexpr double targetAutoSamples = 50000.0;
        const std::uint64_t interior = static_cast<std::uint64_t>(request.frameWidth - 8u) *
                static_cast<std::uint64_t>(request.frameHeight - 8u);
        autoStride = std::max<std::uint32_t>(1u, static_cast<std::uint32_t>(std::floor(
                std::sqrt(static_cast<double>(interior) / targetAutoSamples))));
        autoColumns = ((request.frameWidth - 9u) / autoStride) + 1u;
        autoRows = ((request.frameHeight - 9u) / autoStride) + 1u;
    }
    const std::uint64_t autoCount = static_cast<std::uint64_t>(autoColumns) * autoRows;
    const std::uint64_t exposureCount = bncam::raw_exposure::kTileCount;
    // Evidence and resolved EV map coexist in the compact SSBO during the single Vulkan submission.
    const std::uint64_t exposureRecordCount = exposureCount * 2u;
    const std::uint64_t compactRecordCount = std::max({greenCount, autoCount, exposureRecordCount});
    const std::uint64_t greenBytes = std::max<std::uint64_t>(
            sizeof(float) * 4u, compactRecordCount * 4u * sizeof(float));
    constexpr std::uint64_t telemetryBytes = kExposureTelemetryWords * sizeof(std::uint32_t);
    const std::uint64_t clipMapWidth = (static_cast<std::uint64_t>(request.frameWidth) + 1u) / 2u;
    const std::uint64_t clipMapHeight = (static_cast<std::uint64_t>(request.frameHeight) + 1u) / 2u;
    const std::uint64_t clipMapBytes = clipMapWidth * clipMapHeight * sizeof(float);
    const std::uint64_t residentTransportBytes = frameBytes + clipMapBytes;
    result.inputBytes = frameBytes;
    result.outputBytes = frameBytes;
    result.sourceClipConfidenceMapBytes = clipMapBytes;
    result.compactGreenBytes = greenBytes;
    result.residentInputUsed = residentInput;
    result.adaptiveExposureRequested = request.adaptiveExposureEnabled;
    result.adaptiveExposurePhysicalNoiseModel = request.noiseModelValid;

    bool reallocated = false;
    const std::uint32_t writeAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
    const std::uint32_t readAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    if ((!residentInput && !ensureBufferLocked(allocator, frameBytes, writeAccess, inputStaging_, reallocated, failure)) ||
        !ensureBufferLocked(allocator, residentTransportBytes, 0u, output_, reallocated, failure) ||
        !ensureBufferLocked(allocator, greenBytes, readAccess, greenSamples_, reallocated, failure) ||
        !ensureBufferLocked(allocator, telemetryBytes, readAccess, telemetry_, reallocated, failure) ||
        (!request.deferFullFrameReadback &&
         !ensureBufferLocked(allocator, frameBytes, readAccess, readback_, reallocated, failure)) ||
        !ensureLensMapLocked(allocator, request, reallocated, result.lensMapUploadMs, failure)) {
        result.status = "GPU_RAW_FINALIZE_BUFFER_ALLOCATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = inputStaging_.capacityBytes + output_.capacityBytes + readback_.capacityBytes +
            greenSamples_.capacityBytes + telemetry_.capacityBytes + lensMap_.capacityBytes;
    updateDescriptorSetLocked(device, residentInput ? residentInputBuffer : VK_NULL_HANDLE);

    if (!residentInput) {
        const auto packStart = Clock::now();
        float* packed = static_cast<float*>(inputStaging_.mapped);
        if (request.rowStrideFloats == request.frameWidth) {
            std::memcpy(packed, request.mosaicData, static_cast<std::size_t>(frameBytes));
        } else {
            for (std::uint32_t y = 0; y < request.frameHeight; ++y) {
                std::memcpy(packed + static_cast<std::size_t>(y) * request.frameWidth,
                            request.mosaicData + static_cast<std::size_t>(y) * request.rowStrideFloats,
                            static_cast<std::size_t>(request.frameWidth) * sizeof(float));
            }
        }
        vmaFlushAllocation(allocator, inputStaging_.allocation, 0u, static_cast<VkDeviceSize>(frameBytes));
        result.inputPackingMs = elapsedMs(packStart);
    }
    std::memset(greenSamples_.mapped, 0, static_cast<std::size_t>(greenBytes));
    std::memset(telemetry_.mapped, 0, static_cast<std::size_t>(telemetryBytes));
    vmaFlushAllocation(allocator, greenSamples_.allocation, 0u, static_cast<VkDeviceSize>(greenBytes));
    vmaFlushAllocation(allocator, telemetry_.allocation, 0u, static_cast<VkDeviceSize>(telemetryBytes));

    // Submission 1: upload once when needed, then extract only the compact aligned G1/G2 samples.
    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "GPU_RAW_FINALIZE_SAMPLE_COMMAND_BEGIN_FAILED";
        result.failureReason = "vkBeginCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart); return result;
    }
    VkBuffer activeInput = residentInput ? residentInputBuffer : inputStaging_.buffer;
    VkBufferMemoryBarrier inputBarrier{};
    inputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    inputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarrier.buffer = activeInput;
    inputBarrier.size = static_cast<VkDeviceSize>(frameBytes);
    inputBarrier.srcAccessMask = residentInput
            ? (VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
            : VK_ACCESS_HOST_WRITE_BIT;
    inputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(commandBuffer_, residentInput
            ? (VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT)
            : VK_PIPELINE_STAGE_HOST_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 1u, &inputBarrier, 0u, nullptr);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 4u);
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 0u);
        result.timestampQueryUsed = true;
    }
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                            0u, 1u, &descriptorSet_, 0u, nullptr);
    PushConstants push{};
    push.frameWidth = request.frameWidth;
    push.frameHeight = request.frameHeight;
    push.effectiveCfaPattern = std::min(request.effectiveCfaPattern, 3u);
    push.sensorCfaPattern = std::min(request.sensorCfaPattern, 3u);
    push.cfaOffsetX = request.cfaOffsetX;
    push.cfaOffsetY = request.cfaOffsetY;
    push.mode = 0u;
    push.xStep = xStep;
    push.yStep = yStep;
    push.sampleColumns = columns;
    push.sampleRows = rows;
    push.lensColumns = request.lensShadingColumns;
    push.lensRows = request.lensShadingRows;
    push.lensEnabled = (request.lensShadingMap != nullptr && request.lensShadingColumns > 0u &&
                        request.lensShadingRows > 0u) ? 1u : 0u;
    push.baseDefectThreshold = request.isRaw10 ? 0.070f : 0.050f;
    push.greenCalibrationRatio = std::clamp(request.greenCalibrationRatio, 0.50f, 2.0f);
    push.noiseModelEnabled = request.noiseModelValid ? 1u : 0u;
    push.s0 = request.effectiveS[0]; push.s1 = request.effectiveS[1];
    push.s2 = request.effectiveS[2]; push.s3 = request.effectiveS[3];
    push.o0 = request.effectiveO[0]; push.o1 = request.effectiveO[1];
    push.o2 = request.effectiveO[2]; push.o3 = request.effectiveO[3];
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    if (columns > 0u && rows > 0u) {
        vkCmdDispatch(commandBuffer_, (columns + 15u) / 16u, (rows + 15u) / 16u, 1u);
    }
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
    }
    VkBufferMemoryBarrier greenBarrier{};
    greenBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    greenBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    greenBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    greenBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    greenBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    greenBarrier.buffer = greenSamples_.buffer;
    greenBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0u, 0u, nullptr, 1u, &greenBarrier, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "GPU_RAW_FINALIZE_SAMPLE_COMMAND_END_FAILED";
        result.failureReason = "vkEndCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart); return result;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer_;
    const auto sampleSubmitStart = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS) {
        result.status = "GPU_RAW_FINALIZE_SAMPLE_SUBMIT_FAILED";
        result.failureReason = "vkQueueSubmit_failed";
        result.totalMs = elapsedMs(totalStart); return result;
    }
    VkResult wait = vkWaitForFences(device, 1u, &fence_, VK_TRUE, 4'000'000'000ull);
    result.synchronizationMs += elapsedMs(sampleSubmitStart);
    if (wait != VK_SUCCESS) {
        result.submissionMayRemainInFlight = true;
        VulkanRuntime::instance().markGpuStalled("RawFinalize_Sample");
        result.status = "GPU_STALLED";
        result.failureReason = "vkWaitForFences_sample_" + std::to_string(wait);
        result.totalMs = elapsedMs(totalStart); return result;
    }
    if (queryPool_ != VK_NULL_HANDLE) {
        std::uint64_t timestamps[2]{0u, 0u};
        if (vkGetQueryPoolResults(device, queryPool_, 0u, 2u, sizeof(timestamps), timestamps,
                                  sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            timestamps[1] >= timestamps[0]) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physicalDevice, &properties);
            const double msPerTick = static_cast<double>(properties.limits.timestampPeriod) / 1.0e6;
            result.greenSamplingKernelMs = static_cast<float>(
                    static_cast<double>(timestamps[1] - timestamps[0]) * msPerTick);
        }
    }

    const auto reductionStart = Clock::now();
    if (greenCount > 0u) {
        vmaInvalidateAllocation(allocator, greenSamples_.allocation, 0u, static_cast<VkDeviceSize>(greenBytes));
        const float* samples = static_cast<const float*>(greenSamples_.mapped);
        std::vector<float> even;
        std::vector<float> odd;
        even.reserve(static_cast<std::size_t>(greenCount));
        odd.reserve(static_cast<std::size_t>(greenCount));
        for (std::uint64_t i = 0u; i < greenCount; ++i) {
            const float* record = samples + static_cast<std::size_t>(i) * 4u;
            if (record[2] >= 0.5f && std::isfinite(record[0])) even.push_back(record[0]);
            if (record[3] >= 0.5f && std::isfinite(record[1])) odd.push_back(record[1]);
        }
        result.greenSampleCountEven = even.size();
        result.greenSampleCountOdd = odd.size();
        const auto evidence = bncam::raw_green_split::evaluate(even, odd);
        result.greenEvenMedian = medianFromSamples(even);
        result.greenOddMedian = medianFromSamples(odd);
        result.greenPairSampleCount = static_cast<std::uint64_t>(evidence.pairCount);
        result.greenSplitRelativeMedian = evidence.relativeMedian;
        result.greenSplitRelativeMad = evidence.relativeMad;
        result.greenSplitSignConsensus = evidence.signConsensus;
        result.greenSplitReason = bncam::raw_green_split::reasonName(evidence.reason);
        if (evidence.apply) {
            result.greenEvenScale = evidence.evenScale;
            result.greenOddScale = evidence.oddScale;
            result.greenSplitApplied = true;
        }
    }
    result.greenReductionCpuMs = elapsedMs(reductionStart);

    // Submission 2: exact full-frame defect + bounded green balance + lens shading on GPU.
    // Phase 5 extends this same command buffer with compact 64x48 scene evidence, GPU exposure
    // resolution, scalar CFA application and final Auto-demosaic evidence. No extra submit/fence.
    std::memset(telemetry_.mapped, 0, static_cast<std::size_t>(telemetryBytes));
    vmaFlushAllocation(allocator, telemetry_.allocation, 0u, static_cast<VkDeviceSize>(telemetryBytes));
    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "GPU_RAW_FINALIZE_COMMAND_BEGIN_FAILED";
        result.failureReason = "vkBeginCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart); return result;
    }
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(commandBuffer_, queryPool_, 2u, 4u);
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 2u);
    }
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                            0u, 1u, &descriptorSet_, 0u, nullptr);
    push.mode = 1u;
    push.greenEvenScale = result.greenEvenScale;
    push.greenOddScale = result.greenOddScale;
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u, (request.frameHeight + 15u) / 16u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 3u);
    }

    VkBufferMemoryBarrier outputForObserver{};
    outputForObserver.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    outputForObserver.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outputForObserver.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    outputForObserver.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputForObserver.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputForObserver.buffer = output_.buffer;
    outputForObserver.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &outputForObserver, 0u, nullptr);

    if (request.adaptiveExposureEnabled) {
        // Phase 5 production path: evidence -> resolver -> scalar CFA apply all stay in this
        // command buffer. No compact CPU decision or extra queue/fence cycle owns exposure.
        push.mode = 3u;
        push.xStep = 1u;
        push.yStep = 1u;
        push.sampleColumns = bncam::raw_exposure::kGridWidth;
        push.sampleRows = bncam::raw_exposure::kGridHeight;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_,
                      (bncam::raw_exposure::kGridWidth + 15u) / 16u,
                      (bncam::raw_exposure::kGridHeight + 15u) / 16u, 1u);

        VkBufferMemoryBarrier exposureResolveBarriers[2]{};
        exposureResolveBarriers[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        exposureResolveBarriers[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        exposureResolveBarriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        exposureResolveBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        exposureResolveBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        exposureResolveBarriers[0].buffer = greenSamples_.buffer;
        exposureResolveBarriers[0].size = VK_WHOLE_SIZE;
        exposureResolveBarriers[1] = exposureResolveBarriers[0];
        exposureResolveBarriers[1].buffer = telemetry_.buffer;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             2u, exposureResolveBarriers, 0u, nullptr);

        push.mode = 4u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, 1u, 1u, 1u);

        VkBufferMemoryBarrier exposureMapForApply{};
        exposureMapForApply.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        exposureMapForApply.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        exposureMapForApply.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        exposureMapForApply.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        exposureMapForApply.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        exposureMapForApply.buffer = greenSamples_.buffer;
        exposureMapForApply.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &exposureMapForApply, 0u, nullptr);

        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 4u);
        }
        push.mode = 5u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                      (request.frameHeight + 15u) / 16u, 1u);
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 5u);
        }

        outputForObserver.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        outputForObserver.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &outputForObserver, 0u, nullptr);
    }

    if (autoColumns > 0u && autoRows > 0u) {
        push.mode = 2u;
        push.xStep = autoStride;
        push.yStep = autoStride;
        push.sampleColumns = autoColumns;
        push.sampleRows = autoRows;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (autoColumns + 15u) / 16u, (autoRows + 15u) / 16u, 1u);
    }
    VkBufferMemoryBarrier hostBarriers[2]{};
    hostBarriers[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    hostBarriers[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    hostBarriers[0].dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    hostBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostBarriers[0].buffer = telemetry_.buffer;
    hostBarriers[0].size = VK_WHOLE_SIZE;
    hostBarriers[1].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    hostBarriers[1].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    hostBarriers[1].dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    hostBarriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostBarriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostBarriers[1].buffer = greenSamples_.buffer;
    hostBarriers[1].size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr, 2u, hostBarriers, 0u, nullptr);
    if (!request.deferFullFrameReadback) {
        VkBufferMemoryBarrier outputForTransfer{};
        outputForTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        outputForTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
        outputForTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        outputForTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        outputForTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        outputForTransfer.buffer = output_.buffer;
        outputForTransfer.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr,
                             1u, &outputForTransfer, 0u, nullptr);
        VkBufferCopy copy{};
        copy.size = static_cast<VkDeviceSize>(frameBytes);
        vkCmdCopyBuffer(commandBuffer_, output_.buffer, readback_.buffer, 1u, &copy);
        VkBufferMemoryBarrier readBarrier{};
        readBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        readBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        readBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        readBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        readBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        readBarrier.buffer = readback_.buffer;
        readBarrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                             0u, 0u, nullptr, 1u, &readBarrier, 0u, nullptr);
    }
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "GPU_RAW_FINALIZE_COMMAND_END_FAILED";
        result.failureReason = "vkEndCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart); return result;
    }
    const auto finalizeSubmitStart = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS) {
        result.status = "GPU_RAW_FINALIZE_SUBMIT_FAILED";
        result.failureReason = "vkQueueSubmit_failed";
        result.totalMs = elapsedMs(totalStart); return result;
    }
    wait = vkWaitForFences(device, 1u, &fence_, VK_TRUE, 4'000'000'000ull);
    result.synchronizationMs += elapsedMs(finalizeSubmitStart);
    if (wait != VK_SUCCESS) {
        result.submissionMayRemainInFlight = true;
        VulkanRuntime::instance().markGpuStalled("RawFinalize_Finalize");
        result.status = "GPU_STALLED";
        result.failureReason = "vkWaitForFences_finalize_" + std::to_string(wait);
        result.totalMs = elapsedMs(totalStart); return result;
    }
    if (queryPool_ != VK_NULL_HANDLE) {
        std::uint64_t timestamps[2]{0u, 0u};
        if (vkGetQueryPoolResults(device, queryPool_, 2u, 2u, sizeof(timestamps), timestamps,
                                  sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            timestamps[1] >= timestamps[0]) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physicalDevice, &properties);
            const double msPerTick = static_cast<double>(properties.limits.timestampPeriod) / 1.0e6;
            result.finalizeKernelMs = static_cast<float>(
                    static_cast<double>(timestamps[1] - timestamps[0]) * msPerTick);
        }
    }

    const auto readStart = Clock::now();
    vmaInvalidateAllocation(allocator, telemetry_.allocation, 0u, static_cast<VkDeviceSize>(telemetryBytes));
    const auto* telemetry = static_cast<const std::uint32_t*>(telemetry_.mapped);
    result.sourceSaturatedPixelCount = telemetry[0];
    result.defectCorrectedPixelCount = telemetry[1];
    result.lensCorrectedPixelCount = telemetry[2];
    result.overRangePixelCount = telemetry[3];
    float maxGain = 1.0f;
    const std::uint32_t gainBits = telemetry[4];
    std::memcpy(&maxGain, &gainBits, sizeof(maxGain));
    result.lensMaximumGain = std::isfinite(maxGain) ? std::max(1.0f, maxGain) : 1.0f;
    result.lensShadingApplied = request.lensShadingMap != nullptr && request.lensShadingColumns > 0u &&
            request.lensShadingRows > 0u;

    if (request.adaptiveExposureEnabled) {
        const auto telemetryFloat = [&](std::uint32_t index, float fallback) noexcept {
            float value = fallback;
            const std::uint32_t bits = telemetry[index];
            std::memcpy(&value, &bits, sizeof(value));
            return std::isfinite(value) ? value : fallback;
        };
        result.exposureSceneP10 = telemetryFloat(kExposureSummaryP10, 0.0f);
        result.exposureSceneP25 = telemetryFloat(kExposureSummaryP25, 0.0f);
        result.exposureSceneP50 = telemetryFloat(kExposureSummaryP50, 0.0f);
        result.exposureSceneP75 = telemetryFloat(kExposureSummaryP75, 0.0f);
        result.exposureSceneP90 = telemetryFloat(kExposureSummaryP90, 0.0f);
        result.exposureSceneP95 = telemetryFloat(kExposureSummaryP95, 0.0f);
        result.exposureSceneP99 = telemetryFloat(kExposureSummaryP99, 0.0f);
        result.exposureMeasuredSceneDrEv = telemetryFloat(kExposureSummaryDr, 0.0f);
        result.exposureLowerNeutralBoundaryEv = telemetryFloat(kExposureSummaryLowerNeutral, 0.0f);
        result.exposureUpperNeutralBoundaryEv = telemetryFloat(kExposureSummaryUpperNeutral, 0.0f);
        result.exposureSpatialAuthority = telemetryFloat(kExposureSummaryAuthority, 0.0f);
        result.exposureMinEv = telemetryFloat(kExposureSummaryMinEv, 0.0f);
        result.exposureP10Ev = telemetryFloat(kExposureSummaryP10Ev, 0.0f);
        result.exposureP50Ev = telemetryFloat(kExposureSummaryP50Ev, 0.0f);
        result.exposureP90Ev = telemetryFloat(kExposureSummaryP90Ev, 0.0f);
        result.exposureMaxEv = telemetryFloat(kExposureSummaryMaxEv, 0.0f);
        result.exposureMeanPositiveEv = telemetryFloat(kExposureSummaryMeanPosEv, 0.0f);
        result.exposureMeanNegativeEv = telemetryFloat(kExposureSummaryMeanNegEv, 0.0f);
        result.exposurePositiveFraction = std::clamp(telemetryFloat(kExposureSummaryPosFrac, 0.0f), 0.0f, 1.0f);
        result.exposureNeutralFraction = std::clamp(telemetryFloat(kExposureSummaryNeuFrac, 1.0f), 0.0f, 1.0f);
        result.exposureNegativeFraction = std::clamp(telemetryFloat(kExposureSummaryNegFrac, 0.0f), 0.0f, 1.0f);
        result.exposureMeanGainSquared = std::max(0.0f, telemetryFloat(kExposureSummaryMeanGain2, 1.0f));
        result.exposureP90PositiveGain = std::max(1.0f, telemetryFloat(kExposureSummaryP90PosGain, 1.0f));
        result.exposureReductionCpuMs = 0.0f;
        switch (telemetry[kExposureSummaryStatus]) {
            case 1u: result.adaptiveExposureStatus = "INSUFFICIENT_SPATIAL_EVIDENCE"; break;
            case 2u: result.adaptiveExposureStatus = "INVALID_SCENE_REFERENCE"; break;
            case 3u: result.adaptiveExposureStatus = "SIGNED_SPATIAL_MAP_READY"; break;
            case 4u: result.adaptiveExposureStatus = "LOW_SCENE_SEPARATION_NEUTRAL_MAP"; break;
            default: result.adaptiveExposureStatus = "GPU_RESOLVER_NOT_READY"; break;
        }
        result.adaptiveExposureApplied = telemetry[kExposureSummaryStatus] == 3u ||
                telemetry[kExposureSummaryStatus] == 4u;

        if (queryPool_ != VK_NULL_HANDLE) {
            std::uint64_t timestamps[2]{0u, 0u};
            if (vkGetQueryPoolResults(device, queryPool_, 4u, 2u, sizeof(timestamps), timestamps,
                                      sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
                timestamps[1] >= timestamps[0]) {
                VkPhysicalDeviceProperties properties{};
                vkGetPhysicalDeviceProperties(physicalDevice, &properties);
                const double msPerTick = static_cast<double>(properties.limits.timestampPeriod) / 1.0e6;
                result.exposureApplyKernelMs = static_cast<float>(
                        static_cast<double>(timestamps[1] - timestamps[0]) * msPerTick);
            }
        }
    } else {
        result.adaptiveExposureStatus = "DISABLED";
    }

    // Auto-demosaic compact scene evidence is always read from the final output generation: after
    // adaptive exposure when enabled, directly after raw finalization otherwise.
    if (autoCount > 0u) {
        vmaInvalidateAllocation(allocator, greenSamples_.allocation, 0u,
                                static_cast<VkDeviceSize>(autoCount * 4u * sizeof(float)));
        const float* samples = static_cast<const float*>(greenSamples_.mapped);
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
            const auto percentileSorted = [](const std::vector<float>& values, float percentile) -> float {
                if (values.empty()) return 0.0f;
                const std::size_t index = std::min(
                        values.size() - 1u,
                        static_cast<std::size_t>(std::floor(
                                std::clamp(percentile, 0.0f, 1.0f) *
                                static_cast<float>(values.size() - 1u))));
                return values[index];
            };
            result.autoSceneMetricsReady = true;
            result.autoSceneSampleCount = signals.size();
            result.autoSceneMedianSignal = percentileSorted(signals, 0.50f);
            result.autoSceneMeanGradient = static_cast<float>(gradientSum / static_cast<double>(signals.size()));
            result.autoSceneP90Gradient = percentileSorted(gradients, 0.90f);
            const float denominator = static_cast<float>(signals.size());
            result.autoSceneEdgeFraction = static_cast<float>(edgeCount) / denominator;
            result.autoSceneCoherentEdgeFraction = static_cast<float>(coherentCount) / denominator;
            result.autoSceneLowSignalFraction = static_cast<float>(lowSignalCount) / denominator;
        }
    }
    if (!request.deferFullFrameReadback) {
        vmaInvalidateAllocation(allocator, readback_.allocation, 0u, static_cast<VkDeviceSize>(frameBytes));
        result.outputMosaic.resize(static_cast<std::size_t>(pixelCount));
        std::memcpy(result.outputMosaic.data(), readback_.mapped, static_cast<std::size_t>(frameBytes));
    }
    result.readbackMs = elapsedMs(readStart);
    result.fullFrameReadbackDeferred = request.deferFullFrameReadback;
    result.sourceClipConfidenceMapReady = true;
    result.success = true;
    ++residentOutputGeneration_;
    // Internal resident transport contains the image mosaic followed by the compact
    // 2x2-cell source clipping-confidence map. Public CPU readback remains mosaic-only.
    residentOutputBytes_ = residentTransportBytes;
    residentOutputWidth_ = request.frameWidth;
    residentOutputHeight_ = request.frameHeight;
    result.residentOutputGeneration = residentOutputGeneration_;
    result.status = residentInput
            ? "GPU_RAW_FINALIZE_RESIDENT_INPUT_PRIMARY_DEMOSAIC_HANDOFF_READY"
            : "GPU_RAW_FINALIZE_PRIMARY_DEMOSAIC_HANDOFF_READY";
    result.failureReason = "none";
    result.totalMs = elapsedMs(totalStart);
    return result;
#endif
}

bool VulkanSpectraRawFinalizeBackend::resolveResidentOutput(
        std::uint64_t generation,
        VkBuffer& buffer,
        std::uint64_t& bytes,
        std::uint32_t& width,
        std::uint32_t& height
) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (generation == 0u || generation != residentOutputGeneration_ || output_.buffer == VK_NULL_HANDLE ||
        residentOutputBytes_ == 0u || residentOutputWidth_ == 0u || residentOutputHeight_ == 0u) {
        return false;
    }
    buffer = output_.buffer;
    bytes = residentOutputBytes_;
    width = residentOutputWidth_;
    height = residentOutputHeight_;
    return true;
}

bool VulkanSpectraRawFinalizeBackend::readbackResidentOutput(
        VkDevice device,
        VkQueue computeQueue,
        std::uint64_t generation,
        std::vector<float>& output
) noexcept {
    output.clear();
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_RAW_FINALIZE_SHADER_AVAILABLE
    (void)device; (void)computeQueue; (void)generation;
    return false;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    if (!initialized_ || allocator_ == nullptr || device == VK_NULL_HANDLE ||
        device != initializedDevice_ || computeQueue == VK_NULL_HANDLE ||
        generation == 0u || generation != residentOutputGeneration_ ||
        residentOutputBytes_ == 0u || output_.buffer == VK_NULL_HANDLE ||
        output_.allocation == nullptr) {
        return false;
    }

    const std::uint64_t imageBytes = static_cast<std::uint64_t>(residentOutputWidth_) *
            residentOutputHeight_ * sizeof(float);
    if (imageBytes == 0u || imageBytes > residentOutputBytes_) return false;
    bool reallocated = false;
    std::string failure;
    constexpr std::uint32_t readAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    if (!ensureBufferLocked(allocator_, imageBytes, readAccess,
                            readback_, reallocated, failure) ||
        readback_.buffer == VK_NULL_HANDLE || readback_.allocation == nullptr ||
        readback_.mapped == nullptr) {
        return false;
    }

    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) return false;

    VkBufferMemoryBarrier outputForTransfer{};
    outputForTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    outputForTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
    outputForTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    outputForTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputForTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputForTransfer.buffer = output_.buffer;
    outputForTransfer.offset = 0u;
    outputForTransfer.size = static_cast<VkDeviceSize>(imageBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr,
                         1u, &outputForTransfer, 0u, nullptr);

    VkBufferCopy copy{};
    copy.size = static_cast<VkDeviceSize>(imageBytes);
    vkCmdCopyBuffer(commandBuffer_, output_.buffer, readback_.buffer, 1u, &copy);

    VkBufferMemoryBarrier toHost{};
    toHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.buffer = readback_.buffer;
    toHost.offset = 0u;
    toHost.size = static_cast<VkDeviceSize>(imageBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr,
                         1u, &toHost, 0u, nullptr);

    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) return false;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer_;
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS) return false;
    const VkResult wait = vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull);
    if (wait != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("RawFinalize_Readback");
        return false;
    }

    vmaInvalidateAllocation(allocator_, readback_.allocation, 0u,
                            static_cast<VkDeviceSize>(imageBytes));
    const std::size_t floatCount = static_cast<std::size_t>(
            imageBytes / sizeof(float));
    output.resize(floatCount);
    std::memcpy(output.data(), readback_.mapped, static_cast<std::size_t>(imageBytes));
    return true;
#endif
}


} // namespace bncam::vulkan
