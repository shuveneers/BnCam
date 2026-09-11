#include "VulkanNeuralRawDenoiseBackend.h"
#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_NEURAL_SHADERS_AVAILABLE
#define BNCAM_NEURAL_SHADERS_AVAILABLE 0
#endif
#if BNCAM_NEURAL_SHADERS_AVAILABLE
#include "NeuralConditionSpirv.h"
#include "NeuralConvSpirv.h"
#include "NeuralFilmParamsSpirv.h"
#include "NeuralFilmApplySpirv.h"
#include "NeuralGateSpirv.h"
#include "NeuralAddSpirv.h"
#include "NeuralScaledAddSpirv.h"
#include "NeuralWritebackSpirv.h"
#endif
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <limits>
#include <type_traits>
namespace bncam::vulkan::neural { using namespace bncam::spectra::neural;
VulkanNeuralRawDenoiseBackend::~VulkanNeuralRawDenoiseBackend(){destroy();}
namespace {
template<class T>T fromToken(std::uint64_t token) noexcept{if constexpr(std::is_pointer_v<T>)return reinterpret_cast<T>(static_cast<std::uintptr_t>(token));else return static_cast<T>(token);}
std::uint32_t divUp(std::uint32_t a,std::uint32_t b){return (a+b-1u)/b;}
void barrier(VkCommandBuffer c){VkMemoryBarrier m{VK_STRUCTURE_TYPE_MEMORY_BARRIER};m.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT;m.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_READ_BIT;vkCmdPipelineBarrier(c,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&m,0,nullptr,0,nullptr);}
std::vector<std::uint32_t> spirv(NeuralKernel k){
#if BNCAM_NEURAL_SHADERS_AVAILABLE
 switch(k){case NeuralKernel::Conditioning:return getNeuralConditionSpirv();case NeuralKernel::Conv:return getNeuralConvSpirv();case NeuralKernel::FilmParams:return getNeuralFilmParamsSpirv();case NeuralKernel::FilmApply:return getNeuralFilmApplySpirv();case NeuralKernel::Gate:return getNeuralGateSpirv();case NeuralKernel::Add:return getNeuralAddSpirv();case NeuralKernel::ScaledAdd:return getNeuralScaledAddSpirv();case NeuralKernel::Writeback:return getNeuralWritebackSpirv();default:return {};}
#else
 (void)k;return {};
#endif
}
struct CondPC{std::int32_t tileX,tileY;std::uint32_t tileW,tileH,fullW,fullH,paddedW,paddedH,lscW,lscH,lscChannels,hasLsc;};
struct ConvPC{std::uint32_t inW,inH,inC,outW,outH,outC,kernel,stride,upsample2x,weightHalfBase,biasC4Base;std::int32_t inputGlobalX,inputGlobalY;std::uint32_t domainW,domainH,enforceDomain;};
struct FilmParamPC{std::uint32_t outC,inC,weightHalfBase,biasC4Base,globalFloatBase;};struct BasicPC{std::uint32_t width,height,channels,extra;};
struct WritePC{std::uint32_t tileW,tileH,validX,validY,validW,validH,fullW,fullH;std::int32_t globalX,globalY;float kSigma,qMin,qMax,authority,lumaAuthority,chromaAuthority,detailProtection,lowFrequencyCleanup,adaptiveResponse,clipThreshold,nearThreshold;std::uint32_t writeResidual,writeEvidence,hasConfidence;};
static_assert(sizeof(WritePC)<=128);
}
bool VulkanNeuralRawDenoiseBackend::ensureBuffer(Buffer&b,std::uint64_t bytes,bool host) noexcept{
#if !BNCAM_VMA_HEADER_AVAILABLE
 (void)b;(void)bytes;(void)host;return false;
#else
 if(!allocator_||!bytes)return false;if(b.buffer&&b.allocation&&b.bytes>=bytes&&(!host||b.mapped))return true;if(b.buffer&&b.allocation)vmaDestroyBuffer(allocator_,b.buffer,b.allocation);b={};VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bi.size=bytes;bi.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT|VK_BUFFER_USAGE_TRANSFER_DST_BIT;bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;VmaAllocationCreateInfo ai{};ai.usage=host?VMA_MEMORY_USAGE_AUTO_PREFER_HOST:VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;if(host)ai.flags=VMA_ALLOCATION_CREATE_MAPPED_BIT|VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;VmaAllocationInfo ar{};if(vmaCreateBuffer(allocator_,&bi,&ai,&b.buffer,&b.allocation,&ar)!=VK_SUCCESS){b={};return false;}b.bytes=bytes;b.mapped=ar.pMappedData;return !host||b.mapped;
#endif
}
void VulkanNeuralRawDenoiseBackend::freeBuffer(Buffer&b) noexcept{
#if BNCAM_VMA_HEADER_AVAILABLE
 if(allocator_&&b.buffer&&b.allocation)vmaDestroyBuffer(allocator_,b.buffer,b.allocation);
#endif
 b={};}
bool VulkanNeuralRawDenoiseBackend::createPipelines() noexcept{
#if !BNCAM_NEURAL_SHADERS_AVAILABLE
 return false;
#else
 std::array<VkDescriptorSetLayoutBinding,10> binds{};for(std::uint32_t i=0;i<binds.size();++i){binds[i].binding=i;binds[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;binds[i].descriptorCount=1;binds[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;}VkDescriptorSetLayoutCreateInfo si{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};si.bindingCount=binds.size();si.pBindings=binds.data();if(vkCreateDescriptorSetLayout(device_,&si,nullptr,&pipes_.setLayout)!=VK_SUCCESS)return false;VkPushConstantRange pr{};pr.stageFlags=VK_SHADER_STAGE_COMPUTE_BIT;pr.offset=0;pr.size=128;VkPipelineLayoutCreateInfo li{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};li.setLayoutCount=1;li.pSetLayouts=&pipes_.setLayout;li.pushConstantRangeCount=1;li.pPushConstantRanges=&pr;if(vkCreatePipelineLayout(device_,&li,nullptr,&pipes_.pipelineLayout)!=VK_SUCCESS)return false;for(std::size_t i=0;i<static_cast<std::size_t>(NeuralKernel::Count);++i){auto code=spirv(static_cast<NeuralKernel>(i));if(code.empty())return false;VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};mi.codeSize=code.size()*4u;mi.pCode=code.data();if(vkCreateShaderModule(device_,&mi,nullptr,&pipes_.modules[i])!=VK_SUCCESS)return false;VkPipelineShaderStageCreateInfo st{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};st.stage=VK_SHADER_STAGE_COMPUTE_BIT;st.module=pipes_.modules[i];st.pName="main";VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};ci.stage=st;ci.layout=pipes_.pipelineLayout;if(vkCreateComputePipelines(device_,VK_NULL_HANDLE,1,&ci,nullptr,&pipes_.pipelines[i])!=VK_SUCCESS)return false;}VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,10u*2048u};VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};pi.flags=VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;pi.maxSets=2048;pi.poolSizeCount=1;pi.pPoolSizes=&ps;return vkCreateDescriptorPool(device_,&pi,nullptr,&pipes_.pool)==VK_SUCCESS;
#endif
}
bool VulkanNeuralRawDenoiseBackend::uploadWeights() noexcept {
    if (!model_.valid || !ensureBuffer(weights_, model_.weightsBytes, false) ||
        !ensureBuffer(weightStaging_, model_.weightsBytes, true) ||
        weightStaging_.mapped == nullptr) {
        return false;
    }
    std::memcpy(
            weightStaging_.mapped,
            model_.bytes.data() + model_.weightsFileOffset,
            model_.weightsBytes);
#if BNCAM_VMA_HEADER_AVAILABLE
    vmaFlushAllocation(allocator_, weightStaging_.allocation, 0, model_.weightsBytes);
#endif

    VkCommandBufferAllocateInfo allocateInfo{
            VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocateInfo.commandPool = commandPool_;
    allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocateInfo.commandBufferCount = 1u;
    VkCommandBuffer command = VK_NULL_HANDLE;
    if (vkAllocateCommandBuffers(device_, &allocateInfo, &command) != VK_SUCCESS) {
        return false;
    }

    bool success = false;
    VkFence fence = VK_NULL_HANDLE;
    do {
        VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(command, &beginInfo) != VK_SUCCESS) {
            break;
        }
        VkBufferCopy copy{0u, 0u, static_cast<VkDeviceSize>(model_.weightsBytes)};
        vkCmdCopyBuffer(command, weightStaging_.buffer, weights_.buffer, 1u, &copy);
        if (vkEndCommandBuffer(command) != VK_SUCCESS) {
            break;
        }

        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        if (vkCreateFence(device_, &fenceInfo, nullptr, &fence) != VK_SUCCESS) {
            break;
        }
        VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        submitInfo.commandBufferCount = 1u;
        submitInfo.pCommandBuffers = &command;
        {
            std::lock_guard<std::mutex> queueLock(*queueMutex_);
            if (vkQueueSubmit(queue_, 1u, &submitInfo, fence) != VK_SUCCESS) {
                break;
            }
        }
        success = vkWaitForFences(device_, 1u, &fence, VK_TRUE, UINT64_MAX) == VK_SUCCESS;
    } while (false);

    if (fence != VK_NULL_HANDLE) {
        vkDestroyFence(device_, fence, nullptr);
    }
    vkFreeCommandBuffers(device_, commandPool_, 1u, &command);
    return success;
}

bool VulkanNeuralRawDenoiseBackend::ensureSlots(const NeuralExecutionPlan&p) noexcept{auto tw=p.tiles.tileInputWidth(),th=p.tiles.tileInputHeight(),maxC=p.maxChannels;auto scratch=NeuralTensorShape{tw,th,maxC}.bytesFp16C4();auto cond=NeuralTensorShape{tw,th,14}.bytesFp16C4();auto s0=NeuralTensorShape{tw,th,model_.widths[0]}.bytesFp16C4();auto s1=NeuralTensorShape{ceilDiv(tw,2u),ceilDiv(th,2u),model_.widths[1]}.bytesFp16C4();auto s2=NeuralTensorShape{ceilDiv(tw,4u),ceilDiv(th,4u),model_.widths[2]}.bytesFp16C4();auto logit=NeuralTensorShape{tw,th,4}.bytesFp16C4();auto conf=NeuralTensorShape{tw,th,1}.bytesFp16C4();auto film=std::uint64_t(ceilDiv(2u*model_.widths[3],4u))*8u;for(auto&s:slots_){if(!ensureBuffer(s.conditioning,cond,false)||!ensureBuffer(s.a,scratch,false)||!ensureBuffer(s.b,scratch,false)||!ensureBuffer(s.c,scratch,false)||!ensureBuffer(s.skip0,s0,false)||!ensureBuffer(s.skip1,s1,false)||!ensureBuffer(s.skip2,s2,false)||!ensureBuffer(s.filmParams,film,false)||!ensureBuffer(s.physicsGlobal,256,true)||!ensureBuffer(s.residualLogits,logit,false)||!ensureBuffer(s.posteriorLogits,logit,false)||!ensureBuffer(s.confidenceLogits,conf,false))return false;}return ensureBuffer(dummy_,4096,true);}
VkBuffer VulkanNeuralRawDenoiseBackend::slotBuffer(Slot&s,NeuralSlot q) const noexcept{switch(q){case NeuralSlot::Conditioning:return s.conditioning.buffer;case NeuralSlot::A:return s.a.buffer;case NeuralSlot::B:return s.b.buffer;case NeuralSlot::C:return s.c.buffer;case NeuralSlot::Skip0:return s.skip0.buffer;case NeuralSlot::Skip1:return s.skip1.buffer;case NeuralSlot::Skip2:return s.skip2.buffer;case NeuralSlot::Global:return s.physicsGlobal.buffer;case NeuralSlot::ResidualLogits:return s.residualLogits.buffer;case NeuralSlot::PosteriorLogits:return s.posteriorLogits.buffer;case NeuralSlot::ConfidenceLogits:return s.confidenceLogits.buffer;default:return VK_NULL_HANDLE;}}
std::uint64_t VulkanNeuralRawDenoiseBackend::slotCapacity(const Slot&s,NeuralSlot q) const noexcept{switch(q){case NeuralSlot::Conditioning:return s.conditioning.bytes;case NeuralSlot::A:return s.a.bytes;case NeuralSlot::B:return s.b.bytes;case NeuralSlot::C:return s.c.bytes;case NeuralSlot::Skip0:return s.skip0.bytes;case NeuralSlot::Skip1:return s.skip1.bytes;case NeuralSlot::Skip2:return s.skip2.bytes;case NeuralSlot::Global:return s.physicsGlobal.bytes;case NeuralSlot::ResidualLogits:return s.residualLogits.bytes;case NeuralSlot::PosteriorLogits:return s.posteriorLogits.bytes;case NeuralSlot::ConfidenceLogits:return s.confidenceLogits.bytes;default:return 0;}}
bool VulkanNeuralRawDenoiseBackend::initialize(VkPhysicalDevice pd,VkDevice dev,VkQueue q,VkCommandPool cp,VulkanAllocatorOwner&ao,std::mutex&qm,const void*pkg,std::size_t n,std::uint32_t count) noexcept{std::lock_guard<std::mutex>l(mutex_);destroyLocked();if(!pd||!dev||!q||!cp||!ao.isReady()||!pkg||!n||count<2||count>4)return false;physical_=pd;device_=dev;queue_=q;commandPool_=cp;allocator_=ao.handle();queueMutex_=&qm;packageSha_=sha256(pkg,n);model_=loadNeuralModelPackage(pkg,n);if(!model_.valid||!createPipelines()||!uploadWeights()){diag_.lastFailure=model_.valid?"NEURAL_VULKAN_INIT_FAILED":model_.failureReason;destroyLocked();return false;}slots_.resize(count);for(auto&s:slots_){VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ai.commandPool=cp;ai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;ai.commandBufferCount=1;if(vkAllocateCommandBuffers(dev,&ai,&s.command)!=VK_SUCCESS){destroyLocked();return false;}VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};fi.flags=VK_FENCE_CREATE_SIGNALED_BIT;if(vkCreateFence(dev,&fi,nullptr,&s.fence)!=VK_SUCCESS){destroyLocked();return false;}}diag_.inFlightSlots=count;diag_.modelLoads++;ready_=true;return true;}
void VulkanNeuralRawDenoiseBackend::destroyLocked() noexcept{ready_=false;if(device_){vkDeviceWaitIdle(device_);for(auto&s:slots_){VulkanNeuralResourceBridge::releaseImported(device_,s.imported);if(s.fence)vkDestroyFence(device_,s.fence,nullptr);if(s.command)vkFreeCommandBuffers(device_,commandPool_,1,&s.command);for(Buffer*b:{&s.conditioning,&s.a,&s.b,&s.c,&s.skip0,&s.skip1,&s.skip2,&s.filmParams,&s.physicsGlobal,&s.residualLogits,&s.posteriorLogits,&s.confidenceLogits})freeBuffer(*b);}if(pipes_.pool)vkDestroyDescriptorPool(device_,pipes_.pool,nullptr);for(auto p:pipes_.pipelines)if(p)vkDestroyPipeline(device_,p,nullptr);for(auto m:pipes_.modules)if(m)vkDestroyShaderModule(device_,m,nullptr);if(pipes_.pipelineLayout)vkDestroyPipelineLayout(device_,pipes_.pipelineLayout,nullptr);if(pipes_.setLayout)vkDestroyDescriptorSetLayout(device_,pipes_.setLayout,nullptr);}freeBuffer(weights_);freeBuffer(weightStaging_);freeBuffer(dummy_);slots_.clear();pipes_={};model_={};packageSha_={};physical_=VK_NULL_HANDLE;device_=VK_NULL_HANDLE;queue_=VK_NULL_HANDLE;commandPool_=VK_NULL_HANDLE;allocator_=nullptr;queueMutex_=nullptr;}
void VulkanNeuralRawDenoiseBackend::destroy() noexcept{std::lock_guard<std::mutex>l(mutex_);destroyLocked();}
bool VulkanNeuralRawDenoiseBackend::validateRequestForVulkan(
        const NeuralRawDenoiseRequest& request,
        std::string& why) const noexcept {
    if (request.schemaVersion != kNeuralRawDenoiseRequestSchemaVersion ||
        !request.validResourceShape()) {
        why = "NEURAL_VULKAN_REQUEST_SHAPE_INVALID";
        return false;
    }

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(physical_, &properties);
    const VkDeviceSize storageAlignment =
            std::max<VkDeviceSize>(properties.limits.minStorageBufferOffsetAlignment, 4u);

    const auto tight = [storageAlignment](
            const NeuralResourceView& view,
            std::uint32_t elementBytes) noexcept {
        const std::uint64_t tightRow =
                std::uint64_t{view.width} * view.channels * elementBytes;
        return (view.byteOffset % storageAlignment) == 0u &&
                view.rowStrideBytes == tightRow;
    };
    const auto residentOutput = [](const NeuralResourceView& view) noexcept {
        return view.kind == NeuralResourceKind::VulkanBuffer && view.token != 0u;
    };

    if (request.packedNormalizedRawInput.elementType != NeuralElementType::Fp32 ||
        !tight(request.packedNormalizedRawInput, 4u) ||
        request.cleanPackedRawOutput.elementType != NeuralElementType::Fp32 ||
        !tight(request.cleanPackedRawOutput, 4u) ||
        request.posteriorVarianceOutput.elementType != NeuralElementType::Fp32 ||
        !tight(request.posteriorVarianceOutput, 4u)) {
        why = "NEURAL_VULKAN_V1_REQUIRES_TIGHT_FP32_EXTERNAL_RAW";
        return false;
    }
    if (!residentOutput(request.cleanPackedRawOutput) ||
        !residentOutput(request.posteriorVarianceOutput)) {
        why = "NEURAL_VULKAN_OUTPUTS_REQUIRE_RESIDENT_BUFFERS";
        return false;
    }

    if (request.core.remainingLsc.hasSpatialGainMap) {
        if (request.remainingLscMap.kind != NeuralResourceKind::VulkanBuffer ||
            request.remainingLscMap.elementType != NeuralElementType::Fp32 ||
            !tight(request.remainingLscMap, 4u)) {
            why = "NEURAL_VULKAN_V1_LSC_REQUIRES_TIGHT_FP32_RESIDENT_BUFFER";
            return false;
        }
    }

    if (request.residualDebugRequested) {
        if (!residentOutput(request.boundedResidualDebugOutput) ||
            request.boundedResidualDebugOutput.elementType != NeuralElementType::Fp32 ||
            !tight(request.boundedResidualDebugOutput, 4u)) {
            why = "NEURAL_VULKAN_V1_RESIDUAL_REQUIRES_FP32_RESIDENT_BUFFER";
            return false;
        }
    }

    if (request.originalSaturationEvidenceRequested) {
        if (!residentOutput(request.originalSaturationMaskOutput) ||
            request.originalSaturationMaskOutput.elementType != NeuralElementType::U32 ||
            !tight(request.originalSaturationMaskOutput, 4u) ||
            !residentOutput(request.originalHeadroomEvidenceOutput) ||
            request.originalHeadroomEvidenceOutput.elementType != NeuralElementType::Fp32 ||
            !tight(request.originalHeadroomEvidenceOutput, 4u)) {
            why = "NEURAL_VULKAN_V1_EVIDENCE_REQUIRES_RESIDENT_FP32_U32_BUFFERS";
            return false;
        }
    }

    why = "none";
    return true;
}

std::uint32_t VulkanNeuralRawDenoiseBackend::acquireFreeSlot() noexcept {
    for (std::uint32_t i = 0u; i < slots_.size(); ++i) {
        auto& slot = slots_[i];
        if (!slot.busy) {
            return i;
        }
        if (vkGetFenceStatus(device_, slot.fence) == VK_SUCCESS) {
            // Async callers are allowed not to resolve a ticket. Reclaim only
            // after GPU completion and release any imported AHB ownership.
            slot.busy = false;
            VulkanNeuralResourceBridge::releaseImported(device_, slot.imported);
            ++diag_.completed;
            return i;
        }
    }
    return UINT32_MAX;
}

NeuralVulkanSubmissionTicket VulkanNeuralRawDenoiseBackend::submitAsync(
        const NeuralRawDenoiseRequest& request) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    NeuralVulkanSubmissionTicket ticket{};

    // Exact profile/master bypass: do not import a resource and do not record
    // or dispatch a neural command buffer.
    if (!request.controls.valid() || !request.controls.enabled ||
        request.controls.noiseReduction <= kNeuralAuthorityBypassEpsilon) {
        ticket.accepted = true;
        ticket.immediate.status = NeuralBackendStatus::Bypassed;
        ticket.immediate.bypassReason = !request.controls.valid()
                ? NeuralBypassReason::InvalidControls
                : (!request.controls.enabled
                        ? NeuralBypassReason::NeuralDisabled
                        : NeuralBypassReason::ZeroAuthority);
        ++diag_.bypassed;
        return ticket;
    }

    if (!ready_.load()) {
        ticket.immediate.status = NeuralBackendStatus::Failed;
        ticket.immediate.failureCode = NeuralBackendFailureCode::ModelLoadFailed;
        ticket.immediate.bypassReason = NeuralBypassReason::BackendUnavailable;
        return ticket;
    }

    std::string why;
    if (!validateRequestForVulkan(request, why)) {
        ticket.immediate.status = NeuralBackendStatus::Failed;
        ticket.immediate.failureCode = NeuralBackendFailureCode::InvalidRequest;
        ticket.immediate.bypassReason = NeuralBypassReason::BackendFailure;
        diag_.lastFailure = why;
        ++diag_.failed;
        return ticket;
    }

    const auto plan = buildNeuralExecutionPlan(
            model_,
            request.packedNormalizedRawInput.width,
            request.packedNormalizedRawInput.height,
            static_cast<std::uint32_t>(slots_.size()));
    if (!plan.valid || !ensureSlots(plan)) {
        ticket.immediate.status = NeuralBackendStatus::Failed;
        ticket.immediate.failureCode = NeuralBackendFailureCode::InternalError;
        ticket.immediate.bypassReason = NeuralBypassReason::BackendFailure;
        diag_.lastFailure = plan.valid ? "NEURAL_SLOT_ALLOCATION_FAILED" : plan.failureReason;
        ++diag_.failed;
        return ticket;
    }

    const std::uint32_t slotIndex = acquireFreeSlot();
    if (slotIndex == UINT32_MAX) {
        ticket.immediate.status = NeuralBackendStatus::Failed;
        ticket.immediate.failureCode = NeuralBackendFailureCode::SynchronizationFailed;
        ticket.immediate.bypassReason = NeuralBypassReason::BackendFailure;
        diag_.lastFailure = "NEURAL_NO_FREE_INFLIGHT_SLOT";
        ++diag_.failed;
        return ticket;
    }

    auto input = VulkanNeuralResourceBridge::resolveInput(
            physical_,
            device_,
            request.packedNormalizedRawInput,
            request.packedNormalizedRawGpuFallback);
    if (!input.valid) {
        ticket.immediate.status = NeuralBackendStatus::Failed;
        ticket.immediate.failureCode = NeuralBackendFailureCode::ResourceImportFailed;
        ticket.immediate.bypassReason = NeuralBypassReason::BackendFailure;
        diag_.lastFailure = input.failureReason;
        ++diag_.failed;
        return ticket;
    }

    auto& slot = slots_[slotIndex];
    std::string failure;
    if (!recordAndSubmit(slot, plan, request, input, failure)) {
        VulkanNeuralResourceBridge::releaseImported(device_, input);
        ticket.immediate.status = NeuralBackendStatus::Failed;
        ticket.immediate.failureCode = NeuralBackendFailureCode::DispatchFailed;
        ticket.immediate.bypassReason = NeuralBypassReason::BackendFailure;
        diag_.lastFailure = failure;
        ++diag_.failed;
        return ticket;
    }

    slot.imported = input;
    slot.busy = true;
    slot.generation = ++generation_;
    slot.pending = {};
    slot.pending.status = NeuralBackendStatus::Completed;
    slot.pending.cleanRawWritten = true;
    slot.pending.posteriorVarianceWritten = true;
    slot.pending.boundedResidualDebugWritten = request.residualDebugRequested;
    slot.pending.originalSaturationMaskWritten = request.originalSaturationEvidenceRequested;
    slot.pending.originalHeadroomEvidenceWritten = request.originalSaturationEvidenceRequested;
    slot.pending.dispatchedKernelCount = plan.tileCount * plan.kernelDispatchesPerTile;

    ticket.accepted = true;
    ticket.generation = slot.generation;
    ticket.slot = slotIndex;
    ++diag_.submissions;
    diag_.lastTileCount = plan.tileCount;
    diag_.lastKernelDispatches = slot.pending.dispatchedKernelCount;
    diag_.lastInputPath = input.path;
    diag_.lastFailure = "none";
    return ticket;
}

NeuralRawDenoiseResult VulkanNeuralRawDenoiseBackend::resolve(
        const NeuralVulkanSubmissionTicket& ticket,
        std::uint64_t timeoutNs) noexcept {
    if (ticket.generation == 0u) {
        return ticket.immediate;
    }

    VkFence fence = VK_NULL_HANDLE;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (ticket.slot >= slots_.size()) {
            NeuralRawDenoiseResult result{};
            result.status = NeuralBackendStatus::Failed;
            result.failureCode = NeuralBackendFailureCode::SynchronizationFailed;
            result.bypassReason = NeuralBypassReason::BackendFailure;
            return result;
        }
        const auto& slot = slots_[ticket.slot];
        if (!slot.busy || slot.generation != ticket.generation) {
            NeuralRawDenoiseResult result{};
            result.status = NeuralBackendStatus::Failed;
            result.failureCode = NeuralBackendFailureCode::SynchronizationFailed;
            result.bypassReason = NeuralBypassReason::BackendFailure;
            return result;
        }
        fence = slot.fence;
    }

    // Do not hold the backend state mutex while the GPU is in flight. Other
    // slots/captures may continue to submit concurrently.
    const VkResult waitResult = vkWaitForFences(device_, 1u, &fence, VK_TRUE, timeoutNs);
    if (waitResult != VK_SUCCESS) {
        NeuralRawDenoiseResult result{};
        result.status = NeuralBackendStatus::Failed;
        result.failureCode = NeuralBackendFailureCode::SynchronizationFailed;
        result.bypassReason = NeuralBypassReason::BackendFailure;
        return result;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    auto& slot = slots_[ticket.slot];
    if (!slot.busy || slot.generation != ticket.generation) {
        NeuralRawDenoiseResult result{};
        result.status = NeuralBackendStatus::Failed;
        result.failureCode = NeuralBackendFailureCode::SynchronizationFailed;
        result.bypassReason = NeuralBypassReason::BackendFailure;
        return result;
    }
    slot.busy = false;
    VulkanNeuralResourceBridge::releaseImported(device_, slot.imported);
    ++diag_.completed;
    return slot.pending;
}

NeuralRawDenoiseResult VulkanNeuralRawDenoiseBackend::run(
        const NeuralRawDenoiseRequest& request) noexcept {
    const auto ticket = submitAsync(request);
    if (ticket.generation == 0u) {
        return ticket.immediate;
    }
    return resolve(ticket, UINT64_MAX);
}

NeuralVulkanDiagnostics VulkanNeuralRawDenoiseBackend::diagnostics() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return diag_;
}

NeuralVulkanModelIdentity VulkanNeuralRawDenoiseBackend::modelIdentity() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    NeuralVulkanModelIdentity out{};
    out.ready = ready_.load(std::memory_order_acquire) && model_.valid;
    if (!out.ready) return out;
    out.packageSha = packageSha_;
    out.sourceModelSha = model_.sourceModelSha;
    out.packedWeightsSha = model_.packedWeightsSha;
    out.residualKSigma = model_.residualKSigma;
    out.innerTile = model_.innerTile;
    out.halo = model_.halo;
    return out;
}

// Command encoder helpers are deliberately local. Descriptor sets are allocated
// once per operation/in-flight slot and then reused for every tile; tile-local
// geometry remains in push constants. This avoids tens of thousands of
// descriptor allocations for a full-resolution capture.
bool VulkanNeuralRawDenoiseBackend::recordAndSubmit(
        Slot& slot,
        const NeuralExecutionPlan& plan,
        const NeuralRawDenoiseRequest& request,
        NeuralResolvedGpuInput& input,
        std::string& failure) noexcept {
    try {
        if (vkWaitForFences(device_, 1u, &slot.fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS ||
            vkResetFences(device_, 1u, &slot.fence) != VK_SUCCESS ||
            vkResetCommandBuffer(slot.command, 0u) != VK_SUCCESS) {
            failure = "NEURAL_SLOT_RESET_FAILED";
            return false;
        }
        if (dummy_.mapped == nullptr || slot.physicsGlobal.mapped == nullptr) {
            failure = "NEURAL_HOST_CONTROL_BUFFER_UNMAPPED";
            return false;
        }

        std::memset(dummy_.mapped, 0, std::min<std::uint64_t>(dummy_.bytes, 4096u));
        auto* metadata = static_cast<float*>(slot.physicsGlobal.mapped);
        std::fill(metadata, metadata + 64, 0.0f);
        for (std::size_t i = 0; i < 4u; ++i) {
            metadata[i] = request.core.noise.shotS[i];
            metadata[4u + i] = request.core.noise.readO[i];
        }
        metadata[8] = request.conditioningConfig.logSigmaFloor;
        metadata[9] = request.conditioningConfig.headroomSpan;
        metadata[10] = conservativeSpatialTrust(request.core);

        const auto global = buildGlobalConditioning(request.core, request.framePhysics);
        auto* globalValues = metadata + 16;
        globalValues[0] = std::log(std::max(global.frame.exposureTimeSeconds, 1.0e-12));
        globalValues[1] = std::log(std::max(global.frame.analogGain, 1.0e-8f));
        globalValues[2] = std::log(std::max(global.frame.digitalGain, 1.0e-8f));
        globalValues[3] = static_cast<float>(global.frame.bitDepth) / 32.0f;
        globalValues[4] = global.noiseModelTrust;
        globalValues[5] = global.blackLevelTrust;
        globalValues[6] = global.remainingLscTrust;
        globalValues[7] = global.structuredNoise.rowPeriodicity;
        globalValues[8] = global.structuredNoise.columnPeriodicity;
        globalValues[9] = global.structuredNoise.fixedPattern;
        globalValues[10] = global.structuredNoise.dsnuLike;
        globalValues[11] = global.structuredNoise.prnuLike;
        globalValues[12] = global.structuredNoise.lowFrequencyResidual;
        globalValues[13] = global.structuredNoise.lowFrequencyChroma;
        globalValues[14] = global.structuredNoise.channelImbalance;
        globalValues[15] = global.structuredNoise.spatialBlackDrift;
        globalValues[16] = global.structuredNoise.rareReadoutPattern;
        globalValues[17] = global.structuredNoise.confidence;

#if BNCAM_VMA_HEADER_AVAILABLE
        vmaFlushAllocation(allocator_, slot.physicsGlobal.allocation, 0, 256u);
        vmaFlushAllocation(
                allocator_,
                dummy_.allocation,
                0,
                std::min<std::uint64_t>(dummy_.bytes, 4096u));
#endif

        const auto externalBuffer = [](const NeuralResourceView& view) noexcept -> VkBuffer {
            return view.kind == NeuralResourceKind::VulkanBuffer
                    ? fromToken<VkBuffer>(view.token)
                    : VK_NULL_HANDLE;
        };
        const VkBuffer lsc = request.core.remainingLsc.hasSpatialGainMap
                ? externalBuffer(request.remainingLscMap)
                : dummy_.buffer;
        const VkBuffer clean = externalBuffer(request.cleanPackedRawOutput);
        const VkBuffer posterior = externalBuffer(request.posteriorVarianceOutput);
        const VkBuffer residual = request.residualDebugRequested
                ? externalBuffer(request.boundedResidualDebugOutput)
                : dummy_.buffer;
        const VkBuffer saturationMask = request.originalSaturationEvidenceRequested
                ? externalBuffer(request.originalSaturationMaskOutput)
                : dummy_.buffer;
        const VkBuffer headroomEvidence = request.originalSaturationEvidenceRequested
                ? externalBuffer(request.originalHeadroomEvidenceOutput)
                : dummy_.buffer;
        if (clean == VK_NULL_HANDLE || posterior == VK_NULL_HANDLE ||
            (request.core.remainingLsc.hasSpatialGainMap && lsc == VK_NULL_HANDLE) ||
            (request.residualDebugRequested && residual == VK_NULL_HANDLE) ||
            (request.originalSaturationEvidenceRequested &&
             (saturationMask == VK_NULL_HANDLE || headroomEvidence == VK_NULL_HANDLE))) {
            failure = "NEURAL_EXTERNAL_OUTPUT_OR_LSC_NOT_VULKAN_BUFFER";
            return false;
        }

        VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(slot.command, &beginInfo) != VK_SUCCESS) {
            failure = "NEURAL_COMMAND_BEGIN_FAILED";
            return false;
        }

        // Covers resident upstream RAW and BnCam-owned staging buffers on the
        // same queue. Direct AHB import additionally uses an explicit producer
        // completion contract/semaphore at queue submit.
        VkMemoryBarrier acquireBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        acquireBarrier.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT |
                VK_ACCESS_SHADER_WRITE_BIT |
                VK_ACCESS_TRANSFER_WRITE_BIT;
        acquireBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT |
                VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(
                slot.command,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0u,
                1u,
                &acquireBarrier,
                0u,
                nullptr,
                0u,
                nullptr);

        std::size_t setCursor = 0u;
        auto allocateSet = [&]() -> VkDescriptorSet {
            if (setCursor < slot.sets.size()) {
                return slot.sets[setCursor++];
            }
            VkDescriptorSetLayout layout = pipes_.setLayout;
            VkDescriptorSetAllocateInfo allocateInfo{
                    VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            allocateInfo.descriptorPool = pipes_.pool;
            allocateInfo.descriptorSetCount = 1u;
            allocateInfo.pSetLayouts = &layout;
            VkDescriptorSet set = VK_NULL_HANDLE;
            if (vkAllocateDescriptorSets(device_, &allocateInfo, &set) != VK_SUCCESS) {
                return VK_NULL_HANDLE;
            }
            slot.sets.push_back(set);
            ++setCursor;
            return set;
        };

        bool updateDescriptorSets = true;
        auto dispatch = [&](NeuralKernel kernel,
                            const std::array<VkBuffer, 10>& buffers,
                            const std::array<VkDeviceSize, 10>& offsets,
                            const void* pushConstants,
                            std::uint32_t pushConstantBytes,
                            std::uint32_t groupsX,
                            std::uint32_t groupsY,
                            std::uint32_t groupsZ) -> bool {
            VkDescriptorSet set = allocateSet();
            if (set == VK_NULL_HANDLE) {
                return false;
            }
            if (updateDescriptorSets) {
                std::array<VkDescriptorBufferInfo, 10> infos{};
                std::array<VkWriteDescriptorSet, 10> writes{};
                for (std::uint32_t i = 0u; i < 10u; ++i) {
                    infos[i].buffer = buffers[i] != VK_NULL_HANDLE ? buffers[i] : dummy_.buffer;
                    infos[i].offset = offsets[i];
                    infos[i].range = VK_WHOLE_SIZE;
                    writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                    writes[i].dstSet = set;
                    writes[i].dstBinding = i;
                    writes[i].descriptorCount = 1u;
                    writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                    writes[i].pBufferInfo = &infos[i];
                }
                vkUpdateDescriptorSets(device_, 10u, writes.data(), 0u, nullptr);
            }
            vkCmdBindPipeline(
                    slot.command,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipes_.pipelines[static_cast<std::size_t>(kernel)]);
            vkCmdBindDescriptorSets(
                    slot.command,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipes_.pipelineLayout,
                    0u,
                    1u,
                    &set,
                    0u,
                    nullptr);
            if (pushConstants != nullptr && pushConstantBytes != 0u) {
                vkCmdPushConstants(
                        slot.command,
                        pipes_.pipelineLayout,
                        VK_SHADER_STAGE_COMPUTE_BIT,
                        0u,
                        pushConstantBytes,
                        pushConstants);
            }
            vkCmdDispatch(slot.command, groupsX, groupsY, groupsZ);
            barrier(slot.command);
            return true;
        };

        const auto tensor = [&](const std::string& name) -> const NeuralTensorRecord* {
            return model_.findTensor(name);
        };

        for (std::uint32_t tileIndex = 0u; tileIndex < plan.tileCount; ++tileIndex) {
            setCursor = 0u;
            updateDescriptorSets = tileIndex == 0u;
            const auto tile = neuralTileAt(plan.tiles, tileIndex);
            const std::uint32_t tileWidth = tile.inputWidth;
            const std::uint32_t tileHeight = tile.inputHeight;

            for (const auto& op : plan.ops) {
                const std::uint32_t shapeWidth = ceilDiv(tileWidth, op.spatialDivisor);
                const std::uint32_t shapeHeight = ceilDiv(tileHeight, op.spatialDivisor);

                if (op.primitive == NeuralPrimitive::Conditioning) {
                    CondPC pc{
                            static_cast<std::int32_t>(tile.inputX),
                            static_cast<std::int32_t>(tile.inputY),
                            tileWidth,
                            tileHeight,
                            plan.tiles.fullWidth,
                            plan.tiles.fullHeight,
                            plan.tiles.paddedWidth,
                            plan.tiles.paddedHeight,
                            request.core.remainingLsc.mapWidth,
                            request.core.remainingLsc.mapHeight,
                            request.core.remainingLsc.mapChannels,
                            request.core.remainingLsc.hasSpatialGainMap ? 1u : 0u};
                    std::array<VkBuffer, 10> buffers{
                            input.buffer, lsc, slot.physicsGlobal.buffer, slot.conditioning.buffer};
                    std::array<VkDeviceSize, 10> offsets{
                            input.byteOffset, request.remainingLscMap.byteOffset, 0u, 0u};
                    if (!dispatch(
                                NeuralKernel::Conditioning,
                                buffers,
                                offsets,
                                &pc,
                                sizeof(pc),
                                divUp(tileWidth, 8u),
                                divUp(tileHeight, 8u),
                                1u)) {
                        failure = "NEURAL_CONDITION_DISPATCH_SETUP_FAILED";
                        return false;
                    }
                    continue;
                }

                if (op.primitive == NeuralPrimitive::Copy) {
                    const VkBuffer source = slotBuffer(slot, op.src0);
                    const VkBuffer destination = slotBuffer(slot, op.dst);
                    const std::uint64_t bytes = NeuralTensorShape{
                            shapeWidth, shapeHeight, op.channelsOut}.bytesFp16C4();
                    if (source == VK_NULL_HANDLE || destination == VK_NULL_HANDLE ||
                        bytes > slotCapacity(slot, op.src0) || bytes > slotCapacity(slot, op.dst)) {
                        failure = "NEURAL_COPY_RESOURCE_BOUNDS_INVALID";
                        return false;
                    }
                    VkBufferCopy copy{0u, 0u, static_cast<VkDeviceSize>(bytes)};
                    vkCmdCopyBuffer(slot.command, source, destination, 1u, &copy);
                    barrier(slot.command);
                    continue;
                }

                if (op.primitive == NeuralPrimitive::Conv) {
                    const auto* weight = tensor(op.weight);
                    const auto* bias = tensor(op.bias);
                    if (weight == nullptr || bias == nullptr) {
                        failure = "NEURAL_CONV_TENSOR_MISSING";
                        return false;
                    }
                    std::uint32_t inputDivisor = op.upsample2x
                            ? op.spatialDivisor * 2u
                            : (op.stride == 2u ? op.spatialDivisor / 2u : op.spatialDivisor);
                    inputDivisor = std::max(inputDivisor, 1u);
                    const std::uint32_t inputWidth = ceilDiv(tileWidth, inputDivisor);
                    const std::uint32_t inputHeight = ceilDiv(tileHeight, inputDivisor);
                    ConvPC pc{
                            inputWidth,
                            inputHeight,
                            op.channelsIn,
                            shapeWidth,
                            shapeHeight,
                            op.channelsOut,
                            op.kernel,
                            op.stride,
                            op.upsample2x ? 1u : 0u,
                            static_cast<std::uint32_t>(weight->weightOffset / 2u),
                            static_cast<std::uint32_t>(bias->weightOffset / 8u),
                            static_cast<std::int32_t>(tile.inputX / inputDivisor),
                            static_cast<std::int32_t>(tile.inputY / inputDivisor),
                            ceilDiv(plan.tiles.fullWidth, inputDivisor),
                            ceilDiv(plan.tiles.fullHeight, inputDivisor),
                            (op.weight == "residual_head.weight" ||
                             op.weight == "posterior_head.weight" ||
                             op.weight == "confidence_head.weight") ? 1u : 0u};
                    std::array<VkBuffer, 10> buffers{
                            slotBuffer(slot, op.src0),
                            weights_.buffer,
                            weights_.buffer,
                            slotBuffer(slot, op.dst)};
                    std::array<VkDeviceSize, 10> offsets{};
                    if (!dispatch(
                                NeuralKernel::Conv,
                                buffers,
                                offsets,
                                &pc,
                                sizeof(pc),
                                divUp(shapeWidth, 8u),
                                divUp(shapeHeight, 4u),
                                ceilDiv(op.channelsOut, 4u))) {
                        failure = "NEURAL_CONV_DISPATCH_SETUP_FAILED";
                        return false;
                    }
                    continue;
                }

                if (op.primitive == NeuralPrimitive::Film) {
                    const auto* weight = tensor(op.weight);
                    const auto* bias = tensor(op.bias);
                    if (weight == nullptr || bias == nullptr) {
                        failure = "NEURAL_FILM_TENSOR_MISSING";
                        return false;
                    }
                    FilmParamPC paramPc{
                            2u * op.channelsOut,
                            18u,
                            static_cast<std::uint32_t>(weight->weightOffset / 2u),
                            static_cast<std::uint32_t>(bias->weightOffset / 8u),
                            16u};
                    std::array<VkBuffer, 10> paramBuffers{
                            slot.physicsGlobal.buffer,
                            weights_.buffer,
                            weights_.buffer,
                            slot.filmParams.buffer};
                    std::array<VkDeviceSize, 10> paramOffsets{};
                    if (!dispatch(
                                NeuralKernel::FilmParams,
                                paramBuffers,
                                paramOffsets,
                                &paramPc,
                                sizeof(paramPc),
                                ceilDiv(2u * op.channelsOut, 4u),
                                1u,
                                1u)) {
                        failure = "NEURAL_FILM_PARAMS_FAILED";
                        return false;
                    }

                    BasicPC applyPc{shapeWidth, shapeHeight, op.channelsOut, 0u};
                    std::array<VkBuffer, 10> applyBuffers{
                            slotBuffer(slot, op.src0),
                            slot.filmParams.buffer,
                            slotBuffer(slot, op.dst)};
                    std::array<VkDeviceSize, 10> applyOffsets{};
                    if (!dispatch(
                                NeuralKernel::FilmApply,
                                applyBuffers,
                                applyOffsets,
                                &applyPc,
                                12u,
                                divUp(shapeWidth, 8u),
                                divUp(shapeHeight, 8u),
                                ceilDiv(op.channelsOut, 4u))) {
                        failure = "NEURAL_FILM_APPLY_FAILED";
                        return false;
                    }
                    continue;
                }

                if (op.primitive == NeuralPrimitive::SimpleGate ||
                    op.primitive == NeuralPrimitive::Add) {
                    BasicPC pc{shapeWidth, shapeHeight, op.channelsOut, 0u};
                    const NeuralKernel kernel = op.primitive == NeuralPrimitive::SimpleGate
                            ? NeuralKernel::Gate
                            : NeuralKernel::Add;
                    std::array<VkBuffer, 10> buffers{};
                    buffers[0] = slotBuffer(slot, op.src0);
                    if (op.primitive == NeuralPrimitive::SimpleGate) {
                        buffers[1] = slotBuffer(slot, op.dst);
                    } else {
                        buffers[1] = slotBuffer(slot, op.src1);
                        buffers[2] = slotBuffer(slot, op.dst);
                    }
                    std::array<VkDeviceSize, 10> offsets{};
                    if (!dispatch(
                                kernel,
                                buffers,
                                offsets,
                                &pc,
                                12u,
                                divUp(shapeWidth, 8u),
                                divUp(shapeHeight, 8u),
                                ceilDiv(op.channelsOut, 4u))) {
                        failure = op.primitive == NeuralPrimitive::SimpleGate
                                ? "NEURAL_GATE_FAILED"
                                : "NEURAL_ADD_FAILED";
                        return false;
                    }
                    continue;
                }

                if (op.primitive == NeuralPrimitive::ScaledAdd) {
                    const auto* scale = tensor(op.scale);
                    if (scale == nullptr) {
                        failure = "NEURAL_SCALE_TENSOR_MISSING";
                        return false;
                    }
                    BasicPC pc{
                            shapeWidth,
                            shapeHeight,
                            op.channelsOut,
                            static_cast<std::uint32_t>(scale->weightOffset / 8u)};
                    std::array<VkBuffer, 10> buffers{
                            slotBuffer(slot, op.src0),
                            slotBuffer(slot, op.src1),
                            weights_.buffer,
                            slotBuffer(slot, op.dst)};
                    std::array<VkDeviceSize, 10> offsets{};
                    if (!dispatch(
                                NeuralKernel::ScaledAdd,
                                buffers,
                                offsets,
                                &pc,
                                sizeof(pc),
                                divUp(shapeWidth, 8u),
                                divUp(shapeHeight, 8u),
                                ceilDiv(op.channelsOut, 4u))) {
                        failure = "NEURAL_SCALED_ADD_FAILED";
                        return false;
                    }
                    continue;
                }

                if (op.primitive == NeuralPrimitive::Writeback) {
                    const WritePC pc{
                            tileWidth,
                            tileHeight,
                            tile.validX,
                            tile.validY,
                            tile.centerWidth,
                            tile.centerHeight,
                            plan.tiles.fullWidth,
                            plan.tiles.fullHeight,
                            static_cast<std::int32_t>(tile.centerX),
                            static_cast<std::int32_t>(tile.centerY),
                            model_.residualKSigma,
                            model_.posteriorMin,
                            model_.posteriorMax,
                            std::clamp(request.controls.noiseReduction, 0.0f, 1.0f),
                            std::clamp(request.controls.lumaNoise, 0.0f, 1.0f),
                            std::clamp(request.controls.chromaNoise, 0.0f, 1.0f),
                            std::clamp(request.controls.detailProtection, 0.0f, 1.0f),
                            std::clamp(request.controls.lowFrequencyCleanup, 0.0f, 1.0f),
                            std::clamp(request.controls.adaptiveResponse, 0.0f, 1.0f),
                            1.0f - request.conditioningConfig.clippingEpsilon,
                            1.0f - request.conditioningConfig.headroomSpan,
                            request.residualDebugRequested ? 1u : 0u,
                            request.originalSaturationEvidenceRequested ? 1u : 0u,
                            (model_.flags & 1u) != 0u ? 1u : 0u};
                    std::array<VkBuffer, 10> buffers{
                            input.buffer,
                            slot.conditioning.buffer,
                            slot.residualLogits.buffer,
                            slot.posteriorLogits.buffer,
                            clean,
                            posterior,
                            residual,
                            saturationMask,
                            headroomEvidence,
                            slot.confidenceLogits.buffer};
                    std::array<VkDeviceSize, 10> offsets{
                            input.byteOffset,
                            0u,
                            0u,
                            0u,
                            request.cleanPackedRawOutput.byteOffset,
                            request.posteriorVarianceOutput.byteOffset,
                            request.boundedResidualDebugOutput.byteOffset,
                            request.originalSaturationMaskOutput.byteOffset,
                            request.originalHeadroomEvidenceOutput.byteOffset,
                            0u};
                    if (!dispatch(
                                NeuralKernel::Writeback,
                                buffers,
                                offsets,
                                &pc,
                                sizeof(pc),
                                divUp(tile.centerWidth, 8u),
                                divUp(tile.centerHeight, 8u),
                                1u)) {
                        failure = "NEURAL_WRITEBACK_FAILED";
                        return false;
                    }
                    continue;
                }

                if (op.primitive == NeuralPrimitive::Evidence) {
                    // Evidence is fused into Writeback. Keep the graph marker so
                    // the future reconstruction boundary remains explicit.
                    continue;
                }
            }

            if (setCursor != plan.kernelDispatchesPerTile) {
                failure = "NEURAL_DESCRIPTOR_PLAN_DISPATCH_COUNT_MISMATCH";
                return false;
            }
        }

        if (vkEndCommandBuffer(slot.command) != VK_SUCCESS) {
            failure = "NEURAL_COMMAND_END_FAILED";
            return false;
        }

        VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        VkSubmitInfo submitInfo{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        if (input.waitSemaphore != VK_NULL_HANDLE) {
            submitInfo.waitSemaphoreCount = 1u;
            submitInfo.pWaitSemaphores = &input.waitSemaphore;
            submitInfo.pWaitDstStageMask = &waitStage;
        }
        submitInfo.commandBufferCount = 1u;
        submitInfo.pCommandBuffers = &slot.command;
        {
            std::lock_guard<std::mutex> queueLock(*queueMutex_);
            if (vkQueueSubmit(queue_, 1u, &submitInfo, slot.fence) != VK_SUCCESS) {
                failure = "NEURAL_QUEUE_SUBMIT_FAILED";
                return false;
            }
        }

        failure = "none";
        return true;
    } catch (...) {
        failure = "NEURAL_RECORD_EXCEPTION";
        return false;
    }
}

} // namespace bncam::vulkan::neural
