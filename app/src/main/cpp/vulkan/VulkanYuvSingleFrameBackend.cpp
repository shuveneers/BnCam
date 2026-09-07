#include "VulkanYuvSingleFrameBackend.h"
#include "VulkanPipelineCacheRegistry.h"
#include "../YuvSingleFrameResidualPolicy.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif

#ifndef BNCAM_YUV_SINGLE_FRAME_ISP_SHADER_AVAILABLE
#define BNCAM_YUV_SINGLE_FRAME_ISP_SHADER_AVAILABLE 0
#endif
#if BNCAM_YUV_SINGLE_FRAME_ISP_SHADER_AVAILABLE
#include "YuvSingleFrameIspSpirv.h"
#endif

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

namespace bncam::vulkan {
namespace {

using Clock = std::chrono::steady_clock;

float elapsedMs(const Clock::time_point& start) {
    return std::chrono::duration<float, std::milli>(Clock::now() - start).count();
}

std::uint64_t align4(std::uint64_t value) {
    return (value + 3u) & ~std::uint64_t{3u};
}

float sanitizeProfileCreativeCarrier(float value) {
    if (!std::isfinite(value)) return 0.0f;
    if (value <= -1.5f) {
        const float payload = -value - 2.0f;
        if (payload >= 0.0f && payload <= 8388607.0f &&
            std::abs(payload - std::round(payload)) <= 0.001f) return value;
        return 0.0f;
    }
    return std::clamp(value, -1.0f, 1.0f);
}

struct IspPush {
    std::uint32_t inputWidth;
    std::uint32_t inputHeight;
    std::uint32_t outputWidth;
    std::uint32_t outputHeight;
    std::uint32_t rotationDegrees;
    std::uint32_t outputPixelCount;
    float wbRed;
    float wbGreen;
    float wbBlue;
    float saturationControl;
    float contrastControl;
    float vibranceControl;
    float profileDetailAmount;
    float profileDetailRadius;
    float profileDetailDetail;
    float profileDetailMasking;
    std::uint32_t ultraHdrMode;
    std::uint32_t gainmapWidth;
    std::uint32_t gainmapHeight;
    std::uint32_t gainmapPixelCount;
    std::uint32_t portraitEnabled;
    std::uint32_t portraitMaskWidth;
    std::uint32_t portraitMaskHeight;
    std::uint32_t portraitMaskRotationDegrees;
    float portraitTargetLeft;
    float portraitTargetTop;
    float portraitTargetRight;
    float portraitTargetBottom;
    float profileNrLumaBlend;
    float profileNrLumaProtection;
    float profileNrChromaBlend;
    float profileNrChromaProtection;
};
static_assert(sizeof(IspPush) == 128u);

} // namespace

bool VulkanYuvSingleFrameBackend::ensureBufferLocked(
        VmaAllocator allocator,
        std::uint64_t bytes,
        bool hostVisible,
        PersistentBuffer& target,
        std::string& failureReason) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)hostVisible; (void)target;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u) {
        failureReason = "YUV_SINGLE_FRAME_BUFFER_REQUEST_INVALID";
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
    allocationInfo.usage = hostVisible
            ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST
            : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    if (hostVisible) {
        allocationInfo.flags = VMA_ALLOCATION_CREATE_MAPPED_BIT |
                               VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    }

    VmaAllocationInfo info{};
    const VkResult result = vmaCreateBuffer(
            allocator,
            &bufferInfo,
            &allocationInfo,
            &target.buffer,
            &target.allocation,
            &info);
    if (result != VK_SUCCESS || target.buffer == VK_NULL_HANDLE || target.allocation == nullptr ||
        (hostVisible && info.pMappedData == nullptr)) {
        target = {};
        failureReason = "YUV_SINGLE_FRAME_VMA_BUFFER_FAILED_" + std::to_string(result);
        return false;
    }
    target.mapped = info.pMappedData;
    target.capacityBytes = bytes;
    return true;
#endif
}

void VulkanYuvSingleFrameBackend::destroyBufferLocked(PersistentBuffer& buffer) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr && buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, buffer.buffer, buffer.allocation);
    }
#endif
    buffer = {};
}

void VulkanYuvSingleFrameBackend::destroyLocked(VkDevice device) noexcept {
    destroyBufferLocked(inputStaging_);
    destroyBufferLocked(inputDevice_);
    destroyBufferLocked(toneStaging_);
    destroyBufferLocked(toneDevice_);
    destroyBufferLocked(outputDevice_);
    destroyBufferLocked(outputReadback_);
    destroyBufferLocked(gainLogDevice_);
    destroyBufferLocked(gainmapDevice_);
    destroyBufferLocked(gainmapReadback_);
    destroyBufferLocked(ultraHdrTelemetry_);
    destroyBufferLocked(portraitMask_);

    if (device != VK_NULL_HANDLE) {
        if (descriptorPool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool_, nullptr);
        if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline_, nullptr);
        if (shaderModule_ != VK_NULL_HANDLE) vkDestroyShaderModule(device, shaderModule_, nullptr);
        if (pipelineLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout_, nullptr);
        if (descriptorSetLayout_ != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorSetLayout_, nullptr);
    }
    descriptorSetLayout_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    shaderModule_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    descriptorPool_ = VK_NULL_HANDLE;
    descriptorSet_ = VK_NULL_HANDLE;
    allocator_ = nullptr;
    initializedDevice_ = VK_NULL_HANDLE;
    initialized_ = false;
}

void VulkanYuvSingleFrameBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanYuvSingleFrameBackend::initializeLocked(
        VkDevice device,
        std::string& failureReason) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device) return true;
        failureReason = "YUV_SINGLE_FRAME_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_YUV_SINGLE_FRAME_ISP_SHADER_AVAILABLE
    (void)device;
    failureReason = "YUV_SINGLE_FRAME_SHADER_NOT_COMPILED";
    return false;
#else
    if (device == VK_NULL_HANDLE) {
        failureReason = "YUV_SINGLE_FRAME_DEVICE_INVALID";
        return false;
    }

    VkDescriptorSetLayoutBinding bindings[9]{};
    for (std::uint32_t i = 0u; i < 9u; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo setInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    setInfo.bindingCount = 9u;
    setInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device, &setInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "YUV_SINGLE_FRAME_DESCRIPTOR_LAYOUT_FAILED";
        destroyLocked(device);
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0u;
    pushRange.size = sizeof(IspPush);
    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount = 1u;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;
    layoutInfo.pushConstantRangeCount = 1u;
    layoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "YUV_SINGLE_FRAME_PIPELINE_LAYOUT_FAILED";
        destroyLocked(device);
        return false;
    }

    const std::vector<std::uint32_t>& spirv = getYuvSingleFrameIspSpirv();
    if (spirv.empty()) {
        failureReason = "YUV_SINGLE_FRAME_SHADER_EMPTY";
        destroyLocked(device);
        return false;
    }
    VkShaderModuleCreateInfo moduleInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    moduleInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    moduleInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &moduleInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "YUV_SINGLE_FRAME_SHADER_MODULE_FAILED";
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
        failureReason = "YUV_SINGLE_FRAME_PIPELINE_FAILED";
        destroyLocked(device);
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 9u;
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 1u;
    poolInfo.poolSizeCount = 1u;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        failureReason = "YUV_SINGLE_FRAME_DESCRIPTOR_POOL_FAILED";
        destroyLocked(device);
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1u;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "YUV_SINGLE_FRAME_DESCRIPTOR_SET_FAILED";
        destroyLocked(device);
        return false;
    }

    initializedDevice_ = device;
    initialized_ = true;
    return true;
#endif
}

YuvSingleFrameIspResult VulkanYuvSingleFrameBackend::execute(
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const YuvSingleFrameIspRequest& request) noexcept {
    YuvSingleFrameIspResult out{};
    out.attempted = true;
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)device; (void)computeQueue; (void)commandPool; (void)allocatorOwner; (void)request;
    out.failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return out;
#else
    if (device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE ||
        request.nv21 == nullptr || request.width < 2u || request.height < 2u ||
        (request.width & 1u) != 0u || (request.height & 1u) != 0u) {
        out.failureReason = "YUV_SINGLE_FRAME_REQUEST_INVALID";
        return out;
    }
    const std::uint64_t lumaBytes =
            static_cast<std::uint64_t>(request.width) * request.height;
    const std::uint64_t expectedNv21Bytes = lumaBytes * 3u / 2u;
    const bool useResidentLuma = request.residentLumaBuffer != VK_NULL_HANDLE;
    if (request.nv21Bytes < expectedNv21Bytes) {
        out.failureReason = "YUV_SINGLE_FRAME_NV21_LENGTH_INVALID";
        return out;
    }
    if (useResidentLuma && request.residentLumaBytes < lumaBytes) {
        out.failureReason = "YUV_SINGLE_FRAME_RESIDENT_LUMA_LENGTH_INVALID";
        return out;
    }
    const std::uint32_t rotation = request.rotationDegrees % 360u;
    if (rotation != 0u && rotation != 90u && rotation != 180u && rotation != 270u) {
        out.failureReason = "YUV_SINGLE_FRAME_ROTATION_INVALID";
        return out;
    }

    out.outputWidth = (rotation == 90u || rotation == 270u) ? request.height : request.width;
    out.outputHeight = (rotation == 90u || rotation == 270u) ? request.width : request.height;
    const std::uint64_t outputPixelCount = static_cast<std::uint64_t>(out.outputWidth) * out.outputHeight;
    // YUV420 requires even width and height, therefore the rotated output always contains a
    // multiple of four pixels. The shader can safely assign exactly one invocation to each
    // 4-pixel / 12-byte BGR24 block with no tail writer overlap.
    if ((outputPixelCount & 3u) != 0u) {
        out.failureReason = "YUV_SINGLE_FRAME_BGR24_BLOCK_ALIGNMENT_INVALID";
        return out;
    }
    const std::uint64_t bgrBytes = outputPixelCount * 3u;
    const std::uint64_t paddedInputBytes = align4(expectedNv21Bytes);
    const std::uint64_t paddedOutputBytes = align4(bgrBytes);
    constexpr std::uint64_t toneBytes = 256u * sizeof(std::uint32_t);
    constexpr std::uint64_t telemetryBytes =
            bncam::yuv_phase13::kTelemetryWordCount * sizeof(std::uint32_t);
    const bool useUltraHdr = request.ultraHdrGainmapRequested &&
            request.residentHdrAccumulatorBuffer != VK_NULL_HANDLE &&
            request.residentHdrWeightBuffer != VK_NULL_HANDLE &&
            request.residentHdrBytes >= lumaBytes * sizeof(float);
    out.ultraHdrStatus = request.ultraHdrGainmapRequested
            ? (useUltraHdr ? "GPU_HDR_AUTHORITY_READY" : "HDR_AUTHORITY_UNAVAILABLE")
            : "NOT_REQUESTED";
    const std::uint32_t portraitRotation = request.portraitMaskRotationDegrees % 360u;
    const bool portraitRotationSupported = portraitRotation == 0u || portraitRotation == 90u ||
            portraitRotation == 180u || portraitRotation == 270u;
    const bool portraitBoundsValid = std::isfinite(request.portraitTargetLeft) &&
            std::isfinite(request.portraitTargetTop) && std::isfinite(request.portraitTargetRight) &&
            std::isfinite(request.portraitTargetBottom) && request.portraitTargetLeft >= 0.0f &&
            request.portraitTargetTop >= 0.0f && request.portraitTargetRight <= 1.0f &&
            request.portraitTargetBottom <= 1.0f && request.portraitTargetRight > request.portraitTargetLeft &&
            request.portraitTargetBottom > request.portraitTargetTop;
    const std::uint64_t portraitMaskPixels = static_cast<std::uint64_t>(request.portraitMaskWidth) *
            request.portraitMaskHeight;
    const bool usePortrait = request.portraitEffectRequested && request.portraitMask != nullptr &&
            request.portraitMaskWidth > 1u && request.portraitMaskHeight > 1u && portraitBoundsValid &&
            portraitRotationSupported && request.portraitMaskFloatCount >= portraitMaskPixels;
    out.portraitEffectRequested = request.portraitEffectRequested;
    out.portraitStatus = request.portraitEffectRequested
            ? (usePortrait ? "GPU_MASK_READY" : "MASK_UNAVAILABLE_OR_INVALID")
            : "NOT_REQUESTED";
    const std::uint32_t gainmapWidth = useUltraHdr ? (out.outputWidth + 3u) / 4u : 0u;
    const std::uint32_t gainmapHeight = useUltraHdr ? (out.outputHeight + 3u) / 4u : 0u;
    const std::uint64_t gainmapPixelCount =
            static_cast<std::uint64_t>(gainmapWidth) * gainmapHeight;
    const std::uint32_t gainmapWordsPerRow = useUltraHdr ? (gainmapWidth + 3u) / 4u : 0u;
    const std::uint64_t gainLogBytes = align4(gainmapPixelCount * sizeof(float));
    const std::uint64_t gainmapPackedBytes = useUltraHdr
            ? static_cast<std::uint64_t>(gainmapWordsPerRow) * sizeof(std::uint32_t) * gainmapHeight
            : 0u;

    std::lock_guard<std::mutex> lock(mutex_);
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr) {
        out.failureReason = "YUV_SINGLE_FRAME_VMA_ALLOCATOR_UNAVAILABLE";
        return out;
    }
    if (!initializeLocked(device, out.failureReason)) return out;
    if (!ensureBufferLocked(allocator_, paddedInputBytes, true, inputStaging_, out.failureReason) ||
        !ensureBufferLocked(allocator_, paddedInputBytes, false, inputDevice_, out.failureReason) ||
        !ensureBufferLocked(allocator_, toneBytes, true, toneStaging_, out.failureReason) ||
        !ensureBufferLocked(allocator_, toneBytes, false, toneDevice_, out.failureReason) ||
        !ensureBufferLocked(allocator_, paddedOutputBytes, false, outputDevice_, out.failureReason) ||
        !ensureBufferLocked(allocator_, paddedOutputBytes, true, outputReadback_, out.failureReason) ||
        (useUltraHdr && !ensureBufferLocked(allocator_, gainLogBytes, false, gainLogDevice_, out.failureReason)) ||
        (useUltraHdr && !ensureBufferLocked(allocator_, gainmapPackedBytes, false, gainmapDevice_, out.failureReason)) ||
        (useUltraHdr && !ensureBufferLocked(allocator_, gainmapPackedBytes, true, gainmapReadback_, out.failureReason)) ||
        !ensureBufferLocked(allocator_, telemetryBytes, true, ultraHdrTelemetry_, out.failureReason) ||
        (usePortrait && !ensureBufferLocked(allocator_, portraitMaskPixels * sizeof(float), true, portraitMask_, out.failureReason))) {
        return out;
    }

    auto uploadStart = Clock::now();
    if (useResidentLuma) {
        const std::uint64_t chromaBytes = expectedNv21Bytes - lumaBytes;
        std::memcpy(
                static_cast<std::uint8_t*>(inputStaging_.mapped) + lumaBytes,
                request.nv21 + lumaBytes,
                static_cast<std::size_t>(chromaBytes));
        if (paddedInputBytes > expectedNv21Bytes) {
            std::memset(static_cast<std::uint8_t*>(inputStaging_.mapped) + expectedNv21Bytes,
                        0,
                        static_cast<std::size_t>(paddedInputBytes - expectedNv21Bytes));
        }
        out.fullFrameCpuUploadBytes = chromaBytes;
        out.residentLumaConsumed = true;
        out.residentLumaGeneration = request.residentLumaGeneration;
    } else {
        std::memcpy(inputStaging_.mapped, request.nv21, static_cast<std::size_t>(expectedNv21Bytes));
        if (paddedInputBytes > expectedNv21Bytes) {
            std::memset(static_cast<std::uint8_t*>(inputStaging_.mapped) + expectedNv21Bytes,
                        0,
                        static_cast<std::size_t>(paddedInputBytes - expectedNv21Bytes));
        }
        out.fullFrameCpuUploadBytes = expectedNv21Bytes;
    }
    std::memcpy(toneStaging_.mapped, request.toneLut.data(), static_cast<std::size_t>(toneBytes));
    if (usePortrait) {
        const auto portraitBytes = portraitMaskPixels * sizeof(float);
        std::memcpy(portraitMask_.mapped, request.portraitMask, static_cast<std::size_t>(portraitBytes));
        vmaFlushAllocation(allocator_, portraitMask_.allocation, 0u, static_cast<VkDeviceSize>(portraitBytes));
    }
    vmaFlushAllocation(allocator_, inputStaging_.allocation, 0u, paddedInputBytes);
    vmaFlushAllocation(allocator_, toneStaging_.allocation, 0u, toneBytes);
    out.inputUploadMs = elapsedMs(uploadStart);

    // FASE 13 residual-noise analysis uses the compact telemetry buffer on every YUV render.
    // Ultra HDR retains word 0; YUV analysis owns words 8..63.
    std::memset(ultraHdrTelemetry_.mapped, 0, static_cast<std::size_t>(telemetryBytes));
    vmaFlushAllocation(allocator_, ultraHdrTelemetry_.allocation, 0u, telemetryBytes);

    VkDescriptorBufferInfo descriptorBuffers[9]{};
    descriptorBuffers[0] = {inputDevice_.buffer, 0u, paddedInputBytes};
    descriptorBuffers[1] = {toneDevice_.buffer, 0u, toneBytes};
    descriptorBuffers[2] = {outputDevice_.buffer, 0u, paddedOutputBytes};
    descriptorBuffers[3] = {
            useUltraHdr ? request.residentHdrAccumulatorBuffer : inputDevice_.buffer,
            0u,
            useUltraHdr ? request.residentHdrBytes : paddedInputBytes};
    descriptorBuffers[4] = {
            useUltraHdr ? request.residentHdrWeightBuffer : inputDevice_.buffer,
            0u,
            useUltraHdr ? request.residentHdrBytes : paddedInputBytes};
    descriptorBuffers[5] = {
            useUltraHdr ? gainLogDevice_.buffer : outputDevice_.buffer,
            0u,
            useUltraHdr ? gainLogBytes : paddedOutputBytes};
    descriptorBuffers[6] = {
            useUltraHdr ? gainmapDevice_.buffer : outputDevice_.buffer,
            0u,
            useUltraHdr ? gainmapPackedBytes : paddedOutputBytes};
    descriptorBuffers[7] = {ultraHdrTelemetry_.buffer, 0u, telemetryBytes};
    descriptorBuffers[8] = {
            usePortrait ? portraitMask_.buffer : toneDevice_.buffer,
            0u,
            usePortrait ? portraitMaskPixels * sizeof(float) : toneBytes};
    VkWriteDescriptorSet writes[9]{};
    for (std::uint32_t i = 0u; i < 9u; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &descriptorBuffers[i];
    }
    vkUpdateDescriptorSets(device, 9u, writes, 0u, nullptr);

    VkCommandBuffer command = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo commandInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &commandInfo, &command) != VK_SUCCESS) {
        out.failureReason = "YUV_SINGLE_FRAME_COMMAND_ALLOC_FAILED";
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
        out.failureReason = "YUV_SINGLE_FRAME_COMMAND_BEGIN_FAILED";
        freeCommand();
        return out;
    }

    if (useResidentLuma) {
        VkBufferMemoryBarrier residentBarrier{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        residentBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        residentBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        residentBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        residentBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        residentBarrier.buffer = request.residentLumaBuffer;
        residentBarrier.offset = 0u;
        residentBarrier.size = static_cast<VkDeviceSize>(lumaBytes);
        vkCmdPipelineBarrier(
                command,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0u,
                0u, nullptr,
                1u, &residentBarrier,
                0u, nullptr);
        VkBufferCopy lumaCopy{0u, 0u, static_cast<VkDeviceSize>(lumaBytes)};
        vkCmdCopyBuffer(command, request.residentLumaBuffer, inputDevice_.buffer, 1u, &lumaCopy);
        VkBufferCopy chromaCopy{
                static_cast<VkDeviceSize>(lumaBytes),
                static_cast<VkDeviceSize>(lumaBytes),
                static_cast<VkDeviceSize>(expectedNv21Bytes - lumaBytes)};
        vkCmdCopyBuffer(command, inputStaging_.buffer, inputDevice_.buffer, 1u, &chromaCopy);
    } else {
        VkBufferCopy inputCopy{0u, 0u, paddedInputBytes};
        vkCmdCopyBuffer(command, inputStaging_.buffer, inputDevice_.buffer, 1u, &inputCopy);
    }
    VkBufferCopy toneCopy{0u, 0u, toneBytes};
    vkCmdCopyBuffer(command, toneStaging_.buffer, toneDevice_.buffer, 1u, &toneCopy);

    VkMemoryBarrier uploadBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    uploadBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    uploadBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            1u,
            &uploadBarrier,
            0u,
            nullptr,
            0u,
            nullptr);

    if (useUltraHdr) {
        VkBufferMemoryBarrier hdrReady[2]{};
        for (std::uint32_t i = 0u; i < 2u; ++i) {
            hdrReady[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            hdrReady[i].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            hdrReady[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            hdrReady[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hdrReady[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hdrReady[i].offset = 0u;
            hdrReady[i].size = static_cast<VkDeviceSize>(request.residentHdrBytes);
        }
        hdrReady[0].buffer = request.residentHdrAccumulatorBuffer;
        hdrReady[1].buffer = request.residentHdrWeightBuffer;
        vkCmdPipelineBarrier(
                command,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0u,
                0u, nullptr,
                2u, hdrReady,
                0u, nullptr);
    }

    IspPush push{
        request.width,
        request.height,
        out.outputWidth,
        out.outputHeight,
        rotation,
        static_cast<std::uint32_t>(outputPixelCount),
        std::clamp(request.wbRed, 0.50f, 2.00f),
        std::clamp(request.wbGreen, 0.50f, 2.00f),
        std::clamp(request.wbBlue, 0.50f, 2.00f),
        sanitizeProfileCreativeCarrier(request.saturation),
        std::clamp(request.contrast, -1.0f, 1.0f),
        std::clamp(request.vibrance, -1.0f, 1.0f),
        std::clamp(request.profileDetailAmount, -1.0f, 1.0f),
        std::clamp(request.profileDetailRadius, 0.0f, 3.00f),
        // Phase 3 compatibility transport is intentionally non-positive; legacy/default
        // unsigned values collapse to neutral 0 before the shader decodes signed Detail.
        std::clamp(request.profileDetailDetail, -1.0f, 0.0f),
        std::clamp(request.profileDetailMasking, -1.0f, 1.0f),
        0u,
        gainmapWidth,
        gainmapHeight,
        static_cast<std::uint32_t>(gainmapPixelCount),
        usePortrait ? 1u : 0u,
        request.portraitMaskWidth,
        request.portraitMaskHeight,
        portraitRotation,
        request.portraitTargetLeft,
        request.portraitTargetTop,
        request.portraitTargetRight,
        request.portraitTargetBottom,
        std::clamp(request.profileNrLumaBlend, 0.0f, 0.75f),
        std::clamp(request.profileNrLumaProtection, 0.0f, 1.0f),
        std::clamp(request.profileNrChromaBlend, 0.0f, 0.85f),
        std::clamp(request.profileNrChromaProtection, 0.0f, 1.0f),
    };
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(
            command,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            pipelineLayout_,
            0u,
            1u,
            &descriptorSet_,
            0u,
            nullptr);
    const std::uint32_t blocks = static_cast<std::uint32_t>((outputPixelCount + 3u) / 4u);
    const std::uint32_t groups = (blocks + 63u) / 64u;
    const auto gpuStart = Clock::now();

    // FASE 13 is deliberately three resident dispatches:
    // 2 = sampled residual histogram, 3 = compact robust model finalize, 0 = render.
    // No full-frame intermediate leaves the GPU.
    push.ultraHdrMode = 2u;
    vkCmdPushConstants(command, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(command, groups, 1u, 1u);

    VkMemoryBarrier analysisBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    analysisBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    analysisBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            1u, &analysisBarrier,
            0u, nullptr,
            0u, nullptr);

    push.ultraHdrMode = 3u;
    vkCmdPushConstants(command, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(command, 1u, 1u, 1u);

    VkMemoryBarrier modelBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    modelBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    modelBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            1u, &modelBarrier,
            0u, nullptr,
            0u, nullptr);

    push.ultraHdrMode = 0u;
    vkCmdPushConstants(command, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(command, groups, 1u, 1u);

    if (useUltraHdr) {
        VkBufferMemoryBarrier gainReady[2]{};
        gainReady[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        gainReady[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        gainReady[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        gainReady[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        gainReady[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        gainReady[0].buffer = gainLogDevice_.buffer;
        gainReady[0].size = static_cast<VkDeviceSize>(gainLogBytes);
        gainReady[1].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        gainReady[1].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        gainReady[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        gainReady[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        gainReady[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        gainReady[1].buffer = ultraHdrTelemetry_.buffer;
        gainReady[1].size = static_cast<VkDeviceSize>(telemetryBytes);
        vkCmdPipelineBarrier(
                command,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0u,
                0u, nullptr,
                2u, gainReady,
                0u, nullptr);

        push.ultraHdrMode = 1u;
        vkCmdPushConstants(command, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        const std::uint64_t gainmapWordCount =
                static_cast<std::uint64_t>(gainmapWordsPerRow) * gainmapHeight;
        const std::uint32_t gainmapGroups = static_cast<std::uint32_t>(
                (gainmapWordCount + 63u) / 64u);
        vkCmdDispatch(command, gainmapGroups, 1u, 1u);
    }

    VkMemoryBarrier outputBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    outputBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outputBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(
            command,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
            0u,
            1u,
            &outputBarrier,
            0u,
            nullptr,
            0u,
            nullptr);
    VkBufferCopy outputCopy{0u, 0u, paddedOutputBytes};
    vkCmdCopyBuffer(command, outputDevice_.buffer, outputReadback_.buffer, 1u, &outputCopy);
    if (useUltraHdr) {
        VkBufferCopy gainmapCopy{0u, 0u, static_cast<VkDeviceSize>(gainmapPackedBytes)};
        vkCmdCopyBuffer(command, gainmapDevice_.buffer, gainmapReadback_.buffer, 1u, &gainmapCopy);
    }

    if (vkEndCommandBuffer(command) != VK_SUCCESS) {
        out.failureReason = "YUV_SINGLE_FRAME_COMMAND_END_FAILED";
        freeCommand();
        return out;
    }

    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) {
        out.failureReason = "YUV_SINGLE_FRAME_FENCE_CREATE_FAILED";
        freeCommand();
        return out;
    }
    VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submitInfo.commandBufferCount = 1u;
    submitInfo.pCommandBuffers = &command;
    if (vkQueueSubmit(computeQueue, 1u, &submitInfo, fence) != VK_SUCCESS) {
        out.failureReason = "YUV_SINGLE_FRAME_QUEUE_SUBMIT_FAILED";
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
                ? "YUV_SINGLE_FRAME_FENCE_TIMEOUT"
                : "YUV_SINGLE_FRAME_FENCE_WAIT_FAILED";
        // Unknown completion: deliberately retain fence/command and persistent resources. The
        // authoritative runtime keeps its in-flight ownership registered and quarantines itself.
        return out;
    }
    out.gpuExecutionWallMs = elapsedMs(gpuStart);

    vkDestroyFence(device, fence, nullptr);
    freeCommand();

    const auto readbackStart = Clock::now();
    vmaInvalidateAllocation(allocator_, outputReadback_.allocation, 0u, paddedOutputBytes);
    out.bgr24.resize(static_cast<std::size_t>(bgrBytes));
    std::memcpy(out.bgr24.data(), outputReadback_.mapped, static_cast<std::size_t>(bgrBytes));

    vmaInvalidateAllocation(allocator_, ultraHdrTelemetry_.allocation, 0u, telemetryBytes);
    const auto* telemetry = static_cast<const std::uint32_t*>(ultraHdrTelemetry_.mapped);
    if (telemetry != nullptr) {
        out.yuvResidualLumaSamples = telemetry[bncam::yuv_phase13::kLumaSampleCountWord];
        out.yuvResidualChromaSamples = telemetry[bncam::yuv_phase13::kChromaSampleCountWord];
        out.yuvResidualSigmaY = static_cast<float>(telemetry[bncam::yuv_phase13::kSigmaYQ24Word]) /
                bncam::yuv_phase13::kQ24Scale;
        out.yuvResidualSigmaU = static_cast<float>(telemetry[bncam::yuv_phase13::kSigmaUQ24Word]) /
                bncam::yuv_phase13::kQ24Scale;
        out.yuvResidualSigmaV = static_cast<float>(telemetry[bncam::yuv_phase13::kSigmaVQ24Word]) /
                bncam::yuv_phase13::kQ24Scale;
        out.yuvResidualLumaAuthority = static_cast<float>(
                telemetry[bncam::yuv_phase13::kLumaAuthorityQ24Word]) / bncam::yuv_phase13::kQ24Scale;
        out.yuvResidualChromaAuthority = static_cast<float>(
                telemetry[bncam::yuv_phase13::kChromaAuthorityQ24Word]) / bncam::yuv_phase13::kQ24Scale;
        out.yuvResidualModelConfidence = static_cast<float>(
                telemetry[bncam::yuv_phase13::kModelConfidenceQ24Word]) / bncam::yuv_phase13::kQ24Scale;
        out.yuvResidualNoiseModelAvailable = out.yuvResidualModelConfidence >= 0.15f &&
                out.yuvResidualLumaSamples >= 24u && out.yuvResidualChromaSamples >= 16u;
    }

    if (useUltraHdr) {
        constexpr float kMeaningfulGainLog2 = 0.111031312f; // log2(1.08)
        const float maxLog2Boost = telemetry != nullptr
                ? static_cast<float>(telemetry[0]) / 65536.0f
                : 0.0f;
        out.ultraHdrMaxContentBoost = std::exp2(std::clamp(maxLog2Boost, 0.0f, 4.0f));
        out.ultraHdrMeaningfulHeadroom = maxLog2Boost >= kMeaningfulGainLog2;
        out.ultraHdrGainmapWidth = gainmapWidth;
        out.ultraHdrGainmapHeight = gainmapHeight;
        out.ultraHdrGainmapRowStrideBytes = gainmapWordsPerRow * sizeof(std::uint32_t);
        if (out.ultraHdrMeaningfulHeadroom && gainmapReadback_.mapped != nullptr) {
            vmaInvalidateAllocation(allocator_, gainmapReadback_.allocation, 0u, gainmapPackedBytes);
            out.ultraHdrGainmapBytes.resize(static_cast<std::size_t>(gainmapPackedBytes));
            // Vulkan has already produced the final 8-bit gainmap in JPEG orientation. This is
            // an artifact transfer only; CPU code performs no gainmap pixel calculation.
            std::memcpy(out.ultraHdrGainmapBytes.data(), gainmapReadback_.mapped,
                        static_cast<std::size_t>(gainmapPackedBytes));
            out.ultraHdrGainmapGenerated = true;
            out.ultraHdrStatus = "GPU_GAINMAP_READY";
        } else {
            out.ultraHdrStatus = out.ultraHdrMeaningfulHeadroom
                    ? "GPU_GAINMAP_READBACK_UNAVAILABLE"
                    : "NO_MEANINGFUL_HDR_HEADROOM";
        }
    }
    out.publicationReadbackMs = elapsedMs(readbackStart);
    out.fullFrameGpuReadbackBytes = bgrBytes + (useUltraHdr ? gainmapPackedBytes : 0u);
    if (usePortrait) {
        out.portraitEffectApplied = true;
        out.portraitStatus = "GPU_PORTRAIT_APPLIED";
    }
    out.backend = "VULKAN_YUV_SINGLE_FRAME_ISP_PHASE13_RESIDUAL";
    out.failureReason = "none";
    out.success = true;
    return out;
#endif
}

} // namespace bncam::vulkan
