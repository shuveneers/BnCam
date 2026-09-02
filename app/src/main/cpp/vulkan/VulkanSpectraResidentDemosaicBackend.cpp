#include "VulkanSpectraResidentDemosaicBackend.h"
#include "VulkanPipelineCacheRegistry.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE
#define BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE 0
#endif
#if BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE
#include "SpectraResidentDemosaicSpirv.h"
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
    return static_cast<float>(std::chrono::duration<double, std::milli>(Clock::now() - started).count());
}

struct ResidualSampling {
    std::uint32_t stride = 1u;
    std::uint32_t columns = 0u;
    std::uint32_t rows = 0u;
    std::uint64_t recordCount = 0u;
    std::uint64_t bytes = 0u;
};

ResidualSampling residualSamplingFor(std::uint32_t width, std::uint32_t height) {
    ResidualSampling sampling{};
    if (width < 5u || height < 5u) return sampling;
    constexpr double targetSamples = 180000.0;
    const std::uint64_t interior = static_cast<std::uint64_t>(width - 4u) *
            static_cast<std::uint64_t>(height - 4u);
    sampling.stride = std::max<std::uint32_t>(
            1u, static_cast<std::uint32_t>(std::floor(std::sqrt(
                    static_cast<double>(interior) / targetSamples))));
    sampling.columns = ((width - 5u) / sampling.stride) + 1u;
    sampling.rows = ((height - 5u) / sampling.stride) + 1u;
    sampling.recordCount = static_cast<std::uint64_t>(sampling.columns) * sampling.rows;
    sampling.bytes = sampling.recordCount * 8u * sizeof(float);
    return sampling;
}


constexpr std::uint64_t kHueSatHeaderFloats = 16u;
constexpr std::uint64_t kColorTelemetryWords = 20u;

bool hueSatTableValid(const SpectraResidentColorTransformRequest& request, const float* table,
                      std::size_t floatCount) noexcept {
    if (table == nullptr || request.hueSatHueDivisions < 1u || request.hueSatSaturationDivisions < 2u ||
        request.hueSatValueDivisions < 1u || request.hueSatEncoding > 1u) return false;
    const std::uint64_t entries = static_cast<std::uint64_t>(request.hueSatHueDivisions) *
            request.hueSatSaturationDivisions * request.hueSatValueDivisions;
    if (entries == 0u || entries > (1u << 20) || floatCount != entries * 3u) return false;
    for (std::uint64_t i = 0u; i < entries; ++i) {
        const float h = table[i * 3u + 0u];
        const float sat = table[i * 3u + 1u];
        const float val = table[i * 3u + 2u];
        if (!std::isfinite(h) || !std::isfinite(sat) || !std::isfinite(val) || sat < 0.0f || val < 0.0f) return false;
    }
    for (std::uint32_t v = 0u; v < request.hueSatValueDivisions; ++v) {
        for (std::uint32_t h = 0u; h < request.hueSatHueDivisions; ++h) {
            const std::uint64_t cell = (static_cast<std::uint64_t>(v) * request.hueSatHueDivisions + h) *
                    request.hueSatSaturationDivisions;
            if (std::abs(table[cell * 3u + 2u] - 1.0f) > 1.0e-5f) return false;
        }
    }
    return true;
}

struct alignas(16) PushConstants {
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::uint32_t cfaPattern = 0;
    std::uint32_t mode = 1;
    float wbR = 1.0f;
    float wbG = 1.0f;
    float wbB = 1.0f;
    float padding0 = 0.0f;
    float ccm[12]{1.0f, 0.0f, 0.0f, 0.0f,
                  1.0f, 0.0f, 0.0f, 0.0f,
                  1.0f, 0.0f, 0.0f, 0.0f};
    std::uint32_t residualStride = 1u;
    std::uint32_t residualColumns = 0u;
    std::uint32_t residualRows = 0u;
    std::uint32_t padding1 = 0u;
    float cfaEvidence0[4]{0.0f, 0.0f, 0.0f, 0.0f};
    float cfaEvidence1[4]{0.0f, 0.0f, 0.0f, 0.0f};
};
static_assert(sizeof(PushConstants) == 128u, "resident demosaic push layout must stay within Vulkan minimum guarantee");

} // namespace

bool VulkanSpectraResidentDemosaicBackend::productionKernelConnected() const noexcept {
#if BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE
    return !getSpectraResidentDemosaicSpirv().empty();
#else
    return false;
#endif
}

bool VulkanSpectraResidentDemosaicBackend::pipelineInitialized() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return initialized_;
}

bool VulkanSpectraResidentDemosaicBackend::ensureBufferLocked(
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
        failureReason = "INVALID_RESIDENT_DEMOSAIC_BUFFER_REQUEST";
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
        failureReason = "vmaCreateBuffer_resident_demosaic_failed_" + std::to_string(create);
        return false;
    }
    buffer.mapped = allocationResult.pMappedData;
    buffer.capacityBytes = bytes;
    reallocated = true;
    allocationGeneration_++;
    return true;
#endif
}

void VulkanSpectraResidentDemosaicBackend::destroyBuffersLocked() noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr) {
        for (PersistentBuffer* buffer : {&inputStaging_, &outputReadback_, &deviceInput_, &deviceOutput_,
                                         &rgbUpload_, &colorStatistics_, &colorTelemetry_, &sourceClipConfidence_,
                                         &cloudCorrectionMap_, &hueSatProfile_, &residualCandidates_}) {
            if (buffer->buffer != VK_NULL_HANDLE && buffer->allocation != nullptr) {
                vmaDestroyBuffer(allocator_, buffer->buffer, buffer->allocation);
            }
            *buffer = {};
        }
    }
#endif
    allocator_ = nullptr;
}

void VulkanSpectraResidentDemosaicBackend::destroyLocked(VkDevice device) noexcept {
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
    descriptorBindingsInitialized_ = false;
    residentDemosaicGeneration_ = 0u;
    residentColorGeneration_ = 0u;
    residentWidth_ = 0u;
    residentHeight_ = 0u;
    residentDemosaicValid_ = false;
    sourceClipConfidenceValid_ = false;
    sourceClipConfidenceDemosaicGeneration_ = 0u;
    sourceClipConfidenceWidth_ = 0u;
    sourceClipConfidenceHeight_ = 0u;
    residentColorValid_ = false;
}

void VulkanSpectraResidentDemosaicBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanSpectraResidentDemosaicBackend::initializeLocked(
        VkDevice device,
        VkCommandPool commandPool,
        std::string& failureReason
) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device && initializedCommandPool_ == commandPool) return true;
        failureReason = "RESIDENT_DEMOSAIC_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE
    (void)device; (void)commandPool;
    failureReason = "RESIDENT_DEMOSAIC_SHADER_NOT_COMPILED";
    return false;
#else
    const auto& spirv = getSpectraResidentDemosaicSpirv();
    if (device == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE || spirv.empty()) {
        failureReason = "RESIDENT_DEMOSAIC_INITIALIZATION_INPUT_INVALID";
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[8]{};
    for (std::uint32_t i = 0; i < 8u; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo descriptorInfo{};
    descriptorInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorInfo.bindingCount = 8u;
    descriptorInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorSetLayout_resident_demosaic_failed";
        destroyLocked(device); return false;
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
        failureReason = "vkCreatePipelineLayout_resident_demosaic_failed";
        destroyLocked(device); return false;
    }
    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_resident_demosaic_failed";
        destroyLocked(device); return false;
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
        failureReason = "vkCreateComputePipelines_resident_demosaic_failed";
        destroyLocked(device); return false;
    }
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 8u;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1u;
    poolInfo.poolSizeCount = 1u;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorPool_resident_demosaic_failed";
        destroyLocked(device); return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1u;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "vkAllocateDescriptorSets_resident_demosaic_failed";
        destroyLocked(device); return false;
    }
    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &commandInfo, &commandBuffer_) != VK_SUCCESS) {
        failureReason = "vkAllocateCommandBuffers_resident_demosaic_failed";
        destroyLocked(device); return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence_) != VK_SUCCESS) {
        failureReason = "vkCreateFence_resident_demosaic_failed";
        destroyLocked(device); return false;
    }
    VkQueryPoolCreateInfo queryInfo{};
    queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    // Phase 6 adds two resident post-demosaic passes before compact residual extraction.
    // Eight slots cover primary reconstruction, classify/correct and residual sampling.
    queryInfo.queryCount = 8u;
    // Timestamp queries are optional observability. Failure must not disable the kernel.
    if (vkCreateQueryPool(device, &queryInfo, nullptr, &queryPool_) != VK_SUCCESS) queryPool_ = VK_NULL_HANDLE;
    initialized_ = true;
    initializedDevice_ = device;
    initializedCommandPool_ = commandPool;
    failureReason.clear();
    return true;
#endif
}

void VulkanSpectraResidentDemosaicBackend::updateDescriptorSetLocked(VkDevice device, VkBuffer inputOverride, VkBuffer scratchOverride) noexcept {
    VkDescriptorBufferInfo infos[8]{};
    infos[0].buffer = inputOverride != VK_NULL_HANDLE ? inputOverride : deviceInput_.buffer;
    infos[1].buffer = deviceOutput_.buffer;
    // Binding 2 aliases output only for demosaic modes that do not need scratch. AMaZE/Auto Hybrid
    // use rgbUpload_ as their guide; Phase 9 AWB+CCM also binds rgbUpload_ as a distinct protected
    // output so binding 1 remains immutable pre-WB neighbour evidence.
    infos[2].buffer = scratchOverride != VK_NULL_HANDLE ? scratchOverride : deviceOutput_.buffer;
    infos[3].buffer = colorStatistics_.buffer;
    infos[4].buffer = residualCandidates_.buffer;
    infos[5].buffer = cloudCorrectionMap_.buffer;
    infos[6].buffer = colorTelemetry_.buffer;
    infos[7].buffer = hueSatProfile_.buffer != VK_NULL_HANDLE ? hueSatProfile_.buffer : colorTelemetry_.buffer;
    for (auto& info : infos) info.range = VK_WHOLE_SIZE;
    VkWriteDescriptorSet writes[8]{};
    for (std::uint32_t i = 0; i < 8u; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, 8u, writes, 0u, nullptr);
    descriptorBindingsInitialized_ = true;
}

SpectraResidentDemosaicResult VulkanSpectraResidentDemosaicBackend::execute(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const SpectraResidentDemosaicRequest& request
) noexcept {
    return executeInternal(physicalDevice, device, computeQueue, commandPool, allocatorOwner,
                           VK_NULL_HANDLE, 0u, request);
}

SpectraResidentDemosaicResult VulkanSpectraResidentDemosaicBackend::executeFromResidentMosaic(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        VkBuffer residentInputBuffer,
        std::uint64_t residentInputBytes,
        const SpectraResidentDemosaicRequest& request
) noexcept {
    return executeInternal(physicalDevice, device, computeQueue, commandPool, allocatorOwner,
                           residentInputBuffer, residentInputBytes, request);
}

SpectraResidentDemosaicResult VulkanSpectraResidentDemosaicBackend::executeInternal(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        VkBuffer residentInputBuffer,
        std::uint64_t residentInputBytes,
        const SpectraResidentDemosaicRequest& request
) noexcept {
    SpectraResidentDemosaicResult result{};
    result.attempted = true;
    const auto totalStart = Clock::now();
    if (request.algorithm == SpectraGpuDemosaicAlgorithm::MENON_2007) {
        result.cpuFallbackRequired = true;
        result.status = "GPU_DEMOSAIC_MENON_TYPED_FALLBACK_LEGACY_REFERENCE_ONLY";
        result.failureReason = "MENON_2007_NOT_PORTED_LONG_TERM_ALGORITHM_REPLACEMENT_PLANNED";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    const bool residentInput = residentInputBuffer != VK_NULL_HANDLE;
    if ((!residentInput && request.mosaicData == nullptr) ||
        request.frameWidth == 0u || request.frameHeight == 0u ||
        (!residentInput && request.rowStrideFloats < request.frameWidth) ||
        device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE) {
        result.cpuFallbackRequired = true;
        result.status = "GPU_DEMOSAIC_INVALID_INPUT";
        result.failureReason = "INVALID_RESIDENT_DEMOSAIC_REQUEST";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE
    (void)physicalDevice; (void)allocatorOwner; (void)residentInputBuffer; (void)residentInputBytes;
    result.cpuFallbackRequired = true;
    result.status = "GPU_DEMOSAIC_BUILD_SUPPORT_UNAVAILABLE";
    result.failureReason = !BNCAM_VMA_HEADER_AVAILABLE ? "VMA_HEADER_NOT_AVAILABLE" : "RESIDENT_DEMOSAIC_SHADER_NOT_COMPILED";
    result.totalMs = elapsedMs(totalStart);
    return result;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    std::string failure;
    if (!initializeLocked(device, commandPool, failure)) {
        result.cpuFallbackRequired = true;
        result.status = "GPU_DEMOSAIC_INITIALIZATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    result.pipelineAvailable = true;
    VmaAllocator allocator = allocatorOwner.handle();
    if (allocator == nullptr) {
        result.cpuFallbackRequired = true;
        result.status = "GPU_DEMOSAIC_ALLOCATOR_UNAVAILABLE";
        result.failureReason = "VMA_ALLOCATOR_NOT_READY";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    allocator_ = allocator;
    const std::uint64_t pixelCount = static_cast<std::uint64_t>(request.frameWidth) * request.frameHeight;
    const std::uint64_t inputBytes = pixelCount * sizeof(float);
    const std::uint64_t outputBytes = pixelCount * 3u * sizeof(float);
    const std::uint32_t sourceClipWidth = (request.frameWidth + 1u) / 2u;
    const std::uint32_t sourceClipHeight = (request.frameHeight + 1u) / 2u;
    const std::uint64_t sourceClipBytes = static_cast<std::uint64_t>(sourceClipWidth) *
            sourceClipHeight * sizeof(float);
    const bool sourceClipMapAppended = residentInput &&
            residentInputBytes >= inputBytes + sourceClipBytes;
    if (residentInput && residentInputBytes < inputBytes) {
        result.cpuFallbackRequired = true;
        result.status = "GPU_DEMOSAIC_RESIDENT_INPUT_SIZE_MISMATCH";
        result.failureReason = "RESIDENT_MOSAIC_BUFFER_SMALLER_THAN_FRAME";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    result.residentInputUsed = residentInput;
    const std::uint64_t groupCount =
            static_cast<std::uint64_t>((request.frameWidth + 15u) / 16u) *
            static_cast<std::uint64_t>((request.frameHeight + 15u) / 16u);
    const std::uint64_t statisticsBytes = std::max<std::uint64_t>(36u, groupCount * 9u * sizeof(float));
    const ResidualSampling residualSampling = residualSamplingFor(request.frameWidth, request.frameHeight);
    const bool phase6ResidualChromaRequested = request.phase6ResidualChromaEnabled &&
            std::isfinite(request.noiseSigmaChroma) && request.noiseSigmaChroma > 1.0e-7f;
    const std::uint64_t phase6ScratchBytes = pixelCount * 2u * sizeof(float);
    const bool reconstructionScratchRequired =
            request.algorithm == SpectraGpuDemosaicAlgorithm::AMAZE ||
            request.algorithm == SpectraGpuDemosaicAlgorithm::AUTO_HYBRID;
    const std::uint64_t requiredScratchBytes = std::max(
            reconstructionScratchRequired ? outputBytes : 0u,
            phase6ResidualChromaRequested ? phase6ScratchBytes : 0u);
    result.phase6ResidualChromaRequested = phase6ResidualChromaRequested;
    result.residualSampleStride = residualSampling.stride;
    result.residualSampleColumns = residualSampling.columns;
    result.residualSampleRows = residualSampling.rows;
    result.inputBytes = inputBytes;
    result.outputBytes = outputBytes;
    bool reallocated = false;
    const std::uint32_t writeAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
    const std::uint32_t readAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    if (!ensureBufferLocked(allocator, inputBytes, writeAccess, inputStaging_, reallocated, failure) ||
        !ensureBufferLocked(allocator, outputBytes, readAccess, outputReadback_, reallocated, failure) ||
        !ensureBufferLocked(allocator, inputBytes, 0u, deviceInput_, reallocated, failure) ||
        !ensureBufferLocked(allocator, outputBytes, 0u, deviceOutput_, reallocated, failure) ||
        (requiredScratchBytes > 0u &&
         !ensureBufferLocked(allocator, requiredScratchBytes,
                 0u, rgbUpload_, reallocated, failure)) ||
        !ensureBufferLocked(allocator, statisticsBytes, readAccess, colorStatistics_, reallocated, failure) ||
        !ensureBufferLocked(allocator, 16u * sizeof(std::uint32_t), readAccess, colorTelemetry_, reallocated, failure) ||
        (sourceClipMapAppended &&
         !ensureBufferLocked(allocator, sourceClipBytes, 0u, sourceClipConfidence_, reallocated, failure)) ||
        !ensureBufferLocked(allocator, 16u, writeAccess, cloudCorrectionMap_, reallocated, failure) ||
        !ensureBufferLocked(allocator, std::max<std::uint64_t>(24u, residualSampling.bytes),
                            readAccess, residualCandidates_, reallocated, failure)) {
        result.cpuFallbackRequired = true;
        result.status = "GPU_DEMOSAIC_BUFFER_ALLOCATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = inputStaging_.capacityBytes + outputReadback_.capacityBytes +
            deviceInput_.capacityBytes + deviceOutput_.capacityBytes + rgbUpload_.capacityBytes +
            colorStatistics_.capacityBytes + colorTelemetry_.capacityBytes + sourceClipConfidence_.capacityBytes +
            cloudCorrectionMap_.capacityBytes + residualCandidates_.capacityBytes;
    // Rebind every demosaic submission because binding 0 may alternate between an internal
    // upload buffer and an opaque resident Pass-3 buffer. AMaZE and Auto Hybrid bind the same
    // dedicated resident guide scratch at binding 2; other algorithms retain the color alias.
    const bool usesGuideScratch = request.algorithm == SpectraGpuDemosaicAlgorithm::AMAZE ||
            request.algorithm == SpectraGpuDemosaicAlgorithm::AUTO_HYBRID ||
            phase6ResidualChromaRequested;
    updateDescriptorSetLocked(
            device,
            residentInput ? residentInputBuffer : VK_NULL_HANDLE,
            usesGuideScratch ? rgbUpload_.buffer : VK_NULL_HANDLE);

    const auto packStart = Clock::now();
    if (!residentInput) {
        float* packed = static_cast<float*>(inputStaging_.mapped);
        for (std::uint32_t y = 0; y < request.frameHeight; ++y) {
            std::memcpy(packed + static_cast<std::size_t>(y) * request.frameWidth,
                        request.mosaicData + static_cast<std::size_t>(y) * request.rowStrideFloats,
                        static_cast<std::size_t>(request.frameWidth) * sizeof(float));
        }
        result.inputPackingMs = elapsedMs(packStart);
        vmaFlushAllocation(allocator, inputStaging_.allocation, 0u, static_cast<VkDeviceSize>(inputBytes));
    } else {
        result.inputPackingMs = 0.0f;
    }

    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.cpuFallbackRequired = true;
        result.status = "GPU_DEMOSAIC_COMMAND_BEGIN_FAILED";
        result.failureReason = "vkBeginCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart); return result;
    }
    VkBufferMemoryBarrier uploadBarrier{};
    uploadBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    uploadBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    uploadBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    uploadBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    uploadBarrier.size = static_cast<VkDeviceSize>(residentInput ? residentInputBytes : inputBytes);
    if (!residentInput) {
        VkBufferCopy inputCopy{};
        inputCopy.size = static_cast<VkDeviceSize>(inputBytes);
        vkCmdCopyBuffer(commandBuffer_, inputStaging_.buffer, deviceInput_.buffer, 1u, &inputCopy);
        uploadBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        uploadBarrier.buffer = deviceInput_.buffer;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &uploadBarrier, 0u, nullptr);
    } else {
        // Pass 3 stores its final mosaic in a Vulkan buffer after a device-side copy.
        // Accept both transfer- and shader-written producers to keep the handoff generic.
        uploadBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        uploadBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT |
                (sourceClipMapAppended ? VK_ACCESS_TRANSFER_READ_BIT : 0u);
        uploadBarrier.buffer = residentInputBuffer;
        vkCmdPipelineBarrier(commandBuffer_,
                             VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                                     (sourceClipMapAppended ? VK_PIPELINE_STAGE_TRANSFER_BIT : 0u),
                             0u, 0u, nullptr, 1u, &uploadBarrier, 0u, nullptr);
    }

    if (sourceClipMapAppended) {
        // D124: copy the compact source-domain confidence tail into backend-owned storage.
        // The resident-input barrier above grants both shader-read and transfer-read access.
        // Owning this copy prevents a later RawFinalize generation from changing the evidence.
        VkBufferCopy clipCopy{};
        clipCopy.srcOffset = static_cast<VkDeviceSize>(inputBytes);
        clipCopy.dstOffset = 0u;
        clipCopy.size = static_cast<VkDeviceSize>(sourceClipBytes);
        vkCmdCopyBuffer(commandBuffer_, residentInputBuffer, sourceClipConfidence_.buffer, 1u, &clipCopy);
        VkBufferMemoryBarrier clipCopyReady{};
        clipCopyReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        clipCopyReady.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        clipCopyReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        clipCopyReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        clipCopyReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        clipCopyReady.buffer = sourceClipConfidence_.buffer;
        clipCopyReady.size = static_cast<VkDeviceSize>(sourceClipBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &clipCopyReady, 0u, nullptr);
    }

    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 8u);
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 0u);
        result.timestampQueryUsed = true;
    }
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                            0u, 1u, &descriptorSet_, 0u, nullptr);
    PushConstants push{};
    push.frameWidth = request.frameWidth;
    push.frameHeight = request.frameHeight;
    push.cfaPattern = std::min(request.cfaPattern, 3u);
    push.cfaEvidence0[0] = std::clamp(request.cfaEvidenceAvailable, 0.0f, 1.0f);
    push.cfaEvidence0[1] = std::clamp(request.cfaCommonOpponentSupport, 0.0f, 1.0f);
    push.cfaEvidence0[2] = std::clamp(request.cfaStructureProtection, 0.0f, 1.0f);
    push.cfaEvidence0[3] = std::clamp(request.cfaFineCorrectionConfidence, 0.0f, 1.0f);
    push.cfaEvidence1[0] = std::clamp(request.cfaMidCorrectionConfidence, 0.0f, 1.0f);
    push.cfaEvidence1[1] = std::clamp(request.cfaLowCorrectionConfidence, 0.0f, 1.0f);
    push.cfaEvidence1[2] = std::clamp(request.cfaRedOpponentCorrectionConfidence, 0.0f, 1.0f);
    push.cfaEvidence1[3] = std::clamp(request.cfaBlueOpponentCorrectionConfidence, 0.0f, 1.0f);
    // The push block is fixed at Vulkan's 128-byte minimum guarantee. ccm[9..11]
    // correspond to GLSL padding1..padding3 and are unused by demosaic modes, so
    // carry the compact physical noise context there without growing the layout.
    push.ccm[9] = std::max(0.0f, std::isfinite(request.noiseSigmaY) ? request.noiseSigmaY : 0.0f);
    push.ccm[10] = std::max(0.0f, std::isfinite(request.noiseSigmaChroma) ? request.noiseSigmaChroma : 0.0f);
    push.ccm[11] = std::clamp(std::isfinite(request.noisePressure) ? request.noisePressure : 0.0f, 0.0f, 1.0f);
    // ccm[3..4] are unused by reconstruction modes and transport Phase-6 bounded authority.
    push.ccm[3] = std::clamp(std::isfinite(request.phase6MaximumBlend)
            ? request.phase6MaximumBlend : 0.0f, 0.0f, 0.95f);
    push.ccm[4] = std::clamp(std::isfinite(request.phase6MaximumCorrection)
            ? request.phase6MaximumCorrection : 0.0f, 0.0f, 0.15f);
    if (request.algorithm == SpectraGpuDemosaicAlgorithm::AUTO_HYBRID) {
        float malvarPrior = std::clamp(std::isfinite(request.autoMalvarPrior) ? request.autoMalvarPrior : 0.0f, 0.0f, 1.0f);
        float neuralPrior = std::clamp(std::isfinite(request.autoNeuralJddPrior) ? request.autoNeuralJddPrior : 0.0f, 0.0f, 1.0f);
        float amazePrior = std::clamp(std::isfinite(request.autoAmazePrior) ? request.autoAmazePrior : 0.0f, 0.0f, 1.0f);
        const float priorSum = malvarPrior + neuralPrior + amazePrior;
        if (priorSum > 1.0e-6f) {
            malvarPrior /= priorSum;
            neuralPrior /= priorSum;
            amazePrior /= priorSum;
        } else {
            malvarPrior = neuralPrior = amazePrior = 1.0f / 3.0f;
        }
        // ccm[0..2] are unused by demosaic modes and map to GLSL ccm0..ccm2.
        push.ccm[0] = malvarPrior;
        push.ccm[1] = neuralPrior;
        push.ccm[2] = amazePrior;
    }
    switch (request.algorithm) {
        case SpectraGpuDemosaicAlgorithm::BILINEAR:
            push.mode = 0u;
            break;
        case SpectraGpuDemosaicAlgorithm::NEURAL_JDD:
            push.mode = 2u;
            break;
        case SpectraGpuDemosaicAlgorithm::AMAZE:
            push.mode = 5u;
            break;
        case SpectraGpuDemosaicAlgorithm::AUTO_HYBRID:
            push.mode = 7u;
            break;
        case SpectraGpuDemosaicAlgorithm::MALVAR_2004:
        default:
            push.mode = 1u;
            break;
    }
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u, (request.frameHeight + 15u) / 16u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        // Slot 1 always marks the end of the first primary pass. For Malvar/Neural JDD this is
        // the complete reconstruction; for AMaZE/Auto Hybrid it is the end of the resident guide pass.
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
    }
    if (request.algorithm == SpectraGpuDemosaicAlgorithm::AMAZE ||
        request.algorithm == SpectraGpuDemosaicAlgorithm::AUTO_HYBRID) {
        VkBufferMemoryBarrier guideBarrier{};
        guideBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        guideBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        guideBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        guideBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        guideBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        guideBarrier.buffer = rgbUpload_.buffer;
        guideBarrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &guideBarrier, 0u, nullptr);
        push.mode = request.algorithm == SpectraGpuDemosaicAlgorithm::AMAZE ? 6u : 8u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                      (request.frameHeight + 15u) / 16u, 1u);
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 2u);
        }
    } else if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 2u);
    }
    // Phase 6: two-pass, no-feedback residual chroma artifact correction. Pass 9 classifies
    // physically significant opponent outliers into the vec4 scratch. Pass 10 applies only
    // those precomputed corrections to R/B while preserving G exactly.
    if (phase6ResidualChromaRequested) {
        VkBufferMemoryBarrier phase6InputReady{};
        phase6InputReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        phase6InputReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        phase6InputReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        phase6InputReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        phase6InputReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        phase6InputReady.buffer = deviceOutput_.buffer;
        phase6InputReady.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &phase6InputReady, 0u, nullptr);
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 3u);
        }
        push.mode = 9u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                      (request.frameHeight + 15u) / 16u, 1u);
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 4u);
        }
        VkBufferMemoryBarrier phase6GuideReady{};
        phase6GuideReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        phase6GuideReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        phase6GuideReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        phase6GuideReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        phase6GuideReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        phase6GuideReady.buffer = rgbUpload_.buffer;
        phase6GuideReady.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &phase6GuideReady, 0u, nullptr);
        push.mode = 10u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                      (request.frameHeight + 15u) / 16u, 1u);
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 5u);
        }
    } else if (queryPool_ != VK_NULL_HANDLE) {
        // Keep timestamp ordering monotonic when Phase 6 is intentionally disabled.
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 3u);
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 4u);
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 5u);
    }

    // Keep the full RGB device-resident. Only the compact residual candidate field is
    // generated/read back here, so AWB+CCM can consume deviceOutput_ without a ~150 MB roundtrip.
    VkBufferMemoryBarrier outputBarrier{};
    outputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    outputBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    outputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputBarrier.buffer = deviceOutput_.buffer;
    outputBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 1u, &outputBarrier, 0u, nullptr);
    push.mode = 4u;
    push.residualStride = residualSampling.stride;
    push.residualColumns = residualSampling.columns;
    push.residualRows = residualSampling.rows;
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 6u);
    }
    vkCmdDispatch(commandBuffer_, (residualSampling.columns + 15u) / 16u,
                  (residualSampling.rows + 15u) / 16u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 7u);
    }
    VkBufferMemoryBarrier residualBarrier{};
    residualBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    residualBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    residualBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    residualBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    residualBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    residualBarrier.buffer = residualCandidates_.buffer;
    residualBarrier.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr, 1u, &residualBarrier, 0u, nullptr);
    if (phase6ResidualChromaRequested) {
        VkBufferMemoryBarrier phase6StatsBarrier{};
        phase6StatsBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        phase6StatsBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        phase6StatsBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        phase6StatsBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        phase6StatsBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        phase6StatsBarrier.buffer = colorStatistics_.buffer;
        phase6StatsBarrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr,
                             1u, &phase6StatsBarrier, 0u, nullptr);
    }
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.cpuFallbackRequired = true;
        result.status = "GPU_DEMOSAIC_COMMAND_END_FAILED";
        result.failureReason = "vkEndCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart); return result;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer_;
    const auto submitStart = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS) {
        result.cpuFallbackRequired = true;
        result.status = "GPU_DEMOSAIC_SUBMIT_FAILED";
        result.failureReason = "vkQueueSubmit_failed";
        result.totalMs = elapsedMs(totalStart); return result;
    }
    const VkResult wait = vkWaitForFences(device, 1u, &fence_, VK_TRUE, 4'000'000'000ull);
    result.synchronizationMs = elapsedMs(submitStart);
    if (wait != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("Demosaic");
        result.cpuFallbackRequired = true;
        result.status = "GPU_STALLED";
        result.failureReason = "vkWaitForFences_demosaic_" + std::to_string(wait);
        result.totalMs = elapsedMs(totalStart); return result;
    }
    if (queryPool_ != VK_NULL_HANDLE) {
        std::uint64_t timestamps[8]{0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u};
        if (vkGetQueryPoolResults(device, queryPool_, 0u, 8u, sizeof(timestamps), timestamps,
                                  sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physicalDevice, &properties);
            const double timestampMs = static_cast<double>(properties.limits.timestampPeriod) / 1.0e6;
            if ((request.algorithm == SpectraGpuDemosaicAlgorithm::AMAZE ||
                 request.algorithm == SpectraGpuDemosaicAlgorithm::AUTO_HYBRID) &&
                timestamps[1] >= timestamps[0] && timestamps[2] >= timestamps[1]) {
                const float guideMs = static_cast<float>(
                        static_cast<double>(timestamps[1] - timestamps[0]) * timestampMs);
                const float secondPassMs = static_cast<float>(
                        static_cast<double>(timestamps[2] - timestamps[1]) * timestampMs);
                if (request.algorithm == SpectraGpuDemosaicAlgorithm::AMAZE) {
                    result.amazeGreenPassMs = guideMs;
                    result.amazeReconstructPassMs = secondPassMs;
                } else {
                    result.autoHybridGuidePassMs = guideMs;
                    result.autoHybridBlendPassMs = secondPassMs;
                }
                result.kernelMs = guideMs + secondPassMs;
            } else if (timestamps[1] >= timestamps[0]) {
                result.kernelMs = static_cast<float>(
                        static_cast<double>(timestamps[1] - timestamps[0]) * timestampMs);
            }
            if (phase6ResidualChromaRequested && timestamps[4] >= timestamps[3] &&
                timestamps[5] >= timestamps[4]) {
                result.phase6ClassifyPassMs = static_cast<float>(
                        static_cast<double>(timestamps[4] - timestamps[3]) * timestampMs);
                result.phase6CorrectPassMs = static_cast<float>(
                        static_cast<double>(timestamps[5] - timestamps[4]) * timestampMs);
            }
            if (timestamps[7] >= timestamps[6]) {
                result.residualKernelMs = static_cast<float>(
                        static_cast<double>(timestamps[7] - timestamps[6]) * timestampMs);
            }
        }
    }
    const auto readStart = Clock::now();
    if (residualSampling.bytes > 0u) {
        vmaInvalidateAllocation(allocator, residualCandidates_.allocation, 0u,
                                static_cast<VkDeviceSize>(residualSampling.bytes));
        result.residualCandidates.resize(static_cast<std::size_t>(residualSampling.recordCount) * 8u);
        std::memcpy(result.residualCandidates.data(), residualCandidates_.mapped,
                    static_cast<std::size_t>(residualSampling.bytes));
    }
    if (phase6ResidualChromaRequested && statisticsBytes > 0u) {
        vmaInvalidateAllocation(allocator, colorStatistics_.allocation, 0u,
                                static_cast<VkDeviceSize>(statisticsBytes));
        const float* stats = static_cast<const float*>(colorStatistics_.mapped);
        double processed = 0.0;
        double candidates = 0.0;
        double isolated = 0.0;
        double zipper = 0.0;
        double edgeProtected = 0.0;
        double saturatedProtected = 0.0;
        double sumAbsRg = 0.0;
        double sumAbsBg = 0.0;
        float maxCorrection = 0.0f;
        for (std::uint64_t group = 0u; group < groupCount; ++group) {
            const std::size_t base = static_cast<std::size_t>(group * 9u);
            processed += std::max(0.0f, stats[base + 0u]);
            candidates += std::max(0.0f, stats[base + 1u]);
            isolated += std::max(0.0f, stats[base + 2u]);
            zipper += std::max(0.0f, stats[base + 3u]);
            edgeProtected += std::max(0.0f, stats[base + 4u]);
            saturatedProtected += std::max(0.0f, stats[base + 5u]);
            sumAbsRg += std::max(0.0f, stats[base + 6u]);
            sumAbsBg += std::max(0.0f, stats[base + 7u]);
            maxCorrection = std::max(maxCorrection, std::max(0.0f, stats[base + 8u]));
        }
        result.phase6ProcessedPixels = static_cast<std::uint64_t>(std::llround(processed));
        result.phase6CandidatePixels = static_cast<std::uint64_t>(std::llround(candidates));
        result.phase6IsolatedOutlierPixels = static_cast<std::uint64_t>(std::llround(isolated));
        result.phase6ZipperPixels = static_cast<std::uint64_t>(std::llround(zipper));
        result.phase6EdgeProtectedPixels = static_cast<std::uint64_t>(std::llround(edgeProtected));
        result.phase6SaturatedDetailProtectedPixels =
                static_cast<std::uint64_t>(std::llround(saturatedProtected));
        const double denominator = std::max(1.0, candidates);
        result.phase6MeanAbsCorrectionRG = sumAbsRg / denominator;
        result.phase6MeanAbsCorrectionBG = sumAbsBg / denominator;
        result.phase6MaximumAbsoluteCorrection = maxCorrection;
    }
    result.readbackMs = elapsedMs(readStart);
    // uploadMs is intentionally the non-kernel, non-readback submission share. Exact transfer GPU
    // timestamps can be added when the graph becomes fully resident and copies disappear entirely.
    result.uploadMs = std::max(0.0f, result.synchronizationMs - result.kernelMs -
            result.phase6ClassifyPassMs - result.phase6CorrectPassMs - result.residualKernelMs);
    result.success = true;
    result.gpuUsedForOutput = true;
    result.phase6ResidualChromaUsedForOutput = phase6ResidualChromaRequested;
    result.cpuFallbackRequired = false;
    result.fullReadbackDeferred = true;
    residentDemosaicGeneration_++;
    residentWidth_ = request.frameWidth;
    residentHeight_ = request.frameHeight;
    residentDemosaicValid_ = true;
    sourceClipConfidenceValid_ = sourceClipMapAppended;
    sourceClipConfidenceDemosaicGeneration_ = sourceClipMapAppended ? residentDemosaicGeneration_ : 0u;
    sourceClipConfidenceWidth_ = sourceClipMapAppended ? sourceClipWidth : 0u;
    sourceClipConfidenceHeight_ = sourceClipMapAppended ? sourceClipHeight : 0u;
    result.residentDemosaicGeneration = residentDemosaicGeneration_;
    const char* algorithmStatus = request.algorithm == SpectraGpuDemosaicAlgorithm::BILINEAR
            ? "BILINEAR"
            : (request.algorithm == SpectraGpuDemosaicAlgorithm::NEURAL_JDD
                    ? "NEURAL_JDD"
                    : (request.algorithm == SpectraGpuDemosaicAlgorithm::AMAZE
                            ? "AMAZE"
                            : (request.algorithm == SpectraGpuDemosaicAlgorithm::AUTO_HYBRID
                                    ? "AUTO_HYBRID"
                                    : "MALVAR_2004")));
    result.status = std::string(residentInput ? "GPU_RESIDENT_INPUT_PRIMARY_" : "GPU_PRIMARY_") +
            algorithmStatus;
    result.failureReason = "none";
    result.totalMs = elapsedMs(totalStart);
    return result;
#endif
}


SpectraResidentColorTransformResult VulkanSpectraResidentDemosaicBackend::executeAwbCcm(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const SpectraResidentColorTransformRequest& request
) noexcept {
    SpectraResidentColorTransformResult result{};
    result.attempted = true;
    const auto totalStart = Clock::now();
    if (request.frameWidth == 0u || request.frameHeight == 0u ||
        device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE) {
        result.status = "GPU_COLOR_TRANSFORM_INVALID_INPUT";
        result.failureReason = "INVALID_RESIDENT_COLOR_TRANSFORM_REQUEST";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_DEMOSAIC_SHADER_AVAILABLE
    (void)physicalDevice; (void)allocatorOwner;
    result.status = "GPU_COLOR_TRANSFORM_BUILD_SUPPORT_UNAVAILABLE";
    result.failureReason = !BNCAM_VMA_HEADER_AVAILABLE
            ? "VMA_HEADER_NOT_AVAILABLE" : "RESIDENT_DEMOSAIC_SHADER_NOT_COMPILED";
    result.totalMs = elapsedMs(totalStart);
    return result;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    std::string failure;
    if (!initializeLocked(device, commandPool, failure)) {
        result.status = "GPU_COLOR_TRANSFORM_INITIALIZATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    VmaAllocator allocator = allocatorOwner.handle();
    if (allocator == nullptr) {
        result.status = "GPU_COLOR_TRANSFORM_ALLOCATOR_UNAVAILABLE";
        result.failureReason = "VMA_ALLOCATOR_NOT_READY";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    allocator_ = allocator;
    const std::uint64_t pixelCount = static_cast<std::uint64_t>(request.frameWidth) * request.frameHeight;
    const std::uint64_t rgbBytes = pixelCount * 3u * sizeof(float);
    const std::uint64_t groupCount =
            static_cast<std::uint64_t>((request.frameWidth + 15u) / 16u) *
            static_cast<std::uint64_t>((request.frameHeight + 15u) / 16u);
    const std::uint64_t statisticsBytes = std::max<std::uint64_t>(48u, groupCount * 12u * sizeof(float));
    const ResidualSampling residualSampling = residualSamplingFor(request.frameWidth, request.frameHeight);
    const bool cloudMapContractValid = request.preWbCloudCorrectionReady &&
            request.preWbCloudCorrectionRG != nullptr &&
            request.preWbCloudCorrectionBG != nullptr &&
            request.preWbCloudCorrectionValid != nullptr &&
            request.preWbCloudGridColumns == 16u && request.preWbCloudGridRows == 12u &&
            request.preWbCloudValidTileCount >= 6u;
    const std::uint64_t cloudMapRecordCount = cloudMapContractValid ? 192u : 1u;
    const std::uint64_t cloudMapBytes = cloudMapRecordCount * 4u * sizeof(float);
    const bool hueSatData1Valid = request.calibratedHueSatMapEnabled &&
            hueSatTableValid(request, request.hueSatData1, request.hueSatData1FloatCount);
    const bool hueSatSecondRequested = request.hueSatData2 != nullptr || request.hueSatData2FloatCount != 0u ||
            request.hueSatWeightSecond > 1.0e-6f;
    const bool hueSatData2Valid = !hueSatSecondRequested ||
            hueSatTableValid(request, request.hueSatData2, request.hueSatData2FloatCount);
    const float hueSatWeightFirst = std::clamp(request.hueSatWeightFirst, 0.0f, 1.0f);
    const float hueSatWeightSecond = std::clamp(request.hueSatWeightSecond, 0.0f, 1.0f);
    const bool hueSatWeightsValid = std::isfinite(request.hueSatWeightFirst) &&
            std::isfinite(request.hueSatWeightSecond) &&
            std::abs((hueSatWeightFirst + hueSatWeightSecond) - 1.0f) <= 1.0e-3f;
    const bool hueSatMapContractValid = request.calibratedHueSatMapEnabled && hueSatData1Valid &&
            hueSatData2Valid && hueSatWeightsValid;
    const std::uint64_t hueSatEntryCount = hueSatMapContractValid
            ? static_cast<std::uint64_t>(request.hueSatHueDivisions) * request.hueSatSaturationDivisions *
                    request.hueSatValueDivisions : 0u;
    const std::uint64_t hueSatTableFloats = hueSatEntryCount * 3u;
    const std::uint64_t hueSatProfileFloats = kHueSatHeaderFloats + hueSatTableFloats +
            (hueSatSecondRequested && hueSatMapContractValid ? hueSatTableFloats : 0u);
    const std::uint64_t hueSatProfileBytes = std::max<std::uint64_t>(kHueSatHeaderFloats * sizeof(float),
            hueSatProfileFloats * sizeof(float));
    result.calibratedHueSatMapRequested = request.calibratedHueSatMapEnabled;
    result.calibratedHueSatMapWeightFirst = hueSatWeightFirst;
    result.calibratedHueSatMapWeightSecond = hueSatWeightSecond;
    result.residualSampleStride = residualSampling.stride;
    result.residualSampleColumns = residualSampling.columns;
    result.residualSampleRows = residualSampling.rows;
    result.compactStatisticsBytes = statisticsBytes + residualSampling.bytes +
            kColorTelemetryWords * sizeof(std::uint32_t);

    const bool residentInputUsable = residentDemosaicValid_ &&
            request.residentDemosaicGeneration != 0u &&
            request.residentDemosaicGeneration == residentDemosaicGeneration_ &&
            residentWidth_ == request.frameWidth && residentHeight_ == request.frameHeight &&
            deviceOutput_.buffer != VK_NULL_HANDLE && deviceOutput_.capacityBytes >= rgbBytes;
    const std::uint32_t expectedClipWidth = (request.frameWidth + 1u) / 2u;
    const std::uint32_t expectedClipHeight = (request.frameHeight + 1u) / 2u;
    const std::uint64_t expectedClipBytes = static_cast<std::uint64_t>(expectedClipWidth) *
            expectedClipHeight * sizeof(float);
    const bool sourceClipConfidenceReady = residentInputUsable && sourceClipConfidenceValid_ &&
            sourceClipConfidenceDemosaicGeneration_ == request.residentDemosaicGeneration &&
            sourceClipConfidenceWidth_ == expectedClipWidth &&
            sourceClipConfidenceHeight_ == expectedClipHeight &&
            sourceClipConfidence_.buffer != VK_NULL_HANDLE &&
            sourceClipConfidence_.capacityBytes >= expectedClipBytes;
    bool reallocated = false;
    const std::uint32_t writeAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
    const std::uint32_t readAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    if (!ensureBufferLocked(allocator, sizeof(float), 0u, deviceInput_, reallocated, failure) ||
        (!residentInputUsable &&
         !ensureBufferLocked(allocator, rgbBytes, writeAccess, inputStaging_, reallocated, failure)) ||
        !ensureBufferLocked(allocator, rgbBytes, 0u, deviceOutput_, reallocated, failure) ||
        !ensureBufferLocked(allocator, rgbBytes, 0u, rgbUpload_, reallocated, failure) ||
        (!request.deferFullReadback &&
         !ensureBufferLocked(allocator, rgbBytes, readAccess, outputReadback_, reallocated, failure)) ||
        !ensureBufferLocked(allocator, statisticsBytes, readAccess, colorStatistics_, reallocated, failure) ||
        !ensureBufferLocked(allocator, kColorTelemetryWords * sizeof(std::uint32_t), readAccess, colorTelemetry_, reallocated, failure) ||
        !ensureBufferLocked(allocator, cloudMapBytes, writeAccess, cloudCorrectionMap_, reallocated, failure) ||
        !ensureBufferLocked(allocator, hueSatProfileBytes, writeAccess, hueSatProfile_, reallocated, failure) ||
        !ensureBufferLocked(allocator, std::max<std::uint64_t>(24u, residualSampling.bytes),
                            readAccess, residualCandidates_, reallocated, failure)) {
        result.status = "GPU_COLOR_TRANSFORM_BUFFER_ALLOCATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = inputStaging_.capacityBytes + outputReadback_.capacityBytes +
            deviceInput_.capacityBytes + deviceOutput_.capacityBytes + rgbUpload_.capacityBytes +
            colorStatistics_.capacityBytes + colorTelemetry_.capacityBytes +
            sourceClipConfidence_.capacityBytes + cloudCorrectionMap_.capacityBytes +
            hueSatProfile_.capacityBytes + residualCandidates_.capacityBytes;
    result.phase9SourceRawConfidenceMapUsed = sourceClipConfidenceReady;
    result.phase9SourceRawConfidenceMapBytes = sourceClipConfidenceReady ? expectedClipBytes : 0u;

    // Upload the compact 16x12 opponent cloud field consumed by mode-3 pre-WB correction.
    float* cloudPacked = static_cast<float*>(cloudCorrectionMap_.mapped);
    std::fill(cloudPacked, cloudPacked + static_cast<std::ptrdiff_t>(cloudMapRecordCount * 4u), 0.0f);
    if (cloudMapContractValid) {
        for (std::size_t i = 0; i < 192u; ++i) {
            const std::size_t base = i * 4u;
            cloudPacked[base + 0u] = request.preWbCloudCorrectionRG[i];
            cloudPacked[base + 1u] = request.preWbCloudCorrectionBG[i];
            cloudPacked[base + 2u] = request.preWbCloudCorrectionValid[i] != 0u ? 1.0f : 0.0f;
            cloudPacked[base + 3u] = 0.0f;
        }
        result.cloudCorrectionMapUploaded = true;
        result.cloudCorrectionMapBytes = cloudMapBytes;
    }
    vmaFlushAllocation(allocator, cloudCorrectionMap_.allocation, 0u, static_cast<VkDeviceSize>(cloudMapBytes));

    // Upload only trusted, already-validated DNG tables. The resident shader performs the
    // actual illuminant blend and trilinear HSM sampling; no scene-derived synthetic LUT exists.
    float* hueSatPacked = static_cast<float*>(hueSatProfile_.mapped);
    std::fill(hueSatPacked, hueSatPacked + static_cast<std::ptrdiff_t>(hueSatProfileBytes / sizeof(float)), 0.0f);
    hueSatPacked[0] = hueSatMapContractValid ? 1.0f : 0.0f;
    if (hueSatMapContractValid) {
        hueSatPacked[1] = static_cast<float>(request.hueSatHueDivisions);
        hueSatPacked[2] = static_cast<float>(request.hueSatSaturationDivisions);
        hueSatPacked[3] = static_cast<float>(request.hueSatValueDivisions);
        hueSatPacked[4] = static_cast<float>(request.hueSatEncoding);
        hueSatPacked[5] = hueSatWeightFirst;
        hueSatPacked[6] = hueSatWeightSecond;
        hueSatPacked[7] = hueSatSecondRequested ? 1.0f : 0.0f;
        hueSatPacked[8] = static_cast<float>(hueSatEntryCount);
        std::memcpy(hueSatPacked + kHueSatHeaderFloats, request.hueSatData1,
                    static_cast<std::size_t>(hueSatTableFloats) * sizeof(float));
        if (hueSatSecondRequested) {
            std::memcpy(hueSatPacked + kHueSatHeaderFloats + hueSatTableFloats, request.hueSatData2,
                        static_cast<std::size_t>(hueSatTableFloats) * sizeof(float));
        }
        result.calibratedHueSatMapProfileBytes = hueSatProfileFloats * sizeof(float);
    }
    vmaFlushAllocation(allocator, hueSatProfile_.allocation, 0u, static_cast<VkDeviceSize>(hueSatProfileBytes));
    // Phase 9: immutable pre-WB RGB at binding 1, protected post-CCM output at binding 2.
    // Binding 0 is repurposed in mode 3 for the source-RAW confidence map when the exact
    // demosaic generation owns one; CPU/reference fallbacks retain the legacy path.
    updateDescriptorSetLocked(device,
            sourceClipConfidenceReady ? sourceClipConfidence_.buffer : VK_NULL_HANDLE,
            rgbUpload_.buffer);

    bool useResident = residentInputUsable;
    if (!useResident) {
        if (request.rgbData == nullptr || request.rowStrideFloats < static_cast<std::size_t>(request.frameWidth) * 3u) {
            result.status = "GPU_COLOR_TRANSFORM_NO_VALID_RGB_INPUT";
            result.failureReason = "RESIDENT_GENERATION_MISMATCH_AND_CPU_RGB_UNAVAILABLE";
            result.totalMs = elapsedMs(totalStart);
            return result;
        }
        const auto packStart = Clock::now();
        float* packed = static_cast<float*>(inputStaging_.mapped);
        const std::size_t tightRowFloats = static_cast<std::size_t>(request.frameWidth) * 3u;
        for (std::uint32_t y = 0; y < request.frameHeight; ++y) {
            std::memcpy(packed + static_cast<std::size_t>(y) * tightRowFloats,
                        request.rgbData + static_cast<std::size_t>(y) * request.rowStrideFloats,
                        tightRowFloats * sizeof(float));
        }
        result.inputPackingMs = elapsedMs(packStart);
        vmaFlushAllocation(allocator, inputStaging_.allocation, 0u, static_cast<VkDeviceSize>(rgbBytes));
        result.cpuRgbUploadUsed = true;
    } else {
        result.residentInputUsed = true;
    }

    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "GPU_COLOR_TRANSFORM_COMMAND_BEGIN_FAILED";
        result.failureReason = "vkBeginCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    if (!useResident) {
        VkBufferCopy copy{};
        copy.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdCopyBuffer(commandBuffer_, inputStaging_.buffer, deviceOutput_.buffer, 1u, &copy);
        VkBufferMemoryBarrier barrier{};
        barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = deviceOutput_.buffer;
        barrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 1u, &barrier, 0u, nullptr);
    }
    vkCmdFillBuffer(commandBuffer_, colorTelemetry_.buffer, 0u,
                    kColorTelemetryWords * sizeof(std::uint32_t), 0u);
    VkBufferMemoryBarrier phase9TelemetryClear{};
    phase9TelemetryClear.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    phase9TelemetryClear.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    phase9TelemetryClear.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    phase9TelemetryClear.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    phase9TelemetryClear.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    phase9TelemetryClear.buffer = colorTelemetry_.buffer;
    phase9TelemetryClear.size = kColorTelemetryWords * sizeof(std::uint32_t);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &phase9TelemetryClear, 0u, nullptr);
    if (cloudMapContractValid) {
        VkBufferMemoryBarrier cloudHostToShader{};
        cloudHostToShader.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        cloudHostToShader.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        cloudHostToShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        cloudHostToShader.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        cloudHostToShader.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        cloudHostToShader.buffer = cloudCorrectionMap_.buffer;
        cloudHostToShader.size = static_cast<VkDeviceSize>(cloudMapBytes);
        vkCmdPipelineBarrier(
                commandBuffer_, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0u, 0u, nullptr, 1u, &cloudHostToShader, 0u, nullptr);
    }
    if (hueSatMapContractValid) {
        VkBufferMemoryBarrier hueSatHostToShader{};
        hueSatHostToShader.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        hueSatHostToShader.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        hueSatHostToShader.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        hueSatHostToShader.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hueSatHostToShader.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hueSatHostToShader.buffer = hueSatProfile_.buffer;
        hueSatHostToShader.size = static_cast<VkDeviceSize>(hueSatProfileBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_HOST_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &hueSatHostToShader, 0u, nullptr);
    }
    if (sourceClipConfidenceReady) {
        VkBufferMemoryBarrier sourceClipReady{};
        sourceClipReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        sourceClipReady.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        sourceClipReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        sourceClipReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sourceClipReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sourceClipReady.buffer = sourceClipConfidence_.buffer;
        sourceClipReady.size = static_cast<VkDeviceSize>(expectedClipBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &sourceClipReady, 0u, nullptr);
    }
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
    push.mode = 3u;
    push.wbR = request.wbRgb[0];
    push.wbG = request.wbRgb[1];
    push.wbB = request.wbRgb[2];
    // The 128-byte push layout is already at Vulkan's minimum guaranteed limit. During mode 3
    // the demosaic evidence slots are no longer needed, so reuse two of them for the pre-WB
    // opponent cleanup maxima rather than growing the push-constant block.
    push.cfaEvidence0[0] = std::clamp(request.preWbOpponentCleanupBlend[0], 0.0f, 0.12f);
    push.cfaEvidence0[1] = std::clamp(request.preWbOpponentCleanupBlend[1], 0.0f, 0.12f);
    push.cfaEvidence0[2] = cloudMapContractValid ? 1.0f : 0.0f;
    push.cfaEvidence0[3] = cloudMapContractValid
            ? std::clamp(request.preWbCloudMaxAbsoluteCorrection, 0.0f, 0.010f)
            : 0.0f;
    // cfaEvidence1 is unused by mode 3 reconstruction; slot 0 is an exact generation-scoped
    // source-RAW confidence-map availability flag without growing the 128-byte push block.
    push.cfaEvidence1[0] = sourceClipConfidenceReady ? 1.0f : 0.0f;
    push.ccm[0] = request.colorMatrix[0];
    push.ccm[1] = request.colorMatrix[1];
    push.ccm[2] = request.colorMatrix[2];
    push.ccm[3] = request.colorMatrix[3];
    push.ccm[4] = request.colorMatrix[4];
    push.ccm[5] = request.colorMatrix[5];
    push.ccm[6] = request.colorMatrix[6];
    push.ccm[7] = request.colorMatrix[7];
    push.ccm[8] = request.colorMatrix[8];
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u, (request.frameHeight + 15u) / 16u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
    }
    // Phase 9 AWB+CCM writes rgbUpload_ while deviceOutput_ stays immutable pre-WB source. Before the one unavoidable downstream RGB
    // readback, generate the post-colour residual observation on GPU as compact candidates.
    VkBufferMemoryBarrier colorToResidual{};
    colorToResidual.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    colorToResidual.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    colorToResidual.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    colorToResidual.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    colorToResidual.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    colorToResidual.buffer = rgbUpload_.buffer;
    colorToResidual.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &colorToResidual, 0u, nullptr);
    push.mode = 11u; // Phase 9: post-colour residual domain reads colorRgb.
    push.residualStride = residualSampling.stride;
    push.residualColumns = residualSampling.columns;
    push.residualRows = residualSampling.rows;
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 2u);
    }
    vkCmdDispatch(commandBuffer_, (residualSampling.columns + 15u) / 16u,
                  (residualSampling.rows + 15u) / 16u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 3u);
    }

    VkBufferMemoryBarrier barriers[4]{};
    barriers[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    barriers[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barriers[0].dstAccessMask = request.deferFullReadback
            ? VK_ACCESS_SHADER_READ_BIT
            : VK_ACCESS_TRANSFER_READ_BIT;
    barriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[0].buffer = rgbUpload_.buffer;
    barriers[0].size = VK_WHOLE_SIZE;
    barriers[1].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    barriers[1].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barriers[1].dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    barriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[1].buffer = colorStatistics_.buffer;
    barriers[1].size = VK_WHOLE_SIZE;
    barriers[2].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    barriers[2].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barriers[2].dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    barriers[2].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[2].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[2].buffer = residualCandidates_.buffer;
    barriers[2].size = VK_WHOLE_SIZE;
    barriers[3].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    barriers[3].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barriers[3].dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    barriers[3].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[3].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[3].buffer = colorTelemetry_.buffer;
    barriers[3].size = kColorTelemetryWords * sizeof(std::uint32_t);
    const VkPipelineStageFlags colorDestinationStage = request.deferFullReadback
            ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT
            : VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         colorDestinationStage, 0u, 0u, nullptr, 4u, barriers, 0u, nullptr);
    if (!request.deferFullReadback) {
        VkBufferCopy colorCopy{};
        colorCopy.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdCopyBuffer(commandBuffer_, rgbUpload_.buffer, outputReadback_.buffer, 1u, &colorCopy);
    }
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "GPU_COLOR_TRANSFORM_COMMAND_END_FAILED";
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
        result.status = "GPU_COLOR_TRANSFORM_SUBMIT_FAILED";
        result.failureReason = "vkQueueSubmit_failed";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    const VkResult wait = vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull);
    result.synchronizationMs = elapsedMs(submitStart);
    if (wait != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("AwbCcm");
        result.status = "GPU_STALLED";
        result.failureReason = "vkWaitForFences_awb_ccm_" + std::to_string(wait);
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    if (queryPool_ != VK_NULL_HANDLE) {
        std::uint64_t timestamps[4]{0u, 0u, 0u, 0u};
        if (vkGetQueryPoolResults(device, queryPool_, 0u, 4u, sizeof(timestamps), timestamps,
                                  sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            timestamps[1] >= timestamps[0]) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physicalDevice, &properties);
            const double timestampMs = static_cast<double>(properties.limits.timestampPeriod) / 1.0e6;
            result.kernelMs = static_cast<float>(
                    static_cast<double>(timestamps[1] - timestamps[0]) * timestampMs);
            if (timestamps[3] >= timestamps[2]) {
                result.residualKernelMs = static_cast<float>(
                        static_cast<double>(timestamps[3] - timestamps[2]) * timestampMs);
            }
        }
    }

    const auto readStart = Clock::now();
    if (!request.deferFullReadback) {
        vmaInvalidateAllocation(allocator, outputReadback_.allocation, 0u, static_cast<VkDeviceSize>(rgbBytes));
    }
    vmaInvalidateAllocation(allocator, colorStatistics_.allocation, 0u, static_cast<VkDeviceSize>(statisticsBytes));
    vmaInvalidateAllocation(allocator, colorTelemetry_.allocation, 0u,
                            kColorTelemetryWords * sizeof(std::uint32_t));
    if (residualSampling.bytes > 0u) {
        vmaInvalidateAllocation(allocator, residualCandidates_.allocation, 0u,
                                static_cast<VkDeviceSize>(residualSampling.bytes));
        result.residualCandidates.resize(static_cast<std::size_t>(residualSampling.recordCount) * 8u);
        std::memcpy(result.residualCandidates.data(), residualCandidates_.mapped,
                    static_cast<std::size_t>(residualSampling.bytes));
    }
    if (!request.deferFullReadback) {
        result.outputRgb.resize(static_cast<std::size_t>(pixelCount) * 3u);
        std::memcpy(result.outputRgb.data(), outputReadback_.mapped, static_cast<std::size_t>(rgbBytes));
    }
    result.readbackMs = elapsedMs(readStart);

    const auto reduceStart = Clock::now();
    const float* statistics = static_cast<const float*>(colorStatistics_.mapped);
    double sums[12]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
    for (std::uint64_t group = 0; group < groupCount; ++group) {
        const float* record = statistics + static_cast<std::size_t>(group) * 12u;
        for (int i = 0; i < 12; ++i) sums[i] += static_cast<double>(record[i]);
    }
    const double inversePixels = 1.0 / static_cast<double>(std::max<std::uint64_t>(1u, pixelCount));
    for (int c = 0; c < 3; ++c) {
        result.rawMean[c] = sums[c] * inversePixels;
        result.wbMean[c] = sums[3 + c] * inversePixels;
        result.ccmMean[c] = sums[6 + c] * inversePixels;
    }
    result.cloudMeanAbsCorrectionRG = sums[9] * inversePixels;
    result.cloudMeanAbsCorrectionBG = sums[10] * inversePixels;
    result.cloudAffectedPixelFraction = std::clamp(sums[11] * inversePixels, 0.0, 1.0);
    const auto* phase9 = static_cast<const std::uint32_t*>(colorTelemetry_.mapped);
    result.phase9SensorClipCandidatePixels = phase9[0];
    result.phase9SingleChannelSensorClipPixels = phase9[1];
    result.phase9MultiChannelSensorClipPixels = phase9[2];
    result.phase9WbAboveUnityWithoutSensorClipPixels = phase9[3];
    result.phase9CcmNegativeExcursionPixels = phase9[4];
    result.phase9ColorConfidenceAppliedPixels = phase9[5];
    result.phase9GamutCompressedPixels = phase9[6];
    result.phase9LegacyMagentaRiskPixels = phase9[7];
    result.phase9ProtectedMagentaRiskPixels = phase9[8];
    result.phase9SceneLinearOverUnityPixels = phase9[9];
    result.phase9FullySensorClippedPixels = phase9[10];
    result.phase9PartialColorConfidencePixels = phase9[11];
    result.phase9SourceRawConfidenceCandidatePixels = phase9[12];
    result.phase9SourceRawZeroConfidencePixels = phase9[13];
    result.phase9SourceRawPartialConfidencePixels = phase9[14];
    result.phase9SourceRawDemosaicDisagreementPixels = phase9[15];
    result.calibratedHueSatMapAppliedPixels = phase9[16];
    result.calibratedHueSatMapApplied = hueSatMapContractValid && phase9[16] > 0u;
    result.compactStatisticsReductionMs = elapsedMs(reduceStart);
    result.success = request.deferFullReadback ||
            result.outputRgb.size() == static_cast<std::size_t>(pixelCount) * 3u;
    result.fullReadbackDeferred = request.deferFullReadback;
    result.cloudCorrectionApplied = result.success && cloudMapContractValid;
    // Promote protected post-CCM scratch to resident output without a full-frame copy/readback.
    std::swap(deviceOutput_, rgbUpload_);

    // deviceOutput_ now contains post-CCM RGB, not the original demosaic generation. Prevent
    // accidental reuse/reapplication of WB+CCM under the same demosaic token, but publish a new
    // opaque color generation when the next Vulkan stage owns the full-resolution buffer.
    residentDemosaicValid_ = false;
    sourceClipConfidenceValid_ = false;
    sourceClipConfidenceDemosaicGeneration_ = 0u;
    if (result.success && request.deferFullReadback) {
        residentColorGeneration_++;
        residentColorValid_ = true;
        residentWidth_ = request.frameWidth;
        residentHeight_ = request.frameHeight;
        result.residentColorGeneration = residentColorGeneration_;
    } else {
        residentColorValid_ = false;
    }
    result.status = result.success
            ? (request.deferFullReadback
                    ? (result.calibratedHueSatMapApplied
                            ? "GPU_PRIMARY_AWB_DNG_CCM_HUESATMAP_RESIDENT_OUTPUT"
                            : "GPU_PRIMARY_AWB_CCM_RESIDENT_OUTPUT")
                    : (useResident ? "GPU_PRIMARY_AWB_CCM_RESIDENT_DEMOSAIC_INPUT"
                                   : "GPU_PRIMARY_AWB_CCM_CPU_RGB_UPLOAD_FALLBACK_INPUT"))
            : "GPU_COLOR_TRANSFORM_READBACK_INCOMPLETE";
    result.failureReason = "none";
    result.totalMs = elapsedMs(totalStart);
    return result;
#endif
}

bool VulkanSpectraResidentDemosaicBackend::resolveResidentColorOutput(
        std::uint64_t generation, VkBuffer& buffer, std::uint64_t& bytes,
        std::uint32_t& width, std::uint32_t& height) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!residentColorValid_ || generation == 0u || generation != residentColorGeneration_ ||
        deviceOutput_.buffer == VK_NULL_HANDLE || residentWidth_ == 0u || residentHeight_ == 0u) {
        return false;
    }
    buffer = deviceOutput_.buffer;
    width = residentWidth_;
    height = residentHeight_;
    bytes = static_cast<std::uint64_t>(width) * height * 3u * sizeof(float);
    return true;
}

} // namespace bncam::vulkan
