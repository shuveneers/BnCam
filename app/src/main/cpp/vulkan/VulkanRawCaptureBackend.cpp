#include "VulkanRawCaptureBackend.h"
#include "VulkanPipelineCacheRegistry.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE
#define BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE 0
#endif
#if BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE
#include "RawCaptureCanonicalizeSpirv.h"
#endif

#include <algorithm>
#include <chrono>
#include <cstring>
#include <limits>

namespace bncam::vulkan {
namespace {
using Clock = std::chrono::steady_clock;

float elapsedMs(const Clock::time_point& start) {
    return std::chrono::duration<float, std::milli>(Clock::now() - start).count();
}

std::uint64_t align4(std::uint64_t value) {
    return (value + 3u) & ~std::uint64_t{3u};
}

std::uint32_t packedRaw10RowBytes(std::uint32_t width) {
    return ((width + 3u) / 4u) * 5u;
}

struct PushConstants {
    std::uint32_t sourceWidth = 0u;
    std::uint32_t sourceHeight = 0u;
    std::uint32_t outputWidth = 0u;
    std::uint32_t outputHeight = 0u;
    std::uint32_t inputRowStrideBytes = 0u;
    std::uint32_t inputPixelStrideBytes = 0u;
    std::uint32_t cropLeft = 0u;
    std::uint32_t cropTop = 0u;
    std::uint32_t sourceFormat = 0u;
    std::uint32_t nativeWhite = 1u;
    std::uint32_t payloadWhite = 1u;
    std::uint32_t nativeBlack0 = 0u;
    std::uint32_t nativeBlack1 = 0u;
    std::uint32_t nativeBlack2 = 0u;
    std::uint32_t nativeBlack3 = 0u;
    std::uint32_t payloadBlack0 = 0u;
    std::uint32_t payloadBlack1 = 0u;
    std::uint32_t payloadBlack2 = 0u;
    std::uint32_t payloadBlack3 = 0u;
};
static_assert(sizeof(PushConstants) == 76u, "RAW capture canonicalize push constant layout mismatch");

class AhbReadLock final {
public:
    AhbReadLock() = default;
    ~AhbReadLock() { release(); }

    AhbReadLock(const AhbReadLock&) = delete;
    AhbReadLock& operator=(const AhbReadLock&) = delete;

    bool acquire(AHardwareBuffer* buffer, RawCaptureSourceFormat format, std::string& failureReason) noexcept {
        if (buffer == nullptr) {
            failureReason = "RAW_CAPTURE_AHB_NULL";
            return false;
        }
        buffer_ = buffer;
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(buffer, &desc);
        width = desc.width;
        height = desc.height;
        if (width == 0u || height == 0u) {
            failureReason = "RAW_CAPTURE_AHB_DIMENSIONS_INVALID";
            buffer_ = nullptr;
            return false;
        }

        AHardwareBuffer_Planes planes{};
        const int planesStatus = AHardwareBuffer_lockPlanes(
                buffer,
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                -1,
                nullptr,
                &planes);
        if (planesStatus == 0 && planes.planeCount >= 1u && planes.planes[0].data != nullptr) {
            data = static_cast<const std::uint8_t*>(planes.planes[0].data);
            rowStrideBytes = static_cast<std::uint32_t>(planes.planes[0].rowStride);
            pixelStrideBytes = static_cast<std::uint32_t>(planes.planes[0].pixelStride);
            if (format == RawCaptureSourceFormat::RAW10) {
                if (rowStrideBytes == 0u) rowStrideBytes = packedRaw10RowBytes(width);
                pixelStrideBytes = 0u; // Android RAW10 reports pixelStride=0 by contract.
            } else {
                if (pixelStrideBytes == 0u) pixelStrideBytes = 2u;
                if (rowStrideBytes == 0u) {
                    rowStrideBytes = std::max(desc.width, desc.stride) * pixelStrideBytes;
                }
            }
            locked_ = true;
            return true;
        }

        void* flat = nullptr;
        const int lockStatus = AHardwareBuffer_lock(
                buffer,
                AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                -1,
                nullptr,
                &flat);
        if (lockStatus != 0 || flat == nullptr) {
            failureReason = "RAW_CAPTURE_AHB_LOCK_FAILED_" + std::to_string(lockStatus);
            buffer_ = nullptr;
            return false;
        }
        data = static_cast<const std::uint8_t*>(flat);
        if (format == RawCaptureSourceFormat::RAW10) {
            rowStrideBytes = packedRaw10RowBytes(std::max(desc.width, desc.stride));
            pixelStrideBytes = 0u;
        } else {
            pixelStrideBytes = 2u;
            rowStrideBytes = std::max(desc.width, desc.stride) * pixelStrideBytes;
        }
        locked_ = true;
        return true;
    }

    void release() noexcept {
        if (locked_ && buffer_ != nullptr) {
            AHardwareBuffer_unlock(buffer_, nullptr);
        }
        buffer_ = nullptr;
        data = nullptr;
        locked_ = false;
    }

    const std::uint8_t* data = nullptr;
    std::uint32_t width = 0u;
    std::uint32_t height = 0u;
    std::uint32_t rowStrideBytes = 0u;
    std::uint32_t pixelStrideBytes = 0u;

private:
    AHardwareBuffer* buffer_ = nullptr;
    bool locked_ = false;
};
} // namespace

bool VulkanRawCaptureBackend::ensureBufferLocked(
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
    if (allocator == nullptr || bytes == 0u ||
        bytes > static_cast<std::uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        failureReason = "RAW_CAPTURE_BUFFER_REQUEST_INVALID";
        return false;
    }
    if (target.buffer != VK_NULL_HANDLE && target.allocation != nullptr &&
        target.capacityBytes >= bytes && (!hostVisible || target.mapped != nullptr)) {
        return true;
    }
    if (target.buffer != VK_NULL_HANDLE && target.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, target.buffer, target.allocation);
    }
    target = {};

    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
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
    VmaAllocationInfo allocationResult{};
    const VkResult result = vmaCreateBuffer(
            allocator,
            &bufferInfo,
            &allocationInfo,
            &target.buffer,
            &target.allocation,
            &allocationResult);
    if (result != VK_SUCCESS || target.buffer == VK_NULL_HANDLE || target.allocation == nullptr ||
        (hostVisible && allocationResult.pMappedData == nullptr)) {
        target = {};
        failureReason = "RAW_CAPTURE_VMA_BUFFER_FAILED_" + std::to_string(result);
        return false;
    }
    target.mapped = allocationResult.pMappedData;
    target.capacityBytes = bytes;
    return true;
#endif
}

void VulkanRawCaptureBackend::destroyLocked(VkDevice device) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr) {
        for (PersistentBuffer* buffer : {&staging_, &canonical_, &readback_}) {
            if (buffer->buffer != VK_NULL_HANDLE && buffer->allocation != nullptr) {
                vmaDestroyBuffer(allocator_, buffer->buffer, buffer->allocation);
            }
            *buffer = {};
        }
    }
#else
    staging_ = {};
    canonical_ = {};
    readback_ = {};
    residentOutputGeneration_ = 0u;
    residentOutputBytes_ = 0u;
    residentOutputWidth_ = 0u;
    residentOutputHeight_ = 0u;
#endif
    residentOutputGeneration_ = 0u;
    residentOutputBytes_ = 0u;
    residentOutputWidth_ = 0u;
    residentOutputHeight_ = 0u;
    allocator_ = nullptr;
    if (device != VK_NULL_HANDLE) {
        if (descriptorPool_ != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, descriptorPool_, nullptr);
        if (pipeline_ != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline_, nullptr);
        if (shaderModule_ != VK_NULL_HANDLE) vkDestroyShaderModule(device, shaderModule_, nullptr);
        if (pipelineLayout_ != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout_, nullptr);
        if (descriptorSetLayout_ != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, descriptorSetLayout_, nullptr);
    }
    descriptorPool_ = VK_NULL_HANDLE;
    descriptorSet_ = VK_NULL_HANDLE;
    pipeline_ = VK_NULL_HANDLE;
    shaderModule_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    descriptorSetLayout_ = VK_NULL_HANDLE;
    initializedDevice_ = VK_NULL_HANDLE;
    initialized_ = false;
}

void VulkanRawCaptureBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanRawCaptureBackend::ensureInitializedLocked(VkDevice device, std::string& failureReason) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device) return true;
        failureReason = "RAW_CAPTURE_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE
    (void)device;
    failureReason = "RAW_CAPTURE_CANONICALIZE_SHADER_NOT_COMPILED";
    return false;
#else
    const auto& spirv = getRawCaptureCanonicalizeSpirv();
    if (device == VK_NULL_HANDLE || spirv.empty()) {
        failureReason = "RAW_CAPTURE_INITIALIZATION_INPUT_INVALID";
        return false;
    }

    VkDescriptorSetLayoutBinding bindings[2]{};
    for (std::uint32_t i = 0u; i < 2u; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 2u;
    layoutInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device, &layoutInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "RAW_CAPTURE_DESCRIPTOR_LAYOUT_FAILED";
        destroyLocked(device);
        return false;
    }

    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0u;
    pushRange.size = sizeof(PushConstants);
    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1u;
    pipelineLayoutInfo.pSetLayouts = &descriptorSetLayout_;
    pipelineLayoutInfo.pushConstantRangeCount = 1u;
    pipelineLayoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device, &pipelineLayoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "RAW_CAPTURE_PIPELINE_LAYOUT_FAILED";
        destroyLocked(device);
        return false;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "RAW_CAPTURE_SHADER_MODULE_FAILED";
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
    if (VulkanPipelineCacheRegistry::createComputePipelines(device, 1u, &pipelineInfo, nullptr, &pipeline_) != VK_SUCCESS) {
        failureReason = "RAW_CAPTURE_PIPELINE_FAILED";
        destroyLocked(device);
        return false;
    }

    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 2u;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1u;
    poolInfo.poolSizeCount = 1u;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        failureReason = "RAW_CAPTURE_DESCRIPTOR_POOL_FAILED";
        destroyLocked(device);
        return false;
    }
    VkDescriptorSetAllocateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setInfo.descriptorPool = descriptorPool_;
    setInfo.descriptorSetCount = 1u;
    setInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &setInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "RAW_CAPTURE_DESCRIPTOR_SET_FAILED";
        destroyLocked(device);
        return false;
    }

    initializedDevice_ = device;
    initialized_ = true;
    return true;
#endif
}


bool VulkanRawCaptureBackend::resolveResidentOutput(
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
        canonical_.buffer == VK_NULL_HANDLE || residentOutputBytes_ == 0u ||
        residentOutputWidth_ == 0u || residentOutputHeight_ == 0u) {
        return false;
    }
    buffer = canonical_.buffer;
    bytes = residentOutputBytes_;
    width = residentOutputWidth_;
    height = residentOutputHeight_;
    return true;
}

RawCaptureCanonicalizeResult VulkanRawCaptureBackend::canonicalize(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const RawCaptureCanonicalizeRequest& request) noexcept {
    RawCaptureCanonicalizeResult result{};
    result.attempted = true;
    const auto totalStart = Clock::now();
    (void)physicalDevice;

#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)device; (void)computeQueue; (void)commandPool; (void)allocatorOwner; (void)request;
    result.failureReason = "VMA_HEADER_NOT_AVAILABLE";
    result.totalMs = elapsedMs(totalStart);
    return result;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    if (device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE ||
        request.hardwareBuffer == nullptr || request.cropWidth == 0u || request.cropHeight == 0u ||
        request.nativeWhite == 0u || request.payloadWhite == 0u || !allocatorOwner.isReady()) {
        result.failureReason = "RAW_CAPTURE_REQUEST_INVALID";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    if (!ensureInitializedLocked(device, result.failureReason)) {
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    const VmaAllocator requestAllocator = allocatorOwner.handle();
    if (allocator_ != nullptr && allocator_ != requestAllocator) {
        result.failureReason = "RAW_CAPTURE_ALLOCATOR_OWNERSHIP_CHANGED";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    allocator_ = requestAllocator;

    AhbReadLock source;
    const auto stagingStart = Clock::now();
    if (!source.acquire(request.hardwareBuffer, request.sourceFormat, result.failureReason)) {
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    if (request.cropLeft + request.cropWidth > source.width ||
        request.cropTop + request.cropHeight > source.height) {
        result.failureReason = "RAW_CAPTURE_CROP_OUT_OF_BOUNDS";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    if (request.sourceFormat == RawCaptureSourceFormat::RAW10) {
        const std::uint32_t minimumRow = packedRaw10RowBytes(source.width);
        if (source.rowStrideBytes < minimumRow) {
            result.failureReason = "RAW_CAPTURE_RAW10_ROW_STRIDE_INVALID";
            result.totalMs = elapsedMs(totalStart);
            return result;
        }
    } else {
        if (source.pixelStrideBytes < 2u ||
            source.rowStrideBytes < (source.width - 1u) * source.pixelStrideBytes + 2u) {
            result.failureReason = "RAW_CAPTURE_RAW_SENSOR_LAYOUT_INVALID";
            result.totalMs = elapsedMs(totalStart);
            return result;
        }
    }

    const std::uint32_t sourceWidth = source.width;
    const std::uint32_t sourceHeight = source.height;
    const std::uint32_t sourceRowStrideBytes = source.rowStrideBytes;
    const std::uint32_t sourcePixelStrideBytes = source.pixelStrideBytes;
    const std::uint64_t sourceBytes = static_cast<std::uint64_t>(sourceRowStrideBytes) * sourceHeight;
    const std::uint64_t pixelCount = static_cast<std::uint64_t>(request.cropWidth) * request.cropHeight;
    if (pixelCount == 0u || pixelCount > (std::numeric_limits<std::uint64_t>::max() / 2u)) {
        result.failureReason = "RAW_CAPTURE_OUTPUT_SIZE_OVERFLOW";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    const std::uint64_t outputBytes = pixelCount * sizeof(std::uint16_t);
    const std::uint64_t stagingBytes = align4(sourceBytes);
    const std::uint64_t canonicalBytes = align4(outputBytes);
    std::string bufferFailure;
    if (!ensureBufferLocked(allocator_, stagingBytes, true, staging_, bufferFailure) ||
        !ensureBufferLocked(allocator_, canonicalBytes, false, canonical_, bufferFailure) ||
        !ensureBufferLocked(allocator_, canonicalBytes, true, readback_, bufferFailure)) {
        result.failureReason = bufferFailure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    std::memcpy(staging_.mapped, source.data, static_cast<std::size_t>(sourceBytes));
    if (stagingBytes > sourceBytes) {
        std::memset(static_cast<std::uint8_t*>(staging_.mapped) + sourceBytes, 0,
                    static_cast<std::size_t>(stagingBytes - sourceBytes));
    }
    source.release();
    vmaFlushAllocation(allocator_, staging_.allocation, 0u, static_cast<VkDeviceSize>(stagingBytes));
    result.inputStagingMs = elapsedMs(stagingStart);
    result.sourceRowStrideBytes = sourceRowStrideBytes;
    result.sourcePixelStrideBytes = sourcePixelStrideBytes;
    result.sourceBytes = sourceBytes;
    result.cpuStagingCopyBytes = sourceBytes;
    result.inputImportPath = "AHB_CPU_BYTE_STAGING_TO_VULKAN";

    VkDescriptorBufferInfo inputInfo{};
    inputInfo.buffer = staging_.buffer;
    inputInfo.offset = 0u;
    inputInfo.range = static_cast<VkDeviceSize>(stagingBytes);
    VkDescriptorBufferInfo outputInfo{};
    outputInfo.buffer = canonical_.buffer;
    outputInfo.offset = 0u;
    outputInfo.range = static_cast<VkDeviceSize>(canonicalBytes);
    VkWriteDescriptorSet writes[2]{};
    for (std::uint32_t i = 0u; i < 2u; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    }
    writes[0].pBufferInfo = &inputInfo;
    writes[1].pBufferInfo = &outputInfo;
    vkUpdateDescriptorSets(device, 2u, writes, 0u, nullptr);

    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkCommandBufferAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocateInfo.commandPool = commandPool;
    allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocateInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &allocateInfo, &commandBuffer) != VK_SUCCESS) {
        result.failureReason = "RAW_CAPTURE_COMMAND_BUFFER_ALLOCATE_FAILED";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) {
        vkFreeCommandBuffers(device, commandPool, 1u, &commandBuffer);
        result.failureReason = "RAW_CAPTURE_FENCE_CREATE_FAILED";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    const auto gpuStart = Clock::now();
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer, &beginInfo) != VK_SUCCESS) {
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1u, &commandBuffer);
        result.failureReason = "RAW_CAPTURE_COMMAND_BEGIN_FAILED";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    VkBufferMemoryBarrier hostToCompute{};
    hostToCompute.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    hostToCompute.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    hostToCompute.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    hostToCompute.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostToCompute.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostToCompute.buffer = staging_.buffer;
    hostToCompute.offset = 0u;
    hostToCompute.size = static_cast<VkDeviceSize>(stagingBytes);
    vkCmdPipelineBarrier(commandBuffer,
                         VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         0u, 0u, nullptr, 1u, &hostToCompute, 0u, nullptr);

    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                            0u, 1u, &descriptorSet_, 0u, nullptr);
    PushConstants push{};
    push.sourceWidth = sourceWidth;
    push.sourceHeight = sourceHeight;
    push.outputWidth = request.cropWidth;
    push.outputHeight = request.cropHeight;
    push.inputRowStrideBytes = sourceRowStrideBytes;
    push.inputPixelStrideBytes = sourcePixelStrideBytes;
    push.cropLeft = request.cropLeft;
    push.cropTop = request.cropTop;
    push.sourceFormat = static_cast<std::uint32_t>(request.sourceFormat);
    push.nativeWhite = request.nativeWhite;
    push.payloadWhite = request.payloadWhite;
    push.nativeBlack0 = request.nativeBlack[0];
    push.nativeBlack1 = request.nativeBlack[1];
    push.nativeBlack2 = request.nativeBlack[2];
    push.nativeBlack3 = request.nativeBlack[3];
    push.payloadBlack0 = request.payloadBlack[0];
    push.payloadBlack1 = request.payloadBlack[1];
    push.payloadBlack2 = request.payloadBlack[2];
    push.payloadBlack3 = request.payloadBlack[3];
    vkCmdPushConstants(commandBuffer, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                       0u, sizeof(PushConstants), &push);
    const std::uint64_t outputWords = (pixelCount + 1u) / 2u;
    const std::uint32_t groups = static_cast<std::uint32_t>((outputWords + 255u) / 256u);
    vkCmdDispatch(commandBuffer, groups, 1u, 1u);

    VkBufferMemoryBarrier computeToTransfer{};
    computeToTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    computeToTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    computeToTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    computeToTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    computeToTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    computeToTransfer.buffer = canonical_.buffer;
    computeToTransfer.offset = 0u;
    computeToTransfer.size = static_cast<VkDeviceSize>(canonicalBytes);
    vkCmdPipelineBarrier(commandBuffer,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0u, 0u, nullptr, 1u, &computeToTransfer, 0u, nullptr);

    VkBufferCopy copy{};
    copy.size = static_cast<VkDeviceSize>(outputBytes);
    vkCmdCopyBuffer(commandBuffer, canonical_.buffer, readback_.buffer, 1u, &copy);

    VkBufferMemoryBarrier transferToHost{};
    transferToHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    transferToHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    transferToHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    transferToHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    transferToHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    transferToHost.buffer = readback_.buffer;
    transferToHost.offset = 0u;
    transferToHost.size = static_cast<VkDeviceSize>(outputBytes);
    vkCmdPipelineBarrier(commandBuffer,
                         VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT,
                         0u, 0u, nullptr, 1u, &transferToHost, 0u, nullptr);

    const VkResult endResult = vkEndCommandBuffer(commandBuffer);
    if (endResult != VK_SUCCESS) {
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1u, &commandBuffer);
        result.failureReason = "RAW_CAPTURE_COMMAND_END_FAILED_" + std::to_string(endResult);
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer;
    const VkResult submitResult = vkQueueSubmit(computeQueue, 1u, &submit, fence);
    if (submitResult != VK_SUCCESS) {
        vkDestroyFence(device, fence, nullptr);
        vkFreeCommandBuffers(device, commandPool, 1u, &commandBuffer);
        result.failureReason = "RAW_CAPTURE_QUEUE_SUBMIT_FAILED_" + std::to_string(submitResult);
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    // Bounded failure protection only. Capture synchronization remains the actual fence completion,
    // not an arbitrary sleep/retry loop.
    const VkResult waitResult = vkWaitForFences(device, 1u, &fence, VK_TRUE, 3'000'000'000ull);
    result.gpuKernelAndSyncMs = elapsedMs(gpuStart);
    if (waitResult != VK_SUCCESS) {
        // The queue accepted this submission, so a timeout/error cannot prove that the command
        // buffer or its resources are no longer in use. Deliberately retain them and tell
        // VulkanRuntime to quarantine the in-flight submission instead of destroying live GPU
        // resources or pretending the submission completed. The process owner reclaims them.
        result.submissionMayRemainInFlight = true;
        result.failureReason = waitResult == VK_TIMEOUT
                ? "RAW_CAPTURE_GPU_TIMEOUT"
                : "RAW_CAPTURE_GPU_WAIT_UNSAFE_" + std::to_string(waitResult);
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, commandPool, 1u, &commandBuffer);

    const auto readbackStart = Clock::now();
    vmaInvalidateAllocation(allocator_, readback_.allocation, 0u, static_cast<VkDeviceSize>(outputBytes));
    result.outputRaw16.resize(static_cast<std::size_t>(pixelCount));
    std::memcpy(result.outputRaw16.data(), readback_.mapped, static_cast<std::size_t>(outputBytes));
    result.readbackMs = elapsedMs(readbackStart);
    result.fullFrameGpuReadbackBytes = outputBytes;
    result.width = request.cropWidth;
    result.height = request.cropHeight;
    const std::uint64_t requestedGeneration = request.generationId;
    const std::uint64_t residentGeneration = requestedGeneration != 0u
            ? requestedGeneration
            : ++generationCounter_;
    residentOutputGeneration_ = residentGeneration;
    residentOutputBytes_ = outputBytes;
    residentOutputWidth_ = request.cropWidth;
    residentOutputHeight_ = request.cropHeight;
    result.residentOutputProduced = true;
    result.residentOutputGeneration = residentGeneration;
    result.residentOutputBytes = outputBytes;
    result.rawUnpackBackend = "VULKAN_RAW_CAPTURE_CANONICALIZE";
    result.cpuFullFrameRawUnpack = false;
    result.success = true;
    result.failureReason = "none";
    result.totalMs = elapsedMs(totalStart);
    return result;
#endif
}

} // namespace bncam::vulkan
