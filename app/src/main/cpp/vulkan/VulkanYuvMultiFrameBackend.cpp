#include "VulkanYuvMultiFrameBackend.h"
#include "VulkanPipelineCacheRegistry.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif

#ifndef BNCAM_YUV_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE
#define BNCAM_YUV_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE 0
#endif
#ifndef BNCAM_YUV_MULTIFRAME_FUSION_SHADER_AVAILABLE
#define BNCAM_YUV_MULTIFRAME_FUSION_SHADER_AVAILABLE 0
#endif
#if BNCAM_YUV_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE
#include "YuvMultiFrameAlignmentSpirv.h"
#endif
#if BNCAM_YUV_MULTIFRAME_FUSION_SHADER_AVAILABLE
#include "YuvMultiFrameFusionSpirv.h"
#endif

#include <algorithm>
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

std::uint64_t align4(std::uint64_t value) {
    return (value + 3u) & ~std::uint64_t{3u};
}

struct AlignmentPush {
    std::uint32_t width;
    std::uint32_t height;
    std::int32_t minDx;
    std::int32_t minDy;
    std::uint32_t candidateCols;
    std::uint32_t candidateRows;
    std::uint32_t sampleStep;
    std::uint32_t border;
};
static_assert(sizeof(AlignmentPush) == 32u);

struct FusionPush {
    std::uint32_t width;
    std::uint32_t height;
    float dx;
    float dy;
    std::uint32_t mode;
    std::uint32_t frameCount;
    float exposureScaleToAnchor;
    std::uint32_t computationalHdr;
};
static_assert(sizeof(FusionPush) == 32u);

template <typename PipelineT>
bool createStoragePipeline(
        VkDevice device,
        const std::vector<std::uint32_t>& spirv,
        std::uint32_t bindingCount,
        std::uint32_t pushBytes,
        const char* label,
        PipelineT& out,
        std::string& failure) {
    if (device == VK_NULL_HANDLE || spirv.empty() || bindingCount == 0u) {
        failure = std::string(label) + "_SHADER_INVALID";
        return false;
    }
    std::vector<VkDescriptorSetLayoutBinding> bindings(bindingCount);
    for (std::uint32_t i = 0u; i < bindingCount; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo setInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    setInfo.bindingCount = bindingCount;
    setInfo.pBindings = bindings.data();
    if (vkCreateDescriptorSetLayout(device, &setInfo, nullptr, &out.descriptorSetLayout) != VK_SUCCESS) {
        failure = std::string(label) + "_DESCRIPTOR_LAYOUT_FAILED";
        return false;
    }

    VkPushConstantRange push{};
    push.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    push.size = pushBytes;
    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount = 1u;
    layoutInfo.pSetLayouts = &out.descriptorSetLayout;
    layoutInfo.pushConstantRangeCount = 1u;
    layoutInfo.pPushConstantRanges = &push;
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr, &out.pipelineLayout) != VK_SUCCESS) {
        failure = std::string(label) + "_PIPELINE_LAYOUT_FAILED";
        return false;
    }

    VkShaderModuleCreateInfo moduleInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    moduleInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    moduleInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &moduleInfo, nullptr, &out.shaderModule) != VK_SUCCESS) {
        failure = std::string(label) + "_SHADER_MODULE_FAILED";
        return false;
    }
    VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = out.shaderModule;
    stage.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pipelineInfo.stage = stage;
    pipelineInfo.layout = out.pipelineLayout;
    if (VulkanPipelineCacheRegistry::createComputePipelines(device, 1u, &pipelineInfo, nullptr, &out.pipeline) != VK_SUCCESS) {
        failure = std::string(label) + "_PIPELINE_FAILED";
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = bindingCount;
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 1u;
    poolInfo.poolSizeCount = 1u;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &out.descriptorPool) != VK_SUCCESS) {
        failure = std::string(label) + "_DESCRIPTOR_POOL_FAILED";
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocInfo.descriptorPool = out.descriptorPool;
    allocInfo.descriptorSetCount = 1u;
    allocInfo.pSetLayouts = &out.descriptorSetLayout;
    if (vkAllocateDescriptorSets(device, &allocInfo, &out.descriptorSet) != VK_SUCCESS) {
        failure = std::string(label) + "_DESCRIPTOR_SET_FAILED";
        return false;
    }
    return true;
}

template <typename PipelineT>
void bindBuffers(VkDevice device, const PipelineT& pipeline, const std::vector<VkDescriptorBufferInfo>& infos) {
    std::vector<VkWriteDescriptorSet> writes(infos.size());
    for (std::uint32_t i = 0u; i < infos.size(); ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = pipeline.descriptorSet;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, static_cast<std::uint32_t>(writes.size()), writes.data(), 0u, nullptr);
}

bool copyLumaToMapped(
        AHardwareBuffer* buffer,
        std::uint8_t* destination,
        std::uint32_t expectedWidth,
        std::uint32_t expectedHeight,
        std::uint32_t& rowStride,
        std::uint32_t& pixelStride,
        std::string& failure) noexcept {
    if (buffer == nullptr || destination == nullptr) {
        failure = "YUV_MULTIFRAME_INPUT_NULL";
        return false;
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    if (desc.width != expectedWidth || desc.height != expectedHeight) {
        failure = "YUV_MULTIFRAME_DIMENSION_MISMATCH";
        return false;
    }
    AHardwareBuffer_Planes planes{};
    const int lockStatus = AHardwareBuffer_lockPlanes(
            buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &planes);
    if (lockStatus != 0 || planes.planeCount < 1u || planes.planes[0].data == nullptr) {
        failure = "YUV_MULTIFRAME_LUMA_LOCK_FAILED_" + std::to_string(lockStatus);
        return false;
    }
    const auto& plane = planes.planes[0];
    rowStride = plane.rowStride;
    pixelStride = plane.pixelStride;
    const auto* source = static_cast<const std::uint8_t*>(plane.data);
    for (std::uint32_t y = 0u; y < expectedHeight; ++y) {
        const std::uint8_t* sourceRow = source + static_cast<std::size_t>(y) * plane.rowStride;
        std::uint8_t* destinationRow = destination + static_cast<std::size_t>(y) * expectedWidth;
        if (plane.pixelStride == 1u) {
            std::memcpy(destinationRow, sourceRow, expectedWidth);
        } else if (plane.pixelStride > 1u) {
            for (std::uint32_t x = 0u; x < expectedWidth; ++x) {
                destinationRow[x] = sourceRow[static_cast<std::size_t>(x) * plane.pixelStride];
            }
        } else {
            AHardwareBuffer_unlock(buffer, nullptr);
            failure = "YUV_MULTIFRAME_LUMA_PIXEL_STRIDE_INVALID";
            return false;
        }
    }
    AHardwareBuffer_unlock(buffer, nullptr);
    return true;
}

float parabolicOffset(float negative, float center, float positive) {
    const float denominator = negative - 2.0f * center + positive;
    if (!std::isfinite(denominator) || std::abs(denominator) < 1.0e-6f) return 0.0f;
    return std::clamp(0.5f * (negative - positive) / denominator, -0.5f, 0.5f);
}

} // namespace

bool VulkanYuvMultiFrameBackend::ensureBufferLocked(
        VmaAllocator allocator,
        std::uint64_t bytes,
        bool hostVisible,
        PersistentBuffer& target,
        std::string& failure) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)hostVisible; (void)target;
    failure = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u) {
        failure = "YUV_MULTIFRAME_BUFFER_REQUEST_INVALID";
        return false;
    }
    if (target.buffer != VK_NULL_HANDLE && target.allocation != nullptr &&
        target.capacityBytes >= bytes && (!hostVisible || target.mapped != nullptr)) {
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
    allocationInfo.usage = hostVisible ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    if (hostVisible) {
        allocationInfo.flags = VMA_ALLOCATION_CREATE_MAPPED_BIT |
                               VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    }
    VmaAllocationInfo info{};
    const VkResult result = vmaCreateBuffer(
            allocator, &bufferInfo, &allocationInfo,
            &target.buffer, &target.allocation, &info);
    if (result != VK_SUCCESS || target.buffer == VK_NULL_HANDLE || target.allocation == nullptr ||
        (hostVisible && info.pMappedData == nullptr)) {
        target = {};
        failure = "YUV_MULTIFRAME_VMA_BUFFER_FAILED_" + std::to_string(result);
        return false;
    }
    target.mapped = info.pMappedData;
    target.capacityBytes = bytes;
    return true;
#endif
}

void VulkanYuvMultiFrameBackend::destroyBufferLocked(PersistentBuffer& buffer) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr && buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, buffer.buffer, buffer.allocation);
    }
#endif
    buffer = {};
}

void VulkanYuvMultiFrameBackend::destroyPipelineLocked(VkDevice device, PipelineBundle& pipeline) noexcept {
    if (device != VK_NULL_HANDLE) {
        if (pipeline.descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, pipeline.descriptorPool, nullptr);
        if (pipeline.pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline.pipeline, nullptr);
        if (pipeline.shaderModule != VK_NULL_HANDLE) vkDestroyShaderModule(device, pipeline.shaderModule, nullptr);
        if (pipeline.pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipeline.pipelineLayout, nullptr);
        if (pipeline.descriptorSetLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, pipeline.descriptorSetLayout, nullptr);
    }
    pipeline = {};
}
bool VulkanYuvMultiFrameBackend::ensureSubmissionResourcesLocked(
        VkDevice device, VkCommandPool commandPool, bool& created,
        std::string& failure) noexcept {
    created = false;
    if (submissionResourcesUnsafe_) {
        failure = "YUV_MULTIFRAME_SUBMISSION_RESOURCES_QUARANTINED";
        return false;
    }
    if (reusableCommandBuffer_ != VK_NULL_HANDLE && reusableFence_ != VK_NULL_HANDLE &&
        submissionCommandPool_ == commandPool) {
        return true;
    }
    if (reusableCommandBuffer_ != VK_NULL_HANDLE || reusableFence_ != VK_NULL_HANDLE) {
        destroySubmissionResourcesLocked(device);
    }
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = commandPool;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = 1u;
    const VkResult alloc = vkAllocateCommandBuffers(device, &ai, &reusableCommandBuffer_);
    if (alloc != VK_SUCCESS) {
        reusableCommandBuffer_ = VK_NULL_HANDLE;
        failure = "YUV_MULTIFRAME_COMMAND_ALLOCATE_FAILED_" + std::to_string(alloc);
        return false;
    }
    submissionCommandPool_ = commandPool;
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    const VkResult fenceResult = vkCreateFence(device, &fi, nullptr, &reusableFence_);
    if (fenceResult != VK_SUCCESS) {
        vkFreeCommandBuffers(device, submissionCommandPool_, 1u, &reusableCommandBuffer_);
        reusableCommandBuffer_ = VK_NULL_HANDLE;
        submissionCommandPool_ = VK_NULL_HANDLE;
        failure = "YUV_MULTIFRAME_FENCE_CREATE_FAILED_" + std::to_string(fenceResult);
        return false;
    }
    created = true;
    return true;
}

void VulkanYuvMultiFrameBackend::destroySubmissionResourcesLocked(VkDevice device) noexcept {
    if (device != VK_NULL_HANDLE) {
        if (reusableFence_ != VK_NULL_HANDLE) vkDestroyFence(device, reusableFence_, nullptr);
        if (reusableCommandBuffer_ != VK_NULL_HANDLE && submissionCommandPool_ != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(device, submissionCommandPool_, 1u, &reusableCommandBuffer_);
        }
    }
    reusableFence_ = VK_NULL_HANDLE;
    reusableCommandBuffer_ = VK_NULL_HANDLE;
    submissionCommandPool_ = VK_NULL_HANDLE;
    submissionResourcesUnsafe_ = false;
}

void VulkanYuvMultiFrameBackend::destroyLocked(VkDevice device) noexcept {
    destroySubmissionResourcesLocked(device);
    destroyBufferLocked(staging_);
    destroyBufferLocked(anchor_);
    destroyBufferLocked(support_);
    destroyBufferLocked(scores_);
    destroyBufferLocked(scoreReadback_);
    destroyBufferLocked(accumulator_);
    destroyBufferLocked(weight_);
    destroyBufferLocked(fusedLuma_);
    destroyBufferLocked(lumaReadback_);
    destroyPipelineLocked(device, alignmentPipeline_);
    destroyPipelineLocked(device, fusionPipeline_);
    allocator_ = nullptr;
    initializedDevice_ = VK_NULL_HANDLE;
    initialized_ = false;
    residentOutputGeneration_ = 0u;
    residentOutputBytes_ = 0u;
    residentOutputWidth_ = 0u;
    residentOutputHeight_ = 0u;
    residentHdrGeneration_ = 0u;
    residentHdrBytes_ = 0u;
    residentHdrWidth_ = 0u;
    residentHdrHeight_ = 0u;
}

void VulkanYuvMultiFrameBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanYuvMultiFrameBackend::resolveResidentOutput(
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
        fusedLuma_.buffer == VK_NULL_HANDLE || residentOutputBytes_ == 0u ||
        residentOutputWidth_ == 0u || residentOutputHeight_ == 0u) {
        return false;
    }
    buffer = fusedLuma_.buffer;
    bytes = residentOutputBytes_;
    width = residentOutputWidth_;
    height = residentOutputHeight_;
    return true;
}

bool VulkanYuvMultiFrameBackend::resolveResidentHdrAuthority(
        std::uint64_t generation,
        VkBuffer& accumulatorBuffer,
        VkBuffer& weightBuffer,
        std::uint64_t& bytes,
        std::uint32_t& width,
        std::uint32_t& height) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    accumulatorBuffer = VK_NULL_HANDLE;
    weightBuffer = VK_NULL_HANDLE;
    bytes = 0u;
    width = 0u;
    height = 0u;
    if (generation == 0u || generation != residentHdrGeneration_ || residentHdrBytes_ == 0u ||
        accumulator_.buffer == VK_NULL_HANDLE || weight_.buffer == VK_NULL_HANDLE) {
        return false;
    }
    accumulatorBuffer = accumulator_.buffer;
    weightBuffer = weight_.buffer;
    bytes = residentHdrBytes_;
    width = residentHdrWidth_;
    height = residentHdrHeight_;
    return true;
}

bool VulkanYuvMultiFrameBackend::initializeLocked(VkDevice device, std::string& failureReason) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device) return true;
        failureReason = "YUV_MULTIFRAME_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_YUV_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE || !BNCAM_YUV_MULTIFRAME_FUSION_SHADER_AVAILABLE
    (void)device;
    failureReason = "YUV_MULTIFRAME_SHADER_NOT_COMPILED";
    return false;
#else
    if (!createStoragePipeline(device, getYuvMultiFrameAlignmentSpirv(), 3u, sizeof(AlignmentPush),
                               "YUV_MULTIFRAME_ALIGNMENT", alignmentPipeline_, failureReason) ||
        !createStoragePipeline(device, getYuvMultiFrameFusionSpirv(), 5u, sizeof(FusionPush),
                               "YUV_MULTIFRAME_FUSION", fusionPipeline_, failureReason)) {
        destroyLocked(device);
        return false;
    }
    initializedDevice_ = device;
    initialized_ = true;
    return true;
#endif
}

YuvMultiFrameAlignmentResult VulkanYuvMultiFrameBackend::align(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const YuvMultiFrameAlignmentRequest& request) noexcept {
    YuvMultiFrameAlignmentResult out{};
    out.attempted = true;
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)physicalDevice; (void)device; (void)computeQueue; (void)commandPool; (void)allocatorOwner; (void)request;
    out.failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return out;
#else
    (void)physicalDevice;
    if (device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE ||
        request.frames.size() < 2u || request.frames.front() == nullptr) {
        out.failureReason = "YUV_MULTIFRAME_ALIGNMENT_REQUEST_INVALID";
        return out;
    }
    AHardwareBuffer_Desc anchorDesc{};
    AHardwareBuffer_describe(request.frames.front(), &anchorDesc);
    if (anchorDesc.width < 32u || anchorDesc.height < 32u) {
        out.failureReason = "YUV_MULTIFRAME_ALIGNMENT_DIMENSIONS_INVALID";
        return out;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    residentOutputGeneration_ = 0u;
    residentOutputBytes_ = 0u;
    residentOutputWidth_ = 0u;
    residentOutputHeight_ = 0u;
    residentHdrGeneration_ = 0u;
    residentHdrBytes_ = 0u;
    residentHdrWidth_ = 0u;
    residentHdrHeight_ = 0u;
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr) {
        out.failureReason = "YUV_MULTIFRAME_VMA_ALLOCATOR_UNAVAILABLE";
        return out;
    }
    if (!initializeLocked(device, out.failureReason)) return out;

    out.width = anchorDesc.width;
    out.height = anchorDesc.height;
    const std::uint64_t lumaBytes = static_cast<std::uint64_t>(out.width) * out.height;
    const std::uint64_t paddedLumaBytes = align4(lumaBytes);
    const std::uint64_t accumulatorBytes = lumaBytes * sizeof(float);
    const std::uint32_t maxShift = std::clamp(request.maxShiftPixels, 1u, 32u);
    const std::uint32_t candidateCols = 2u * maxShift + 1u;
    const std::uint32_t candidateRows = candidateCols;
    const std::uint32_t candidateCount = candidateCols * candidateRows;
    const std::uint64_t scoreBytes = static_cast<std::uint64_t>(candidateCount) * sizeof(float);

    if (!ensureBufferLocked(allocator_, paddedLumaBytes, true, staging_, out.failureReason) ||
        !ensureBufferLocked(allocator_, paddedLumaBytes, false, anchor_, out.failureReason) ||
        !ensureBufferLocked(allocator_, paddedLumaBytes, false, support_, out.failureReason) ||
        !ensureBufferLocked(allocator_, scoreBytes, false, scores_, out.failureReason) ||
        !ensureBufferLocked(allocator_, scoreBytes, true, scoreReadback_, out.failureReason)) {
        return out;
    }
    if (request.fuseLuma) {
        if (request.deferFullFrameReadback && request.generationId == 0u) {
            out.failureReason = "YUV_MULTIFRAME_RESIDENT_GENERATION_INVALID";
            return out;
        }
        if (!ensureBufferLocked(allocator_, accumulatorBytes, false, accumulator_, out.failureReason) ||
            !ensureBufferLocked(allocator_, accumulatorBytes, false, weight_, out.failureReason) ||
            !ensureBufferLocked(allocator_, paddedLumaBytes, false, fusedLuma_, out.failureReason) ||
            (!request.deferFullFrameReadback &&
             !ensureBufferLocked(allocator_, paddedLumaBytes, true, lumaReadback_, out.failureReason))) {
            return out;
        }
    }

    bool submissionResourcesCreated = false;
    if (!ensureSubmissionResourcesLocked(device, commandPool, submissionResourcesCreated, out.failureReason)) return out;
    out.commandBufferAllocations = submissionResourcesCreated ? 1u : 0u;
    out.fenceCreations = submissionResourcesCreated ? 1u : 0u;
    out.reusedSubmissionResources = !submissionResourcesCreated;
    const std::vector<VkDescriptorBufferInfo> alignmentDescriptorInfos{
            {anchor_.buffer, 0u, paddedLumaBytes},
            {support_.buffer, 0u, paddedLumaBytes},
            {scores_.buffer, 0u, scoreBytes}};
    bindBuffers(device, alignmentPipeline_, alignmentDescriptorInfos);
    ++out.descriptorSetUpdates;
    out.descriptorWrites += static_cast<std::uint32_t>(alignmentDescriptorInfos.size());
    if (request.fuseLuma) {
        const std::vector<VkDescriptorBufferInfo> fusionDescriptorInfos{
                {anchor_.buffer, 0u, paddedLumaBytes},
                {support_.buffer, 0u, paddedLumaBytes},
                {accumulator_.buffer, 0u, accumulatorBytes},
                {fusedLuma_.buffer, 0u, paddedLumaBytes},
                {weight_.buffer, 0u, accumulatorBytes}};
        bindBuffers(device, fusionPipeline_, fusionDescriptorInfos);
        ++out.descriptorSetUpdates;
        out.descriptorWrites += static_cast<std::uint32_t>(fusionDescriptorInfos.size());
    }
    auto allocateCommand = [&](VkCommandBuffer& command) -> bool {
        if (submissionResourcesUnsafe_ || reusableCommandBuffer_ == VK_NULL_HANDLE) return false;
        command = reusableCommandBuffer_;
        return true;
    };
    auto beginCommand = [&](VkCommandBuffer command, const char* stage) -> bool {
        const VkResult reset = vkResetCommandBuffer(command, 0u);
        if (reset != VK_SUCCESS) {
            out.failureReason = std::string(stage) + "_COMMAND_RESET_FAILED_" + std::to_string(reset);
            return false;
        }
        ++out.commandBufferResets;
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(command, &begin) != VK_SUCCESS) {
            out.failureReason = std::string(stage) + "_COMMAND_BEGIN_FAILED";
            return false;
        }
        return true;
    };
    auto submitAndWait = [&](VkCommandBuffer command, const char* stage) -> bool {
        if (submissionResourcesUnsafe_ || reusableFence_ == VK_NULL_HANDLE || command != reusableCommandBuffer_) {
            out.failureReason = std::string(stage) + "_SUBMISSION_RESOURCES_INVALID";
            return false;
        }
        const VkResult resetFence = vkResetFences(device, 1u, &reusableFence_);
        if (resetFence != VK_SUCCESS) {
            out.failureReason = std::string(stage) + "_FENCE_RESET_FAILED_" + std::to_string(resetFence);
            return false;
        }
        ++out.fenceResets;
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submit.commandBufferCount = 1u;
        submit.pCommandBuffers = &command;
        if (vkQueueSubmit(computeQueue, 1u, &submit, reusableFence_) != VK_SUCCESS) {
            out.failureReason = std::string(stage) + "_QUEUE_SUBMIT_FAILED";
            return false;
        }
        ++out.queueSubmissions;
        const auto start = Clock::now();
        const VkResult wait = vkWaitForFences(device, 1u, &reusableFence_, VK_TRUE, 1'500'000'000ull);
        out.gpuSynchronizationMs += elapsedMs(start);
        if (wait != VK_SUCCESS) {
            submissionResourcesUnsafe_ = true;
            out.submissionMayRemainInFlight = true;
            out.failureReason = std::string(stage) + (wait == VK_TIMEOUT ? "_FENCE_TIMEOUT" : "_FENCE_WAIT_FAILED");
            return false;
        }
        return true;
    };
    auto uploadStagingTo = [&](VkBuffer destination, const char* stage) -> bool {
        vmaFlushAllocation(allocator_, staging_.allocation, 0u, paddedLumaBytes);
        VkCommandBuffer command = VK_NULL_HANDLE;
        if (!allocateCommand(command)) {
            out.failureReason = std::string(stage) + "_COMMAND_ALLOC_FAILED";
            return false;
        }
        if (!beginCommand(command, stage)) return false;
        VkBufferCopy copy{0u, 0u, paddedLumaBytes};
        vkCmdCopyBuffer(command, staging_.buffer, destination, 1u, &copy);
        VkBufferMemoryBarrier barrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = destination;
        barrier.size = paddedLumaBytes;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0u, 0u, nullptr, 1u, &barrier, 0u, nullptr);
        if (vkEndCommandBuffer(command) != VK_SUCCESS) {

            out.failureReason = std::string(stage) + "_COMMAND_END_FAILED";
            return false;
        }
        const bool ok = submitAndWait(command, stage);
        return ok;
    };
    auto dispatchFusion = [&](std::uint32_t mode, float dx, float dy, std::uint32_t frameCount,
                              float exposureScale, bool readback, const char* stage) -> bool {
        FusionPush push{out.width, out.height, dx, dy, mode, frameCount,
                        std::clamp(exposureScale, 0.0625f, 16.0f), request.computationalHdr ? 1u : 0u};
        VkCommandBuffer command = VK_NULL_HANDLE;
        if (!allocateCommand(command)) { out.failureReason = std::string(stage) + "_COMMAND_ALLOC_FAILED"; return false; }
        if (!beginCommand(command, stage)) return false;
        VkBufferMemoryBarrier accumBarrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        accumBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        accumBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        accumBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        accumBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        accumBarrier.buffer = accumulator_.buffer;
        accumBarrier.size = accumulatorBytes;
        if (mode != 0u) {
            VkBufferMemoryBarrier weightBarrier = accumBarrier;
            weightBarrier.buffer = weight_.buffer;
            VkBufferMemoryBarrier fusionBarriers[2]{accumBarrier, weightBarrier};
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 0u, 0u, nullptr, 2u, fusionBarriers, 0u, nullptr);
        }
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, fusionPipeline_.pipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, fusionPipeline_.pipelineLayout,
                                0u, 1u, &fusionPipeline_.descriptorSet, 0u, nullptr);
        vkCmdPushConstants(command, fusionPipeline_.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        const std::uint32_t pixelOrWordCount = mode == 2u
                ? static_cast<std::uint32_t>((lumaBytes + 3u) / 4u)
                : static_cast<std::uint32_t>(lumaBytes);
        vkCmdDispatch(command, (pixelOrWordCount + 63u) / 64u, 1u, 1u);
        if (readback) {
            VkBufferMemoryBarrier outputBarrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
            outputBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            outputBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            outputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            outputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            outputBarrier.buffer = fusedLuma_.buffer;
            outputBarrier.size = paddedLumaBytes;
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                 0u, 0u, nullptr, 1u, &outputBarrier, 0u, nullptr);
            VkBufferCopy copy{0u, 0u, paddedLumaBytes};
            vkCmdCopyBuffer(command, fusedLuma_.buffer, lumaReadback_.buffer, 1u, &copy);
            VkBufferMemoryBarrier hostBarrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
            hostBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            hostBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            hostBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hostBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hostBarrier.buffer = lumaReadback_.buffer;
            hostBarrier.size = paddedLumaBytes;
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                                 0u, 0u, nullptr, 1u, &hostBarrier, 0u, nullptr);
        }
        if (vkEndCommandBuffer(command) != VK_SUCCESS) {

            out.failureReason = std::string(stage) + "_COMMAND_END_FAILED";
            return false;
        }
        const auto start = Clock::now();
        const bool ok = submitAndWait(command, stage);
        out.gpuFusionMs += elapsedMs(start);
        return ok;
    };

    std::uint32_t anchorRowStride = 0u;
    std::uint32_t anchorPixelStride = 0u;
    const auto anchorCopyStart = Clock::now();
    std::memset(staging_.mapped, 0, static_cast<std::size_t>(paddedLumaBytes));
    if (!copyLumaToMapped(request.frames.front(), static_cast<std::uint8_t*>(staging_.mapped),
                          out.width, out.height, anchorRowStride, anchorPixelStride, out.failureReason)) return out;
    out.inputCopyMs += elapsedMs(anchorCopyStart);
    out.anchorRowStrideBytes = anchorRowStride;
    out.anchorPixelStrideBytes = anchorPixelStride;
    out.fullFrameCpuUploadBytes += lumaBytes;
    if (!uploadStagingTo(anchor_.buffer, "YUV_MULTIFRAME_ANCHOR_UPLOAD")) return out;
    if (request.fuseLuma && !dispatchFusion(0u, 0.0f, 0.0f, 1u, 1.0f, false, "YUV_MULTIFRAME_FUSION_INIT")) return out;

    const AlignmentPush alignmentPush{
            out.width, out.height,
            -static_cast<std::int32_t>(maxShift), -static_cast<std::int32_t>(maxShift),
            candidateCols, candidateRows,
            std::clamp(request.sampleStep, 1u, 16u), maxShift + 2u};
    out.shifts.reserve(request.frames.size() - 1u);

    for (std::size_t frameIndex = 1u; frameIndex < request.frames.size(); ++frameIndex) {
        YuvAlignmentShift shift{};
        std::uint32_t supportRowStride = 0u;
        std::uint32_t supportPixelStride = 0u;
        const auto copyStart = Clock::now();
        std::memset(staging_.mapped, 0, static_cast<std::size_t>(paddedLumaBytes));
        if (!copyLumaToMapped(request.frames[frameIndex], static_cast<std::uint8_t*>(staging_.mapped),
                              out.width, out.height, supportRowStride, supportPixelStride, shift.rejectReason)) {
            ++out.supportRejected;
            out.shifts.push_back(shift);
            continue;
        }
        out.inputCopyMs += elapsedMs(copyStart);
        out.fullFrameCpuUploadBytes += lumaBytes;
        vmaFlushAllocation(allocator_, staging_.allocation, 0u, paddedLumaBytes);
        VkCommandBuffer command = VK_NULL_HANDLE;
        if (!allocateCommand(command)) { out.failureReason = "YUV_MULTIFRAME_ALIGNMENT_COMMAND_ALLOC_FAILED"; return out; }
        if (!beginCommand(command, "YUV_MULTIFRAME_ALIGNMENT")) return out;
        // Phase 9: support upload and alignment are one GPU submission. The transfer->compute
        // barrier replaces the former host-side fence wait without weakening visibility.
        VkBufferCopy supportCopy{0u, 0u, paddedLumaBytes};
        vkCmdCopyBuffer(command, staging_.buffer, support_.buffer, 1u, &supportCopy);
        VkBufferMemoryBarrier supportReady{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        supportReady.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        supportReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        supportReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        supportReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        supportReady.buffer = support_.buffer;
        supportReady.size = paddedLumaBytes;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0u, 0u, nullptr, 1u, &supportReady, 0u, nullptr);
        ++out.combinedSupportUploadAlignmentSubmissions;
        vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, alignmentPipeline_.pipeline);
        vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, alignmentPipeline_.pipelineLayout,
                                0u, 1u, &alignmentPipeline_.descriptorSet, 0u, nullptr);
        vkCmdPushConstants(command, alignmentPipeline_.pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(alignmentPush), &alignmentPush);
        vkCmdDispatch(command, candidateCount, 1u, 1u);
        VkBufferMemoryBarrier scoreBarrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        scoreBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        scoreBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        scoreBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        scoreBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        scoreBarrier.buffer = scores_.buffer;
        scoreBarrier.size = scoreBytes;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0u, 0u, nullptr, 1u, &scoreBarrier, 0u, nullptr);
        VkBufferCopy scoreCopy{0u, 0u, scoreBytes};
        vkCmdCopyBuffer(command, scores_.buffer, scoreReadback_.buffer, 1u, &scoreCopy);
        VkBufferMemoryBarrier hostBarrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        hostBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        hostBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        hostBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostBarrier.buffer = scoreReadback_.buffer;
        hostBarrier.size = scoreBytes;
        vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                             0u, 0u, nullptr, 1u, &hostBarrier, 0u, nullptr);
        if (vkEndCommandBuffer(command) != VK_SUCCESS) {

            out.failureReason = "YUV_MULTIFRAME_ALIGNMENT_COMMAND_END_FAILED";
            return out;
        }
        const auto gpuStart = Clock::now();
        const bool aligned = submitAndWait(command, "YUV_MULTIFRAME_ALIGNMENT");
        out.gpuAlignmentMs += elapsedMs(gpuStart);
        if (!aligned) return out;
        vmaInvalidateAllocation(allocator_, scoreReadback_.allocation, 0u, scoreBytes);
        out.compactGpuReadbackBytes += scoreBytes;

        const auto* scoreValues = static_cast<const float*>(scoreReadback_.mapped);
        std::uint32_t bestIndex = 0u;
        float bestScore = -std::numeric_limits<float>::infinity();
        for (std::uint32_t i = 0u; i < candidateCount; ++i) {
            if (std::isfinite(scoreValues[i]) && scoreValues[i] > bestScore) {
                bestScore = scoreValues[i];
                bestIndex = i;
            }
        }
        const std::uint32_t bestCol = bestIndex % candidateCols;
        const std::uint32_t bestRow = bestIndex / candidateCols;
        float subX = 0.0f;
        float subY = 0.0f;
        if (bestCol > 0u && bestCol + 1u < candidateCols) {
            subX = parabolicOffset(scoreValues[bestIndex - 1u], bestScore, scoreValues[bestIndex + 1u]);
        }
        if (bestRow > 0u && bestRow + 1u < candidateRows) {
            subY = parabolicOffset(scoreValues[bestIndex - candidateCols], bestScore,
                                   scoreValues[bestIndex + candidateCols]);
        }
        shift.score = bestScore;
        shift.dx = -static_cast<float>(maxShift) + static_cast<float>(bestCol) + subX;
        shift.dy = -static_cast<float>(maxShift) + static_cast<float>(bestRow) + subY;
        shift.accepted = std::isfinite(bestScore) && bestScore >= request.minimumCorrelation &&
                         std::abs(shift.dx) <= static_cast<float>(maxShift) + 0.51f &&
                         std::abs(shift.dy) <= static_cast<float>(maxShift) + 0.51f;
        shift.rejectReason = shift.accepted ? "none" : "YUV_ALIGNMENT_CORRELATION_REJECTED";
        if (shift.accepted) {
            const float supportExposureScale = request.computationalHdr && frameIndex < request.exposureScaleToAnchor.size()
                    ? std::clamp(request.exposureScaleToAnchor[frameIndex], 0.0625f, 16.0f) : 1.0f;
            if (request.fuseLuma &&
                !dispatchFusion(1u, shift.dx, shift.dy, 1u, supportExposureScale, false, "YUV_MULTIFRAME_FUSION_ACCUMULATE")) {
                return out;
            }
            ++out.supportAccepted;
        } else {
            ++out.supportRejected;
        }
        out.shifts.push_back(shift);
    }

    out.alignmentBackend = "VULKAN_YUV_NCC_COMPACT_SCOREGRID";
    if (request.fuseLuma) {
        const std::uint32_t frameCount = 1u + static_cast<std::uint32_t>(out.supportAccepted);
        const bool publicationReadback = !request.deferFullFrameReadback;
        if (!dispatchFusion(2u, 0.0f, 0.0f, frameCount, 1.0f, publicationReadback,
                            "YUV_MULTIFRAME_FUSION_FINALIZE")) return out;
        if (request.deferFullFrameReadback) {
            residentOutputGeneration_ = request.generationId;
            residentOutputBytes_ = lumaBytes;
            residentOutputWidth_ = out.width;
            residentOutputHeight_ = out.height;
            out.residentFusedLumaProduced = true;
            out.fullFrameReadbackDeferred = true;
            out.residentOutputGeneration = request.generationId;
            out.fullFrameGpuReadbackBytes = 0u;
            if (request.computationalHdr && out.supportAccepted > 0) {
                residentHdrGeneration_ = request.generationId;
                residentHdrBytes_ = accumulatorBytes;
                residentHdrWidth_ = out.width;
                residentHdrHeight_ = out.height;
            }
        } else {
            vmaInvalidateAllocation(allocator_, lumaReadback_.allocation, 0u, paddedLumaBytes);
            out.outputLuma.resize(static_cast<std::size_t>(lumaBytes));
            std::memcpy(out.outputLuma.data(), lumaReadback_.mapped, static_cast<std::size_t>(lumaBytes));
            out.fullFrameGpuReadbackBytes = lumaBytes;
        }
        out.fusionBackend = request.computationalHdr
                ? "VULKAN_YUV_HDR_BILINEAR_CONFIDENCE_LUMA_FUSION"
                : "VULKAN_YUV_STREAMING_BILINEAR_LUMA_FUSION";
    } else {
        out.fusionBackend = "NOT_REQUESTED";
    }

    out.success = true;
    out.failureReason = "none";
    return out;
#endif
}

} // namespace bncam::vulkan
