#include "VulkanSpectraPass3PlannerBackend.h"
#include "VulkanPipelineCacheRegistry.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif

#ifndef BNCAM_SPECTRA_PASS3_PLANNER_SHADER_AVAILABLE
#define BNCAM_SPECTRA_PASS3_PLANNER_SHADER_AVAILABLE 0
#endif
#if BNCAM_SPECTRA_PASS3_PLANNER_SHADER_AVAILABLE
#include "SpectraPass3PlannerSpirv.h"
#endif

#include "VulkanRuntime.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>

namespace bncam::vulkan {
namespace {

using Clock = std::chrono::steady_clock;

float elapsedMs(Clock::time_point started) {
    return static_cast<float>(
            std::chrono::duration<double, std::milli>(Clock::now() - started).count());
}

// Keep the historical 32-byte ABI width used by the generated Vulkan pipeline while removing
// all legacy planner controls. Only frame/CFA/grid dimensions are meaningful.
struct PushConstants {
    std::uint32_t frameWidth = 0u;
    std::uint32_t frameHeight = 0u;
    std::uint32_t cfaPattern = 0u;
    std::uint32_t gridCols = 0u;
    std::uint32_t gridRows = 0u;
    std::uint32_t reserved0 = 0u;
    std::uint32_t reserved1 = 0u;
    std::uint32_t reserved2 = 0u;
};
static_assert(sizeof(PushConstants) == 32u, "RAW noise observer push constant layout mismatch");

bool checkedNoiseMapFloatCount(
        const SpectraNoiseMapPlannerRequest& request,
        std::uint64_t& floatCount,
        std::uint64_t& bytes
) {
    const std::uint64_t cells =
            static_cast<std::uint64_t>(request.gridWidth) *
            static_cast<std::uint64_t>(request.gridHeight);
    constexpr std::uint64_t kFloatsPerCell = 16u;

    if (cells == 0u ||
        cells > std::numeric_limits<std::uint64_t>::max() / kFloatsPerCell) {
        return false;
    }

    floatCount = cells * kFloatsPerCell;
    if (floatCount > std::numeric_limits<std::uint64_t>::max() / sizeof(float)) {
        return false;
    }

    bytes = floatCount * sizeof(float);
    return bytes <= static_cast<std::uint64_t>(
            std::numeric_limits<VkDeviceSize>::max());
}

} // namespace

bool VulkanSpectraPass3PlannerBackend::productionKernelConnected() const noexcept {
#if BNCAM_SPECTRA_PASS3_PLANNER_SHADER_AVAILABLE
    return !getSpectraPass3PlannerSpirv().empty();
#else
    return false;
#endif
}

bool VulkanSpectraPass3PlannerBackend::pipelineInitialized() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return initialized_;
}

bool VulkanSpectraPass3PlannerBackend::ensureOutputLocked(
        VmaAllocator allocator,
        std::uint64_t bytes,
        bool& reallocated,
        std::string& failureReason
) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator;
    (void)bytes;
    (void)reallocated;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u ||
        bytes > static_cast<std::uint64_t>(
                std::numeric_limits<VkDeviceSize>::max())) {
        failureReason = "INVALID_RAW_NOISE_OBSERVER_OUTPUT_BUFFER_REQUEST";
        return false;
    }

    if (output_.buffer != VK_NULL_HANDLE &&
        output_.allocation != nullptr &&
        output_.mapped != nullptr &&
        output_.capacityBytes >= bytes) {
        return true;
    }

    if (output_.buffer != VK_NULL_HANDLE && output_.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, output_.buffer, output_.allocation);
    }
    output_ = {};

    VkBufferCreateInfo info{};
    info.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    info.size = static_cast<VkDeviceSize>(bytes);
    info.usage =
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
            VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    VmaAllocationCreateInfo allocInfo{};
    allocInfo.usage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST;
    allocInfo.flags =
            VMA_ALLOCATION_CREATE_MAPPED_BIT |
            VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;

    VmaAllocationInfo allocationResult{};
    const VkResult create = vmaCreateBuffer(
            allocator,
            &info,
            &allocInfo,
            &output_.buffer,
            &output_.allocation,
            &allocationResult);

    if (create != VK_SUCCESS ||
        output_.buffer == VK_NULL_HANDLE ||
        output_.allocation == nullptr ||
        allocationResult.pMappedData == nullptr) {
        output_ = {};
        failureReason =
                "vmaCreateBuffer_raw_noise_observer_output_failed_" +
                std::to_string(create);
        return false;
    }

    output_.mapped = allocationResult.pMappedData;
    output_.capacityBytes = bytes;
    reallocated = true;
    ++allocationGeneration_;
    return true;
#endif
}

void VulkanSpectraPass3PlannerBackend::destroyLocked(VkDevice device) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr &&
        output_.buffer != VK_NULL_HANDLE &&
        output_.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, output_.buffer, output_.allocation);
    }
#endif
    output_ = {};
    allocator_ = nullptr;

    if (device != VK_NULL_HANDLE) {
        if (queryPool_ != VK_NULL_HANDLE) {
            vkDestroyQueryPool(device, queryPool_, nullptr);
        }
        if (fence_ != VK_NULL_HANDLE) {
            vkDestroyFence(device, fence_, nullptr);
        }
        if (descriptorPool_ != VK_NULL_HANDLE) {
            vkDestroyDescriptorPool(device, descriptorPool_, nullptr);
        }
        if (pipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device, pipeline_, nullptr);
        }
        if (shaderModule_ != VK_NULL_HANDLE) {
            vkDestroyShaderModule(device, shaderModule_, nullptr);
        }
        if (pipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device, pipelineLayout_, nullptr);
        }
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
    descriptorSet_ = VK_NULL_HANDLE;
    commandBuffer_ = VK_NULL_HANDLE;
    fence_ = VK_NULL_HANDLE;
    queryPool_ = VK_NULL_HANDLE;
}

void VulkanSpectraPass3PlannerBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanSpectraPass3PlannerBackend::initializeLocked(
        VkDevice device,
        VkCommandPool commandPool,
        std::string& failureReason
) noexcept {
    if (initialized_ &&
        initializedDevice_ == device &&
        initializedCommandPool_ == commandPool) {
        return true;
    }

    if (initialized_) {
        destroyLocked(initializedDevice_);
    }

#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)device;
    (void)commandPool;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#elif !BNCAM_SPECTRA_PASS3_PLANNER_SHADER_AVAILABLE
    (void)device;
    (void)commandPool;
    failureReason = "RAW_NOISE_OBSERVER_SHADER_NOT_COMPILED";
    return false;
#else
    const auto& spirv = getSpectraPass3PlannerSpirv();
    if (device == VK_NULL_HANDLE ||
        commandPool == VK_NULL_HANDLE ||
        spirv.empty()) {
        failureReason = "RAW_NOISE_OBSERVER_INITIALIZATION_INPUT_INVALID";
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
    if (vkCreateDescriptorSetLayout(
                device, &layoutInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorSetLayout_raw_noise_observer_failed";
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
    if (vkCreatePipelineLayout(
                device, &pipelineLayoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "vkCreatePipelineLayout_raw_noise_observer_failed";
        destroyLocked(device);
        return false;
    }

    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(
                device, &shaderInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_raw_noise_observer_failed";
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
    if (VulkanPipelineCacheRegistry::createComputePipelines(
                device, 1u, &pipelineInfo, nullptr, &pipeline_) != VK_SUCCESS) {
        failureReason = "vkCreateComputePipelines_raw_noise_observer_failed";
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
    if (vkCreateDescriptorPool(
                device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorPool_raw_noise_observer_failed";
        destroyLocked(device);
        return false;
    }

    VkDescriptorSetAllocateInfo setInfo{};
    setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setInfo.descriptorPool = descriptorPool_;
    setInfo.descriptorSetCount = 1u;
    setInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(
                device, &setInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "vkAllocateDescriptorSets_raw_noise_observer_failed";
        destroyLocked(device);
        return false;
    }

    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(
                device, &commandInfo, &commandBuffer_) != VK_SUCCESS) {
        failureReason = "vkAllocateCommandBuffers_raw_noise_observer_failed";
        destroyLocked(device);
        return false;
    }

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence_) != VK_SUCCESS) {
        failureReason = "vkCreateFence_raw_noise_observer_failed";
        destroyLocked(device);
        return false;
    }

    VkQueryPoolCreateInfo queryInfo{};
    queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryInfo.queryCount = 2u;
    if (vkCreateQueryPool(
                device, &queryInfo, nullptr, &queryPool_) != VK_SUCCESS) {
        queryPool_ = VK_NULL_HANDLE;
    }

    initialized_ = true;
    initializedDevice_ = device;
    initializedCommandPool_ = commandPool;
    return true;
#endif
}

void VulkanSpectraPass3PlannerBackend::updateDescriptorSetLocked(
        VkDevice device,
        VkBuffer residentInput
) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    VkDescriptorBufferInfo infos[2]{};
    infos[0].buffer = residentInput;
    infos[0].offset = 0u;
    infos[0].range = VK_WHOLE_SIZE;
    infos[1].buffer = output_.buffer;
    infos[1].offset = 0u;
    infos[1].range = VK_WHOLE_SIZE;

    VkWriteDescriptorSet writes[2]{};
    for (std::uint32_t i = 0u; i < 2u; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, 2u, writes, 0u, nullptr);
#else
    (void)device;
    (void)residentInput;
#endif
}

SpectraNoiseMapPlannerResult VulkanSpectraPass3PlannerBackend::executeNoiseMapFromResident(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        VkBuffer residentInputBuffer,
        std::uint64_t residentInputBytes,
        const SpectraNoiseMapPlannerRequest& request
) noexcept {
    SpectraNoiseMapPlannerResult result{};
    result.attempted = true;
    result.pipelineAvailable = productionKernelConnected();
    const auto totalStart = Clock::now();

#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_PASS3_PLANNER_SHADER_AVAILABLE
    (void)physicalDevice;
    (void)device;
    (void)computeQueue;
    (void)commandPool;
    (void)allocatorOwner;
    (void)residentInputBuffer;
    (void)residentInputBytes;
    (void)request;
    result.status = "RAW_NOISE_OBSERVER_UNAVAILABLE";
    result.failureReason = !BNCAM_VMA_HEADER_AVAILABLE
            ? "VMA_HEADER_NOT_AVAILABLE"
            : "RAW_NOISE_OBSERVER_SHADER_NOT_COMPILED";
    result.totalMs = elapsedMs(totalStart);
    return result;
#else
    if (physicalDevice == VK_NULL_HANDLE ||
        device == VK_NULL_HANDLE ||
        computeQueue == VK_NULL_HANDLE ||
        commandPool == VK_NULL_HANDLE ||
        residentInputBuffer == VK_NULL_HANDLE ||
        request.residentInputGeneration == 0u ||
        request.frameWidth == 0u ||
        request.frameHeight == 0u ||
        request.gridWidth == 0u ||
        request.gridHeight == 0u) {
        result.status = "RAW_NOISE_OBSERVER_INVALID_REQUEST";
        result.failureReason = "resident_input_dimensions_or_grid_invalid";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    const std::uint64_t pixelCount =
            static_cast<std::uint64_t>(request.frameWidth) *
            static_cast<std::uint64_t>(request.frameHeight);
    if (request.frameWidth != 0u &&
        pixelCount / request.frameWidth != request.frameHeight) {
        result.status = "RAW_NOISE_OBSERVER_INPUT_SIZE_OVERFLOW";
        result.failureReason = "frame_pixel_count_overflow";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    if (pixelCount >
        std::numeric_limits<std::uint64_t>::max() / sizeof(float)) {
        result.status = "RAW_NOISE_OBSERVER_INPUT_SIZE_OVERFLOW";
        result.failureReason = "frame_byte_count_overflow";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    const std::uint64_t expectedInputBytes = pixelCount * sizeof(float);
    if (residentInputBytes < expectedInputBytes) {
        result.status = "RAW_NOISE_OBSERVER_RESIDENT_INPUT_SIZE_MISMATCH";
        result.failureReason = "resident_input_smaller_than_frame";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    std::uint64_t floatCount = 0u;
    std::uint64_t outputBytes = 0u;
    if (!checkedNoiseMapFloatCount(request, floatCount, outputBytes)) {
        result.status = "RAW_NOISE_OBSERVER_OUTPUT_SIZE_OVERFLOW";
        result.failureReason = "compact_output_size_invalid";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    const std::uint64_t cellCount64 =
            static_cast<std::uint64_t>(request.gridWidth) *
            static_cast<std::uint64_t>(request.gridHeight);
    if (cellCount64 > std::numeric_limits<std::uint32_t>::max()) {
        result.status = "RAW_NOISE_OBSERVER_GRID_TOO_LARGE";
        result.failureReason = "dispatch_cell_count_exceeds_uint32";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }
    const std::uint32_t cellCount =
            static_cast<std::uint32_t>(cellCount64);

    std::lock_guard<std::mutex> lock(mutex_);

    std::string failure;
    if (!initializeLocked(device, commandPool, failure)) {
        result.status = "RAW_NOISE_OBSERVER_INITIALIZATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    allocator_ = allocatorOwner.handle();
    bool reallocated = false;
    if (!ensureOutputLocked(
                allocator_, outputBytes, reallocated, failure)) {
        result.status = "RAW_NOISE_OBSERVER_BUFFER_ALLOCATION_FAILED";
        result.failureReason = failure;
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentResidentBytes = output_.capacityBytes;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.compactBytes = outputBytes;
    result.residentInputUsed = true;

    updateDescriptorSetLocked(device, residentInputBuffer);

    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);

    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "RAW_NOISE_OBSERVER_COMMAND_RECORDING_FAILED";
        result.failureReason = "vkBeginCommandBuffer";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 2u);
        vkCmdWriteTimestamp(
                commandBuffer_,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                queryPool_,
                0u);
    }

    VkBufferMemoryBarrier inputBarrier{};
    inputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    inputBarrier.srcAccessMask =
            VK_ACCESS_SHADER_WRITE_BIT |
            VK_ACCESS_TRANSFER_WRITE_BIT;
    inputBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    inputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputBarrier.buffer = residentInputBuffer;
    inputBarrier.offset = 0u;
    inputBarrier.size = static_cast<VkDeviceSize>(expectedInputBytes);

    vkCmdPipelineBarrier(
            commandBuffer_,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            0u, nullptr,
            1u, &inputBarrier,
            0u, nullptr);

    vkCmdFillBuffer(
            commandBuffer_,
            output_.buffer,
            0u,
            static_cast<VkDeviceSize>(outputBytes),
            0u);

    VkBufferMemoryBarrier outputClearBarrier{};
    outputClearBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    outputClearBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    outputClearBarrier.dstAccessMask =
            VK_ACCESS_SHADER_READ_BIT |
            VK_ACCESS_SHADER_WRITE_BIT;
    outputClearBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputClearBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputClearBarrier.buffer = output_.buffer;
    outputClearBarrier.offset = 0u;
    outputClearBarrier.size = static_cast<VkDeviceSize>(outputBytes);

    vkCmdPipelineBarrier(
            commandBuffer_,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u,
            0u, nullptr,
            1u, &outputClearBarrier,
            0u, nullptr);

    vkCmdBindPipeline(
            commandBuffer_,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            pipeline_);
    vkCmdBindDescriptorSets(
            commandBuffer_,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            pipelineLayout_,
            0u,
            1u,
            &descriptorSet_,
            0u,
            nullptr);

    PushConstants push{};
    push.frameWidth = request.frameWidth;
    push.frameHeight = request.frameHeight;
    push.cfaPattern = request.cfaPattern;
    push.gridCols = request.gridWidth;
    push.gridRows = request.gridHeight;
    vkCmdPushConstants(
            commandBuffer_,
            pipelineLayout_,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0u,
            sizeof(push),
            &push);

    vkCmdDispatch(commandBuffer_, cellCount, 1u, 1u);

    if (queryPool_ != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(
                commandBuffer_,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                queryPool_,
                1u);
    }

    VkBufferMemoryBarrier outputBarrier{};
    outputBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    outputBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outputBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    outputBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputBarrier.buffer = output_.buffer;
    outputBarrier.offset = 0u;
    outputBarrier.size = static_cast<VkDeviceSize>(outputBytes);

    vkCmdPipelineBarrier(
            commandBuffer_,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_HOST_BIT,
            0u,
            0u, nullptr,
            1u, &outputBarrier,
            0u, nullptr);

    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "RAW_NOISE_OBSERVER_COMMAND_RECORDING_FAILED";
        result.failureReason = "vkEndCommandBuffer";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer_;

    const auto syncStart = Clock::now();
    const VkResult submitResult =
            vkQueueSubmit(computeQueue, 1u, &submit, fence_);
    const VkResult waitResult = submitResult == VK_SUCCESS
            ? vkWaitForFences(
                    device,
                    1u,
                    &fence_,
                    VK_TRUE,
                    1'500'000'000ull)
            : submitResult;

    if (submitResult != VK_SUCCESS || waitResult != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("RawNoiseMapObserver");
        result.status = "GPU_STALLED";
        result.failureReason = "submit_or_wait_timeout";
        result.totalMs = elapsedMs(totalStart);
        return result;
    }

    result.synchronizationMs = elapsedMs(syncStart);

    if (queryPool_ != VK_NULL_HANDLE) {
        std::uint64_t timestamps[2]{};
        if (vkGetQueryPoolResults(
                    device,
                    queryPool_,
                    0u,
                    2u,
                    sizeof(timestamps),
                    timestamps,
                    sizeof(std::uint64_t),
                    VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            timestamps[1] >= timestamps[0]) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physicalDevice, &properties);
            result.kernelMs = static_cast<float>(
                    static_cast<double>(timestamps[1] - timestamps[0]) *
                    static_cast<double>(properties.limits.timestampPeriod) /
                    1.0e6);
        }
    }

    if (!(result.kernelMs > 0.0f)) {
        result.kernelMs = result.synchronizationMs;
    }

    const auto readStart = Clock::now();
    vmaInvalidateAllocation(
            allocator_,
            output_.allocation,
            0u,
            outputBytes);

    const auto* values =
            static_cast<const float*>(output_.mapped);
    const std::size_t tileCount =
            static_cast<std::size_t>(cellCount);
    result.tiles.resize(tileCount);

    for (std::size_t cell = 0u; cell < tileCount; ++cell) {
        const float* record = values + cell * 16u;
        auto& tile = result.tiles[cell];

        for (std::size_t channel = 0u; channel < 4u; ++channel) {
            const float sum = record[channel];
            const float count = record[4u + channel];
            const float minimum = record[8u + channel];
            const float maximum = record[12u + channel];

            tile.signalSum[channel] =
                    std::isfinite(sum)
                    ? std::max(0.0f, sum)
                    : 0.0f;

            tile.sampleCount[channel] =
                    std::isfinite(count)
                    ? static_cast<std::uint32_t>(
                            std::max(0.0f, std::round(count)))
                    : 0u;

            tile.signalMin[channel] =
                    std::isfinite(minimum)
                    ? std::clamp(minimum, 0.0f, 1.0f)
                    : 0.0f;

            tile.signalMax[channel] =
                    std::isfinite(maximum)
                    ? std::clamp(maximum, 0.0f, 1.0f)
                    : 0.0f;
        }
    }

    result.readbackMs = elapsedMs(readStart);
    result.success = result.tiles.size() == tileCount;
    result.status = result.success
            ? "RAW_NOISE_OBSERVER_GPU_EXACT_TILE_REDUCTION_READY"
            : "RAW_NOISE_OBSERVER_COMPACT_PARSE_MISMATCH";
    if (!result.success) {
        result.failureReason = "compact_output_parse_mismatch";
    }
    result.totalMs = elapsedMs(totalStart);
    return result;
#endif
}

} // namespace bncam::vulkan
