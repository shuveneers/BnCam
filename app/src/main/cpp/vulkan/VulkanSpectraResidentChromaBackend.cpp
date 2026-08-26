#include "VulkanSpectraResidentChromaBackend.h"
#include "VulkanPipelineCacheRegistry.h"
#include "../SpectraChromaNoRegretPolicy.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE
#define BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE 0
#endif
#if BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE
#include "SpectraResidentChromaSpirv.h"
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
    return static_cast<float>(std::chrono::duration<double, std::milli>(Clock::now() - started).count());
}

struct alignas(16) PushConstants {
    std::uint32_t frameWidth = 0;
    std::uint32_t frameHeight = 0;
    std::uint32_t cfaPattern = 0;
    std::uint32_t mode = 0;
    std::uint32_t lensColumns = 0;
    std::uint32_t lensRows = 0;
    std::uint32_t gridCols = 0;
    std::uint32_t gridRows = 0;
    std::uint32_t tileWidth = 0;
    std::uint32_t tileHeight = 0;
    std::uint32_t fieldCols = 0;
    std::uint32_t fieldRows = 0;
    float s[4]{0.0f, 0.0f, 0.0f, 0.0f};
    float o[4]{0.0f, 0.0f, 0.0f, 0.0f};
    float params0[4]{0.0f, 0.0f, 0.0f, 0.0f};
    float params1[4]{0.0f, 0.0f, 0.0f, 0.0f};
    float params2[4]{0.0f, 0.0f, 0.0f, 0.0f};
};
static_assert(sizeof(PushConstants) == 128u, "resident chroma push layout must stay within Vulkan minimum guarantee");

struct alignas(16) TileStatsGpu {
    float meanSignal[4]{0.0f, 0.0f, 0.0f, 0.0f};
    float residualEnergy = 0.0f;
    float structureEnergy = 0.0f;
    float sampleCount = 0.0f;
    float populatedChannels = 0.0f;
};
static_assert(sizeof(TileStatsGpu) == 32u, "resident chroma GLSL TileStats layout mismatch");

[[maybe_unused]] constexpr std::uint64_t kTelemetryWordCount = 96u;
[[maybe_unused]] constexpr std::uint64_t kTelemetryBytes = kTelemetryWordCount * sizeof(std::uint32_t);
[[maybe_unused]] constexpr std::uint64_t kDummyBytes = 16u;


float percentile(std::vector<float> values, float quantile) {
    if (values.empty()) return 0.0f;
    quantile = std::clamp(quantile, 0.0f, 1.0f);
    const std::size_t index = std::min(values.size() - 1u, static_cast<std::size_t>(
            std::lround(quantile * static_cast<float>(values.size() - 1u))));
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(index), values.end());
    return values[index];
}

[[maybe_unused]] bool checkedMultiply(std::uint64_t a, std::uint64_t b, std::uint64_t& out) {
    if (a == 0u || b == 0u) { out = 0u; return true; }
    if (a > std::numeric_limits<std::uint64_t>::max() / b) return false;
    out = a * b;
    return true;
}

struct NoRegretConfig {
    const std::vector<SpectraPass1BeforeTile>* beforeTiles = nullptr;
    int passIndex = 0;
    float targetFloorScale = 1.0f;
    float minimumResidualRatio = 0.70f;
    float detailRetentionFloor = 0.95f;
    float combinedNoisePressure = 0.0f;
    float modelConfidence = 0.0f;
};

[[maybe_unused]] SpectraPass1NoRegretGpuResult decideNoRegret(
        const NoRegretConfig& config,
        const TileStatsGpu* after,
        std::vector<float>& acceptance
) {
    SpectraPass1NoRegretGpuResult result{};
    if (config.beforeTiles == nullptr) return result;
    const auto& beforeTiles = *config.beforeTiles;
    result.totalTiles = static_cast<int>(beforeTiles.size());
    acceptance.assign(beforeTiles.size(), 0.0f);
    if (after == nullptr || beforeTiles.empty()) return result;

    std::vector<float> evaluatedAcceptance;
    evaluatedAcceptance.reserve(beforeTiles.size());
    double acceptanceSum = 0.0;
    double improvementSum = 0.0;
    double colourShiftSum = 0.0;
    double edgePreservationSum = 0.0;
    double oversmoothingSum = 0.0;
    float maximumColourShift = 0.0f;

    for (std::size_t i = 0; i < beforeTiles.size(); ++i) {
        const SpectraPass1BeforeTile& b = beforeTiles[i];
        const TileStatsGpu& a = after[i];
        const std::uint32_t sampleCount = a.sampleCount > 0.0f
                ? static_cast<std::uint32_t>(std::lround(a.sampleCount)) : 0u;
        const std::uint32_t populated = a.populatedChannels > 0.0f
                ? static_cast<std::uint32_t>(std::lround(a.populatedChannels)) : 0u;
        const bool afterValid = populated == 4u && sampleCount >= 32u;
        const float sampleCoverage = std::clamp(static_cast<float>(sampleCount) / 96.0f, 0.0f, 1.0f);
        const float channelCoverage = std::clamp(static_cast<float>(populated) / 4.0f, 0.0f, 1.0f);
        const float afterConfidence = sampleCoverage * channelCoverage *
                (0.35f + 0.65f * std::clamp(config.modelConfidence, 0.0f, 1.0f));
        if (!b.valid || !afterValid || b.confidence < 0.05f || afterConfidence < 0.05f) continue;
        result.evaluatedTiles++;

        const auto domain = bncam::spectra2::resolveChromaNoRegretDomain(
                config.passIndex,
                b.residualEnergy,
                b.chromaResidualEnergy,
                b.predictedSpatialResidualVariance,
                b.predictedChromaResidualVariance);
        const float beforeEnergy = domain.beforeEnergy;
        const float afterEnergy = std::max(0.0f, a.residualEnergy);
        const float target = std::max(1.0e-12f, domain.targetVariance) * config.targetFloorScale;
        const auto risk = [target, passIndex = config.passIndex](float energy) {
            const float ratio = std::max(0.04f, energy / target);
            if (passIndex >= 2) {
                // Pass 2 & 3: Chroma and low-frequency residual cleanup.
                // CFA opponent false-colour reduction at or below predicted target is desirable,
                // while true texture/edge preservation is guarded by structureRetention.
                return ratio > 1.0f ? std::log(ratio) : 0.0f;
            }
            if (ratio < 1.0f) {
                return 0.35f * std::abs(std::log(std::max(0.40f, ratio)));
            }
            return std::log(ratio);
        };
        const float riskImprovement = risk(beforeEnergy) - risk(afterEnergy);
        improvementSum += riskImprovement;

        const float minimumEnergy = target * config.minimumResidualRatio;
        const auto contextDecision = bncam::spectra::resolveContextFusionNoRegret({
                beforeEnergy,
                afterEnergy,
                target,
                b.structureEnergy,
                std::max(0.0f, a.structureEnergy),
                config.minimumResidualRatio,
                config.detailRetentionFloor,
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
            const float driftLimit = std::max(0.00025f,
                    (1.20f + 0.35f * config.combinedNoisePressure) * std::sqrt(channelVariance));
            if (channelDrift > driftLimit) { meanDrift = true; break; }
        }
        float tileAcceptance = 0.0f;
        const auto pass3ColourDrift = bncam::spectra2::resolvePass3ColourDriftDecision(
                deltaRg, deltaBg, b.predictedChromaResidualVariance, config.combinedNoisePressure);
        if (meanDrift || (config.passIndex == 3 && pass3ColourDrift.hardReject)) {
            result.rejectedMeanDrift++;
        } else if (contextDecision.worsened || contextDecision.noImprovement) {
            result.rejectedNoImprovement++;
        } else {
            tileAcceptance = contextDecision.acceptance;
            if (config.passIndex == 3) {
                tileAcceptance *= pass3ColourDrift.acceptanceScale;
            }
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

[[maybe_unused]] float bitsFloat(std::uint32_t bits) {
    float value = 0.0f;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

} // namespace

bool VulkanSpectraResidentChromaBackend::productionKernelConnected() const noexcept {
#if BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE
    return !getSpectraResidentChromaSpirv().empty();
#else
    return false;
#endif
}

bool VulkanSpectraResidentChromaBackend::pipelineInitialized() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return initialized_;
}

bool VulkanSpectraResidentChromaBackend::ensureBufferLocked(
        VmaAllocator allocator, std::uint64_t bytes, std::uint32_t hostAccess,
        PersistentBuffer& buffer, bool& reallocated, std::string& failureReason) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)hostAccess; (void)buffer; (void)reallocated;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u ||
        bytes > static_cast<std::uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        failureReason = "INVALID_RESIDENT_CHROMA_BUFFER_REQUEST";
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
        failureReason = "vmaCreateBuffer_resident_chroma_failed_" + std::to_string(create);
        return false;
    }
    buffer.mapped = allocationResult.pMappedData;
    buffer.capacityBytes = bytes;
    reallocated = true;
    allocationGeneration_++;
    return true;
#endif
}

void VulkanSpectraResidentChromaBackend::destroyBuffersLocked() noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr) {
        for (PersistentBuffer* buffer : {&input_, &candidate_, &scratch_, &lensShading_, &auxiliary_,
                                         &tileStatistics_, &acceptance_, &telemetry_}) {
            if (buffer->buffer != VK_NULL_HANDLE && buffer->allocation != nullptr) {
                vmaDestroyBuffer(allocator_, buffer->buffer, buffer->allocation);
            }
            *buffer = {};
        }
    }
#endif
    allocator_ = nullptr;
}

void VulkanSpectraResidentChromaBackend::destroyLocked(VkDevice device) noexcept {
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
}

void VulkanSpectraResidentChromaBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanSpectraResidentChromaBackend::initializeLocked(
        VkDevice device, VkCommandPool commandPool, std::string& failureReason) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device && initializedCommandPool_ == commandPool) return true;
        failureReason = "RESIDENT_CHROMA_BACKEND_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE
    (void)device; (void)commandPool;
    failureReason = "RESIDENT_CHROMA_SHADER_NOT_COMPILED";
    return false;
#else
    const auto& spirv = getSpectraResidentChromaSpirv();
    if (device == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE || spirv.empty()) {
        failureReason = "RESIDENT_CHROMA_INITIALIZATION_INPUT_INVALID";
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
        failureReason = "vkCreateDescriptorSetLayout_resident_chroma_failed"; destroyLocked(device); return false;
    }
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.size = sizeof(PushConstants);
    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1u;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;
    layoutInfo.pushConstantRangeCount = 1u;
    layoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "vkCreatePipelineLayout_resident_chroma_failed"; destroyLocked(device); return false;
    }
    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_resident_chroma_failed"; destroyLocked(device); return false;
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
        failureReason = "vkCreateComputePipelines_resident_chroma_failed"; destroyLocked(device); return false;
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
        failureReason = "vkCreateDescriptorPool_resident_chroma_failed"; destroyLocked(device); return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1u;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "vkAllocateDescriptorSets_resident_chroma_failed"; destroyLocked(device); return false;
    }
    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &commandInfo, &commandBuffer_) != VK_SUCCESS) {
        failureReason = "vkAllocateCommandBuffers_resident_chroma_failed"; destroyLocked(device); return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence_) != VK_SUCCESS) {
        failureReason = "vkCreateFence_resident_chroma_failed"; destroyLocked(device); return false;
    }
    VkQueryPoolCreateInfo queryInfo{};
    queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryInfo.queryCount = 8u;
    if (vkCreateQueryPool(device, &queryInfo, nullptr, &queryPool_) != VK_SUCCESS) queryPool_ = VK_NULL_HANDLE;
    initialized_ = true;
    initializedDevice_ = device;
    initializedCommandPool_ = commandPool;
    return true;
#endif
}

void VulkanSpectraResidentChromaBackend::updateDescriptorSetLocked(VkDevice device, VkBuffer inputOverride) noexcept {
    const VkBuffer buffers[8] = {inputOverride != VK_NULL_HANDLE ? inputOverride : input_.buffer,
                                 candidate_.buffer, scratch_.buffer, lensShading_.buffer,
                                 auxiliary_.buffer, tileStatistics_.buffer, acceptance_.buffer, telemetry_.buffer};
    VkDescriptorBufferInfo infos[8]{};
    VkWriteDescriptorSet writes[8]{};
    for (std::uint32_t i = 0; i < 8u; ++i) {
        infos[i].buffer = buffers[i]; infos[i].range = VK_WHOLE_SIZE;
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_; writes[i].dstBinding = i;
        writes[i].descriptorCount = 1u; writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, 8u, writes, 0u, nullptr);
    descriptorBindingsInitialized_ = true;
}

bool VulkanSpectraResidentChromaBackend::resolveResidentOutput(
        std::uint64_t generation,
        VkBuffer& buffer,
        std::uint64_t& bytes,
        std::uint32_t& width,
        std::uint32_t& height
) const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    buffer = VK_NULL_HANDLE;
    bytes = 0u;
    width = 0u;
    height = 0u;
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE
    (void)generation;
    return false;
#else
    if (!initialized_ || generation == 0u || generation != residentOutputGeneration_ ||
        residentOutputBytes_ == 0u || residentOutputWidth_ == 0u ||
        residentOutputHeight_ == 0u || input_.buffer == VK_NULL_HANDLE) {
        return false;
    }
    buffer = input_.buffer;
    bytes = residentOutputBytes_;
    width = residentOutputWidth_;
    height = residentOutputHeight_;
    return true;
#endif
}

bool VulkanSpectraResidentChromaBackend::readbackResidentOutput(
        std::uint64_t generation,
        std::vector<float>& output
) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    output.clear();
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE
    (void)generation;
    return false;
#else
    if (!initialized_ || allocator_ == nullptr || generation == 0u ||
        generation != residentOutputGeneration_ || residentOutputBytes_ == 0u ||
        input_.allocation == nullptr || input_.mapped == nullptr) {
        return false;
    }
    vmaInvalidateAllocation(allocator_, input_.allocation, 0u, residentOutputBytes_);
    const std::size_t floatCount = static_cast<std::size_t>(
            residentOutputBytes_ / sizeof(float));
    output.resize(floatCount);
    std::memcpy(output.data(), input_.mapped, floatCount * sizeof(float));
    return true;
#endif
}

SpectraResidentChromaResult VulkanSpectraResidentChromaBackend::executePass2(
        VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue, VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner, const SpectraResidentPass2Request& request) noexcept {
    return executePass2Internal(physicalDevice, device, computeQueue, commandPool, allocatorOwner,
                                VK_NULL_HANDLE, 0u, request);
}

SpectraResidentChromaResult VulkanSpectraResidentChromaBackend::executePass2FromResident(
        VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue, VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner, VkBuffer residentInputBuffer,
        std::uint64_t residentInputBytes, const SpectraResidentPass2Request& request) noexcept {
    return executePass2Internal(physicalDevice, device, computeQueue, commandPool, allocatorOwner,
                                residentInputBuffer, residentInputBytes, request);
}

SpectraResidentChromaResult VulkanSpectraResidentChromaBackend::executePass2Internal(
        VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue, VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner, VkBuffer residentInputBuffer,
        std::uint64_t residentInputBytes, const SpectraResidentPass2Request& request) noexcept {
    SpectraResidentChromaResult result{};
    result.attempted = true;
    result.pipelineAvailable = productionKernelConnected();
    const auto totalStarted = Clock::now();
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE
    (void)physicalDevice; (void)device; (void)computeQueue; (void)commandPool; (void)allocatorOwner; (void)residentInputBuffer; (void)residentInputBytes; (void)request;
    result.status = !BNCAM_VMA_HEADER_AVAILABLE ? "VMA_HEADER_NOT_AVAILABLE" : "RESIDENT_CHROMA_SHADER_NOT_COMPILED";
    result.failureReason = result.status;
    result.totalMs = elapsedMs(totalStarted);
    return result;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    std::string failure;
    if (!initializeLocked(device, commandPool, failure)) {
        result.status = "RESIDENT_CHROMA_INITIALIZATION_FAILED"; result.failureReason = failure;
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    allocator_ = allocatorOwner.handle();
    const bool residentInput = residentInputBuffer != VK_NULL_HANDLE;
    if (allocator_ == nullptr || (!residentInput && request.mosaicData == nullptr) ||
        request.frameWidth == 0u || request.frameHeight == 0u ||
        request.beforeTiles.empty() || request.noRegretGridCols == 0u || request.noRegretGridRows == 0u ||
        (!request.fineEnabled && !request.midEnabled)) {
        result.status = "PASS2_GPU_INVALID_REQUEST"; result.failureReason = "missing_input_or_active_band_or_no_regret_grid";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    std::uint64_t pixels = 0u;
    if (!checkedMultiply(request.frameWidth, request.frameHeight, pixels) ||
        !checkedMultiply(pixels, sizeof(float), result.inputBytes)) {
        result.status = "PASS2_GPU_SIZE_OVERFLOW"; result.failureReason = "frame_size_overflow";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    if (residentInput && residentInputBytes < result.inputBytes) {
        result.status = "PASS2_GPU_RESIDENT_INPUT_SIZE_MISMATCH";
        result.failureReason = "resident_pass1_buffer_smaller_than_requested_frame";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.residentInputUsed = residentInput;
    const std::uint64_t tileCount = static_cast<std::uint64_t>(request.noRegretGridCols) * request.noRegretGridRows;
    result.tileStatisticsBytes = tileCount * sizeof(TileStatsGpu);
    result.acceptanceBytes = tileCount * sizeof(float);
    const std::uint64_t lensBytes = request.lensShadingMap != nullptr && request.lensShadingColumns > 0u && request.lensShadingRows > 0u
            ? static_cast<std::uint64_t>(request.lensShadingColumns) * request.lensShadingRows * 4u * sizeof(float) : kDummyBytes;
    bool reallocated = false;
    const std::uint32_t mapped = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
    if (!ensureBufferLocked(allocator_, result.inputBytes, mapped, input_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, result.inputBytes, 0u, candidate_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, result.inputBytes, 0u, scratch_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, lensBytes, mapped, lensShading_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, kDummyBytes, mapped, auxiliary_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, result.tileStatisticsBytes, mapped, tileStatistics_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, result.acceptanceBytes, mapped, acceptance_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, kTelemetryBytes, mapped, telemetry_, reallocated, failure)) {
        result.status = "PASS2_GPU_BUFFER_ALLOCATION_FAILED"; result.failureReason = failure;
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = input_.capacityBytes + candidate_.capacityBytes + scratch_.capacityBytes +
            lensShading_.capacityBytes + auxiliary_.capacityBytes + tileStatistics_.capacityBytes +
            acceptance_.capacityBytes + telemetry_.capacityBytes;
    updateDescriptorSetLocked(device, residentInput ? residentInputBuffer : VK_NULL_HANDLE);

    {
        const auto t = Clock::now();
        float* dst = static_cast<float*>(input_.mapped);
        if (!residentInput) {
            if (request.rowStrideFloats == request.frameWidth) {
                std::memcpy(dst, request.mosaicData, static_cast<std::size_t>(result.inputBytes));
            } else {
                for (std::uint32_t y = 0; y < request.frameHeight; ++y) {
                    std::memcpy(dst + static_cast<std::size_t>(y) * request.frameWidth,
                                request.mosaicData + static_cast<std::size_t>(y) * request.rowStrideFloats,
                                static_cast<std::size_t>(request.frameWidth) * sizeof(float));
                }
            }
            vmaFlushAllocation(allocator_, input_.allocation, 0u, result.inputBytes);
        }
        std::memset(telemetry_.mapped, 0, static_cast<std::size_t>(kTelemetryBytes));
        vmaFlushAllocation(allocator_, telemetry_.allocation, 0u, kTelemetryBytes);
        result.inputPackingMs = residentInput ? 0.0f : elapsedMs(t);
    }
    {
        const auto t = Clock::now();
        if (request.lensShadingMap != nullptr && lensBytes > kDummyBytes) {
            const bool generationChanged = request.lensShadingGenerationId != lensShadingGenerationId_ ||
                    lensBytes != lensShadingGenerationBytes_ || reallocated;
            if (generationChanged) {
                std::memcpy(lensShading_.mapped, request.lensShadingMap, static_cast<std::size_t>(lensBytes));
                vmaFlushAllocation(allocator_, lensShading_.allocation, 0u, lensBytes);
                lensShadingGenerationId_ = request.lensShadingGenerationId;
                lensShadingGenerationBytes_ = lensBytes;
            }
        } else {
            const float ones[4]{1.0f, 1.0f, 1.0f, 1.0f};
            std::memcpy(lensShading_.mapped, ones, sizeof(ones));
            vmaFlushAllocation(allocator_, lensShading_.allocation, 0u, sizeof(ones));
        }

        // Delta 23: Pass 2 owns this otherwise-unused 16-byte auxiliary payload. Keep the
        // Vulkan push-constant layout at 128 bytes and pass four bounded opponent multipliers:
        // fine R, fine B, mid R, mid B. Pass 3 repopulates auxiliary_ in its own execution path.
        const float opponentAuthority[4]{
                std::clamp(request.fineRedAuthorityMultiplier, 0.88f, 1.12f),
                std::clamp(request.fineBlueAuthorityMultiplier, 0.88f, 1.12f),
                std::clamp(request.midRedAuthorityMultiplier, 0.88f, 1.12f),
                std::clamp(request.midBlueAuthorityMultiplier, 0.88f, 1.12f)
        };
        std::memcpy(auxiliary_.mapped, opponentAuthority, sizeof(opponentAuthority));
        vmaFlushAllocation(allocator_, auxiliary_.allocation, 0u, sizeof(opponentAuthority));
        result.auxiliaryUploadMs = elapsedMs(t);
    }

    PushConstants push{};
    push.frameWidth = request.frameWidth; push.frameHeight = request.frameHeight; push.cfaPattern = request.cfaPattern;
    push.lensColumns = request.lensShadingMap != nullptr ? request.lensShadingColumns : 0u;
    push.lensRows = request.lensShadingMap != nullptr ? request.lensShadingRows : 0u;
    push.gridCols = request.noRegretGridCols; push.gridRows = request.noRegretGridRows;
    push.tileWidth = request.noRegretTileWidth; push.tileHeight = request.noRegretTileHeight;
    for (int ch = 0; ch < 4; ++ch) { push.s[ch] = request.effectiveS[static_cast<std::size_t>(ch)]; push.o[ch] = request.effectiveO[static_cast<std::size_t>(ch)]; }
    const std::uint32_t passFlags = (request.fineEnabled ? 1u : 0u) | (request.midEnabled ? 2u : 0u);
    push.params0[0] = request.blendStrength; push.params0[1] = request.isoAuthority; push.params0[2] = request.modelConfidence;
    push.params0[3] = static_cast<float>(passFlags);
    push.params1[0] = request.fineAuthorityScale; push.params1[1] = request.fineMaximumCorrectionScale;
    push.params1[2] = request.fineModelConfidence; push.params1[3] = request.fineVisibleChromaAmplification;
    push.params2[0] = request.midAuthorityScale; push.params2[1] = request.midMaximumCorrectionScale;
    push.params2[2] = request.midModelConfidence; push.params2[3] = request.midVisibleChromaAmplification;

    auto barrierBuffer = [&](VkBuffer buffer, VkAccessFlags src, VkAccessFlags dst) {
        VkBufferMemoryBarrier barrier{}; barrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        barrier.srcAccessMask = src; barrier.dstAccessMask = dst;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.buffer = buffer; barrier.size = VK_WHOLE_SIZE;
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 1u, &barrier, 0u, nullptr);
    };

    vkResetFences(device, 1u, &fence_); vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo begin{}; begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO; begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "PASS2_GPU_COMMAND_RECORDING_FAILED"; result.failureReason = "vkBeginCommandBuffer";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.timestampQueryUsed = queryPool_ != VK_NULL_HANDLE;
    if (result.timestampQueryUsed) { vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 8u); vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool_, 0u); }
    VkBufferMemoryBarrier hostWrites[4]{};
    std::uint32_t hostWriteCount = 0u;
    if (!residentInput) {
        hostWrites[hostWriteCount].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        hostWrites[hostWriteCount].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        hostWrites[hostWriteCount].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        hostWrites[hostWriteCount].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostWrites[hostWriteCount].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostWrites[hostWriteCount].buffer = input_.buffer;
        hostWrites[hostWriteCount].size = static_cast<VkDeviceSize>(result.inputBytes);
        ++hostWriteCount;
    }
    for (VkBuffer buffer : {lensShading_.buffer, auxiliary_.buffer, telemetry_.buffer}) {
        hostWrites[hostWriteCount].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        hostWrites[hostWriteCount].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        hostWrites[hostWriteCount].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        hostWrites[hostWriteCount].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostWrites[hostWriteCount].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostWrites[hostWriteCount].buffer = buffer;
        hostWrites[hostWriteCount].size = VK_WHOLE_SIZE;
        ++hostWriteCount;
    }
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                         0u, nullptr, hostWriteCount, hostWrites, 0u, nullptr);
    if (residentInput) {
        VkBufferMemoryBarrier residentBarrier{};
        residentBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        residentBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        residentBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        residentBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        residentBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        residentBarrier.buffer = residentInputBuffer;
        residentBarrier.size = static_cast<VkDeviceSize>(result.inputBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                             0u, nullptr, 1u, &residentBarrier, 0u, nullptr);
    }
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0u, 1u, &descriptorSet_, 0u, nullptr);
    const std::uint32_t gx = (request.frameWidth + 15u) / 16u, gy = (request.frameHeight + 15u) / 16u;
    const std::uint32_t quadWidth = (request.frameWidth + 1u) / 2u;
    const std::uint32_t quadHeight = (request.frameHeight + 1u) / 2u;
    const std::uint32_t gxFine = (quadWidth + 15u) / 16u;
    const std::uint32_t gyFine = (quadHeight + 15u) / 16u;
    if (request.fineEnabled) {
        // QUALITY DELTA 0007: the GALOSH-inspired fine kernel owns one aligned
        // 2x2 CFA quad per invocation and reconstructs all four sensels.
        push.mode = 0u; vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push); vkCmdDispatch(commandBuffer_, gxFine, gyFine, 1u);
    } else {
        // Mid-only mode reads the original input directly; no fine dispatch is required.
    }
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
    if (request.fineEnabled) barrierBuffer(candidate_.buffer, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
    if (request.midEnabled) {
        push.mode = 1u; vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push); vkCmdDispatch(commandBuffer_, gx, gy, 1u);
    }
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 2u);
    barrierBuffer(request.midEnabled ? scratch_.buffer : candidate_.buffer, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
    push.mode = 2u; vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (static_cast<std::uint32_t>(tileCount) + 15u) / 16u, 1u, 1u);
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 3u);
    VkBufferMemoryBarrier toHost{}; toHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER; toHost.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT; toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.buffer = tileStatistics_.buffer; toHost.size = static_cast<VkDeviceSize>(result.tileStatisticsBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr, 1u, &toHost, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "PASS2_GPU_COMMAND_RECORDING_FAILED"; result.failureReason = "vkEndCommandBuffer_candidate";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    VkSubmitInfo submit{}; submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO; submit.commandBufferCount = 1u; submit.pCommandBuffers = &commandBuffer_;
    auto syncStart = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS || vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("Pass2_Candidate");
        result.status = "GPU_STALLED"; result.failureReason = "pass2_candidate_submit_or_wait_timeout";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.synchronizationMs += elapsedMs(syncStart);
    vmaInvalidateAllocation(allocator_, tileStatistics_.allocation, 0u, result.tileStatisticsBytes);

    std::vector<float> acceptance;
    NoRegretConfig config{&request.beforeTiles, 2, request.targetFloorScale, request.minimumResidualRatio,
                          request.detailRetentionFloor, request.combinedNoisePressure, request.modelConfidence};
    const auto decisionStart = Clock::now();
    result.noRegret = decideNoRegret(config, static_cast<const TileStatsGpu*>(tileStatistics_.mapped), acceptance);
    result.noRegretCpuDecisionMs = elapsedMs(decisionStart);
    if (result.noRegret.evaluatedTiles == 0) {
        result.status = "PASS2_GPU_NO_REGRET_NO_VALID_TILES"; result.failureReason = "tile_statistics_not_qualified";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    std::memcpy(acceptance_.mapped, acceptance.data(), static_cast<std::size_t>(result.acceptanceBytes));
    vmaFlushAllocation(allocator_, acceptance_.allocation, 0u, result.acceptanceBytes);

    vkResetFences(device, 1u, &fence_); vkResetCommandBuffer(commandBuffer_, 0u);
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "PASS2_GPU_COMMAND_RECORDING_FAILED"; result.failureReason = "vkBeginCommandBuffer_blend";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool_, 4u);
    VkBufferMemoryBarrier acceptanceBarrier{}; acceptanceBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    acceptanceBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT; acceptanceBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    acceptanceBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; acceptanceBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    acceptanceBarrier.buffer = acceptance_.buffer; acceptanceBarrier.size = static_cast<VkDeviceSize>(result.acceptanceBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                         0u, nullptr, 1u, &acceptanceBarrier, 0u, nullptr);
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0u, 1u, &descriptorSet_, 0u, nullptr);
    push.mode = 3u; vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push); vkCmdDispatch(commandBuffer_, gx, gy, 1u);
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 5u);
    VkBuffer finalBuffer = request.midEnabled ? scratch_.buffer : candidate_.buffer;
    VkBufferMemoryBarrier toTransfer{}; toTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.buffer = finalBuffer; toTransfer.size = static_cast<VkDeviceSize>(result.inputBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr, 1u, &toTransfer, 0u, nullptr);
    VkBufferCopy copy{}; copy.size = static_cast<VkDeviceSize>(result.inputBytes); vkCmdCopyBuffer(commandBuffer_, finalBuffer, input_.buffer, 1u, &copy);
    VkBufferMemoryBarrier readbacks[2]{};
    std::uint32_t readbackBarrierCount = 0u;
    if (!request.deferFullFrameReadback) {
        auto& outputToHost = readbacks[readbackBarrierCount++];
        outputToHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        outputToHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        outputToHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        outputToHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        outputToHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        outputToHost.buffer = input_.buffer;
        outputToHost.size = static_cast<VkDeviceSize>(result.inputBytes);
    }
    auto& telemetryToHost = readbacks[readbackBarrierCount++];
    telemetryToHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    telemetryToHost.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    telemetryToHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    telemetryToHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    telemetryToHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    telemetryToHost.buffer = telemetry_.buffer;
    telemetryToHost.size = static_cast<VkDeviceSize>(kTelemetryBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr,
                         readbackBarrierCount, readbacks, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "PASS2_GPU_COMMAND_RECORDING_FAILED"; result.failureReason = "vkEndCommandBuffer_blend";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    syncStart = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS || vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("Pass2_Blend");
        result.status = "GPU_STALLED"; result.failureReason = "pass2_blend_submit_or_wait_timeout";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.synchronizationMs += elapsedMs(syncStart);

    if (result.timestampQueryUsed) {
        std::uint64_t ts[6]{};
        if (vkGetQueryPoolResults(device, queryPool_, 0u, 6u, sizeof(ts), ts, sizeof(std::uint64_t),
                                  VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
            VkPhysicalDeviceProperties props{}; vkGetPhysicalDeviceProperties(physicalDevice, &props);
            const double toMs = static_cast<double>(props.limits.timestampPeriod) / 1.0e6;
            if (ts[1] >= ts[0]) result.fineKernelMs = request.fineEnabled ? static_cast<float>((ts[1] - ts[0]) * toMs) : 0.0f;
            if (ts[2] >= ts[1]) result.midKernelMs = request.midEnabled ? static_cast<float>((ts[2] - ts[1]) * toMs) : 0.0f;
            if (ts[3] >= ts[2]) result.tileStatisticsKernelMs = static_cast<float>((ts[3] - ts[2]) * toMs);
            if (ts[5] >= ts[4]) result.noRegretBlendKernelMs = static_cast<float>((ts[5] - ts[4]) * toMs);
            result.gpuKernelMs = result.fineKernelMs + result.midKernelMs + result.tileStatisticsKernelMs + result.noRegretBlendKernelMs;
        } else result.timestampQueryUsed = false;
    }
    if (!result.timestampQueryUsed) result.gpuKernelMs = result.synchronizationMs;

    const auto readStart = Clock::now();
    vmaInvalidateAllocation(allocator_, telemetry_.allocation, 0u, kTelemetryBytes);
    if (!request.deferFullFrameReadback) {
        vmaInvalidateAllocation(allocator_, input_.allocation, 0u, result.inputBytes);
        result.outputMosaic.resize(static_cast<std::size_t>(pixels));
        std::memcpy(result.outputMosaic.data(), input_.mapped, static_cast<std::size_t>(result.inputBytes));
    }
    result.readbackMs = elapsedMs(readStart);
    result.fullFrameReadbackDeferred = request.deferFullFrameReadback;
    const auto* words = static_cast<const std::uint32_t*>(telemetry_.mapped);
    result.telemetry.fineCandidatePixelCount = words[0]; result.telemetry.fineChangedPixelCount = words[1];
    result.telemetry.fineStructureRejectedPixelCount = words[2]; result.telemetry.fineMaximumCorrection = std::abs(bitsFloat(words[3]));
    result.telemetry.midCandidatePixelCount = words[4]; result.telemetry.midChangedPixelCount = words[5];
    result.telemetry.midStructureRejectedPixelCount = words[6]; result.telemetry.midMaximumCorrection = std::abs(bitsFloat(words[7]));
    result.telemetry.profiledStrongFineBlendQuadCount = words[13];
    result.telemetry.profiledColorEdgeProtectedQuadCount = words[14];
    result.telemetry.profiledHeavyFineCleanQuadCount = words[15];
    result.noRegret.attenuatedPixelFraction = pixels > 0u ? static_cast<float>(words[80]) / static_cast<float>(pixels) : 0.0f;
    result.gpuNoRegretBlendUsed = true; result.cpuFullFrameCandidateReadbackAvoided = true;
    result.transferAndSyncMs = result.inputPackingMs + result.auxiliaryUploadMs + result.synchronizationMs + result.readbackMs;
    ++residentOutputGeneration_;
    residentOutputBytes_ = result.inputBytes;
    residentOutputWidth_ = request.frameWidth;
    residentOutputHeight_ = request.frameHeight;
    result.residentOutputGeneration = residentOutputGeneration_;
    result.residentOutputBytes = residentOutputBytes_;
    result.totalMs = elapsedMs(totalStarted);
    result.success = request.deferFullFrameReadback
            ? result.residentOutputGeneration != 0u
            : result.outputMosaic.size() == static_cast<std::size_t>(pixels);
    result.status = result.success
            ? (request.deferFullFrameReadback
                    ? (residentInput
                            ? "SPECTRA_PASS2_RESIDENT_FROM_PASS1_ZERO_READBACK_READY"
                            : "SPECTRA_PASS2_GPU_RESIDENT_ZERO_READBACK_READY")
                    : (residentInput ? "SPECTRA_PASS2_RESIDENT_FROM_PASS1_READY"
                                     : "SPECTRA_PASS2_GPU_PRIMARY_READY"))
            : "SPECTRA_PASS2_GPU_OUTPUT_UNAVAILABLE";
    if (!result.success) result.failureReason = request.deferFullFrameReadback
            ? "PASS2_GPU_RESIDENT_OUTPUT_UNAVAILABLE"
            : "PASS2_GPU_FINAL_READBACK_SIZE_MISMATCH";
    return result;
#endif
}

SpectraResidentChromaResult VulkanSpectraResidentChromaBackend::executePass3(
        VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue, VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner, const SpectraResidentPass3Request& request) noexcept {
    return executePass3Internal(physicalDevice, device, computeQueue, commandPool, allocatorOwner,
                                VK_NULL_HANDLE, 0u, request);
}

SpectraResidentChromaResult VulkanSpectraResidentChromaBackend::executePass3FromResident(
        VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue, VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner, VkBuffer residentInputBuffer,
        std::uint64_t residentInputBytes, const SpectraResidentPass3Request& request) noexcept {
    return executePass3Internal(physicalDevice, device, computeQueue, commandPool, allocatorOwner,
                                residentInputBuffer, residentInputBytes, request);
}

SpectraResidentChromaResult VulkanSpectraResidentChromaBackend::executePass3Internal(
        VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue, VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner, VkBuffer residentInputBuffer,
        std::uint64_t residentInputBytes, const SpectraResidentPass3Request& request) noexcept {
    SpectraResidentChromaResult result{};
    result.attempted = true; result.pipelineAvailable = productionKernelConnected();
    const auto totalStarted = Clock::now();
#if !BNCAM_VMA_HEADER_AVAILABLE || !BNCAM_SPECTRA_CHROMA_RESIDENT_SHADER_AVAILABLE
    (void)physicalDevice; (void)device; (void)computeQueue; (void)commandPool; (void)allocatorOwner;
    (void)residentInputBuffer; (void)residentInputBytes; (void)request;
    result.status = !BNCAM_VMA_HEADER_AVAILABLE ? "VMA_HEADER_NOT_AVAILABLE" : "RESIDENT_CHROMA_SHADER_NOT_COMPILED";
    result.failureReason = result.status; result.totalMs = elapsedMs(totalStarted); return result;
#else
    std::lock_guard<std::mutex> lock(mutex_);
    std::string failure;
    if (!initializeLocked(device, commandPool, failure)) {
        result.status = "RESIDENT_CHROMA_INITIALIZATION_FAILED"; result.failureReason = failure;
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    allocator_ = allocatorOwner.handle();
    const bool residentInput = residentInputBuffer != VK_NULL_HANDLE;
    const bool anyStage = request.applyRowBanding || request.applyColumnBanding || request.applyLowFrequencyChroma;
    if (allocator_ == nullptr || (!residentInput && request.mosaicData == nullptr) ||
        request.frameWidth == 0u || request.frameHeight == 0u ||
        request.beforeTiles.empty() || request.noRegretGridCols == 0u || request.noRegretGridRows == 0u || !anyStage) {
        result.status = "PASS3_GPU_INVALID_REQUEST"; result.failureReason = "missing_input_or_active_stage_or_no_regret_grid";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    std::uint64_t pixels = 0u;
    if (!checkedMultiply(request.frameWidth, request.frameHeight, pixels) ||
        !checkedMultiply(pixels, sizeof(float), result.inputBytes)) {
        result.status = "PASS3_GPU_SIZE_OVERFLOW"; result.failureReason = "frame_size_overflow";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    if (residentInput && residentInputBytes < result.inputBytes) {
        result.status = "PASS3_GPU_RESIDENT_INPUT_SIZE_MISMATCH";
        result.failureReason = "resident_pass2_buffer_smaller_than_requested_frame";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.residentInputUsed = residentInput;
    const std::uint64_t tileCount = static_cast<std::uint64_t>(request.noRegretGridCols) * request.noRegretGridRows;
    result.tileStatisticsBytes = tileCount * sizeof(TileStatsGpu); result.acceptanceBytes = tileCount * sizeof(float);
    const std::uint64_t gridSize = static_cast<std::uint64_t>(request.chromaGridCols) * request.chromaGridRows;
    // Delta 24 appends two floats after the existing row/column + four low-frequency grids:
    // low R authority multiplier, low B authority multiplier.
    const std::uint64_t auxiliaryFloats =
            4ull * request.frameHeight + 4ull * request.frameWidth + 4ull * gridSize + 2ull;
    result.auxiliaryBytes = std::max<std::uint64_t>(kDummyBytes, auxiliaryFloats * sizeof(float));
    bool reallocated = false; const std::uint32_t mapped = VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
    if (!ensureBufferLocked(allocator_, result.inputBytes, mapped, input_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, result.inputBytes, 0u, candidate_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, result.inputBytes, 0u, scratch_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, kDummyBytes, mapped, lensShading_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, result.auxiliaryBytes, mapped, auxiliary_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, result.tileStatisticsBytes, mapped, tileStatistics_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, result.acceptanceBytes, mapped, acceptance_, reallocated, failure) ||
        !ensureBufferLocked(allocator_, kTelemetryBytes, mapped, telemetry_, reallocated, failure)) {
        result.status = "PASS3_GPU_BUFFER_ALLOCATION_FAILED"; result.failureReason = failure;
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.persistentBufferReallocated = reallocated; result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = input_.capacityBytes + candidate_.capacityBytes + scratch_.capacityBytes +
            lensShading_.capacityBytes + auxiliary_.capacityBytes + tileStatistics_.capacityBytes +
            acceptance_.capacityBytes + telemetry_.capacityBytes;
    updateDescriptorSetLocked(device, residentInput ? residentInputBuffer : VK_NULL_HANDLE);

    {
        const auto t = Clock::now();
        if (!residentInput) {
            float* dst = static_cast<float*>(input_.mapped);
            if (request.rowStrideFloats == request.frameWidth) {
                std::memcpy(dst, request.mosaicData, static_cast<std::size_t>(result.inputBytes));
            } else {
                for (std::uint32_t y = 0; y < request.frameHeight; ++y) {
                    std::memcpy(dst + static_cast<std::size_t>(y) * request.frameWidth,
                                request.mosaicData + static_cast<std::size_t>(y) * request.rowStrideFloats,
                                static_cast<std::size_t>(request.frameWidth) * sizeof(float));
                }
            }
            vmaFlushAllocation(allocator_, input_.allocation, 0u, result.inputBytes);
        }
        std::memset(telemetry_.mapped, 0, static_cast<std::size_t>(kTelemetryBytes));
        vmaFlushAllocation(allocator_, telemetry_.allocation, 0u, kTelemetryBytes);
        result.inputPackingMs = residentInput ? 0.0f : elapsedMs(t);
    }
    {
        const auto t = Clock::now();
        float* aux = static_cast<float*>(auxiliary_.mapped); std::size_t cursor = 0u;
        for (int ch = 0; ch < 4; ++ch) {
            if (request.rowProfileByChannel[ch].size() != request.frameHeight) {
                result.status = "PASS3_GPU_PROFILE_SIZE_MISMATCH"; result.failureReason = "row_profile_size"; result.totalMs = elapsedMs(totalStarted); return result;
            }
            std::memcpy(aux + cursor, request.rowProfileByChannel[ch].data(), request.frameHeight * sizeof(float)); cursor += request.frameHeight;
        }
        for (int ch = 0; ch < 4; ++ch) {
            if (request.columnProfileByChannel[ch].size() != request.frameWidth) {
                result.status = "PASS3_GPU_PROFILE_SIZE_MISMATCH"; result.failureReason = "column_profile_size"; result.totalMs = elapsedMs(totalStarted); return result;
            }
            std::memcpy(aux + cursor, request.columnProfileByChannel[ch].data(), request.frameWidth * sizeof(float)); cursor += request.frameWidth;
        }
        const std::size_t gs = static_cast<std::size_t>(gridSize);
        for (const std::vector<float>* grid : {&request.lowFreqRGrid, &request.lowFreqBGrid,
                                               &request.lowFreqRConfidenceGrid, &request.lowFreqBConfidenceGrid}) {
            if (request.applyLowFrequencyChroma && grid->size() != gs) {
                result.status = "PASS3_GPU_GRID_SIZE_MISMATCH"; result.failureReason = "low_frequency_grid_size"; result.totalMs = elapsedMs(totalStarted); return result;
            }
            if (grid->size() == gs && gs > 0u) std::memcpy(aux + cursor, grid->data(), gs * sizeof(float));
            else if (gs > 0u) std::fill(aux + cursor, aux + cursor + gs, 0.0f);
            cursor += gs;
        }
        aux[cursor++] = std::clamp(request.lowRedAuthorityMultiplier, 0.88f, 1.12f);
        aux[cursor++] = std::clamp(request.lowBlueAuthorityMultiplier, 0.88f, 1.12f);
        vmaFlushAllocation(allocator_, auxiliary_.allocation, 0u, result.auxiliaryBytes);
        result.auxiliaryUploadMs = elapsedMs(t);
    }

    PushConstants push{};
    push.frameWidth = request.frameWidth; push.frameHeight = request.frameHeight; push.cfaPattern = request.cfaPattern;
    push.gridCols = request.noRegretGridCols; push.gridRows = request.noRegretGridRows;
    push.tileWidth = request.noRegretTileWidth; push.tileHeight = request.noRegretTileHeight;
    push.fieldCols = request.chromaGridCols; push.fieldRows = request.chromaGridRows;
    for (int ch = 0; ch < 4; ++ch) { push.s[ch] = request.effectiveS[static_cast<std::size_t>(ch)]; push.o[ch] = request.effectiveO[static_cast<std::size_t>(ch)]; }
    const std::uint32_t passFlags = (request.applyRowBanding ? 1u : 0u) | (request.applyColumnBanding ? 2u : 0u) |
            (request.applyLowFrequencyChroma ? 4u : 0u);
    push.params0[0] = request.bandingAuthority; push.params0[1] = request.chromaAuthority;
    push.params0[2] = request.lowFrequencyConfidence; push.params0[3] = static_cast<float>(passFlags);
    push.params1[0] = request.lowAuthorityScale; push.params1[1] = request.lowMaximumCorrectionScale;
    push.params1[2] = request.lowModelConfidence; push.params1[3] = request.lowVisibleChromaAmplification;

    vkResetFences(device, 1u, &fence_); vkResetCommandBuffer(commandBuffer_, 0u);
    VkCommandBufferBeginInfo begin{}; begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO; begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "PASS3_GPU_COMMAND_RECORDING_FAILED"; result.failureReason = "vkBeginCommandBuffer"; result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.timestampQueryUsed = queryPool_ != VK_NULL_HANDLE;
    if (result.timestampQueryUsed) { vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 8u); vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool_, 0u); }
    VkBufferMemoryBarrier inputReady{};
    inputReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    inputReady.srcAccessMask = residentInput ? VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT
                                             : VK_ACCESS_HOST_WRITE_BIT;
    inputReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    inputReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    inputReady.buffer = residentInput ? residentInputBuffer : input_.buffer;
    inputReady.size = static_cast<VkDeviceSize>(result.inputBytes);
    VkBufferMemoryBarrier hostWrites[2]{};
    const VkBuffer hostBuffers[2]{auxiliary_.buffer, telemetry_.buffer};
    for (std::uint32_t i = 0; i < 2u; ++i) {
        hostWrites[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        hostWrites[i].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        hostWrites[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        hostWrites[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostWrites[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostWrites[i].buffer = hostBuffers[i];
        hostWrites[i].size = VK_WHOLE_SIZE;
    }
    const VkPipelineStageFlags inputSourceStage = residentInput
            ? (VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT)
            : VK_PIPELINE_STAGE_HOST_BIT;
    vkCmdPipelineBarrier(commandBuffer_, inputSourceStage,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                         0u, nullptr, 1u, &inputReady, 0u, nullptr);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u,
                         0u, nullptr, 2u, hostWrites, 0u, nullptr);
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0u, 1u, &descriptorSet_, 0u, nullptr);
    const std::uint32_t gx = (request.frameWidth + 15u) / 16u, gy = (request.frameHeight + 15u) / 16u;
    push.mode = 4u; vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push); vkCmdDispatch(commandBuffer_, gx, gy, 1u);
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
    VkBufferMemoryBarrier candidateBarrier{}; candidateBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    candidateBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; candidateBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    candidateBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; candidateBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    candidateBarrier.buffer = candidate_.buffer; candidateBarrier.size = static_cast<VkDeviceSize>(result.inputBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 1u, &candidateBarrier, 0u, nullptr);
    push.mode = 5u; vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push);
    vkCmdDispatch(commandBuffer_, (static_cast<std::uint32_t>(tileCount) + 15u) / 16u, 1u, 1u);
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 2u);
    VkBufferMemoryBarrier toHost{}; toHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER; toHost.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT; toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.buffer = tileStatistics_.buffer; toHost.size = static_cast<VkDeviceSize>(result.tileStatisticsBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr, 1u, &toHost, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "PASS3_GPU_COMMAND_RECORDING_FAILED"; result.failureReason = "vkEndCommandBuffer_candidate"; result.totalMs = elapsedMs(totalStarted); return result;
    }
    VkSubmitInfo submit{}; submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO; submit.commandBufferCount = 1u; submit.pCommandBuffers = &commandBuffer_;
    auto syncStart = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS || vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("Pass3_Candidate");
        result.status = "GPU_STALLED"; result.failureReason = "pass3_candidate_submit_or_wait_timeout"; result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.synchronizationMs += elapsedMs(syncStart);
    vmaInvalidateAllocation(allocator_, tileStatistics_.allocation, 0u, result.tileStatisticsBytes);

    std::vector<float> acceptance;
    NoRegretConfig config{&request.beforeTiles, 3, request.targetFloorScale, request.minimumResidualRatio,
                          request.detailRetentionFloor, request.combinedNoisePressure, request.modelConfidence};
    const auto decisionStart = Clock::now();
    result.noRegret = decideNoRegret(config, static_cast<const TileStatsGpu*>(tileStatistics_.mapped), acceptance);
    result.noRegretCpuDecisionMs = elapsedMs(decisionStart);
    if (result.noRegret.evaluatedTiles == 0) {
        result.status = "PASS3_GPU_NO_REGRET_NO_VALID_TILES"; result.failureReason = "tile_statistics_not_qualified"; result.totalMs = elapsedMs(totalStarted); return result;
    }
    std::memcpy(acceptance_.mapped, acceptance.data(), static_cast<std::size_t>(result.acceptanceBytes));
    vmaFlushAllocation(allocator_, acceptance_.allocation, 0u, result.acceptanceBytes);

    vkResetFences(device, 1u, &fence_); vkResetCommandBuffer(commandBuffer_, 0u);
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "PASS3_GPU_COMMAND_RECORDING_FAILED"; result.failureReason = "vkBeginCommandBuffer_blend"; result.totalMs = elapsedMs(totalStarted); return result;
    }
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool_, 4u);
    VkBufferMemoryBarrier acceptanceBarrier{}; acceptanceBarrier.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    acceptanceBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT; acceptanceBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    acceptanceBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; acceptanceBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    acceptanceBarrier.buffer = acceptance_.buffer; acceptanceBarrier.size = static_cast<VkDeviceSize>(result.acceptanceBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0u, 0u, nullptr, 1u, &acceptanceBarrier, 0u, nullptr);
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0u, 1u, &descriptorSet_, 0u, nullptr);
    push.mode = 6u; vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(push), &push); vkCmdDispatch(commandBuffer_, gx, gy, 1u);
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 5u);
    VkBufferMemoryBarrier toTransfer{}; toTransfer.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    toTransfer.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED; toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.buffer = candidate_.buffer; toTransfer.size = static_cast<VkDeviceSize>(result.inputBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0u, 0u, nullptr, 1u, &toTransfer, 0u, nullptr);
    VkBufferCopy copy{}; copy.size = static_cast<VkDeviceSize>(result.inputBytes); vkCmdCopyBuffer(commandBuffer_, candidate_.buffer, input_.buffer, 1u, &copy);
    VkBufferMemoryBarrier telemetryToHost{};
    telemetryToHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    telemetryToHost.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    telemetryToHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    telemetryToHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    telemetryToHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    telemetryToHost.buffer = telemetry_.buffer;
    telemetryToHost.size = static_cast<VkDeviceSize>(kTelemetryBytes);
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr,
                         1u, &telemetryToHost, 0u, nullptr);
    if (!request.deferFullFrameReadback) {
        VkBufferMemoryBarrier outputToHost{};
        outputToHost.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        outputToHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        outputToHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        outputToHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        outputToHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        outputToHost.buffer = input_.buffer;
        outputToHost.size = static_cast<VkDeviceSize>(result.inputBytes);
        vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             VK_PIPELINE_STAGE_HOST_BIT, 0u, 0u, nullptr,
                             1u, &outputToHost, 0u, nullptr);
    }
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "PASS3_GPU_COMMAND_RECORDING_FAILED"; result.failureReason = "vkEndCommandBuffer_blend"; result.totalMs = elapsedMs(totalStarted); return result;
    }
    syncStart = Clock::now();
    if (vkQueueSubmit(computeQueue, 1u, &submit, fence_) != VK_SUCCESS || vkWaitForFences(device, 1u, &fence_, VK_TRUE, 3'000'000'000ull) != VK_SUCCESS) {
        VulkanRuntime::instance().markGpuStalled("Pass3_Blend");
        result.status = "GPU_STALLED"; result.failureReason = "pass3_blend_submit_or_wait_timeout"; result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.synchronizationMs += elapsedMs(syncStart);

    if (result.timestampQueryUsed) {
        std::uint64_t ts[6]{};
        if (vkGetQueryPoolResults(device, queryPool_, 0u, 6u, sizeof(ts), ts, sizeof(std::uint64_t), VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
            VkPhysicalDeviceProperties props{}; vkGetPhysicalDeviceProperties(physicalDevice, &props);
            const double toMs = static_cast<double>(props.limits.timestampPeriod) / 1.0e6;
            if (ts[1] >= ts[0]) result.pass3KernelMs = static_cast<float>((ts[1] - ts[0]) * toMs);
            if (ts[2] >= ts[1]) result.tileStatisticsKernelMs = static_cast<float>((ts[2] - ts[1]) * toMs);
            if (ts[5] >= ts[4]) result.noRegretBlendKernelMs = static_cast<float>((ts[5] - ts[4]) * toMs);
            result.gpuKernelMs = result.pass3KernelMs + result.tileStatisticsKernelMs + result.noRegretBlendKernelMs;
        } else result.timestampQueryUsed = false;
    }
    if (!result.timestampQueryUsed) result.gpuKernelMs = result.synchronizationMs;

    const auto readStart = Clock::now();
    vmaInvalidateAllocation(allocator_, telemetry_.allocation, 0u, kTelemetryBytes);
    if (!request.deferFullFrameReadback) {
        vmaInvalidateAllocation(allocator_, input_.allocation, 0u, result.inputBytes);
        result.outputMosaic.resize(static_cast<std::size_t>(pixels));
        std::memcpy(result.outputMosaic.data(), input_.mapped, static_cast<std::size_t>(result.inputBytes));
    }
    result.readbackMs = elapsedMs(readStart);
    result.fullFrameReadbackDeferred = request.deferFullFrameReadback;
    const auto* words = static_cast<const std::uint32_t*>(telemetry_.mapped);
    result.telemetry.pass3ChangedPixelCount = words[8]; result.telemetry.pass3LowCandidatePixelCount = words[9];
    result.telemetry.pass3LowStructureRejectedPixelCount = words[10]; result.telemetry.pass3LowChromaChangedPixelCount = words[11];
    result.telemetry.pass3MaximumLowChromaShift = std::abs(bitsFloat(words[12]));
    result.noRegret.attenuatedPixelFraction = pixels > 0u ? static_cast<float>(words[80]) / static_cast<float>(pixels) : 0.0f;
    result.gpuNoRegretBlendUsed = true; result.cpuFullFrameCandidateReadbackAvoided = true;
    result.transferAndSyncMs = result.inputPackingMs + result.auxiliaryUploadMs + result.synchronizationMs + result.readbackMs;
    ++residentOutputGeneration_;
    residentOutputBytes_ = result.inputBytes;
    residentOutputWidth_ = request.frameWidth;
    residentOutputHeight_ = request.frameHeight;
    result.residentOutputGeneration = residentOutputGeneration_;
    result.residentOutputBytes = residentOutputBytes_;
    result.totalMs = elapsedMs(totalStarted);
    result.success = request.deferFullFrameReadback
            ? result.residentOutputGeneration != 0u
            : result.outputMosaic.size() == static_cast<std::size_t>(pixels);
    result.status = result.success
            ? (request.deferFullFrameReadback
                    ? "SPECTRA_PASS3_GPU_RESIDENT_DEMOSAIC_HANDOFF_READY"
                    : (residentInput ? "SPECTRA_PASS3_RESIDENT_FROM_PASS2_READY"
                                     : "SPECTRA_PASS3_GPU_PRIMARY_READY"))
            : "SPECTRA_PASS3_GPU_OUTPUT_SIZE_MISMATCH";
    if (!result.success) result.failureReason = "PASS3_GPU_FINAL_OUTPUT_UNAVAILABLE";
    return result;
#endif
}

} // namespace bncam::vulkan
