#include "VulkanSpectraResidentPreDemosaicBackend.h"
#include "VulkanPipelineCacheRegistry.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_SPECTRA_PRE_DEMOSAIC_SHADER_AVAILABLE
#define BNCAM_SPECTRA_PRE_DEMOSAIC_SHADER_AVAILABLE 0
#endif
#if BNCAM_SPECTRA_PRE_DEMOSAIC_SHADER_AVAILABLE
#include "SpectraResidentPreDemosaicSpirv.h"
#endif

#include "VulkanRuntime.h"
#include "../SpectraContextFusionNoRegret.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <numeric>

namespace bncam::vulkan {
namespace {
using Clock = std::chrono::steady_clock;

float elapsedMs(Clock::time_point started) {
    return static_cast<float>(std::chrono::duration<double, std::milli>(
            Clock::now() - started).count());
}

struct alignas(16) PushConstants {
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::uint32_t cfaPattern = 0;
    std::uint32_t mode = 0;
    std::uint32_t tensorColumns = 0;
    std::uint32_t tensorRows = 0;
    std::uint32_t tensorStep = 16;
    std::uint32_t lensColumns = 0;
    std::uint32_t lensRows = 0;
    std::uint32_t gridCols = 0;
    std::uint32_t gridRows = 0;
    std::uint32_t tileWidth = 0;
    std::uint32_t tileHeight = 0;
    float s0 = 0.0f;
    float s1 = 0.0f;
    float s2 = 0.0f;
    float s3 = 0.0f;
    float o0 = 0.0f;
    float o1 = 0.0f;
    float o2 = 0.0f;
    float o3 = 0.0f;
    float blendStrength = 0.0f;
    float maxPixelShift = 0.0f;
    float isoAuthority = 0.0f;
    float maxLinearShift = 0.028f;
    std::uint32_t physicalBaselineMode = 0u;
    float modelConfidence = 0.0f;
    std::uint32_t observationOffset = 0;
    float combinedNoisePressure = 0.0f;
};
static_assert(sizeof(PushConstants) == 128u,
              "resident pre-demosaic push layout must stay within Vulkan minimum guarantee");

struct alignas(16) TileStatsGpu {
    float meanSignal[4]{0.0f, 0.0f, 0.0f, 0.0f};
    float residualEnergy = 0.0f;
    float structureEnergy = 0.0f;
    float sampleCount = 0.0f;
    float populatedChannels = 0.0f;
};
static_assert(sizeof(TileStatsGpu) == 32u, "GLSL TileStats layout mismatch");

constexpr std::uint64_t kTelemetryWordCount = 96u;
constexpr std::uint64_t kTelemetryBytes = kTelemetryWordCount * sizeof(std::uint32_t);


float percentile(std::vector<float> values, float quantile) {
    if (values.empty()) return 0.0f;
    quantile = std::clamp(quantile, 0.0f, 1.0f);
    const std::size_t index = std::min(
            values.size() - 1u,
            static_cast<std::size_t>(std::lround(
                    quantile * static_cast<float>(values.size() - 1u))));
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(index), values.end());
    return values[index];
}

bool checkedMultiply(std::uint64_t a, std::uint64_t b, std::uint64_t& out) {
    if (a == 0u || b == 0u) { out = 0u; return true; }
    if (a > std::numeric_limits<std::uint64_t>::max() / b) return false;
    out = a * b;
    return true;
}

SpectraPass1NoRegretGpuResult decideNoRegret(
        const SpectraResidentPreDemosaicRequest& request,
        const TileStatsGpu* after,
        std::vector<float>& acceptance
) {
    SpectraPass1NoRegretGpuResult result{};
    result.totalTiles = static_cast<int>(request.beforeTiles.size());
    acceptance.assign(request.beforeTiles.size(), 0.0f);
    if (after == nullptr || request.beforeTiles.empty()) return result;

    // Pass0 is a frame-wide additive calibration whose magnitude was already qualified by
    // IspCore's strict CFA residual estimator. A tilewise denoise acceptance map would make a
    // single physical black offset spatially discontinuous (and could reject it for the mean
    // change that is the correction itself). Once this pass is dispatched, blend the calibrated
    // candidate globally. Pass1 retains the ordinary per-tile no-regret path below.
    if (request.pass0Only) {
        std::fill(acceptance.begin(), acceptance.end(), 1.0f);
        result.evaluatedTiles = result.totalTiles;
        result.acceptedTiles = result.totalTiles;
        result.invalidTiles = 0;
        result.rejectedTiles = 0;
        result.meanAcceptance = 1.0f;
        result.meanRiskImprovement = 0.0f;
        result.meanColourShift = 0.0f;
        result.maxColourShift = 0.0f;
        result.edgePreservationScore = 1.0f;
        result.oversmoothingScore = 0.0f;
        result.acceptanceP10 = 1.0f;
        result.acceptanceP50 = 1.0f;
        result.acceptanceP90 = 1.0f;
        return result;
    }

    std::vector<float> evaluatedAcceptance;
    evaluatedAcceptance.reserve(request.beforeTiles.size());
    double acceptanceSum = 0.0;
    double improvementSum = 0.0;
    double colourShiftSum = 0.0;
    double edgePreservationSum = 0.0;
    double oversmoothingSum = 0.0;
    float maximumColourShift = 0.0f;

    for (std::size_t i = 0; i < request.beforeTiles.size(); ++i) {
        const SpectraPass1BeforeTile& b = request.beforeTiles[i];
        const TileStatsGpu& a = after[i];
        const std::uint32_t sampleCount = a.sampleCount > 0.0f
                ? static_cast<std::uint32_t>(std::lround(a.sampleCount)) : 0u;
        const std::uint32_t populated = a.populatedChannels > 0.0f
                ? static_cast<std::uint32_t>(std::lround(a.populatedChannels)) : 0u;
        const bool afterValid = populated == 4u && sampleCount >= 32u;
        const float sampleCoverage = std::clamp(static_cast<float>(sampleCount) / 96.0f, 0.0f, 1.0f);
        const float channelCoverage = std::clamp(static_cast<float>(populated) / 4.0f, 0.0f, 1.0f);
        const float afterConfidence = sampleCoverage * channelCoverage *
                (0.35f + 0.65f * std::clamp(request.modelConfidence, 0.0f, 1.0f));
        if (!b.valid || !afterValid || b.confidence < 0.05f || afterConfidence < 0.05f) continue;
        result.evaluatedTiles++;

        const float beforeEnergy = b.residualEnergy;
        const float afterEnergy = std::max(0.0f, a.residualEnergy);
        const float target = std::max(1.0e-12f, b.predictedSpatialResidualVariance) *
                request.targetFloorScale;
        const auto risk = [target](float energy) {
            const float ratio = std::max(0.04f, energy / target);
            if (ratio < 1.0f) {
                return 0.35f * std::abs(std::log(std::max(0.40f, ratio)));
            }
            return std::log(ratio);
        };
        const float riskImprovement = risk(beforeEnergy) - risk(afterEnergy);
        improvementSum += riskImprovement;

        const float minimumEnergy = target * request.minimumResidualRatio;
        const auto contextDecision = bncam::spectra::resolveContextFusionNoRegret({
                beforeEnergy,
                afterEnergy,
                target,
                b.structureEnergy,
                std::max(0.0f, a.structureEnergy),
                request.minimumResidualRatio,
                request.detailRetentionFloor,
                b.confidence,
                riskImprovement,
                true
        });
        const float structureRetention = contextDecision.structureRetention;
        const float beforeGreen = 0.5f * (b.meanSignal[1] + b.meanSignal[2]);
        const float afterGreen = 0.5f * (a.meanSignal[1] + a.meanSignal[2]);
        const float deltaRg = (a.meanSignal[0] - afterGreen) - (b.meanSignal[0] - beforeGreen);
        const float deltaBg = (a.meanSignal[3] - afterGreen) - (b.meanSignal[3] - beforeGreen);
        const float colourShift = std::sqrt(deltaRg * deltaRg + deltaBg * deltaBg);
        colourShiftSum += colourShift;
        maximumColourShift = std::max(maximumColourShift, colourShift);
        edgePreservationSum += std::clamp(structureRetention, 0.0f, 1.0f);
        // Report candidate pressure, while final acceptance is continuously constrained
        // so the blended output does not cross this floor.
        const float oversmoothingRisk = minimumEnergy > 1.0e-12f
                ? std::clamp((minimumEnergy - afterEnergy) / minimumEnergy, 0.0f, 1.0f) : 0.0f;
        oversmoothingSum += oversmoothingRisk;

        bool meanDrift = false;
        for (int ch = 0; ch < 4; ++ch) {
            const float channelDrift = std::abs(a.meanSignal[ch] - b.meanSignal[ch]);
            const float channelVariance = std::max(1.0e-12f, b.predictedRawVarianceByChannel[ch]);
            const float driftLimit = std::max(
                    0.00025f,
                    (1.20f + 0.35f * request.combinedNoisePressure) * std::sqrt(channelVariance));
            if (channelDrift > driftLimit) { meanDrift = true; break; }
        }
        float tileAcceptance = 0.0f;
        if (meanDrift) {
            result.rejectedMeanDrift++;
        } else if (contextDecision.worsened || contextDecision.noImprovement) {
            result.rejectedNoImprovement++;
        } else {
            tileAcceptance = contextDecision.acceptance;
            if (tileAcceptance <= 0.0f) {
                if (contextDecision.residualFloorLimited) result.rejectedOversmooth++;
                else if (contextDecision.detailFloorLimited) result.rejectedDetailLoss++;
                else result.rejectedNoImprovement++;
            } else if (tileAcceptance >= 0.90f) {
                result.acceptedTiles++;
            } else {
                result.partiallyAcceptedTiles++;
            }
        }
        acceptance[i] = tileAcceptance;
        evaluatedAcceptance.push_back(tileAcceptance);
        acceptanceSum += tileAcceptance;
    }

    result.invalidTiles = std::max(0, result.totalTiles - result.evaluatedTiles);
    result.rejectedTiles = std::max(
            0, result.evaluatedTiles - result.acceptedTiles - result.partiallyAcceptedTiles);
    if (result.evaluatedTiles > 0) {
        const double inv = 1.0 / static_cast<double>(result.evaluatedTiles);
        result.meanAcceptance = static_cast<float>(acceptanceSum * inv);
        result.meanRiskImprovement = static_cast<float>(improvementSum * inv);
        result.meanColourShift = static_cast<float>(colourShiftSum * inv);
        result.maxColourShift = maximumColourShift;
        result.edgePreservationScore = static_cast<float>(edgePreservationSum * inv);
        result.oversmoothingScore = static_cast<float>(oversmoothingSum * inv);
        result.acceptanceP10 = percentile(evaluatedAcceptance, 0.10f);
        result.acceptanceP50 = percentile(evaluatedAcceptance, 0.50f);
        result.acceptanceP90 = percentile(evaluatedAcceptance, 0.90f);
    }
    return result;
}
} // namespace

bool VulkanSpectraResidentPreDemosaicBackend::productionKernelConnected() const noexcept {
#if BNCAM_SPECTRA_PRE_DEMOSAIC_SHADER_AVAILABLE
    return !getSpectraResidentPreDemosaicSpirv().empty();
#else
    return false;
#endif
}

bool VulkanSpectraResidentPreDemosaicBackend::pipelineInitialized() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return initialized_;
}

bool VulkanSpectraResidentPreDemosaicBackend::ensureBufferLocked(
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
        failureReason = "INVALID_PRE_DEMOSAIC_BUFFER_REQUEST";
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
    info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT |
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
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
        failureReason = "vmaCreateBuffer_resident_pre_demosaic_failed_" + std::to_string(create);
        return false;
    }
    buffer.mapped = allocationResult.pMappedData;
    buffer.capacityBytes = bytes;
    reallocated = true;
    allocationGeneration_++;
    return true;
#endif
}

void VulkanSpectraResidentPreDemosaicBackend::destroyBuffersLocked() noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr) {
        for (PersistentBuffer* buffer : {
                &input_, &candidate_, &tensor_, &lensShading_, &tileStatistics_,
                &acceptance_, &telemetry_, &observation_}) {
            if (buffer->buffer != VK_NULL_HANDLE && buffer->allocation != nullptr) {
                vmaDestroyBuffer(allocator_, buffer->buffer, buffer->allocation);
            }
            *buffer = {};
        }
    }
#endif
    allocator_ = nullptr;
}

void VulkanSpectraResidentPreDemosaicBackend::destroyLocked(VkDevice device) noexcept {
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
    lensShadingGenerationId_ = 0u;
    lensShadingGenerationBytes_ = 0u;
    residentOutputGeneration_ = 0u;
    residentOutputBytes_ = 0u;
    residentOutputWidth_ = 0u;
    residentOutputHeight_ = 0u;
    residentOutputIsInput_ = false;
}

void VulkanSpectraResidentPreDemosaicBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanSpectraResidentPreDemosaicBackend::initializeLocked(
        VkDevice device,
        VkCommandPool commandPool,
        std::string& failureReason
) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device && initializedCommandPool_ == commandPool) return true;
        failureReason = "PRE_DEMOSAIC_BACKEND_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_SPECTRA_PRE_DEMOSAIC_SHADER_AVAILABLE
    (void)device; (void)commandPool;
    failureReason = "RESIDENT_PRE_DEMOSAIC_SHADER_NOT_COMPILED";
    return false;
#else
    const auto& spirv = getSpectraResidentPreDemosaicSpirv();
    if (device == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE || spirv.empty()) {
        failureReason = "PRE_DEMOSAIC_INITIALIZATION_INPUT_INVALID";
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
        failureReason = "vkCreateDescriptorSetLayout_pre_demosaic_failed";
        destroyLocked(device);
        return false;
    }
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0u;
    pushRange.size = sizeof(PushConstants);
    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1u;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;
    layoutInfo.pushConstantRangeCount = 1u;
    layoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "vkCreatePipelineLayout_pre_demosaic_failed";
        destroyLocked(device);
        return false;
    }
    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_pre_demosaic_failed";
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
        failureReason = "vkCreateComputePipelines_pre_demosaic_failed";
        destroyLocked(device);
        return false;
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
        failureReason = "vkCreateDescriptorPool_pre_demosaic_failed";
        destroyLocked(device);
        return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1u;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "vkAllocateDescriptorSets_pre_demosaic_failed";
        destroyLocked(device);
        return false;
    }
    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &commandInfo, &commandBuffer_) != VK_SUCCESS) {
        failureReason = "vkAllocateCommandBuffers_pre_demosaic_failed";
        destroyLocked(device);
        return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence_) != VK_SUCCESS) {
        failureReason = "vkCreateFence_pre_demosaic_failed";
        destroyLocked(device);
        return false;
    }
    VkQueryPoolCreateInfo queryInfo{};
    queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryInfo.queryCount = 7u;
    if (vkCreateQueryPool(device, &queryInfo, nullptr, &queryPool_) != VK_SUCCESS) {
        // Timestamp queries are observability only. Execution remains correct without them.
        queryPool_ = VK_NULL_HANDLE;
    }
    initialized_ = true;
    initializedDevice_ = device;
    initializedCommandPool_ = commandPool;
    return true;
#endif
}

void VulkanSpectraResidentPreDemosaicBackend::updateDescriptorSetLocked(
        VkDevice device,
        VkBuffer sourceBuffer,
        VkBuffer candidateBuffer
) noexcept {
    const VkBuffer buffers[8] = {
        sourceBuffer, candidateBuffer, tensor_.buffer, lensShading_.buffer,
        tileStatistics_.buffer, acceptance_.buffer, telemetry_.buffer, observation_.buffer
    };
    VkDescriptorBufferInfo infos[8]{};
    VkWriteDescriptorSet writes[8]{};
    for (std::uint32_t i = 0; i < 8u; ++i) {
        infos[i].buffer = buffers[i];
        infos[i].offset = 0u;
        infos[i].range = VK_WHOLE_SIZE;
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


bool VulkanSpectraResidentPreDemosaicBackend::resolveResidentOutput(
        std::uint64_t generation,
        VkBuffer& buffer,
        std::uint64_t& bytes,
        std::uint32_t& width,
        std::uint32_t& height
) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    const VkBuffer residentBuffer = residentOutputIsInput_ ? input_.buffer : candidate_.buffer;
    if (generation == 0u || generation != residentOutputGeneration_ ||
        residentBuffer == VK_NULL_HANDLE || residentOutputBytes_ == 0u) {
        return false;
    }
    buffer = residentBuffer;
    bytes = residentOutputBytes_;
    width = residentOutputWidth_;
    height = residentOutputHeight_;
    return true;
}

bool VulkanSpectraResidentPreDemosaicBackend::readbackResidentOutput(
        VkDevice device,
        VkQueue computeQueue,
        std::uint64_t generation,
        std::vector<float>& output
) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_PRE_DEMOSAIC_SHADER_AVAILABLE
    (void)device; (void)computeQueue; (void)generation; (void)output;
    return false;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    output.clear();
    if (!initialized_ || device == VK_NULL_HANDLE || device != initializedDevice_ ||
        computeQueue == VK_NULL_HANDLE || generation == 0u ||
        generation != residentOutputGeneration_ || residentOutputBytes_ == 0u ||
        residentOutputWidth_ == 0u || residentOutputHeight_ == 0u ||
        candidate_.buffer == VK_NULL_HANDLE || input_.buffer == VK_NULL_HANDLE ||
        input_.mapped == nullptr || input_.capacityBytes < residentOutputBytes_) {
        return false;
    }

    const VkBuffer residentBuffer = residentOutputIsInput_ ? input_.buffer : candidate_.buffer;
    if (residentBuffer == VK_NULL_HANDLE) return false;
    if (residentOutputIsInput_) {
        vmaInvalidateAllocation(allocator_, input_.allocation, 0u,
                                static_cast<VkDeviceSize>(residentOutputBytes_));
        const std::size_t floatCount =
                static_cast<std::size_t>(residentOutputBytes_ / sizeof(float));
        output.resize(floatCount);
        std::memcpy(output.data(), input_.mapped, static_cast<std::size_t>(residentOutputBytes_));
        return true;
    }

    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) return false;

    VkBufferMemoryBarrier toTransfer{};
    toTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.buffer = residentBuffer;
    toTransfer.offset = 0u;
    toTransfer.size = static_cast<VkDeviceSize>(residentOutputBytes_);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr,
                         1u, &toTransfer, 0u, nullptr);

    VkBufferCopy copy{};
    copy.size = static_cast<VkDeviceSize>(residentOutputBytes_);
    vkCmdCopyBuffer(commandBuffer_, residentBuffer, input_.buffer, 1u, &copy);

    VkBufferMemoryBarrier toHost{};
    toHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.buffer = input_.buffer;
    toHost.offset = 0u;
    toHost.size = static_cast<VkDeviceSize>(residentOutputBytes_);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr,
                         1u, &toHost, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) return false;

    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer_;
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS ||
        vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("Pass1_Readback");
        return false;
    }

    vmaInvalidateAllocation(allocator_, input_.allocation, 0u,
                            static_cast<VkDeviceSize>(residentOutputBytes_));
    const std::size_t floatCount =
            static_cast<std::size_t>(residentOutputBytes_ / sizeof(float));
    output.resize(floatCount);
    std::memcpy(output.data(), input_.mapped, static_cast<std::size_t>(residentOutputBytes_));
    return true;
#endif
}


SpectraResidentPreDemosaicResult VulkanSpectraResidentPreDemosaicBackend::executePass1(
        VkPhysicalDevice physicalDevice,
        VkDevice device,
        VkQueue computeQueue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        const SpectraResidentPreDemosaicRequest& request
) noexcept {
    SpectraResidentPreDemosaicResult result{};
    result.attempted = true;
    result.pass0Only = request.pass0Only;
    result.pipelineAvailable = productionKernelConnected();
    const auto totalStarted = Clock::now();
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)physicalDevice; (void)device; (void)computeQueue; (void)commandPool; (void)allocatorOwner; (void)request;
    result.status = "VMA_HEADER_NOT_AVAILABLE";
    result.failureReason = "SPECTRA_PASS1_VULKAN_REQUIRES_VMA";
    result.totalMs = elapsedMs(totalStarted);
    return result;
#else
    if (!result.pipelineAvailable) {
        result.status = "PRE_DEMOSAIC_SHADER_UNAVAILABLE";
        result.failureReason = "SPECTRA_PASS1_RESIDENT_SHADER_NOT_CONNECTED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    const bool externalResidentInputRequested = request.externalResidentInputBuffer != VK_NULL_HANDLE;
    const bool internalResidentInputRequested = request.residentInputGeneration != 0u;
    const bool residentInputRequested = externalResidentInputRequested || internalResidentInputRequested;
    const bool hostInputRequested = !residentInputRequested;
    if (externalResidentInputRequested && internalResidentInputRequested) {
        result.status = "PRE_DEMOSAIC_REQUEST_INVALID";
        result.failureReason = "AMBIGUOUS_INTERNAL_AND_EXTERNAL_RESIDENT_INPUT";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    if (device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE ||
        (hostInputRequested && request.mosaicData == nullptr) || request.frameWidth < 9u || request.frameHeight < 9u ||
        (hostInputRequested && request.rowStrideFloats < request.frameWidth) || request.noRegretGridCols == 0u ||
        request.noRegretGridRows == 0u || request.noRegretTileWidth == 0u || request.noRegretTileHeight == 0u ||
        request.beforeTiles.size() != static_cast<std::size_t>(
                request.noRegretGridCols * request.noRegretGridRows)) {
        result.status = "PRE_DEMOSAIC_REQUEST_INVALID";
        result.failureReason = "INVALID_SPECTRA_PASS1_RESIDENT_REQUEST";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }

    std::uint64_t pixelCount = 0u;
    if (!checkedMultiply(request.frameWidth, request.frameHeight, pixelCount) ||
        !checkedMultiply(pixelCount, sizeof(float), result.inputBytes)) {
        result.status = "PRE_DEMOSAIC_SIZE_OVERFLOW";
        result.failureReason = "MOSAIC_SIZE_OVERFLOW";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.candidateBytes = result.inputBytes;
    result.outputBytes = result.inputBytes;
    const std::uint64_t tileCount = request.beforeTiles.size();
    result.tileStatisticsBytes = tileCount * sizeof(TileStatsGpu);
    result.acceptanceBytes = tileCount * sizeof(float);
    const std::uint64_t tensorCellCount = std::max<std::uint64_t>(
            1u, static_cast<std::uint64_t>(request.tensorColumns) * request.tensorRows);
    result.tensorBytes = tensorCellCount * 4u * sizeof(float);
    const std::uint64_t lensFloatCount = request.lensShadingMap != nullptr &&
            request.lensShadingColumns > 0u && request.lensShadingRows > 0u
            ? static_cast<std::uint64_t>(request.lensShadingColumns) * request.lensShadingRows * 4u
            : 4u;
    result.lensShadingBytes = lensFloatCount * sizeof(float);

    const std::uint32_t rawObservationDispatchX = (request.frameWidth + 3u) / 4u;
    const std::uint32_t rawObservationDispatchY = (request.frameHeight + 3u) / 4u;
    const std::uint32_t rawObservationGroupsX = (rawObservationDispatchX + 15u) / 16u;
    const std::uint32_t rawObservationGroupsY = (rawObservationDispatchY + 15u) / 16u;
    const std::uint64_t rawObservationGroupCount =
            static_cast<std::uint64_t>(rawObservationGroupsX) * rawObservationGroupsY;
    const std::uint32_t bandObservationDispatchX = (request.frameWidth + 15u) / 16u;
    const std::uint32_t bandObservationDispatchY = (request.frameHeight + 15u) / 16u;
    const std::uint32_t bandObservationGroupsX = (bandObservationDispatchX + 15u) / 16u;
    const std::uint32_t bandObservationGroupsY = (bandObservationDispatchY + 15u) / 16u;
    const std::uint64_t bandObservationGroupCount =
            static_cast<std::uint64_t>(bandObservationGroupsX) * bandObservationGroupsY;
    const std::uint64_t observationVec4Count = std::max<std::uint64_t>(
            1u, rawObservationGroupCount + 4u * bandObservationGroupCount);
    const std::uint64_t observationBytes = observationVec4Count * 4u * sizeof(float);

    std::lock_guard<std::mutex> lock(mutex_);
    if (!initializeLocked(device, commandPool, result.failureReason)) {
        result.status = "PRE_DEMOSAIC_INITIALIZATION_FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr) {
        result.status = "PRE_DEMOSAIC_ALLOCATOR_UNAVAILABLE";
        result.failureReason = "AUTHORITATIVE_VMA_ALLOCATOR_NULL";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }

    bool reallocated = false;
    const auto ensure = [&](std::uint64_t bytes, std::uint32_t hostAccess, PersistentBuffer& buffer) -> bool {
        bool changed = false;
        if (!ensureBufferLocked(allocator_, bytes, hostAccess, buffer, changed, result.failureReason)) return false;
        reallocated = reallocated || changed;
        return true;
    };
    if (!ensure(result.inputBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT, input_) ||
        !ensure(result.candidateBytes, 0u, candidate_) ||
        !ensure(result.tensorBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT, tensor_) ||
        !ensure(result.lensShadingBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT, lensShading_) ||
        !ensure(result.tileStatisticsBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT, tileStatistics_) ||
        !ensure(result.acceptanceBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT, acceptance_) ||
        !ensure(kTelemetryBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT, telemetry_) ||
        !ensure(observationBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT, observation_)) {
        result.status = "PRE_DEMOSAIC_BUFFER_ALLOCATION_FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = input_.capacityBytes + candidate_.capacityBytes + tensor_.capacityBytes +
            lensShading_.capacityBytes + tileStatistics_.capacityBytes + acceptance_.capacityBytes +
            telemetry_.capacityBytes + observation_.capacityBytes;

    VkBuffer sourceBuffer = input_.buffer;
    VkBuffer outputBuffer = candidate_.buffer;
    if (externalResidentInputRequested) {
        if (request.externalResidentInputBytes < result.inputBytes) {
            result.status = "PRE_DEMOSAIC_RESIDENT_INPUT_UNAVAILABLE";
            result.failureReason = "EXTERNAL_RESIDENT_INPUT_SIZE_MISMATCH";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        sourceBuffer = request.externalResidentInputBuffer;
        outputBuffer = candidate_.buffer;
        result.residentInputConsumed = true;
        result.externalResidentInputConsumed = true;
    } else if (internalResidentInputRequested) {
        if (request.residentInputGeneration != residentOutputGeneration_ ||
            residentOutputBytes_ != result.inputBytes ||
            residentOutputWidth_ != request.frameWidth || residentOutputHeight_ != request.frameHeight) {
            result.status = "PRE_DEMOSAIC_RESIDENT_INPUT_UNAVAILABLE";
            result.failureReason = "RESIDENT_PASS0_OR_PASS1_GENERATION_MISMATCH";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        const PersistentBuffer* sourceStorage = residentOutputIsInput_ ? &input_ : &candidate_;
        const PersistentBuffer* outputStorage = residentOutputIsInput_ ? &candidate_ : &input_;
        sourceBuffer = sourceStorage->buffer;
        outputBuffer = outputStorage->buffer;
        result.residentInputConsumed = true;
    } else {
        const auto packingStarted = Clock::now();
        float* packed = static_cast<float*>(input_.mapped);
        if (request.rowStrideFloats == request.frameWidth) {
            std::memcpy(packed, request.mosaicData, static_cast<std::size_t>(result.inputBytes));
        } else {
            for (std::uint32_t y = 0; y < request.frameHeight; ++y) {
                std::memcpy(packed + static_cast<std::size_t>(y) * request.frameWidth,
                            request.mosaicData + static_cast<std::size_t>(y) * request.rowStrideFloats,
                            static_cast<std::size_t>(request.frameWidth) * sizeof(float));
            }
        }
        vmaFlushAllocation(allocator_, input_.allocation, 0u, result.inputBytes);
        result.inputPackingMs = elapsedMs(packingStarted);
    }
    if (!request.pass0Only) {
        const auto tensorStarted = Clock::now();
        if (request.tensorCells != nullptr && request.tensorColumns > 0u && request.tensorRows > 0u) {
            std::memcpy(tensor_.mapped, request.tensorCells, static_cast<std::size_t>(result.tensorBytes));
        } else {
            std::memset(tensor_.mapped, 0, static_cast<std::size_t>(result.tensorBytes));
        }
        vmaFlushAllocation(allocator_, tensor_.allocation, 0u, result.tensorBytes);
        result.tensorUploadMs = elapsedMs(tensorStarted);

        const bool uploadLens = reallocated || lensShadingGenerationId_ != request.lensShadingGenerationId ||
                lensShadingGenerationBytes_ != result.lensShadingBytes;
        if (uploadLens) {
            if (request.lensShadingMap != nullptr && request.lensShadingColumns > 0u && request.lensShadingRows > 0u) {
                std::memcpy(lensShading_.mapped, request.lensShadingMap,
                            static_cast<std::size_t>(result.lensShadingBytes));
            } else {
                float* gains = static_cast<float*>(lensShading_.mapped);
                gains[0] = gains[1] = gains[2] = gains[3] = 1.0f;
            }
            vmaFlushAllocation(allocator_, lensShading_.allocation, 0u, result.lensShadingBytes);
            lensShadingGenerationId_ = request.lensShadingGenerationId;
            lensShadingGenerationBytes_ = result.lensShadingBytes;
        }
    }
    std::memset(tileStatistics_.mapped, 0, static_cast<std::size_t>(result.tileStatisticsBytes));
    std::memset(telemetry_.mapped, 0, static_cast<std::size_t>(kTelemetryBytes));
    std::memset(observation_.mapped, 0, static_cast<std::size_t>(observationBytes));
    vmaFlushAllocation(allocator_, tileStatistics_.allocation, 0u, result.tileStatisticsBytes);
    vmaFlushAllocation(allocator_, telemetry_.allocation, 0u, kTelemetryBytes);
    vmaFlushAllocation(allocator_, observation_.allocation, 0u, observationBytes);
    updateDescriptorSetLocked(device, sourceBuffer, outputBuffer);

    PushConstants push{};
    push.frameWidth = request.frameWidth;
    push.frameHeight = request.frameHeight;
    push.cfaPattern = request.cfaPattern;
    push.tensorColumns = request.tensorColumns;
    push.tensorRows = request.tensorRows;
    push.tensorStep = std::max(1u, request.tensorStep);
    push.lensColumns = request.lensShadingMap != nullptr ? request.lensShadingColumns : 0u;
    push.lensRows = request.lensShadingMap != nullptr ? request.lensShadingRows : 0u;
    push.gridCols = request.noRegretGridCols;
    push.gridRows = request.noRegretGridRows;
    push.tileWidth = request.noRegretTileWidth;
    push.tileHeight = request.noRegretTileHeight;
    if (request.pass0Only) {
        push.s0 = request.pass0ChannelBias[0]; push.s1 = request.pass0ChannelBias[1];
        push.s2 = request.pass0ChannelBias[2]; push.s3 = request.pass0ChannelBias[3];
    } else {
        push.s0 = request.effectiveS[0]; push.s1 = request.effectiveS[1];
        push.s2 = request.effectiveS[2]; push.s3 = request.effectiveS[3];
    }
    push.o0 = request.effectiveO[0]; push.o1 = request.effectiveO[1];
    push.o2 = request.effectiveO[2]; push.o3 = request.effectiveO[3];
    push.blendStrength = request.blendStrength;
    push.maxPixelShift = request.maxPixelShift;
    push.isoAuthority = request.isoAuthority;
    push.maxLinearShift = std::clamp(
            std::isfinite(request.maxLinearShift) ? request.maxLinearShift : 0.028f,
            1.0e-5f, 0.028f);
    push.physicalBaselineMode = request.physicalBaselineMode ? 1u : 0u;
    push.modelConfidence = request.modelConfidence;
    push.combinedNoisePressure = std::clamp(
            std::isfinite(request.combinedNoisePressure) ? request.combinedNoisePressure : 0.0f,
            0.0f, 1.0f);

    // Submission 1: full-frame Pass 1 candidate followed by compact tile statistics.
    {
        vkResetFences(device, 1u, &fence_);
        vkResetCommandBuffer(commandBuffer_, 0u);
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
            result.status = "PRE_DEMOSAIC_COMMAND_RECORDING_FAILED";
            result.failureReason = "vkBeginCommandBuffer_pass1_failed";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        result.timestampQueryUsed = queryPool_ != VK_NULL_HANDLE;
        if (result.timestampQueryUsed) {
            vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 7u);
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool_, 0u);
        }
        VkBufferMemoryBarrier hostToCompute[4]{};
        std::uint32_t hostBarrierCount = 0u;
        auto appendHostBarrier = [&](VkBuffer buffer, VkDeviceSize size) {
            auto& barrier = hostToCompute[hostBarrierCount++];
            barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.buffer = buffer;
            barrier.offset = 0u;
            barrier.size = size;
        };
        if (hostInputRequested) appendHostBarrier(input_.buffer, static_cast<VkDeviceSize>(result.inputBytes));
        if (!request.pass0Only) {
            appendHostBarrier(tensor_.buffer, static_cast<VkDeviceSize>(result.tensorBytes));
            appendHostBarrier(lensShading_.buffer, static_cast<VkDeviceSize>(result.lensShadingBytes));
        }
        appendHostBarrier(telemetry_.buffer, static_cast<VkDeviceSize>(kTelemetryBytes));
        if (hostBarrierCount > 0u) {
            vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_HOST_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                                 0u, nullptr, hostBarrierCount, hostToCompute, 0u, nullptr);
        }
        if (residentInputRequested) {
            VkBufferMemoryBarrier residentToCompute{};
            residentToCompute.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            residentToCompute.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            residentToCompute.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            residentToCompute.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            residentToCompute.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            residentToCompute.buffer = sourceBuffer;
            residentToCompute.offset = 0u;
            residentToCompute.size = static_cast<VkDeviceSize>(result.inputBytes);
            vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                                 0u, nullptr, 1u, &residentToCompute, 0u, nullptr);
        }
        vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                                0u, 1u, &descriptorSet_, 0u, nullptr);
        push.mode = request.pass0Only ? 5u : 0u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                      (request.frameHeight + 15u) / 16u, 1u);
        if (result.timestampQueryUsed) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
        }
        VkBufferMemoryBarrier candidateBarrier{};
        candidateBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        candidateBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        candidateBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        candidateBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        candidateBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        candidateBarrier.buffer = outputBuffer;
        candidateBarrier.offset = 0u;
        candidateBarrier.size = static_cast<VkDeviceSize>(result.candidateBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &candidateBarrier, 0u, nullptr);
        push.mode = 1u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        const std::uint32_t tileCount32 = static_cast<std::uint32_t>(tileCount);
        vkCmdDispatch(commandBuffer_, (tileCount32 + 15u) / 16u, 1u, 1u);
        if (result.timestampQueryUsed) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 2u);
        }
        VkBufferMemoryBarrier hostBarriers[2]{};
        for (auto& barrier : hostBarriers) {
            barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        }
        hostBarriers[0].buffer = tileStatistics_.buffer;
        hostBarriers[0].size = static_cast<VkDeviceSize>(result.tileStatisticsBytes);
        hostBarriers[1].buffer = telemetry_.buffer;
        hostBarriers[1].size = static_cast<VkDeviceSize>(kTelemetryBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr,
                             2u, hostBarriers, 0u, nullptr);
        if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
            result.status = "PRE_DEMOSAIC_COMMAND_RECORDING_FAILED";
            result.failureReason = "vkEndCommandBuffer_pass1_failed";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1u;
        submit.pCommandBuffers = &commandBuffer_;
        const auto syncStarted = Clock::now();
        if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS ||
            vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
            VulkanRuntime::instance().markGpuStalled("Pass1_Submission1");
            result.status = "GPU_STALLED";
            result.failureReason = "pass1_queue_submit_or_wait_timeout";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        result.synchronizationMs += elapsedMs(syncStarted);
    }

    vmaInvalidateAllocation(allocator_, tileStatistics_.allocation, 0u, result.tileStatisticsBytes);
    vmaInvalidateAllocation(allocator_, telemetry_.allocation, 0u, kTelemetryBytes);
    std::vector<float> acceptance;
    {
        const auto decisionStarted = Clock::now();
        result.noRegret = decideNoRegret(
                request, static_cast<const TileStatsGpu*>(tileStatistics_.mapped), acceptance);
        result.noRegretCpuDecisionMs = elapsedMs(decisionStarted);
    }
    if (result.noRegret.evaluatedTiles == 0) {
        result.status = "PRE_DEMOSAIC_NO_REGRET_NO_VALID_TILES";
        result.failureReason = "PASS1_GPU_TILE_STATISTICS_NOT_QUALIFIED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    std::memcpy(acceptance_.mapped, acceptance.data(), static_cast<std::size_t>(result.acceptanceBytes));
    vmaFlushAllocation(allocator_, acceptance_.allocation, 0u, result.acceptanceBytes);

    // Submission 2: blend the candidate with original on-device using the exact tile acceptance map.
    {
        vkResetFences(device, 1u, &fence_);
        vkResetCommandBuffer(commandBuffer_, 0u);
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
            result.status = "PRE_DEMOSAIC_COMMAND_RECORDING_FAILED";
            result.failureReason = "vkBeginCommandBuffer_no_regret_failed";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        if (result.timestampQueryUsed) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool_, 3u);
        }
        VkBufferMemoryBarrier blendInputs[2]{};
        blendInputs[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        blendInputs[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        blendInputs[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        blendInputs[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        blendInputs[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        blendInputs[0].buffer = outputBuffer;
        blendInputs[0].offset = 0u;
        blendInputs[0].size = static_cast<VkDeviceSize>(result.candidateBytes);
        blendInputs[1].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        blendInputs[1].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        blendInputs[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        blendInputs[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        blendInputs[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        blendInputs[1].buffer = acceptance_.buffer;
        blendInputs[1].offset = 0u;
        blendInputs[1].size = static_cast<VkDeviceSize>(result.acceptanceBytes);
        vkCmdPipelineBarrier(commandBuffer_,
                             VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 2u, blendInputs, 0u, nullptr);
        vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                                0u, 1u, &descriptorSet_, 0u, nullptr);
        push.mode = 2u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, (request.frameWidth + 15u) / 16u,
                      (request.frameHeight + 15u) / 16u, 1u);
        if (result.timestampQueryUsed) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 4u);
        }
        // 8H-G: mode 2 finalises candidate_ in-place. Do not copy the full frame to
        // host here. Compact post-Pass1 observations are collected in a third GPU
        // submission and normal production hands candidate_ directly to Pass 2.
        if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
            result.status = "PRE_DEMOSAIC_COMMAND_RECORDING_FAILED";
            result.failureReason = "vkEndCommandBuffer_no_regret_failed";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1u;
        submit.pCommandBuffers = &commandBuffer_;
        const auto syncStarted = Clock::now();
        if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS ||
            vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
            VulkanRuntime::instance().markGpuStalled("Pass1_Submission2_NoRegret");
            result.status = "GPU_STALLED";
            result.failureReason = "no_regret_queue_submit_or_wait_timeout";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        result.synchronizationMs += elapsedMs(syncStarted);
    }


    // Submission 3: collect post-No-Regret tile state, raw residual statistics and
    // chroma-band observations directly from the final device-resident mosaic.
    {
        std::memset(tileStatistics_.mapped, 0, static_cast<std::size_t>(result.tileStatisticsBytes));
        std::memset(observation_.mapped, 0, static_cast<std::size_t>(observationBytes));
        vmaFlushAllocation(allocator_, tileStatistics_.allocation, 0u, result.tileStatisticsBytes);
        vmaFlushAllocation(allocator_, observation_.allocation, 0u, observationBytes);
        vkResetFences(device, 1u, &fence_);
        vkResetCommandBuffer(commandBuffer_, 0u);
        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
            result.status = "PRE_DEMOSAIC_OBSERVATION_RECORDING_FAILED";
            result.failureReason = "vkBeginCommandBuffer_compact_observation_failed";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        if (result.timestampQueryUsed) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool_, 5u);
        }
        VkBufferMemoryBarrier observationInputs[3]{};
        observationInputs[0].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        observationInputs[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        observationInputs[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        observationInputs[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        observationInputs[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        observationInputs[0].buffer = candidate_.buffer;
        observationInputs[0].size = static_cast<VkDeviceSize>(result.candidateBytes);
        for (int i = 1; i < 3; ++i) {
            observationInputs[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            observationInputs[i].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
            observationInputs[i].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            observationInputs[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            observationInputs[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        }
        observationInputs[1].buffer = tileStatistics_.buffer;
        observationInputs[1].size = static_cast<VkDeviceSize>(result.tileStatisticsBytes);
        observationInputs[2].buffer = observation_.buffer;
        observationInputs[2].size = static_cast<VkDeviceSize>(observationBytes);
        vkCmdPipelineBarrier(commandBuffer_,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 3u, observationInputs, 0u, nullptr);
        vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
        vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_,
                                0u, 1u, &descriptorSet_, 0u, nullptr);
        push.mode = 1u;
        push.observationOffset = 0u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        const std::uint32_t tileCount32 = static_cast<std::uint32_t>(tileCount);
        vkCmdDispatch(commandBuffer_, (tileCount32 + 15u) / 16u, 1u, 1u);

        push.mode = 3u;
        push.observationOffset = 0u;
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, rawObservationGroupsX, rawObservationGroupsY, 1u);

        push.mode = 4u;
        push.observationOffset = static_cast<std::uint32_t>(rawObservationGroupCount);
        vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT,
                           0u, sizeof(push), &push);
        vkCmdDispatch(commandBuffer_, bandObservationGroupsX, bandObservationGroupsY, 1u);
        if (result.timestampQueryUsed) {
            vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 6u);
        }

        VkBufferMemoryBarrier hostBarriers[4]{};
        std::uint32_t hostBarrierCount = 3u;
        const VkBuffer compactBuffers[3]{tileStatistics_.buffer, observation_.buffer, telemetry_.buffer};
        const VkDeviceSize compactSizes[3]{static_cast<VkDeviceSize>(result.tileStatisticsBytes),
                                           static_cast<VkDeviceSize>(observationBytes),
                                           static_cast<VkDeviceSize>(kTelemetryBytes)};
        for (std::uint32_t i = 0; i < 3u; ++i) {
            hostBarriers[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            hostBarriers[i].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            hostBarriers[i].dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            hostBarriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hostBarriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hostBarriers[i].buffer = compactBuffers[i];
            hostBarriers[i].size = compactSizes[i];
        }
        if (!request.deferFullFrameReadback) {
            VkBufferMemoryBarrier candidateToTransfer{};
            candidateToTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            candidateToTransfer.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            candidateToTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            candidateToTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            candidateToTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            candidateToTransfer.buffer = outputBuffer;
            candidateToTransfer.size = static_cast<VkDeviceSize>(result.candidateBytes);
            vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr,
                                 1u, &candidateToTransfer, 0u, nullptr);
            VkBufferCopy finalCopy{};
            finalCopy.size = static_cast<VkDeviceSize>(result.outputBytes);
            if (outputBuffer != input_.buffer) {
                vkCmdCopyBuffer(commandBuffer_, outputBuffer, input_.buffer, 1u, &finalCopy);
            }
            hostBarriers[3].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            hostBarriers[3].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            hostBarriers[3].dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            hostBarriers[3].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hostBarriers[3].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hostBarriers[3].buffer = input_.buffer;
            hostBarriers[3].size = static_cast<VkDeviceSize>(result.outputBytes);
            hostBarrierCount = 4u;
        }
        vkCmdPipelineBarrier(commandBuffer_,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_HOST_BIT, 0u,
                             0u, nullptr, hostBarrierCount, hostBarriers, 0u, nullptr);
        if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
            result.status = "PRE_DEMOSAIC_OBSERVATION_RECORDING_FAILED";
            result.failureReason = "vkEndCommandBuffer_compact_observation_failed";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1u;
        submit.pCommandBuffers = &commandBuffer_;
        const auto observationStarted = Clock::now();
        if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS ||
            vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
            VulkanRuntime::instance().markGpuStalled("Pass1_Submission3_Observation");
            result.status = "GPU_STALLED";
            result.failureReason = "compact_observation_submit_or_wait_timeout";
            result.totalMs = elapsedMs(totalStarted);
            return result;
        }
        result.synchronizationMs += elapsedMs(observationStarted);
    }

    if (result.timestampQueryUsed) {
        std::uint64_t timestamps[7]{};
        if (vkGetQueryPoolResults(device, queryPool_, 0u, 7u, sizeof(timestamps), timestamps,
                sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS &&
            timestamps[1] >= timestamps[0] && timestamps[2] >= timestamps[1] && timestamps[4] >= timestamps[3]) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physicalDevice, &properties);
            const double toMs = static_cast<double>(properties.limits.timestampPeriod) / 1.0e6;
            const float primaryKernelMs = static_cast<float>((timestamps[1] - timestamps[0]) * toMs);
            if (request.pass0Only) result.pass0KernelMs = primaryKernelMs;
            else result.pass1KernelMs = primaryKernelMs;
            result.tileStatisticsKernelMs = static_cast<float>((timestamps[2] - timestamps[1]) * toMs);
            result.noRegretBlendKernelMs = static_cast<float>((timestamps[4] - timestamps[3]) * toMs);
            if (timestamps[6] >= timestamps[5]) {
                result.compactObservationKernelMs = static_cast<float>((timestamps[6] - timestamps[5]) * toMs);
            }
            result.gpuKernelMs = result.pass0KernelMs + result.pass1KernelMs + result.tileStatisticsKernelMs +
                    result.noRegretBlendKernelMs + result.compactObservationKernelMs;
        } else {
            result.timestampQueryUsed = false;
        }
    }
    if (!result.timestampQueryUsed) result.gpuKernelMs = result.synchronizationMs;

    {
        const auto readbackStarted = Clock::now();
        vmaInvalidateAllocation(allocator_, tileStatistics_.allocation, 0u, result.tileStatisticsBytes);
        vmaInvalidateAllocation(allocator_, observation_.allocation, 0u, observationBytes);
        vmaInvalidateAllocation(allocator_, telemetry_.allocation, 0u, kTelemetryBytes);
        if (!request.deferFullFrameReadback) {
            vmaInvalidateAllocation(allocator_, input_.allocation, 0u, result.outputBytes);
            result.outputMosaic.resize(static_cast<std::size_t>(pixelCount));
            std::memcpy(result.outputMosaic.data(), input_.mapped, static_cast<std::size_t>(result.outputBytes));
        }
        result.compactObservationReadbackMs = elapsedMs(readbackStarted);
        result.readbackMs = request.deferFullFrameReadback ? 0.0f : result.compactObservationReadbackMs;
    }

    // Convert final compact tile state into the exact subset required by the next
    // pass' No-Regret evaluator. Predicted variances are reconstructed from S/O and
    // the GPU-measured per-channel mean signal; no full-frame CPU provenance pass is needed.
    {
        const auto* tiles = static_cast<const TileStatsGpu*>(tileStatistics_.mapped);
        result.finalTiles.resize(static_cast<std::size_t>(tileCount));
        for (std::size_t i = 0; i < static_cast<std::size_t>(tileCount); ++i) {
            const TileStatsGpu& src = tiles[i];
            SpectraPass1BeforeTile dst{};
            int populated = src.populatedChannels > 0.0f ? static_cast<int>(std::lround(src.populatedChannels)) : 0;
            const std::uint32_t samples = src.sampleCount > 0.0f ? static_cast<std::uint32_t>(std::lround(src.sampleCount)) : 0u;
            float predictedSum = 0.0f;
            for (int ch = 0; ch < 4; ++ch) {
                dst.meanSignal[static_cast<std::size_t>(ch)] = src.meanSignal[ch];
                const float predicted = std::max(0.0f,
                        request.effectiveS[static_cast<std::size_t>(ch)] * std::max(0.0f, src.meanSignal[ch]) +
                        request.effectiveO[static_cast<std::size_t>(ch)]);
                dst.predictedRawVarianceByChannel[static_cast<std::size_t>(ch)] = predicted;
                predictedSum += predicted;
            }
            dst.predictedSpatialResidualVariance = 1.25f * 0.25f * predictedSum;
            dst.residualEnergy = std::max(0.0f, src.residualEnergy);
            dst.structureEnergy = std::max(0.0f, src.structureEnergy);
            const float sampleCoverage = std::clamp(static_cast<float>(samples) / 96.0f, 0.0f, 1.0f);
            const float channelCoverage = std::clamp(static_cast<float>(populated) / 4.0f, 0.0f, 1.0f);
            dst.confidence = sampleCoverage * channelCoverage *
                    (0.35f + 0.65f * std::clamp(request.modelConfidence, 0.0f, 1.0f));
            dst.valid = populated == 4 && samples >= 32u;
            result.finalTiles[i] = dst;
        }
    }

    {
        const auto* obs = static_cast<const float*>(observation_.mapped);
        double residualSq = 0.0, chromaSq = 0.0;
        std::uint64_t residualCount = 0u, chromaCount = 0u;
        for (std::uint64_t i = 0; i < rawObservationGroupCount; ++i) {
            const float* v = obs + 4u * i;
            residualSq += std::max(0.0f, v[0]);
            residualCount += static_cast<std::uint64_t>(std::max(0.0f, std::round(v[1])));
            chromaSq += std::max(0.0f, v[2]);
            chromaCount += static_cast<std::uint64_t>(std::max(0.0f, std::round(v[3])));
        }
        result.rawStatistics.residualSquaredSum = residualSq;
        result.rawStatistics.residualSampleCount = residualCount;
        result.rawStatistics.chromaSquaredSum = chromaSq;
        result.rawStatistics.chromaSampleCount = chromaCount;
        result.rawStatistics.residualEnergy = residualCount > 0u
                ? static_cast<float>(residualSq / static_cast<double>(residualCount)) : 0.0f;
        result.rawStatistics.chromaResidualEnergy = chromaCount > 0u
                ? static_cast<float>(chromaSq / static_cast<double>(chromaCount)) : 0.0f;
        result.rawStatistics.method = "M8H_G_GPU_RESIDENT_COMPACT_RAW_STATISTICS";

        double fine = 0.0, mid = 0.0, low = 0.0, row = 0.0, col = 0.0;
        double redFine = 0.0, redMid = 0.0, redLow = 0.0;
        double blueFine = 0.0, blueMid = 0.0, blueLow = 0.0;
        std::uint64_t samples = 0u, red = 0u, blue = 0u;
        const std::uint64_t bandBase = rawObservationGroupCount;
        for (std::uint64_t i = 0; i < bandObservationGroupCount; ++i) {
            const float* a = obs + 4u * (bandBase + 4u * i);
            const float* b = obs + 4u * (bandBase + 4u * i + 1u);
            const float* redBand = obs + 4u * (bandBase + 4u * i + 2u);
            const float* blueBand = obs + 4u * (bandBase + 4u * i + 3u);
            fine += std::max(0.0f, a[0]); mid += std::max(0.0f, a[1]);
            low += std::max(0.0f, a[2]); row += std::max(0.0f, a[3]);
            col += std::max(0.0f, b[0]);
            samples += static_cast<std::uint64_t>(std::max(0.0f, std::round(b[1])));
            red += static_cast<std::uint64_t>(std::max(0.0f, std::round(b[2])));
            blue += static_cast<std::uint64_t>(std::max(0.0f, std::round(b[3])));
            redFine += std::max(0.0f, redBand[0]);
            redMid += std::max(0.0f, redBand[1]);
            redLow += std::max(0.0f, redBand[2]);
            blueFine += std::max(0.0f, blueBand[0]);
            blueMid += std::max(0.0f, blueBand[1]);
            blueLow += std::max(0.0f, blueBand[2]);
        }
        result.chromaBands.sampleCount = samples;
        result.chromaBands.redSampleCount = red;
        result.chromaBands.blueSampleCount = blue;
        const std::uint64_t maxColour = std::max(red, blue);
        result.chromaBands.redBlueSampleBalance = maxColour > 0u
                ? static_cast<float>(std::min(red, blue)) / static_cast<float>(maxColour) : 0.0f;
        if (samples > 0u) {
            const double inv = 1.0 / static_cast<double>(samples);
            result.chromaBands.fineEnergy = static_cast<float>(fine * inv);
            result.chromaBands.midEnergy = static_cast<float>(mid * inv);
            result.chromaBands.lowEnergy = static_cast<float>(low * inv);
            result.chromaBands.rowPatternProxy = static_cast<float>(row * inv);
            result.chromaBands.columnPatternProxy = static_cast<float>(col * inv);
        }
        if (red > 0u) {
            const double invRed = 1.0 / static_cast<double>(red);
            result.chromaBands.redFineEnergy = static_cast<float>(redFine * invRed);
            result.chromaBands.redMidEnergy = static_cast<float>(redMid * invRed);
            result.chromaBands.redLowEnergy = static_cast<float>(redLow * invRed);
        }
        if (blue > 0u) {
            const double invBlue = 1.0 / static_cast<double>(blue);
            result.chromaBands.blueFineEnergy = static_cast<float>(blueFine * invBlue);
            result.chromaBands.blueMidEnergy = static_cast<float>(blueMid * invBlue);
            result.chromaBands.blueLowEnergy = static_cast<float>(blueLow * invBlue);
        }
        result.chromaBands.confidence = std::clamp(static_cast<float>(samples) / 2048.0f, 0.0f, 1.0f) *
                result.chromaBands.redBlueSampleBalance;
        result.chromaBands.status = (samples == 0u || red == 0u || blue == 0u)
                ? "NO_BALANCED_RB_SAMPLES"
                : (result.chromaBands.redBlueSampleBalance < 0.75f
                        ? "UNBALANCED_RB_SUPPORT_PRE_DEMOSAIC_PROXY"
                        : (samples >= 256u ? "AVAILABLE_SAMPLED_PRE_DEMOSAIC_PROXY"
                                           : "LOW_SUPPORT_SAMPLED_PRE_DEMOSAIC_PROXY"));
    }
    {
        const auto* words = static_cast<const std::uint32_t*>(telemetry_.mapped);
        result.telemetry.evaluatedPixelCount = words[0];
        result.telemetry.changedPixelCount = words[1];
        result.telemetry.edgeProtectedPixelCount = words[2];
        result.telemetry.validTensorPixelCount = words[3];
        result.telemetry.confidentTensorPixelCount = words[4];
        result.telemetry.fallbackPixelCount = words[5];
        result.telemetry.directionalChangedPixelCount = words[6];
        result.telemetry.crossEdgeProtectedSampleCount = words[7];
        result.telemetry.alongStructureSupportedSampleCount = words[8];
        result.telemetry.contextFlatPixelCount = words[81];
        result.telemetry.contextStructureProtectedPixelCount = words[82];
        result.telemetry.contextBoostedPixelCount = words[83];
        result.telemetry.profiledMultibandPixelCount = words[84];
        result.telemetry.profiledHeavyFineShrinkPixelCount = words[85];
        result.telemetry.coherentDetailRestitutionPixelCount = words[86];
        result.telemetry.profiledPatchConsensusPixelCount = words[87];
        result.telemetry.profiledStrongPatchConsensusPixelCount = words[88];
        result.telemetry.profiledPatchPosteriorCleanPixelCount = words[89];
        result.telemetry.profiledPatchGradientProtectedPixelCount = words[90];
        for (std::size_t i = 0; i < 4; ++i) result.telemetry.orientationHistogram[i] = words[9u + i];
        for (std::size_t i = 0; i < 32; ++i) {
            result.telemetry.confidenceHistogram[i] = words[16u + i];
            result.telemetry.coherenceHistogram[i] = words[48u + i];
        }
        float maximumLinearCorrection = 0.0f;
        std::uint32_t maximumLinearCorrectionBits = words[13];
        std::memcpy(&maximumLinearCorrection, &maximumLinearCorrectionBits, sizeof(float));
        result.telemetry.maximumLinearCorrection = std::abs(maximumLinearCorrection);
        result.noRegret.attenuatedPixelFraction = pixelCount > 0u
                ? static_cast<float>(words[80]) / static_cast<float>(pixelCount) : 0.0f;
    }
    result.gpuNoRegretBlendUsed = true;
    result.cpuFullFrameCandidateReadbackAvoided = true;
    result.outputRemainsResidentUntilFinalReadback = true;
    result.fullFrameReadbackDeferred = request.deferFullFrameReadback;
    residentOutputGeneration_++;
    if (residentOutputGeneration_ == 0u) residentOutputGeneration_ = 1u;
    residentOutputBytes_ = result.outputBytes;
    residentOutputWidth_ = request.frameWidth;
    residentOutputHeight_ = request.frameHeight;
    residentOutputIsInput_ = outputBuffer == input_.buffer;
    result.residentOutputGeneration = residentOutputGeneration_;
    result.residentOutputBytes = residentOutputBytes_;
    result.transferAndSyncMs = result.inputPackingMs + result.tensorUploadMs +
            result.synchronizationMs + result.compactObservationReadbackMs + result.readbackMs;
    result.totalMs = elapsedMs(totalStarted);
    result.success = request.deferFullFrameReadback
            ? (result.residentOutputGeneration != 0u && result.finalTiles.size() == tileCount)
            : (result.outputMosaic.size() == static_cast<std::size_t>(pixelCount));
    result.status = result.success
            ? (request.pass0Only
                    ? (request.deferFullFrameReadback
                            ? "SPECTRA_PASS0_GPU_RESIDENT_HANDOFF_READY"
                            : "SPECTRA_PASS0_GPU_NO_REGRET_READY")
                    : (request.deferFullFrameReadback
                            ? "SPECTRA_PASS1_GPU_RESIDENT_HANDOFF_READY"
                            : "SPECTRA_PASS1_GPU_RESIDENT_NO_REGRET_READY"))
            : (request.pass0Only ? "SPECTRA_PASS0_GPU_OUTPUT_CONTRACT_MISMATCH"
                                 : "SPECTRA_PASS1_GPU_OUTPUT_CONTRACT_MISMATCH");
    if (!result.success) result.failureReason = request.deferFullFrameReadback
            ? (request.pass0Only ? "PASS0_GPU_RESIDENT_HANDOFF_INCOMPLETE"
                                 : "PASS1_GPU_RESIDENT_HANDOFF_INCOMPLETE")
            : (request.pass0Only ? "PASS0_GPU_FINAL_READBACK_SIZE_MISMATCH"
                                 : "PASS1_GPU_FINAL_READBACK_SIZE_MISMATCH");
    return result;

#endif
}

} // namespace bncam::vulkan
