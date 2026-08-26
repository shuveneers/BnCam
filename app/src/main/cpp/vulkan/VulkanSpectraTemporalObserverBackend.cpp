#include "VulkanSpectraTemporalObserverBackend.h"
#include "VulkanPipelineCacheRegistry.h"
#include "VulkanRuntime.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif
#ifndef BNCAM_SPECTRA_TEMPORAL_OBSERVER_SHADER_AVAILABLE
#define BNCAM_SPECTRA_TEMPORAL_OBSERVER_SHADER_AVAILABLE 0
#endif
#if BNCAM_SPECTRA_TEMPORAL_OBSERVER_SHADER_AVAILABLE
#include "SpectraTemporalObserverSpirv.h"
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

[[maybe_unused]] constexpr std::uint32_t kLocalSize = 64u;
[[maybe_unused]] constexpr std::uint32_t kCoarseStride = 10u;
constexpr std::uint32_t kObserverStride = 244u;
constexpr std::uint32_t kObserverBinBase = 80u;
constexpr std::uint32_t kObserverGlobalBase = 240u;
constexpr std::uint32_t kSignalBins = 8u;
[[maybe_unused]] constexpr std::uint32_t kStaticCellSize = 48u;
constexpr std::uint32_t kHistogramBins = 1024u;
constexpr double kHistogramMaxZ = 8.0;
[[maybe_unused]] constexpr std::uint32_t kParamsWords = 48u;

float elapsedMs(Clock::time_point started) {
    return static_cast<float>(std::chrono::duration<double, std::milli>(Clock::now() - started).count());
}

[[maybe_unused]] std::uint32_t floatBits(float value) {
    std::uint32_t bits = 0u;
    static_assert(sizeof(bits) == sizeof(value));
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

[[maybe_unused]] bool checkedMultiply(std::uint64_t a, std::uint64_t b, std::uint64_t& out) {
    if (a == 0u || b == 0u) { out = 0u; return true; }
    if (a > std::numeric_limits<std::uint64_t>::max() / b) return false;
    out = a * b;
    return true;
}

[[maybe_unused]] std::uint32_t ceilDiv(std::uint32_t value, std::uint32_t divisor) {
    return divisor == 0u ? 0u : (value + divisor - 1u) / divisor;
}

[[maybe_unused]] double histogramP90(const std::uint32_t* histogram) {
    if (histogram == nullptr) return 0.0;
    std::uint64_t total = 0u;
    for (std::uint32_t i = 0; i < kHistogramBins; ++i) total += histogram[i];
    if (total == 0u) return 0.0;
    const std::uint64_t target = static_cast<std::uint64_t>(std::ceil(0.90 * static_cast<double>(total)));
    std::uint64_t cumulative = 0u;
    for (std::uint32_t i = 0; i < kHistogramBins; ++i) {
        cumulative += histogram[i];
        if (cumulative >= target) {
            return (static_cast<double>(i) + 0.5) * kHistogramMaxZ /
                    static_cast<double>(kHistogramBins);
        }
    }
    return kHistogramMaxZ;
}

struct ReducedObserver {
    std::array<double, 4> weightSum{};
    std::array<double, 4> diffSum{};
    std::array<double, 4> diffSqSum{};
    std::array<double, 4> signalSum{};
    std::array<double, 4> innovationSum{};
    std::array<double, 4> innovationSqSum{};
    std::array<double, 4> residualAnchorSum{};
    std::array<double, 4> residualSupportSum{};
    std::array<double, 4> residualAnchorSqSum{};
    std::array<double, 4> residualSupportSqSum{};
    std::array<double, 4> residualCrossSum{};
    std::array<double, 4> residualWeightSum{};
    std::array<double, 4> innovationLagProduct{};
    std::array<double, 4> innovationLagWeight{};
    std::array<double, 4> previousInnovation{};
    std::array<bool, 4> hasPreviousInnovation{};
    std::array<std::uint64_t, 4> samples{};
    std::array<std::uint64_t, 4> heavyTailCount{};

    std::array<std::array<double, kSignalBins>, 4> binDiffSum{};
    std::array<std::array<double, kSignalBins>, 4> binDiffSqSum{};
    std::array<std::array<double, kSignalBins>, 4> binSignalSum{};
    std::array<std::array<double, kSignalBins>, 4> binWeight{};
    std::array<std::array<std::uint64_t, kSignalBins>, 4> binCount{};

    std::uint64_t totalMotionSamples = 0u;
    std::uint64_t acceptedMotionSamples = 0u;
    std::uint64_t highConfidenceStaticSamples = 0u;
    std::uint64_t localMotionRejectedSamples = 0u;
};

[[maybe_unused]] ReducedObserver reduceObserverPartials(const float* partials, std::uint32_t groupCount) {
    ReducedObserver out{};
    if (partials == nullptr) return out;
    for (std::uint32_t group = 0; group < groupCount; ++group) {
        const float* rec = partials + static_cast<std::size_t>(group) * kObserverStride;
        for (std::uint32_t ch = 0; ch < 4u; ++ch) {
            const std::uint32_t cb = ch * 20u;
            out.weightSum[ch] += rec[cb + 0u];
            out.diffSum[ch] += rec[cb + 1u];
            out.diffSqSum[ch] += rec[cb + 2u];
            out.signalSum[ch] += rec[cb + 3u];
            out.innovationSum[ch] += rec[cb + 4u];
            out.innovationSqSum[ch] += rec[cb + 5u];
            out.residualAnchorSum[ch] += rec[cb + 6u];
            out.residualSupportSum[ch] += rec[cb + 7u];
            out.residualAnchorSqSum[ch] += rec[cb + 8u];
            out.residualSupportSqSum[ch] += rec[cb + 9u];
            out.residualCrossSum[ch] += rec[cb + 10u];
            out.residualWeightSum[ch] += rec[cb + 11u];
            out.samples[ch] += static_cast<std::uint64_t>(std::llround(std::max(0.0f, rec[cb + 12u])));
            out.heavyTailCount[ch] += static_cast<std::uint64_t>(std::llround(std::max(0.0f, rec[cb + 13u])));
            out.innovationLagProduct[ch] += rec[cb + 14u];
            out.innovationLagWeight[ch] += rec[cb + 15u];
            const bool groupHas = rec[cb + 19u] > 0.5f;
            if (groupHas) {
                const double firstZ = rec[cb + 16u];
                const double firstWeight = rec[cb + 17u];
                if (out.hasPreviousInnovation[ch]) {
                    out.innovationLagProduct[ch] += firstWeight * firstZ * out.previousInnovation[ch];
                    out.innovationLagWeight[ch] += firstWeight;
                }
                out.previousInnovation[ch] = rec[cb + 18u];
                out.hasPreviousInnovation[ch] = true;
            }
            for (std::uint32_t bin = 0; bin < kSignalBins; ++bin) {
                const std::uint32_t bb = kObserverBinBase + (ch * kSignalBins + bin) * 5u;
                out.binDiffSum[ch][bin] += rec[bb + 0u];
                out.binDiffSqSum[ch][bin] += rec[bb + 1u];
                out.binSignalSum[ch][bin] += rec[bb + 2u];
                out.binWeight[ch][bin] += rec[bb + 3u];
                out.binCount[ch][bin] += static_cast<std::uint64_t>(
                        std::llround(std::max(0.0f, rec[bb + 4u])));
            }
        }
        out.totalMotionSamples += static_cast<std::uint64_t>(
                std::llround(std::max(0.0f, rec[kObserverGlobalBase + 0u])));
        out.acceptedMotionSamples += static_cast<std::uint64_t>(
                std::llround(std::max(0.0f, rec[kObserverGlobalBase + 1u])));
        out.highConfidenceStaticSamples += static_cast<std::uint64_t>(
                std::llround(std::max(0.0f, rec[kObserverGlobalBase + 2u])));
        out.localMotionRejectedSamples += static_cast<std::uint64_t>(
                std::llround(std::max(0.0f, rec[kObserverGlobalBase + 3u])));
    }
    return out;
}

[[maybe_unused]] void finalizeObservation(
        const SpectraTemporalObserverRequest& request,
        const ReducedObserver& reduced,
        SpectraTemporalObserverResult& result
) {
    result.samples = reduced.samples;
    result.highConfidenceStaticSamples = reduced.highConfidenceStaticSamples;
    result.localMotionRejectedSamples = reduced.localMotionRejectedSamples;
    result.motionConfidence = reduced.totalMotionSamples > 0u
            ? static_cast<float>(reduced.acceptedMotionSamples) /
                    static_cast<float>(reduced.totalMotionSamples)
            : 0.0f;

    double confidenceSum = 0.0;
    double correlationWeighted = 0.0;
    double correlationWeight = 0.0;
    double globalInnovationWeight = 0.0;
    double globalInnovationMeanWeighted = 0.0;
    double globalInnovationVarianceWeighted = 0.0;
    double globalInnovationLagWeighted = 0.0;
    double globalHeavyTailWeighted = 0.0;
    int validChannels = 0;

    for (int ch = 0; ch < 4; ++ch) {
        const std::uint64_t count = reduced.samples[ch];
        const double weightSum = reduced.weightSum[ch];
        if (count < 64u || weightSum <= 1.0e-12) continue;

        const double meanDiff = reduced.diffSum[ch] / weightSum;
        const double diffVariance = std::max(
                0.0, reduced.diffSqSum[ch] / weightSum - meanDiff * meanDiff);
        const double observed = 0.5 * diffVariance;
        const double meanSignal = reduced.signalSum[ch] / weightSum;
        const double predicted = std::max(
                1.0e-12, request.effectiveS[ch] * meanSignal + request.effectiveO[ch]);
        result.observedVariance[ch] = observed;
        result.predictedVariance[ch] = predicted;

        const double innovationMean = reduced.innovationSum[ch] / weightSum;
        const double innovationVariance = std::max(
                0.0, reduced.innovationSqSum[ch] / weightSum - innovationMean * innovationMean);
        const double innovationLag = reduced.innovationLagWeight[ch] > 1.0e-12 && innovationVariance > 1.0e-12
                ? std::clamp(
                        reduced.innovationLagProduct[ch] / reduced.innovationLagWeight[ch] /
                                std::max(innovationVariance, 1.0e-12),
                        -1.0, 1.0)
                : 0.0;
        const double heavyTailFraction = static_cast<double>(reduced.heavyTailCount[ch]) /
                static_cast<double>(std::max<std::uint64_t>(1u, count));
        const spectra_temporal::InnovationDiagnostics innovation{
                innovationMean, innovationVariance, innovationLag, heavyTailFraction, count};

        double temporalCorrelation = 0.0;
        const double residualWeight = reduced.residualWeightSum[ch];
        if (residualWeight > 64.0) {
            const double meanA = reduced.residualAnchorSum[ch] / residualWeight;
            const double meanB = reduced.residualSupportSum[ch] / residualWeight;
            const double varianceA = std::max(
                    0.0, reduced.residualAnchorSqSum[ch] / residualWeight - meanA * meanA);
            const double varianceB = std::max(
                    0.0, reduced.residualSupportSqSum[ch] / residualWeight - meanB * meanB);
            const double covariance = reduced.residualCrossSum[ch] / residualWeight - meanA * meanB;
            if (varianceA > 1.0e-12 && varianceB > 1.0e-12) {
                const double rawCorrelation = covariance / std::sqrt(varianceA * varianceB);
                const double correlationConfidence = std::clamp(residualWeight / 2048.0, 0.0, 1.0) *
                        static_cast<double>(result.forwardBackwardConsistency) *
                        result.staticProbabilityP50 * static_cast<double>(request.modelConfidence);
                temporalCorrelation = std::clamp(
                        std::max(0.0, rawCorrelation) * correlationConfidence, 0.0, 0.85);
            }
        }
        if (temporalCorrelation <= 0.0) {
            const double varianceDeficit = std::clamp(1.0 - observed / predicted, 0.0, 1.0);
            temporalCorrelation = std::clamp(
                    0.25 * varianceDeficit * result.staticProbabilityP50 *
                            result.forwardBackwardConsistency * request.modelConfidence,
                    0.0, 0.40);
        }
        result.temporalCorrelation[ch] = temporalCorrelation;
        result.persistentPatternFraction[ch] = temporalCorrelation;
        const double correlationCorrection = 1.0 / std::max(0.15, 1.0 - temporalCorrelation);

        std::vector<spectra_temporal::TemporalFitPoint> points;
        points.reserve(kSignalBins);
        double minSignal = 1.0;
        double maxSignal = 0.0;
        for (std::uint32_t bin = 0; bin < kSignalBins; ++bin) {
            const std::uint64_t binSamples = reduced.binCount[ch][bin];
            const double weight = reduced.binWeight[ch][bin];
            if (binSamples < 24u || weight <= 1.0e-12) continue;
            const double binMeanDiff = reduced.binDiffSum[ch][bin] / weight;
            const double binVariance = std::max(
                    0.0, reduced.binDiffSqSum[ch][bin] / weight - binMeanDiff * binMeanDiff);
            const double signal = reduced.binSignalSum[ch][bin] / weight;
            const double variance = 0.5 * binVariance * correlationCorrection;
            if (!std::isfinite(signal) || !std::isfinite(variance) || variance <= 0.0) continue;
            points.push_back({signal, variance, std::sqrt(weight)});
            minSignal = std::min(minSignal, signal);
            maxSignal = std::max(maxSignal, signal);
        }

        const double signalSpan = points.empty() ? 0.0 : std::max(0.0, maxSignal - minSignal);
        result.populatedSignalBins[ch] = static_cast<int>(points.size());
        result.signalSpan[ch] = signalSpan;
        const auto fit = spectra_temporal::fitTemporalNoiseModel(points, innovation);

        double sScale = 1.0;
        double oScale = 1.0;
        double regressionConfidence = 0.0;
        if (fit.valid && points.size() >= 3u && signalSpan >= 0.035) {
            if (request.effectiveS[ch] > 1.0e-12) {
                sScale = std::clamp(
                        fit.slope / request.effectiveS[ch],
                        request.adaptationLowerBound, request.adaptationUpperBound);
            }
            if (request.effectiveO[ch] > 1.0e-12) {
                oScale = std::clamp(
                        fit.offset / request.effectiveO[ch],
                        request.adaptationLowerBound, request.adaptationUpperBound);
            }
            const double sampleConfidence = std::clamp(static_cast<double>(count) / 4096.0, 0.0, 1.0);
            regressionConfidence = fit.confidence * sampleConfidence;
            result.fitEstimator[ch] = static_cast<int>(fit.estimator);
            result.fitPhysicalScore[ch] = fit.metrics.physicalScore;
        }
        if (regressionConfidence < 0.05) {
            const double commonScale = std::clamp(
                    (observed * correlationCorrection) / predicted,
                    request.adaptationLowerBound, request.adaptationUpperBound);
            sScale = commonScale;
            oScale = commonScale;
            regressionConfidence = 0.15 * std::clamp(
                    static_cast<double>(count) / 4096.0, 0.0, 1.0) * result.staticProbabilityP50;
            result.fitEstimator[ch] = static_cast<int>(spectra_temporal::TemporalFitEstimator::NONE);
        }

        result.sAdaptationScale[ch] = sScale;
        result.oAdaptationScale[ch] = oScale;
        result.adaptationScale[ch] = std::sqrt(std::max(0.0, sScale * oScale));
        result.regressionConfidence[ch] = regressionConfidence;
        result.fitInnovationMean[ch] = innovationMean;
        result.fitInnovationVariance[ch] = innovationVariance;
        result.fitResidualCorrelation[ch] = innovationLag;
        result.fitHeavyTailFraction[ch] = heavyTailFraction;

        const double channelConfidence = std::clamp(static_cast<double>(count) / 2048.0, 0.0, 1.0) *
                std::clamp(0.30 + 0.70 * regressionConfidence, 0.0, 1.0);
        confidenceSum += channelConfidence;
        correlationWeighted += temporalCorrelation * channelConfidence;
        correlationWeight += channelConfidence;
        globalInnovationWeight += channelConfidence;
        globalInnovationMeanWeighted += innovationMean * channelConfidence;
        globalInnovationVarianceWeighted += innovationVariance * channelConfidence;
        globalInnovationLagWeighted += innovationLag * channelConfidence;
        globalHeavyTailWeighted += heavyTailFraction * channelConfidence;
        validChannels++;
    }

    result.normalizedInnovationMean = globalInnovationWeight > 0.0
            ? globalInnovationMeanWeighted / globalInnovationWeight : 0.0;
    result.normalizedInnovationVariance = globalInnovationWeight > 0.0
            ? globalInnovationVarianceWeighted / globalInnovationWeight : 0.0;
    result.normalizedInnovationLagCorrelation = globalInnovationWeight > 0.0
            ? globalInnovationLagWeighted / globalInnovationWeight : 0.0;
    result.heavyTailFraction = globalInnovationWeight > 0.0
            ? globalHeavyTailWeighted / globalInnovationWeight : 0.0;
    result.meanTemporalCorrelation = correlationWeight > 0.0
            ? static_cast<float>(std::clamp(correlationWeighted / correlationWeight, 0.0, 0.85))
            : 0.0f;
    result.persistentPatternMean = result.meanTemporalCorrelation;
    result.independentNoiseFraction = std::clamp(1.0f - result.persistentPatternMean, 0.15f, 1.0f);
    result.observerConfidence = validChannels > 0
            ? std::clamp(
                    (confidenceSum / static_cast<double>(validChannels)) *
                    static_cast<double>(result.alignmentConfidence) *
                    static_cast<double>(result.motionConfidence) *
                    static_cast<double>(result.forwardBackwardConsistency) *
                    result.staticProbabilityP50 * static_cast<double>(request.modelConfidence),
                    0.0, 1.0)
            : 0.0;
    const float temporalBlend = 0.20f + 0.80f * request.temporalAuthority;
    result.supportWeight = std::clamp(
            result.alignmentConfidence * result.motionConfidence * result.forwardBackwardConsistency *
                    static_cast<float>(0.30 + 0.70 * result.staticProbabilityP50) *
                    (0.35f + 0.65f * request.modelConfidence) * temporalBlend *
                    std::sqrt(result.independentNoiseFraction),
            0.02f, 1.0f);
    result.valid = validChannels >= 2 && result.highConfidenceStaticSamples >= 256u &&
            result.observerConfidence >= 0.08 && result.forwardBackwardConsistency >= 0.20f;
}

} // namespace

bool VulkanSpectraTemporalObserverBackend::productionKernelConnected() const noexcept {
#if BNCAM_SPECTRA_TEMPORAL_OBSERVER_SHADER_AVAILABLE
    return !getSpectraTemporalObserverSpirv().empty();
#else
    return false;
#endif
}

bool VulkanSpectraTemporalObserverBackend::pipelineInitialized() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return initialized_;
}

bool VulkanSpectraTemporalObserverBackend::ensureBufferLocked(
        VmaAllocator allocator, std::uint64_t bytes, std::uint32_t hostAccess,
        PersistentBuffer& buffer, bool& reallocated, std::string& failureReason) noexcept {
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocator; (void)bytes; (void)hostAccess; (void)buffer; (void)reallocated;
    failureReason = "VMA_HEADER_NOT_AVAILABLE";
    return false;
#else
    if (allocator == nullptr || bytes == 0u ||
        bytes > static_cast<std::uint64_t>(std::numeric_limits<VkDeviceSize>::max())) {
        failureReason = "INVALID_TEMPORAL_OBSERVER_BUFFER_REQUEST";
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
        failureReason = "vmaCreateBuffer_temporal_observer_failed_" + std::to_string(create);
        return false;
    }
    buffer.mapped = allocationResult.pMappedData;
    buffer.capacityBytes = bytes;
    reallocated = true;
    allocationGeneration_++;
    return true;
#endif
}

void VulkanSpectraTemporalObserverBackend::destroyBuffersLocked() noexcept {
#if BNCAM_VMA_HEADER_AVAILABLE
    if (allocator_ != nullptr) {
        for (PersistentBuffer* buffer : {
                &anchor_, &supportPacked_, &params_, &coarsePartials_, &observerPartials_,
                &staticCells_, &histogram_}) {
            if (buffer->buffer != VK_NULL_HANDLE && buffer->allocation != nullptr) {
                vmaDestroyBuffer(allocator_, buffer->buffer, buffer->allocation);
            }
            *buffer = {};
        }
    }
#endif
    allocator_ = nullptr;
}

void VulkanSpectraTemporalObserverBackend::destroyLocked(VkDevice device) noexcept {
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
}

void VulkanSpectraTemporalObserverBackend::destroy(VkDevice device) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    destroyLocked(device);
}

bool VulkanSpectraTemporalObserverBackend::initializeLocked(
        VkDevice device, VkCommandPool commandPool, std::string& failureReason) noexcept {
    if (initialized_) {
        if (initializedDevice_ == device && initializedCommandPool_ == commandPool) return true;
        failureReason = "TEMPORAL_OBSERVER_BACKEND_RUNTIME_OWNERSHIP_CHANGED";
        return false;
    }
#if !BNCAM_SPECTRA_TEMPORAL_OBSERVER_SHADER_AVAILABLE
    (void)device; (void)commandPool;
    failureReason = "TEMPORAL_OBSERVER_SHADER_NOT_COMPILED";
    return false;
#else
    const auto& spirv = getSpectraTemporalObserverSpirv();
    if (device == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE || spirv.empty()) {
        failureReason = "TEMPORAL_OBSERVER_INITIALIZATION_INPUT_INVALID";
        return false;
    }
    VkDescriptorSetLayoutBinding bindings[7]{};
    for (std::uint32_t i = 0; i < 7u; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1u;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo descriptorInfo{};
    descriptorInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    descriptorInfo.bindingCount = 7u;
    descriptorInfo.pBindings = bindings;
    if (vkCreateDescriptorSetLayout(device, &descriptorInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorSetLayout_temporal_observer_failed";
        destroyLocked(device); return false;
    }
    VkPushConstantRange pushRange{};
    pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushRange.offset = 0u;
    pushRange.size = sizeof(std::uint32_t);
    VkPipelineLayoutCreateInfo layoutInfo{};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1u;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;
    layoutInfo.pushConstantRangeCount = 1u;
    layoutInfo.pPushConstantRanges = &pushRange;
    if (vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        failureReason = "vkCreatePipelineLayout_temporal_observer_failed";
        destroyLocked(device); return false;
    }
    VkShaderModuleCreateInfo shaderInfo{};
    shaderInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    shaderInfo.codeSize = spirv.size() * sizeof(std::uint32_t);
    shaderInfo.pCode = spirv.data();
    if (vkCreateShaderModule(device, &shaderInfo, nullptr, &shaderModule_) != VK_SUCCESS) {
        failureReason = "vkCreateShaderModule_temporal_observer_failed";
        destroyLocked(device); return false;
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
        failureReason = "vkCreateComputePipelines_temporal_observer_failed";
        destroyLocked(device); return false;
    }
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSize.descriptorCount = 7u;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1u;
    poolInfo.poolSizeCount = 1u;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(device, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        failureReason = "vkCreateDescriptorPool_temporal_observer_failed";
        destroyLocked(device); return false;
    }
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1u;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device, &allocInfo, &descriptorSet_) != VK_SUCCESS) {
        failureReason = "vkAllocateDescriptorSets_temporal_observer_failed";
        destroyLocked(device); return false;
    }
    VkCommandBufferAllocateInfo commandInfo{};
    commandInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandInfo.commandPool = commandPool;
    commandInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandInfo.commandBufferCount = 1u;
    if (vkAllocateCommandBuffers(device, &commandInfo, &commandBuffer_) != VK_SUCCESS) {
        failureReason = "vkAllocateCommandBuffers_temporal_observer_failed";
        destroyLocked(device); return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vkCreateFence(device, &fenceInfo, nullptr, &fence_) != VK_SUCCESS) {
        failureReason = "vkCreateFence_temporal_observer_failed";
        destroyLocked(device); return false;
    }
    VkQueryPoolCreateInfo queryInfo{};
    queryInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
    queryInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
    queryInfo.queryCount = 6u;
    if (vkCreateQueryPool(device, &queryInfo, nullptr, &queryPool_) != VK_SUCCESS) queryPool_ = VK_NULL_HANDLE;
    initialized_ = true;
    initializedDevice_ = device;
    initializedCommandPool_ = commandPool;
    return true;
#endif
}

void VulkanSpectraTemporalObserverBackend::updateDescriptorSetLocked(
        VkDevice device,
        VkBuffer anchorBuffer,
        VkBuffer supportBuffer) noexcept {
    const VkBuffer buffers[7] = {
        anchorBuffer, supportBuffer, params_.buffer, coarsePartials_.buffer,
        observerPartials_.buffer, staticCells_.buffer, histogram_.buffer
    };
    VkDescriptorBufferInfo infos[7]{};
    VkWriteDescriptorSet writes[7]{};
    for (std::uint32_t i = 0; i < 7u; ++i) {
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
    vkUpdateDescriptorSets(device, 7u, writes, 0u, nullptr);
    descriptorBindingsInitialized_ = true;
}

SpectraTemporalObserverResult VulkanSpectraTemporalObserverBackend::execute(
        VkPhysicalDevice physicalDevice, VkDevice device, VkQueue computeQueue,
        VkCommandPool commandPool, VulkanAllocatorOwner& allocatorOwner,
        const SpectraTemporalObserverRequest& request) noexcept {
    SpectraTemporalObserverResult result{};
    result.attempted = true;
    result.pipelineAvailable = productionKernelConnected();
    result.alignmentConfidence = std::clamp(request.alignmentConfidence, 0.0f, 1.0f);
    result.forwardBackwardConsistency = std::clamp(request.forwardBackwardConsistency, 0.0f, 1.0f);
    const auto totalStarted = Clock::now();
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)physicalDevice; (void)device; (void)computeQueue; (void)commandPool; (void)allocatorOwner; (void)request;
    result.status = "VMA_HEADER_NOT_AVAILABLE";
    result.failureReason = "TEMPORAL_OBSERVER_VULKAN_REQUIRES_VMA";
    result.totalMs = elapsedMs(totalStarted);
    return result;
#else
    if (!result.pipelineAvailable) {
        result.status = "TEMPORAL_OBSERVER_SHADER_UNAVAILABLE";
        result.failureReason = "SPECTRA_TEMPORAL_OBSERVER_SHADER_NOT_CONNECTED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    const bool residentInputs = request.residentAnchorRaw16Buffer != VK_NULL_HANDLE &&
            request.residentSupportRaw16Buffer != VK_NULL_HANDLE;
    const bool hostInputs = request.anchorData != nullptr && request.supportData != nullptr &&
            request.anchorRowStrideFloats >= request.anchorWidth &&
            request.supportRowStrideU16 >= request.supportWidth;
    if (device == VK_NULL_HANDLE || computeQueue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE ||
        (!residentInputs && !hostInputs) ||
        request.anchorWidth < 16u || request.anchorHeight < 16u ||
        request.supportWidth < 16u || request.supportHeight < 16u || request.whiteLevel <= 1) {
        result.status = "TEMPORAL_OBSERVER_REQUEST_INVALID";
        result.failureReason = "INVALID_TEMPORAL_OBSERVER_GPU_REQUEST";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }

    const int yStart = std::max(4, request.applyDy + 4);
    const int yEnd = std::min(static_cast<int>(request.anchorHeight) - 4,
                              static_cast<int>(request.supportHeight) + request.applyDy - 4);
    const int xStart = std::max(4, request.applyDx + 4);
    const int xEnd = std::min(static_cast<int>(request.anchorWidth) - 4,
                              static_cast<int>(request.supportWidth) + request.applyDx - 4);
    if (xStart >= xEnd || yStart >= yEnd) {
        result.status = "TEMPORAL_OBSERVER_EMPTY_OVERLAP";
        result.failureReason = "ALIGNED_OBSERVER_OVERLAP_EMPTY";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    const std::uint32_t xGroups = ceilDiv(static_cast<std::uint32_t>(xEnd - xStart), 4u);
    const std::uint32_t yGroups = ceilDiv(static_cast<std::uint32_t>(yEnd - yStart), 4u);
    const std::uint64_t blockCount64 = static_cast<std::uint64_t>(xGroups) * yGroups;
    if (blockCount64 == 0u || blockCount64 > std::numeric_limits<std::uint32_t>::max()) {
        result.status = "TEMPORAL_OBSERVER_BLOCK_COUNT_INVALID";
        result.failureReason = "TEMPORAL_OBSERVER_BLOCK_COUNT_OVERFLOW";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    const std::uint32_t blockCount = static_cast<std::uint32_t>(blockCount64);
    const std::uint32_t workGroupCount = ceilDiv(blockCount, kLocalSize);
    const std::uint32_t staticCols = ceilDiv(request.anchorWidth, kStaticCellSize);
    const std::uint32_t staticRows = ceilDiv(request.anchorHeight, kStaticCellSize);
    const std::uint32_t staticCellCount = staticCols * staticRows;

    std::uint64_t anchorPixels = 0u;
    std::uint64_t supportPixels = 0u;
    std::uint64_t anchorU16Bytes = 0u;
    std::uint64_t supportU16Bytes = 0u;
    if (!checkedMultiply(request.anchorWidth, request.anchorHeight, anchorPixels) ||
        !checkedMultiply(request.supportWidth, request.supportHeight, supportPixels) ||
        !checkedMultiply(anchorPixels, sizeof(std::uint16_t), anchorU16Bytes) ||
        !checkedMultiply(supportPixels, sizeof(std::uint16_t), supportU16Bytes)) {
        result.status = "TEMPORAL_OBSERVER_SIZE_OVERFLOW";
        result.failureReason = "TEMPORAL_OBSERVER_INPUT_SIZE_OVERFLOW";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    const std::uint64_t anchorPackedBytes = ((anchorPixels + 1u) / 2u) * sizeof(std::uint32_t);
    const std::uint64_t supportPackedBytes = ((supportPixels + 1u) / 2u) * sizeof(std::uint32_t);
    const std::uint64_t paramsBytes = kParamsWords * sizeof(std::uint32_t);
    const std::uint64_t coarseBytes = static_cast<std::uint64_t>(workGroupCount) * kCoarseStride * sizeof(float);
    const std::uint64_t observerBytes = static_cast<std::uint64_t>(workGroupCount) * kObserverStride * sizeof(float);
    const std::uint64_t staticBytes = static_cast<std::uint64_t>(staticCellCount) * 2u * sizeof(float);
    const std::uint64_t histogramBytes = kHistogramBins * sizeof(std::uint32_t);
    result.inputBytes = anchorU16Bytes + supportU16Bytes;
    result.residentRawInputsUsed = residentInputs;
    result.compactReadbackBytes = coarseBytes + observerBytes + staticBytes + histogramBytes;

    std::lock_guard<std::mutex> lock(mutex_);
    if (!initializeLocked(device, commandPool, result.failureReason)) {
        result.status = "TEMPORAL_OBSERVER_INITIALIZATION_FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    allocator_ = allocatorOwner.handle();
    if (allocator_ == nullptr) {
        result.status = "TEMPORAL_OBSERVER_ALLOCATOR_UNAVAILABLE";
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
    if ((!residentInputs && !ensure(anchorPackedBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT, anchor_)) ||
        (!residentInputs && !ensure(supportPackedBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT, supportPacked_)) ||
        !ensure(paramsBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT, params_) ||
        !ensure(coarseBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT, coarsePartials_) ||
        !ensure(observerBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT, observerPartials_) ||
        !ensure(staticBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT, staticCells_) ||
        !ensure(histogramBytes, VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT, histogram_)) {
        result.status = "TEMPORAL_OBSERVER_BUFFER_ALLOCATION_FAILED";
        result.totalMs = elapsedMs(totalStarted);
        return result;
    }
    result.persistentBufferReallocated = reallocated;
    result.persistentBufferReuseHit = !reallocated;
    result.persistentAllocationGeneration = allocationGeneration_;
    result.persistentResidentBytes = (residentInputs ? 0u : anchor_.capacityBytes + supportPacked_.capacityBytes) +
            params_.capacityBytes + coarsePartials_.capacityBytes + observerPartials_.capacityBytes +
            staticCells_.capacityBytes + histogram_.capacityBytes;

    const auto packingStarted = Clock::now();
    if (!residentInputs) {
        std::memset(anchor_.mapped, 0, static_cast<std::size_t>(anchorPackedBytes));
        auto* anchorPackedU16 = static_cast<std::uint16_t*>(anchor_.mapped);
        for (std::uint32_t y = 0; y < request.anchorHeight; ++y) {
            const float* src = request.anchorData + static_cast<std::size_t>(y) * request.anchorRowStrideFloats;
            std::uint16_t* dst = anchorPackedU16 + static_cast<std::size_t>(y) * request.anchorWidth;
            for (std::uint32_t x = 0; x < request.anchorWidth; ++x) {
                const float value = std::isfinite(src[x]) ? src[x] : 0.0f;
                dst[x] = static_cast<std::uint16_t>(std::clamp(std::lround(value), 0l, 65535l));
            }
        }
        std::memset(supportPacked_.mapped, 0, static_cast<std::size_t>(supportPackedBytes));
        auto* supportPackedU16 = static_cast<std::uint16_t*>(supportPacked_.mapped);
        if (request.supportRowStrideU16 == request.supportWidth) {
            std::memcpy(supportPackedU16, request.supportData, static_cast<std::size_t>(supportU16Bytes));
        } else {
            for (std::uint32_t y = 0; y < request.supportHeight; ++y) {
                std::memcpy(supportPackedU16 + static_cast<std::size_t>(y) * request.supportWidth,
                            request.supportData + static_cast<std::size_t>(y) * request.supportRowStrideU16,
                            static_cast<std::size_t>(request.supportWidth) * sizeof(std::uint16_t));
            }
        }
        result.fullFrameCpuUploadBytes = anchorU16Bytes + supportU16Bytes;
    }
    auto* words = static_cast<std::uint32_t*>(params_.mapped);
    std::memset(words, 0, static_cast<std::size_t>(paramsBytes));
    words[0] = request.anchorWidth; words[1] = request.anchorHeight;
    words[2] = request.supportWidth; words[3] = request.supportHeight;
    words[4] = static_cast<std::uint32_t>(xStart); words[5] = static_cast<std::uint32_t>(xEnd);
    words[6] = static_cast<std::uint32_t>(yStart); words[7] = static_cast<std::uint32_t>(yEnd);
    words[8] = xGroups; words[9] = yGroups; words[10] = static_cast<std::uint32_t>(request.cfaPattern);
    words[11] = staticCols; words[12] = staticRows; words[13] = kStaticCellSize;
    words[14] = static_cast<std::uint32_t>(request.applyDx); words[15] = static_cast<std::uint32_t>(request.applyDy);
    words[16] = floatBits(std::clamp(request.strictness, 0.0f, 1.0f));
    words[17] = floatBits(result.forwardBackwardConsistency);
    words[18] = floatBits(static_cast<float>(request.whiteLevel));
    for (std::uint32_t ch = 0; ch < 4u; ++ch) {
        words[19u + ch] = floatBits(static_cast<float>(std::max(0.0, request.effectiveS[ch])));
        words[23u + ch] = floatBits(static_cast<float>(std::max(0.0, request.effectiveO[ch])));
        words[27u + ch] = floatBits(static_cast<float>(std::clamp(
                request.blackLevels[ch], 0, std::max(0, request.whiteLevel - 1))));
        words[31u + ch] = floatBits(0.0f);
    }
    words[35] = blockCount;
    std::memset(histogram_.mapped, 0, static_cast<std::size_t>(histogramBytes));
    if (!residentInputs) {
        vmaFlushAllocation(allocator_, anchor_.allocation, 0u, anchorPackedBytes);
        vmaFlushAllocation(allocator_, supportPacked_.allocation, 0u, supportPackedBytes);
    }
    vmaFlushAllocation(allocator_, params_.allocation, 0u, paramsBytes);
    vmaFlushAllocation(allocator_, histogram_.allocation, 0u, histogramBytes);
    result.inputPackingMs = elapsedMs(packingStarted);

    const VkBuffer activeAnchorBuffer = residentInputs ? request.residentAnchorRaw16Buffer : anchor_.buffer;
    const VkBuffer activeSupportBuffer = residentInputs ? request.residentSupportRaw16Buffer : supportPacked_.buffer;
    updateDescriptorSetLocked(device, activeAnchorBuffer, activeSupportBuffer);
    result.timestampQueryUsed = queryPool_ != VK_NULL_HANDLE;
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1u;
    submit.pCommandBuffers = &commandBuffer_;

    auto recordInputsToCompute = [&](VkCommandBuffer cmd) {
        if (residentInputs) {
            VkBufferMemoryBarrier residentBarriers[2]{};
            const VkBuffer buffers[2] = {activeAnchorBuffer, activeSupportBuffer};
            const VkDeviceSize sizes[2] = {anchorPackedBytes, supportPackedBytes};
            for (int i = 0; i < 2; ++i) {
                residentBarriers[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
                residentBarriers[i].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
                residentBarriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                residentBarriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                residentBarriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                residentBarriers[i].buffer = buffers[i];
                residentBarriers[i].offset = 0u;
                residentBarriers[i].size = sizes[i];
            }
            vkCmdPipelineBarrier(cmd,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 0u, 0u, nullptr, 2u, residentBarriers, 0u, nullptr);
        }
        VkBufferMemoryBarrier hostBarriers[2]{};
        const VkBuffer hostBuffers[2] = {params_.buffer, histogram_.buffer};
        const VkDeviceSize hostSizes[2] = {paramsBytes, histogramBytes};
        for (int i = 0; i < 2; ++i) {
            hostBarriers[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
            hostBarriers[i].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
            hostBarriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            hostBarriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hostBarriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            hostBarriers[i].buffer = hostBuffers[i];
            hostBarriers[i].offset = 0u;
            hostBarriers[i].size = hostSizes[i];
        }
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             0u, 0u, nullptr, 2u, hostBarriers, 0u, nullptr);
        if (!residentInputs) {
            VkBufferMemoryBarrier packedBarriers[2]{};
            const VkBuffer packedBuffers[2] = {activeAnchorBuffer, activeSupportBuffer};
            const VkDeviceSize packedSizes[2] = {anchorPackedBytes, supportPackedBytes};
            for (int i = 0; i < 2; ++i) {
                packedBarriers[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
                packedBarriers[i].srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
                packedBarriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                packedBarriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                packedBarriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
                packedBarriers[i].buffer = packedBuffers[i];
                packedBarriers[i].offset = 0u;
                packedBarriers[i].size = packedSizes[i];
            }
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                 0u, 0u, nullptr, 2u, packedBarriers, 0u, nullptr);
        }
    };

    // Pass A: coarse per-CFA frame delta.
    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "TEMPORAL_OBSERVER_COMMAND_RECORDING_FAILED";
        result.failureReason = "vkBeginCommandBuffer_coarse";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    recordInputsToCompute(commandBuffer_);
    if (result.timestampQueryUsed) {
        vkCmdResetQueryPool(commandBuffer_, queryPool_, 0u, 6u);
        vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool_, 0u);
    }
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0u, 1u, &descriptorSet_, 0u, nullptr);
    std::uint32_t mode = 0u;
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(mode), &mode);
    vkCmdDispatch(commandBuffer_, workGroupCount, 1u, 1u);
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 1u);
    VkBufferMemoryBarrier coarseRead{};
    coarseRead.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    coarseRead.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    coarseRead.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    coarseRead.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    coarseRead.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    coarseRead.buffer = coarsePartials_.buffer; coarseRead.size = coarseBytes;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0u, 0u, nullptr, 1u, &coarseRead, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "TEMPORAL_OBSERVER_COMMAND_RECORDING_FAILED";
        result.failureReason = "vkEndCommandBuffer_coarse";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    auto syncStart = Clock::now();
    const VkResult coarseSubmit = vkQueueSubmit(computeQueue, 1u, &submit, fence_);
    if (coarseSubmit != VK_SUCCESS) {
        result.status = "GPU_SUBMIT_FAILED";
        result.failureReason = "coarse_submit_" + std::to_string(coarseSubmit);
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    const VkResult coarseWait = vkWaitForFences(device, 1u, &fence_, VK_TRUE, 1'500'000'000ull);
    if (coarseWait != VK_SUCCESS) {
        result.submissionMayRemainInFlight = true;
        VulkanRuntime::instance().markGpuStalled("TemporalObserver_Coarse");
        result.status = "GPU_STALLED";
        result.failureReason = coarseWait == VK_TIMEOUT ? "coarse_gpu_timeout" : "coarse_gpu_wait_unsafe_" + std::to_string(coarseWait);
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.synchronizationMs += elapsedMs(syncStart);
    vmaInvalidateAllocation(allocator_, coarsePartials_.allocation, 0u, coarseBytes);

    const auto coarseReduceStart = Clock::now();
    std::array<double, 4> coarseDiffSum{};
    std::array<double, 4> coarseDiffWeight{};
    const float* coarse = static_cast<const float*>(coarsePartials_.mapped);
    for (std::uint32_t group = 0u; group < workGroupCount; ++group) {
        const float* rec = coarse + static_cast<std::size_t>(group) * kCoarseStride;
        for (std::uint32_t ch = 0u; ch < 4u; ++ch) {
            coarseDiffSum[ch] += rec[ch * 2u];
            coarseDiffWeight[ch] += rec[ch * 2u + 1u];
        }
        result.clippingRejectedSamples += static_cast<std::uint64_t>(std::llround(std::max(0.0f, rec[8])));
        result.textureRejectedSamples += static_cast<std::uint64_t>(std::llround(std::max(0.0f, rec[9])));
    }
    for (std::uint32_t ch = 0u; ch < 4u; ++ch) {
        const float delta = coarseDiffWeight[ch] > 1.0e-12
                ? static_cast<float>(coarseDiffSum[ch] / coarseDiffWeight[ch]) : 0.0f;
        words[31u + ch] = floatBits(delta);
    }
    vmaFlushAllocation(allocator_, params_.allocation, 0u, paramsBytes);
    result.coarseReductionMs = elapsedMs(coarseReduceStart);

    // Pass B+C: normalized-innovation aggregates + static-probability field.
    vkResetFences(device, 1u, &fence_);
    vkResetCommandBuffer(commandBuffer_, 0u);
    if (vkBeginCommandBuffer(commandBuffer_, &begin) != VK_SUCCESS) {
        result.status = "TEMPORAL_OBSERVER_COMMAND_RECORDING_FAILED";
        result.failureReason = "vkBeginCommandBuffer_observer";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    recordInputsToCompute(commandBuffer_);
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool_, 2u);
    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline_);
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0u, 1u, &descriptorSet_, 0u, nullptr);
    mode = 1u;
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(mode), &mode);
    vkCmdDispatch(commandBuffer_, workGroupCount, 1u, 1u);
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 3u);
    mode = 2u;
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 4u);
    vkCmdPushConstants(commandBuffer_, pipelineLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0u, sizeof(mode), &mode);
    vkCmdDispatch(commandBuffer_, staticCellCount, 1u, 1u);
    if (result.timestampQueryUsed) vkCmdWriteTimestamp(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queryPool_, 5u);

    VkBufferMemoryBarrier readbacks[3]{};
    const VkBuffer rb[3] = {observerPartials_.buffer, staticCells_.buffer, histogram_.buffer};
    const VkDeviceSize rbs[3] = {observerBytes, staticBytes, histogramBytes};
    for (int i = 0; i < 3; ++i) {
        readbacks[i].sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        readbacks[i].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        readbacks[i].dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        readbacks[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        readbacks[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        readbacks[i].buffer = rb[i]; readbacks[i].offset = 0u; readbacks[i].size = rbs[i];
    }
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0u, 0u, nullptr, 3u, readbacks, 0u, nullptr);
    if (vkEndCommandBuffer(commandBuffer_) != VK_SUCCESS) {
        result.status = "TEMPORAL_OBSERVER_COMMAND_RECORDING_FAILED";
        result.failureReason = "vkEndCommandBuffer_observer";
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    syncStart = Clock::now();
    const VkResult observerSubmit = vkQueueSubmit(computeQueue, 1u, &submit, fence_);
    if (observerSubmit != VK_SUCCESS) {
        result.status = "GPU_SUBMIT_FAILED";
        result.failureReason = "observer_submit_" + std::to_string(observerSubmit);
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    const VkResult observerWait = vkWaitForFences(device, 1u, &fence_, VK_TRUE, 1'500'000'000ull);
    if (observerWait != VK_SUCCESS) {
        result.submissionMayRemainInFlight = true;
        VulkanRuntime::instance().markGpuStalled("TemporalObserver_Fine");
        result.status = "GPU_STALLED";
        result.failureReason = observerWait == VK_TIMEOUT ? "observer_gpu_timeout" : "observer_gpu_wait_unsafe_" + std::to_string(observerWait);
        result.totalMs = elapsedMs(totalStarted); return result;
    }
    result.synchronizationMs += elapsedMs(syncStart);

    if (result.timestampQueryUsed) {
        std::uint64_t ts[6]{};
        if (vkGetQueryPoolResults(device, queryPool_, 0u, 6u, sizeof(ts), ts, sizeof(std::uint64_t),
                                  VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
            VkPhysicalDeviceProperties props{};
            vkGetPhysicalDeviceProperties(physicalDevice, &props);
            const double toMs = static_cast<double>(props.limits.timestampPeriod) / 1.0e6;
            if (ts[1] >= ts[0]) result.coarseKernelMs = static_cast<float>((ts[1] - ts[0]) * toMs);
            if (ts[3] >= ts[2]) result.observerKernelMs = static_cast<float>((ts[3] - ts[2]) * toMs);
            if (ts[5] >= ts[4]) result.staticFieldKernelMs = static_cast<float>((ts[5] - ts[4]) * toMs);
        } else {
            result.timestampQueryUsed = false;
        }
    }

    const auto readbackStarted = Clock::now();
    vmaInvalidateAllocation(allocator_, observerPartials_.allocation, 0u, observerBytes);
    vmaInvalidateAllocation(allocator_, staticCells_.allocation, 0u, staticBytes);
    vmaInvalidateAllocation(allocator_, histogram_.allocation, 0u, histogramBytes);
    result.readbackMs = elapsedMs(readbackStarted);

    const auto reductionStarted = Clock::now();
    const ReducedObserver reduced = reduceObserverPartials(
            static_cast<const float*>(observerPartials_.mapped), workGroupCount);
    result.staticProbabilityField.imageWidth = static_cast<int>(request.anchorWidth);
    result.staticProbabilityField.imageHeight = static_cast<int>(request.anchorHeight);
    result.staticProbabilityField.cellSize = static_cast<int>(kStaticCellSize);
    result.staticProbabilityField.columns = static_cast<int>(staticCols);
    result.staticProbabilityField.rows = static_cast<int>(staticRows);
    result.staticProbabilityField.values.assign(staticCellCount, 0.0f);
    std::vector<double> staticValues;
    staticValues.reserve(staticCellCount);
    const float* cells = static_cast<const float*>(staticCells_.mapped);
    for (std::uint32_t i = 0u; i < staticCellCount; ++i) {
        const double count = std::max(0.0, static_cast<double>(cells[i * 2u + 1u]));
        const double value = count > 0.0 ? static_cast<double>(cells[i * 2u]) / count : 0.0;
        result.staticProbabilityField.values[i] = static_cast<float>(std::clamp(value, 0.0, 1.0));
        if (count > 0.0) staticValues.push_back(value);
    }
    result.staticProbabilityMean = staticValues.empty() ? 0.0 :
            std::accumulate(staticValues.begin(), staticValues.end(), 0.0) /
                    static_cast<double>(staticValues.size());
    result.staticProbabilityP10 = spectra_temporal::percentile(staticValues, 0.10);
    result.staticProbabilityP50 = spectra_temporal::percentile(staticValues, 0.50);
    result.staticProbabilityP90 = spectra_temporal::percentile(staticValues, 0.90);
    result.normalizedInnovationP90 = histogramP90(static_cast<const std::uint32_t*>(histogram_.mapped));
    finalizeObservation(request, reduced, result);
    result.compactReductionAndFitMs = elapsedMs(reductionStarted);

    result.cpuFullFrameObserverAvoided = true;
    result.success = true;
    result.status = result.valid ? "SPECTRA_TEMPORAL_OBSERVER_GPU_PRIMARY_VALID" :
                                   "SPECTRA_TEMPORAL_OBSERVER_GPU_PRIMARY_REJECTED_BY_MODEL_GATES";
    result.totalMs = elapsedMs(totalStarted);
    return result;
#endif
}

} // namespace bncam::vulkan
