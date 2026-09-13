#include "VulkanNeuralRawProductionBridge.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_NEURAL_MOSAIC_BRIDGE_SHADER_AVAILABLE
#define BNCAM_NEURAL_MOSAIC_BRIDGE_SHADER_AVAILABLE 0
#endif
#if BNCAM_NEURAL_MOSAIC_BRIDGE_SHADER_AVAILABLE
#include "NeuralMosaicBridgeSpirv.h"
#endif

#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <limits>
#include <type_traits>

namespace bncam::vulkan::neural {
namespace {
using Clock = std::chrono::steady_clock;

struct BridgePushConstants {
    std::uint32_t rawWidth = 0u;
    std::uint32_t rawHeight = 0u;
    std::uint32_t packedWidth = 0u;
    std::uint32_t packedHeight = 0u;
    std::uint32_t mode = 0u;
    std::uint32_t off0x = 0u, off0y = 0u;
    std::uint32_t off1x = 0u, off1y = 0u;
    std::uint32_t off2x = 0u, off2y = 0u;
    std::uint32_t off3x = 0u, off3y = 0u;
    float shotS0 = 0.0f, shotS1 = 0.0f, shotS2 = 0.0f, shotS3 = 0.0f;
    float readO0 = 0.0f, readO1 = 0.0f, readO2 = 0.0f, readO3 = 0.0f;
    float adaptiveFullEvidenceSnr = bncam::spectra::neural::kNeuralAdaptiveFullEvidenceSnr;
    float adaptiveIdentitySnr = bncam::spectra::neural::kNeuralAdaptiveIdentitySnr;
};
static_assert(sizeof(BridgePushConstants) == 92u, "neural mosaic bridge push layout");

float elapsedMs(Clock::time_point start) noexcept {
    return std::chrono::duration<float, std::milli>(Clock::now() - start).count();
}

std::uint64_t fp32Bytes(std::uint32_t w, std::uint32_t h, std::uint32_t c) noexcept {
    return static_cast<std::uint64_t>(w) * h * c * sizeof(float);
}

template<class T>
std::uint64_t toToken(T handle) noexcept {
    if constexpr (std::is_pointer_v<T>) {
        return static_cast<std::uint64_t>(reinterpret_cast<std::uintptr_t>(handle));
    } else {
        return static_cast<std::uint64_t>(handle);
    }
}

bncam::spectra::neural::NeuralResourceView makeBufferView(
        VkBuffer buffer, bncam::spectra::neural::NeuralElementType type,
        bncam::spectra::neural::NeuralResourceAccess access,
        std::uint32_t width, std::uint32_t height, std::uint32_t channels,
        std::uint32_t bytesPerElement) noexcept {
    bncam::spectra::neural::NeuralResourceView out{};
    out.kind = bncam::spectra::neural::NeuralResourceKind::VulkanBuffer;
    out.elementType = type;
    out.access = access;
    out.token = toToken(buffer);
    out.width = width;
    out.height = height;
    out.channels = channels;
    out.rowStrideBytes = width * channels * bytesPerElement;
    return out;
}
} // namespace

bool VulkanNeuralRawProductionBridge::ensureBufferLocked(
        VmaAllocator allocator, std::uint64_t bytes, bool hostVisible,
        Buffer& buffer, std::string& failure) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)hostVisible; (void)buffer;
    failure = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr) {
        failure = "NEURAL_PRODUCTION_ALLOCATOR_UNAVAILABLE";
        return false;
    }
    if (bytes == 0u) {
        failure = "NEURAL_PRODUCTION_BUFFER_SIZE_ZERO";
        return false;
    }
    if (bytes > static_cast<std::uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        failure = "NEURAL_PRODUCTION_BUFFER_SIZE_OVERFLOW";
        return false;
    }
    if (buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr &&
        buffer.capacityBytes >= bytes && (!hostVisible || buffer.mapped != nullptr)) {
        return true;
    }
    freeBufferLocked(buffer);

    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size = static_cast<VkDeviceSize>(bytes);
    bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
               VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VmaAllocationCreateInfo ai{};
    ai.usage = hostVisible ? VMA_MEMORY_USAGE_AUTO_PREFER_HOST : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    if (hostVisible) {
        ai.flags = VMA_ALLOCATION_CREATE_MAPPED_BIT | VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    }
    VmaAllocationInfo info{};
    VkResult vr = vmaCreateBuffer(allocator, &bi, &ai, &buffer.buffer, &buffer.allocation, &info);
    if (vr != VK_SUCCESS || buffer.buffer == VK_NULL_HANDLE || buffer.allocation == nullptr ||
        (hostVisible && info.pMappedData == nullptr)) {
        buffer = {};
        failure = "NEURAL_PRODUCTION_VMA_BUFFER_FAILED_" + std::to_string(vr);
        return false;
    }
    buffer.mapped = info.pMappedData;
    buffer.capacityBytes = bytes;
    return true;
#endif
}

void VulkanNeuralRawProductionBridge::freeBufferLocked(Buffer& buffer) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr && buffer.buffer != VK_NULL_HANDLE && buffer.allocation != nullptr) {
        vmaDestroyBuffer(allocator_, buffer.buffer, buffer.allocation);
    }
#endif
    buffer = {};
}

bool VulkanNeuralRawProductionBridge::initializeLocked(
        VkDevice device, VkCommandPool commandPool, std::string& failure) noexcept {
#if !BNCAM_NEURAL_MOSAIC_BRIDGE_SHADER_AVAILABLE
    (void)device; (void)commandPool;
    failure = "NEURAL_MOSAIC_BRIDGE_SHADER_UNAVAILABLE";
    return false;
#else
    if (device_ == device && commandPool_ == commandPool && pipeline_ != VK_NULL_HANDLE) return true;
    destroyLocked(device_);
    if (device == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE) {
        failure = "NEURAL_PRODUCTION_VULKAN_HANDLES_INVALID";
        return false;
    }

    std::array<VkDescriptorSetLayoutBinding,4> bindings{};
    for (std::uint32_t i=0;i<4u;++i) {
        bindings[i].binding=i; bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount=1u; bindings[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo dsl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dsl.bindingCount=static_cast<std::uint32_t>(bindings.size()); dsl.pBindings=bindings.data();
    if (vkCreateDescriptorSetLayout(device,&dsl,nullptr,&descriptorSetLayout_) != VK_SUCCESS) {
        failure="NEURAL_PRODUCTION_DESCRIPTOR_LAYOUT_FAILED"; destroyLocked(device); return false;
    }
    VkPushConstantRange pcr{}; pcr.stageFlags=VK_SHADER_STAGE_COMPUTE_BIT; pcr.size=sizeof(BridgePushConstants);
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount=1u; pli.pSetLayouts=&descriptorSetLayout_; pli.pushConstantRangeCount=1u; pli.pPushConstantRanges=&pcr;
    if (vkCreatePipelineLayout(device,&pli,nullptr,&pipelineLayout_) != VK_SUCCESS) {
        failure="NEURAL_PRODUCTION_PIPELINE_LAYOUT_FAILED"; destroyLocked(device); return false;
    }
    const auto& words=getNeuralMosaicBridgeSpirv();
    if (words.empty()) { failure="NEURAL_PRODUCTION_SHADER_EMPTY"; destroyLocked(device); return false; }
    VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; sm.codeSize=words.size()*sizeof(std::uint32_t); sm.pCode=words.data();
    if (vkCreateShaderModule(device,&sm,nullptr,&shaderModule_) != VK_SUCCESS) {
        failure="NEURAL_PRODUCTION_SHADER_MODULE_FAILED"; destroyLocked(device); return false;
    }
    VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stage.stage=VK_SHADER_STAGE_COMPUTE_BIT; stage.module=shaderModule_; stage.pName="main";
    VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO}; ci.stage=stage; ci.layout=pipelineLayout_;
    if (vkCreateComputePipelines(device,VK_NULL_HANDLE,1u,&ci,nullptr,&pipeline_) != VK_SUCCESS) {
        failure="NEURAL_PRODUCTION_PIPELINE_FAILED"; destroyLocked(device); return false;
    }
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,4u};
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets=1u; dpi.poolSizeCount=1u; dpi.pPoolSizes=&ps;
    if (vkCreateDescriptorPool(device,&dpi,nullptr,&descriptorPool_) != VK_SUCCESS) {
        failure="NEURAL_PRODUCTION_DESCRIPTOR_POOL_FAILED"; destroyLocked(device); return false;
    }
    VkDescriptorSetAllocateInfo dai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; dai.descriptorPool=descriptorPool_; dai.descriptorSetCount=1u; dai.pSetLayouts=&descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device,&dai,&descriptorSet_) != VK_SUCCESS) {
        failure="NEURAL_PRODUCTION_DESCRIPTOR_ALLOC_FAILED"; destroyLocked(device); return false;
    }
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; cai.commandPool=commandPool; cai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; cai.commandBufferCount=1u;
    if (vkAllocateCommandBuffers(device,&cai,&commandBuffer_) != VK_SUCCESS) {
        failure="NEURAL_PRODUCTION_COMMAND_ALLOC_FAILED"; destroyLocked(device); return false;
    }
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(device,&fi,nullptr,&fence_) != VK_SUCCESS) {
        failure="NEURAL_PRODUCTION_FENCE_FAILED"; destroyLocked(device); return false;
    }
    device_=device; commandPool_=commandPool;
    return true;
#endif
}

bool VulkanNeuralRawProductionBridge::uploadRemainingLscLocked(
        VmaAllocator allocator, const NeuralProductionGpuRequest& request,
        std::uint64_t& uploadedBytes, std::string& failure) noexcept {
    uploadedBytes=0u;
    if (!request.prepared.core.remainingLsc.hasSpatialGainMap) return true;
    const std::uint64_t count=static_cast<std::uint64_t>(request.remainingLscWidth)*request.remainingLscHeight*request.remainingLscChannels;
    const std::uint64_t bytes=count*sizeof(float);
    if (request.remainingLscMap==nullptr || request.remainingLscWidth!=request.prepared.core.remainingLsc.mapWidth ||
        request.remainingLscHeight!=request.prepared.core.remainingLsc.mapHeight ||
        request.remainingLscChannels!=request.prepared.core.remainingLsc.mapChannels || bytes==0u) {
        failure="NEURAL_PRODUCTION_LSC_MAP_MISMATCH"; return false;
    }
    if (!ensureBufferLocked(allocator,bytes,true,lscMap_,failure)) return false;
    if (lscGeneration_!=request.remainingLscGeneration) {
        std::memcpy(lscMap_.mapped,request.remainingLscMap,static_cast<std::size_t>(bytes));
#if BNCAM_VMA_HEADER_AVAILABLE
        vmaFlushAllocation(allocator,lscMap_.allocation,0,static_cast<VkDeviceSize>(bytes));
#endif
        lscGeneration_=request.remainingLscGeneration; uploadedBytes=bytes;
    }
    return true;
}

bool VulkanNeuralRawProductionBridge::dispatchBridgeLocked(
        VkDevice device, VkQueue queue, std::mutex& queueMutex,
        VkBuffer input, VkBuffer output, VkBuffer auxiliary0, VkBuffer auxiliary1,
        const bncam::spectra::neural::CanonicalBayerPackContract& cfa,
        std::uint32_t rawWidth, std::uint32_t rawHeight,
        std::uint32_t mode,
        const std::array<float, 4>& shotS,
        const std::array<float, 4>& readO,
        std::string& failure) noexcept {
    if (input == VK_NULL_HANDLE || output == VK_NULL_HANDLE ||
        !cfa.supportsExtent(rawWidth, rawHeight)) {
        failure = "NEURAL_PRODUCTION_BRIDGE_INPUT_INVALID";
        return false;
    }
    if (auxiliary0 == VK_NULL_HANDLE) auxiliary0 = input;
    if (auxiliary1 == VK_NULL_HANDLE) auxiliary1 = input;

    const std::uint32_t pw = rawWidth / 2u;
    const std::uint32_t ph = rawHeight / 2u;
    const std::uint32_t posteriorGroupsX = (pw + 7u) / 8u;
    const std::uint32_t posteriorGroupsY = (ph + 7u) / 8u;
    const std::uint32_t effectGroupsX = (pw + 31u) / 32u;
    const std::uint32_t effectGroupsY = (ph + 31u) / 32u;
    const VkDeviceSize packedRange = fp32Bytes(pw, ph, 4u);
    const VkDeviceSize inputRange = mode == 0u
            ? fp32Bytes(rawWidth, rawHeight, 1u) : packedRange;
    const VkDeviceSize outputRange = mode == 0u
            ? packedRange
            : (mode == 2u
                    ? static_cast<VkDeviceSize>(posteriorGroupsX) * posteriorGroupsY *
                        4u * sizeof(float)
                    : (mode == 3u
                            ? static_cast<VkDeviceSize>(effectGroupsX) * effectGroupsY *
                                bncam::spectra::neural::kNeuralEffectSummaryVec4PerGroup *
                                4u * sizeof(float)
                            : fp32Bytes(rawWidth, rawHeight, 1u)));
    const VkDeviceSize auxiliaryRange = mode == 3u ? packedRange : inputRange;

    std::array<VkDescriptorBufferInfo, 4> infos{{
        {input, 0u, inputRange},
        {output, 0u, outputRange},
        {auxiliary0, 0u, auxiliaryRange},
        {auxiliary1, 0u, auxiliaryRange}
    }};
    std::array<VkWriteDescriptorSet, 4> writes{};
    for (std::uint32_t i = 0u; i < writes.size(); ++i) {
        writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, static_cast<std::uint32_t>(writes.size()), writes.data(), 0u, nullptr);
    if (vkResetFences(device, 1u, &fence_) != VK_SUCCESS ||
        vkResetCommandBuffer(commandBuffer_, 0u) != VK_SUCCESS) {
        failure = "NEURAL_PRODUCTION_BRIDGE_RESET_FAILED";
        return false;
    }
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &bi) != VK_SUCCESS) {
        failure = "NEURAL_PRODUCTION_BRIDGE_BEGIN_FAILED";
        return false;
    }
    VkMemoryBarrier acquireBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    acquireBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    acquireBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(commandBuffer_,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
            1u, &acquireBarrier, 0u, nullptr, 0u, nullptr);
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
            0u, 1u, &descriptorSet_, 0u, nullptr);

    BridgePushConstants pc{};
    pc.rawWidth = rawWidth;
    pc.rawHeight = rawHeight;
    pc.packedWidth = pw;
    pc.packedHeight = ph;
    pc.mode = mode;
    pc.off0x = cfa.sourceOffsets[0].x; pc.off0y = cfa.sourceOffsets[0].y;
    pc.off1x = cfa.sourceOffsets[1].x; pc.off1y = cfa.sourceOffsets[1].y;
    pc.off2x = cfa.sourceOffsets[2].x; pc.off2y = cfa.sourceOffsets[2].y;
    pc.off3x = cfa.sourceOffsets[3].x; pc.off3y = cfa.sourceOffsets[3].y;
    pc.shotS0 = shotS[0]; pc.shotS1 = shotS[1]; pc.shotS2 = shotS[2]; pc.shotS3 = shotS[3];
    pc.readO0 = readO[0]; pc.readO1 = readO[1]; pc.readO2 = readO[2]; pc.readO3 = readO[3];
    pc.adaptiveFullEvidenceSnr = bncam::spectra::neural::kNeuralAdaptiveFullEvidenceSnr;
    pc.adaptiveIdentitySnr = bncam::spectra::neural::kNeuralAdaptiveIdentitySnr;
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
            0u, sizeof(pc), &pc);

    const std::uint32_t dispatchX = mode == 3u ? effectGroupsX : posteriorGroupsX;
    const std::uint32_t dispatchY = mode == 3u ? effectGroupsY : posteriorGroupsY;
    vkCmdDispatch(commandBuffer_, dispatchX, dispatchY, 1u);

    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 1u, &mb, 0u, nullptr, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        failure = "NEURAL_PRODUCTION_BRIDGE_END_FAILED";
        return false;
    }
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.commandBufferCount = 1u;
    si.pCommandBuffers = &commandBuffer_;
    {
        std::lock_guard<std::mutex> qlock(queueMutex);
        if (vkQueueSubmit(queue, 1u, &si, fence_) != VK_SUCCESS) {
            failure = "NEURAL_PRODUCTION_BRIDGE_SUBMIT_FAILED";
            return false;
        }
    }
    const VkResult wait = vkWaitForFences(device, 1u, &fence_, VK_TRUE, UINT64_MAX);
    if (wait != VK_SUCCESS) {
        failure = "NEURAL_PRODUCTION_BRIDGE_WAIT_FAILED_" + std::to_string(wait);
        return false;
    }
    return true;
}

NeuralProductionGpuResult VulkanNeuralRawProductionBridge::execute(
        VkDevice device, VkQueue queue, VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner, std::mutex& queueMutex,
        VulkanNeuralRawDenoiseBackend& neuralBackend,
        const NeuralProductionGpuRequest& request) noexcept {
    using namespace bncam::spectra::neural;
    const auto started=Clock::now();
    NeuralProductionGpuResult out{};
    out.downstreamBayerBuffer=request.normalizedBayerInput;
    out.downstreamBayerBytes=request.normalizedBayerBytes;
    out.packedWidth=request.prepared.core.cfa.packedWidth(request.prepared.core.rawWidth);
    out.packedHeight=request.prepared.core.cfa.packedHeight(request.prepared.core.rawHeight);

    NeuralRuntimeReadiness readiness=mergeProductionReadiness(request.prepared,request.runtimeReadiness);
    const NeuralInvocationDecision decision=decideNeuralInvocation(request.prepared.core,request.prepared.controls,readiness);
    if(!decision.runInference){out.bypassReason=decision.bypassReason;out.status="BYPASS_"+std::string(neuralBypassReasonName(decision.bypassReason));out.totalWallMs=elapsedMs(started);return out;}
    out.attempted=true;
    if (!request.conditioningConfig.valid()) {
        out.bypassReason = NeuralBypassReason::InvalidConditioningSchema;
        out.status = "BYPASS_INVALID_CONDITIONING_CONFIG";
        out.totalWallMs = elapsedMs(started);
        return out;
    }
    if(request.normalizedBayerInput==VK_NULL_HANDLE || request.normalizedBayerBytes!=fp32Bytes(request.prepared.core.rawWidth,request.prepared.core.rawHeight,1u)){
        out.failureCode=NeuralBackendFailureCode::InvalidRequest;out.bypassReason=NeuralBypassReason::BackendFailure;out.status="FAIL_BYPASS_INVALID_RESIDENT_RAW";out.totalWallMs=elapsedMs(started);return out;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    std::string failure;
    // initializeLocked() may rebuild the bridge and therefore calls destroyLocked(),
    // which clears allocator_. Bind the current authoritative VMA allocator only
    // after bridge initialization has completed.
    if(!initializeLocked(device,commandPool,failure)){
        out.failureCode=NeuralBackendFailureCode::InternalError;out.bypassReason=NeuralBypassReason::BackendFailure;out.status="FAIL_BYPASS_"+failure;out.totalWallMs=elapsedMs(started);return out;
    }
    allocator_=allocatorOwner.handle();
    if(allocator_==nullptr){
        out.failureCode=NeuralBackendFailureCode::InternalError;
        out.bypassReason=NeuralBypassReason::BackendFailure;
        out.status="FAIL_BYPASS_NEURAL_PRODUCTION_ALLOCATOR_UNAVAILABLE";
        out.totalWallMs=elapsedMs(started);
        return out;
    }
    const std::uint64_t packedBytes=fp32Bytes(out.packedWidth,out.packedHeight,4u);
    const std::uint64_t scalarPackedBytes=fp32Bytes(out.packedWidth,out.packedHeight,1u);
    const std::uint32_t effectGroupsX = (out.packedWidth + 31u) / 32u;
    const std::uint32_t effectGroupsY = (out.packedHeight + 31u) / 32u;
    const std::uint64_t effectGroupCount =
            static_cast<std::uint64_t>(effectGroupsX) * effectGroupsY;
    const std::uint64_t effectSummaryBytes = effectGroupCount *
            bncam::spectra::neural::kNeuralEffectSummaryVec4PerGroup * 4u * sizeof(float);
    if(!ensureBufferLocked(allocator_,packedBytes,false,packedInput_,failure)||
       !ensureBufferLocked(allocator_,packedBytes,false,cleanPacked_,failure)||
       !ensureBufferLocked(allocator_,packedBytes,false,posteriorPacked_,failure)||
       !ensureBufferLocked(allocator_,effectSummaryBytes,true,effectSummary_,failure)||
       !ensureBufferLocked(allocator_,scalarPackedBytes,false,headroom_,failure)||
       !ensureBufferLocked(allocator_,static_cast<std::uint64_t>(out.packedWidth)*out.packedHeight*sizeof(std::uint32_t),false,saturationMask_,failure)||
       !ensureBufferLocked(allocator_,request.normalizedBayerBytes,false,downstreamMosaic_,failure)){
        out.failureCode=NeuralBackendFailureCode::InternalError;out.bypassReason=NeuralBypassReason::BackendFailure;out.status="FAIL_BYPASS_"+failure;out.totalWallMs=elapsedMs(started);return out;
    }
    if(!uploadRemainingLscLocked(allocator_,request,out.compactMetadataUploadBytes,failure)){
        out.failureCode=NeuralBackendFailureCode::InvalidRequest;out.bypassReason=NeuralBypassReason::MissingRequiredLsc;out.status="FAIL_BYPASS_"+failure;out.totalWallMs=elapsedMs(started);return out;
    }
    const auto packBridgeStarted = Clock::now();
    if(!dispatchBridgeLocked(device,queue,queueMutex,request.normalizedBayerInput,packedInput_.buffer,
            packedInput_.buffer, packedInput_.buffer, request.prepared.core.cfa,
            request.prepared.core.rawWidth, request.prepared.core.rawHeight, 0u,
            request.prepared.core.noise.shotS, request.prepared.core.noise.readO, failure)){
        out.packBridgeMs = elapsedMs(packBridgeStarted);
        out.failureCode=NeuralBackendFailureCode::DispatchFailed;out.bypassReason=NeuralBypassReason::BackendFailure;out.status="FAIL_BYPASS_"+failure;out.totalWallMs=elapsedMs(started);return out;
    }
    out.packBridgeMs = elapsedMs(packBridgeStarted);
    out.bridgeKernelDispatches=1u;

    NeuralRawDenoiseRequest nr{};nr.core=request.prepared.core;nr.framePhysics=request.prepared.framePhysics;nr.conditioningConfig=request.conditioningConfig;nr.controls=request.prepared.controls;
    nr.packedNormalizedRawInput=makeBufferView(packedInput_.buffer,NeuralElementType::Fp32,NeuralResourceAccess::ReadOnly,out.packedWidth,out.packedHeight,4u,4u);
    nr.cleanPackedRawOutput=makeBufferView(cleanPacked_.buffer,NeuralElementType::Fp32,NeuralResourceAccess::WriteOnly,out.packedWidth,out.packedHeight,4u,4u);
    nr.posteriorVarianceOutput=makeBufferView(posteriorPacked_.buffer,NeuralElementType::Fp32,NeuralResourceAccess::WriteOnly,out.packedWidth,out.packedHeight,4u,4u);
    if(request.prepared.core.remainingLsc.hasSpatialGainMap){nr.remainingLscMap=makeBufferView(lscMap_.buffer,NeuralElementType::Fp32,NeuralResourceAccess::ReadOnly,request.remainingLscWidth,request.remainingLscHeight,request.remainingLscChannels,4u);}
    nr.originalSaturationEvidenceRequested=true;
    nr.originalSaturationMaskOutput=makeBufferView(saturationMask_.buffer,NeuralElementType::U32,NeuralResourceAccess::WriteOnly,out.packedWidth,out.packedHeight,1u,4u);
    nr.originalHeadroomEvidenceOutput=makeBufferView(headroom_.buffer,NeuralElementType::Fp32,NeuralResourceAccess::WriteOnly,out.packedWidth,out.packedHeight,1u,4u);

    const auto backendStarted = Clock::now();
    const NeuralRawDenoiseResult neural=neuralBackend.run(nr);
    out.backendRunMs = elapsedMs(backendStarted);
    out.backendSlotReadyWaitMs = neural.slotReadyWaitMs;
    out.backendCommandRecordMs = neural.commandRecordMs;
    out.backendQueueSubmitMs = neural.queueSubmitMs;
    out.backendCompletionWaitMs = neural.completionWaitMs;
    out.neuralKernelDispatches=neural.dispatchedKernelCount;
    out.failureCode=neural.failureCode;out.bypassReason=neural.bypassReason;
    if(selectNeuralPublicationSource(decision,neural)!=NeuralPublicationSource::NeuralOutput){
        out.status="FAIL_BYPASS_NEURAL_"+std::string(neuralBypassReasonName(neural.bypassReason));
        const auto backendDiagnostics=neuralBackend.diagnostics();
        if(!backendDiagnostics.lastFailure.empty()&&backendDiagnostics.lastFailure!="none"){
            out.status+="_"+backendDiagnostics.lastFailure;
        }
        out.totalWallMs=elapsedMs(started);
        return out;
    }

    // Phase-6 recovery truth: one compact GPU reduction measures the actual neural mutation
    // while preserving the existing posterior feedback. This does not alter neural pixels or
    // inference authority. The CPU receives one 15xvec4 summary per 32x32 packed region only.
    const auto effectStarted = Clock::now();
    if(!dispatchBridgeLocked(device, queue, queueMutex,
            packedInput_.buffer, effectSummary_.buffer, cleanPacked_.buffer, posteriorPacked_.buffer,
            request.prepared.core.cfa, request.prepared.core.rawWidth, request.prepared.core.rawHeight,
            3u, request.prepared.core.noise.shotS, request.prepared.core.noise.readO, failure)) {
        out.failureCode = NeuralBackendFailureCode::DispatchFailed;
        out.bypassReason = NeuralBypassReason::PosteriorInvalid;
        out.status = "FAIL_BYPASS_EFFECT_POSTERIOR_REDUCTION_" + failure;
        out.totalWallMs = elapsedMs(started);
        return out;
    }
#if BNCAM_VMA_HEADER_AVAILABLE
    vmaInvalidateAllocation(allocator_, effectSummary_.allocation, 0,
            static_cast<VkDeviceSize>(effectSummaryBytes));
#endif
    out.effectSummaryMs = elapsedMs(effectStarted);
    const auto* summary = static_cast<const float*>(effectSummary_.mapped);
    const std::uint64_t packedSampleCount =
            static_cast<std::uint64_t>(out.packedWidth) * out.packedHeight;

    if (!bncam::spectra::neural::reducePosteriorMeanFromNeuralEffectSummary(
            summary, static_cast<std::size_t>(effectGroupCount), packedSampleCount,
            out.posteriorMeanVarianceCfa)) {
        out.failureCode = NeuralBackendFailureCode::InvalidPosteriorOutput;
        out.bypassReason = NeuralBypassReason::PosteriorInvalid;
        out.status = "FAIL_BYPASS_POSTERIOR_SUMMARY_INVALID";
        out.totalWallMs = elapsedMs(started);
        return out;
    }
    out.posteriorSummaryReady = true;
    // The same physical readback carries posterior truth and the optional effect metrics.
    out.compactPosteriorReadbackBytes = effectSummaryBytes;

    out.effectTelemetry = bncam::spectra::neural::reduceNeuralEffectSummary(
            summary, static_cast<std::size_t>(effectGroupCount), packedSampleCount);
    out.effectTelemetryReady = out.effectTelemetry.ready;
    out.compactEffectReadbackBytes = effectSummaryBytes;
    out.effectTelemetryStatus = out.effectTelemetryReady
            ? "MEASURED_NEURAL_OUTPUT"
            : "UNAVAILABLE_EFFECT_REDUCTION_INVALID";

    const auto unpackBridgeStarted = Clock::now();
    if(!dispatchBridgeLocked(device,queue,queueMutex,cleanPacked_.buffer,downstreamMosaic_.buffer,
            cleanPacked_.buffer, cleanPacked_.buffer, request.prepared.core.cfa,
            request.prepared.core.rawWidth, request.prepared.core.rawHeight, 1u,
            request.prepared.core.noise.shotS, request.prepared.core.noise.readO, failure)){
        out.unpackBridgeMs = elapsedMs(unpackBridgeStarted);
        out.failureCode=NeuralBackendFailureCode::DispatchFailed;out.bypassReason=NeuralBypassReason::BackendFailure;out.status="FAIL_BYPASS_UNPACK_"+failure;out.totalWallMs=elapsedMs(started);return out;
    }
    out.unpackBridgeMs = elapsedMs(unpackBridgeStarted);
    out.bridgeKernelDispatches=3u;out.success=true;out.neuralPublished=true;out.originalPublished=false;
    out.downstreamBayerBuffer=downstreamMosaic_.buffer;out.downstreamBayerBytes=request.normalizedBayerBytes;out.residentOutputGeneration=request.generationId!=0u?request.generationId:++generationCounter_;
    out.posteriorVariancePacked=posteriorPacked_.buffer;out.originalSaturationMaskPacked=saturationMask_.buffer;out.originalHeadroomPacked=headroom_.buffer;
    out.persistentGpuBytes=packedInput_.capacityBytes+cleanPacked_.capacityBytes+posteriorPacked_.capacityBytes+effectSummary_.capacityBytes+saturationMask_.capacityBytes+headroom_.capacityBytes+downstreamMosaic_.capacityBytes+lscMap_.capacityBytes;
    out.status="NEURAL_PUBLISHED";out.totalWallMs=elapsedMs(started);return out;
}

void VulkanNeuralRawProductionBridge::destroyLocked(VkDevice device) noexcept {
    for(Buffer* b:{&packedInput_,&cleanPacked_,&posteriorPacked_,&effectSummary_,&saturationMask_,&headroom_,&downstreamMosaic_,&lscMap_}) freeBufferLocked(*b);
    if(device!=VK_NULL_HANDLE){if(fence_!=VK_NULL_HANDLE)vkDestroyFence(device,fence_,nullptr);if(commandBuffer_!=VK_NULL_HANDLE&&commandPool_!=VK_NULL_HANDLE)vkFreeCommandBuffers(device,commandPool_,1u,&commandBuffer_);if(descriptorPool_!=VK_NULL_HANDLE)vkDestroyDescriptorPool(device,descriptorPool_,nullptr);if(pipeline_!=VK_NULL_HANDLE)vkDestroyPipeline(device,pipeline_,nullptr);if(shaderModule_!=VK_NULL_HANDLE)vkDestroyShaderModule(device,shaderModule_,nullptr);if(pipelineLayout_!=VK_NULL_HANDLE)vkDestroyPipelineLayout(device,pipelineLayout_,nullptr);if(descriptorSetLayout_!=VK_NULL_HANDLE)vkDestroyDescriptorSetLayout(device,descriptorSetLayout_,nullptr);}
    fence_=VK_NULL_HANDLE;commandBuffer_=VK_NULL_HANDLE;descriptorPool_=VK_NULL_HANDLE;descriptorSet_=VK_NULL_HANDLE;pipeline_=VK_NULL_HANDLE;shaderModule_=VK_NULL_HANDLE;pipelineLayout_=VK_NULL_HANDLE;descriptorSetLayout_=VK_NULL_HANDLE;device_=VK_NULL_HANDLE;commandPool_=VK_NULL_HANDLE;allocator_=nullptr;lscGeneration_=0u;
}

void VulkanNeuralRawProductionBridge::destroy(VkDevice device) noexcept {std::lock_guard<std::mutex> lock(mutex_);destroyLocked(device);}

} // namespace bncam::vulkan::neural
