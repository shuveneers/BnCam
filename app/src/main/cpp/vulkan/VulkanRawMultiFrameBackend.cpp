#include "VulkanRawMultiFrameBackend.h"
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
#ifndef BNCAM_RAW_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE
#define BNCAM_RAW_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE 0
#endif
#ifndef BNCAM_RAW_MULTIFRAME_FUSION_SHADER_AVAILABLE
#define BNCAM_RAW_MULTIFRAME_FUSION_SHADER_AVAILABLE 0
#endif
#if BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE
#include "RawCaptureCanonicalizeSpirv.h"
#endif
#if BNCAM_RAW_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE
#include "RawMultiFrameAlignmentSpirv.h"
#endif
#if BNCAM_RAW_MULTIFRAME_FUSION_SHADER_AVAILABLE
#include "RawMultiFrameFusionSpirv.h"
#endif

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <numeric>

namespace bncam::vulkan {
namespace {
using Clock = std::chrono::steady_clock;

float elapsedMs(const Clock::time_point& start) {
    return std::chrono::duration<float, std::milli>(Clock::now() - start).count();
}

std::uint64_t align4(std::uint64_t value) { return (value + 3u) & ~std::uint64_t{3u}; }
std::uint32_t ceilDiv(std::uint32_t value, std::uint32_t divisor) {
    return divisor == 0u ? 0u : (value + divisor - 1u) / divisor;
}
std::uint32_t packedRaw10RowBytes(std::uint32_t width) { return ((width + 3u) / 4u) * 5u; }

struct CanonicalizePush {
    std::uint32_t sourceWidth;
    std::uint32_t sourceHeight;
    std::uint32_t outputWidth;
    std::uint32_t outputHeight;
    std::uint32_t inputRowStrideBytes;
    std::uint32_t inputPixelStrideBytes;
    std::uint32_t cropLeft;
    std::uint32_t cropTop;
    std::uint32_t sourceFormat;
    std::uint32_t nativeWhite;
    std::uint32_t payloadWhite;
    std::uint32_t nativeBlack0;
    std::uint32_t nativeBlack1;
    std::uint32_t nativeBlack2;
    std::uint32_t nativeBlack3;
    std::uint32_t payloadBlack0;
    std::uint32_t payloadBlack1;
    std::uint32_t payloadBlack2;
    std::uint32_t payloadBlack3;
};
static_assert(sizeof(CanonicalizePush) == 76u);

struct AlignmentPush {
    std::uint32_t width;
    std::uint32_t height;
    std::int32_t centerDx;
    std::int32_t centerDy;
    std::uint32_t searchRadius;
    std::uint32_t shiftStep;
    std::uint32_t sampleStep;
    std::uint32_t candidateCols;
    std::uint32_t candidateRows;
    std::uint32_t maxShift;
    float whiteLevel;
    float black0;
    float black1;
    float black2;
    float black3;
    std::uint32_t mode;
};
static_assert(sizeof(AlignmentPush) == 64u);

struct FusionPush { std::uint32_t mode; };
static_assert(sizeof(FusionPush) == 4u);

struct AhbReadLock final {
    ~AhbReadLock() { release(); }
    bool acquire(AHardwareBuffer* buffer, RawCaptureSourceFormat format, std::string& failure) noexcept {
        if (buffer == nullptr) { failure = "RAW_MULTIFRAME_AHB_NULL"; return false; }
        buffer_ = buffer;
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(buffer, &desc);
        width = desc.width;
        height = desc.height;
        if (width == 0u || height == 0u) { failure = "RAW_MULTIFRAME_AHB_DIMENSIONS_INVALID"; buffer_ = nullptr; return false; }
        AHardwareBuffer_Planes planes{};
        const int planeStatus = AHardwareBuffer_lockPlanes(
                buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &planes);
        if (planeStatus == 0 && planes.planeCount >= 1u && planes.planes[0].data != nullptr) {
            data = static_cast<const std::uint8_t*>(planes.planes[0].data);
            rowStrideBytes = static_cast<std::uint32_t>(planes.planes[0].rowStride);
            pixelStrideBytes = static_cast<std::uint32_t>(planes.planes[0].pixelStride);
            if (format == RawCaptureSourceFormat::RAW10) {
                if (rowStrideBytes == 0u) rowStrideBytes = packedRaw10RowBytes(width);
                pixelStrideBytes = 0u;
            } else {
                if (pixelStrideBytes == 0u) pixelStrideBytes = 2u;
                if (rowStrideBytes == 0u) rowStrideBytes = std::max(desc.width, desc.stride) * pixelStrideBytes;
            }
            locked_ = true;
            return true;
        }
        if (planeStatus == 0) {
            // lockPlanes() owns the AHardwareBuffer even if a vendor returned an unusable
            // plane descriptor. Release that ownership before attempting the flat compatibility lock.
            AHardwareBuffer_unlock(buffer, nullptr);
        }
        void* flat = nullptr;
        const int status = AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &flat);
        if (status != 0 || flat == nullptr) {
            failure = "RAW_MULTIFRAME_AHB_LOCK_FAILED_" + std::to_string(status);
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
        if (locked_ && buffer_ != nullptr) AHardwareBuffer_unlock(buffer_, nullptr);
        buffer_ = nullptr; data = nullptr; locked_ = false;
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

struct AlignmentScore { float dx; float dy; float response; float count; };
static_assert(sizeof(AlignmentScore) == 16u);

struct AlignmentDecisionGpu {
    bool accepted = false;
    int applyDx = 0;
    int applyDy = 0;
    double estimatedShiftX = 0.0;
    double estimatedShiftY = 0.0;
    double response = 0.0;
    std::string rejectReason = "none";
};

struct StaticConsensus {
    spectra_temporal::StaticProbabilityField field{};
    std::vector<double> sums;
    std::vector<std::uint32_t> counts;
    int acceptedPairs = 0;

    float compareAndUpdate(SpectraTemporalObserverResult& o) {
        const auto& current = o.staticProbabilityField;
        if (!current.valid()) return 0.0f;
        if (!field.valid() || field.columns != current.columns || field.rows != current.rows || field.cellSize != current.cellSize) {
            field = current;
            sums.assign(current.values.begin(), current.values.end());
            counts.assign(current.values.size(), 1u);
            acceptedPairs = 1;
            return 0.50f;
        }
        double agreementSum = 0.0, agreementWeight = 0.0;
        for (std::size_t i = 0; i < current.values.size(); ++i) {
            const double previous = counts[i] > 0u ? sums[i] / static_cast<double>(counts[i]) : 0.0;
            const double now = std::clamp(static_cast<double>(current.values[i]), 0.0, 1.0);
            const double support = std::max(0.05, std::min(previous, now));
            agreementSum += support * (1.0 - std::abs(previous - now));
            agreementWeight += support;
            sums[i] += now;
            counts[i] += 1u;
            field.values[i] = static_cast<float>(std::clamp(sums[i] / static_cast<double>(counts[i]), 0.0, 1.0));
        }
        ++acceptedPairs;
        const double agreement = agreementWeight > 1.0e-12 ? agreementSum / agreementWeight : 0.0;
        const double pairSupport = spectra_temporal::smoothstep01(static_cast<double>(acceptedPairs) / 3.0);
        return static_cast<float>(std::clamp((0.35 + 0.65 * agreement) * (0.55 + 0.45 * pairSupport), 0.0, 1.0));
    }
};

// FASE 6: capture-local S/O fit consensus removed.

double percentile(std::vector<double> values, double q) {
    values.erase(std::remove_if(values.begin(), values.end(), [](double v){ return !std::isfinite(v); }), values.end());
    if (values.empty()) return 1.0;
    std::sort(values.begin(), values.end());
    const double p = std::clamp(q,0.0,1.0)*(values.size()-1u);
    const std::size_t lo=static_cast<std::size_t>(std::floor(p)), hi=static_cast<std::size_t>(std::ceil(p));
    if (lo==hi) return values[lo];
    const double f=p-static_cast<double>(lo); return values[lo]*(1.0-f)+values[hi]*f;
}

template <typename PipelineT>
bool createPipeline(VkDevice device, const std::vector<std::uint32_t>& spirv,
                    std::uint32_t bindingCount, std::uint32_t pushBytes,
                    PipelineT& out, std::string& failure) {
    if (device == VK_NULL_HANDLE || spirv.empty() || bindingCount == 0u) { failure="RAW_MULTIFRAME_PIPELINE_INPUT_INVALID"; return false; }
    std::vector<VkDescriptorSetLayoutBinding> bindings(bindingCount);
    for (std::uint32_t i=0;i<bindingCount;++i) {
        bindings[i].binding=i; bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount=1u; bindings[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    li.bindingCount=bindingCount; li.pBindings=bindings.data();
    if (vkCreateDescriptorSetLayout(device,&li,nullptr,&out.descriptorSetLayout)!=VK_SUCCESS) { failure="RAW_MULTIFRAME_DESCRIPTOR_LAYOUT_FAILED"; return false; }
    VkPushConstantRange range{}; range.stageFlags=VK_SHADER_STAGE_COMPUTE_BIT; range.size=pushBytes;
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount=1u; pli.pSetLayouts=&out.descriptorSetLayout; pli.pushConstantRangeCount=1u; pli.pPushConstantRanges=&range;
    if (vkCreatePipelineLayout(device,&pli,nullptr,&out.pipelineLayout)!=VK_SUCCESS) { failure="RAW_MULTIFRAME_PIPELINE_LAYOUT_FAILED"; return false; }
    VkShaderModuleCreateInfo si{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; si.codeSize=spirv.size()*sizeof(std::uint32_t); si.pCode=spirv.data();
    if (vkCreateShaderModule(device,&si,nullptr,&out.shaderModule)!=VK_SUCCESS) { failure="RAW_MULTIFRAME_SHADER_MODULE_FAILED"; return false; }
    VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO}; stage.stage=VK_SHADER_STAGE_COMPUTE_BIT; stage.module=out.shaderModule; stage.pName="main";
    VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO}; ci.stage=stage; ci.layout=out.pipelineLayout;
    if (VulkanPipelineCacheRegistry::createComputePipelines(device,1u,&ci,nullptr,&out.pipeline)!=VK_SUCCESS) { failure="RAW_MULTIFRAME_COMPUTE_PIPELINE_FAILED"; return false; }
    VkDescriptorPoolSize poolSize{}; poolSize.type=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; poolSize.descriptorCount=bindingCount;
    VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets=1u; dpi.poolSizeCount=1u; dpi.pPoolSizes=&poolSize;
    if (vkCreateDescriptorPool(device,&dpi,nullptr,&out.descriptorPool)!=VK_SUCCESS) { failure="RAW_MULTIFRAME_DESCRIPTOR_POOL_FAILED"; return false; }
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; ai.descriptorPool=out.descriptorPool; ai.descriptorSetCount=1u; ai.pSetLayouts=&out.descriptorSetLayout;
    if (vkAllocateDescriptorSets(device,&ai,&out.descriptorSet)!=VK_SUCCESS) { failure="RAW_MULTIFRAME_DESCRIPTOR_SET_FAILED"; return false; }
    out.bindingCount=bindingCount; return true;
}

template <typename PipelineT>
void bindBuffers(VkDevice device, const PipelineT& p,
                 const std::vector<VkDescriptorBufferInfo>& infos) {
    std::vector<VkWriteDescriptorSet> writes(infos.size());
    for (std::uint32_t i=0;i<infos.size();++i) {
        writes[i].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; writes[i].dstSet=p.descriptorSet;
        writes[i].dstBinding=i; writes[i].descriptorCount=1u; writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; writes[i].pBufferInfo=&infos[i];
    }
    vkUpdateDescriptorSets(device,static_cast<std::uint32_t>(writes.size()),writes.data(),0u,nullptr);
}

} // namespace

bool VulkanRawMultiFrameBackend::ensureBufferLocked(VmaAllocator allocator, std::uint64_t bytes, bool hostVisible,
                                                      PersistentBuffer& target, std::string& failure) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator;(void)bytes;(void)hostVisible;(void)target; failure="VMA_HEADER_NOT_AVAILABLE"; return false;
#else
    if (allocator==nullptr || bytes==0u) { failure="RAW_MULTIFRAME_BUFFER_REQUEST_INVALID"; return false; }
    if (target.buffer!=VK_NULL_HANDLE && target.allocation!=nullptr && target.capacityBytes>=bytes && (!hostVisible || target.mapped!=nullptr)) return true;
    destroyBufferLocked(target);
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bi.size=static_cast<VkDeviceSize>(bytes);
    bi.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT; bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    VmaAllocationCreateInfo ai{}; ai.usage=hostVisible?VMA_MEMORY_USAGE_AUTO_PREFER_HOST:VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
    if (hostVisible) ai.flags=VMA_ALLOCATION_CREATE_MAPPED_BIT|VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    VmaAllocationInfo info{};
    const VkResult r=vmaCreateBuffer(allocator,&bi,&ai,&target.buffer,&target.allocation,&info);
    if (r!=VK_SUCCESS || target.buffer==VK_NULL_HANDLE || target.allocation==nullptr || (hostVisible && info.pMappedData==nullptr)) {
        target={}; failure="RAW_MULTIFRAME_VMA_BUFFER_FAILED_"+std::to_string(r); return false;
    }
    target.mapped=info.pMappedData; target.capacityBytes=bytes; return true;
#endif
}

void VulkanRawMultiFrameBackend::destroyBufferLocked(PersistentBuffer& b) noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_!=nullptr && b.buffer!=VK_NULL_HANDLE && b.allocation!=nullptr) vmaDestroyBuffer(allocator_,b.buffer,b.allocation);
#endif
    b={};
}
void VulkanRawMultiFrameBackend::destroyPipelineLocked(VkDevice device, PipelineBundle& p) noexcept {
    if (device!=VK_NULL_HANDLE) {
        if (p.descriptorPool) vkDestroyDescriptorPool(device,p.descriptorPool,nullptr);
        if (p.pipeline) vkDestroyPipeline(device,p.pipeline,nullptr);
        if (p.shaderModule) vkDestroyShaderModule(device,p.shaderModule,nullptr);
        if (p.pipelineLayout) vkDestroyPipelineLayout(device,p.pipelineLayout,nullptr);
        if (p.descriptorSetLayout) vkDestroyDescriptorSetLayout(device,p.descriptorSetLayout,nullptr);
    }
    p={};
}
bool VulkanRawMultiFrameBackend::ensureSubmissionResourcesLocked(
        VkDevice device, VkCommandPool commandPool, bool& created,
        std::string& failure) noexcept {
    created = false;
    if (submissionResourcesUnsafe_) {
        failure = "RAW_MULTIFRAME_SUBMISSION_RESOURCES_QUARANTINED";
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
        failure = "RAW_MULTIFRAME_COMMAND_ALLOCATE_FAILED_" + std::to_string(alloc);
        return false;
    }
    submissionCommandPool_ = commandPool;
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    const VkResult fenceResult = vkCreateFence(device, &fi, nullptr, &reusableFence_);
    if (fenceResult != VK_SUCCESS) {
        vkFreeCommandBuffers(device, submissionCommandPool_, 1u, &reusableCommandBuffer_);
        reusableCommandBuffer_ = VK_NULL_HANDLE;
        submissionCommandPool_ = VK_NULL_HANDLE;
        failure = "RAW_MULTIFRAME_FENCE_CREATE_FAILED_" + std::to_string(fenceResult);
        return false;
    }
    created = true;
    return true;
}

void VulkanRawMultiFrameBackend::destroySubmissionResourcesLocked(VkDevice device) noexcept {
    if (device != VK_NULL_HANDLE) {
        if (reusableFence_ != VK_NULL_HANDLE) {
            vkDestroyFence(device, reusableFence_, nullptr);
        }
        if (reusableCommandBuffer_ != VK_NULL_HANDLE && submissionCommandPool_ != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(device, submissionCommandPool_, 1u, &reusableCommandBuffer_);
        }
    }
    reusableFence_ = VK_NULL_HANDLE;
    reusableCommandBuffer_ = VK_NULL_HANDLE;
    submissionCommandPool_ = VK_NULL_HANDLE;
    submissionResourcesUnsafe_ = false;
}
void VulkanRawMultiFrameBackend::destroyLocked(VkDevice device) noexcept {
    destroySubmissionResourcesLocked(device);
    for (PersistentBuffer* b : {&staging_,&anchor_,&support_,&accum_,&weight_,&weightSq_,&correlation_,&staticField_,&fusionParams_,&finalRaw_,&fusionStats_,&finalStats_,&alignmentScores_,&alignmentResult_,&readback_}) destroyBufferLocked(*b);
    destroyPipelineLocked(device,canonicalizePipeline_); destroyPipelineLocked(device,alignmentPipeline_); destroyPipelineLocked(device,fusionPipeline_);
    allocator_=nullptr; initializedDevice_=VK_NULL_HANDLE; initialized_=false;
    residentOutputGeneration_=0u; residentOutputBytes_=0u; residentOutputWidth_=0u; residentOutputHeight_=0u;
}
void VulkanRawMultiFrameBackend::destroy(VkDevice device) noexcept { std::lock_guard<std::mutex> l(mutex_); destroyLocked(device); }

bool VulkanRawMultiFrameBackend::resolveResidentOutput(
        std::uint64_t generation, VkBuffer& buffer, std::uint64_t& bytes,
        std::uint32_t& width, std::uint32_t& height) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    buffer=VK_NULL_HANDLE; bytes=0u; width=0u; height=0u;
    if (generation==0u || generation!=residentOutputGeneration_ ||
        finalRaw_.buffer==VK_NULL_HANDLE || residentOutputBytes_==0u ||
        residentOutputWidth_==0u || residentOutputHeight_==0u) return false;
    buffer=finalRaw_.buffer; bytes=residentOutputBytes_;
    width=residentOutputWidth_; height=residentOutputHeight_;
    return true;
}

bool VulkanRawMultiFrameBackend::initializeLocked(VkDevice device, std::string& failure) noexcept {
    if (initialized_) return initializedDevice_==device ? true : (failure="RAW_MULTIFRAME_RUNTIME_OWNERSHIP_CHANGED", false);
#if !BNCAM_RAW_CAPTURE_CANONICALIZE_SHADER_AVAILABLE || !BNCAM_RAW_MULTIFRAME_ALIGNMENT_SHADER_AVAILABLE || !BNCAM_RAW_MULTIFRAME_FUSION_SHADER_AVAILABLE
    (void)device; failure="RAW_MULTIFRAME_SHADER_NOT_COMPILED"; return false;
#else
    if (!createPipeline(device,getRawCaptureCanonicalizeSpirv(),2u,sizeof(CanonicalizePush),canonicalizePipeline_,failure) ||
        !createPipeline(device,getRawMultiFrameAlignmentSpirv(),4u,sizeof(AlignmentPush),alignmentPipeline_,failure) ||
        !createPipeline(device,getRawMultiFrameFusionSpirv(),11u,sizeof(FusionPush),fusionPipeline_,failure)) {
        destroyLocked(device); return false;
    }
    initializedDevice_=device; initialized_=true; return true;
#endif
}

RawMultiFrameResult VulkanRawMultiFrameBackend::execute(
        VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue, VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner, std::mutex* queueSubmissionMutex,
        VulkanSpectraTemporalObserverBackend& observer,
        const RawMultiFrameRequest& request) noexcept {
    RawMultiFrameResult out{}; out.attempted=true;
    const auto totalStart=Clock::now();
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)physicalDevice;(void)device;(void)computeQueue;(void)commandPool;(void)allocatorOwner;(void)queueSubmissionMutex;(void)observer;(void)request;
    out.failureReason="VMA_HEADER_NOT_AVAILABLE"; return out;
#else
    if (device==VK_NULL_HANDLE || computeQueue==VK_NULL_HANDLE || commandPool==VK_NULL_HANDLE || request.frames.size()<2u ||
        request.cropWidth<16u || request.cropHeight<16u || request.nativeWhite==0u || request.payloadWhite==0u) {
        out.failureReason="RAW_MULTIFRAME_REQUEST_INVALID"; return out;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    residentOutputGeneration_=0u; residentOutputBytes_=0u; residentOutputWidth_=0u; residentOutputHeight_=0u;
    allocator_=allocatorOwner.handle();
    if (!initializeLocked(device,out.failureReason)) return out;
    const std::uint64_t pixels=static_cast<std::uint64_t>(request.cropWidth)*request.cropHeight;
    const std::uint64_t rawBytes=pixels*sizeof(std::uint16_t), packedBytes=align4(rawBytes);
    if (!ensureBufferLocked(allocator_,packedBytes,false,anchor_,out.failureReason) ||
        !ensureBufferLocked(allocator_,packedBytes,false,support_,out.failureReason) ||
        !ensureBufferLocked(allocator_,pixels*sizeof(float),false,accum_,out.failureReason) ||
        !ensureBufferLocked(allocator_,pixels*sizeof(float),false,weight_,out.failureReason) ||
        !ensureBufferLocked(allocator_,pixels*sizeof(float),false,weightSq_,out.failureReason) ||
        !ensureBufferLocked(allocator_,pixels*sizeof(float),false,correlation_,out.failureReason) ||
        !ensureBufferLocked(allocator_,packedBytes,false,finalRaw_,out.failureReason) ||
        !ensureBufferLocked(allocator_,packedBytes,true,readback_,out.failureReason) ||
        !ensureBufferLocked(allocator_,48u*sizeof(std::uint32_t),true,fusionParams_,out.failureReason) ||
        !ensureBufferLocked(allocator_,4u*sizeof(std::uint32_t),true,finalStats_,out.failureReason)) return out;

    bool submissionResourcesCreated = false;
    if (!ensureSubmissionResourcesLocked(device, commandPool, submissionResourcesCreated, out.failureReason)) return out;
    out.commandBufferAllocations = submissionResourcesCreated ? 1u : 0u;
    out.fenceCreations = submissionResourcesCreated ? 1u : 0u;
    out.reusedSubmissionResources = !submissionResourcesCreated;
    auto submitAndWait=[&](VkCommandBuffer cmd, const char* stage, float& ms)->bool {
        if (submissionResourcesUnsafe_ || reusableFence_ == VK_NULL_HANDLE || cmd != reusableCommandBuffer_) {
            out.failureReason=std::string(stage)+"_SUBMISSION_RESOURCES_INVALID"; return false;
        }
        const VkResult resetFence = vkResetFences(device,1u,&reusableFence_);
        if (resetFence != VK_SUCCESS) { out.failureReason=std::string(stage)+"_FENCE_RESET_FAILED_"+std::to_string(resetFence); return false; }
        ++out.fenceResets;
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount=1u; si.pCommandBuffers=&cmd;
        const auto t=Clock::now();
        VkResult submit;
        if (queueSubmissionMutex != nullptr) {
            std::lock_guard<std::mutex> queueSubmitLock(*queueSubmissionMutex);
            submit = vkQueueSubmit(computeQueue,1u,&si,reusableFence_);
        } else {
            // Runtime may deliberately hold the queue mutex around the entire backend when a
            // separate preview command pool is unavailable. Do not recursively lock it here.
            submit = vkQueueSubmit(computeQueue,1u,&si,reusableFence_);
        }
        if (submit!=VK_SUCCESS) { out.failureReason=std::string(stage)+"_QUEUE_SUBMIT_FAILED_"+std::to_string(submit); return false; }
        ++out.queueSubmissions;
        const VkResult wait=vkWaitForFences(device,1u,&reusableFence_,VK_TRUE,3'000'000'000ull); const float syncMs=elapsedMs(t); ms+=syncMs; out.gpuSynchronizationMs+=syncMs;
        if (wait!=VK_SUCCESS) { submissionResourcesUnsafe_=true; out.submissionMayRemainInFlight=true; out.failureReason=std::string(stage)+(wait==VK_TIMEOUT?"_GPU_TIMEOUT":"_GPU_WAIT_UNSAFE_"+std::to_string(wait)); return false; }
        return true;
    };
    auto allocCmd=[&](VkCommandBuffer& cmd)->bool {
        if (submissionResourcesUnsafe_ || reusableCommandBuffer_==VK_NULL_HANDLE) { out.failureReason="RAW_MULTIFRAME_COMMAND_RESOURCE_UNAVAILABLE"; return false; }
        cmd=reusableCommandBuffer_; return true;
    };
    auto beginCmd=[&](VkCommandBuffer cmd)->bool {
        const VkResult reset=vkResetCommandBuffer(cmd,0u);
        if(reset!=VK_SUCCESS){out.failureReason="RAW_MULTIFRAME_COMMAND_RESET_FAILED_"+std::to_string(reset);return false;}
        ++out.commandBufferResets;
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; bi.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if(vkBeginCommandBuffer(cmd,&bi)!=VK_SUCCESS){out.failureReason="RAW_MULTIFRAME_COMMAND_BEGIN_FAILED";return false;}return true; };

    enum class CanonicalizeOutcome {
        SUCCESS,
        FRAME_REJECT,
        BACKEND_FAILURE
    };
    auto canonicalize=[&](AHardwareBuffer* ahb, PersistentBuffer& target, RawMultiFrameSupportResult* supportResult)->CanonicalizeOutcome {
        const auto start=Clock::now(); AhbReadLock source; std::string fail;
        if (!source.acquire(ahb,request.sourceFormat,fail)) {
            if (supportResult != nullptr) {
                supportResult->rejectReason = "source_unavailable:" + fail;
                supportResult->canonicalizeMs = elapsedMs(start);
                return CanonicalizeOutcome::FRAME_REJECT;
            }
            out.failureReason=fail;
            return CanonicalizeOutcome::BACKEND_FAILURE;
        }
        if (request.cropLeft+request.cropWidth>source.width || request.cropTop+request.cropHeight>source.height) {
            if (supportResult != nullptr) {
                supportResult->rejectReason = "source_geometry_mismatch";
                supportResult->canonicalizeMs = elapsedMs(start);
                return CanonicalizeOutcome::FRAME_REJECT;
            }
            out.failureReason="RAW_MULTIFRAME_CROP_OUT_OF_RANGE";
            return CanonicalizeOutcome::BACKEND_FAILURE;
        }
        const std::uint64_t sourceBytes=static_cast<std::uint64_t>(source.rowStrideBytes)*source.height;
        if (!ensureBufferLocked(allocator_,align4(sourceBytes),true,staging_,out.failureReason)) return CanonicalizeOutcome::BACKEND_FAILURE;
        const auto rowStride=source.rowStrideBytes, pixelStride=source.pixelStrideBytes, sourceW=source.width, sourceH=source.height;
        std::memcpy(staging_.mapped,source.data,static_cast<std::size_t>(sourceBytes)); source.release();
        vmaFlushAllocation(allocator_,staging_.allocation,0u,static_cast<VkDeviceSize>(align4(sourceBytes)));
        out.fullFrameCpuUploadBytes+=sourceBytes; out.inputImportPath="AHB_CPU_BYTE_STAGING_TO_VULKAN";
        if (out.anchorSourceRowStrideBytes==0u) { out.anchorSourceRowStrideBytes=rowStride; out.anchorSourcePixelStrideBytes=pixelStride; }
        bindBuffers(device,canonicalizePipeline_,{{staging_.buffer,0,VK_WHOLE_SIZE},{target.buffer,0,VK_WHOLE_SIZE}});
        VkCommandBuffer cmd=VK_NULL_HANDLE;
        if(!allocCmd(cmd)) return CanonicalizeOutcome::BACKEND_FAILURE;
        if(!beginCmd(cmd)){return CanonicalizeOutcome::BACKEND_FAILURE;}
        VkBufferMemoryBarrier hb{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER}; hb.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT; hb.dstAccessMask=VK_ACCESS_SHADER_READ_BIT; hb.srcQueueFamilyIndex=hb.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; hb.buffer=staging_.buffer; hb.size=VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,0,nullptr,1,&hb,0,nullptr);
        vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,canonicalizePipeline_.pipeline); vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,canonicalizePipeline_.pipelineLayout,0,1,&canonicalizePipeline_.descriptorSet,0,nullptr);
        CanonicalizePush p{sourceW,sourceH,request.cropWidth,request.cropHeight,rowStride,pixelStride,request.cropLeft,request.cropTop,static_cast<std::uint32_t>(request.sourceFormat),request.nativeWhite,request.payloadWhite,
            request.nativeBlack[0],request.nativeBlack[1],request.nativeBlack[2],request.nativeBlack[3],request.payloadBlack[0],request.payloadBlack[1],request.payloadBlack[2],request.payloadBlack[3]};
        vkCmdPushConstants(cmd,canonicalizePipeline_.pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(p),&p);
        vkCmdDispatch(cmd,ceilDiv(static_cast<std::uint32_t>((pixels+1u)/2u),256u),1,1);
        VkBufferMemoryBarrier ready{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER}; ready.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT; ready.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_TRANSFER_READ_BIT; ready.srcQueueFamilyIndex=ready.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; ready.buffer=target.buffer; ready.size=VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,1,&ready,0,nullptr);
        if(vkEndCommandBuffer(cmd)!=VK_SUCCESS){out.failureReason="RAW_MULTIFRAME_CANONICALIZE_COMMAND_END_FAILED";return CanonicalizeOutcome::BACKEND_FAILURE;}
        float stageMs=0.0f; const bool ok=submitAndWait(cmd,"RAW_MULTIFRAME_CANONICALIZE",stageMs);
        out.canonicalizeGpuMs+=stageMs;
        if(supportResult) supportResult->canonicalizeMs=elapsedMs(start);
        return ok ? CanonicalizeOutcome::SUCCESS : CanonicalizeOutcome::BACKEND_FAILURE;
    };

    auto alignmentPass=[&](VkBuffer a,VkBuffer b,int centerDx,int centerDy,std::uint32_t radius,std::uint32_t shiftStep,std::uint32_t sampleStep, AlignmentDecisionGpu& decision)->bool {
        const std::uint32_t cols=(radius*2u)/shiftStep+1u, rows=cols, candidates=cols*rows;
        const std::uint64_t scoreBytes=static_cast<std::uint64_t>(candidates)*sizeof(AlignmentScore);
        const std::uint64_t resultBytes=sizeof(AlignmentScore);
        if(!ensureBufferLocked(allocator_,scoreBytes,false,alignmentScores_,out.failureReason) ||
           !ensureBufferLocked(allocator_,resultBytes,true,alignmentResult_,out.failureReason)) return false;
        bindBuffers(device,alignmentPipeline_,{{a,0,VK_WHOLE_SIZE},{b,0,VK_WHOLE_SIZE},{alignmentScores_.buffer,0,scoreBytes},{alignmentResult_.buffer,0,resultBytes}});
        VkCommandBuffer cmd=VK_NULL_HANDLE;
        if(!allocCmd(cmd)) return false;
        if(!beginCmd(cmd)){return false;}
        VkBufferMemoryBarrier barriers[2]{};
        for(int i=0;i<2;++i){barriers[i].sType=VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;barriers[i].srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT;barriers[i].dstAccessMask=VK_ACCESS_SHADER_READ_BIT;barriers[i].srcQueueFamilyIndex=barriers[i].dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;barriers[i].buffer=i==0?a:b;barriers[i].size=VK_WHOLE_SIZE;}
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,0,nullptr,2,barriers,0,nullptr);
        vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,alignmentPipeline_.pipeline);
        vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,alignmentPipeline_.pipelineLayout,0,1,&alignmentPipeline_.descriptorSet,0,nullptr);
        AlignmentPush p{request.cropWidth,request.cropHeight,centerDx,centerDy,radius,shiftStep,sampleStep,cols,rows,std::max(1u,request.maxShiftPixels),static_cast<float>(request.payloadWhite),
            static_cast<float>(request.payloadBlack[0]),static_cast<float>(request.payloadBlack[1]),static_cast<float>(request.payloadBlack[2]),static_cast<float>(request.payloadBlack[3]),0u};
        vkCmdPushConstants(cmd,alignmentPipeline_.pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(p),&p);
        vkCmdDispatch(cmd,candidates,1,1);
        VkBufferMemoryBarrier scoresReady{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        scoresReady.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT; scoresReady.dstAccessMask=VK_ACCESS_SHADER_READ_BIT;
        scoresReady.srcQueueFamilyIndex=scoresReady.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
        scoresReady.buffer=alignmentScores_.buffer; scoresReady.size=scoreBytes;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,0,nullptr,1,&scoresReady,0,nullptr);
        p.mode=1u;
        vkCmdPushConstants(cmd,alignmentPipeline_.pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(p),&p);
        vkCmdDispatch(cmd,1u,1u,1u);
        VkBufferMemoryBarrier host{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
        host.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT; host.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
        host.srcQueueFamilyIndex=host.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED; host.buffer=alignmentResult_.buffer; host.size=resultBytes;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,0,nullptr,1,&host,0,nullptr);
        if(vkEndCommandBuffer(cmd)!=VK_SUCCESS){out.failureReason="RAW_MULTIFRAME_ALIGNMENT_COMMAND_END_FAILED";return false;}
        float ms=0.0f; const bool submittedOk=submitAndWait(cmd,"RAW_MULTIFRAME_ALIGNMENT",ms);
        if(!submittedOk) return false; out.alignmentGpuMs+=ms;
        vmaInvalidateAllocation(allocator_,alignmentResult_.allocation,0,static_cast<VkDeviceSize>(resultBytes));
        out.compactGpuReadbackBytes+=resultBytes;
        const auto* chosen=static_cast<const AlignmentScore*>(alignmentResult_.mapped);
        if(!std::isfinite(chosen->response)||chosen->count<64.0f){decision.accepted=false;decision.response=0.0;decision.rejectReason="no valid GPU alignment candidate";return true;}
        decision.applyDx=static_cast<int>(std::lround(chosen->dx)); decision.applyDy=static_cast<int>(std::lround(chosen->dy)); decision.response=chosen->response; decision.estimatedShiftX=-chosen->dx; decision.estimatedShiftY=-chosen->dy;
        return true;
    };
    auto align=[&](VkBuffer a,VkBuffer b,AlignmentDecisionGpu& d)->bool {
        const std::uint32_t maxShift=std::max(1u,request.maxShiftPixels);
        const std::uint32_t coarseRadius=(maxShift/2u)*2u;
        if(!alignmentPass(a,b,0,0,coarseRadius,2u,64u,d)) return false;
        const int coarseDx=d.applyDx, coarseDy=d.applyDy;
        const std::uint32_t fineRadius=(std::min(4u,maxShift)/2u)*2u;
        if(fineRadius>0u && !alignmentPass(a,b,coarseDx,coarseDy,fineRadius,2u,16u,d)) return false;
        const double minResponse=0.03+0.07*std::clamp(static_cast<double>(request.alignmentStrictness),0.0,1.0);
        if(std::abs(d.applyDx)>static_cast<int>(maxShift)||std::abs(d.applyDy)>static_cast<int>(maxShift)){d.rejectReason="shift exceeds maxShiftPixels";return true;}
        if(d.response<minResponse){d.rejectReason="GPU alignment response below threshold";return true;}
        d.accepted=true; d.rejectReason="none"; return true;
    };

    std::vector<VkDescriptorBufferInfo> lastFusionDescriptorInfos;
    auto updateFusionDescriptors=[&](std::uint64_t staticBytes,std::uint64_t statsBytes) {
        const std::vector<VkDescriptorBufferInfo> infos{
            {anchor_.buffer,0,VK_WHOLE_SIZE},{support_.buffer,0,VK_WHOLE_SIZE},{accum_.buffer,0,VK_WHOLE_SIZE},{weight_.buffer,0,VK_WHOLE_SIZE},{weightSq_.buffer,0,VK_WHOLE_SIZE},{correlation_.buffer,0,VK_WHOLE_SIZE},
            {staticField_.buffer,0,static_cast<VkDeviceSize>(staticBytes)},{fusionParams_.buffer,0,48u*sizeof(std::uint32_t)},{finalRaw_.buffer,0,VK_WHOLE_SIZE},{fusionStats_.buffer,0,static_cast<VkDeviceSize>(statsBytes)},
            {finalStats_.buffer,0,4u*sizeof(std::uint32_t)}};
        const bool unchanged = infos.size() == lastFusionDescriptorInfos.size() &&
                std::equal(infos.begin(), infos.end(), lastFusionDescriptorInfos.begin(),
                           [](const VkDescriptorBufferInfo& a, const VkDescriptorBufferInfo& b) {
                               return a.buffer == b.buffer && a.offset == b.offset && a.range == b.range;
                           });
        if (unchanged) {
            ++out.fusionDescriptorSetUpdateSkips;
            return;
        }
        bindBuffers(device,fusionPipeline_,infos);
        lastFusionDescriptorInfos=infos;
        ++out.fusionDescriptorSetUpdates;
    };
    auto dispatchFusion=[&](std::uint32_t mode,std::uint32_t gx,std::uint32_t gy,float& stageMs)->bool {
        VkCommandBuffer cmd=VK_NULL_HANDLE;
        if(!allocCmd(cmd))return false;
        if(!beginCmd(cmd)){return false;}
        VkMemoryBarrier dep{VK_STRUCTURE_TYPE_MEMORY_BARRIER};dep.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT;dep.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&dep,0,nullptr,0,nullptr);
        vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,fusionPipeline_.pipeline);vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,fusionPipeline_.pipelineLayout,0,1,&fusionPipeline_.descriptorSet,0,nullptr);
        FusionPush push{mode};vkCmdPushConstants(cmd,fusionPipeline_.pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(push),&push);vkCmdDispatch(cmd,gx,gy,1);
        if (mode == 1u || mode == 2u || mode == 3u) {
            VkBufferMemoryBarrier toHost{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
            toHost.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            toHost.srcQueueFamilyIndex = toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toHost.buffer = mode == 3u ? fusionStats_.buffer : finalStats_.buffer;
            toHost.size = mode == 3u ? fusionStats_.capacityBytes : 4u*sizeof(std::uint32_t);
            vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,0,nullptr,1,&toHost,0,nullptr);
        }
        if(vkEndCommandBuffer(cmd)!=VK_SUCCESS){out.failureReason="RAW_MULTIFRAME_FUSION_COMMAND_END_FAILED";return false;}
        const bool submittedOk=submitAndWait(cmd,"RAW_MULTIFRAME_FUSION",stageMs);
        if(!submittedOk) return false; return true;
    };

    // DngMerger defines the newest/last selected frame as anchor. Preserve that exact contract.
    if(canonicalize(request.frames.back(),anchor_,nullptr) != CanonicalizeOutcome::SUCCESS) { out.totalMs=elapsedMs(totalStart); return out; }
    out.framesDecoded=1; out.width=request.cropWidth; out.height=request.cropHeight; out.rawUnpackBackend="VULKAN_RAW_CAPTURE_CANONICALIZE_RESIDENT";
    const std::uint64_t defaultStaticBytes=sizeof(float), defaultStatsBytes=sizeof(float)*4u;
    if(!ensureBufferLocked(allocator_,defaultStaticBytes,true,staticField_,out.failureReason)||!ensureBufferLocked(allocator_,defaultStatsBytes,true,fusionStats_,out.failureReason))return out;
    *static_cast<float*>(staticField_.mapped)=0.0f; vmaFlushAllocation(allocator_,staticField_.allocation,0,defaultStaticBytes);
    auto* initParams=static_cast<std::uint32_t*>(fusionParams_.mapped);std::fill(initParams,initParams+48u,0u);
    auto initPutF=[&](std::uint32_t idx,float v){std::uint32_t u;std::memcpy(&u,&v,sizeof(u));initParams[idx]=u;};
    initParams[0]=request.cropWidth;initParams[1]=request.cropHeight;initParams[4]=request.cfaPattern;initParams[5]=request.spectra.enabled?1u:0u;
    initPutF(12,std::clamp(request.alignmentStrictness,0.0f,1.0f));initPutF(13,static_cast<float>(request.payloadWhite));
    initPutF(15,request.spectra.confidence);initPutF(16,request.spectra.temporalAuthority);initPutF(17,1.0f);initPutF(18,1.0f);
    for(int ch=0;ch<4;++ch){initPutF(20+ch,static_cast<float>(request.payloadBlack[ch]));initPutF(24+ch,static_cast<float>(request.spectra.effectiveS[ch]));initPutF(28+ch,static_cast<float>(request.spectra.effectiveO[ch]));initPutF(32+ch,1.0f);initPutF(36+ch,1.0f);}
    initParams[40]=request.computationalHdr?1u:0u;initPutF(41,1.0f);initPutF(42,1.0f);initPutF(43,1.0f);
    vmaFlushAllocation(allocator_,fusionParams_.allocation,0,48u*sizeof(std::uint32_t));
    updateFusionDescriptors(defaultStaticBytes,defaultStatsBytes);
    float initMs=0.0f; if(!dispatchFusion(0u,ceilDiv(request.cropWidth,16u),ceilDiv(request.cropHeight,16u),initMs)){out.totalMs=elapsedMs(totalStart);return out;} out.fusionGpuMs+=initMs;

    StaticConsensus staticConsensus{}; out.supports.resize(request.frames.size()-1u);
    for(std::size_t i=0;i+1u<request.frames.size();++i){
        auto& sr=out.supports[i];
        const float exposureScale = (request.computationalHdr && i < request.exposureScaleToAnchor.size())
                ? std::clamp(request.exposureScaleToAnchor[i], 0.0625f, 16.0f) : 1.0f;
        sr.exposureScaleToAnchor = exposureScale;
        sr.hdrHighlightAuthority = request.computationalHdr && exposureScale < 0.85f;
        sr.hdrShadowAuthority = request.computationalHdr && exposureScale > 1.15f;
        sr.hdrTemporalMainAuthority = request.computationalHdr && !sr.hdrHighlightAuthority && !sr.hdrShadowAuthority;
        const CanonicalizeOutcome supportCanonicalize = canonicalize(request.frames[i],support_,&sr);
        if (supportCanonicalize == CanonicalizeOutcome::FRAME_REJECT) {
            ++out.supportRejected;
            continue;
        }
        if (supportCanonicalize == CanonicalizeOutcome::BACKEND_FAILURE) {
            sr.rejectReason=out.failureReason;
            out.totalMs=elapsedMs(totalStart);
            return out;
        }
        sr.canonicalized=true; ++out.framesDecoded;
        AlignmentDecisionGpu fwd{}; if(!align(anchor_.buffer,support_.buffer,fwd)){out.totalMs=elapsedMs(totalStart);return out;}
        sr.applyDx=fwd.applyDx;sr.applyDy=fwd.applyDy;sr.estimatedShiftX=fwd.estimatedShiftX;sr.estimatedShiftY=fwd.estimatedShiftY;sr.phaseResponse=fwd.response;
        if(!fwd.accepted){sr.rejectReason=fwd.rejectReason;++out.supportRejected;continue;} sr.alignmentAccepted=true;
        float fb=1.0f;
        const bool requireBidirectionalCheck = request.spectra.enabled || request.computationalHdr;
        if(requireBidirectionalCheck){
            AlignmentDecisionGpu rev{};
            if(!align(support_.buffer,anchor_.buffer,rev)){out.totalMs=elapsedMs(totalStart);return out;}
            sr.reverseEstimatedShiftX=rev.estimatedShiftX;
            sr.reverseEstimatedShiftY=rev.estimatedShiftY;
            sr.reversePhaseResponse=rev.response;
            sr.forwardBackwardClosureErrorPixels=rev.accepted
                    ? std::hypot(fwd.estimatedShiftX+rev.estimatedShiftX,fwd.estimatedShiftY+rev.estimatedShiftY)
                    : std::numeric_limits<double>::infinity();
            // RAW alignment searches CFA-safe translations in two-pixel increments. The generic
            // 0.85 px closure sigma makes a single 2 px quantization mismatch score ~0.063, below
            // the HDR gate, effectively demanding exact reciprocity. Use 1.5 px here: one search
            // quantum remains useful with reduced confidence; a 4 px mismatch still scores ~0.03
            // and remains rejected by the unchanged 0.12 HDR threshold.
            fb=rev.accepted?spectra_temporal::forwardBackwardConsistency(
                    fwd.estimatedShiftX,fwd.estimatedShiftY,rev.estimatedShiftX,rev.estimatedShiftY,
                    fwd.response,rev.response,1.5):0.0f;
        }
        sr.forwardBackwardConsistency=fb;
        const float minimumForwardBackward = request.computationalHdr ? 0.12f : 0.08f;
        if(requireBidirectionalCheck && fb < minimumForwardBackward){
            sr.rejectReason=request.computationalHdr ? "hdr_forward_backward_inconsistent" : "forward_backward_inconsistent";
            ++out.supportRejected;
            continue;
        }
        SpectraTemporalObserverResult obs{};
        const double minAlignmentResponse=0.03+0.07*std::clamp(static_cast<double>(request.alignmentStrictness),0.0,1.0);
        const float hdrAlignmentConfidence=request.computationalHdr
                ? static_cast<float>(std::clamp((fwd.response-minAlignmentResponse)/std::max(1.0e-6,0.35-minAlignmentResponse),0.0,1.0))
                : 0.0f;
        float supportWeight=request.computationalHdr
                ? std::clamp((0.35f+0.65f*hdrAlignmentConfidence)*(0.50f+0.50f*fb),0.10f,1.0f)
                : 0.25f;
        if(request.spectra.enabled){
            SpectraTemporalObserverRequest orq{};orq.anchorWidth=request.cropWidth;orq.anchorHeight=request.cropHeight;orq.supportWidth=request.cropWidth;orq.supportHeight=request.cropHeight;orq.residentAnchorRaw16Buffer=anchor_.buffer;orq.residentSupportRaw16Buffer=support_.buffer;
            orq.applyDx=fwd.applyDx;orq.applyDy=fwd.applyDy;orq.strictness=std::clamp(request.alignmentStrictness,0.0f,1.0f);const double minR=0.03+0.07*orq.strictness;orq.alignmentConfidence=static_cast<float>(std::clamp((fwd.response-minR)/std::max(1.0e-6,0.35-minR),0.0,1.0));orq.forwardBackwardConsistency=fb;orq.whiteLevel=static_cast<int>(request.payloadWhite);
            for(int ch=0;ch<4;++ch)orq.blackLevels[ch]=static_cast<int>(request.payloadBlack[ch]);orq.cfaPattern=static_cast<int>(request.cfaPattern);orq.effectiveS=request.spectra.effectiveS;orq.effectiveO=request.spectra.effectiveO;orq.modelConfidence=request.spectra.confidence;orq.temporalAuthority=request.spectra.temporalAuthority;
            obs=observer.execute(physicalDevice,device,computeQueue,commandPool,allocatorOwner,orq);out.observerGpuMs+=obs.totalMs;out.compactGpuReadbackBytes+=obs.compactReadbackBytes;
            if(obs.submissionMayRemainInFlight) out.submissionMayRemainInFlight=true;
            if(!obs.success){out.failureReason="RAW_MULTIFRAME_TEMPORAL_OBSERVER_FAILED_"+obs.failureReason;out.totalMs=elapsedMs(totalStart);return out;}
            sr.repeatedSupportConfidence=staticConsensus.compareAndUpdate(obs);
            const double repeatedAuthority=0.50+0.50*static_cast<double>(sr.repeatedSupportConfidence);
            obs.observerConfidence=std::clamp(obs.observerConfidence*repeatedAuthority,0.0,1.0);
            obs.supportWeight=obs.supportWeight*(0.65f+0.35f*sr.repeatedSupportConfidence);
            obs.valid=obs.valid&&sr.repeatedSupportConfidence>=0.25f;
            supportWeight=obs.supportWeight;sr.spectraObservation=obs;
            if(!obs.valid){sr.rejectReason="SPECTRA observer/consensus rejected support";++out.supportRejected;continue;}
        }
        sr.acceptedForFusion=false; sr.rejectReason=request.fuseSupportFrames?"pending_fusion":"observer_only";
        if(!request.fuseSupportFrames)continue;
        const auto& field=request.spectra.enabled?obs.staticProbabilityField:spectra_temporal::StaticProbabilityField{};
        const std::uint64_t staticBytes=std::max<std::uint64_t>(sizeof(float),field.values.size()*sizeof(float));
        const std::uint32_t sampleStride=32u,sampleCols=ceilDiv(request.cropWidth,sampleStride),sampleRows=ceilDiv(request.cropHeight,sampleStride);const std::uint64_t statsBytes=std::max<std::uint64_t>(sizeof(float)*4u,static_cast<std::uint64_t>(sampleCols)*sampleRows*sizeof(float)*4u);
        if(!ensureBufferLocked(allocator_,staticBytes,true,staticField_,out.failureReason)||!ensureBufferLocked(allocator_,statsBytes,true,fusionStats_,out.failureReason))return out;
        if(field.valid())std::memcpy(staticField_.mapped,field.values.data(),field.values.size()*sizeof(float));else *static_cast<float*>(staticField_.mapped)=0.0f;vmaFlushAllocation(allocator_,staticField_.allocation,0,staticBytes);
        auto* p=static_cast<std::uint32_t*>(fusionParams_.mapped);std::fill(p,p+48u,0u);auto putF=[&](std::uint32_t idx,float v){std::uint32_t u;std::memcpy(&u,&v,sizeof(u));p[idx]=u;};
        p[0]=request.cropWidth;p[1]=request.cropHeight;p[2]=static_cast<std::uint32_t>(fwd.applyDx);p[3]=static_cast<std::uint32_t>(fwd.applyDy);p[4]=request.cfaPattern;p[5]=request.spectra.enabled?1u:0u;p[6]=field.valid()?static_cast<std::uint32_t>(field.columns):0u;p[7]=field.valid()?static_cast<std::uint32_t>(field.rows):0u;p[8]=field.valid()?static_cast<std::uint32_t>(field.cellSize):0u;p[9]=sampleStride;p[10]=sampleCols;p[11]=sampleRows;
        putF(12,std::clamp(request.alignmentStrictness,0.0f,1.0f));putF(13,static_cast<float>(request.payloadWhite));putF(14,supportWeight);putF(15,request.spectra.confidence);putF(16,request.spectra.temporalAuthority);putF(17,request.spectra.enabled?obs.motionConfidence:1.0f);putF(18,fb);putF(19,request.spectra.enabled?obs.meanTemporalCorrelation:0.0f);
        for(int ch=0;ch<4;++ch){putF(20+ch,static_cast<float>(request.payloadBlack[ch]));putF(24+ch,static_cast<float>(request.spectra.effectiveS[ch]));putF(28+ch,static_cast<float>(request.spectra.effectiveO[ch]));putF(32+ch,1.0f);putF(36+ch,1.0f);} // slots 32..39 are legacy ABI padding; frozen physical S/O is never adapted.
        p[40]=request.computationalHdr?1u:0u;putF(41,exposureScale);putF(42,sr.hdrHighlightAuthority?1.0f:0.0f);putF(43,sr.hdrShadowAuthority?1.0f:0.0f);
        vmaFlushAllocation(allocator_,fusionParams_.allocation,0,48u*sizeof(std::uint32_t));updateFusionDescriptors(staticBytes,statsBytes);
        auto* supportStatsWords = static_cast<std::uint32_t*>(finalStats_.mapped);
        supportStatsWords[3] = 0u;
        // Keep mapped-memory maintenance atom-size safe. The allocation is only 16 bytes, so
        // flushing/invalidation of the complete compact counter buffer is simpler and portable.
        vmaFlushAllocation(allocator_,finalStats_.allocation,0,4u*sizeof(std::uint32_t));
        float fuseMs=0.0f;
        if(!dispatchFusion(1u,ceilDiv(request.cropWidth,16u),ceilDiv(request.cropHeight,16u),fuseMs)){out.totalMs=elapsedMs(totalStart);return out;}
        sr.fusionGpuMs=fuseMs;out.fusionGpuMs+=fuseMs;
        vmaInvalidateAllocation(allocator_,finalStats_.allocation,0,4u*sizeof(std::uint32_t));
        sr.fusionContributedPixels = supportStatsWords[3];
        if (sr.fusionContributedPixels == 0u) {
            sr.rejectReason = "no_weighted_pixel_contribution";
            ++out.supportRejected;
            continue;
        }
        sr.acceptedForFusion=true; sr.rejectReason="none"; ++out.supportAccepted;
    }

    if(out.supportAccepted>0){out.residentFusedRawProduced=true;}
    const std::uint32_t sampleStride=32u,sampleCols=ceilDiv(request.cropWidth,sampleStride),sampleRows=ceilDiv(request.cropHeight,sampleStride);const std::uint64_t statsBytes=std::max<std::uint64_t>(sizeof(float)*4u,static_cast<std::uint64_t>(sampleCols)*sampleRows*sizeof(float)*4u);
    if(!ensureBufferLocked(allocator_,statsBytes,true,fusionStats_,out.failureReason))return out;updateFusionDescriptors(std::max<std::uint64_t>(sizeof(float),staticField_.capacityBytes),statsBytes);
    auto* finalStatsWords = static_cast<std::uint32_t*>(finalStats_.mapped);
    finalStatsWords[0] = 0xffffu;
    finalStatsWords[1] = 0u;
    finalStatsWords[2] = 0u;
    finalStatsWords[3] = static_cast<std::uint32_t>(std::min<std::uint64_t>(pixels, std::numeric_limits<std::uint32_t>::max()));
    vmaFlushAllocation(allocator_,finalStats_.allocation,0,4u*sizeof(std::uint32_t));
    float finalMs=0.0f;if(!dispatchFusion(2u,ceilDiv(static_cast<std::uint32_t>((pixels+1u)/2u),16u),1u,finalMs)){out.totalMs=elapsedMs(totalStart);return out;}out.fusionGpuMs+=finalMs;
    vmaInvalidateAllocation(allocator_,finalStats_.allocation,0,4u*sizeof(std::uint32_t));
    out.compactGpuReadbackBytes += 4u*sizeof(std::uint32_t);
    out.finalRawMin = static_cast<std::uint16_t>(std::min<std::uint32_t>(finalStatsWords[0], 0xffffu));
    out.finalRawMax = static_cast<std::uint16_t>(std::min<std::uint32_t>(finalStatsWords[1], 0xffffu));
    out.finalRawSaturatedCount = finalStatsWords[2];
    out.finalRawSaturatedPct = pixels > 0u
            ? (static_cast<double>(out.finalRawSaturatedCount) * 100.0 / static_cast<double>(pixels))
            : 0.0;
    if(request.spectra.enabled && out.supportAccepted>0){float statsMs=0.0f;if(!dispatchFusion(3u,ceilDiv(sampleCols,16u),ceilDiv(sampleRows,16u),statsMs)){out.totalMs=elapsedMs(totalStart);return out;}out.fusionGpuMs+=statsMs;vmaInvalidateAllocation(allocator_,fusionStats_.allocation,0,statsBytes);out.compactGpuReadbackBytes+=statsBytes;const float* v=static_cast<const float*>(fusionStats_.mapped);std::vector<double> scales,eff;std::size_t fallback=0,total=static_cast<std::size_t>(sampleCols)*sampleRows;scales.reserve(total);eff.reserve(total);for(std::size_t i=0;i<total;++i){scales.push_back(v[i*4]);eff.push_back(v[i*4+1]);if(v[i*4+2]>0.5f)++fallback;}const double varianceScaleMean = scales.empty() ? 1.0 : std::accumulate(scales.begin(), scales.end(), 0.0) / static_cast<double>(scales.size());out.spectraFusionVarianceScale=std::clamp(varianceScaleMean,0.02,1.0);out.spectraFusionVarianceP10=percentile(scales,0.10);out.spectraFusionVarianceP50=percentile(scales,0.50);out.spectraFusionVarianceP90=percentile(scales,0.90);out.spectraEffectiveFrameCount=out.spectraFusionVarianceScale > 1.0e-9 ? 1.0 / out.spectraFusionVarianceScale : 1.0;out.spectraEffectiveFrameCountP10=percentile(eff,0.10);out.spectraEffectiveFrameCountP50=percentile(eff,0.50);out.spectraEffectiveFrameCountP90=percentile(eff,0.90);out.spectraLocalFusionFallbackFraction=total?static_cast<double>(fallback)/total:1.0;}

    VkCommandBuffer cmd=VK_NULL_HANDLE;
    if(!allocCmd(cmd))return out;
    if(!beginCmd(cmd)){return out;}VkBufferMemoryBarrier toTransfer{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};toTransfer.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;toTransfer.dstAccessMask=VK_ACCESS_TRANSFER_READ_BIT;toTransfer.srcQueueFamilyIndex=toTransfer.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;toTransfer.buffer=finalRaw_.buffer;toTransfer.size=rawBytes;vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,1,&toTransfer,0,nullptr);VkBufferCopy cp{0,0,rawBytes};vkCmdCopyBuffer(cmd,finalRaw_.buffer,readback_.buffer,1,&cp);VkBufferMemoryBarrier toHost{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};toHost.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;toHost.dstAccessMask=VK_ACCESS_HOST_READ_BIT;toHost.srcQueueFamilyIndex=toHost.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;toHost.buffer=readback_.buffer;toHost.size=rawBytes;vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,0,nullptr,1,&toHost,0,nullptr);if(vkEndCommandBuffer(cmd)!=VK_SUCCESS){out.failureReason="RAW_MULTIFRAME_READBACK_COMMAND_END_FAILED";return out;}float rbSync=0.0f;const auto rbStart=Clock::now();const bool readbackSubmitOk=submitAndWait(cmd,"RAW_MULTIFRAME_FINAL_READBACK",rbSync);if(!readbackSubmitOk)return out;vmaInvalidateAllocation(allocator_,readback_.allocation,0,rawBytes);out.outputRaw16.resize(static_cast<std::size_t>(pixels));std::memcpy(out.outputRaw16.data(),readback_.mapped,static_cast<std::size_t>(rawBytes));out.finalReadbackMs=elapsedMs(rbStart);out.fullFrameGpuReadbackBytes=rawBytes;out.finalReadbackPerformed=true;
    // Phase 13: publish the resident generation only after the complete backend transaction,
    // including the still-required DNG/publication readback, has succeeded.
    if (request.generationId != 0u) {
        residentOutputGeneration_=request.generationId; residentOutputBytes_=rawBytes;
        residentOutputWidth_=request.cropWidth; residentOutputHeight_=request.cropHeight;
        out.residentOutputProduced=true; out.residentOutputGeneration=request.generationId;
    }
    const bool hdrSameOrSimilarExposureMainStack = request.computationalHdr &&
            !request.exposureScaleToAnchor.empty() &&
            std::all_of(request.exposureScaleToAnchor.begin(), request.exposureScaleToAnchor.end(),
                        [](float scale) { return std::isfinite(scale) && scale >= 0.85f && scale <= 1.15f; });
    out.cpuAlignment=false;out.cpuFusion=false;out.cpuFullFrameSupportMaterialization=false;
    out.alignmentBackend="VULKAN_RAW_NCC_EXHAUSTIVE_FINE";
    out.fusionBackend=request.computationalHdr
            ? (hdrSameOrSimilarExposureMainStack
                ? "VULKAN_RAW_HDR_SAME_EXPOSURE_TEMPORAL_CONFIDENCE_FUSION"
                : "VULKAN_RAW_HDR_EXPOSURE_AWARE_CONFIDENCE_FUSION")
            : (request.spectra.enabled?"VULKAN_RAW_SPECTRA_ROBUST_ACCUM":"VULKAN_RAW_ROBUST_ACCUM");
    out.success=true;out.failureReason="none";out.totalMs=elapsedMs(totalStart);return out;
#endif
}

} // namespace bncam::vulkan
