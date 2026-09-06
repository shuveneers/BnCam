#include "VulkanSpectraResidentToneBackend.h"
#include "../ProfileEdgeZipperTransport.h"
#include "VulkanPipelineCacheRegistry.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_SPECTRA_TONE_SHADER_AVAILABLE
#define BNCAM_SPECTRA_TONE_SHADER_AVAILABLE 0
#endif
#if BNCAM_SPECTRA_TONE_SHADER_AVAILABLE
#include "SpectraResidentToneSpirv.h"
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
float elapsedMs(Clock::time_point start) {
    return static_cast<float>(std::chrono::duration<double, std::milli>(Clock::now() - start).count());
}

struct alignas(16) PushConstants {
    std::uint32_t frameWidth = 0u;
    std::uint32_t frameHeight = 0u;
    std::uint32_t mode = 0u;
    std::uint32_t sampleStep = 1u;
    std::uint32_t sampleCount = 0u;
    std::uint32_t displayOffsetFloats = 0u;
    std::uint32_t isRawBayer = 1u;
    std::uint32_t presenceReserved0 = 0u;
    float exposureGain = 1.0f;
    float rawJpegBaseVibrance = 1.0f;
    float profileSaturation = 0.0f;
    float profileContrast = 0.0f;
    float profileVibrance = 0.0f;
    float presenceReserved1 = 0.0f;
    float shoulderStart = 0.68f;
    float shoulderStrength = 1.0f;
    std::uint32_t ultraHdrSourceMapWidth = 0u;
    std::uint32_t ultraHdrSourceMapHeight = 0u;
    std::uint32_t ultraHdrOutputMapWidth = 0u;
    std::uint32_t ultraHdrOutputMapHeight = 0u;
    std::uint32_t ultraHdrPackedWordsPerRow = 0u;
    std::uint32_t outputRotationDegrees = 0u;
    std::uint32_t portraitEnabled = 0u;
    std::uint32_t portraitMaskWidth = 0u;
    std::uint32_t portraitMaskHeight = 0u;
    std::uint32_t portraitMaskRotationDegrees = 0u;
    float portraitTargetLeft = 0.0f;
    float portraitTargetTop = 0.0f;
    float portraitTargetRight = 0.0f;
    float portraitTargetBottom = 0.0f;
};
static_assert(sizeof(PushConstants) == 128u, "resident tone push constants mismatch");

[[maybe_unused]] constexpr std::uint32_t kDisplayGridWidth = 32u;
[[maybe_unused]] constexpr std::uint32_t kDisplayGridHeight = 24u;
[[maybe_unused]] constexpr std::uint32_t kTelemetryWords = 64u;
constexpr std::size_t kToneLutFloats = 4096u * 2u;

// Capture-time RAW preview survival: mode-0 scene preparation is a full-resolution, expensive
// 16x16 compute kernel. Keep each capture submission well below one 33 ms RAW-preview frame
// budget so the higher-priority preview queue gets real scheduling boundaries during capture.
// 384 rows = 24 complete workgroups and eight stripes for the common 3072-row RAW stream.
constexpr std::uint32_t kSceneObserverWorkgroupRows = 16u;
constexpr std::uint32_t kSceneObserverStripeRows = 384u;
static_assert(kSceneObserverStripeRows % kSceneObserverWorkgroupRows == 0u,
              "scene observer stripes must preserve 16-row workgroup alignment");

constexpr std::uint32_t kFllfMaxLevels = 6u;
struct FllfLevelLayout {
    std::uint32_t width = 1u;
    std::uint32_t height = 1u;
    std::uint32_t offset = 0u;
};
struct FllfPyramidLayout {
    FllfLevelLayout levels[kFllfMaxLevels]{};
    std::uint32_t levelCount = 0u;
    std::uint64_t totalFloats = 0u;
};
FllfPyramidLayout buildFllfPyramidLayout(
        std::uint32_t frameWidth, std::uint32_t frameHeight, std::uint32_t requestedLevels) noexcept {
    FllfPyramidLayout out{};
    std::uint32_t w = std::max(1u, (frameWidth + 1u) / 2u);
    std::uint32_t h = std::max(1u, (frameHeight + 1u) / 2u);
    const std::uint32_t levels = std::clamp(requestedLevels, 2u, kFllfMaxLevels);
    for (std::uint32_t level = 0u; level < levels; ++level) {
        if (out.totalFloats > static_cast<std::uint64_t>(std::numeric_limits<std::uint32_t>::max())) break;
        out.levels[level] = {w, h, static_cast<std::uint32_t>(out.totalFloats)};
        out.totalFloats += static_cast<std::uint64_t>(w) * h;
        ++out.levelCount;
        if ((w == 1u && h == 1u) || level + 1u == levels) break;
        w = std::max(1u, (w + 1u) / 2u);
        h = std::max(1u, (h + 1u) / 2u);
    }
    return out;
}

} // namespace

bool VulkanSpectraResidentToneBackend::productionKernelConnected() const noexcept {
#if BNCAM_SPECTRA_TONE_SHADER_AVAILABLE
    return !getSpectraResidentToneSpirv().empty();
#else
    return false;
#endif
}

bool VulkanSpectraResidentToneBackend::ensureBufferLocked(
        VmaAllocator allocator, std::uint64_t bytes, std::uint32_t hostAccess,
        PersistentBuffer& buffer, bool& reallocated, std::string& failureReason) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)hostAccess; (void)buffer; (void)reallocated;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u || bytes > std::numeric_limits<VkDeviceSize>::max()) {
        failureReason = "INVALID_RESIDENT_TONE_BUFFER_REQUEST";
        return false;
    }
    const bool mappedRequired = hostAccess != 0u;
    if (buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr &&
        buffer.capacityBytes >= bytes && (!mappedRequired || buffer.mapped != nullptr)) {
        return true;
    }
    if (buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, buffer.buffer, buffer.allocation);
        buffer = {};
    }
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = static_cast<VkDeviceSize>(bytes);
    bufferInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VmaAllocationCreateInfo allocationInfo{};
    allocationInfo.usage = mappedRequired ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    if (mappedRequired) {
        allocationInfo.flags = hostAccess | VMA_ALLOCATION_CREATE_MAPPED_BIT;
    }
    VmaAllocationInfo mappedInfo{};
    if (vmaCreateBuffer(allocator, &bufferInfo, &allocationInfo, &buffer.buffer,
                        &buffer.allocation, &mappedInfo) != VK_SUCCESS) {
        failureReason = "vmaCreateBuffer_resident_tone_failed";
        buffer = {};
        return false;
    }
    buffer.mapped = mappedInfo.pMappedData;
    buffer.capacityBytes = bytes;
    reallocated = true;
    ++allocationGeneration_;
    return true;
#endif
}

void VulkanSpectraResidentToneBackend::destroyBuffersLocked() noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr) {
        for (PersistentBuffer* b : {&workingRgb_, &compact_, &toneLut_, &readback_, &telemetry_,
                                    &ultraHdrLuma_, &ultraHdrGainLog_, &ultraHdrGainmapPacked_, &portraitMask_, &portraitBlurRgb_,
                                    &localToneBase_, &fllfGaussian_, &fllfCorrection_}) {
            if (b->buffer != VK_NULL_HANDLE && b->allocation != nullptr) {
                vmaDestroyBuffer(allocator_, b->buffer, b->allocation);
            }
            *b = {};
        }
    }
#endif
    residentSceneValid_ = false;
    residentToneValid_ = false;
    residentWidth_ = 0u;
    residentHeight_ = 0u;
}

void VulkanSpectraResidentToneBackend::destroyLocked(VkDevice device) noexcept {
    destroyBuffersLocked();
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
    descriptorSetLayout_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    shaderModule_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    descriptorPool_ = VK_NULL_HANDLE;
    descriptorSet_ = VK_NULL_HANDLE;
    commandBuffer_ = VK_NULL_HANDLE;
    fence_ = VK_NULL_HANDLE;
    queryPool_ = VK_NULL_HANDLE;
    initialized_ = false;
    initializedDevice_ = VK_NULL_HANDLE;
    initializedCommandPool_ = VK_NULL_HANDLE;
    allocator_ = nullptr;
}

void VulkanSpectraResidentToneBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanSpectraResidentToneBackend::initializeLocked(
        VkDevice device, VkCommandPool commandPool, std::string& failureReason) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device && initializedCommandPool_ == commandPool) return true;
        failureReason = "RESIDENT_TONE_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_SPECTRA_TONE_SHADER_AVAILABLE
    (void)device; (void)commandPool;
    failureReason = "RESIDENT_TONE_SHADER_NOT_COMPILED";
    return false;
#else
    const auto& spirv = getSpectraResidentToneSpirv();
    if (device == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE || spirv.empty()) {
        failureReason = "RESIDENT_TONE_INITIALIZATION_INVALID";
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[13]{};
    for (std::uint32_t i = 0u; i < 13u; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo dsl{};
    dsl.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dsl.bindingCount = 13u;
    dsl.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device, &dsl, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorSetLayout_resident_tone_failed";
        destroyLocked(device); return false;
    }
    VkPushConstantRange range{};
    range.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    range.size = sizeof(PushConstants);
    VkPipelineLayoutCreateInfo pli{};
    pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pli.setLayoutCount = 1u;
    pli.pSetLayouts = &descriptorSetLayout_;
    pli.pushConstantRangeCount = 1u;
    pli.pPushConstantRanges = &range;
    if (vkCreatePipelineLayout(device, &pli, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "vkCreatePipelineLayout_resident_tone_failed";
        destroyLocked(device); return false;
    }
    VkShaderModuleCreateInfo sm{};
    sm.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    sm.codeSize = spirv.size() * sizeof(std::uint32_t);
    sm.pCode = spirv.data();
    if (vkCreateShaderModule(device, &sm, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_resident_tone_failed";
        destroyLocked(device); return false;
    }
    VkPipelineShaderStageCreateInfo stage{};
    stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = shaderModule_;
    stage.pName = "main";
    VkComputePipelineCreateInfo cp{};
    cp.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cp.stage = stage;
    cp.layout = pipelineLayout_;
    if (VulkanPipelineCacheRegistry::createComputePipelines(device, 1u, &cp, nullptr, &pipeline_) != VK_SUCCESS) {
        failureReason = "vkCreateComputePipelines_resident_tone_failed";
        destroyLocked(device); return false;
    }
    VkDescriptorPoolSize ps{};
    ps.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    ps.descriptorCount = 13u;
    VkDescriptorPoolCreateInfo dpi{};
    dpi.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpi.maxSets = 1u;
    dpi.poolSizeCount = 1u;
    dpi.pPoolSizes = &ps;
    if (vkCreateDescriptorPool(device, &dpi, nullptr, &descriptorPool_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorPool_resident_tone_failed";
        destroyLocked(device); return false;
    }
    VkDescriptorSetAllocateInfo dsa{};
    dsa.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsa.descriptorPool = descriptorPool_;
    dsa.descriptorSetCount = 1u;
    dsa.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &dsa, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "vkAllocateDescriptorSets_resident_tone_failed";
        destroyLocked(device); return false;
    }
    VkCommandBufferAllocateInfo cb{};
    cb.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cb.commandPool = commandPool;
    cb.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cb.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &cb, &commandBuffer_) != VK_SUCCESS) {
        failureReason = "vkAllocateCommandBuffers_resident_tone_failed";
        destroyLocked(device); return false;
    }
    VkFenceCreateInfo fi{};
    fi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fi, nullptr, &fence_) != VK_SUCCESS) {
        failureReason = "vkCreateFence_resident_tone_failed";
        destroyLocked(device); return false;
    }
    VkQueryPoolCreateInfo qi{};
    qi.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    qi.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qi.queryCount = 12u;
    if (vkCreateQueryPool(device, &qi, nullptr, &queryPool_) != VK_SUCCESS) queryPool_ = VK_NULL_HANDLE;
    initialized_ = true;
    initializedDevice_ = device;
    initializedCommandPool_ = commandPool;
    failureReason.clear();
    return true;
#endif
}

void VulkanSpectraResidentToneBackend::updateDescriptorsLocked(
        VkDevice device, VkBuffer inputOverride) noexcept {
    // Bind harmless already-valid buffers for optional Ultra HDR bindings until a gainmap is
    // actually requested. This keeps the shared scene/tone shader descriptor set valid without
    // allocating gainmap resources for ordinary captures.
    VkDescriptorBufferInfo infos[13]{};
    infos[0].buffer = inputOverride != VK_NULL_HANDLE ? inputOverride : workingRgb_.buffer;
    infos[1].buffer = workingRgb_.buffer;
    infos[2].buffer = compact_.buffer;
    infos[3].buffer = toneLut_.buffer;
    infos[4].buffer = telemetry_.buffer;
    infos[5].buffer = ultraHdrLuma_.buffer != VK_NULL_HANDLE ? ultraHdrLuma_.buffer : compact_.buffer;
    infos[6].buffer = ultraHdrGainLog_.buffer != VK_NULL_HANDLE ? ultraHdrGainLog_.buffer : compact_.buffer;
    infos[7].buffer = ultraHdrGainmapPacked_.buffer != VK_NULL_HANDLE ? ultraHdrGainmapPacked_.buffer : telemetry_.buffer;
    infos[8].buffer = portraitMask_.buffer != VK_NULL_HANDLE ? portraitMask_.buffer : compact_.buffer;
    infos[9].buffer = portraitBlurRgb_.buffer != VK_NULL_HANDLE ? portraitBlurRgb_.buffer : workingRgb_.buffer;
    infos[10].buffer = localToneBase_.buffer != VK_NULL_HANDLE ? localToneBase_.buffer : compact_.buffer;
    infos[11].buffer = fllfGaussian_.buffer != VK_NULL_HANDLE ? fllfGaussian_.buffer : compact_.buffer;
    infos[12].buffer = fllfCorrection_.buffer != VK_NULL_HANDLE ? fllfCorrection_.buffer : compact_.buffer;
    for (auto& info : infos) info.range = VK_WHOLE_SIZE;
    VkWriteDescriptorSet writes[13]{};
    for (std::uint32_t i = 0u; i < 13u; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, 13u, writes, 0u, nullptr);
}

SpectraResidentSceneObserverResult VulkanSpectraResidentToneBackend::executeSceneObserverFromResident(
        VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue,
        VkCommandPool commandPool, VulkanAllocatorOwner& allocatorOwner,
        VkBuffer residentInputBuffer, std::uint64_t residentInputBytes,
        const SpectraResidentSceneObserverRequest& request) noexcept {
    SpectraResidentSceneObserverResult result{};
    result.attempted = true;
    const auto totalStart = Clock::now();
    if (residentInputBuffer == VK_NULL_HANDLE || request.frameWidth < 3u || request.frameHeight < 3u ||
        device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE) {
        result.status = "GPU_SCENE_OBSERVER_INVALID_INPUT";
        result.failureReason = "INVALID_RESIDENT_SCENE_REQUEST";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_TONE_SHADER_AVAILABLE
    (void)physicalDevice; (void)allocatorOwner; (void)residentInputBytes;
    result.status = "GPU_SCENE_OBSERVER_BUILD_SUPPORT_UNAVAILABLE";
    result.failureReason = !BNCAM_VMA_HEADER_AVAILABLE ? "VMA_HEADER_NOT_AVAILABLE" : "RESIDENT_TONE_SHADER_NOT_COMPILED";
    result.totalMs = elapsedMs(totalStart);
    return result;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    std::string failure;
    if (!initializeLocked(device, commandPool, failure)) {
        result.status = "GPU_SCENE_OBSERVER_INITIALIZATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr) {
        result.status = "GPU_SCENE_OBSERVER_ALLOCATOR_UNAVAILABLE";
        result.failureReason = "VMA_ALLOCATOR_NOT_READY";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    const std::uint64_t pixels = static_cast<std::uint64_t>(request.frameWidth) * request.frameHeight;
    const std::uint64_t rgbBytes = pixels * 3u * sizeof(float);
    const float preToneChroma444Strength = std::clamp(request.preToneChroma444Strength, 0.0f, 0.94f);
    const bool preToneChroma444Requested = request.preToneChroma444Enabled &&
            preToneChroma444Strength > 1.0e-4f;
    const bool preToneChromaCovarianceWhiteningRequested = preToneChroma444Requested &&
            request.preToneChromaCovarianceWhiteningEnabled &&
            std::isfinite(request.preToneChromaVarianceY) && request.preToneChromaVarianceY > 1.0e-14f &&
            std::isfinite(request.preToneChromaVarianceC1) && request.preToneChromaVarianceC1 > 1.0e-14f &&
            std::isfinite(request.preToneChromaVarianceC2) && request.preToneChromaVarianceC2 > 1.0e-14f &&
            std::isfinite(request.preToneChromaCovarianceC1C2) &&
            std::isfinite(request.preToneChromaReferenceSignal) && request.preToneChromaReferenceSignal > 0.0f &&
            std::isfinite(request.preToneChromaShotNoiseFraction) &&
            std::isfinite(request.preToneChromaModelConfidence) && request.preToneChromaModelConfidence >= 0.10f &&
            std::isfinite(request.preToneChromaFullShrinkSigma) &&
            std::isfinite(request.preToneChromaPreserveSigma) &&
            request.preToneChromaPreserveSigma > request.preToneChromaFullShrinkSigma;
    if (residentInputBytes < rgbBytes) {
        result.status = "GPU_SCENE_OBSERVER_RESIDENT_INPUT_TOO_SMALL";
        result.failureReason = "RESIDENT_RGB_BYTES_BELOW_FRAME_REQUIREMENT";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    const std::uint32_t target = std::max(1u, request.targetSampleCount);
    result.sampleStep = static_cast<std::uint32_t>(std::max<std::uint64_t>(1u, pixels / target));
    result.sampleCount = static_cast<std::uint32_t>((pixels + result.sampleStep - 1u) / result.sampleStep);
    const std::uint64_t compactFloats = static_cast<std::uint64_t>(result.sampleCount) * 3u +
            kDisplayGridWidth * kDisplayGridHeight;
    result.compactBytes = compactFloats * sizeof(float);
    bool reallocated = false;
    const std::uint32_t readAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    const std::uint32_t writeAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
    if (!ensureBufferLocked(allocator_, rgbBytes, 0u, workingRgb_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, std::max<std::uint64_t>(result.compactBytes, 16u), readAccess, compact_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, kToneLutFloats * sizeof(float), writeAccess, toneLut_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, kTelemetryWords * sizeof(std::uint32_t), readAccess, telemetry_, reallocated, failure)) {
        result.status = "GPU_SCENE_OBSERVER_BUFFER_ALLOCATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = workingRgb_.capacityBytes + compact_.capacityBytes +
            toneLut_.capacityBytes + readback_.capacityBytes + telemetry_.capacityBytes +
            ultraHdrLuma_.capacityBytes + ultraHdrGainLog_.capacityBytes + ultraHdrGainmapPacked_.capacityBytes +
            portraitMask_.capacityBytes + portraitBlurRgb_.capacityBytes + localToneBase_.capacityBytes +
            fllfGaussian_.capacityBytes + fllfCorrection_.capacityBytes;
    updateDescriptorsLocked(device, residentInputBuffer);

    PushConstants push{};
    push.frameWidth = request.frameWidth;
    push.frameHeight = request.frameHeight;
    push.mode = 0u;
    push.sampleStep = result.sampleStep;
    push.sampleCount = result.sampleCount;
    push.displayOffsetFloats = result.sampleCount * 3u;
    push.presenceReserved0 = preToneChroma444Requested ? 1u : 0u;
    push.presenceReserved1 = preToneChroma444Strength;
    // Mode-0-only aliases. Keep the push block at the portable 128-byte Vulkan minimum while
    // supplying the exact propagated physical covariance needed for whitened chroma shrinkage.
    push.portraitEnabled = preToneChromaCovarianceWhiteningRequested ? 1u : 0u;
    push.exposureGain = std::max(0.0f, request.preToneChromaVarianceY);
    push.rawJpegBaseVibrance = std::max(0.0f, request.preToneChromaVarianceC1);
    push.profileSaturation = std::max(0.0f, request.preToneChromaVarianceC2);
    push.profileContrast = request.preToneChromaCovarianceC1C2;
    push.profileVibrance = std::clamp(request.preToneChromaReferenceSignal, 1.0e-4f, 2.0f);
    push.portraitTargetLeft = std::clamp(request.preToneChromaShotNoiseFraction, 0.0f, 1.0f);
    push.portraitTargetTop = std::clamp(request.preToneChromaModelConfidence, 0.0f, 1.0f);
    push.portraitTargetRight = std::max(0.50f, request.preToneChromaFullShrinkSigma);
    push.portraitTargetBottom = std::max(push.portraitTargetRight + 0.25f, request.preToneChromaPreserveSigma);
    // Mode 0 owns no tone shoulder. Reuse these two existing push slots only for
    // measured physical-noise and WB+CCM amplification evidence. Delta 0066 removes
    // all demosaic-family / RAW-format authority heuristics from Phase 9.
    push.shoulderStart = std::clamp(request.preToneChromaNoisePressure, 0.0f, 1.0f);
    push.shoulderStrength = std::clamp(request.preToneChromaWbCcmPressure, 0.0f, 1.0f);

    double timestampToMs = 0.0;
    if (queryPool_ != VK_NULL_HANDLE) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(physicalDevice, &props);
        timestampToMs = static_cast<double>(props.limits.timestampPeriod) / 1.0e6;
    }

    // The old path submitted mode 0 as one 4096x3072 dispatch. On the target device that kernel
    // occupied the capture queue for ~100 ms, and the RAW preview queue could consequently wait
    // hundreds of milliseconds during rapid shots. Split only this independent-per-workgroup pass
    // into aligned row stripes. Pixel math and full-frame geometry are unchanged; the boundaries
    // exist solely so the dedicated, higher-priority preview queue can be scheduled between them.
    for (std::uint32_t stripeRow = 0u; stripeRow < request.frameHeight;
         stripeRow += kSceneObserverStripeRows) {
        const std::uint32_t stripeRows = std::min(
                kSceneObserverStripeRows, request.frameHeight - stripeRow);
        vkResetFences(device, 1u, &fence_);
        vkResetCommandBuffer(commandBuffer_, 0u);
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
            result.status = "GPU_SCENE_OBSERVER_STRIPE_COMMAND_BEGIN_FAILED";
            result.failureReason = "vkBeginCommandBuffer_scene_observer_stripe_failed";
            result.totalMs = elapsedMs(totalStart);
            return result;
        }

        if (stripeRow == 0u) {
            vkCmdFillBuffer(commandBuffer_, telemetry_.buffer, 0u, VK_WHOLE_SIZE, 0u);
            VkBufferMemoryBarrier inputBarrier{};
            inputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            inputBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            inputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            inputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            inputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            inputBarrier.buffer = residentInputBuffer;
            inputBarrier.size = static_cast<VkDeviceSize>(rgbBytes);
            VkBufferMemoryBarrier telemetryBarrier{};
            telemetryBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            telemetryBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            telemetryBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            telemetryBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            telemetryBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            telemetryBarrier.buffer = telemetry_.buffer;
            telemetryBarrier.size = VK_WHOLE_SIZE;
            VkBufferMemoryBarrier initial[2]{inputBarrier, telemetryBarrier};
            vkCmdPipelineBarrier(commandBuffer_,
                                 VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                                 0u, nullptr, 2u, initial, 0u, nullptr);
        } else {
            // Telemetry atomics are accumulated over all stripes. Fence completion makes the
            // previous submission complete; keep an explicit shader memory dependency as well.
            VkBufferMemoryBarrier telemetryContinue{};
            telemetryContinue.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            telemetryContinue.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            telemetryContinue.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            telemetryContinue.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            telemetryContinue.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            telemetryContinue.buffer = telemetry_.buffer;
            telemetryContinue.size = VK_WHOLE_SIZE;
            vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                                 0u, nullptr, 1u, &telemetryContinue, 0u, nullptr);
        }

        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 2u);
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                queryPool_, 0u);
        }
        vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                                0u, 1u, &descriptorSet_, 0u, nullptr);
        // Mode-0-only alias: the shader adds this aligned row origin to globalInvocationID.y.
        // executeTone() builds a fresh PushConstants object, so Ultra HDR/FLLF semantics are
        // untouched outside this scene-observer pass.
        push.ultraHdrSourceMapHeight = stripeRow;
        push.mode = 0u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                      (stripeRows + 15u) / 16u, 1u);
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                queryPool_, 1u);
        }
        if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
            result.status = "GPU_SCENE_OBSERVER_STRIPE_COMMAND_END_FAILED";
            result.failureReason = "vkEndCommandBuffer_scene_observer_stripe_failed";
            result.totalMs = elapsedMs(totalStart);
            return result;
        }

        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1u;
        submit.pCommandBuffers = &commandBuffer_;
        const auto stripeWaitStart = Clock::now();
        if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS ||
            vkWaitForFences(device, 1u, &fence_, VK_TRUE, 1'500'000'000ull) != VK_SUCCESS) {
            VulkanRuntime::instance().markGpuStalled("SceneObserverStripe");
            result.status = "GPU_STALLED";
            result.failureReason = "scene_observer_stripe_submit_or_wait_timeout";
            result.totalMs = elapsedMs(totalStart);
            return result;
        }
        result.synchronizationMs += elapsedMs(stripeWaitStart);

        if (queryPool_ != VK_NULL_HANDLE) {
            std::uint64_t stripeTs[2]{};
            if (vkGetQueryPoolResults(device, queryPool_, 0u, 2u, sizeof(stripeTs), stripeTs,
                                      sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
                stripeTs[1] >= stripeTs[0]) {
                result.highlightKernelMs += static_cast<float>(
                        (stripeTs[1] - stripeTs[0]) * timestampToMs);
            }
        }
    }

    if (preToneChroma444Requested) {
        result.preToneChroma444Applied = true;
        result.preToneChroma444Strength = preToneChroma444Strength;
    }

    // Compact scene sampling is tiny compared with mode 0. Keep it as one final submission after
    // every stripe has completed. This also establishes the full workingRgb write->read dependency.
    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo compactBegin{};
    compactBegin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    compactBegin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &compactBegin) != VK_SUCCESS) {
        result.status = "GPU_SCENE_OBSERVER_COMPACT_COMMAND_BEGIN_FAILED";
        result.failureReason = "vkBeginCommandBuffer_scene_observer_compact_failed";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    VkBufferMemoryBarrier workingBarrier{};
    workingBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    workingBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    workingBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    workingBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    workingBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    workingBarrier.buffer = workingRgb_.buffer;
    workingBarrier.size = static_cast<VkDeviceSize>(rgbBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                         0u, nullptr, 1u, &workingBarrier, 0u, nullptr);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(commandBuffer_, queryPool_, 2u, 2u);
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            queryPool_, 2u);
    }
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                            0u, 1u, &descriptorSet_, 0u, nullptr);
    push.ultraHdrSourceMapHeight = 0u;
    push.mode = 1u;
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (result.sampleCount + 15u) / 16u, 1u, 1u);
    push.mode = 2u;
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, 2u, 2u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            queryPool_, 3u);
    }

    VkBufferMemoryBarrier hostBarriers[2]{};
    for (auto& b : hostBarriers) {
        b.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        b.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.size = VK_WHOLE_SIZE;
    }
    hostBarriers[0].buffer = compact_.buffer;
    hostBarriers[1].buffer = telemetry_.buffer;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u,
                         0u, nullptr, 2u, hostBarriers, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "GPU_SCENE_OBSERVER_COMPACT_COMMAND_END_FAILED";
        result.failureReason = "vkEndCommandBuffer_scene_observer_compact_failed";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    VkSubmitInfo compactSubmit{};
    compactSubmit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    compactSubmit.commandBufferCount = 1u;
    compactSubmit.pCommandBuffers = &commandBuffer_;
    const auto compactWaitStart = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &compactSubmit, fence_) != VK_SUCCESS ||
        vkWaitForFences(device, 1u, &fence_, VK_TRUE, 1'500'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("SceneObserverCompact");
        result.status = "GPU_STALLED";
        result.failureReason = "scene_observer_compact_submit_or_wait_timeout";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    result.synchronizationMs += elapsedMs(compactWaitStart);
    if (queryPool_ != VK_NULL_HANDLE) {
        std::uint64_t compactTs[2]{};
        if (vkGetQueryPoolResults(device, queryPool_, 2u, 2u, sizeof(compactTs), compactTs,
                                  sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            compactTs[1] >= compactTs[0]) {
            result.sampleKernelMs = static_cast<float>(
                    (compactTs[1] - compactTs[0]) * timestampToMs);
        }
    }
    const auto readStart = Clock::now();
    vmaInvalidateAllocation(allocator_, compact_.allocation, 0u, static_cast<VkDeviceSize>(result.compactBytes));
    vmaInvalidateAllocation(allocator_, telemetry_.allocation, 0u, kTelemetryWords * sizeof(std::uint32_t));
    const float* compact = static_cast<const float*>(compact_.mapped);
    result.sampledRgb.resize(static_cast<std::size_t>(result.sampleCount) * 3u);
    std::memcpy(result.sampledRgb.data(), compact, result.sampledRgb.size() * sizeof(float));
    result.displayGrid.resize(kDisplayGridWidth * kDisplayGridHeight);
    std::memcpy(result.displayGrid.data(), compact + static_cast<std::size_t>(result.sampleCount) * 3u,
                result.displayGrid.size() * sizeof(float));
    const auto* telemetry = static_cast<const std::uint32_t*>(telemetry_.mapped);
    // Phase 9 Delta 0063: mode-0 telemetry is compact tile/chroma evidence only.
    // No full-frame debug surface crosses to the CPU.
    result.correctedHighlightPixels = 0u;
    result.highlightRecoveryApplied = false;
    result.preToneChromaTilesScanned = telemetry[0];
    result.preToneChromaEligibleTiles = telemetry[1];
    result.preToneChromaCorrectedPixels = telemetry[2];
    result.preToneChromaDetailProtectedPixels = telemetry[3];
    result.preToneChromaStructuredTiles = telemetry[7];
    result.preToneChromaTileScanApplied = preToneChroma444Requested && telemetry[0] > 0u;
    if (telemetry[0] > 0u) {
        const float invTiles = 1.0f / static_cast<float>(telemetry[0]);
        result.preToneChromaMeanNoisePressure =
                static_cast<float>(telemetry[4]) * invTiles / 4095.0f;
        result.preToneChromaMeanResidualSigma =
                0.05f * static_cast<float>(telemetry[5]) * invTiles / 4095.0f;
    }
    float preToneChromaMaxCorrection = 0.0f;
    std::memcpy(&preToneChromaMaxCorrection, &telemetry[6], sizeof(preToneChromaMaxCorrection));
    result.preToneChromaMaxCorrection =
            std::isfinite(preToneChromaMaxCorrection) && preToneChromaMaxCorrection >= 0.0f
                    ? preToneChromaMaxCorrection
                    : 0.0f;
    result.preToneChromaCovarianceEvaluatedPixels = telemetry[56];
    result.preToneChromaNearBlackPixels = telemetry[57];
    result.preToneChromaStrongShrinkPixels = telemetry[58];
    result.preToneChromaPreservedEvidencePixels = telemetry[59];
    float maximumMahalanobisRadius = 0.0f;
    std::memcpy(&maximumMahalanobisRadius, &telemetry[60], sizeof(maximumMahalanobisRadius));
    result.preToneChromaMaxMahalanobisRadius =
            std::isfinite(maximumMahalanobisRadius) && maximumMahalanobisRadius >= 0.0f
                    ? maximumMahalanobisRadius : 0.0f;
    if (telemetry[63] > 0u) {
        result.preToneChromaMeanShrinkAuthority =
                static_cast<float>(telemetry[61]) /
                (4095.0f * static_cast<float>(telemetry[63]));
    }
    result.preToneChromaCovarianceFallbackPixels = telemetry[62];
    result.preToneChromaCovarianceWhiteningApplied =
            preToneChromaCovarianceWhiteningRequested &&
            result.preToneChromaCovarianceEvaluatedPixels > 0u;
    result.compactReadbackMs = elapsedMs(readStart);
    result.residentInputUsed = true;
    residentSceneGeneration_++;
    residentWidth_ = request.frameWidth;
    residentHeight_ = request.frameHeight;
    residentSceneValid_ = true;
    residentToneValid_ = false;
    result.residentSceneGeneration = residentSceneGeneration_;
    result.success = result.sampledRgb.size() == static_cast<std::size_t>(result.sampleCount) * 3u &&
            result.displayGrid.size() == kDisplayGridWidth * kDisplayGridHeight;
    result.status = result.success ? "GPU_RESIDENT_PRETONE_PREPARATION_AND_SCENE_SAMPLES_READY"
                                   : "GPU_SCENE_OBSERVER_COMPACT_READBACK_INCOMPLETE";
    result.failureReason = result.success ? "none" : "COMPACT_SCENE_OUTPUT_SIZE_MISMATCH";
    result.totalMs = elapsedMs(totalStart);
    return result;
#endif
}

SpectraResidentToneResult VulkanSpectraResidentToneBackend::executeTone(
        VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue,
        VkCommandPool commandPool, VulkanAllocatorOwner& allocatorOwner,
        const SpectraResidentToneRequest& request) noexcept {
    SpectraResidentToneResult result{};
    result.attempted = true;
    const auto totalStart = Clock::now();
    if (request.frameWidth == 0u || request.frameHeight == 0u ||
        request.toneLut == nullptr || request.toneLutFloatCount != kToneLutFloats ||
        device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE) {
        result.status = "GPU_TONE_INVALID_INPUT";
        result.failureReason = "INVALID_RESIDENT_TONE_REQUEST";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_TONE_SHADER_AVAILABLE
    (void)physicalDevice; (void)allocatorOwner;
    result.status = "GPU_TONE_BUILD_SUPPORT_UNAVAILABLE";
    result.failureReason = !BNCAM_VMA_HEADER_AVAILABLE ? "VMA_HEADER_NOT_AVAILABLE" : "RESIDENT_TONE_SHADER_NOT_COMPILED";
    result.totalMs = elapsedMs(totalStart);
    return result;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    std::string failure;
    if (!initializeLocked(device, commandPool, failure)) {
        result.status = "GPU_TONE_INITIALIZATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr || !residentSceneValid_ || request.residentSceneGeneration == 0u ||
        request.residentSceneGeneration != residentSceneGeneration_ ||
        request.frameWidth != residentWidth_ || request.frameHeight != residentHeight_) {
        result.status = "GPU_TONE_RESIDENT_INPUT_UNAVAILABLE";
        result.failureReason = "SCENE_GENERATION_MISMATCH";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    const std::uint64_t pixels = static_cast<std::uint64_t>(request.frameWidth) * request.frameHeight;
    const std::uint64_t rgbBytes = pixels * 3u * sizeof(float);
    const auto normalizedRotation = static_cast<std::uint32_t>(
            ((request.outputRotationDegrees % 360) + 360) % 360);
    const bool supportedRotation = normalizedRotation == 0u || normalizedRotation == 90u ||
            normalizedRotation == 180u || normalizedRotation == 270u;
    const bool ultraHdrRequested = request.ultraHdrGainmapRequested && supportedRotation;
    const auto portraitRotation = request.portraitMaskRotationDegrees % 360u;
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
    const bool portraitRequested = request.portraitEffectRequested && request.portraitMask != nullptr &&
            request.portraitMaskWidth > 1u && request.portraitMaskHeight > 1u && portraitBoundsValid &&
            portraitRotationSupported && request.portraitMaskFloatCount >= portraitMaskPixels;
    result.portraitEffectRequested = request.portraitEffectRequested;
    result.portraitStatus = request.portraitEffectRequested
            ? (portraitRequested ? "GPU_MASK_READY" : "MASK_UNAVAILABLE_OR_INVALID")
            : "NOT_REQUESTED";
    const std::uint32_t sourceMapWidth = (request.frameWidth + 3u) / 4u;
    const std::uint32_t sourceMapHeight = (request.frameHeight + 3u) / 4u;
    const bool swapGainmapAxes = normalizedRotation == 90u || normalizedRotation == 270u;
    const std::uint32_t outputMapWidth = swapGainmapAxes ? sourceMapHeight : sourceMapWidth;
    const std::uint32_t outputMapHeight = swapGainmapAxes ? sourceMapWidth : sourceMapHeight;
    const std::uint32_t packedWordsPerRow = (outputMapWidth + 3u) / 4u;
    const std::uint64_t mapPixels = static_cast<std::uint64_t>(sourceMapWidth) * sourceMapHeight;
    const std::uint64_t packedBytes = static_cast<std::uint64_t>(packedWordsPerRow) *
            outputMapHeight * sizeof(std::uint32_t);
    const bool localToneRequested = request.localToneStrength > 1.0e-4f;
    result.localToneRequested = localToneRequested;
    const bool fllfRequested = request.isRawBayer && request.fllfEnabled && request.fllfStrength > 1.0e-4f;
    result.fllfRequested = fllfRequested;
    const bool linearDetailRequested = request.isRawBayer && request.linearDetailEnabled &&
            request.linearDetailAuthority > 1.0e-4f && request.linearDetailNoiseSigmaY > 0.0f &&
            request.linearDetailModelConfidence >= 0.15f;
    result.linearDetailRequested = linearDetailRequested;
    const bool perceptualDetailRequested = request.isRawBayer && request.perceptualDetailEnabled &&
            (std::abs(request.perceptualDetailAuthority) > 1.0e-4f ||
             std::abs(request.perceptualDetailRadius) > 1.0e-4f || // Phase 4 Legibility transport.
             std::abs(request.perceptualDetailEmphasis) > 1.0e-4f ||
             std::abs(request.perceptualDetailMasking) > 1.0e-4f);
    result.perceptualDetailRequested = perceptualDetailRequested;
    const FllfPyramidLayout fllfLayout = buildFllfPyramidLayout(
            request.frameWidth, request.frameHeight, request.fllfPyramidLevels);
    const std::uint64_t fllfScalarBytes = fllfLayout.totalFloats * sizeof(float);
    result.ultraHdrGainmapRequested = request.ultraHdrGainmapRequested;
    if (request.ultraHdrGainmapRequested && !supportedRotation) {
        result.ultraHdrStatus = "UNSUPPORTED_OUTPUT_ROTATION";
    }
    bool reallocated = false;
    const std::uint32_t writeAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
    const std::uint32_t readAccess = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    if (!ensureBufferLocked(allocator_, kToneLutFloats * sizeof(float), writeAccess, toneLut_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, kTelemetryWords * sizeof(std::uint32_t), readAccess, telemetry_, reallocated, failure) ||
        (!request.deferFullReadback && !ensureBufferLocked(allocator_, rgbBytes, readAccess, readback_, reallocated, failure)) ||
        (ultraHdrRequested && !ensureBufferLocked(allocator_, mapPixels * sizeof(float), 0u, ultraHdrLuma_, reallocated, failure)) ||
        (ultraHdrRequested && !ensureBufferLocked(allocator_, mapPixels * sizeof(float), 0u, ultraHdrGainLog_, reallocated, failure)) ||
        (ultraHdrRequested && !ensureBufferLocked(allocator_, packedBytes, readAccess, ultraHdrGainmapPacked_, reallocated, failure)) ||
        (portraitRequested && !ensureBufferLocked(allocator_, portraitMaskPixels * sizeof(float), writeAccess, portraitMask_, reallocated, failure)) ||
        ((portraitRequested || linearDetailRequested || perceptualDetailRequested) && !ensureBufferLocked(allocator_, rgbBytes, 0u, portraitBlurRgb_, reallocated, failure)) ||
        (localToneRequested && !ensureBufferLocked(allocator_, mapPixels * sizeof(float), 0u, localToneBase_, reallocated, failure)) ||
        (fllfRequested && (!ensureBufferLocked(allocator_, std::max<std::uint64_t>(fllfScalarBytes, 16u), 0u, fllfGaussian_, reallocated, failure) ||
                           !ensureBufferLocked(allocator_, std::max<std::uint64_t>(fllfScalarBytes, 16u), 0u, fllfCorrection_, reallocated, failure)))) {
        result.status = "GPU_TONE_BUFFER_ALLOCATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = workingRgb_.capacityBytes + compact_.capacityBytes +
            toneLut_.capacityBytes + readback_.capacityBytes + telemetry_.capacityBytes +
            ultraHdrLuma_.capacityBytes + ultraHdrGainLog_.capacityBytes + ultraHdrGainmapPacked_.capacityBytes +
            portraitMask_.capacityBytes + portraitBlurRgb_.capacityBytes + localToneBase_.capacityBytes +
            fllfGaussian_.capacityBytes + fllfCorrection_.capacityBytes;
    result.fllfResidentBytes = fllfRequested
            ? fllfGaussian_.capacityBytes + fllfCorrection_.capacityBytes
            : 0u;
    result.linearDetailScratchBytes = linearDetailRequested ? portraitBlurRgb_.capacityBytes : 0u;
    result.perceptualDetailScratchBytes = perceptualDetailRequested ? portraitBlurRgb_.capacityBytes : 0u;
    const auto uploadStart = Clock::now();
    std::memcpy(toneLut_.mapped, request.toneLut, kToneLutFloats * sizeof(float));
    vmaFlushAllocation(allocator_, toneLut_.allocation, 0u, kToneLutFloats * sizeof(float));
    if (portraitRequested) {
        const auto portraitBytes = portraitMaskPixels * sizeof(float);
        std::memcpy(portraitMask_.mapped, request.portraitMask, static_cast<std::size_t>(portraitBytes));
        vmaFlushAllocation(allocator_, portraitMask_.allocation, 0u, static_cast<VkDeviceSize>(portraitBytes));
    }
    result.lutUploadMs = elapsedMs(uploadStart);
    updateDescriptorsLocked(device, workingRgb_.buffer);

    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo bi{};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &bi) != VK_SUCCESS) {
        result.status = "GPU_TONE_COMMAND_BEGIN_FAILED";
        result.failureReason = "vkBeginCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    VkBufferMemoryBarrier ready[2]{};
    ready[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    ready[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    ready[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    ready[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    ready[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    ready[0].buffer = workingRgb_.buffer;
    ready[0].size = static_cast<VkDeviceSize>(rgbBytes);
    ready[1].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    ready[1].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    ready[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    ready[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    ready[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    ready[1].buffer = toneLut_.buffer;
    ready[1].size = kToneLutFloats * sizeof(float);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 2u, ready, 0u, nullptr);
    // Reset tone/gainmap/local-adaptation/detail telemetry while preserving Phase-9 scene-observer
    // tile count [0]. FLLF owns [8..19]; Phase-11 owns [20..38]; Phase-12 owns [39..55].
    // Mode-0 near-black covariance telemetry uses [56..63] and is read before this tone reset.
    vkCmdFillBuffer(commandBuffer_, telemetry_.buffer, sizeof(std::uint32_t),
                    (kTelemetryWords - 1u) * sizeof(std::uint32_t), 0u);
    if (localToneRequested) {
        const float ltmValues[4] = {
                std::clamp(request.localToneStrength, 0.0f, 1.0f),
                std::clamp(request.localToneSceneKey, 0.08f, 0.20f),
                std::clamp(request.localToneMaxLiftEv, 0.0f, 1.5f),
                std::clamp(request.localToneMaxCompressEv, 0.0f, 1.0f)};
        std::uint32_t ltmBits[4]{};
        std::memcpy(ltmBits, ltmValues, sizeof(ltmBits));
        vkCmdUpdateBuffer(commandBuffer_, telemetry_.buffer,
                          3u * sizeof(std::uint32_t), sizeof(ltmBits), ltmBits);
    }
    if (fllfRequested) {
        const float fllfValues[6] = {
                std::clamp(request.fllfStrength, 0.0f, 0.92f),
                std::clamp(request.fllfSceneKey, 0.12f, 0.18f),
                std::clamp(request.fllfMaxLiftEv, 0.0f, 1.80f),
                std::clamp(request.fllfMaxCompressEv, 0.0f, 1.10f),
                std::clamp(request.fllfEdgeStopEv, 0.40f, 0.90f),
                std::clamp(request.fllfRefinement, 0.0f, 0.22f)};
        const float fllfPhysicalNoiseSigmaY = std::clamp(
                request.fllfPhysicalNoiseSigmaY, 0.0f, 0.50f);
        std::uint32_t fllfBits[6]{};
        std::memcpy(fllfBits, fllfValues, sizeof(fllfBits));
        vkCmdUpdateBuffer(commandBuffer_, telemetry_.buffer,
                          8u * sizeof(std::uint32_t), sizeof(fllfBits), fllfBits);
        vkCmdUpdateBuffer(commandBuffer_, telemetry_.buffer,
                          19u * sizeof(std::uint32_t),
                          sizeof(fllfPhysicalNoiseSigmaY),
                          &fllfPhysicalNoiseSigmaY);
    }
    if (linearDetailRequested) {
        const float detailValues[11] = {
                std::clamp(request.linearDetailAuthority, 0.0f, 0.62f),
                std::clamp(request.linearDetailRadius, 0.50f, 3.00f),
                std::clamp(request.linearDetailEmphasis, 0.0f, 1.0f),
                std::clamp(request.linearDetailMasking, 0.0f, 1.0f),
                std::max(0.5f, request.linearDetailMinimumResidualSnr),
                std::max(0.4f, request.linearDetailMinimumGradientSnr),
                std::clamp(request.linearDetailHardHaloLimit, 0.004f, 0.05f),
                std::max(1.0e-7f, request.linearDetailNoiseSigmaY),
                std::clamp(request.linearDetailReferenceSignal, 1.0e-4f, 2.0f),
                std::clamp(request.linearDetailShotNoiseFraction, 0.0f, 1.0f),
                std::clamp(request.linearDetailModelConfidence, 0.0f, 1.0f)};
        std::uint32_t detailBits[11]{};
        std::memcpy(detailBits, detailValues, sizeof(detailBits));
        vkCmdUpdateBuffer(commandBuffer_, telemetry_.buffer,
                          20u * sizeof(std::uint32_t), sizeof(detailBits), detailBits);
    }
    if (perceptualDetailRequested) {
        const float edgeAuthority = bncam::profile_edge_zipper_transport::decodeEdge(request.perceptualDetailMasking);
        const float antiZipperAuthority = bncam::profile_edge_zipper_transport::decodeAntiZipper(request.perceptualDetailMasking);
        const float perceptualValues[9] = {
                std::clamp(request.perceptualDetailAuthority, -1.0f, 1.0f),
                std::clamp(request.perceptualDetailRadius, -1.0f, 1.0f), // Phase 4 signed Legibility.
                std::clamp(request.perceptualDetailEmphasis, -1.0f, 1.0f), // Phase 3 standalone signed Detail.
                edgeAuthority, // standalone signed Edge authority.
                std::max(1.0f / 255.0f, request.perceptualDetailNoiseSigmaY),
                antiZipperAuthority, // slot 44: 0..1 zipper detection/reconstruction authority.
                0.75f,
                std::clamp(request.perceptualDetailHardHaloLimit, 0.0f, 0.14f),
                1.0f};
        std::uint32_t perceptualBits[9]{};
        std::memcpy(perceptualBits, perceptualValues, sizeof(perceptualBits));
        vkCmdUpdateBuffer(commandBuffer_, telemetry_.buffer,
                          39u * sizeof(std::uint32_t), sizeof(perceptualBits), perceptualBits);
    }
    VkBufferMemoryBarrier telemetryReady{};
    telemetryReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    telemetryReady.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    telemetryReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    telemetryReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    telemetryReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    telemetryReady.buffer = telemetry_.buffer;
    telemetryReady.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                         0u, nullptr, 1u, &telemetryReady, 0u, nullptr);

    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0u,
                            1u, &descriptorSet_, 0u, nullptr);
    PushConstants push{};
    push.frameWidth = request.frameWidth;
    push.frameHeight = request.frameHeight;
    push.isRawBayer = request.isRawBayer ? 1u : 0u;
    push.exposureGain = request.exposureGain;
    push.rawJpegBaseVibrance = request.rawJpegBaseVibrance;
    push.shoulderStart = std::clamp(request.shoulderStart, 0.50f, 0.85f);
    push.shoulderStrength = std::clamp(request.shoulderStrength, 0.50f, 2.50f);
    push.profileSaturation = std::clamp(request.profileColorSaturation, -1.0f, 1.0f);
    push.profileContrast = std::clamp(request.profileColorContrast, -1.0f, 1.0f);
    push.profileVibrance = std::clamp(request.profilePresenceVibrance, -1.0f, 1.0f);
    push.ultraHdrSourceMapWidth = sourceMapWidth;
    push.ultraHdrSourceMapHeight = sourceMapHeight;
    push.ultraHdrOutputMapWidth = outputMapWidth;
    push.ultraHdrOutputMapHeight = outputMapHeight;
    push.ultraHdrPackedWordsPerRow = packedWordsPerRow;
    push.outputRotationDegrees = normalizedRotation;
    push.portraitEnabled = portraitRequested ? 1u : 0u;
    push.portraitMaskWidth = request.portraitMaskWidth;
    push.portraitMaskHeight = request.portraitMaskHeight;
    push.portraitMaskRotationDegrees = portraitRotation;
    push.portraitTargetLeft = request.portraitTargetLeft;
    push.portraitTargetTop = request.portraitTargetTop;
    push.portraitTargetRight = request.portraitTargetRight;
    push.portraitTargetBottom = request.portraitTargetBottom;

    if (linearDetailRequested) {
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdResetQueryPool(commandBuffer_, queryPool_, 8u, 2u);
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 8u);
        }
        // Pass 14 reads immutable workingRgb neighbourhoods and writes a proposal into the
        // existing full-resolution scratch buffer. Pass 15 commits pointwise, so no invocation
        // observes another invocation's partially sharpened neighbours.
        push.mode = 14u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                       (request.frameHeight + 15u) / 16u, 1u);
        VkBufferMemoryBarrier proposalReady{};
        proposalReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        proposalReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        proposalReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        proposalReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        proposalReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        proposalReady.buffer = portraitBlurRgb_.buffer;
        proposalReady.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &proposalReady, 0u, nullptr);

        push.mode = 15u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                       (request.frameHeight + 15u) / 16u, 1u);
        VkBufferMemoryBarrier detailCommitted{};
        detailCommitted.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        detailCommitted.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        detailCommitted.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        detailCommitted.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        detailCommitted.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        detailCommitted.buffer = workingRgb_.buffer;
        detailCommitted.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &detailCommitted, 0u, nullptr);
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 9u);
        }
    }

    if (fllfRequested && fllfLayout.levelCount >= 2u) {
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 8u);
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 0u);
        }
        const auto& level0 = fllfLayout.levels[0];
        push.mode = 10u;
        push.sampleStep = 0u;
        push.sampleCount = level0.offset;
        push.displayOffsetFloats = 0u;
        push.presenceReserved0 = 0u;
        push.ultraHdrSourceMapWidth = level0.width;
        push.ultraHdrSourceMapHeight = level0.height;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (level0.width + 15u) / 16u, (level0.height + 15u) / 16u, 1u);
        for (std::uint32_t level = 1u; level < fllfLayout.levelCount; ++level) {
            VkBufferMemoryBarrier previousReady{};
            previousReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            previousReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            previousReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            previousReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            previousReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            previousReady.buffer = fllfGaussian_.buffer;
            previousReady.size = VK_WHOLE_SIZE;
            vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                                 0u, nullptr, 1u, &previousReady, 0u, nullptr);
            const auto& src = fllfLayout.levels[level - 1u];
            const auto& dst = fllfLayout.levels[level];
            push.mode = 11u;
            push.sampleStep = src.offset;
            push.sampleCount = dst.offset;
            push.displayOffsetFloats = src.width;
            push.presenceReserved0 = src.height;
            push.ultraHdrSourceMapWidth = dst.width;
            push.ultraHdrSourceMapHeight = dst.height;
            vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
            vkCmdDispatch(commandBuffer_, (dst.width + 15u) / 16u, (dst.height + 15u) / 16u, 1u);
        }
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
        }
        push.sampleStep = 1u;
        push.sampleCount = 0u;
        push.displayOffsetFloats = 0u;
        push.presenceReserved0 = 0u;
        push.ultraHdrSourceMapWidth = sourceMapWidth;
        push.ultraHdrSourceMapHeight = sourceMapHeight;

        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 2u);
        }
        // Seed local exposure on the coarsest still-spatial Gaussian level, then reconstruct the
        // correction field toward level 0. Each finer level uses its Laplacian band as an edge
        // stop, so broad exposure adaptation does not cross strong scene structure.
        const auto& coarse = fllfLayout.levels[fllfLayout.levelCount - 1u];
        push.mode = 12u;
        push.sampleStep = coarse.offset;
        push.sampleCount = coarse.offset;
        push.displayOffsetFloats = coarse.width;
        push.presenceReserved0 = coarse.height;
        push.ultraHdrSourceMapWidth = coarse.width;
        push.ultraHdrSourceMapHeight = coarse.height;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (coarse.width + 15u) / 16u, (coarse.height + 15u) / 16u, 1u);
        for (std::uint32_t level = fllfLayout.levelCount - 1u; level > 0u; --level) {
            VkBufferMemoryBarrier correctionReady{};
            correctionReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            correctionReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            correctionReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            correctionReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            correctionReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            correctionReady.buffer = fllfCorrection_.buffer;
            correctionReady.size = VK_WHOLE_SIZE;
            vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                                 0u, nullptr, 1u, &correctionReady, 0u, nullptr);
            const auto& src = fllfLayout.levels[level];
            const auto& dst = fllfLayout.levels[level - 1u];
            push.mode = 13u;
            push.sampleStep = src.offset;
            push.sampleCount = dst.offset;
            push.displayOffsetFloats = src.width;
            push.presenceReserved0 = src.height;
            push.ultraHdrSourceMapWidth = dst.width;
            push.ultraHdrSourceMapHeight = dst.height;
            vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
            vkCmdDispatch(commandBuffer_, (dst.width + 15u) / 16u, (dst.height + 15u) / 16u, 1u);
        }
        VkBufferMemoryBarrier finalCorrectionReady{};
        finalCorrectionReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        finalCorrectionReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        finalCorrectionReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        finalCorrectionReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        finalCorrectionReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        finalCorrectionReady.buffer = fllfCorrection_.buffer;
        finalCorrectionReady.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &finalCorrectionReady, 0u, nullptr);
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 3u);
        }
        push.sampleStep = 1u;
        push.sampleCount = 0u;
        push.displayOffsetFloats = 0u;
        push.presenceReserved0 = 0u;
        push.ultraHdrSourceMapWidth = sourceMapWidth;
        push.ultraHdrSourceMapHeight = sourceMapHeight;
    }

    if (portraitRequested) {
        // Pass 7 builds a mask-aware scene-linear background bokeh into a separate resident
        // buffer. Pass 8 composites it back into workingRgb. This happens before Ultra HDR
        // authority capture so SDR and HDR renditions share exactly the same portrait edges.
        push.mode = 7u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                       (request.frameHeight + 15u) / 16u, 1u);
        VkBufferMemoryBarrier blurReady{};
        blurReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        blurReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        blurReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        blurReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        blurReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        blurReady.buffer = portraitBlurRgb_.buffer;
        blurReady.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &blurReady, 0u, nullptr);

        push.mode = 8u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                       (request.frameHeight + 15u) / 16u, 1u);
        VkBufferMemoryBarrier portraitCompositeReady{};
        portraitCompositeReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        portraitCompositeReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        portraitCompositeReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        portraitCompositeReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        portraitCompositeReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        portraitCompositeReady.buffer = workingRgb_.buffer;
        portraitCompositeReady.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &portraitCompositeReady, 0u, nullptr);
        result.portraitEffectApplied = true;
        result.portraitStatus = "GPU_PORTRAIT_APPLIED";
    }

    if (localToneRequested) {
        // Pass 9 builds a quarter-resolution edge-aware scene-linear luma base. The following
        // pointwise tone pass reads this resident base and applies bounded local exposure; no
        // full-frame CPU image or readback is introduced.
        push.mode = 9u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (sourceMapWidth + 15u) / 16u,
                       (sourceMapHeight + 15u) / 16u, 1u);
        VkBufferMemoryBarrier ltmBaseReady{};
        ltmBaseReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        ltmBaseReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        ltmBaseReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        ltmBaseReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        ltmBaseReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        ltmBaseReady.buffer = localToneBase_.buffer;
        ltmBaseReady.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &ltmBaseReady, 0u, nullptr);
    }

    if (ultraHdrRequested) {
        // Capture quarter-resolution HDR luminance BEFORE the in-place SDR tone pass. This is the
        // only HDR authority copy and stays on-device; no full-frame CPU/HDR copy is created.
        push.mode = 4u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (sourceMapWidth + 15u) / 16u,
                       (sourceMapHeight + 15u) / 16u, 1u);
        VkBufferMemoryBarrier hdrAuthorityReady{};
        hdrAuthorityReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        hdrAuthorityReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        hdrAuthorityReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        hdrAuthorityReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hdrAuthorityReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hdrAuthorityReady.buffer = ultraHdrLuma_.buffer;
        hdrAuthorityReady.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &hdrAuthorityReady, 0u, nullptr);
    }

    if (queryPool_ != VK_NULL_HANDLE) {
        if (!fllfRequested) vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 8u);
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, fllfRequested ? 4u : 0u);
    }
    // Phase 5 invariant: in mode 3 this reused field is the boolean RAW FLLF-active flag.
    // It prevents the tone kernel from interpreting the compact fallback descriptor as a
    // full FLLF correction pyramid when policy evidence did not request local processing.
    push.presenceReserved0 = fllfRequested ? 1u : 0u;
    push.mode = 3u;
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                   (request.frameHeight + 15u) / 16u, 1u);
    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, fllfRequested ? 5u : 1u);
    }

    if (perceptualDetailRequested) {
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdResetQueryPool(commandBuffer_, queryPool_, 10u, 2u);
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 10u);
        }
        VkBufferMemoryBarrier toneToPerceptual[2]{};
        toneToPerceptual[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        toneToPerceptual[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        toneToPerceptual[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        toneToPerceptual[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toneToPerceptual[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toneToPerceptual[0].buffer = workingRgb_.buffer;
        toneToPerceptual[0].size = static_cast<VkDeviceSize>(rgbBytes);
        toneToPerceptual[1].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        toneToPerceptual[1].srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        toneToPerceptual[1].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        toneToPerceptual[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toneToPerceptual[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toneToPerceptual[1].buffer = portraitBlurRgb_.buffer;
        toneToPerceptual[1].size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 2u, toneToPerceptual, 0u, nullptr);

        push.mode = 16u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                       (request.frameHeight + 15u) / 16u, 1u);
        VkBufferMemoryBarrier perceptualProposalReady{};
        perceptualProposalReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        perceptualProposalReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        perceptualProposalReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        perceptualProposalReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        perceptualProposalReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        perceptualProposalReady.buffer = portraitBlurRgb_.buffer;
        perceptualProposalReady.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &perceptualProposalReady, 0u, nullptr);

        push.mode = 17u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                       (request.frameHeight + 15u) / 16u, 1u);
        VkBufferMemoryBarrier perceptualCommitted{};
        perceptualCommitted.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        perceptualCommitted.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        perceptualCommitted.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        perceptualCommitted.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        perceptualCommitted.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        perceptualCommitted.buffer = workingRgb_.buffer;
        perceptualCommitted.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &perceptualCommitted, 0u, nullptr);
        if (queryPool_ != VK_NULL_HANDLE) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 11u);
        }
    }

    if (ultraHdrRequested) {
        VkBufferMemoryBarrier tonedRgbReady{};
        tonedRgbReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        tonedRgbReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        tonedRgbReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        tonedRgbReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        tonedRgbReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        tonedRgbReady.buffer = workingRgb_.buffer;
        tonedRgbReady.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &tonedRgbReady, 0u, nullptr);

        // Pass 1: compare GPU-resident HDR and SDR luminance and derive per-pixel log2 gain plus
        // a GPU atomic maximum. No CPU pixel statistics are involved.
        push.mode = 5u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (sourceMapWidth + 15u) / 16u,
                       (sourceMapHeight + 15u) / 16u, 1u);
        VkBufferMemoryBarrier gainReady[2]{};
        gainReady[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        gainReady[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        gainReady[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        gainReady[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        gainReady[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        gainReady[0].buffer = ultraHdrGainLog_.buffer;
        gainReady[0].size = VK_WHOLE_SIZE;
        gainReady[1].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        gainReady[1].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        gainReady[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        gainReady[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        gainReady[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        gainReady[1].buffer = telemetry_.buffer;
        gainReady[1].size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 2u, gainReady, 0u, nullptr);

        // Pass 2: normalize from the GPU-computed maximum, rotate into final JPEG orientation and
        // pack four 8-bit gainmap pixels per uint. The mapped output is already publication-ready.
        push.mode = 6u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (packedWordsPerRow + 15u) / 16u,
                       (outputMapHeight + 15u) / 16u, 1u);
        VkBufferMemoryBarrier gainmapToHost{};
        gainmapToHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        gainmapToHost.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        gainmapToHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        gainmapToHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        gainmapToHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        gainmapToHost.buffer = ultraHdrGainmapPacked_.buffer;
        gainmapToHost.size = static_cast<VkDeviceSize>(packedBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_HOST_BIT, 0u,
                             0u, nullptr, 1u, &gainmapToHost, 0u, nullptr);
    }

    if (!request.deferFullReadback) {
        VkBufferMemoryBarrier toTransfer{};
        toTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toTransfer.buffer = workingRgb_.buffer;
        toTransfer.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr, 1u, &toTransfer, 0u, nullptr);
        VkBufferCopy copy{};
        copy.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdCopyBuffer(commandBuffer_, workingRgb_.buffer, readback_.buffer, 1u, &copy);
        VkBufferMemoryBarrier toHost{};
        toHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toHost.buffer = readback_.buffer;
        toHost.size = static_cast<VkDeviceSize>(rgbBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr, 1u, &toHost, 0u, nullptr);
    }
    VkBufferMemoryBarrier telemetryToHost{};
    telemetryToHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    telemetryToHost.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    telemetryToHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    telemetryToHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    telemetryToHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    telemetryToHost.buffer = telemetry_.buffer;
    telemetryToHost.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr, 1u, &telemetryToHost, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "GPU_TONE_COMMAND_END_FAILED";
        result.failureReason = "vkEndCommandBuffer_failed";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    VkSubmitInfo si{};
    si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si.commandBufferCount = 1u;
    si.pCommandBuffers = &commandBuffer_;
    const auto waitStart = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &si, fence_) != VK_SUCCESS ||
        vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("Tone");
        result.status = "GPU_STALLED";
        result.failureReason = "tone_submit_or_wait_timeout";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    result.synchronizationMs = elapsedMs(waitStart);
    if (queryPool_ != VK_NULL_HANDLE) {
        std::uint64_t ts[6]{};
        const std::uint32_t count = fllfRequested ? 6u : 2u;
        if (vkGetQueryPoolResults(device, queryPool_, 0u, count, sizeof(ts), ts, sizeof(std::uint64_t),
                                  VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(physicalDevice, &props);
            const double ms = static_cast<double>(props.limits.timestampPeriod) / 1.0e6;
            if (fllfRequested) {
                if (ts[1] >= ts[0]) result.fllfPyramidBuildMs = static_cast<float>((ts[1] - ts[0]) * ms);
                if (ts[3] >= ts[2]) result.fllfRemapReconstructMs = static_cast<float>((ts[3] - ts[2]) * ms);
                if (ts[5] >= ts[4]) result.kernelMs = static_cast<float>((ts[5] - ts[4]) * ms);
            } else if (ts[1] >= ts[0]) {
                result.kernelMs = static_cast<float>((ts[1] - ts[0]) * ms);
            }
        }
    }
    if (queryPool_ != VK_NULL_HANDLE && linearDetailRequested) {
        std::uint64_t detailTs[2]{};
        if (vkGetQueryPoolResults(device, queryPool_, 8u, 2u, sizeof(detailTs), detailTs,
                                  sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            detailTs[1] >= detailTs[0]) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(physicalDevice, &props);
            const double ms = static_cast<double>(props.limits.timestampPeriod) / 1.0e6;
            result.linearDetailKernelMs = static_cast<float>((detailTs[1] - detailTs[0]) * ms);
        }
    }
    if (queryPool_ != VK_NULL_HANDLE && perceptualDetailRequested) {
        std::uint64_t perceptualTs[2]{};
        if (vkGetQueryPoolResults(device, queryPool_, 10u, 2u, sizeof(perceptualTs), perceptualTs,
                                  sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            perceptualTs[1] >= perceptualTs[0]) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(physicalDevice, &props);
            const double ms = static_cast<double>(props.limits.timestampPeriod) / 1.0e6;
            result.perceptualDetailKernelMs = static_cast<float>((perceptualTs[1] - perceptualTs[0]) * ms);
        }
    }
    const auto readStart = Clock::now();
    vmaInvalidateAllocation(allocator_, telemetry_.allocation, 0u, kTelemetryWords * sizeof(std::uint32_t));
    const auto* telemetry = static_cast<const std::uint32_t*>(telemetry_.mapped);
    result.highlightNeutralizeApplied = telemetry[1] > 0u;
    result.localToneAdjustedPixels = telemetry[7];
    result.localToneApplied = localToneRequested && result.localToneAdjustedPixels > 0u;
    result.fllfAdjustedPixels = telemetry[14];
    result.fllfEdgeProtectedSamples = telemetry[15];
    const std::uint32_t fllfCorrectionSamples = telemetry[18];
    result.fllfMeanAbsCorrectionEv = fllfCorrectionSamples > 0u
            ? static_cast<float>(telemetry[16]) / (1024.0f * static_cast<float>(fllfCorrectionSamples))
            : 0.0f;
    std::uint32_t fllfMaxBits = telemetry[17];
    std::memcpy(&result.fllfMaxAbsCorrectionEv, &fllfMaxBits, sizeof(float));
    result.fllfPhysicalNoiseSigmaY = std::clamp(
            request.fllfPhysicalNoiseSigmaY, 0.0f, 0.50f);
    result.fllfApplied = fllfRequested && result.fllfAdjustedPixels > 0u;
    result.linearDetailEvaluatedPixels = telemetry[31];
    result.linearDetailChangedPixels = telemetry[32];
    result.linearDetailEdgeSupportedPixels = telemetry[33];
    result.linearDetailNoiseRejectedPixels = telemetry[34];
    result.linearDetailHaloClampedPixels = telemetry[35];
    const std::uint32_t linearDetailCorrectionSamples = telemetry[37];
    result.linearDetailMeanAbsCorrection = linearDetailCorrectionSamples > 0u
            ? static_cast<float>(telemetry[36]) /
                    (65536.0f * static_cast<float>(linearDetailCorrectionSamples))
            : 0.0f;
    std::uint32_t linearDetailMaxBits = telemetry[38];
    std::memcpy(&result.linearDetailMaxAbsCorrection, &linearDetailMaxBits, sizeof(float));
    result.linearDetailApplied = linearDetailRequested && result.linearDetailChangedPixels > 0u;
    result.perceptualDetailEvaluatedPixels = telemetry[48];
    result.perceptualDetailChangedPixels = telemetry[49];
    result.perceptualDetailEdgeSupportedPixels = telemetry[50];
    result.perceptualDetailNoiseRejectedPixels = telemetry[51];
    result.perceptualDetailHaloClampedPixels = telemetry[52];
    const std::uint32_t perceptualCorrectionSamples = telemetry[54];
    result.perceptualDetailMeanAbsCorrection = perceptualCorrectionSamples > 0u
            ? static_cast<float>(telemetry[53]) /
                    (65536.0f * static_cast<float>(perceptualCorrectionSamples))
            : 0.0f;
    std::uint32_t perceptualMaxBits = telemetry[55];
    std::memcpy(&result.perceptualDetailMaxAbsCorrection, &perceptualMaxBits, sizeof(float));
    result.perceptualDetailApplied = perceptualDetailRequested && result.perceptualDetailChangedPixels > 0u;
    if (ultraHdrRequested) {
        constexpr float kMeaningfulGainLog2 = 0.111031312f; // log2(1.08)
        const float maxLog2Boost = static_cast<float>(telemetry[2]) / 65536.0f;
        result.ultraHdrMaxContentBoost = std::exp2(std::clamp(maxLog2Boost, 0.0f, 4.0f));
        result.ultraHdrMeaningfulHeadroom = maxLog2Boost >= kMeaningfulGainLog2;
        result.ultraHdrGainmapWidth = outputMapWidth;
        result.ultraHdrGainmapHeight = outputMapHeight;
        result.ultraHdrGainmapRowStrideBytes = packedWordsPerRow * sizeof(std::uint32_t);
        if (result.ultraHdrMeaningfulHeadroom && ultraHdrGainmapPacked_.mapped != nullptr) {
            vmaInvalidateAllocation(allocator_, ultraHdrGainmapPacked_.allocation, 0u,
                                    static_cast<VkDeviceSize>(packedBytes));
            result.ultraHdrGainmapBytes.resize(static_cast<std::size_t>(packedBytes));
            // GPU already produced final 8-bit pixels and row padding. This is an artifact
            // transfer only; there is no CPU gainmap computation or pixel conversion.
            std::memcpy(result.ultraHdrGainmapBytes.data(), ultraHdrGainmapPacked_.mapped,
                        static_cast<std::size_t>(packedBytes));
            result.ultraHdrGainmapGenerated = true;
            result.ultraHdrStatus = "GPU_GAINMAP_READY";
        } else {
            result.ultraHdrStatus = result.ultraHdrMeaningfulHeadroom
                    ? "GPU_GAINMAP_MAPPED_OUTPUT_UNAVAILABLE"
                    : "NO_MEANINGFUL_HDR_HEADROOM";
        }
    } else if (!request.ultraHdrGainmapRequested) {
        result.ultraHdrStatus = "NOT_REQUESTED";
    }
    if (!request.deferFullReadback) {
        vmaInvalidateAllocation(allocator_, readback_.allocation, 0u, static_cast<VkDeviceSize>(rgbBytes));
        result.outputRgb.resize(static_cast<std::size_t>(pixels) * 3u);
        std::memcpy(result.outputRgb.data(), readback_.mapped, static_cast<std::size_t>(rgbBytes));
    }
    result.readbackMs = elapsedMs(readStart);
    residentToneGeneration_++;
    residentToneValid_ = true;
    residentSceneValid_ = false;
    result.residentToneGeneration = residentToneGeneration_;
    result.residentInputUsed = true;
    result.fullReadbackDeferred = request.deferFullReadback;
    result.success = request.deferFullReadback || result.outputRgb.size() == static_cast<std::size_t>(pixels) * 3u;
    result.status = result.success
            ? (request.deferFullReadback ? "GPU_PRIMARY_TONE_RESIDENT_OUTPUT" : "GPU_PRIMARY_TONE_FINAL_READBACK")
            : "GPU_TONE_READBACK_SIZE_MISMATCH";
    result.failureReason = result.success ? "none" : "FULL_RGB_READBACK_INCOMPLETE";
    result.totalMs = elapsedMs(totalStart);
    return result;
#endif
}

bool VulkanSpectraResidentToneBackend::readbackResidentOutput(
        VkDevice device, VkQueue computeQueue, VulkanAllocatorOwner& allocatorOwner,
        std::uint64_t generation, std::vector<float>& outputRgb,
        std::string& failureReason) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)device; (void)computeQueue; (void)allocatorOwner; (void)generation; (void)outputRgb;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    if (!residentToneValid_ || generation == 0u || generation != residentToneGeneration_ ||
        workingRgb_.buffer == VK_NULL_HANDLE || residentWidth_ == 0u || residentHeight_ == 0u ||
        device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE) {
        failureReason = "RESIDENT_TONE_GENERATION_UNAVAILABLE";
        return false;
    }
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr) {
        failureReason = "AUTHORITATIVE_VMA_ALLOCATOR_NULL";
        return false;
    }
    const std::uint64_t pixels = static_cast<std::uint64_t>(residentWidth_) * residentHeight_;
    const std::uint64_t rgbBytes = pixels * 3u * sizeof(float);
    bool reallocated = false;
    if (!ensureBufferLocked(allocator_, rgbBytes,
            VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT,
            readback_, reallocated, failureReason)) {
        return false;
    }
    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        failureReason = "vkBeginCommandBuffer_tone_readback_failed";
        return false;
    }
    VkBufferMemoryBarrier toTransfer{};
    toTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.buffer = workingRgb_.buffer;
    toTransfer.size = static_cast<VkDeviceSize>(rgbBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0u,
                         0u, nullptr, 1u, &toTransfer, 0u, nullptr);
    VkBufferCopy copy{};
    copy.size = static_cast<VkDeviceSize>(rgbBytes);
    vkCmdCopyBuffer(commandBuffer_, workingRgb_.buffer, readback_.buffer, 1u, &copy);
    VkBufferMemoryBarrier toHost{};
    toHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.buffer = readback_.buffer;
    toHost.size = static_cast<VkDeviceSize>(rgbBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u,
                         0u, nullptr, 1u, &toHost, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        failureReason = "vkEndCommandBuffer_tone_readback_failed";
        return false;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer_;
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS ||
        vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("ToneReadback");
        failureReason = "tone_readback_queue_submit_or_wait_timeout";
        return false;
    }
    vmaInvalidateAllocation(allocator_, readback_.allocation, 0u, static_cast<VkDeviceSize>(rgbBytes));
    try {
        outputRgb.resize(static_cast<std::size_t>(pixels) * 3u);
    } catch (...) {
        failureReason = "tone_readback_cpu_allocation_failed";
        return false;
    }
    std::memcpy(outputRgb.data(), readback_.mapped, static_cast<std::size_t>(rgbBytes));
    failureReason = "none";
    return true;
#endif
}

bool VulkanSpectraResidentToneBackend::resolveResidentOutput(
        std::uint64_t generation, VkBuffer& buffer, std::uint64_t& bytes,
        std::uint32_t& width, std::uint32_t& height) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!residentToneValid_ || generation == 0u || generation != residentToneGeneration_ ||
        workingRgb_.buffer == VK_NULL_HANDLE || residentWidth_ == 0u || residentHeight_ == 0u) {
        return false;
    }
    buffer = workingRgb_.buffer;
    width = residentWidth_;
    height = residentHeight_;
    bytes = static_cast<std::uint64_t>(width) * height * 3u * sizeof(float);
    return true;
}

} // namespace bncam::vulkan
