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
                                         &rgbUpload_, &colorStatistics_, &cloudCorrectionMap_, &residualCandidates_}) {
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
    VkDescriptorSetLayoutBinding bindings[6]{};
    for (std::uint32_t i = 0; i < 6u; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo descriptorInfo{};
    descriptorInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorInfo.bindingCount = 6u;
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
    poolSize.descriptorCount = 6u;
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
    // Two timestamp pairs: primary image kernel and compact residual extraction kernel.
    queryInfo.queryCount = 4u;
    // Timestamp queries are optional observability. Failure must not disable the kernel.
    if (vkCreateQueryPool(device, &queryInfo, nullptr, &queryPool_) != VK_SUCCESS) queryPool_ = VK_NULL_HANDLE;
    initialized_ = true;
    initializedDevice_ = device;
    initializedCommandPool_ = commandPool;
    failureReason.clear();
    return true;
#endif
}

void VulkanSpectraResidentDemosaicBackend::updateDescriptorSetLocked(VkDevice device, VkBuffer inputOverride) noexcept {
    VkDescriptorBufferInfo infos[6]{};
    infos[0].buffer = inputOverride != VK_NULL_HANDLE ? inputOverride : deviceInput_.buffer;
    infos[1].buffer = deviceOutput_.buffer;
    // AWB/CCM is pointwise, therefore binding 2 may safely alias binding 1 and transform
    // the resident demosaic RGB in-place before the final readback.
    infos[2].buffer = deviceOutput_.buffer;
    infos[3].buffer = colorStatistics_.buffer;
    infos[4].buffer = residualCandidates_.buffer;
    infos[5].buffer = cloudCorrectionMap_.buffer;
    for (auto& info : infos) info.range = VK_WHOLE_SIZE;
    VkWriteDescriptorSet writes[6]{};
    for (std::uint32_t i = 0; i < 6u; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, 6u, writes, 0u, nullptr);
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
        result.status = "GPU_DEMOSAIC_MENON_TYPED_FALLBACK_PENDING_RCD_AMAZE_MIGRATION";
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
        !ensureBufferLocked(allocator, statisticsBytes, readAccess, colorStatistics_, reallocated, failure) ||
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
            deviceInput_.capacityBytes + deviceOutput_.capacityBytes + colorStatistics_.capacityBytes +
            cloudCorrectionMap_.capacityBytes + residualCandidates_.capacityBytes;
    // Rebind every demosaic submission because binding 0 may alternate between
    // an internal upload buffer and an opaque resident Pass-3 buffer.
    updateDescriptorSetLocked(device, residentInput ? residentInputBuffer : VK_NULL_HANDLE);

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
    uploadBarrier.size = static_cast<VkDeviceSize>(inputBytes);
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
        uploadBarrier.buffer = residentInputBuffer;
        vkCmdPipelineBarrier(commandBuffer_,
                             VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                             1u, &uploadBarrier, 0u, nullptr);
    }

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
    switch (request.algorithm) {
        case SpectraGpuDemosaicAlgorithm::BILINEAR:
            push.mode = 0u;
            break;
        case SpectraGpuDemosaicAlgorithm::RCD_INSPIRED:
            push.mode = 2u;
            break;
        case SpectraGpuDemosaicAlgorithm::AMAZE_INSPIRED:
            push.mode = 5u;
            break;
        case SpectraGpuDemosaicAlgorithm::MALVAR_2004:
        default:
            push.mode = 1u;
            break;
    }
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u, (request.frameHeight + 15u) / 16u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
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
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 2u);
    }
    vkCmdDispatch(commandBuffer_, (residualSampling.columns + 15u) / 16u,
                  (residualSampling.rows + 15u) / 16u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 3u);
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
    if (residualSampling.bytes > 0u) {
        vmaInvalidateAllocation(allocator, residualCandidates_.allocation, 0u,
                                static_cast<VkDeviceSize>(residualSampling.bytes));
        result.residualCandidates.resize(static_cast<std::size_t>(residualSampling.recordCount) * 8u);
        std::memcpy(result.residualCandidates.data(), residualCandidates_.mapped,
                    static_cast<std::size_t>(residualSampling.bytes));
    }
    result.readbackMs = elapsedMs(readStart);
    // uploadMs is intentionally the non-kernel, non-readback submission share. Exact transfer GPU
    // timestamps can be added when the graph becomes fully resident and copies disappear entirely.
    result.uploadMs = std::max(0.0f, result.synchronizationMs - result.kernelMs - result.residualKernelMs);
    result.success = true;
    result.gpuUsedForOutput = true;
    result.cpuFallbackRequired = false;
    result.fullReadbackDeferred = true;
    residentDemosaicGeneration_++;
    residentWidth_ = request.frameWidth;
    residentHeight_ = request.frameHeight;
    residentDemosaicValid_ = true;
    result.residentDemosaicGeneration = residentDemosaicGeneration_;
    const char* algorithmStatus = request.algorithm == SpectraGpuDemosaicAlgorithm::BILINEAR
            ? "BILINEAR"
            : (request.algorithm == SpectraGpuDemosaicAlgorithm::RCD_INSPIRED
                    ? "RCD_INSPIRED"
                    : (request.algorithm == SpectraGpuDemosaicAlgorithm::AMAZE_INSPIRED
                            ? "AMAZE_INSPIRED"
                            : "MALVAR_2004"));
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
    result.residualSampleStride = residualSampling.stride;
    result.residualSampleColumns = residualSampling.columns;
    result.residualSampleRows = residualSampling.rows;
    result.compactStatisticsBytes = statisticsBytes + residualSampling.bytes;

    const bool residentInputUsable = residentDemosaicValid_ &&
            request.residentDemosaicGeneration != 0u &&
            request.residentDemosaicGeneration == residentDemosaicGeneration_ &&
            residentWidth_ == request.frameWidth && residentHeight_ == request.frameHeight &&
            deviceOutput_.buffer != VK_NULL_HANDLE && deviceOutput_.capacityBytes >= rgbBytes;
    bool reallocated = false;
    const std::uint32_t writeAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
    const std::uint32_t readAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    if (!ensureBufferLocked(allocator, sizeof(float), 0u, deviceInput_, reallocated, failure) ||
        !ensureBufferLocked(allocator, rgbBytes, 0u, deviceOutput_, reallocated, failure) ||
        (!request.deferFullReadback &&
         !ensureBufferLocked(allocator, rgbBytes, readAccess, outputReadback_, reallocated, failure)) ||
        !ensureBufferLocked(allocator, statisticsBytes, readAccess, colorStatistics_, reallocated, failure) ||
        !ensureBufferLocked(allocator, cloudMapBytes, writeAccess, cloudCorrectionMap_, reallocated, failure) ||
        !ensureBufferLocked(allocator, std::max<std::uint64_t>(24u, residualSampling.bytes),
                            readAccess, residualCandidates_, reallocated, failure) ||
        (!residentInputUsable &&
         !ensureBufferLocked(allocator, rgbBytes, writeAccess, rgbUpload_, reallocated, failure))) {
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
            colorStatistics_.capacityBytes + cloudCorrectionMap_.capacityBytes + residualCandidates_.capacityBytes;

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
    if (reallocated || !descriptorBindingsInitialized_) updateDescriptorSetLocked(device);

    bool useResident = residentInputUsable;
    if (!useResident) {
        if (request.rgbData == nullptr || request.rowStrideFloats < static_cast<std::size_t>(request.frameWidth) * 3u) {
            result.status = "GPU_COLOR_TRANSFORM_NO_VALID_RGB_INPUT";
            result.failureReason = "RESIDENT_GENERATION_MISMATCH_AND_CPU_RGB_UNAVAILABLE";
            result.totalMs = elapsedMs(totalStart);
            return result;
        }
        const auto packStart = Clock::now();
        float* packed = static_cast<float*>(rgbUpload_.mapped);
        const std::size_t tightRowFloats = static_cast<std::size_t>(request.frameWidth) * 3u;
        for (std::uint32_t y = 0; y < request.frameHeight; ++y) {
            std::memcpy(packed + static_cast<std::size_t>(y) * tightRowFloats,
                        request.rgbData + static_cast<std::size_t>(y) * request.rowStrideFloats,
                        tightRowFloats * sizeof(float));
        }
        result.inputPackingMs = elapsedMs(packStart);
        vmaFlushAllocation(allocator, rgbUpload_.allocation, 0u, static_cast<VkDeviceSize>(rgbBytes));
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
        vkCmdCopyBuffer(commandBuffer_, rgbUpload_.buffer, deviceOutput_.buffer, 1u, &copy);
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
    // AWB+CCM writes deviceOutput_ in place. Before the one unavoidable downstream RGB
    // readback, generate the post-colour residual observation on GPU as compact candidates.
    VkBufferMemoryBarrier colorToResidual{};
    colorToResidual.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    colorToResidual.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    colorToResidual.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    colorToResidual.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    colorToResidual.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    colorToResidual.buffer = deviceOutput_.buffer;
    colorToResidual.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr,
                         1u, &colorToResidual, 0u, nullptr);
    push.mode = 4u;
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

    VkBufferMemoryBarrier barriers[3]{};
    barriers[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    barriers[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barriers[0].dstAccessMask = request.deferFullReadback
            ? VK_ACCESS_SHADER_READ_BIT
            : VK_ACCESS_TRANSFER_READ_BIT;
    barriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barriers[0].buffer = deviceOutput_.buffer;
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
    const VkPipelineStageFlags colorDestinationStage = request.deferFullReadback
            ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT
            : VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         colorDestinationStage, 0u, 0u, nullptr, 3u, barriers, 0u, nullptr);
    if (!request.deferFullReadback) {
        VkBufferCopy colorCopy{};
        colorCopy.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdCopyBuffer(commandBuffer_, deviceOutput_.buffer, outputReadback_.buffer, 1u, &colorCopy);
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
    result.compactStatisticsReductionMs = elapsedMs(reduceStart);
    result.success = request.deferFullReadback ||
            result.outputRgb.size() == static_cast<std::size_t>(pixelCount) * 3u;
    result.fullReadbackDeferred = request.deferFullReadback;
    result.cloudCorrectionApplied = result.success && cloudMapContractValid;
    // deviceOutput_ now contains post-CCM RGB, not the original demosaic generation. Prevent
    // accidental reuse/reapplication of WB+CCM under the same demosaic token, but publish a new
    // opaque color generation when the next Vulkan stage owns the full-resolution buffer.
    residentDemosaicValid_ = false;
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
                    ? "GPU_PRIMARY_AWB_CCM_RESIDENT_OUTPUT"
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
