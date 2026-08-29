#include "DngMerger.h"
#include "RawCfaLevelMapping.h"
#include "RawSupportRejectionTelemetry.h"
#include "SpectraTemporalFusion.h"
#include "vulkan/VulkanRuntime.h"

#include <android/log.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cinttypes>
#include <cstdint>
#include <cstring>
#include <exception>
#include <iomanip>
#include <limits>
#include <new>
#include <mutex>
#include <numeric>
#include <unordered_set>
#include <unordered_map>
#include <sstream>
#include <string>
#include <vector>
#include <opencv2/opencv.hpp>

#define DNG_LOG_TAG "BnCam_DngMerger"
#define DNG_LOGI(...) __android_log_print(ANDROID_LOG_INFO, DNG_LOG_TAG, __VA_ARGS__)
#define DNG_LOGW(...) __android_log_print(ANDROID_LOG_WARN, DNG_LOG_TAG, __VA_ARGS__)
#define DNG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, DNG_LOG_TAG, __VA_ARGS__)

namespace {

using DngClock = std::chrono::steady_clock;
using DngTimePoint = std::chrono::time_point<DngClock>;

std::mutex gNativeRaw16AllocationMutex;
std::unordered_set<void*> gNativeRaw16Allocations;
// Phase 9: a multi-frame Vulkan result already owns one contiguous std::vector<uint16_t>.
// Transfer that storage directly to the JNI owner instead of allocating/copying a second full RAW16.
std::unordered_map<void*, std::vector<std::uint16_t>*> gNativeRaw16VectorOwners;
// Phase 13: host RAW16 publication allocation -> opaque resident RAW producer generation.
// The generation may belong to the single-frame canonicalizer or the Phase-9 multi-frame backend.
// A zero/missing entry means the publication buffer has no valid GPU-resident producer.
std::unordered_map<void*, std::uint64_t> gNativeRaw16ResidentGenerations;

bool trackNativeRaw16Allocation(void* address, std::uint64_t residentGeneration = 0u) {
    if (address == nullptr) return false;
    std::lock_guard<std::mutex> lock(gNativeRaw16AllocationMutex);
    const bool inserted = gNativeRaw16Allocations.insert(address).second;
    if (inserted && residentGeneration != 0u) {
        gNativeRaw16ResidentGenerations[address] = residentGeneration;
    }
    return inserted;
}

bool trackNativeRaw16VectorAllocation(
        std::vector<std::uint16_t>* owner, std::uint64_t residentGeneration = 0u) {
    if (owner == nullptr || owner->empty() || owner->data() == nullptr) return false;
    void* address = owner->data();
    std::lock_guard<std::mutex> lock(gNativeRaw16AllocationMutex);
    const bool inserted = gNativeRaw16Allocations.insert(address).second;
    if (!inserted) return false;
    gNativeRaw16VectorOwners[address] = owner;
    if (residentGeneration != 0u) {
        gNativeRaw16ResidentGenerations[address] = residentGeneration;
    }
    return true;
}

bool releaseTrackedNativeRaw16Allocation(void* address) {
    if (address == nullptr) return false;
    std::vector<std::uint16_t>* vectorOwner = nullptr;
    {
        std::lock_guard<std::mutex> lock(gNativeRaw16AllocationMutex);
        const auto found = gNativeRaw16Allocations.find(address);
        if (found == gNativeRaw16Allocations.end()) return false;
        gNativeRaw16Allocations.erase(found);
        gNativeRaw16ResidentGenerations.erase(address);
        const auto vectorFound = gNativeRaw16VectorOwners.find(address);
        if (vectorFound != gNativeRaw16VectorOwners.end()) {
            vectorOwner = vectorFound->second;
            gNativeRaw16VectorOwners.erase(vectorFound);
        }
    }
    if (vectorOwner != nullptr) {
        delete vectorOwner;
    } else {
        delete[] static_cast<uint8_t*>(address);
    }
    return true;
}

size_t trackedNativeRaw16AllocationCount() {
    std::lock_guard<std::mutex> lock(gNativeRaw16AllocationMutex);
    return gNativeRaw16Allocations.size();
}

double elapsedDngMs(DngTimePoint start, DngTimePoint end = DngClock::now()) {
    return std::chrono::duration<double, std::milli>(end - start).count();
}

struct LockedRawBuffer {
    uint8_t *data = nullptr;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t rowStrideBytes = 0;
    uint32_t pixelStrideBytes = 0;
    bool locked = false;
    bool lockedWithPlanes = false;
};

struct AlignmentDecision {
    bool accepted = false;
    int applyDx = 0;
    int applyDy = 0;
    double phaseResponse = 0.0;
    double estimatedShiftX = 0.0;
    double estimatedShiftY = 0.0;
    float forwardBackwardConsistency = 0.0f;
    std::string rejectReason = "none";
};


struct SpectraNoiseModel {
    bool enabled = false;
    bool adaptiveCalibrationEnabled = false;
    std::array<double, 4> effectiveS{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> effectiveO{0.0, 0.0, 0.0, 0.0};
    float confidence = 0.0f;
    float noisePressure = 0.0f;
    float temporalAuthority = 0.0f;
    double adaptationLowerBound = 0.92;
    double adaptationUpperBound = 1.08;
};

struct SpectraSupportObservation {
    bool valid = false;
    std::array<double, 4> observedVariance{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> predictedVariance{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> adaptationScale{1.0, 1.0, 1.0, 1.0};
    std::array<double, 4> sAdaptationScale{1.0, 1.0, 1.0, 1.0};
    std::array<double, 4> oAdaptationScale{1.0, 1.0, 1.0, 1.0};
    std::array<double, 4> regressionConfidence{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> temporalCorrelation{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> persistentPatternFraction{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> signalSpan{0.0, 0.0, 0.0, 0.0};
    std::array<int, 4> populatedSignalBins{0, 0, 0, 0};
    std::array<uint64_t, 4> samples{0, 0, 0, 0};
    std::array<int, 4> fitEstimator{0, 0, 0, 0};
    std::array<double, 4> fitPhysicalScore{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> fitInnovationMean{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> fitInnovationVariance{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> fitResidualCorrelation{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> fitHeavyTailFraction{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> fitStabilityConfidence{0.0, 0.0, 0.0, 0.0};
    spectra_temporal::StaticProbabilityField staticProbabilityField{};
    double observerConfidence = 0.0;
    float alignmentConfidence = 0.0f;
    float motionConfidence = 0.0f;
    float forwardBackwardConsistency = 0.0f;
    float repeatedSupportConfidence = 0.0f;
    float meanFitStabilityConfidence = 0.0f;
    float meanTemporalCorrelation = 0.0f;
    float persistentPatternMean = 0.0f;
    float independentNoiseFraction = 1.0f;
    float supportWeight = 0.25f;
    double staticProbabilityMean = 0.0;
    double staticProbabilityP10 = 0.0;
    double staticProbabilityP50 = 0.0;
    double staticProbabilityP90 = 0.0;
    double normalizedInnovationMean = 0.0;
    double normalizedInnovationVariance = 0.0;
    double normalizedInnovationP90 = 0.0;
    double normalizedInnovationLagCorrelation = 0.0;
    double heavyTailFraction = 0.0;
    uint64_t highConfidenceStaticSamples = 0;
    uint64_t clippingRejectedSamples = 0;
    uint64_t textureRejectedSamples = 0;
    uint64_t localMotionRejectedSamples = 0;

    // Milestone 8H-D temporal observer GPU-primary telemetry. A GPU execution
    // that completes but is rejected by model gates is still authoritative and
    // must not trigger a duplicate full-frame CPU observer pass.
    bool vulkanAttempted = false;
    bool vulkanExecutionSucceeded = false;
    bool vulkanUsedForObserver = false;
    bool vulkanCpuFallbackUsed = false;
    bool vulkanPersistentReuseHit = false;
    bool vulkanPersistentReallocated = false;
    double vulkanInputPackingMs = 0.0;
    double vulkanCoarseKernelMs = 0.0;
    double vulkanCoarseReductionMs = 0.0;
    double vulkanObserverKernelMs = 0.0;
    double vulkanStaticFieldKernelMs = 0.0;
    double vulkanCompactReductionAndFitMs = 0.0;
    double vulkanSynchronizationMs = 0.0;
    double vulkanReadbackMs = 0.0;
    double vulkanTotalMs = 0.0;
    uint64_t vulkanInputBytes = 0;
    uint64_t vulkanCompactReadbackBytes = 0;
    uint64_t vulkanPersistentResidentBytes = 0;
    std::string vulkanStatus = "NOT_ATTEMPTED";
    std::string vulkanFailureReason = "none";
};


struct SpectraStaticConsensus {
    spectra_temporal::StaticProbabilityField field{};
    std::vector<double> sums;
    std::vector<uint32_t> counts;
    int acceptedPairs = 0;

    float compareAndUpdate(SpectraSupportObservation& observation) {
        const auto& current = observation.staticProbabilityField;
        if (!current.valid()) {
            observation.repeatedSupportConfidence = 0.0f;
            return 0.0f;
        }
        if (!field.valid() || field.columns != current.columns || field.rows != current.rows ||
            field.cellSize != current.cellSize) {
            field = current;
            sums.assign(current.values.begin(), current.values.end());
            counts.assign(current.values.size(), 1u);
            acceptedPairs = 1;
            observation.repeatedSupportConfidence = 0.50f;
            return observation.repeatedSupportConfidence;
        }

        double agreementSum = 0.0;
        double agreementWeight = 0.0;
        for (size_t i = 0; i < current.values.size(); ++i) {
            const double previous = counts[i] > 0u
                    ? sums[i] / static_cast<double>(counts[i])
                    : 0.0;
            const double now = std::clamp(static_cast<double>(current.values[i]), 0.0, 1.0);
            const double support = std::max(0.05, std::min(previous, now));
            agreementSum += support * (1.0 - std::abs(previous - now));
            agreementWeight += support;
            sums[i] += now;
            counts[i] += 1u;
            field.values[i] = static_cast<float>(std::clamp(
                    sums[i] / static_cast<double>(counts[i]),
                    0.0,
                    1.0
            ));
        }
        acceptedPairs++;
        const double agreement = agreementWeight > 1.0e-12
                ? agreementSum / agreementWeight
                : 0.0;
        const double pairSupport = spectra_temporal::smoothstep01(
                static_cast<double>(acceptedPairs) / 3.0
        );
        observation.repeatedSupportConfidence = static_cast<float>(std::clamp(
                (0.35 + 0.65 * agreement) * (0.55 + 0.45 * pairSupport),
                0.0,
                1.0
        ));
        return observation.repeatedSupportConfidence;
    }
};
int spectraCfaChannel(int cfaPattern, int x, int y) {
    return bncam::raw::canonicalPlaneAtMosaicSite(cfaPattern, x, y);
}

inline int spectraMosaicPhase(int x, int y) {
    // RawDomainContract payload black levels retain Camera2 mosaic order:
    // top-left, top-right, bottom-left, bottom-right. This is deliberately
    // independent from the canonical S/O order R, G1, G2, B.
    return ((y & 1) << 1) | (x & 1);
}

inline double spectraPixelBlack(
        int x,
        int y,
        int whiteLevel,
        const std::array<int, 4>& blackLevels
) {
    const int phase = spectraMosaicPhase(x, y);
    return static_cast<double>(std::clamp(
            blackLevels[static_cast<size_t>(phase)],
            0,
            std::max(0, whiteLevel - 1)
    ));
}

inline double spectraNormalizedSignal(
        double rawCode,
        int x,
        int y,
        int whiteLevel,
        const std::array<int, 4>& blackLevels
) {
    const double black = spectraPixelBlack(x, y, whiteLevel, blackLevels);
    const double range = std::max(1.0, static_cast<double>(whiteLevel) - black);
    return std::clamp((rawCode - black) / range, 0.0, 1.0);
}

inline double spectraNormalizedDifference(
        double rawDelta,
        int x,
        int y,
        int whiteLevel,
        const std::array<int, 4>& blackLevels
) {
    const double black = spectraPixelBlack(x, y, whiteLevel, blackLevels);
    const double range = std::max(1.0, static_cast<double>(whiteLevel) - black);
    return rawDelta / range;
}

inline double spectraReferenceVariance(const SpectraNoiseModel& model, int channel) {
    const int safeChannel = std::clamp(channel, 0, 3);
    return std::max(
            1.0e-12,
            model.effectiveS[static_cast<size_t>(safeChannel)] * 0.18 +
                    model.effectiveO[static_cast<size_t>(safeChannel)]
    );
}


/**
 * Capture-local stability check for the dual S/O fit. A fit may be individually plausible but
 * still unsafe when its bounded S/O scales oscillate between support pairs. This consensus never
 * persists across captures; it only reduces authority when repeated pairs disagree.
 */
struct SpectraFitConsensus {
    std::array<double, 4> weightedS{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> weightedO{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> weights{0.0, 0.0, 0.0, 0.0};

    float compareAndUpdate(SpectraSupportObservation& observation) {
        double stabilitySum = 0.0;
        int stableChannels = 0;
        for (int ch = 0; ch < 4; ++ch) {
            if (observation.samples[ch] < 64u || observation.regressionConfidence[ch] <= 0.0) {
                observation.fitStabilityConfidence[ch] = 0.0;
                continue;
            }
            const double currentS = std::clamp(observation.sAdaptationScale[ch], 0.75, 1.25);
            const double currentO = std::clamp(observation.oAdaptationScale[ch], 0.75, 1.25);
            double stability = 0.50;
            if (weights[ch] > 1.0e-12) {
                const double previousS = weightedS[ch] / weights[ch];
                const double previousO = weightedO[ch] / weights[ch];
                stability = spectra_temporal::fitStability(
                        currentS,
                        currentO,
                        previousS,
                        previousO
                );
            }
            stability = std::clamp(stability, 0.0, 1.0);
            observation.fitStabilityConfidence[ch] = stability;
            const double stabilityAuthority = 0.50 + 0.50 * stability;
            observation.regressionConfidence[ch] *= stabilityAuthority;
            observation.fitPhysicalScore[ch] *= stabilityAuthority;

            const double updateWeight = std::max(
                    0.05,
                    observation.regressionConfidence[ch]
            ) * std::sqrt(static_cast<double>(observation.samples[ch]));
            weightedS[ch] += updateWeight * currentS;
            weightedO[ch] += updateWeight * currentO;
            weights[ch] += updateWeight;
            stabilitySum += stability;
            stableChannels++;
        }
        observation.meanFitStabilityConfidence = stableChannels > 0
                ? static_cast<float>(std::clamp(
                        stabilitySum / static_cast<double>(stableChannels), 0.0, 1.0
                ))
                : 0.0f;
        return observation.meanFitStabilityConfidence;
    }
};

bool validSpectraNoiseModel(const SpectraNoiseModel& model) {
    if (!model.enabled || model.confidence <= 0.0f) return false;
    for (int ch = 0; ch < 4; ++ch) {
        if (!std::isfinite(model.effectiveS[ch]) || !std::isfinite(model.effectiveO[ch]) ||
            model.effectiveS[ch] <= 0.0 || model.effectiveO[ch] < 0.0) return false;
    }
    return true;
}

struct Raw16SampleStats {
    uint16_t minValue = 0;
    uint16_t p01 = 0;
    uint16_t p50 = 0;
    uint16_t p95 = 0;
    uint16_t p99 = 0;
    uint16_t maxValue = 0;
};

struct Raw16FullStats {
    uint16_t minValue = 0;
    uint16_t maxValue = 0;
    uint64_t saturatedCount = 0;
    double saturatedPct = 0.0;
};

Raw16SampleStats computeRaw16SampleStats(const cv::Mat& bayer16) {
    Raw16SampleStats stats{};
    if (bayer16.empty() || bayer16.type() != CV_16UC1) return stats;

    const size_t total = static_cast<size_t>(bayer16.rows) * static_cast<size_t>(bayer16.cols);
    const size_t step = std::max<size_t>(1u, total / 20000u);
    std::vector<uint16_t> samples;
    samples.reserve(std::min<size_t>(20000u, total));

    // This is intentionally a compact statistic: jump directly to the sampled indices instead
    // of traversing every RAW pixel just to keep ~20k values.
    for (size_t linear = 0; linear < total; linear += step) {
        const size_t y = linear / static_cast<size_t>(bayer16.cols);
        const size_t x = linear % static_cast<size_t>(bayer16.cols);
        samples.push_back(bayer16.ptr<uint16_t>(static_cast<int>(y))[x]);
    }
    if (samples.empty()) return stats;

    std::sort(samples.begin(), samples.end());
    auto atPct = [&](double pct) -> uint16_t {
        const size_t index = std::min(samples.size() - 1u, static_cast<size_t>(std::round((samples.size() - 1u) * pct)));
        return samples[index];
    };
    stats.minValue = samples.front();
    stats.p01 = atPct(0.01);
    stats.p50 = atPct(0.50);
    stats.p95 = atPct(0.95);
    stats.p99 = atPct(0.99);
    stats.maxValue = samples.back();
    return stats;
}

Raw16FullStats computeRaw16FullStats(const cv::Mat& bayer16, int saturationLevel) {
    Raw16FullStats stats{};
    if (bayer16.empty() || bayer16.type() != CV_16UC1) return stats;

    double minVal = 0.0;
    double maxVal = 0.0;
    cv::minMaxLoc(bayer16, &minVal, &maxVal);
    stats.minValue = static_cast<uint16_t>(std::clamp<int>(static_cast<int>(std::round(minVal)), 0, 65535));
    stats.maxValue = static_cast<uint16_t>(std::clamp<int>(static_cast<int>(std::round(maxVal)), 0, 65535));

    const uint16_t saturatedThreshold = static_cast<uint16_t>(std::clamp(saturationLevel, 1, 65535));
    uint64_t saturated = 0;
    uint64_t total = 0;
    for (int y = 0; y < bayer16.rows; ++y) {
        const uint16_t* row = bayer16.ptr<uint16_t>(y);
        for (int x = 0; x < bayer16.cols; ++x) {
            if (row[x] >= saturatedThreshold) saturated++;
            total++;
        }
    }
    stats.saturatedCount = saturated;
    stats.saturatedPct = total > 0 ? (static_cast<double>(saturated) * 100.0 / static_cast<double>(total)) : 0.0;
    return stats;
}

std::array<int, 4> normalizedBlackLevels(const std::vector<int32_t>& blackLevels, int payloadWhite) {
    const int safeWhite = std::clamp(payloadWhite, 1, 65535);
    std::array<int, 4> out{0, 0, 0, 0};
    for (int i = 0; i < 4; ++i) {
        const int v = i < static_cast<int>(blackLevels.size()) ? static_cast<int>(blackLevels[static_cast<size_t>(i)]) : 0;
        out[static_cast<size_t>(i)] = std::clamp(v, 0, safeWhite > 1 ? safeWhite - 1 : 0);
    }
    return out;
}

std::string blackLevelsToString(const std::array<int, 4>& values) {
    std::ostringstream oss;
    oss << values[0] << "," << values[1] << "," << values[2] << "," << values[3];
    return oss.str();
}

const char* boolString(bool value) noexcept {
    return value ? "true" : "false";
}

struct SampleDomainTransform {
    int nativeWhite = 1;
    int payloadWhite = 1;
    std::array<int, 4> nativeBlack{0, 0, 0, 0};
    std::array<int, 4> payloadBlack{0, 0, 0, 0};
    bool blackAnchored = false;
    bool identity = true;
    float nominalScale = 1.0f;
    std::string contract = "identity";
};

SampleDomainTransform makeSampleDomainTransform(
        int nativeWhite,
        int payloadWhite,
        const std::vector<int32_t>& payloadBlackLevels,
        const std::string& contract
) {
    SampleDomainTransform transform{};
    transform.nativeWhite = std::clamp(nativeWhite, 1, 65535);
    transform.payloadWhite = std::clamp(payloadWhite, 1, 65535);
    transform.payloadBlack = normalizedBlackLevels(payloadBlackLevels, transform.payloadWhite);
    transform.nominalScale = static_cast<float>(transform.payloadWhite) / static_cast<float>(std::max(1, transform.nativeWhite));
    transform.contract = contract;

    bool allBlackZero = true;
    for (int i = 0; i < 4; ++i) {
        const float nativeBlackF = static_cast<float>(transform.payloadBlack[static_cast<size_t>(i)])
                * static_cast<float>(transform.nativeWhite)
                / static_cast<float>(std::max(1, transform.payloadWhite));
        transform.nativeBlack[static_cast<size_t>(i)] = std::clamp(
                static_cast<int>(std::round(nativeBlackF)),
                0,
                transform.nativeWhite > 1 ? transform.nativeWhite - 1 : 0
        );
        allBlackZero = allBlackZero && transform.payloadBlack[static_cast<size_t>(i)] == 0;
    }

    transform.blackAnchored = !allBlackZero;
    transform.identity = transform.nativeWhite == transform.payloadWhite;
    for (int i = 0; i < 4; ++i) {
        transform.identity = transform.identity && transform.nativeBlack[static_cast<size_t>(i)] == transform.payloadBlack[static_cast<size_t>(i)];
    }
    return transform;
}

inline int blackIndexForPixel(int x, int y) {
    return ((y & 1) << 1) | (x & 1);
}

inline uint16_t mapNativeSampleToPayload(int value, int x, int y, const SampleDomainTransform& transform) {
    const int idx = blackIndexForPixel(x, y);
    const int nativeBlack = transform.nativeBlack[static_cast<size_t>(idx)];
    const int payloadBlack = transform.payloadBlack[static_cast<size_t>(idx)];

    if (transform.identity) {
        return static_cast<uint16_t>(std::clamp(value, 0, transform.payloadWhite));
    }

    const int nativeRange = std::max(1, transform.nativeWhite - nativeBlack);
    const int payloadRange = std::max(1, transform.payloadWhite - payloadBlack);
    const float normalized = static_cast<float>(value - nativeBlack) / static_cast<float>(nativeRange);
    const int mapped = static_cast<int>(std::round(static_cast<float>(payloadBlack) + normalized * static_cast<float>(payloadRange)));
    return static_cast<uint16_t>(std::clamp(mapped, 0, transform.payloadWhite));
}

void applySampleDomainTransformInPlace(cv::Mat& bayer16, const SampleDomainTransform& transform) {
    if (bayer16.empty() || bayer16.type() != CV_16UC1 || transform.identity) return;
    cv::parallel_for_(cv::Range(0, bayer16.rows), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            uint16_t* row = bayer16.ptr<uint16_t>(y);
            for (int x = 0; x < bayer16.cols; ++x) {
                row[x] = mapNativeSampleToPayload(static_cast<int>(row[x]), x, y, transform);
            }
        }
    });
}

int inferRawSensorNativeWhite(const Raw16SampleStats& stats, int payloadWhite, std::string& reason) {
    reason = "not_needed";
    const int safeWhite = std::clamp(payloadWhite, 1, 65535);
    if (safeWhite < 65535 || stats.p99 == 0 || stats.maxValue == 0) return safeWhite;

    // RAW_SENSOR should normally already be in the metadata white-level domain. This inference is
    // only for HALs that expose RAW_SENSOR in a 16-bit container while right-justifying lower-bit
    // samples. It is deliberately based on the actual payload ceiling, not on exposure lifting.
    struct Candidate { int white; int softMax; };
    const Candidate candidates[] = {
            {1023, 2048},
            {4095, 8192},
            {16383, 24576}
    };
    for (const Candidate& c : candidates) {
        if (stats.p99 <= c.white && stats.maxValue <= c.softMax) {
            reason = "RAW_SENSOR_16BIT_CONTAINER_WITH_RIGHT_JUSTIFIED_" + std::to_string(c.white + 1) + "_LEVEL_SAMPLES";
            return c.white;
        }
    }
    return safeWhite;
}

jbyteArray createJavaByteArray(JNIEnv *env, const std::vector<uint8_t>& cppBuffer) {
    if (cppBuffer.empty()) return nullptr;
    auto size = static_cast<jsize>(cppBuffer.size());
    jbyteArray javaArray = env->NewByteArray(size);
    if (javaArray == nullptr) return nullptr;
    env->SetByteArrayRegion(javaArray, 0, size, reinterpret_cast<const jbyte*>(cppBuffer.data()));
    return javaArray;
}

uint32_t packedRaw10RowBytes(uint32_t widthPixels) {
    return ((widthPixels + 3u) / 4u) * 5u;
}

int snapToEvenInt(double value) {
    return static_cast<int>(std::round(value / 2.0) * 2.0);
}

float clampStrictness(float value) {
    if (!std::isfinite(value)) return 0.8f;
    return std::clamp(value, 0.0f, 1.0f);
}

std::string formatDouble(double value, int precision) {
    std::ostringstream out;
    out << std::fixed << std::setprecision(std::max(0, precision)) << value;
    return out.str();
}

uint16_t clampRawValue(int value, int whiteLevel) {
    const int safeWhite = std::clamp(whiteLevel, 1, 65535);
    return static_cast<uint16_t>(std::clamp(value, 0, safeWhite));
}

bool lockRaw10Buffer(AHardwareBuffer *buffer, LockedRawBuffer& out) {
    if (buffer == nullptr) return false;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    out.width = desc.width;
    out.height = desc.height;
    out.pixelStrideBytes = 0;

    if (out.width == 0 || out.height == 0) return false;

    AHardwareBuffer_Planes planes{};
    const int planeStatus = AHardwareBuffer_lockPlanes(
            buffer,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
            -1,
            nullptr,
            &planes
    );

    if (planeStatus == 0 && planes.planeCount >= 1 && planes.planes[0].data != nullptr) {
        out.data = static_cast<uint8_t*>(planes.planes[0].data);
        out.rowStrideBytes = static_cast<uint32_t>(planes.planes[0].rowStride);
        out.locked = true;
        out.lockedWithPlanes = true;
        if (out.rowStrideBytes == 0) {
            out.rowStrideBytes = packedRaw10RowBytes(std::max(desc.width, desc.stride));
        }
        return true;
    }

    void *base = nullptr;
    const int lockStatus = AHardwareBuffer_lock(
            buffer,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
            -1,
            nullptr,
            &base
    );
    if (lockStatus != 0 || base == nullptr) return false;

    out.data = static_cast<uint8_t*>(base);
    out.rowStrideBytes = packedRaw10RowBytes(std::max(desc.width, desc.stride));
    out.locked = true;
    out.lockedWithPlanes = false;
    return true;
}

bool lockRawSensorBuffer(AHardwareBuffer *buffer, LockedRawBuffer& out) {
    if (buffer == nullptr) return false;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(buffer, &desc);
    out.width = desc.width;
    out.height = desc.height;

    if (out.width == 0 || out.height == 0) return false;

    AHardwareBuffer_Planes planes{};
    const int planeStatus = AHardwareBuffer_lockPlanes(
            buffer,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
            -1,
            nullptr,
            &planes
    );

    if (planeStatus == 0 && planes.planeCount >= 1 && planes.planes[0].data != nullptr) {
        out.data = static_cast<uint8_t*>(planes.planes[0].data);
        out.rowStrideBytes = static_cast<uint32_t>(planes.planes[0].rowStride);
        out.pixelStrideBytes = static_cast<uint32_t>(planes.planes[0].pixelStride == 0 ? 2 : planes.planes[0].pixelStride);
        out.locked = true;
        out.lockedWithPlanes = true;
        if (out.rowStrideBytes == 0) {
            out.rowStrideBytes = std::max(desc.width, desc.stride) * out.pixelStrideBytes;
        }
        return true;
    }

    void *base = nullptr;
    const int lockStatus = AHardwareBuffer_lock(
            buffer,
            AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
            -1,
            nullptr,
            &base
    );
    if (lockStatus != 0 || base == nullptr) return false;

    out.data = static_cast<uint8_t*>(base);
    out.pixelStrideBytes = 2;
    out.rowStrideBytes = std::max(desc.width, desc.stride) * out.pixelStrideBytes;
    out.locked = true;
    out.lockedWithPlanes = false;
    return true;
}

void unlockRawBuffer(AHardwareBuffer *buffer, const LockedRawBuffer& locked) {
    if (buffer != nullptr && locked.locked) {
        AHardwareBuffer_unlock(buffer, nullptr);
    }
}

bool unpackRaw10ToSensorRaw16(
        const LockedRawBuffer& locked,
        const SampleDomainTransform& transform,
        int cropLeft,
        int cropTop,
        int cropWidth,
        int cropHeight,
        cv::Mat& outBayer16
) {
    if (locked.data == nullptr || locked.width == 0 || locked.height == 0 || locked.rowStrideBytes == 0) return false;
    const uint32_t minimumRowBytes = packedRaw10RowBytes(locked.width);
    if (locked.rowStrideBytes < minimumRowBytes) return false;
    if (cropLeft < 0 || cropTop < 0 || cropWidth <= 0 || cropHeight <= 0 ||
        cropLeft + cropWidth > static_cast<int>(locked.width) ||
        cropTop + cropHeight > static_cast<int>(locked.height)) return false;

    outBayer16 = cv::Mat(cropHeight, cropWidth, CV_16UC1);
    const bool isIdentity = transform.identity;
    const bool blockAligned = (cropLeft % 4) == 0;

    cv::parallel_for_(cv::Range(0, cropHeight), [&](const cv::Range& range) {
        for (int localY = range.start; localY < range.end; ++localY) {
            const int sourceY = cropTop + localY;
            const uint8_t *row = locked.data + static_cast<size_t>(sourceY) * locked.rowStrideBytes;
            auto *dst = outBayer16.ptr<uint16_t>(localY);

            if (isIdentity && blockAligned) {
                const uint32_t blocks = static_cast<uint32_t>(cropWidth) / 4u;
                const uint32_t remainder = static_cast<uint32_t>(cropWidth) % 4u;
                uint32_t inIdx = static_cast<uint32_t>(cropLeft / 4) * 5u;
                uint32_t outIdx = 0u;
                for (uint32_t b = 0; b < blocks; ++b, inIdx += 5u, outIdx += 4u) {
                    if (inIdx + 4u >= locked.rowStrideBytes) return;
                    const uint32_t b4 = row[inIdx + 4u];
                    dst[outIdx]     = static_cast<uint16_t>((row[inIdx] << 2) | (b4 & 0x03));
                    dst[outIdx + 1] = static_cast<uint16_t>((row[inIdx + 1u] << 2) | ((b4 >> 2) & 0x03));
                    dst[outIdx + 2] = static_cast<uint16_t>((row[inIdx + 2u] << 2) | ((b4 >> 4) & 0x03));
                    dst[outIdx + 3] = static_cast<uint16_t>((row[inIdx + 3u] << 2) | ((b4 >> 6) & 0x03));
                }
                if (remainder > 0u) {
                    if (inIdx + 4u >= locked.rowStrideBytes) return;
                    const uint32_t b4 = row[inIdx + 4u];
                    if (remainder >= 1u) dst[outIdx] = static_cast<uint16_t>((row[inIdx] << 2) | (b4 & 0x03));
                    if (remainder >= 2u) dst[outIdx + 1u] = static_cast<uint16_t>((row[inIdx + 1u] << 2) | ((b4 >> 2) & 0x03));
                    if (remainder >= 3u) dst[outIdx + 2u] = static_cast<uint16_t>((row[inIdx + 2u] << 2) | ((b4 >> 4) & 0x03));
                }
            } else {
                for (int localX = 0; localX < cropWidth; ++localX) {
                    const uint32_t sourceX = static_cast<uint32_t>(cropLeft + localX);
                    const uint32_t inIdx = (sourceX / 4u) * 5u;
                    if (inIdx + 4u >= locked.rowStrideBytes) break;
                    const uint32_t lane = sourceX & 3u;
                    const uint32_t b4 = row[inIdx + 4u];
                    const int raw = (row[inIdx + lane] << 2) | ((b4 >> (lane * 2u)) & 0x03);
                    dst[localX] = isIdentity
                            ? static_cast<uint16_t>(raw)
                            : mapNativeSampleToPayload(raw, localX, localY, transform);
                }
            }
        }
    });

    return true;
}

uint16_t readLittleEndianRaw16(const uint8_t *row, uint32_t byteOffset) {
    const uint16_t lo = static_cast<uint16_t>(row[byteOffset]);
    const uint16_t hi = static_cast<uint16_t>(row[byteOffset + 1u]);
    return static_cast<uint16_t>(lo | (hi << 8));
}

bool unpackRawSensorToSensorRaw16(
        const LockedRawBuffer& locked,
        const SampleDomainTransform& transform,
        int cropLeft,
        int cropTop,
        int cropWidth,
        int cropHeight,
        cv::Mat& outBayer16
) {
    if (locked.data == nullptr || locked.width == 0 || locked.height == 0 || locked.rowStrideBytes == 0) return false;
    if (locked.pixelStrideBytes < 2) return false;
    if (cropLeft < 0 || cropTop < 0 || cropWidth <= 0 || cropHeight <= 0 ||
        cropLeft + cropWidth > static_cast<int>(locked.width) ||
        cropTop + cropHeight > static_cast<int>(locked.height)) return false;

    const uint32_t minimumRowBytes = (locked.width - 1u) * locked.pixelStrideBytes + 2u;
    if (locked.rowStrideBytes < minimumRowBytes) return false;

    outBayer16 = cv::Mat(cropHeight, cropWidth, CV_16UC1);
    const bool directRowCopy = locked.pixelStrideBytes == 2u && transform.identity;

    if (directRowCopy) {
        cv::parallel_for_(cv::Range(0, cropHeight), [&](const cv::Range& range) {
            for (int localY = range.start; localY < range.end; ++localY) {
                const int sourceY = cropTop + localY;
                const uint8_t *row = locked.data + static_cast<size_t>(sourceY) * locked.rowStrideBytes +
                        static_cast<size_t>(cropLeft) * 2u;
                auto *dst = outBayer16.ptr<uint16_t>(localY);
                std::memcpy(dst, row, static_cast<size_t>(cropWidth) * sizeof(uint16_t));
            }
        });
        return true;
    }

    cv::parallel_for_(cv::Range(0, cropHeight), [&](const cv::Range& range) {
        for (int localY = range.start; localY < range.end; ++localY) {
            const int sourceY = cropTop + localY;
            const uint8_t *row = locked.data + static_cast<size_t>(sourceY) * locked.rowStrideBytes;
            auto *dst = outBayer16.ptr<uint16_t>(localY);
            for (int localX = 0; localX < cropWidth; ++localX) {
                const uint32_t sourceX = static_cast<uint32_t>(cropLeft + localX);
                const uint32_t byteOffset = sourceX * locked.pixelStrideBytes;
                const uint16_t raw = readLittleEndianRaw16(row, byteOffset);
                dst[localX] = transform.identity
                        ? raw
                        : mapNativeSampleToPayload(raw, localX, localY, transform);
            }
        }
    });
    return true;
}

cv::Mat buildAlignmentImage(const cv::Mat& bayer16, int maxLongEdge, double& scaleOut, int whiteLevel) {
    const int width = bayer16.cols;
    const int height = bayer16.rows;
    const int longest = std::max(width, height);
    const double scale = longest > maxLongEdge ? static_cast<double>(maxLongEdge) / static_cast<double>(longest) : 1.0;
    scaleOut = scale;

    // 1. EERST resizen we het originele 16-bit beeld (dit is super efficiënt)
    cv::Mat small16;
    if (scale < 1.0) {
        cv::resize(bayer16, small16, cv::Size(), scale, scale, cv::INTER_NEAREST);
    } else {
        small16 = bayer16;
    }

    // 2. DAARNA pas converteren naar werkbaar 32-bit float formaat, geschaald naar het actuele whiteLevel
    cv::Mat small32;
    const double scaleTo32F = 1.0 / static_cast<double>(std::clamp(whiteLevel, 1, 65535));
    small16.convertTo(small32, CV_32F, scaleTo32F);
    cv::GaussianBlur(small32, small32, cv::Size(3, 3), 0.0);

    cv::Scalar mean, stddev;
    cv::meanStdDev(small32, mean, stddev);
    if (stddev[0] > 1e-6) {
        small32 = (small32 - mean[0]) / stddev[0];
    } else {
        small32 = small32 - mean[0];
    }
    return small32;
}

AlignmentDecision estimateAlignment(
        const cv::Mat& anchorAlign,
        const cv::Mat& supportAlign,
        double alignmentScale,
        int maxShiftPixels,
        float strictness
) {
    AlignmentDecision decision{};
    if (anchorAlign.empty() || supportAlign.empty() || anchorAlign.size() != supportAlign.size()) {
        decision.rejectReason = "invalid alignment input";
        return decision;
    }

    double response = 0.0;
    cv::Point2d shiftSmall = cv::phaseCorrelate(anchorAlign, supportAlign, cv::noArray(), &response);
    const double scale = std::max(alignmentScale, 1e-6);
    const double shiftX = shiftSmall.x / scale;
    const double shiftY = shiftSmall.y / scale;

    decision.phaseResponse = response;
    decision.estimatedShiftX = shiftX;
    decision.estimatedShiftY = shiftY;

    const float safeStrictness = clampStrictness(strictness);
    const double minResponse = 0.03 + (0.07 * static_cast<double>(safeStrictness));
    const int safeMaxShift = std::max(1, maxShiftPixels);

    if (!std::isfinite(shiftX) || !std::isfinite(shiftY) || !std::isfinite(response)) {
        decision.rejectReason = "non-finite alignment result";
        return decision;
    }
    if (std::abs(shiftX) > safeMaxShift || std::abs(shiftY) > safeMaxShift) {
        decision.rejectReason = "shift exceeds maxShiftPixels";
        return decision;
    }
    if (response < minResponse) {
        std::ostringstream oss;
        oss << "phase response below threshold (" << response << " < " << minResponse << ")";
        decision.rejectReason = oss.str();
        return decision;
    }

    decision.applyDx = snapToEvenInt(-shiftX);
    decision.applyDy = snapToEvenInt(-shiftY);
    decision.accepted = true;
    return decision;
}

// =========================================================
// HELPER: Mapt 4 waardes (R, Gr, Gb, B) naar een 2x2 Grid o.b.v. CFA
// =========================================================
void mapToCfa2x2(int cfa, const float* rgbaValues, float lut[2][2]) {
    const float R = rgbaValues[0];
    const float Gr = rgbaValues[1];
    const float Gb = rgbaValues[2];
    const float B = rgbaValues[3];
    switch (cfa) {
        case 1: // GRBG
            lut[0][0] = Gr; lut[0][1] = R;
            lut[1][0] = B;  lut[1][1] = Gb;
            break;
        case 2: // GBRG
            lut[0][0] = Gb; lut[0][1] = B;
            lut[1][0] = R;  lut[1][1] = Gr;
            break;
        case 3: // BGGR
            lut[0][0] = B;  lut[0][1] = Gb;
            lut[1][0] = Gr; lut[1][1] = R;
            break;
        default: // 0 = RGGB
            lut[0][0] = R;  lut[0][1] = Gr;
            lut[1][0] = Gb; lut[1][1] = B;
            break;
    }
}

SpectraSupportObservation analyzeAlignedSupportNoise(
        const cv::Mat& anchor32,
        const cv::Mat& support16,
        const AlignmentDecision& decision,
        float strictness,
        int whiteLevel,
        const std::array<int, 4>& blackLevels,
        int cfaPattern,
        const SpectraNoiseModel& model
) {
    SpectraSupportObservation observation{};
    const float safeStrictness = clampStrictness(strictness);
    const double minResponse = 0.03 + 0.07 * static_cast<double>(safeStrictness);
    observation.alignmentConfidence = static_cast<float>(std::clamp(
            (decision.phaseResponse - minResponse) / std::max(1.0e-6, 0.35 - minResponse),
            0.0,
            1.0
    ));
    observation.forwardBackwardConsistency = std::clamp(
            decision.forwardBackwardConsistency,
            0.0f,
            1.0f
    );
    if (!validSpectraNoiseModel(model)) {
        observation.supportWeight = std::clamp(
                0.15f + 0.35f * observation.alignmentConfidence,
                0.15f,
                0.50f
        );
        return observation;
    }

    const int dx = decision.applyDx;
    const int dy = decision.applyDy;
    const int yStart = std::max(4, dy + 4);
    const int yEnd = std::min(anchor32.rows - 4, support16.rows + dy - 4);
    const int xStart = std::max(4, dx + 4);
    const int xEnd = std::min(anchor32.cols - 4, support16.cols + dx - 4);
    if (xStart >= xEnd || yStart >= yEnd) return observation;

    // Milestone 8H-D: the pixel-scale temporal observer is GPU-primary. Only
    // compact reductions/fits remain CPU-side inside the Vulkan backend. CPU
    // executes the legacy full-frame observer exclusively on a typed Vulkan
    // execution failure, never merely because the GPU observation is invalid.
    bncam::vulkan::SpectraTemporalObserverRequest gpuRequest{};
    gpuRequest.anchorData = anchor32.ptr<float>(0);
    gpuRequest.anchorWidth = static_cast<std::uint32_t>(anchor32.cols);
    gpuRequest.anchorHeight = static_cast<std::uint32_t>(anchor32.rows);
    gpuRequest.anchorRowStrideFloats = anchor32.step1();
    gpuRequest.supportData = support16.ptr<std::uint16_t>(0);
    gpuRequest.supportWidth = static_cast<std::uint32_t>(support16.cols);
    gpuRequest.supportHeight = static_cast<std::uint32_t>(support16.rows);
    gpuRequest.supportRowStrideU16 = support16.step1();
    gpuRequest.applyDx = dx;
    gpuRequest.applyDy = dy;
    gpuRequest.strictness = safeStrictness;
    gpuRequest.alignmentConfidence = observation.alignmentConfidence;
    gpuRequest.forwardBackwardConsistency = observation.forwardBackwardConsistency;
    gpuRequest.whiteLevel = whiteLevel;
    gpuRequest.blackLevels = blackLevels;
    gpuRequest.cfaPattern = cfaPattern;
    gpuRequest.effectiveS = model.effectiveS;
    gpuRequest.effectiveO = model.effectiveO;
    gpuRequest.modelConfidence = model.confidence;
    gpuRequest.temporalAuthority = model.temporalAuthority;
    gpuRequest.adaptationLowerBound = model.adaptationLowerBound;
    gpuRequest.adaptationUpperBound = model.adaptationUpperBound;
    const auto gpuResult = bncam::vulkan::VulkanRuntime::instance().executeSpectraTemporalObserver(gpuRequest);
    observation.vulkanAttempted = gpuResult.attempted;
    observation.vulkanExecutionSucceeded = gpuResult.success;
    observation.vulkanUsedForObserver = gpuResult.success;
    observation.vulkanCpuFallbackUsed = gpuResult.attempted && !gpuResult.success;
    observation.vulkanPersistentReuseHit = gpuResult.persistentBufferReuseHit;
    observation.vulkanPersistentReallocated = gpuResult.persistentBufferReallocated;
    observation.vulkanInputPackingMs = gpuResult.inputPackingMs;
    observation.vulkanCoarseKernelMs = gpuResult.coarseKernelMs;
    observation.vulkanCoarseReductionMs = gpuResult.coarseReductionMs;
    observation.vulkanObserverKernelMs = gpuResult.observerKernelMs;
    observation.vulkanStaticFieldKernelMs = gpuResult.staticFieldKernelMs;
    observation.vulkanCompactReductionAndFitMs = gpuResult.compactReductionAndFitMs;
    observation.vulkanSynchronizationMs = gpuResult.synchronizationMs;
    observation.vulkanReadbackMs = gpuResult.readbackMs;
    observation.vulkanTotalMs = gpuResult.totalMs;
    observation.vulkanInputBytes = gpuResult.inputBytes;
    observation.vulkanCompactReadbackBytes = gpuResult.compactReadbackBytes;
    observation.vulkanPersistentResidentBytes = gpuResult.persistentResidentBytes;
    observation.vulkanStatus = gpuResult.status;
    observation.vulkanFailureReason = gpuResult.failureReason.empty() ? "none" : gpuResult.failureReason;
    if (gpuResult.success) {
        observation.valid = gpuResult.valid;
        observation.observedVariance = gpuResult.observedVariance;
        observation.predictedVariance = gpuResult.predictedVariance;
        observation.adaptationScale = gpuResult.adaptationScale;
        observation.sAdaptationScale = gpuResult.sAdaptationScale;
        observation.oAdaptationScale = gpuResult.oAdaptationScale;
        observation.regressionConfidence = gpuResult.regressionConfidence;
        observation.temporalCorrelation = gpuResult.temporalCorrelation;
        observation.persistentPatternFraction = gpuResult.persistentPatternFraction;
        observation.signalSpan = gpuResult.signalSpan;
        observation.populatedSignalBins = gpuResult.populatedSignalBins;
        observation.samples = gpuResult.samples;
        observation.fitEstimator = gpuResult.fitEstimator;
        observation.fitPhysicalScore = gpuResult.fitPhysicalScore;
        observation.fitInnovationMean = gpuResult.fitInnovationMean;
        observation.fitInnovationVariance = gpuResult.fitInnovationVariance;
        observation.fitResidualCorrelation = gpuResult.fitResidualCorrelation;
        observation.fitHeavyTailFraction = gpuResult.fitHeavyTailFraction;
        observation.staticProbabilityField = gpuResult.staticProbabilityField;
        observation.observerConfidence = gpuResult.observerConfidence;
        observation.alignmentConfidence = gpuResult.alignmentConfidence;
        observation.motionConfidence = gpuResult.motionConfidence;
        observation.forwardBackwardConsistency = gpuResult.forwardBackwardConsistency;
        observation.meanTemporalCorrelation = gpuResult.meanTemporalCorrelation;
        observation.persistentPatternMean = gpuResult.persistentPatternMean;
        observation.independentNoiseFraction = gpuResult.independentNoiseFraction;
        observation.supportWeight = gpuResult.supportWeight;
        observation.staticProbabilityMean = gpuResult.staticProbabilityMean;
        observation.staticProbabilityP10 = gpuResult.staticProbabilityP10;
        observation.staticProbabilityP50 = gpuResult.staticProbabilityP50;
        observation.staticProbabilityP90 = gpuResult.staticProbabilityP90;
        observation.normalizedInnovationMean = gpuResult.normalizedInnovationMean;
        observation.normalizedInnovationVariance = gpuResult.normalizedInnovationVariance;
        observation.normalizedInnovationP90 = gpuResult.normalizedInnovationP90;
        observation.normalizedInnovationLagCorrelation = gpuResult.normalizedInnovationLagCorrelation;
        observation.heavyTailFraction = gpuResult.heavyTailFraction;
        observation.highConfidenceStaticSamples = gpuResult.highConfidenceStaticSamples;
        observation.clippingRejectedSamples = gpuResult.clippingRejectedSamples;
        observation.textureRejectedSamples = gpuResult.textureRejectedSamples;
        observation.localMotionRejectedSamples = gpuResult.localMotionRejectedSamples;
        return observation;
    }

    constexpr int kSignalBins = 8;
    constexpr int kStaticCellSize = 48;
    using BinDouble = std::array<double, kSignalBins>;
    using BinCount = std::array<uint64_t, kSignalBins>;

    observation.staticProbabilityField.imageWidth = anchor32.cols;
    observation.staticProbabilityField.imageHeight = anchor32.rows;
    observation.staticProbabilityField.cellSize = kStaticCellSize;
    observation.staticProbabilityField.columns =
            (anchor32.cols + kStaticCellSize - 1) / kStaticCellSize;
    observation.staticProbabilityField.rows =
            (anchor32.rows + kStaticCellSize - 1) / kStaticCellSize;
    const size_t staticCells = static_cast<size_t>(
            observation.staticProbabilityField.columns * observation.staticProbabilityField.rows
    );
    observation.staticProbabilityField.values.assign(staticCells, 0.0f);
    std::vector<double> staticSums(staticCells, 0.0);
    std::vector<uint64_t> staticCounts(staticCells, 0u);

    std::array<double, 4> coarseDiffSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> coarseDiffWeight{0.0, 0.0, 0.0, 0.0};

    auto sampleState = [&](int xx, int yy, double& anchorNorm, double& supportNorm,
                           double& pairVariance, double& textureConfidence) -> bool {
        const int srcX = xx - dx;
        const int srcY = yy - dy;
        if (xx - 2 < 0 || xx + 2 >= anchor32.cols || yy - 2 < 0 || yy + 2 >= anchor32.rows ||
            srcX - 2 < 0 || srcX + 2 >= support16.cols ||
            srcY - 2 < 0 || srcY + 2 >= support16.rows) {
            return false;
        }
        const float* anchorRow = anchor32.ptr<float>(yy);
        const float* anchorM2 = anchor32.ptr<float>(yy - 2);
        const float* anchorP2 = anchor32.ptr<float>(yy + 2);
        const uint16_t* supportRow = support16.ptr<uint16_t>(srcY);
        const uint16_t* supportM2 = support16.ptr<uint16_t>(srcY - 2);
        const uint16_t* supportP2 = support16.ptr<uint16_t>(srcY + 2);
        anchorNorm = spectraNormalizedSignal(anchorRow[xx], xx, yy, whiteLevel, blackLevels);
        supportNorm = spectraNormalizedSignal(supportRow[srcX], srcX, srcY, whiteLevel, blackLevels);
        if (anchorNorm < 0.002 || anchorNorm > 0.965 ||
            supportNorm < 0.002 || supportNorm > 0.965) {
            return false;
        }
        const int ch = spectraCfaChannel(cfaPattern, xx, yy);
        const double anchorVariance = std::max(
                1.0e-12,
                model.effectiveS[ch] * anchorNorm + model.effectiveO[ch]
        );
        const double supportVariance = std::max(
                1.0e-12,
                model.effectiveS[ch] * supportNorm + model.effectiveO[ch]
        );
        pairVariance = anchorVariance + supportVariance;
        const double sigma = std::sqrt(std::max(anchorVariance, supportVariance));
        const double anchorGradient = std::max({
                std::abs(spectraNormalizedDifference(
                        static_cast<double>(anchorRow[xx - 2] - anchorRow[xx + 2]),
                        xx, yy, whiteLevel, blackLevels
                )),
                std::abs(spectraNormalizedDifference(
                        static_cast<double>(anchorM2[xx] - anchorP2[xx]),
                        xx, yy, whiteLevel, blackLevels
                ))
        });
        const double supportGradient = std::max({
                std::abs(spectraNormalizedDifference(
                        static_cast<double>(supportRow[srcX - 2] - supportRow[srcX + 2]),
                        srcX, srcY, whiteLevel, blackLevels
                )),
                std::abs(spectraNormalizedDifference(
                        static_cast<double>(supportM2[srcX] - supportP2[srcX]),
                        srcX, srcY, whiteLevel, blackLevels
                ))
        });
        const double gradient = std::max(anchorGradient, supportGradient);
        const double textureLimit = std::max(0.012, (5.5 - 1.5 * safeStrictness) * sigma);
        textureConfidence = std::clamp(1.0 - gradient / std::max(textureLimit, 1.0e-12), 0.0, 1.0);
        return true;
    };

    // First pass: estimate the per-CFA mean frame delta after clipping/texture screening.
    // Subtracting this delta keeps small exposure or black-offset drift out of the motion z-score.
    for (int y = yStart; y < yEnd; y += 4) {
        for (int x = xStart; x < xEnd; x += 4) {
            for (int phaseY = 0; phaseY < 2; ++phaseY) {
                for (int phaseX = 0; phaseX < 2; ++phaseX) {
                    const int yy = y + phaseY;
                    const int xx = x + phaseX;
                    double anchorNorm = 0.0;
                    double supportNorm = 0.0;
                    double pairVariance = 0.0;
                    double textureConfidence = 0.0;
                    if (!sampleState(xx, yy, anchorNorm, supportNorm, pairVariance, textureConfidence)) {
                        observation.clippingRejectedSamples++;
                        continue;
                    }
                    if (textureConfidence <= 0.10) {
                        observation.textureRejectedSamples++;
                        continue;
                    }
                    const double diff = anchorNorm - supportNorm;
                    const double broadGate = std::max(0.015, 8.0 * std::sqrt(pairVariance));
                    if (std::abs(diff) > broadGate) continue;
                    const int ch = spectraCfaChannel(cfaPattern, xx, yy);
                    const double weight = textureConfidence;
                    coarseDiffSum[ch] += weight * diff;
                    coarseDiffWeight[ch] += weight;
                }
            }
        }
    }
    std::array<double, 4> meanDelta{0.0, 0.0, 0.0, 0.0};
    for (int ch = 0; ch < 4; ++ch) {
        if (coarseDiffWeight[ch] > 1.0e-12) {
            meanDelta[ch] = coarseDiffSum[ch] / coarseDiffWeight[ch];
        }
    }

    std::array<BinDouble, 4> binDiffSum{};
    std::array<BinDouble, 4> binDiffSqSum{};
    std::array<BinDouble, 4> binSignalSum{};
    std::array<BinDouble, 4> binWeight{};
    std::array<BinCount, 4> binCount{};
    std::array<double, 4> diffSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> diffSqSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> signalSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> sampleWeightSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> innovationSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> innovationSqSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> innovationLagProduct{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> innovationLagWeight{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> previousInnovation{0.0, 0.0, 0.0, 0.0};
    std::array<bool, 4> hasPreviousInnovation{false, false, false, false};
    std::array<uint64_t, 4> heavyTailCount{0, 0, 0, 0};
    std::array<double, 4> residualAnchorSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> residualSupportSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> residualAnchorSqSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> residualSupportSqSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> residualCrossSum{0.0, 0.0, 0.0, 0.0};
    std::array<double, 4> residualWeightSum{0.0, 0.0, 0.0, 0.0};
    std::vector<double> staticValues;
    std::vector<double> absoluteInnovations;
    uint64_t totalMotionSamples = 0;
    uint64_t acceptedMotionSamples = 0;

    // Second pass: build the continuous static-probability map from normalized innovation.
    // Only high-confidence static samples are allowed to steer the S/O regression.
    for (int y = yStart; y < yEnd; y += 4) {
        for (int x = xStart; x < xEnd; x += 4) {
            for (int phaseY = 0; phaseY < 2; ++phaseY) {
                const int yy = y + phaseY;
                const int srcY = yy - dy;
                const float* anchorRow = anchor32.ptr<float>(yy);
                const float* anchorM2 = anchor32.ptr<float>(yy - 2);
                const float* anchorP2 = anchor32.ptr<float>(yy + 2);
                const uint16_t* supportRow = support16.ptr<uint16_t>(srcY);
                const uint16_t* supportM2 = support16.ptr<uint16_t>(srcY - 2);
                const uint16_t* supportP2 = support16.ptr<uint16_t>(srcY + 2);
                for (int phaseX = 0; phaseX < 2; ++phaseX) {
                    const int xx = x + phaseX;
                    const int srcX = xx - dx;
                    double anchorNorm = 0.0;
                    double supportNorm = 0.0;
                    double pairVariance = 0.0;
                    double textureConfidence = 0.0;
                    if (!sampleState(xx, yy, anchorNorm, supportNorm, pairVariance, textureConfidence)) {
                        continue;
                    }
                    const int ch = spectraCfaChannel(cfaPattern, xx, yy);
                    const double centredDiff = (anchorNorm - supportNorm) - meanDelta[ch];
                    const double signedZ = centredDiff / std::sqrt(std::max(pairVariance, 1.0e-12));
                    const float staticProbability = spectra_temporal::staticProbability(
                            signedZ,
                            textureConfidence,
                            1.0,
                            observation.forwardBackwardConsistency,
                            1.0
                    );
                    const int cellX = std::clamp(xx / kStaticCellSize, 0,
                            observation.staticProbabilityField.columns - 1);
                    const int cellY = std::clamp(yy / kStaticCellSize, 0,
                            observation.staticProbabilityField.rows - 1);
                    const size_t cellIndex = static_cast<size_t>(
                            cellY * observation.staticProbabilityField.columns + cellX
                    );
                    staticSums[cellIndex] += staticProbability;
                    staticCounts[cellIndex]++;
                    totalMotionSamples++;
                    absoluteInnovations.push_back(std::abs(signedZ));
                    if (staticProbability < 0.20f) {
                        observation.localMotionRejectedSamples++;
                        continue;
                    }
                    acceptedMotionSamples++;
                    if (staticProbability >= 0.72f && textureConfidence >= 0.35) {
                        observation.highConfidenceStaticSamples++;
                    }
                    // Do not censor ordinary noise tails by requiring the same high z-based
                    // confidence used by the motion gate. Robust WLS/Huber weighting receives all
                    // supported static samples; the separate high-confidence count still gates
                    // whether the observation may update S/O at all.
                    if (textureConfidence < 0.25) continue;
                    const double sampleWeight = std::pow(
                            static_cast<double>(staticProbability), 1.5
                    ) * std::max(0.15, textureConfidence);
                    observation.samples[ch]++;
                    sampleWeightSum[ch] += sampleWeight;
                    diffSum[ch] += sampleWeight * centredDiff;
                    diffSqSum[ch] += sampleWeight * centredDiff * centredDiff;
                    signalSum[ch] += sampleWeight * anchorNorm;
                    innovationSum[ch] += sampleWeight * signedZ;
                    innovationSqSum[ch] += sampleWeight * signedZ * signedZ;
                    if (hasPreviousInnovation[ch]) {
                        innovationLagProduct[ch] += sampleWeight * signedZ * previousInnovation[ch];
                        innovationLagWeight[ch] += sampleWeight;
                    }
                    previousInnovation[ch] = signedZ;
                    hasPreviousInnovation[ch] = true;
                    if (std::abs(signedZ) > 3.5) heavyTailCount[ch]++;

                    const int signalBin = std::clamp(
                            static_cast<int>(std::floor(anchorNorm * kSignalBins)),
                            0,
                            kSignalBins - 1
                    );
                    binDiffSum[ch][signalBin] += sampleWeight * centredDiff;
                    binDiffSqSum[ch][signalBin] += sampleWeight * centredDiff * centredDiff;
                    binSignalSum[ch][signalBin] += sampleWeight * anchorNorm;
                    binWeight[ch][signalBin] += sampleWeight;
                    binCount[ch][signalBin]++;

                    const double anchorLocalMean = 0.25 * (
                            spectraNormalizedSignal(anchorRow[xx - 2], xx - 2, yy, whiteLevel, blackLevels) +
                            spectraNormalizedSignal(anchorRow[xx + 2], xx + 2, yy, whiteLevel, blackLevels) +
                            spectraNormalizedSignal(anchorM2[xx], xx, yy - 2, whiteLevel, blackLevels) +
                            spectraNormalizedSignal(anchorP2[xx], xx, yy + 2, whiteLevel, blackLevels)
                    );
                    const double supportLocalMean = 0.25 * (
                            spectraNormalizedSignal(supportRow[srcX - 2], srcX - 2, srcY, whiteLevel, blackLevels) +
                            spectraNormalizedSignal(supportRow[srcX + 2], srcX + 2, srcY, whiteLevel, blackLevels) +
                            spectraNormalizedSignal(supportM2[srcX], srcX, srcY - 2, whiteLevel, blackLevels) +
                            spectraNormalizedSignal(supportP2[srcX], srcX, srcY + 2, whiteLevel, blackLevels)
                    );
                    const double residualAnchor = anchorNorm - anchorLocalMean;
                    const double residualSupport = supportNorm - supportLocalMean;
                    residualAnchorSum[ch] += sampleWeight * residualAnchor;
                    residualSupportSum[ch] += sampleWeight * residualSupport;
                    residualAnchorSqSum[ch] += sampleWeight * residualAnchor * residualAnchor;
                    residualSupportSqSum[ch] += sampleWeight * residualSupport * residualSupport;
                    residualCrossSum[ch] += sampleWeight * residualAnchor * residualSupport;
                    residualWeightSum[ch] += sampleWeight;
                }
            }
        }
    }

    for (size_t i = 0; i < staticCells; ++i) {
        const double value = staticCounts[i] > 0u
                ? staticSums[i] / static_cast<double>(staticCounts[i])
                : 0.0;
        observation.staticProbabilityField.values[i] = static_cast<float>(std::clamp(value, 0.0, 1.0));
        if (staticCounts[i] > 0u) staticValues.push_back(value);
    }
    observation.staticProbabilityMean = staticValues.empty()
            ? 0.0
            : std::accumulate(staticValues.begin(), staticValues.end(), 0.0) /
                    static_cast<double>(staticValues.size());
    observation.staticProbabilityP10 = spectra_temporal::percentile(staticValues, 0.10);
    observation.staticProbabilityP50 = spectra_temporal::percentile(staticValues, 0.50);
    observation.staticProbabilityP90 = spectra_temporal::percentile(staticValues, 0.90);
    observation.normalizedInnovationP90 = spectra_temporal::percentile(absoluteInnovations, 0.90);
    observation.motionConfidence = totalMotionSamples > 0u
            ? static_cast<float>(acceptedMotionSamples) / static_cast<float>(totalMotionSamples)
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
        const uint64_t count = observation.samples[ch];
        const double weightSum = sampleWeightSum[ch];
        if (count < 64u || weightSum <= 1.0e-12) continue;

        const double meanDiff = diffSum[ch] / weightSum;
        const double diffVariance = std::max(0.0, diffSqSum[ch] / weightSum - meanDiff * meanDiff);
        const double observed = 0.5 * diffVariance;
        const double meanSignal = signalSum[ch] / weightSum;
        const double predicted = std::max(
                1.0e-12,
                model.effectiveS[ch] * meanSignal + model.effectiveO[ch]
        );
        observation.observedVariance[ch] = observed;
        observation.predictedVariance[ch] = predicted;

        const double innovationMean = innovationSum[ch] / weightSum;
        const double innovationVariance = std::max(
                0.0,
                innovationSqSum[ch] / weightSum - innovationMean * innovationMean
        );
        const double innovationLag = innovationLagWeight[ch] > 1.0e-12 && innovationVariance > 1.0e-12
                ? std::clamp(
                        innovationLagProduct[ch] / innovationLagWeight[ch] /
                                std::max(innovationVariance, 1.0e-12),
                        -1.0,
                        1.0
                )
                : 0.0;
        const double heavyTailFraction = static_cast<double>(heavyTailCount[ch]) /
                static_cast<double>(std::max<uint64_t>(1u, count));
        const spectra_temporal::InnovationDiagnostics innovation{
                innovationMean,
                innovationVariance,
                innovationLag,
                heavyTailFraction,
                count
        };

        double temporalCorrelation = 0.0;
        const double residualWeight = residualWeightSum[ch];
        if (residualWeight > 64.0) {
            const double meanA = residualAnchorSum[ch] / residualWeight;
            const double meanB = residualSupportSum[ch] / residualWeight;
            const double varianceA = std::max(
                    0.0,
                    residualAnchorSqSum[ch] / residualWeight - meanA * meanA
            );
            const double varianceB = std::max(
                    0.0,
                    residualSupportSqSum[ch] / residualWeight - meanB * meanB
            );
            const double covariance = residualCrossSum[ch] / residualWeight - meanA * meanB;
            if (varianceA > 1.0e-12 && varianceB > 1.0e-12) {
                const double rawCorrelation = covariance / std::sqrt(varianceA * varianceB);
                const double correlationConfidence = std::clamp(residualWeight / 2048.0, 0.0, 1.0) *
                        static_cast<double>(observation.forwardBackwardConsistency) *
                        static_cast<double>(observation.staticProbabilityP50) *
                        static_cast<double>(model.confidence);
                temporalCorrelation = std::clamp(
                        std::max(0.0, rawCorrelation) * correlationConfidence,
                        0.0,
                        0.85
                );
            }
        }
        // Conservative fallback when high-pass covariance is unavailable. This path receives
        // half the authority of the legacy variance-deficit inference.
        if (temporalCorrelation <= 0.0) {
            const double varianceDeficit = std::clamp(1.0 - observed / predicted, 0.0, 1.0);
            temporalCorrelation = std::clamp(
                    0.25 * varianceDeficit * observation.staticProbabilityP50 *
                            observation.forwardBackwardConsistency * model.confidence,
                    0.0,
                    0.40
            );
        }
        observation.temporalCorrelation[ch] = temporalCorrelation;
        observation.persistentPatternFraction[ch] = temporalCorrelation;
        const double correlationCorrection = 1.0 / std::max(0.15, 1.0 - temporalCorrelation);

        std::vector<spectra_temporal::TemporalFitPoint> points;
        points.reserve(kSignalBins);
        double minSignal = 1.0;
        double maxSignal = 0.0;
        for (int bin = 0; bin < kSignalBins; ++bin) {
            const uint64_t binSamples = binCount[ch][bin];
            const double weight = binWeight[ch][bin];
            if (binSamples < 24u || weight <= 1.0e-12) continue;
            const double binMeanDiff = binDiffSum[ch][bin] / weight;
            const double binVariance = std::max(
                    0.0,
                    binDiffSqSum[ch][bin] / weight - binMeanDiff * binMeanDiff
            );
            const double signal = binSignalSum[ch][bin] / weight;
            const double variance = 0.5 * binVariance * correlationCorrection;
            if (!std::isfinite(signal) || !std::isfinite(variance) || variance <= 0.0) continue;
            points.push_back({signal, variance, std::sqrt(weight)});
            minSignal = std::min(minSignal, signal);
            maxSignal = std::max(maxSignal, signal);
        }

        const double signalSpan = points.empty() ? 0.0 : std::max(0.0, maxSignal - minSignal);
        observation.populatedSignalBins[ch] = static_cast<int>(points.size());
        observation.signalSpan[ch] = signalSpan;
        // Dual estimator: weighted least squares plus Huber IRLS, selected by physical residual score.
        const auto fit = spectra_temporal::fitTemporalNoiseModel(points, innovation);

        double sScale = 1.0;
        double oScale = 1.0;
        double regressionConfidence = 0.0;
        if (fit.valid && points.size() >= 3u && signalSpan >= 0.035) {
            if (model.effectiveS[ch] > 1.0e-12) {
                sScale = std::clamp(
                        fit.slope / model.effectiveS[ch],
                        model.adaptationLowerBound,
                        model.adaptationUpperBound
                );
            }
            if (model.effectiveO[ch] > 1.0e-12) {
                oScale = std::clamp(
                        fit.offset / model.effectiveO[ch],
                        model.adaptationLowerBound,
                        model.adaptationUpperBound
                );
            }
            const double sampleConfidence = std::clamp(static_cast<double>(count) / 4096.0, 0.0, 1.0);
            regressionConfidence = fit.confidence * sampleConfidence;
            observation.fitEstimator[ch] = static_cast<int>(fit.estimator);
            observation.fitPhysicalScore[ch] = fit.metrics.physicalScore;
        }
        if (regressionConfidence < 0.05) {
            const double commonScale = std::clamp(
                    (observed * correlationCorrection) / predicted,
                    model.adaptationLowerBound,
                    model.adaptationUpperBound
            );
            sScale = commonScale;
            oScale = commonScale;
            regressionConfidence = 0.15 * std::clamp(
                    static_cast<double>(count) / 4096.0,
                    0.0,
                    1.0
            ) * observation.staticProbabilityP50;
            observation.fitEstimator[ch] = static_cast<int>(
                    spectra_temporal::TemporalFitEstimator::NONE
            );
        }

        observation.sAdaptationScale[ch] = sScale;
        observation.oAdaptationScale[ch] = oScale;
        observation.adaptationScale[ch] = std::sqrt(std::max(0.0, sScale * oScale));
        observation.regressionConfidence[ch] = regressionConfidence;
        observation.fitInnovationMean[ch] = innovationMean;
        observation.fitInnovationVariance[ch] = innovationVariance;
        observation.fitResidualCorrelation[ch] = innovationLag;
        observation.fitHeavyTailFraction[ch] = heavyTailFraction;

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

    observation.normalizedInnovationMean = globalInnovationWeight > 0.0
            ? globalInnovationMeanWeighted / globalInnovationWeight : 0.0;
    observation.normalizedInnovationVariance = globalInnovationWeight > 0.0
            ? globalInnovationVarianceWeighted / globalInnovationWeight : 0.0;
    observation.normalizedInnovationLagCorrelation = globalInnovationWeight > 0.0
            ? globalInnovationLagWeighted / globalInnovationWeight : 0.0;
    observation.heavyTailFraction = globalInnovationWeight > 0.0
            ? globalHeavyTailWeighted / globalInnovationWeight : 0.0;
    observation.meanTemporalCorrelation = correlationWeight > 0.0
            ? static_cast<float>(std::clamp(correlationWeighted / correlationWeight, 0.0, 0.85))
            : 0.0f;
    observation.persistentPatternMean = observation.meanTemporalCorrelation;
    observation.independentNoiseFraction = std::clamp(
            1.0f - observation.persistentPatternMean,
            0.15f,
            1.0f
    );
    observation.observerConfidence = validChannels > 0
            ? std::clamp(
                    (confidenceSum / static_cast<double>(validChannels)) *
                    static_cast<double>(observation.alignmentConfidence) *
                    static_cast<double>(observation.motionConfidence) *
                    static_cast<double>(observation.forwardBackwardConsistency) *
                    static_cast<double>(observation.staticProbabilityP50) *
                    static_cast<double>(model.confidence),
                    0.0,
                    1.0
            )
            : 0.0;
    const float temporalBlend = 0.20f + 0.80f * model.temporalAuthority;
    observation.supportWeight = std::clamp(
            observation.alignmentConfidence * observation.motionConfidence *
                    observation.forwardBackwardConsistency *
                    static_cast<float>(0.30 + 0.70 * observation.staticProbabilityP50) *
                    (0.35f + 0.65f * model.confidence) * temporalBlend *
                    std::sqrt(observation.independentNoiseFraction),
            0.02f,
            1.0f
    );
    observation.valid = validChannels >= 2 &&
            observation.highConfidenceStaticSamples >= 256u &&
            observation.observerConfidence >= 0.08 &&
            observation.forwardBackwardConsistency >= 0.20f;
    return observation;
}

void mergeAlignedSupport(
        const cv::Mat& anchor32,
        const cv::Mat& support16,
        const AlignmentDecision& decision,
        float strictness,
        int whiteLevel,
        const std::array<int, 4>& blackLevels,
        int cfaPattern,
        const SpectraNoiseModel& model,
        const SpectraSupportObservation& observation,
        cv::Mat& accum32,
        cv::Mat& weight32,
        cv::Mat& weightSq32,
        cv::Mat& correlationWeighted32
) {
    const int dx = decision.applyDx;
    const int dy = decision.applyDy;
    const float safeStrictness = clampStrictness(strictness);
    const bool spectraValid = validSpectraNoiseModel(model);
    const float supportBaseWeight = spectraValid
            ? observation.supportWeight
            : 0.25f;
    const float fallbackMaxDiff = static_cast<float>(std::clamp(whiteLevel, 1, 65535)) *
            std::clamp(0.35f - (0.22f * safeStrictness), 0.08f, 0.35f);

    const int yStart = std::max(0, dy);
    const int yEnd = std::min(anchor32.rows, support16.rows + dy);
    const int xStart = std::max(0, dx);
    const int xEnd = std::min(anchor32.cols, support16.cols + dx);
    if (xStart >= xEnd || yStart >= yEnd) return;
    cv::parallel_for_(cv::Range(yStart, yEnd), [&](const cv::Range& range) {
        for (int y = range.start; y < range.end; ++y) {
            const int srcY = y - dy;
            const float* pAnchor = anchor32.ptr<float>(y);
            const uint16_t* pSupport = support16.ptr<uint16_t>(srcY);
            float* pAccum = accum32.ptr<float>(y);
            float* pWeight = weight32.ptr<float>(y);
            float* pWeightSq = weightSq32.ptr<float>(y);
            float* pCorrelationWeighted = spectraValid
                    ? correlationWeighted32.ptr<float>(y)
                    : nullptr;

            for (int x = xStart; x < xEnd; ++x) {
                const int srcX = x - dx;
                const float supportValue = static_cast<float>(pSupport[srcX]);
                const float anchorValue = pAnchor[x];
                const float absoluteDiff = std::abs(anchorValue - supportValue);
                float weight = supportBaseWeight;
                double relativeVarianceRatio = 1.0;
                float localStaticProbability = 1.0f;

                if (spectraValid) {
                    localStaticProbability = observation.staticProbabilityField.sample(x, y);
                    const float localAuthority = spectra_temporal::localFusionAuthority(
                            localStaticProbability,
                            observation.forwardBackwardConsistency,
                            model.confidence,
                            observation.motionConfidence
                    );
                    // A local motion probability below this point gets no fusion credit. The
                    // bilinear field and continuous authority avoid a hard visual boundary.
                    if (localAuthority < 0.025f) continue;
                    weight *= localAuthority;

                    const int ch = spectraCfaChannel(cfaPattern, x, y);
                    const double supportSignal = spectraNormalizedSignal(
                            supportValue,
                            srcX,
                            srcY,
                            whiteLevel,
                            blackLevels
                    );
                    const double adaptedS = model.effectiveS[ch] *
                            std::clamp(observation.sAdaptationScale[ch], 0.75, 1.25);
                    const double adaptedO = model.effectiveO[ch] *
                            std::clamp(observation.oAdaptationScale[ch], 0.75, 1.25);
                    const double supportVariance = std::max(
                            1.0e-12,
                            adaptedS * supportSignal + adaptedO
                    );
                    const double anchorSignal = spectraNormalizedSignal(
                            anchorValue,
                            x,
                            y,
                            whiteLevel,
                            blackLevels
                    );
                    const double anchorVariance = std::max(
                            1.0e-12,
                            adaptedS * anchorSignal + adaptedO
                    );
                    relativeVarianceRatio = std::clamp(
                            supportVariance / anchorVariance,
                            0.25,
                            4.0
                    );
                    const int black = static_cast<int>(spectraPixelBlack(
                            srcX,
                            srcY,
                            whiteLevel,
                            blackLevels
                    ));
                    const float channelRange = static_cast<float>(std::max(1, whiteLevel - black));
                    const float sigmaCode = static_cast<float>(std::sqrt(supportVariance)) * channelRange;
                    const float robustSigma = std::max(3.5f * sigmaCode, 1.5f);
                    const float rejectGate = std::max(8.0f * sigmaCode, channelRange * 0.012f);
                    if (absoluteDiff > rejectGate) continue;
                    const float normalized = absoluteDiff / robustSigma;
                    const float robustWeight = std::exp(-0.5f * normalized * normalized);
                    const double referenceVariance = std::max(
                            1.0e-12,
                            adaptedS * 0.15 + adaptedO
                    );
                    const float inverseVarianceWeight = static_cast<float>(std::clamp(
                            referenceVariance / supportVariance,
                            0.25,
                            4.0
                    ));
                    const float modelWeight = 1.0f +
                            model.confidence * model.temporalAuthority *
                            (inverseVarianceWeight - 1.0f);
                    weight *= robustWeight * modelWeight;
                } else {
                    if (absoluteDiff > fallbackMaxDiff) continue;
                }

                if (weight < 0.01f) continue;
                pAccum[x] += supportValue * weight;
                pWeight[x] += weight;
                // Heteroscedastic independent term q = sum(w_i^2 sigma_i^2) / sum(w_i)^2.
                pWeightSq[x] += static_cast<float>(weight * weight * relativeVarianceRatio);
                if (spectraValid) {
                    // This numerator is divided by the local support-weight sum after fusion.
                    // Static probability lowers persistent-pattern credit in uncertain motion areas.
                    if (pCorrelationWeighted != nullptr) {
                        pCorrelationWeighted[x] += weight * observation.meanTemporalCorrelation *
                                localStaticProbability;
                    }
                }
            }
        }
    });
}



void applyGpuRawMultiFrameStats(
        DngMergeStats& stats,
        const bncam::vulkan::RawMultiFrameResult& gpu,
        bool spectraEnabled,
        bool fuseSupportFrames
) {
    stats.framesDecoded = gpu.framesDecoded;
    stats.supportAccepted = gpu.supportAccepted;
    stats.supportRejected = gpu.supportRejected;
    bncam::raw_support_telemetry::RejectionBreakdown rejectionBreakdown{};
    for (const auto& support : gpu.supports) {
        if (support.acceptedForFusion) continue;
        bncam::raw_support_telemetry::record(
                rejectionBreakdown,
                bncam::raw_support_telemetry::classifyRejectionReason(support.rejectReason));
    }
    // The backend's supportRejected counter remains authoritative. If a backend-level reject
    // cannot be associated with a per-support reason, surface the discrepancy as OTHER rather
    // than silently losing it from the compact breakdown.
    if (rejectionBreakdown.total() < gpu.supportRejected) {
        rejectionBreakdown.other += gpu.supportRejected - rejectionBreakdown.total();
    }
    stats.supportRejectedCanonicalization = rejectionBreakdown.canonicalization;
    stats.supportRejectedAlignment = rejectionBreakdown.alignment;
    stats.supportRejectedForwardBackward = rejectionBreakdown.forwardBackward;
    stats.supportRejectedSpectraConsensus = rejectionBreakdown.spectraConsensus;
    stats.supportRejectedZeroWeightedContribution = rejectionBreakdown.zeroWeightedContribution;
    stats.supportRejectedOther = rejectionBreakdown.other;
    stats.framesMerged = fuseSupportFrames && gpu.supportAccepted > 0 ? 1 + gpu.supportAccepted : 1;
    stats.mergeOutputCreated = fuseSupportFrames && gpu.supportAccepted > 0;
    stats.anchorOnly = !stats.mergeOutputCreated;
    stats.inputImportPath = gpu.inputImportPath;
    stats.rawUnpackBackend = gpu.rawUnpackBackend;
    stats.rawUnpackVulkanFrames = gpu.framesDecoded;
    stats.fullFrameCpuUploadBytes += gpu.fullFrameCpuUploadBytes;
    stats.fullFrameGpuReadbackBytes += gpu.fullFrameGpuReadbackBytes;
    stats.alignmentBackend = gpu.alignmentBackend;
    stats.fusionBackend = gpu.fusionBackend;
    stats.cpuAlignment = gpu.cpuAlignment;
    stats.cpuFusion = gpu.cpuFusion;
    stats.cpuFullFrameSupportMaterialization = gpu.cpuFullFrameSupportMaterialization;
    stats.residentFusedRawProduced = gpu.residentFusedRawProduced;
    stats.compactGpuReadbackBytes += gpu.compactGpuReadbackBytes;
    stats.rawUnpackGpuMs += gpu.canonicalizeGpuMs;
    stats.rawAlignmentGpuMs = gpu.alignmentGpuMs;
    stats.rawFusionGpuMs = gpu.fusionGpuMs;
    stats.rawMultiFrameObserverGpuMs = gpu.observerGpuMs;
    stats.rawMultiFrameGpuSynchronizationMs = gpu.gpuSynchronizationMs;
    stats.rawMultiFrameFinalReadbackMs = gpu.finalReadbackMs;
    stats.alignmentMs = gpu.alignmentGpuMs;
    stats.mergeAccumulatorMs = gpu.fusionGpuMs;
    stats.mergeNormalizeMs = 0.0;
    stats.spectraFusionVarianceScale = gpu.spectraFusionVarianceScale;
    stats.spectraFusionVarianceP10 = gpu.spectraFusionVarianceP10;
    stats.spectraFusionVarianceP50 = gpu.spectraFusionVarianceP50;
    stats.spectraFusionVarianceP90 = gpu.spectraFusionVarianceP90;
    stats.spectraEffectiveFrameCount = gpu.spectraEffectiveFrameCount;
    stats.spectraEffectiveFrameCountP10 = gpu.spectraEffectiveFrameCountP10;
    stats.spectraEffectiveFrameCountP50 = gpu.spectraEffectiveFrameCountP50;
    stats.spectraEffectiveFrameCountP90 = gpu.spectraEffectiveFrameCountP90;
    stats.spectraLocalFusionFallbackFraction = gpu.spectraLocalFusionFallbackFraction;

    double sumDx = 0.0, sumDy = 0.0, sumResponse = 0.0;
    double minResponse = std::numeric_limits<double>::infinity(), maxResponse = 0.0;
    int acceptedOutput = 0;
    int acceptedObservations = 0;
    double observerConfidenceSum = 0.0, supportWeightSum = 0.0, correlationSum = 0.0;
    double alignmentConfidenceSum = 0.0, motionConfidenceSum = 0.0, fbSum = 0.0;
    double fbMin = std::numeric_limits<double>::infinity(), repeatedSum = 0.0;
    double staticMeanSum = 0.0, staticP10Sum = 0.0, staticP50Sum = 0.0, staticP90Sum = 0.0;
    double innovationMeanSum = 0.0, innovationVarianceSum = 0.0, innovationP90Sum = 0.0;
    double innovationLagSum = 0.0, heavyTailSum = 0.0, persistentSum = 0.0, fitStabilitySum = 0.0;
    double fitStabilityMin = std::numeric_limits<double>::infinity();
    std::array<double,4> channelWeight{0,0,0,0}, observed{0,0,0,0}, predicted{0,0,0,0};
    std::array<double,4> scale{0,0,0,0}, sScale{0,0,0,0}, oScale{0,0,0,0};
    std::array<double,4> regression{0,0,0,0}, signalSpan{0,0,0,0}, bins{0,0,0,0};
    std::array<double,4> physical{0,0,0,0}, fitMean{0,0,0,0}, fitVar{0,0,0,0};
    std::array<double,4> fitCorr{0,0,0,0}, fitTail{0,0,0,0}, fitStability{0,0,0,0};
    std::array<int,4> wls{0,0,0,0}, huber{0,0,0,0};

    for (const auto& support : gpu.supports) {
        if (spectraEnabled && support.rejectReason == "forward_backward_inconsistent") {
            ++stats.spectraForwardBackwardRejectedPairs;
        }
        if (support.acceptedForFusion) {
            ++acceptedOutput;
            sumDx += support.applyDx; sumDy += support.applyDy; sumResponse += support.phaseResponse;
            minResponse = std::min(minResponse, support.phaseResponse);
            maxResponse = std::max(maxResponse, support.phaseResponse);
        }
        const auto& o = support.spectraObservation;
        if (!spectraEnabled || !o.success) continue;
        stats.spectraTemporalVulkanAttemptedPairs += o.attempted ? 1 : 0;
        stats.spectraTemporalVulkanSucceededPairs += o.success ? 1 : 0;
        stats.spectraTemporalVulkanPersistentReusePairs += o.persistentBufferReuseHit ? 1 : 0;
        stats.spectraTemporalVulkanReallocatedPairs += o.persistentBufferReallocated ? 1 : 0;
        stats.spectraTemporalVulkanInputPackingMs += o.inputPackingMs;
        stats.spectraTemporalVulkanCoarseKernelMs += o.coarseKernelMs;
        stats.spectraTemporalVulkanCoarseReductionMs += o.coarseReductionMs;
        stats.spectraTemporalVulkanObserverKernelMs += o.observerKernelMs;
        stats.spectraTemporalVulkanStaticFieldKernelMs += o.staticFieldKernelMs;
        stats.spectraTemporalVulkanCompactReductionAndFitMs += o.compactReductionAndFitMs;
        stats.spectraTemporalVulkanSynchronizationMs += o.synchronizationMs;
        stats.spectraTemporalVulkanReadbackMs += o.readbackMs;
        stats.spectraTemporalVulkanTotalMs += o.totalMs;
        stats.spectraTemporalVulkanInputBytes = std::max(stats.spectraTemporalVulkanInputBytes, o.inputBytes);
        stats.spectraTemporalVulkanCompactReadbackBytes = std::max(stats.spectraTemporalVulkanCompactReadbackBytes, o.compactReadbackBytes);
        stats.spectraTemporalVulkanPersistentResidentBytes = std::max(stats.spectraTemporalVulkanPersistentResidentBytes, o.persistentResidentBytes);
        stats.spectraTemporalVulkanStatus = o.status;
        stats.spectraTemporalVulkanFailureReason = o.failureReason.empty() ? "none" : o.failureReason;
        if (!o.valid) continue;
        ++acceptedObservations;
        observerConfidenceSum += o.observerConfidence;
        supportWeightSum += o.supportWeight;
        correlationSum += o.meanTemporalCorrelation;
        alignmentConfidenceSum += o.alignmentConfidence;
        motionConfidenceSum += o.motionConfidence;
        fbSum += o.forwardBackwardConsistency;
        fbMin = std::min(fbMin, static_cast<double>(o.forwardBackwardConsistency));
        repeatedSum += support.repeatedSupportConfidence;
        staticMeanSum += o.staticProbabilityMean; staticP10Sum += o.staticProbabilityP10;
        staticP50Sum += o.staticProbabilityP50; staticP90Sum += o.staticProbabilityP90;
        innovationMeanSum += o.normalizedInnovationMean; innovationVarianceSum += o.normalizedInnovationVariance;
        innovationP90Sum += o.normalizedInnovationP90; innovationLagSum += o.normalizedInnovationLagCorrelation;
        heavyTailSum += o.heavyTailFraction; persistentSum += o.persistentPatternMean;
        fitStabilitySum += support.meanFitStabilityConfidence;
        fitStabilityMin = std::min(fitStabilityMin, static_cast<double>(support.meanFitStabilityConfidence));
        stats.spectraHighConfidenceStaticSamples += o.highConfidenceStaticSamples;
        stats.spectraClippingRejectedSamples += o.clippingRejectedSamples;
        stats.spectraTextureRejectedSamples += o.textureRejectedSamples;
        stats.spectraLocalMotionRejectedSamples += o.localMotionRejectedSamples;
        stats.spectraStaticMapColumns = o.staticProbabilityField.columns;
        stats.spectraStaticMapRows = o.staticProbabilityField.rows;
        stats.spectraStaticMapCellSize = o.staticProbabilityField.cellSize;
        for (int ch=0; ch<4; ++ch) {
            const double w = static_cast<double>(o.samples[ch]);
            if (w <= 0.0) continue;
            channelWeight[ch] += w;
            observed[ch] += o.observedVariance[ch]*w; predicted[ch] += o.predictedVariance[ch]*w;
            scale[ch] += o.adaptationScale[ch]*w; sScale[ch] += o.sAdaptationScale[ch]*w; oScale[ch] += o.oAdaptationScale[ch]*w;
            regression[ch] += o.regressionConfidence[ch]*w; signalSpan[ch] += o.signalSpan[ch]*w; bins[ch] += static_cast<double>(o.populatedSignalBins[ch])*w;
            physical[ch] += o.fitPhysicalScore[ch]*w; fitMean[ch] += o.fitInnovationMean[ch]*w; fitVar[ch] += o.fitInnovationVariance[ch]*w;
            fitCorr[ch] += o.fitResidualCorrelation[ch]*w; fitTail[ch] += o.fitHeavyTailFraction[ch]*w; fitStability[ch] += support.fitStabilityConfidence[ch]*w;
            if (o.fitEstimator[ch] == static_cast<int>(spectra_temporal::TemporalFitEstimator::WEIGHTED_LEAST_SQUARES)) ++wls[ch];
            if (o.fitEstimator[ch] == static_cast<int>(spectra_temporal::TemporalFitEstimator::HUBER_IRLS)) ++huber[ch];
            stats.spectraObserverSamples += static_cast<int>(o.samples[ch]);
        }
    }
    if (acceptedOutput > 0) {
        stats.avgAppliedShiftX = sumDx / acceptedOutput; stats.avgAppliedShiftY = sumDy / acceptedOutput;
        stats.avgPhaseResponse = sumResponse / acceptedOutput; stats.minPhaseResponse = std::isfinite(minResponse) ? minResponse : 0.0; stats.maxPhaseResponse = maxResponse;
    }
    stats.spectraAcceptedFramePairs = spectraEnabled ? acceptedObservations : 0;
    stats.spectraRejectedFramePairs = spectraEnabled ? std::max(0, static_cast<int>(gpu.supports.size()) - acceptedObservations) : 0;
    if (acceptedObservations > 0) {
        const double n = static_cast<double>(acceptedObservations);
        stats.spectraObserverConfidence = std::clamp(observerConfidenceSum/n,0.0,1.0);
        stats.spectraAverageSupportWeight = supportWeightSum/n;
        stats.spectraTemporalCorrelation = static_cast<float>(std::clamp(correlationSum/n,0.0,0.85));
        stats.spectraIndependentNoiseFraction = std::clamp(1.0f-stats.spectraTemporalCorrelation,0.15f,1.0f);
        stats.spectraAlignmentConfidence = static_cast<float>(std::clamp(alignmentConfidenceSum/n,0.0,1.0));
        stats.spectraMotionConfidence = static_cast<float>(std::clamp(motionConfidenceSum/n,0.0,1.0));
        stats.spectraForwardBackwardConsistency = std::clamp(fbSum/n,0.0,1.0);
        stats.spectraForwardBackwardConsistencyMin = std::isfinite(fbMin) ? std::clamp(fbMin,0.0,1.0) : 0.0;
        stats.spectraRepeatedSupportConfidence = std::clamp(repeatedSum/n,0.0,1.0);
        stats.spectraStaticProbabilityMean = std::clamp(staticMeanSum/n,0.0,1.0);
        stats.spectraStaticProbabilityP10 = std::clamp(staticP10Sum/n,0.0,1.0);
        stats.spectraStaticProbabilityP50 = std::clamp(staticP50Sum/n,0.0,1.0);
        stats.spectraStaticProbabilityP90 = std::clamp(staticP90Sum/n,0.0,1.0);
        stats.spectraNormalizedInnovationMean = innovationMeanSum/n;
        stats.spectraNormalizedInnovationVariance = std::max(0.0, innovationVarianceSum/n);
        stats.spectraNormalizedInnovationP90 = std::max(0.0, innovationP90Sum/n);
        stats.spectraNormalizedInnovationLagCorrelation = std::clamp(innovationLagSum/n,-1.0,1.0);
        stats.spectraHeavyTailFraction = std::clamp(heavyTailSum/n,0.0,1.0);
        stats.spectraPersistentPatternFraction = std::clamp(persistentSum/n,0.0,0.95);
        stats.spectraFitStabilityConfidence = std::clamp(fitStabilitySum/n,0.0,1.0);
        stats.spectraFitStabilityMin = std::isfinite(fitStabilityMin) ? std::clamp(fitStabilityMin,0.0,1.0) : 0.0;
        for (int ch=0; ch<4; ++ch) if (channelWeight[ch] > 0.0) {
            stats.spectraObservedVariance[ch]=observed[ch]/channelWeight[ch]; stats.spectraPredictedVariance[ch]=predicted[ch]/channelWeight[ch];
            stats.spectraAdaptationScale[ch]=std::clamp(scale[ch]/channelWeight[ch],0.75,1.25); stats.spectraSAdaptationScale[ch]=std::clamp(sScale[ch]/channelWeight[ch],0.75,1.25); stats.spectraOAdaptationScale[ch]=std::clamp(oScale[ch]/channelWeight[ch],0.75,1.25);
            stats.spectraRegressionConfidence[ch]=std::clamp(regression[ch]/channelWeight[ch],0.0,1.0); stats.spectraSignalSpan[ch]=std::max(0.0,signalSpan[ch]/channelWeight[ch]); stats.spectraRegressionBins[ch]=static_cast<int>(std::lround(bins[ch]/channelWeight[ch]));
            stats.spectraFitPhysicalScore[ch]=std::clamp(physical[ch]/channelWeight[ch],0.0,1.0); stats.spectraFitInnovationMean[ch]=fitMean[ch]/channelWeight[ch]; stats.spectraFitInnovationVariance[ch]=std::max(0.0,fitVar[ch]/channelWeight[ch]);
            stats.spectraFitResidualCorrelation[ch]=std::clamp(fitCorr[ch]/channelWeight[ch],-1.0,1.0); stats.spectraFitHeavyTailFraction[ch]=std::clamp(fitTail[ch]/channelWeight[ch],0.0,1.0); stats.spectraFitStabilityByChannel[ch]=std::clamp(fitStability[ch]/channelWeight[ch],0.0,1.0);
            stats.spectraFitEstimator[ch] = huber[ch] > wls[ch] ? static_cast<int>(spectra_temporal::TemporalFitEstimator::HUBER_IRLS) : (wls[ch] > 0 ? static_cast<int>(spectra_temporal::TemporalFitEstimator::WEIGHTED_LEAST_SQUARES) : static_cast<int>(spectra_temporal::TemporalFitEstimator::NONE));
            if (stats.spectraFitEstimator[ch] == static_cast<int>(spectra_temporal::TemporalFitEstimator::WEIGHTED_LEAST_SQUARES)) ++stats.spectraWlsSelectedChannels;
            if (stats.spectraFitEstimator[ch] == static_cast<int>(spectra_temporal::TemporalFitEstimator::HUBER_IRLS)) ++stats.spectraHuberSelectedChannels;
        }
    }
    if (!fuseSupportFrames && spectraEnabled) {
        stats.spectraFusionVarianceScale = 1.0;
        stats.spectraFusionVarianceP10 = stats.spectraFusionVarianceP50 = stats.spectraFusionVarianceP90 = 1.0;
        stats.spectraEffectiveFrameCount = stats.spectraEffectiveFrameCountP10 = stats.spectraEffectiveFrameCountP50 = stats.spectraEffectiveFrameCountP90 = 1.0;
    }
}

template <typename LockFn, typename UnpackFn>
jobject mergeRawToDngRaw16Internal(
        JNIEnv *env,
        const std::vector<AHardwareBuffer*>& hwBuffers,
        jint cfaPattern,
        jint whiteLevel,
        const std::vector<int32_t>& blackLevels,
        jint maxFramesCap,
        jint maxShiftPixels,
        jfloat alignmentStrictness,
        jint spectraMode,
        bool temporalNoiseModelEnabled,
        bool spectraAdaptiveCalibrationEnabled,
        const std::vector<double>& spectraEffectiveS,
        const std::vector<double>& spectraEffectiveO,
        jfloat spectraModelConfidence,
        bool fuseSupportFrames,
        const std::vector<float>& exposureScaleToAnchor,
        bool computationalHdr,
        int sourceCropLeft,
        int sourceCropTop,
        int sourceCropWidth,
        int sourceCropHeight,
        DngMergeStats* stats,
        const std::string& route,
        LockFn lockFn,
        UnpackFn unpackFn
) {
    const auto totalMergeStart = DngClock::now();
    DngMergeStats localStats{};
    localStats.route = route;
    localStats.framesReceived = static_cast<int>(hwBuffers.size());
    localStats.cfaPattern = cfaPattern;
    localStats.whiteLevel = whiteLevel;
    localStats.payloadWhiteLevel = std::clamp(static_cast<int>(whiteLevel), 1, 65535);

    const int initialNativeWhite = route == "RAW10" ? 1023 : localStats.payloadWhiteLevel;
    SampleDomainTransform inputTransform = makeSampleDomainTransform(
            initialNativeWhite,
            localStats.payloadWhiteLevel,
            blackLevels,
            route == "RAW10"
                    ? "RAW10_BLACK_ANCHORED_TO_DNG_PAYLOAD_DOMAIN"
                    : "RAW_SENSOR_NATIVE_DNG_PAYLOAD_DOMAIN"
    );

    localStats.inputNativeWhiteLevel = inputTransform.nativeWhite;
    localStats.inputNativeBlackLevels = blackLevelsToString(inputTransform.nativeBlack);
    localStats.payloadBlackLevels = blackLevelsToString(inputTransform.payloadBlack);
    localStats.payloadScaleFactor = inputTransform.nominalScale;
    localStats.sampleScaleContract = inputTransform.contract;
    localStats.sampleTransformApplied = !inputTransform.identity;
    localStats.maxFramesCap = std::max(1, static_cast<int>(maxFramesCap));
    localStats.maxShiftPixels = std::max(1, static_cast<int>(maxShiftPixels));
    localStats.alignmentStrictness = clampStrictness(alignmentStrictness);

    SpectraNoiseModel spectraModel{};
    spectraModel.enabled = temporalNoiseModelEnabled &&
            spectraEffectiveS.size() >= 4u && spectraEffectiveO.size() >= 4u;
    spectraModel.adaptiveCalibrationEnabled =
            spectraAdaptiveCalibrationEnabled && spectraMode > 0;
    spectraModel.confidence = std::clamp(
            std::isfinite(spectraModelConfidence) ? static_cast<float>(spectraModelConfidence) : 0.0f,
            0.0f,
            1.0f
    );
    for (int ch = 0; ch < 4; ++ch) {
        spectraModel.effectiveS[ch] = spectraEffectiveS.size() > static_cast<size_t>(ch)
                ? spectraEffectiveS[static_cast<size_t>(ch)] : 0.0;
        spectraModel.effectiveO[ch] = spectraEffectiveO.size() > static_cast<size_t>(ch)
                ? spectraEffectiveO[static_cast<size_t>(ch)] : 0.0;
    }
    double meanReferenceVariance = 0.0;
    int pressureChannels = 0;
    for (int ch = 0; ch < 4; ++ch) {
        if (!std::isfinite(spectraModel.effectiveS[ch]) ||
            !std::isfinite(spectraModel.effectiveO[ch]) ||
            spectraModel.effectiveS[ch] <= 0.0 || spectraModel.effectiveO[ch] < 0.0) continue;
        meanReferenceVariance += spectraReferenceVariance(spectraModel, ch);
        pressureChannels++;
    }
    meanReferenceVariance = pressureChannels > 0
            ? meanReferenceVariance / static_cast<double>(pressureChannels)
            : 0.0;
    const double logVariance = std::log2(std::max(1.0e-12, meanReferenceVariance));
    const double pressureT = std::clamp((logVariance + 16.5) / 8.0, 0.0, 1.0);
    spectraModel.noisePressure = static_cast<float>(pressureT * pressureT * (3.0 - 2.0 * pressureT));
    spectraModel.temporalAuthority = std::clamp(
            (0.15f + 0.80f * spectraModel.noisePressure) * spectraModel.confidence,
            0.0f,
            0.95f
    );
    const double allowedDrift = 0.08 + 0.17 * static_cast<double>(spectraModel.noisePressure);
    if (spectraModel.adaptiveCalibrationEnabled) {
        spectraModel.adaptationLowerBound = 1.0 - allowedDrift;
        spectraModel.adaptationUpperBound = 1.0 + allowedDrift;
    } else {
        // Physical Camera2 S/O is immutable in SPECTRA-Off baseline. The observer remains
        // useful for motion/static classification but cannot become a second calibration authority.
        spectraModel.adaptationLowerBound = 1.0;
        spectraModel.adaptationUpperBound = 1.0;
    }
    spectraModel.enabled = validSpectraNoiseModel(spectraModel);
    localStats.spectraEnabled = spectraModel.enabled;
    localStats.temporalNoiseModelEnabled = spectraModel.enabled;
    localStats.spectraAdaptiveCalibrationEnabled =
            spectraModel.enabled && spectraModel.adaptiveCalibrationEnabled;
    localStats.temporalNoiseModelAuthority = !spectraModel.enabled
            ? "DISABLED"
            : (spectraModel.adaptiveCalibrationEnabled
                    ? "SPECTRA_ADAPTIVE_SO"
                    : "PHYSICAL_CAMERA2_FIXED_SO");
    localStats.spectraModelConfidence = spectraModel.confidence;
    localStats.spectraNoisePressure = spectraModel.noisePressure;
    localStats.spectraTemporalAuthority = spectraModel.temporalAuthority;
    localStats.spectraObserverOnly = spectraModel.enabled && !fuseSupportFrames;

    auto appendProvenance = [&](const std::string& entry) {
        if (localStats.supportFrameProvenance == "none") {
            localStats.supportFrameProvenance = entry;
        } else {
            localStats.supportFrameProvenance += "|" + entry;
        }
    };

    auto copyStats = [&]() {
        localStats.totalNativeDngMergeMs = elapsedDngMs(totalMergeStart);
        if (stats != nullptr) *stats = localStats;
    };

    if (hwBuffers.empty()) {
        localStats.failureReason = "no buffers supplied";
        copyStats();
        return nullptr;
    }

    const int useCount = std::min<int>(static_cast<int>(hwBuffers.size()), localStats.maxFramesCap);
    const size_t startIndex = hwBuffers.size() - static_cast<size_t>(useCount);
    std::vector<AHardwareBuffer*> selected;
    selected.reserve(static_cast<size_t>(useCount));
    for (size_t i = startIndex; i < hwBuffers.size(); ++i) selected.push_back(hwBuffers[i]);
    std::vector<float> selectedExposureScale(selected.size(), 1.0f);
    if (exposureScaleToAnchor.size() == hwBuffers.size()) {
        for (size_t i = 0; i < selected.size(); ++i) {
            const float value = exposureScaleToAnchor[startIndex + i];
            selectedExposureScale[i] = std::isfinite(value) && value > 0.0f
                    ? std::clamp(value, 0.0625f, 16.0f) : 1.0f;
        }
    }
    if (computationalHdr && selected.size() > 1u && selectedExposureScale.size() != selected.size()) {
        localStats.failureReason = "invalid HDR exposure-scale contract";
        copyStats();
        return nullptr;
    }

    localStats.framesUsedForDng = static_cast<int>(selected.size());
    localStats.mergeAttempted = selected.size() > 1;

    AHardwareBuffer *anchor = selected.back();

    struct DecodedSourceLayout {
        uint32_t width = 0u;
        uint32_t height = 0u;
        uint32_t rowStrideBytes = 0u;
        uint32_t pixelStrideBytes = 0u;
    };

    auto aggregateRoute = [](std::string& aggregate, const std::string& value) {
        if (value.empty() || value == "NOT_ATTEMPTED") return;
        if (aggregate == "NOT_ATTEMPTED") aggregate = value;
        else if (aggregate != value && aggregate != "MIXED") aggregate = "MIXED";
    };

    std::uint64_t singleFrameResidentGeneration = 0u;

    auto decodeFrame = [&](AHardwareBuffer* buffer, cv::Mat& output, DecodedSourceLayout& layout) -> bool {
        bncam::vulkan::RawCaptureCanonicalizeRequest request{};
        request.hardwareBuffer = buffer;
        request.sourceFormat = route == "RAW10"
                ? bncam::vulkan::RawCaptureSourceFormat::RAW10
                : bncam::vulkan::RawCaptureSourceFormat::RAW_SENSOR;
        request.cropLeft = static_cast<uint32_t>(sourceCropLeft);
        request.cropTop = static_cast<uint32_t>(sourceCropTop);
        request.cropWidth = static_cast<uint32_t>(sourceCropWidth);
        request.cropHeight = static_cast<uint32_t>(sourceCropHeight);
        request.nativeWhite = static_cast<uint32_t>(inputTransform.nativeWhite);
        request.payloadWhite = static_cast<uint32_t>(inputTransform.payloadWhite);
        for (size_t ch = 0; ch < 4u; ++ch) {
            request.nativeBlack[ch] = static_cast<uint32_t>(inputTransform.nativeBlack[ch]);
            request.payloadBlack[ch] = static_cast<uint32_t>(inputTransform.payloadBlack[ch]);
        }
        // Keep single-frame generations in a namespace disjoint from Phase-9 multi-frame IDs.
        request.generationId = 0x8000000000000000ull | (static_cast<std::uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(
                        DngClock::now().time_since_epoch()).count()) & 0x7fffffffffffffffull);

        const auto gpu = bncam::vulkan::VulkanRuntime::instance().executeRawCaptureCanonicalize(request);
        if (gpu.success && gpu.width == static_cast<uint32_t>(sourceCropWidth) &&
            gpu.height == static_cast<uint32_t>(sourceCropHeight) &&
            gpu.outputRaw16.size() == static_cast<size_t>(sourceCropWidth) * static_cast<size_t>(sourceCropHeight)) {
            output = cv::Mat(sourceCropHeight, sourceCropWidth, CV_16UC1);
            std::memcpy(output.ptr<uint16_t>(0), gpu.outputRaw16.data(),
                        gpu.outputRaw16.size() * sizeof(uint16_t));
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(buffer, &desc);
            layout.width = desc.width;
            layout.height = desc.height;
            layout.rowStrideBytes = gpu.sourceRowStrideBytes;
            layout.pixelStrideBytes = gpu.sourcePixelStrideBytes;
            aggregateRoute(localStats.inputImportPath, gpu.inputImportPath);
            aggregateRoute(localStats.rawUnpackBackend, gpu.rawUnpackBackend);
            localStats.rawUnpackVulkanFrames++;
            localStats.fullFrameCpuUploadBytes += gpu.cpuStagingCopyBytes;
            localStats.fullFrameGpuReadbackBytes += gpu.fullFrameGpuReadbackBytes;
            localStats.rawUnpackTransferMs += gpu.inputStagingMs;
            localStats.rawUnpackGpuMs += gpu.gpuKernelAndSyncMs;
            localStats.rawUnpackReadbackMs += gpu.readbackMs;
            if (selected.size() == 1u && buffer == anchor && gpu.residentOutputProduced) {
                singleFrameResidentGeneration = gpu.residentOutputGeneration;
            }
            return true;
        }

        // Explicit bounded fallback only after the authoritative Vulkan route reported failure.
        if (selected.size() == 1u && buffer == anchor) singleFrameResidentGeneration = 0u;
        localStats.cpuFallbackUsed = true;
        localStats.cpuFullFrameRawUnpack = true;
        localStats.rawUnpackCpuFallbackFrames++;
        const std::string frameFallbackReason = gpu.success
                ? "VULKAN_RAW_UNPACK_OUTPUT_CONTRACT_MISMATCH"
                : ((gpu.failureReason.empty() || gpu.failureReason == "none")
                        ? "VULKAN_RAW_UNPACK_FAILED"
                        : gpu.failureReason);
        if (localStats.cpuFallbackReason == "none") {
            localStats.cpuFallbackReason = frameFallbackReason;
        }
        aggregateRoute(localStats.inputImportPath, "CPU_AHB_REFERENCE_FALLBACK");
        aggregateRoute(localStats.rawUnpackBackend, "CPU_REFERENCE_FALLBACK");
        DNG_LOGW("%s RAW canonicalize Vulkan failure -> bounded CPU reference fallback: %s",
                 route.c_str(), frameFallbackReason.c_str());

        LockedRawBuffer locked{};
        if (!lockFn(buffer, locked)) return false;
        layout.width = locked.width;
        layout.height = locked.height;
        layout.rowStrideBytes = locked.rowStrideBytes;
        layout.pixelStrideBytes = locked.pixelStrideBytes;
        const bool decoded = unpackFn(locked, inputTransform, sourceCropLeft, sourceCropTop,
                                      sourceCropWidth, sourceCropHeight, output);
        unlockRawBuffer(buffer, locked);
        return decoded;
    };

    try {
        DNG_LOGI("%s DNG merge started: framesReceived=%zu framesUsed=%zu cap=%d maxShift=%d strictness=%.3f",
                 route.c_str(), hwBuffers.size(), selected.size(), localStats.maxFramesCap, localStats.maxShiftPixels, localStats.alignmentStrictness);

        AHardwareBuffer_Desc anchorDesc{};
        AHardwareBuffer_describe(anchor, &anchorDesc);
        if (sourceCropLeft < 0 || sourceCropTop < 0 || sourceCropWidth <= 0 || sourceCropHeight <= 0 ||
            sourceCropLeft + sourceCropWidth > static_cast<int>(anchorDesc.width) ||
            sourceCropTop + sourceCropHeight > static_cast<int>(anchorDesc.height)) {
            localStats.failureReason = "invalid source crop";
            copyStats();
            return nullptr;
        }
        localStats.width = static_cast<uint32_t>(sourceCropWidth);
        localStats.height = static_cast<uint32_t>(sourceCropHeight);
        const uint64_t framePixels = static_cast<uint64_t>(sourceCropWidth) * static_cast<uint64_t>(sourceCropHeight);
        // GPU Migration Phase 9: multi-frame RAW success stays resident from canonicalization
        // through alignment/temporal observation/fusion. One fused RAW16 downstream bridge remains
        // until Phase 10 makes the SPECTRA/ISP entry resident. The CPU/OpenCV block below is reached
        // only after explicit Vulkan failure.
        if (selected.size() > 1u) {
            bncam::vulkan::RawMultiFrameRequest gpuRequest{};
            gpuRequest.frames = selected;
            gpuRequest.sourceFormat = route == "RAW10"
                    ? bncam::vulkan::RawCaptureSourceFormat::RAW10
                    : bncam::vulkan::RawCaptureSourceFormat::RAW_SENSOR;
            gpuRequest.cropLeft = static_cast<uint32_t>(sourceCropLeft);
            gpuRequest.cropTop = static_cast<uint32_t>(sourceCropTop);
            gpuRequest.cropWidth = static_cast<uint32_t>(sourceCropWidth);
            gpuRequest.cropHeight = static_cast<uint32_t>(sourceCropHeight);
            gpuRequest.nativeWhite = static_cast<uint32_t>(inputTransform.nativeWhite);
            gpuRequest.payloadWhite = static_cast<uint32_t>(inputTransform.payloadWhite);
            for (size_t ch = 0; ch < 4u; ++ch) {
                gpuRequest.nativeBlack[ch] = static_cast<uint32_t>(inputTransform.nativeBlack[ch]);
                gpuRequest.payloadBlack[ch] = static_cast<uint32_t>(inputTransform.payloadBlack[ch]);
                gpuRequest.spectra.effectiveS[ch] = spectraModel.effectiveS[ch];
                gpuRequest.spectra.effectiveO[ch] = spectraModel.effectiveO[ch];
            }
            gpuRequest.cfaPattern = static_cast<uint32_t>(cfaPattern);
            gpuRequest.maxShiftPixels = static_cast<uint32_t>(localStats.maxShiftPixels);
            gpuRequest.alignmentStrictness = localStats.alignmentStrictness;
            gpuRequest.fuseSupportFrames = fuseSupportFrames;
            gpuRequest.exposureScaleToAnchor = selectedExposureScale;
            gpuRequest.computationalHdr = computationalHdr;
            gpuRequest.spectra.enabled = spectraModel.enabled;
            gpuRequest.spectra.confidence = spectraModel.confidence;
            gpuRequest.spectra.noisePressure = spectraModel.noisePressure;
            gpuRequest.spectra.temporalAuthority = spectraModel.temporalAuthority;
            gpuRequest.spectra.adaptationLowerBound = spectraModel.adaptationLowerBound;
            gpuRequest.spectra.adaptationUpperBound = spectraModel.adaptationUpperBound;
            gpuRequest.generationId = static_cast<uint64_t>(
                    std::chrono::duration_cast<std::chrono::microseconds>(
                            DngClock::now().time_since_epoch()).count());

            auto gpuMulti = bncam::vulkan::VulkanRuntime::instance().executeRawMultiFrame(gpuRequest);
            if (gpuMulti.success &&
                gpuMulti.width == static_cast<uint32_t>(sourceCropWidth) &&
                gpuMulti.height == static_cast<uint32_t>(sourceCropHeight) &&
                gpuMulti.outputRaw16.size() == static_cast<size_t>(sourceCropWidth) * static_cast<size_t>(sourceCropHeight)) {
                applyGpuRawMultiFrameStats(localStats, gpuMulti, spectraModel.enabled, fuseSupportFrames);
                localStats.rowStrideBytes = gpuMulti.anchorSourceRowStrideBytes;
                localStats.pixelStrideBytes = gpuMulti.anchorSourcePixelStrideBytes;
                // Phase 9: publication aliases the moved Vulkan result vector; no second RAW16 payload exists.
                localStats.peakWorkingSetEstimateBytes = framePixels * 2u; // one native publication owner.
                if (route == "RAW10") {
                    localStats.raw10ExpectedRowStride = packedRaw10RowBytes(anchorDesc.width);
                    localStats.raw10ActualRowStride = gpuMulti.anchorSourceRowStrideBytes;
                    localStats.raw10StrideMatchesExpected = localStats.raw10ActualRowStride == localStats.raw10ExpectedRowStride;
                    localStats.sourceStrideValid = localStats.raw10ActualRowStride >= localStats.raw10ExpectedRowStride;
                    localStats.sourcePixelStrideValid = gpuMulti.anchorSourcePixelStrideBytes == 0u;
                    localStats.raw10PaddingBytesPerRow = localStats.raw10ActualRowStride > localStats.raw10ExpectedRowStride
                            ? localStats.raw10ActualRowStride - localStats.raw10ExpectedRowStride : 0u;
                    localStats.raw10MalformedRowCount = localStats.raw10ActualRowStride < localStats.raw10ExpectedRowStride
                            ? anchorDesc.height : 0u;
                } else {
                    localStats.rawSensorMinimumRowBytes = anchorDesc.width > 0u
                            ? (anchorDesc.width - 1u) * gpuMulti.anchorSourcePixelStrideBytes + 2u : 0u;
                    localStats.rawSensorActualRowStride = gpuMulti.anchorSourceRowStrideBytes;
                    localStats.rawSensorPixelStride = gpuMulti.anchorSourcePixelStrideBytes;
                    localStats.rawSensorPaddingBytesPerRow = localStats.rawSensorActualRowStride > localStats.rawSensorMinimumRowBytes
                            ? localStats.rawSensorActualRowStride - localStats.rawSensorMinimumRowBytes : 0u;
                    localStats.sourceStrideValid = localStats.rawSensorActualRowStride >= localStats.rawSensorMinimumRowBytes;
                    localStats.sourcePixelStrideValid = localStats.rawSensorPixelStride >= 2u;
                }
                for (size_t i = 0; i < gpuMulti.supports.size(); ++i) {
                    const auto& support = gpuMulti.supports[i];
                    appendProvenance(
                            "support[" + std::to_string(i) + "]=" +
                            (support.acceptedForFusion ? "accepted_for_gpu_fusion" : support.rejectReason) +
                            ":estimatedShift=(" + formatDouble(support.estimatedShiftX, 2) + "," +
                            formatDouble(support.estimatedShiftY, 2) + "),appliedEvenShift=(" +
                            std::to_string(support.applyDx) + "," + std::to_string(support.applyDy) +
                            "),response=" + formatDouble(support.phaseResponse, 4) +
                            ",reverseShift=(" + formatDouble(support.reverseEstimatedShiftX, 2) + "," +
                            formatDouble(support.reverseEstimatedShiftY, 2) + ")" +
                            ",reverseResponse=" + formatDouble(support.reversePhaseResponse, 4) +
                            ",closureErrorPx=" + formatDouble(support.forwardBackwardClosureErrorPixels, 2) +
                            ",forwardBackward=" + formatDouble(support.forwardBackwardConsistency, 4) +
                            ",exposureScaleToAnchor=" + formatDouble(support.exposureScaleToAnchor, 4) +
                            ",hdrRole=" + (support.hdrHighlightAuthority ? "highlight" :
                                (support.hdrShadowAuthority ? "shadow" :
                                    (support.hdrTemporalMainAuthority ? "main_temporal" : "none"))) +
                            ",fusionContributedPixels=" + std::to_string(support.fusionContributedPixels) +
                            ",backend=VULKAN_RESIDENT");
                }
                localStats.failureReason = fuseSupportFrames && gpuMulti.supportAccepted == 0
                        ? "all support frames rejected; anchor DNG RAW16 used"
                        : "none";

                cv::Mat finalView(sourceCropHeight, sourceCropWidth, CV_16UC1,
                                  const_cast<uint16_t*>(gpuMulti.outputRaw16.data()));
                const Raw16SampleStats finalStats = computeRaw16SampleStats(finalView);
                // Full-frame min/max/saturation were reduced during Vulkan finalize. Do not rescan
                // the publication buffer on CPU in the normal GPU multi-frame success path.
                localStats.sampledRaw16Min = finalStats.minValue;
                localStats.sampledRaw16P01 = finalStats.p01;
                localStats.sampledRaw16P50 = finalStats.p50;
                localStats.sampledRaw16P95 = finalStats.p95;
                localStats.sampledRaw16P99 = finalStats.p99;
                localStats.sampledRaw16Max = finalStats.maxValue;
                localStats.fullRaw16Min = gpuMulti.finalRawMin;
                localStats.fullRaw16Max = gpuMulti.finalRawMax;
                localStats.fullRaw16SaturatedCount = gpuMulti.finalRawSaturatedCount;
                localStats.fullRaw16SaturatedPct = gpuMulti.finalRawSaturatedPct;
                localStats.raw16Min = gpuMulti.finalRawMin;
                localStats.raw16P01 = finalStats.p01;
                localStats.raw16P50 = finalStats.p50;
                localStats.raw16P95 = finalStats.p95;
                localStats.raw16P99 = finalStats.p99;
                localStats.raw16Max = gpuMulti.finalRawMax;
                const size_t totalBytes = gpuMulti.outputRaw16.size() * sizeof(uint16_t);
                localStats.raw16Bytes = totalBytes;
                const auto outputStart = DngClock::now();
                const std::uint64_t residentGeneration = gpuMulti.residentOutputProduced
                        ? gpuMulti.residentOutputGeneration : 0u;
                // Move the already-materialized Vulkan result storage into the deterministic JNI owner.
                // This removes the former second full-frame allocation + memcpy while preserving a
                // valid host RAW16 for compact planning, explicit CPU fail-safe and DNG materialization.
                auto* vectorOwner = new (std::nothrow) std::vector<std::uint16_t>(std::move(gpuMulti.outputRaw16));
                void* nativePayload = vectorOwner != nullptr && !vectorOwner->empty()
                        ? static_cast<void*>(vectorOwner->data()) : nullptr;
                if (nativePayload != nullptr &&
                    trackNativeRaw16VectorAllocation(vectorOwner, residentGeneration)) {
                    jobject directBuffer = env->NewDirectByteBuffer(nativePayload, static_cast<jlong>(totalBytes));
                    if (directBuffer != nullptr) {
                        localStats.outputArrayMs = elapsedDngMs(outputStart);
                        localStats.totalNativeDngMergeMs = elapsedDngMs(totalMergeStart);
                        localStats.nativeRaw16OutstandingBuffersAtReturn = trackedNativeRaw16AllocationCount();
                        copyStats();
                        return directBuffer;
                    }
                    releaseTrackedNativeRaw16Allocation(nativePayload);
                    env->ExceptionClear();
                } else if (vectorOwner != nullptr) {
                    delete vectorOwner;
                }
                localStats.failureReason = "GPU fused RAW16 publication ownership transfer failed";
                copyStats();
                return nullptr;
            }

            // Computational HDR is never reinterpreted as an equal-exposure CPU merge. If the
            // authoritative Vulkan HDR transaction fails, return failure so Kotlin can perform
            // the explicit anchor-only fail-safe without fabricating HDR semantics.
            if (computationalHdr) {
                const std::string reason = gpuMulti.failureReason.empty() || gpuMulti.failureReason == "none"
                        ? "VULKAN_RAW_HDR_FAILED" : gpuMulti.failureReason;
                localStats.failureReason = "HDR_VULKAN_REQUIRED_" + reason;
                localStats.cpuFallbackUsed = false;
                localStats.cpuAlignment = false;
                localStats.cpuFusion = false;
                copyStats();
                return nullptr;
            }

            // Explicit fallback/reference path. A completed/clean Vulkan failure may use the old
            // CPU/OpenCV implementation for this capture, but the fallback is visible and never a
            // normal performance choice.
            localStats.cpuFallbackUsed = true;
            localStats.cpuAlignment = true;
            localStats.cpuFusion = fuseSupportFrames;
            localStats.cpuFullFrameSupportMaterialization = true;
            localStats.alignmentBackend = "CPU_REFERENCE_FALLBACK";
            localStats.fusionBackend = fuseSupportFrames ? "CPU_REFERENCE_FALLBACK" : "OBSERVER_ONLY_CPU_REFERENCE_FALLBACK";
            localStats.compactGpuReadbackBytes += gpuMulti.compactGpuReadbackBytes;
            localStats.fullFrameCpuUploadBytes += gpuMulti.fullFrameCpuUploadBytes;
            localStats.fullFrameGpuReadbackBytes += gpuMulti.fullFrameGpuReadbackBytes;
            localStats.rawAlignmentGpuMs += gpuMulti.alignmentGpuMs;
            localStats.rawFusionGpuMs += gpuMulti.fusionGpuMs;
            const std::string reason = gpuMulti.failureReason.empty() || gpuMulti.failureReason == "none"
                    ? "VULKAN_RAW_MULTIFRAME_FAILED" : gpuMulti.failureReason;
            if (localStats.cpuFallbackReason == "none") localStats.cpuFallbackReason = reason;
            DNG_LOGW("%s RAW multi-frame Vulkan failure -> explicit CPU reference fallback: %s",
                     route.c_str(), reason.c_str());
        }

        // CPU fallback/reference working set. This branch is no longer the normal multi-frame success path.
        localStats.peakWorkingSetEstimateBytes = framePixels * (hwBuffers.size() > 1u
                ? (spectraModel.enabled ? 28u : 24u)
                : 4u);

        cv::Mat anchorBayer16;
        DecodedSourceLayout anchorLayout{};
        auto stageStart = DngClock::now();
        if (!decodeFrame(anchor, anchorBayer16, anchorLayout)) {
            localStats.failureReason = "anchor canonicalize failed after fallback";
            copyStats();
            return nullptr;
        }
        localStats.anchorUnpackMs = elapsedDngMs(stageStart);
        localStats.framesDecoded = 1;
        localStats.rowStrideBytes = anchorLayout.rowStrideBytes;
        localStats.pixelStrideBytes = anchorLayout.pixelStrideBytes;
        if (route == "RAW10") {
            localStats.raw10ExpectedRowStride = packedRaw10RowBytes(anchorLayout.width);
            localStats.raw10ActualRowStride = anchorLayout.rowStrideBytes;
            localStats.raw10StrideMatchesExpected = anchorLayout.rowStrideBytes == localStats.raw10ExpectedRowStride;
            localStats.sourceStrideValid = anchorLayout.rowStrideBytes >= localStats.raw10ExpectedRowStride;
            localStats.sourcePixelStrideValid = anchorLayout.pixelStrideBytes == 0u;
            localStats.raw10PaddingBytesPerRow = anchorLayout.rowStrideBytes > localStats.raw10ExpectedRowStride
                    ? anchorLayout.rowStrideBytes - localStats.raw10ExpectedRowStride
                    : 0u;
            localStats.raw10MalformedRowCount = anchorLayout.rowStrideBytes < localStats.raw10ExpectedRowStride
                    ? anchorLayout.height
                    : 0u;
        } else {
            localStats.rawSensorMinimumRowBytes = anchorLayout.width > 0u
                    ? (anchorLayout.width - 1u) * anchorLayout.pixelStrideBytes + 2u
                    : 0u;
            localStats.rawSensorActualRowStride = anchorLayout.rowStrideBytes;
            localStats.rawSensorPixelStride = anchorLayout.pixelStrideBytes;
            localStats.rawSensorPaddingBytesPerRow = anchorLayout.rowStrideBytes > localStats.rawSensorMinimumRowBytes
                    ? anchorLayout.rowStrideBytes - localStats.rawSensorMinimumRowBytes
                    : 0u;
            localStats.sourceStrideValid = anchorLayout.rowStrideBytes >= localStats.rawSensorMinimumRowBytes;
            localStats.sourcePixelStrideValid = anchorLayout.pixelStrideBytes >= 2u;
        }

        Raw16SampleStats unpackedStats = computeRaw16SampleStats(anchorBayer16);
        localStats.unpackedMin = unpackedStats.minValue;
        localStats.unpackedP01 = unpackedStats.p01;
        localStats.unpackedP50 = unpackedStats.p50;
        localStats.unpackedP99 = unpackedStats.p99;
        localStats.unpackedMax = unpackedStats.maxValue;

        // Stage B Non-Destructive Column Statistics Audit (100% Full-Frame)
        int firstNonZeroCol = -1;
        int lastNonZeroCol = -1;
        uint64_t sum0_383 = 0;
        uint64_t nonZero0_383 = 0;
        uint64_t sum384 = 0;
        uint64_t nonZero384 = 0;
        const int checkBoundary = std::min(384, anchorBayer16.cols);

        for (int y = 0; y < anchorBayer16.rows; ++y) {
            const uint16_t* ptr = anchorBayer16.ptr<uint16_t>(y);
            for (int x = 0; x < anchorBayer16.cols; ++x) {
                uint16_t val = ptr[x];
                if (val > 0) {
                    if (firstNonZeroCol < 0 || x < firstNonZeroCol) firstNonZeroCol = x;
                    if (x > lastNonZeroCol) lastNonZeroCol = x;
                }
                if (x < checkBoundary) {
                    sum0_383 += val;
                    if (val > 0) nonZero0_383++;
                } else {
                    sum384 += val;
                    if (val > 0) nonZero384++;
                }
            }
        }
        double mean0_383 = static_cast<double>(sum0_383) / std::max(1.0, static_cast<double>(anchorBayer16.rows * checkBoundary));
        double mean384 = static_cast<double>(sum384) / std::max(1.0, static_cast<double>(anchorBayer16.rows * (anchorBayer16.cols - checkBoundary)));

        DNG_LOGI("STAGE_COLUMN_AUDIT stage=Stage-B (Unpacked RAW) dimensions=%dx%d evalMode=FULL_FRAME_100_PERCENT firstNonZeroCol=%d lastNonZeroCol=%d col0_383Mean=%.2f col0_383NonZero=%" PRIu64 " col384_EndMean=%.2f col384_EndNonZero=%" PRIu64,
                 anchorBayer16.cols, anchorBayer16.rows, firstNonZeroCol, lastNonZeroCol, mean0_383, nonZero0_383, mean384, nonZero384);

        cv::Mat finalBayer16;
        if (selected.size() > 1) {
            cv::Mat anchor32;
            anchorBayer16.convertTo(anchor32, CV_32F);
            cv::Mat accum32(anchor32.size(), CV_32F);
            cv::Mat weight32(anchor32.size(), CV_32F);
            cv::Mat weightSq32(anchor32.size(), CV_32F);
            const bool spectraWeightingActive = validSpectraNoiseModel(spectraModel);
            cv::Mat correlationWeighted32;
            if (spectraWeightingActive) {
                correlationWeighted32 = cv::Mat(anchor32.size(), CV_32F);
            }
            cv::parallel_for_(cv::Range(0, anchor32.rows), [&](const cv::Range& range) {
                for (int y = range.start; y < range.end; ++y) {
                    const float* anchorRow = anchor32.ptr<float>(y);
                    float* accumRow = accum32.ptr<float>(y);
                    float* weightRow = weight32.ptr<float>(y);
                    float* weightSqRow = weightSq32.ptr<float>(y);
                    float* correlationRow = spectraWeightingActive
                            ? correlationWeighted32.ptr<float>(y)
                            : nullptr;
                    for (int x = 0; x < anchor32.cols; ++x) {
                        float anchorWeight = 1.0f;
                        if (spectraWeightingActive) {
                            const int ch = spectraCfaChannel(cfaPattern, x, y);
                            const double signal = spectraNormalizedSignal(
                                    anchorRow[x],
                                    x,
                                    y,
                                    localStats.payloadWhiteLevel,
                                    inputTransform.payloadBlack
                            );
                            const double variance = std::max(
                                    1.0e-12,
                                    spectraModel.effectiveS[ch] * signal + spectraModel.effectiveO[ch]
                            );
                            const float inverseVarianceWeight = static_cast<float>(std::clamp(
                                    spectraReferenceVariance(spectraModel, ch) / variance,
                                    0.25,
                                    4.0
                            ));
                            anchorWeight = 1.0f +
                                    spectraModel.confidence * spectraModel.temporalAuthority *
                                    (inverseVarianceWeight - 1.0f);
                        }
                        accumRow[x] = anchorRow[x] * anchorWeight;
                        weightRow[x] = anchorWeight;
                        weightSqRow[x] = anchorWeight * anchorWeight;
                        if (correlationRow != nullptr) correlationRow[x] = 0.0f;
                    }
                }
            });

            std::array<double, 4> spectraObservedWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraPredictedWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraScaleWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraSScaleWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraOScaleWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraRegressionConfidenceWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraSignalSpanWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraRegressionBinsWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraFitPhysicalScoreWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraFitInnovationMeanWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraFitInnovationVarianceWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraFitResidualCorrelationWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<double, 4> spectraFitHeavyTailWeighted{0.0, 0.0, 0.0, 0.0};
            std::array<int, 4> spectraWlsVotes{0, 0, 0, 0};
            std::array<int, 4> spectraHuberVotes{0, 0, 0, 0};
            std::array<double, 4> spectraChannelWeight{0.0, 0.0, 0.0, 0.0};
            double spectraConfidenceSum = 0.0;
            double spectraSupportWeightSum = 0.0;
            double spectraCorrelationSum = 0.0;
            double spectraAlignmentConfidenceSum = 0.0;
            double spectraMotionConfidenceSum = 0.0;
            int spectraAcceptedObservations = 0;
            SpectraStaticConsensus spectraStaticConsensus{};
            SpectraFitConsensus spectraFitConsensus{};
            double spectraForwardBackwardSum = 0.0;
            double spectraForwardBackwardMin = std::numeric_limits<double>::infinity();
            double spectraRepeatedSupportSum = 0.0;
            double spectraStaticMeanSum = 0.0;
            double spectraStaticP10Sum = 0.0;
            double spectraStaticP50Sum = 0.0;
            double spectraStaticP90Sum = 0.0;
            double spectraInnovationMeanSum = 0.0;
            double spectraInnovationVarianceSum = 0.0;
            double spectraInnovationP90Sum = 0.0;
            double spectraInnovationLagSum = 0.0;
            double spectraHeavyTailSum = 0.0;
            double spectraPersistentPatternSum = 0.0;
            double spectraFitStabilitySum = 0.0;
            double spectraFitStabilityMin = std::numeric_limits<double>::infinity();
            std::array<double, 4> spectraFitStabilityWeighted{0.0, 0.0, 0.0, 0.0};

            double alignmentScale = 1.0;
            stageStart = DngClock::now();
            cv::Mat anchorAlign = buildAlignmentImage(anchorBayer16, 640, alignmentScale, localStats.whiteLevel);
            localStats.alignmentMs += elapsedDngMs(stageStart);

            double sumDx = 0.0;
            double sumDy = 0.0;
            double sumResponse = 0.0;
            double minResponse = std::numeric_limits<double>::infinity();
            double maxResponse = 0.0;

            for (size_t i = 0; i + 1 < selected.size(); ++i) {
                AHardwareBuffer *support = selected[i];
                cv::Mat supportBayer16;
                DecodedSourceLayout supportLayout{};
                stageStart = DngClock::now();
                const bool decoded = decodeFrame(support, supportBayer16, supportLayout);
                localStats.supportUnpackMs += elapsedDngMs(stageStart);

                if (!decoded || supportBayer16.size() != anchorBayer16.size()) {
                    localStats.supportRejected++;
                    appendProvenance("support[" + std::to_string(i) + "]=rejected:decode_or_size_mismatch");
                    continue;
                }
                localStats.framesDecoded++;

                double supportScale = 1.0;
                stageStart = DngClock::now();
                cv::Mat supportAlign = buildAlignmentImage(supportBayer16, 640, supportScale, localStats.whiteLevel);
                if (std::abs(supportScale - alignmentScale) > 1e-6) {
                    localStats.supportRejected++;
                    appendProvenance("support[" + std::to_string(i) + "]=rejected:alignment_scale_mismatch");
                    continue;
                }

                AlignmentDecision decision = estimateAlignment(
                        anchorAlign,
                        supportAlign,
                        alignmentScale,
                        localStats.maxShiftPixels,
                        localStats.alignmentStrictness
                );
                localStats.alignmentMs += elapsedDngMs(stageStart);
                if (!decision.accepted) {
                    localStats.supportRejected++;
                    appendProvenance("support[" + std::to_string(i) + "]=rejected:" + decision.rejectReason);
                    DNG_LOGW("%s DNG support frame %zu rejected: %s shift=(%.2f, %.2f) response=%.4f",
                             route.c_str(), i, decision.rejectReason.c_str(), decision.estimatedShiftX, decision.estimatedShiftY, decision.phaseResponse);
                    continue;
                }

                if (spectraModel.enabled) {
                    stageStart = DngClock::now();
                    const AlignmentDecision reverseDecision = estimateAlignment(
                            supportAlign,
                            anchorAlign,
                            alignmentScale,
                            localStats.maxShiftPixels,
                            localStats.alignmentStrictness
                    );
                    localStats.spectraForwardBackwardAlignmentMs += elapsedDngMs(stageStart);
                    decision.forwardBackwardConsistency = reverseDecision.accepted
                            ? spectra_temporal::forwardBackwardConsistency(
                                    decision.estimatedShiftX,
                                    decision.estimatedShiftY,
                                    reverseDecision.estimatedShiftX,
                                    reverseDecision.estimatedShiftY,
                                    decision.phaseResponse,
                                    reverseDecision.phaseResponse
                            )
                            : 0.0f;
                } else {
                    decision.forwardBackwardConsistency = 1.0f;
                }
                if (spectraModel.enabled && decision.forwardBackwardConsistency < 0.08f) {
                    localStats.supportRejected++;
                    localStats.spectraForwardBackwardRejectedPairs++;
                    appendProvenance(
                            "support[" + std::to_string(i) +
                            "]=rejected:forward_backward_inconsistent,score=" +
                            formatDouble(decision.forwardBackwardConsistency, 4)
                    );
                    continue;
                }

                stageStart = DngClock::now();
                SpectraSupportObservation spectraObservation = analyzeAlignedSupportNoise(
                        anchor32,
                        supportBayer16,
                        decision,
                        localStats.alignmentStrictness,
                        localStats.payloadWhiteLevel,
                        inputTransform.payloadBlack,
                        cfaPattern,
                        spectraModel
                );
                localStats.spectraTemporalObserverMs += elapsedDngMs(stageStart);
                if (spectraObservation.vulkanAttempted) localStats.spectraTemporalVulkanAttemptedPairs++;
                if (spectraObservation.vulkanExecutionSucceeded) localStats.spectraTemporalVulkanSucceededPairs++;
                if (spectraObservation.vulkanCpuFallbackUsed) localStats.spectraTemporalVulkanCpuFallbackPairs++;
                if (spectraObservation.vulkanPersistentReuseHit) localStats.spectraTemporalVulkanPersistentReusePairs++;
                if (spectraObservation.vulkanPersistentReallocated) localStats.spectraTemporalVulkanReallocatedPairs++;
                localStats.spectraTemporalVulkanInputPackingMs += spectraObservation.vulkanInputPackingMs;
                localStats.spectraTemporalVulkanCoarseKernelMs += spectraObservation.vulkanCoarseKernelMs;
                localStats.spectraTemporalVulkanCoarseReductionMs += spectraObservation.vulkanCoarseReductionMs;
                localStats.spectraTemporalVulkanObserverKernelMs += spectraObservation.vulkanObserverKernelMs;
                localStats.spectraTemporalVulkanStaticFieldKernelMs += spectraObservation.vulkanStaticFieldKernelMs;
                localStats.spectraTemporalVulkanCompactReductionAndFitMs += spectraObservation.vulkanCompactReductionAndFitMs;
                localStats.spectraTemporalVulkanSynchronizationMs += spectraObservation.vulkanSynchronizationMs;
                localStats.spectraTemporalVulkanReadbackMs += spectraObservation.vulkanReadbackMs;
                localStats.spectraTemporalVulkanTotalMs += spectraObservation.vulkanTotalMs;
                localStats.spectraTemporalVulkanInputBytes = std::max(
                        localStats.spectraTemporalVulkanInputBytes, spectraObservation.vulkanInputBytes);
                localStats.spectraTemporalVulkanCompactReadbackBytes = std::max(
                        localStats.spectraTemporalVulkanCompactReadbackBytes, spectraObservation.vulkanCompactReadbackBytes);
                localStats.spectraTemporalVulkanPersistentResidentBytes = std::max(
                        localStats.spectraTemporalVulkanPersistentResidentBytes, spectraObservation.vulkanPersistentResidentBytes);
                if (spectraObservation.vulkanAttempted) {
                    localStats.spectraTemporalVulkanStatus = spectraObservation.vulkanStatus;
                    localStats.spectraTemporalVulkanFailureReason = spectraObservation.vulkanFailureReason;
                }

                if (spectraModel.enabled && spectraObservation.staticProbabilityField.valid()) {
                    stageStart = DngClock::now();
                    spectraStaticConsensus.compareAndUpdate(spectraObservation);
                    localStats.spectraStaticConsensusMs += elapsedDngMs(stageStart);
                    const double repeatedAuthority = 0.50 +
                            0.50 * static_cast<double>(spectraObservation.repeatedSupportConfidence);
                    spectraObservation.observerConfidence *= repeatedAuthority;
                    spectraObservation.supportWeight *= static_cast<float>(
                            0.65 + 0.35 * spectraObservation.repeatedSupportConfidence
                    );
                    for (int ch = 0; ch < 4; ++ch) {
                        spectraObservation.regressionConfidence[ch] *= repeatedAuthority;
                    }
                    spectraObservation.valid = spectraObservation.valid &&
                            spectraObservation.repeatedSupportConfidence >= 0.25f;
                }
                if (spectraModel.enabled && spectraObservation.valid) {
                    stageStart = DngClock::now();
                    const float fitStability = spectraFitConsensus.compareAndUpdate(spectraObservation);
                    localStats.spectraFitConsensusMs += elapsedDngMs(stageStart);
                    const double fitStabilityAuthority = 0.75 + 0.25 * fitStability;
                    spectraObservation.observerConfidence *= fitStabilityAuthority;
                }

                bool supportAcceptedForOutput = false;
                if (fuseSupportFrames) {
                    if (spectraModel.enabled && !spectraObservation.valid) {
                        localStats.supportRejected++;
                    } else {
                        stageStart = DngClock::now();
                        mergeAlignedSupport(
                                anchor32,
                                supportBayer16,
                                decision,
                                localStats.alignmentStrictness,
                                localStats.payloadWhiteLevel,
                                inputTransform.payloadBlack,
                                cfaPattern,
                                spectraModel,
                                spectraObservation,
                                accum32,
                                weight32,
                                weightSq32,
                                correlationWeighted32
                        );
                        localStats.mergeAccumulatorMs += elapsedDngMs(stageStart);
                        localStats.supportAccepted++;
                        supportAcceptedForOutput = true;
                    }
                } else if (!spectraObservation.valid) {
                    localStats.supportRejected++;
                }

                if (spectraObservation.valid) {
                    spectraAcceptedObservations++;
                    spectraConfidenceSum += spectraObservation.observerConfidence;
                    spectraSupportWeightSum += spectraObservation.supportWeight;
                    spectraCorrelationSum += spectraObservation.meanTemporalCorrelation;
                    spectraAlignmentConfidenceSum += spectraObservation.alignmentConfidence;
                    spectraMotionConfidenceSum += spectraObservation.motionConfidence;
                    spectraForwardBackwardSum += spectraObservation.forwardBackwardConsistency;
                    spectraForwardBackwardMin = std::min(
                            spectraForwardBackwardMin,
                            static_cast<double>(spectraObservation.forwardBackwardConsistency)
                    );
                    spectraRepeatedSupportSum += spectraObservation.repeatedSupportConfidence;
                    spectraStaticMeanSum += spectraObservation.staticProbabilityMean;
                    spectraStaticP10Sum += spectraObservation.staticProbabilityP10;
                    spectraStaticP50Sum += spectraObservation.staticProbabilityP50;
                    spectraStaticP90Sum += spectraObservation.staticProbabilityP90;
                    spectraInnovationMeanSum += spectraObservation.normalizedInnovationMean;
                    spectraInnovationVarianceSum += spectraObservation.normalizedInnovationVariance;
                    spectraInnovationP90Sum += spectraObservation.normalizedInnovationP90;
                    spectraInnovationLagSum += spectraObservation.normalizedInnovationLagCorrelation;
                    spectraHeavyTailSum += spectraObservation.heavyTailFraction;
                    spectraPersistentPatternSum += spectraObservation.persistentPatternMean;
                    spectraFitStabilitySum += spectraObservation.meanFitStabilityConfidence;
                    spectraFitStabilityMin = std::min(
                            spectraFitStabilityMin,
                            static_cast<double>(spectraObservation.meanFitStabilityConfidence)
                    );
                    localStats.spectraHighConfidenceStaticSamples +=
                            spectraObservation.highConfidenceStaticSamples;
                    localStats.spectraClippingRejectedSamples +=
                            spectraObservation.clippingRejectedSamples;
                    localStats.spectraTextureRejectedSamples +=
                            spectraObservation.textureRejectedSamples;
                    localStats.spectraLocalMotionRejectedSamples +=
                            spectraObservation.localMotionRejectedSamples;
                    localStats.spectraStaticMapColumns =
                            spectraObservation.staticProbabilityField.columns;
                    localStats.spectraStaticMapRows =
                            spectraObservation.staticProbabilityField.rows;
                    localStats.spectraStaticMapCellSize =
                            spectraObservation.staticProbabilityField.cellSize;
                    for (int ch = 0; ch < 4; ++ch) {
                        const double channelWeight = static_cast<double>(spectraObservation.samples[ch]);
                        if (channelWeight <= 0.0) continue;
                        spectraObservedWeighted[ch] += spectraObservation.observedVariance[ch] * channelWeight;
                        spectraPredictedWeighted[ch] += spectraObservation.predictedVariance[ch] * channelWeight;
                        spectraScaleWeighted[ch] += spectraObservation.adaptationScale[ch] * channelWeight;
                        spectraSScaleWeighted[ch] += spectraObservation.sAdaptationScale[ch] * channelWeight;
                        spectraOScaleWeighted[ch] += spectraObservation.oAdaptationScale[ch] * channelWeight;
                        spectraRegressionConfidenceWeighted[ch] +=
                                spectraObservation.regressionConfidence[ch] * channelWeight;
                        spectraSignalSpanWeighted[ch] += spectraObservation.signalSpan[ch] * channelWeight;
                        spectraRegressionBinsWeighted[ch] +=
                                static_cast<double>(spectraObservation.populatedSignalBins[ch]) * channelWeight;
                        spectraFitPhysicalScoreWeighted[ch] +=
                                spectraObservation.fitPhysicalScore[ch] * channelWeight;
                        spectraFitInnovationMeanWeighted[ch] +=
                                spectraObservation.fitInnovationMean[ch] * channelWeight;
                        spectraFitInnovationVarianceWeighted[ch] +=
                                spectraObservation.fitInnovationVariance[ch] * channelWeight;
                        spectraFitResidualCorrelationWeighted[ch] +=
                                spectraObservation.fitResidualCorrelation[ch] * channelWeight;
                        spectraFitHeavyTailWeighted[ch] +=
                                spectraObservation.fitHeavyTailFraction[ch] * channelWeight;
                        spectraFitStabilityWeighted[ch] +=
                                spectraObservation.fitStabilityConfidence[ch] * channelWeight;
                        if (spectraObservation.fitEstimator[ch] == static_cast<int>(
                                spectra_temporal::TemporalFitEstimator::WEIGHTED_LEAST_SQUARES
                        )) spectraWlsVotes[ch]++;
                        if (spectraObservation.fitEstimator[ch] == static_cast<int>(
                                spectra_temporal::TemporalFitEstimator::HUBER_IRLS
                        )) spectraHuberVotes[ch]++;
                        spectraChannelWeight[ch] += channelWeight;
                        localStats.spectraObserverSamples += static_cast<int>(spectraObservation.samples[ch]);
                    }
                }
                const std::string supportStatus = supportAcceptedForOutput
                        ? "accepted_for_fusion"
                        : (spectraObservation.valid ? "accepted_observer_only" : "rejected_observer_low_confidence");
                appendProvenance(
                        "support[" + std::to_string(i) + "]=" + supportStatus + ":estimatedShift=(" +
                        formatDouble(decision.estimatedShiftX, 2) + "," +
                        formatDouble(decision.estimatedShiftY, 2) + "),appliedEvenShift=(" +
                        std::to_string(decision.applyDx) + "," +
                        std::to_string(decision.applyDy) + "),response=" +
                        formatDouble(decision.phaseResponse, 4) +
                        ",forwardBackward=" +
                        formatDouble(decision.forwardBackwardConsistency, 4) +
                        ",staticP50=" +
                        formatDouble(spectraObservation.staticProbabilityP50, 4) +
                        ",repeatedSupport=" +
                        formatDouble(spectraObservation.repeatedSupportConfidence, 4) +
                        ",fitStability=" +
                        formatDouble(spectraObservation.meanFitStabilityConfidence, 4) +
                        ",spectraWeight=" + formatDouble(spectraObservation.supportWeight, 4) +
                        ",temporalCorrelation=" +
                        formatDouble(spectraObservation.meanTemporalCorrelation, 4)
                );
                if (supportAcceptedForOutput) {
                    sumDx += decision.applyDx;
                    sumDy += decision.applyDy;
                    sumResponse += decision.phaseResponse;
                    minResponse = std::min(minResponse, decision.phaseResponse);
                    maxResponse = std::max(maxResponse, decision.phaseResponse);
                }
            }

            localStats.spectraAcceptedFramePairs = spectraModel.enabled
                    ? spectraAcceptedObservations
                    : 0;
            localStats.spectraRejectedFramePairs = spectraModel.enabled
                    ? std::max(
                            0,
                            static_cast<int>(selected.size()) - 1 - spectraAcceptedObservations
                    )
                    : 0;
            if (spectraAcceptedObservations > 0) {
                localStats.spectraAlignmentConfidence = static_cast<float>(std::clamp(
                        spectraAlignmentConfidenceSum / static_cast<double>(spectraAcceptedObservations),
                        0.0,
                        1.0
                ));
                localStats.spectraMotionConfidence = static_cast<float>(std::clamp(
                        spectraMotionConfidenceSum / static_cast<double>(spectraAcceptedObservations),
                        0.0,
                        1.0
                ));
                const double observationCount = static_cast<double>(spectraAcceptedObservations);
                localStats.spectraForwardBackwardConsistency = std::clamp(
                        spectraForwardBackwardSum / observationCount, 0.0, 1.0
                );
                localStats.spectraForwardBackwardConsistencyMin = std::isfinite(spectraForwardBackwardMin)
                        ? std::clamp(spectraForwardBackwardMin, 0.0, 1.0)
                        : 0.0;
                localStats.spectraRepeatedSupportConfidence = std::clamp(
                        spectraRepeatedSupportSum / observationCount, 0.0, 1.0
                );
                localStats.spectraStaticProbabilityMean = std::clamp(
                        spectraStaticMeanSum / observationCount, 0.0, 1.0
                );
                localStats.spectraStaticProbabilityP10 = std::clamp(
                        spectraStaticP10Sum / observationCount, 0.0, 1.0
                );
                localStats.spectraStaticProbabilityP50 = std::clamp(
                        spectraStaticP50Sum / observationCount, 0.0, 1.0
                );
                localStats.spectraStaticProbabilityP90 = std::clamp(
                        spectraStaticP90Sum / observationCount, 0.0, 1.0
                );
                localStats.spectraNormalizedInnovationMean =
                        spectraInnovationMeanSum / observationCount;
                localStats.spectraNormalizedInnovationVariance = std::max(
                        0.0, spectraInnovationVarianceSum / observationCount
                );
                localStats.spectraNormalizedInnovationP90 = std::max(
                        0.0, spectraInnovationP90Sum / observationCount
                );
                localStats.spectraNormalizedInnovationLagCorrelation = std::clamp(
                        spectraInnovationLagSum / observationCount, -1.0, 1.0
                );
                localStats.spectraHeavyTailFraction = std::clamp(
                        spectraHeavyTailSum / observationCount, 0.0, 1.0
                );
                localStats.spectraPersistentPatternFraction = std::clamp(
                        spectraPersistentPatternSum / observationCount, 0.0, 0.95
                );
                localStats.spectraFitStabilityConfidence = std::clamp(
                        spectraFitStabilitySum / observationCount, 0.0, 1.0
                );
                localStats.spectraFitStabilityMin = std::isfinite(spectraFitStabilityMin)
                        ? std::clamp(spectraFitStabilityMin, 0.0, 1.0)
                        : 0.0;
                for (int ch = 0; ch < 4; ++ch) {
                    if (spectraChannelWeight[ch] <= 0.0) continue;
                    localStats.spectraFitPhysicalScore[ch] = std::clamp(
                            spectraFitPhysicalScoreWeighted[ch] / spectraChannelWeight[ch],
                            0.0, 1.0
                    );
                    localStats.spectraFitInnovationMean[ch] =
                            spectraFitInnovationMeanWeighted[ch] / spectraChannelWeight[ch];
                    localStats.spectraFitInnovationVariance[ch] = std::max(
                            0.0, spectraFitInnovationVarianceWeighted[ch] / spectraChannelWeight[ch]
                    );
                    localStats.spectraFitResidualCorrelation[ch] = std::clamp(
                            spectraFitResidualCorrelationWeighted[ch] / spectraChannelWeight[ch],
                            -1.0, 1.0
                    );
                    localStats.spectraFitHeavyTailFraction[ch] = std::clamp(
                            spectraFitHeavyTailWeighted[ch] / spectraChannelWeight[ch],
                            0.0, 1.0
                    );
                    localStats.spectraFitStabilityByChannel[ch] = std::clamp(
                            spectraFitStabilityWeighted[ch] / spectraChannelWeight[ch],
                            0.0, 1.0
                    );
                    localStats.spectraFitEstimator[ch] = spectraHuberVotes[ch] > spectraWlsVotes[ch]
                            ? static_cast<int>(spectra_temporal::TemporalFitEstimator::HUBER_IRLS)
                            : (spectraWlsVotes[ch] > 0
                                    ? static_cast<int>(spectra_temporal::TemporalFitEstimator::WEIGHTED_LEAST_SQUARES)
                                    : static_cast<int>(spectra_temporal::TemporalFitEstimator::NONE));
                    if (localStats.spectraFitEstimator[ch] == static_cast<int>(
                            spectra_temporal::TemporalFitEstimator::WEIGHTED_LEAST_SQUARES
                    )) localStats.spectraWlsSelectedChannels++;
                    if (localStats.spectraFitEstimator[ch] == static_cast<int>(
                            spectra_temporal::TemporalFitEstimator::HUBER_IRLS
                    )) localStats.spectraHuberSelectedChannels++;
                }
            }

            if (!fuseSupportFrames && spectraModel.enabled) {
                localStats.spectraFusionVarianceScale = 1.0;
                localStats.spectraEffectiveFrameCount = 1.0;
                if (spectraAcceptedObservations > 0) {
                    localStats.spectraObserverConfidence = std::clamp(
                            spectraConfidenceSum / static_cast<double>(spectraAcceptedObservations),
                            0.0,
                            1.0
                    );
                    localStats.spectraAverageSupportWeight =
                            spectraSupportWeightSum / static_cast<double>(spectraAcceptedObservations);
                    localStats.spectraTemporalCorrelation = static_cast<float>(std::clamp(
                            spectraCorrelationSum / static_cast<double>(spectraAcceptedObservations),
                            0.0,
                            0.85
                    ));
                    localStats.spectraIndependentNoiseFraction = std::clamp(
                            1.0f - localStats.spectraTemporalCorrelation,
                            0.15f,
                            1.0f
                    );
                    for (int ch = 0; ch < 4; ++ch) {
                        if (spectraChannelWeight[ch] <= 0.0) continue;
                        localStats.spectraObservedVariance[ch] =
                                spectraObservedWeighted[ch] / spectraChannelWeight[ch];
                        localStats.spectraPredictedVariance[ch] =
                                spectraPredictedWeighted[ch] / spectraChannelWeight[ch];
                        localStats.spectraAdaptationScale[ch] = std::clamp(
                                spectraScaleWeighted[ch] / spectraChannelWeight[ch],
                                0.75,
                                1.25
                        );
                        localStats.spectraSAdaptationScale[ch] = std::clamp(
                                spectraSScaleWeighted[ch] / spectraChannelWeight[ch],
                                0.75,
                                1.25
                        );
                        localStats.spectraOAdaptationScale[ch] = std::clamp(
                                spectraOScaleWeighted[ch] / spectraChannelWeight[ch],
                                0.75,
                                1.25
                        );
                        localStats.spectraRegressionConfidence[ch] = std::clamp(
                                spectraRegressionConfidenceWeighted[ch] / spectraChannelWeight[ch],
                                0.0,
                                1.0
                        );
                        localStats.spectraSignalSpan[ch] = std::max(
                                0.0,
                                spectraSignalSpanWeighted[ch] / spectraChannelWeight[ch]
                        );
                        localStats.spectraRegressionBins[ch] = static_cast<int>(std::lround(
                                spectraRegressionBinsWeighted[ch] / spectraChannelWeight[ch]
                        ));
                    }
                }
            }

            if (localStats.supportAccepted > 0) {
                stageStart = DngClock::now();
                finalBayer16 = cv::Mat(accum32.size(), CV_16UC1);
                cv::parallel_for_(cv::Range(0, accum32.rows), [&](const cv::Range& range) {
                    for (int y = range.start; y < range.end; ++y) {
                        const float* aRow = accum32.ptr<float>(y);
                        const float* wRow = weight32.ptr<float>(y);
                        uint16_t* dst = finalBayer16.ptr<uint16_t>(y);
                        for (int x = 0; x < accum32.cols; ++x) {
                            const float w = std::max(0.000001f, wRow[x]);
                            dst[x] = cv::saturate_cast<uint16_t>(aRow[x] / w);
                        }
                    }
                });
                localStats.mergeNormalizeMs = elapsedDngMs(stageStart);

                if (spectraModel.enabled) {
                    if (spectraAcceptedObservations > 0) {
                        localStats.spectraTemporalCorrelation = static_cast<float>(std::clamp(
                                spectraCorrelationSum / static_cast<double>(spectraAcceptedObservations),
                                0.0,
                                0.85
                        ));
                        localStats.spectraIndependentNoiseFraction = std::clamp(
                                1.0f - localStats.spectraTemporalCorrelation,
                                0.15f,
                                1.0f
                        );
                    }

                    stageStart = DngClock::now();
                    std::vector<double> localVarianceScales;
                    std::vector<double> localEffectiveFrames;
                    localVarianceScales.reserve(static_cast<size_t>(
                            std::max(1, accum32.rows / 8) * std::max(1, accum32.cols / 8)
                    ));
                    localEffectiveFrames.reserve(localVarianceScales.capacity());
                    uint64_t sampledFusionPixels = 0u;
                    uint64_t fallbackFusionPixels = 0u;
                    double varianceScaleSum = 0.0;
                    for (int y = 0; y < accum32.rows; y += 8) {
                        const float* anchorRow = anchor32.ptr<float>(y);
                        const float* weightRow = weight32.ptr<float>(y);
                        const float* weightSqRow = weightSq32.ptr<float>(y);
                        const float* correlationRow = correlationWeighted32.ptr<float>(y);
                        for (int x = 0; x < accum32.cols; x += 8) {
                            const double totalWeight = std::max(1.0e-6, static_cast<double>(weightRow[x]));
                            const double independentVarianceScale = std::clamp(
                                    static_cast<double>(weightSqRow[x]) / (totalWeight * totalWeight),
                                    0.02,
                                    1.0
                            );
                            const int ch = spectraCfaChannel(cfaPattern, x, y);
                            const double signal = spectraNormalizedSignal(
                                    anchorRow[x],
                                    x,
                                    y,
                                    localStats.payloadWhiteLevel,
                                    inputTransform.payloadBlack
                            );
                            const double variance = std::max(
                                    1.0e-12,
                                    spectraModel.effectiveS[ch] * signal + spectraModel.effectiveO[ch]
                            );
                            const float inverseVarianceWeight = static_cast<float>(std::clamp(
                                    spectraReferenceVariance(spectraModel, ch) / variance,
                                    0.25,
                                    4.0
                            ));
                            const double anchorWeight = 1.0 +
                                    spectraModel.confidence * spectraModel.temporalAuthority *
                                    (static_cast<double>(inverseVarianceWeight) - 1.0);
                            const double supportWeight = std::max(0.0, totalWeight - anchorWeight);
                            const double localCorrelation = supportWeight > 1.0e-5
                                    ? std::clamp(
                                            static_cast<double>(correlationRow[x]) / supportWeight,
                                            0.0,
                                            0.95
                                    )
                                    : 0.0;
                            if (supportWeight <= 1.0e-5) fallbackFusionPixels++;
                            const double localScale = spectra_temporal::correlationAwareVarianceScale(
                                    independentVarianceScale,
                                    localCorrelation
                            );
                            const double localEffective = spectra_temporal::effectiveFrameCount(localScale);
                            localVarianceScales.push_back(localScale);
                            localEffectiveFrames.push_back(localEffective);
                            varianceScaleSum += localScale;
                            sampledFusionPixels++;
                        }
                    }
                    localStats.spectraCorrelationAnalysisMs += elapsedDngMs(stageStart);
                    localStats.spectraFusionVarianceScale = sampledFusionPixels > 0u
                            ? std::clamp(
                                    varianceScaleSum / static_cast<double>(sampledFusionPixels),
                                    0.02,
                                    1.0
                            )
                            : 1.0;
                    localStats.spectraFusionVarianceP10 =
                            spectra_temporal::percentile(localVarianceScales, 0.10);
                    localStats.spectraFusionVarianceP50 =
                            spectra_temporal::percentile(localVarianceScales, 0.50);
                    localStats.spectraFusionVarianceP90 =
                            spectra_temporal::percentile(localVarianceScales, 0.90);
                    localStats.spectraEffectiveFrameCount =
                            spectra_temporal::effectiveFrameCount(localStats.spectraFusionVarianceScale);
                    localStats.spectraEffectiveFrameCountP10 =
                            spectra_temporal::percentile(localEffectiveFrames, 0.10);
                    localStats.spectraEffectiveFrameCountP50 =
                            spectra_temporal::percentile(localEffectiveFrames, 0.50);
                    localStats.spectraEffectiveFrameCountP90 =
                            spectra_temporal::percentile(localEffectiveFrames, 0.90);
                    localStats.spectraLocalFusionFallbackFraction = sampledFusionPixels > 0u
                            ? static_cast<double>(fallbackFusionPixels) /
                                    static_cast<double>(sampledFusionPixels)
                            : 1.0;

                    if (spectraAcceptedObservations > 0) {
                        localStats.spectraObserverConfidence = std::clamp(
                                spectraConfidenceSum / static_cast<double>(spectraAcceptedObservations),
                                0.0,
                                1.0
                        );
                        localStats.spectraAverageSupportWeight =
                                spectraSupportWeightSum / static_cast<double>(spectraAcceptedObservations);
                        for (int ch = 0; ch < 4; ++ch) {
                            if (spectraChannelWeight[ch] <= 0.0) continue;
                            localStats.spectraObservedVariance[ch] =
                                    spectraObservedWeighted[ch] / spectraChannelWeight[ch];
                            localStats.spectraPredictedVariance[ch] =
                                    spectraPredictedWeighted[ch] / spectraChannelWeight[ch];
                            localStats.spectraAdaptationScale[ch] = std::clamp(
                                    spectraScaleWeighted[ch] / spectraChannelWeight[ch],
                                    0.75,
                                    1.25
                            );
                            localStats.spectraSAdaptationScale[ch] = std::clamp(
                                    spectraSScaleWeighted[ch] / spectraChannelWeight[ch],
                                    0.75,
                                    1.25
                            );
                            localStats.spectraOAdaptationScale[ch] = std::clamp(
                                    spectraOScaleWeighted[ch] / spectraChannelWeight[ch],
                                    0.75,
                                    1.25
                            );
                            localStats.spectraRegressionConfidence[ch] = std::clamp(
                                    spectraRegressionConfidenceWeighted[ch] / spectraChannelWeight[ch],
                                    0.0,
                                    1.0
                            );
                            localStats.spectraSignalSpan[ch] = std::max(
                                    0.0,
                                    spectraSignalSpanWeighted[ch] / spectraChannelWeight[ch]
                            );
                            localStats.spectraRegressionBins[ch] = static_cast<int>(std::lround(
                                    spectraRegressionBinsWeighted[ch] / spectraChannelWeight[ch]
                            ));
                        }
                    }
                }

                localStats.framesMerged = 1 + localStats.supportAccepted;
                localStats.mergeOutputCreated = true;
                localStats.anchorOnly = false;
                localStats.avgAppliedShiftX = sumDx / localStats.supportAccepted;
                localStats.avgAppliedShiftY = sumDy / localStats.supportAccepted;
                localStats.avgPhaseResponse = sumResponse / localStats.supportAccepted;
                localStats.minPhaseResponse = std::isfinite(minResponse) ? minResponse : 0.0;
                localStats.maxPhaseResponse = maxResponse;
            } else {
                finalBayer16 = anchorBayer16;
                localStats.framesMerged = 1;
                localStats.mergeOutputCreated = false;
                localStats.anchorOnly = true;
                localStats.failureReason = !fuseSupportFrames && spectraAcceptedObservations > 0
                        ? "none"
                        : "all support frames rejected; anchor DNG RAW16 used";
            }
        } else {
            finalBayer16 = anchorBayer16;
            localStats.framesMerged = 1;
            localStats.mergeOutputCreated = false;
            localStats.anchorOnly = true;
        }

        Raw16SampleStats finalStats = computeRaw16SampleStats(finalBayer16);
        Raw16FullStats finalFullStats = computeRaw16FullStats(finalBayer16, localStats.payloadWhiteLevel);
        localStats.sampledRaw16Min = finalStats.minValue;
        localStats.sampledRaw16P01 = finalStats.p01;
        localStats.sampledRaw16P50 = finalStats.p50;
        localStats.sampledRaw16P95 = finalStats.p95;
        localStats.sampledRaw16P99 = finalStats.p99;
        localStats.sampledRaw16Max = finalStats.maxValue;
        localStats.fullRaw16Min = finalFullStats.minValue;
        localStats.fullRaw16Max = finalFullStats.maxValue;
        localStats.fullRaw16SaturatedCount = finalFullStats.saturatedCount;
        localStats.fullRaw16SaturatedPct = finalFullStats.saturatedPct;
        localStats.raw16Min = finalFullStats.minValue;
        localStats.raw16P01 = finalStats.p01;
        localStats.raw16P50 = finalStats.p50;
        localStats.raw16P95 = finalStats.p95;
        localStats.raw16P99 = finalStats.p99;
        localStats.raw16Max = finalFullStats.maxValue;

        // Bepaal de totale grootte
        stageStart = DngClock::now();
        size_t totalBytes = static_cast<size_t>(finalBayer16.cols) * static_cast<size_t>(finalBayer16.rows) * 2u;
        localStats.raw16Bytes = totalBytes;

        // Keep the canonical Master RAW16 in native memory. Kotlin receives only a direct
        // ByteBuffer view and must release it explicitly through ImageUtils.
        auto* nativePayload = new (std::nothrow) uint8_t[totalBytes];
        const std::uint64_t residentGenerationForPublication =
                selected.size() == 1u ? singleFrameResidentGeneration : 0u;
        if (nativePayload != nullptr && trackNativeRaw16Allocation(
                    nativePayload, residentGenerationForPublication)) {
            if (finalBayer16.isContinuous()) {
                std::memcpy(nativePayload, finalBayer16.ptr(), totalBytes);
            } else {
                const size_t rowBytes = static_cast<size_t>(finalBayer16.cols) * 2u;
                for (int y = 0; y < finalBayer16.rows; ++y) {
                    std::memcpy(nativePayload + static_cast<size_t>(y) * rowBytes,
                                finalBayer16.ptr<uint16_t>(y), rowBytes);
                }
            }
            jobject directBuffer = env->NewDirectByteBuffer(nativePayload, static_cast<jlong>(totalBytes));
            if (directBuffer != nullptr) {
                localStats.outputArrayMs = elapsedDngMs(stageStart);
                localStats.totalNativeDngMergeMs = elapsedDngMs(totalMergeStart);
                localStats.nativeRaw16OutstandingBuffersAtReturn = trackedNativeRaw16AllocationCount();
                copyStats();
                return directBuffer;
            }
            releaseTrackedNativeRaw16Allocation(nativePayload);
            localStats.failureReason = "JNI NewDirectByteBuffer failed";
            env->ExceptionClear();
        } else {
            if (nativePayload != nullptr) delete[] nativePayload;
            localStats.failureReason = "Native RAW16 allocation/registration failed";
        }

    } catch (const cv::Exception& e) {
        localStats.failureReason = std::string("OpenCV exception: ") + e.what();
        DNG_LOGE("OpenCV exception in %s DNG merger: %s", route.c_str(), e.what());
    } catch (const std::exception& e) {
        localStats.failureReason = std::string("std::exception: ") + e.what();
        DNG_LOGE("std::exception in %s DNG merger: %s", route.c_str(), e.what());
    } catch (...) {
        localStats.failureReason = "unknown native exception";
        DNG_LOGE("Unknown exception in %s DNG merger", route.c_str());
    }

    // Als we hier belanden, is er iets misgegaan, maar de app blijft draaien.
    localStats.totalNativeDngMergeMs = elapsedDngMs(totalMergeStart);
    copyStats();
    return nullptr;
}

} // namespace

bool releaseNativeRaw16Allocation(void* address) {
    return releaseTrackedNativeRaw16Allocation(address);
}

std::uint64_t nativeRaw16ResidentGeneration(void* address) {
    if (address == nullptr) return 0u;
    std::lock_guard<std::mutex> lock(gNativeRaw16AllocationMutex);
    const auto allocation = gNativeRaw16Allocations.find(address);
    if (allocation == gNativeRaw16Allocations.end()) return 0u;
    const auto found = gNativeRaw16ResidentGenerations.find(address);
    return found == gNativeRaw16ResidentGenerations.end() ? 0u : found->second;
}

std::uint64_t nativeRaw16MultiFrameResidentGeneration(void* address) {
    // Compatibility alias retained for Phase-9/early-Phase-13 tests and tooling.
    return nativeRaw16ResidentGeneration(address);
}

size_t nativeRaw16OutstandingAllocationCount() {
    return trackedNativeRaw16AllocationCount();
}

jobject mergeRaw10DngToRaw16(
        JNIEnv *env,
        const std::vector<AHardwareBuffer*>& hwBuffers,
        jint cfaPattern,
        jint whiteLevel,
        const std::vector<int32_t>& blackLevels,
        jint maxFramesCap,
        jint maxShiftPixels,
        jfloat alignmentStrictness,
        jint spectraMode,
        bool temporalNoiseModelEnabled,
        bool spectraAdaptiveCalibrationEnabled,
        const std::vector<double>& spectraEffectiveS,
        const std::vector<double>& spectraEffectiveO,
        jfloat spectraModelConfidence,
        bool fuseSupportFrames,
        const std::vector<float>& exposureScaleToAnchor,
        bool computationalHdr,
        int sourceCropLeft,
        int sourceCropTop,
        int sourceCropWidth,
        int sourceCropHeight,
        DngMergeStats* stats
) {
    return mergeRawToDngRaw16Internal(
            env,
            hwBuffers,
            cfaPattern,
            whiteLevel,
            blackLevels,
            maxFramesCap,
            maxShiftPixels,
            alignmentStrictness,
            spectraMode,
            temporalNoiseModelEnabled,
            spectraAdaptiveCalibrationEnabled,
            spectraEffectiveS,
            spectraEffectiveO,
            spectraModelConfidence,
            fuseSupportFrames,
            exposureScaleToAnchor,
            computationalHdr,
            sourceCropLeft,
            sourceCropTop,
            sourceCropWidth,
            sourceCropHeight,
            stats,
            "RAW10",
            lockRaw10Buffer,
            unpackRaw10ToSensorRaw16
    );
}

jobject mergeRawSensorDngToRaw16(
        JNIEnv *env,
        const std::vector<AHardwareBuffer*>& hwBuffers,
        jint cfaPattern,
        jint whiteLevel,
        const std::vector<int32_t>& blackLevels,
        jint maxFramesCap,
        jint maxShiftPixels,
        jfloat alignmentStrictness,
        jint spectraMode,
        bool temporalNoiseModelEnabled,
        bool spectraAdaptiveCalibrationEnabled,
        const std::vector<double>& spectraEffectiveS,
        const std::vector<double>& spectraEffectiveO,
        jfloat spectraModelConfidence,
        bool fuseSupportFrames,
        const std::vector<float>& exposureScaleToAnchor,
        bool computationalHdr,
        int sourceCropLeft,
        int sourceCropTop,
        int sourceCropWidth,
        int sourceCropHeight,
        DngMergeStats* stats
) {
    return mergeRawToDngRaw16Internal(
            env,
            hwBuffers,
            cfaPattern,
            whiteLevel,
            blackLevels,
            maxFramesCap,
            maxShiftPixels,
            alignmentStrictness,
            spectraMode,
            temporalNoiseModelEnabled,
            spectraAdaptiveCalibrationEnabled,
            spectraEffectiveS,
            spectraEffectiveO,
            spectraModelConfidence,
            fuseSupportFrames,
            exposureScaleToAnchor,
            computationalHdr,
            sourceCropLeft,
            sourceCropTop,
            sourceCropWidth,
            sourceCropHeight,
            stats,
            "RAW_SENSOR",
            lockRawSensorBuffer,
            unpackRawSensorToSensorRaw16
    );
}

std::string formatDngMergeStats(const DngMergeStats& stats) {
    std::ostringstream oss;
    oss << std::boolalpha << std::fixed << std::setprecision(3)
        << "route=" << stats.route
        << ";framesReceived=" << stats.framesReceived
        << ";framesUsedForDng=" << stats.framesUsedForDng
        << ";framesDecoded=" << stats.framesDecoded
        << ";supportAccepted=" << stats.supportAccepted
        << ";supportRejected=" << stats.supportRejected
        << ";supportRejectedCanonicalization=" << stats.supportRejectedCanonicalization
        << ";supportRejectedAlignment=" << stats.supportRejectedAlignment
        << ";supportRejectedForwardBackward=" << stats.supportRejectedForwardBackward
        << ";supportRejectedSpectraConsensus=" << stats.supportRejectedSpectraConsensus
        << ";supportRejectedZeroWeightedContribution=" << stats.supportRejectedZeroWeightedContribution
        << ";supportRejectedOther=" << stats.supportRejectedOther
        << ";framesMerged=" << stats.framesMerged
        << ";mergeAttempted=" << stats.mergeAttempted
        << ";mergeOutputCreated=" << stats.mergeOutputCreated
        << ";anchorOnly=" << stats.anchorOnly
        << ";width=" << stats.width
        << ";height=" << stats.height
        << ";rowStrideBytes=" << stats.rowStrideBytes
        << ";pixelStrideBytes=" << stats.pixelStrideBytes
        << ";cfaPattern=" << stats.cfaPattern
        << ";whiteLevel=" << stats.whiteLevel
        << ";inputNativeWhiteLevel=" << stats.inputNativeWhiteLevel
        << ";payloadWhiteLevel=" << stats.payloadWhiteLevel
        << ";inputNativeBlackLevels=" << stats.inputNativeBlackLevels
        << ";payloadBlackLevels=" << stats.payloadBlackLevels
        << ";payloadScaleFactor=" << stats.payloadScaleFactor
        << ";sampleScaleContract=" << stats.sampleScaleContract
        << ";sampleTransformApplied=" << stats.sampleTransformApplied
        << ";sourceStrideValid=" << stats.sourceStrideValid
        << ";sourcePixelStrideValid=" << stats.sourcePixelStrideValid
        << ";supportFrameProvenance=" << stats.supportFrameProvenance
        << ";raw16Min=" << stats.raw16Min
        << ";raw16P50=" << stats.raw16P50
        << ";raw16P95=" << stats.raw16P95
        << ";raw16P99=" << stats.raw16P99
        << ";raw16Max=" << stats.raw16Max
        << ";sampledRaw16Min=" << stats.sampledRaw16Min
        << ";sampledRaw16P01=" << stats.sampledRaw16P01
        << ";sampledRaw16P50=" << stats.sampledRaw16P50
        << ";sampledRaw16P95=" << stats.sampledRaw16P95
        << ";sampledRaw16P99=" << stats.sampledRaw16P99
        << ";sampledRaw16Max=" << stats.sampledRaw16Max
        << ";fullRaw16Min=" << stats.fullRaw16Min
        << ";fullRaw16Max=" << stats.fullRaw16Max
        << ";fullRaw16SaturatedCount=" << stats.fullRaw16SaturatedCount
        << ";fullRaw16SaturatedPct=" << stats.fullRaw16SaturatedPct
        << ";raw10InputWidth=" << stats.width
        << ";raw10InputHeight=" << stats.height
        << ";raw10RowStride=" << stats.rowStrideBytes
        << ";raw10PixelStride=" << stats.pixelStrideBytes
        << ";raw10ExpectedRowStride=" << stats.raw10ExpectedRowStride
        << ";raw10ActualRowStride=" << stats.raw10ActualRowStride
        << ";raw10StrideMatchesExpected=" << stats.raw10StrideMatchesExpected
        << ";raw10UnpackPattern=" << (stats.route == "RAW10" ? "4px_5bytes" : "not_applicable")
        << ";raw10PaddingBytesPerRow=" << stats.raw10PaddingBytesPerRow
        << ";raw10MalformedRowCount=" << stats.raw10MalformedRowCount
        << ";raw10PackedBytes=" << (stats.rowStrideBytes * stats.height)
        << ";rawSensorMinimumRowBytes=" << stats.rawSensorMinimumRowBytes
        << ";rawSensorActualRowStride=" << stats.rawSensorActualRowStride
        << ";rawSensorPixelStride=" << stats.rawSensorPixelStride
        << ";rawSensorPaddingBytesPerRow=" << stats.rawSensorPaddingBytesPerRow
        << ";raw10UnpackedMin=" << stats.unpackedMin
        << ";raw10UnpackedP01=" << stats.unpackedP01
        << ";raw10UnpackedP50=" << stats.unpackedP50
        << ";raw10UnpackedP99=" << stats.unpackedP99
        << ";raw10UnpackedMax=" << stats.unpackedMax
        << ";masterRaw16Min=" << stats.raw16Min
        << ";masterRaw16P01=" << stats.raw16P01
        << ";masterRaw16P50=" << stats.raw16P50
        << ";masterRaw16P99=" << stats.raw16P99
        << ";masterRaw16Max=" << stats.raw16Max
        << ";sampledMasterRaw16Min=" << stats.sampledRaw16Min
        << ";sampledMasterRaw16P01=" << stats.sampledRaw16P01
        << ";sampledMasterRaw16P50=" << stats.sampledRaw16P50
        << ";sampledMasterRaw16P95=" << stats.sampledRaw16P95
        << ";sampledMasterRaw16P99=" << stats.sampledRaw16P99
        << ";sampledMasterRaw16Max=" << stats.sampledRaw16Max
        << ";blackLevelBase=" << stats.inputNativeBlackLevels
        << ";blackLevelFinal=" << stats.payloadBlackLevels
        << ";whiteLevelBase=" << stats.inputNativeWhiteLevel
        << ";whiteLevelFinal=" << stats.payloadWhiteLevel
        << ";dngSource=" << stats.route
        << ";dngAppliedExposureGain=false"
        << ";dngAppliedToneCurve=false"
        << ";dngAppliedGtm=false"
        << ";dngAppliedLtm=false"
        << ";dngAppliedBento=false"
        << ";dngAppliedPartialHighlightRepair=false"
        << ";dngAppliedHighlightNeutralization=false"
        << ";dngAppliedFalseColorSuppression=false"
        << ";dngAppliedDenoise=false"
        << ";dngAppliedSharpen=false"
        << ";maxFramesCap=" << stats.maxFramesCap
        << ";maxShiftPixels=" << stats.maxShiftPixels
        << ";alignmentStrictness=" << stats.alignmentStrictness
        << ";avgAppliedShiftX=" << stats.avgAppliedShiftX
        << ";avgAppliedShiftY=" << stats.avgAppliedShiftY
        << ";avgPhaseResponse=" << stats.avgPhaseResponse
        << ";minPhaseResponse=" << stats.minPhaseResponse
        << ";maxPhaseResponse=" << stats.maxPhaseResponse
        << ";raw16Bytes=" << stats.raw16Bytes
        << ";peakWorkingSetEstimateBytes=" << stats.peakWorkingSetEstimateBytes
        << ";anchorUnpackMs=" << stats.anchorUnpackMs
        << ";supportUnpackMs=" << stats.supportUnpackMs
        << ";alignmentMs=" << stats.alignmentMs
        << ";mergeAccumulatorMs=" << stats.mergeAccumulatorMs
        << ";mergeNormalizeMs=" << stats.mergeNormalizeMs
        << ";outputArrayMs=" << stats.outputArrayMs
        << ";inputImportPath=" << stats.inputImportPath
        << ";rawUnpackBackend=" << stats.rawUnpackBackend
        << ";cpuFullFrameRawUnpack=" << stats.cpuFullFrameRawUnpack
        << ";cpuFallbackUsed=" << stats.cpuFallbackUsed
        << ";cpuFallbackReason=" << stats.cpuFallbackReason
        << ";rawUnpackVulkanFrames=" << stats.rawUnpackVulkanFrames
        << ";rawUnpackCpuFallbackFrames=" << stats.rawUnpackCpuFallbackFrames
        << ";fullFrameCpuUploadBytes=" << stats.fullFrameCpuUploadBytes
        << ";fullFrameGpuReadbackBytes=" << stats.fullFrameGpuReadbackBytes
        << ";rawUnpackGpuMs=" << stats.rawUnpackGpuMs
        << ";rawUnpackTransferMs=" << stats.rawUnpackTransferMs
        << ";rawUnpackReadbackMs=" << stats.rawUnpackReadbackMs
        << ";alignmentBackend=" << stats.alignmentBackend
        << ";fusionBackend=" << stats.fusionBackend
        << ";cpuAlignment=" << boolString(stats.cpuAlignment)
        << ";cpuFusion=" << boolString(stats.cpuFusion)
        << ";cpuFullFrameSupportMaterialization=" << boolString(stats.cpuFullFrameSupportMaterialization)
        << ";residentFusedRawProduced=" << boolString(stats.residentFusedRawProduced)
        << ";compactGpuReadbackBytes=" << stats.compactGpuReadbackBytes
        << ";rawAlignmentGpuMs=" << stats.rawAlignmentGpuMs
        << ";rawFusionGpuMs=" << stats.rawFusionGpuMs
        << ";rawMultiFrameObserverGpuMs=" << stats.rawMultiFrameObserverGpuMs
        << ";rawMultiFrameGpuSynchronizationMs=" << stats.rawMultiFrameGpuSynchronizationMs
        << ";rawMultiFrameFinalReadbackMs=" << stats.rawMultiFrameFinalReadbackMs
        << ";outputNativeDirectBufferMs=" << stats.outputArrayMs
        << ";raw16Ownership=NATIVE_DIRECT_BUFFER_V1"
        << ";javaRaw16CopyCount=0"
        << ";javaRaw16CopyBytes=0"
        << ";nativeRaw16BufferBytes=" << stats.raw16Bytes
        << ";nativeRaw16OutstandingBuffersAtReturn=" << stats.nativeRaw16OutstandingBuffersAtReturn
        << ";temporalNoiseModelEnabled=" << stats.temporalNoiseModelEnabled
        << ";temporalNoiseModelAuthority=" << stats.temporalNoiseModelAuthority
        << ";spectraAdaptiveCalibrationEnabled=" << stats.spectraAdaptiveCalibrationEnabled
        << ";spectraEnabled=" << stats.spectraEnabled
        << ";spectraModelConfidence=" << stats.spectraModelConfidence
        << ";spectraNoisePressure=" << stats.spectraNoisePressure
        << ";spectraTemporalAuthority=" << stats.spectraTemporalAuthority
        << ";spectraTemporalCorrelation=" << stats.spectraTemporalCorrelation
        << ";spectraIndependentNoiseFraction=" << stats.spectraIndependentNoiseFraction
        << ";spectraAcceptedFramePairs=" << stats.spectraAcceptedFramePairs
        << ";spectraRejectedFramePairs=" << stats.spectraRejectedFramePairs
        << ";spectraAlignmentConfidence=" << stats.spectraAlignmentConfidence
        << ";spectraMotionConfidence=" << stats.spectraMotionConfidence
        << ";spectraObserverSamples=" << stats.spectraObserverSamples
        << ";spectraObserverConfidence=" << stats.spectraObserverConfidence
        << ";spectraAverageSupportWeight=" << stats.spectraAverageSupportWeight
        << ";spectraFusionVarianceScale=" << stats.spectraFusionVarianceScale
        << ";spectraFusionVarianceP10=" << stats.spectraFusionVarianceP10
        << ";spectraFusionVarianceP50=" << stats.spectraFusionVarianceP50
        << ";spectraFusionVarianceP90=" << stats.spectraFusionVarianceP90
        << ";spectraEffectiveFrameCount=" << stats.spectraEffectiveFrameCount
        << ";spectraEffectiveFrameCountP10=" << stats.spectraEffectiveFrameCountP10
        << ";spectraEffectiveFrameCountP50=" << stats.spectraEffectiveFrameCountP50
        << ";spectraEffectiveFrameCountP90=" << stats.spectraEffectiveFrameCountP90
        << ";spectraPersistentPatternFraction=" << stats.spectraPersistentPatternFraction
        << ";spectraForwardBackwardConsistency=" << stats.spectraForwardBackwardConsistency
        << ";spectraForwardBackwardConsistencyMin=" << stats.spectraForwardBackwardConsistencyMin
        << ";spectraForwardBackwardRejectedPairs=" << stats.spectraForwardBackwardRejectedPairs
        << ";spectraRepeatedSupportConfidence=" << stats.spectraRepeatedSupportConfidence
        << ";spectraStaticProbabilityMean=" << stats.spectraStaticProbabilityMean
        << ";spectraStaticProbabilityP10=" << stats.spectraStaticProbabilityP10
        << ";spectraStaticProbabilityP50=" << stats.spectraStaticProbabilityP50
        << ";spectraStaticProbabilityP90=" << stats.spectraStaticProbabilityP90
        << ";spectraNormalizedInnovationMean=" << stats.spectraNormalizedInnovationMean
        << ";spectraNormalizedInnovationVariance=" << stats.spectraNormalizedInnovationVariance
        << ";spectraNormalizedInnovationP90=" << stats.spectraNormalizedInnovationP90
        << ";spectraNormalizedInnovationLagCorrelation=" << stats.spectraNormalizedInnovationLagCorrelation
        << ";spectraHeavyTailFraction=" << stats.spectraHeavyTailFraction
        << ";spectraLocalFusionFallbackFraction=" << stats.spectraLocalFusionFallbackFraction
        << ";spectraHighConfidenceStaticSamples=" << stats.spectraHighConfidenceStaticSamples
        << ";spectraClippingRejectedSamples=" << stats.spectraClippingRejectedSamples
        << ";spectraTextureRejectedSamples=" << stats.spectraTextureRejectedSamples
        << ";spectraLocalMotionRejectedSamples=" << stats.spectraLocalMotionRejectedSamples
        << ";spectraStaticMapColumns=" << stats.spectraStaticMapColumns
        << ";spectraStaticMapRows=" << stats.spectraStaticMapRows
        << ";spectraStaticMapCellSize=" << stats.spectraStaticMapCellSize
        << ";spectraWlsSelectedChannels=" << stats.spectraWlsSelectedChannels
        << ";spectraHuberSelectedChannels=" << stats.spectraHuberSelectedChannels
        << ";spectraFitStabilityConfidence=" << stats.spectraFitStabilityConfidence
        << ";spectraFitStabilityMin=" << stats.spectraFitStabilityMin
        << ";spectraForwardBackwardAlignmentMs=" << stats.spectraForwardBackwardAlignmentMs
        << ";spectraTemporalObserverMs=" << stats.spectraTemporalObserverMs
        << ";spectraTemporalVulkanAttemptedPairs=" << stats.spectraTemporalVulkanAttemptedPairs
        << ";spectraTemporalVulkanSucceededPairs=" << stats.spectraTemporalVulkanSucceededPairs
        << ";spectraTemporalVulkanCpuFallbackPairs=" << stats.spectraTemporalVulkanCpuFallbackPairs
        << ";spectraTemporalVulkanPersistentReusePairs=" << stats.spectraTemporalVulkanPersistentReusePairs
        << ";spectraTemporalVulkanReallocatedPairs=" << stats.spectraTemporalVulkanReallocatedPairs
        << ";spectraTemporalVulkanInputPackingMs=" << stats.spectraTemporalVulkanInputPackingMs
        << ";spectraTemporalVulkanCoarseKernelMs=" << stats.spectraTemporalVulkanCoarseKernelMs
        << ";spectraTemporalVulkanCoarseReductionMs=" << stats.spectraTemporalVulkanCoarseReductionMs
        << ";spectraTemporalVulkanObserverKernelMs=" << stats.spectraTemporalVulkanObserverKernelMs
        << ";spectraTemporalVulkanStaticFieldKernelMs=" << stats.spectraTemporalVulkanStaticFieldKernelMs
        << ";spectraTemporalVulkanCompactReductionAndFitMs=" << stats.spectraTemporalVulkanCompactReductionAndFitMs
        << ";spectraTemporalVulkanSynchronizationMs=" << stats.spectraTemporalVulkanSynchronizationMs
        << ";spectraTemporalVulkanReadbackMs=" << stats.spectraTemporalVulkanReadbackMs
        << ";spectraTemporalVulkanTotalMs=" << stats.spectraTemporalVulkanTotalMs
        << ";spectraTemporalVulkanInputBytes=" << stats.spectraTemporalVulkanInputBytes
        << ";spectraTemporalVulkanCompactReadbackBytes=" << stats.spectraTemporalVulkanCompactReadbackBytes
        << ";spectraTemporalVulkanPersistentResidentBytes=" << stats.spectraTemporalVulkanPersistentResidentBytes
        << ";spectraTemporalVulkanStatus=" << stats.spectraTemporalVulkanStatus
        << ";spectraTemporalVulkanFailureReason=" << stats.spectraTemporalVulkanFailureReason
        << ";spectraStaticConsensusMs=" << stats.spectraStaticConsensusMs
        << ";spectraFitConsensusMs=" << stats.spectraFitConsensusMs
        << ";spectraCorrelationAnalysisMs=" << stats.spectraCorrelationAnalysisMs
        << ";spectraObserverPersistence=CAPTURE_LOCAL_NON_PERSISTENT"
        << ";spectraObserverOnly=" << stats.spectraObserverOnly
        << ";spectraObservedVar0=" << stats.spectraObservedVariance[0]
        << ";spectraObservedVar1=" << stats.spectraObservedVariance[1]
        << ";spectraObservedVar2=" << stats.spectraObservedVariance[2]
        << ";spectraObservedVar3=" << stats.spectraObservedVariance[3]
        << ";spectraPredictedVar0=" << stats.spectraPredictedVariance[0]
        << ";spectraPredictedVar1=" << stats.spectraPredictedVariance[1]
        << ";spectraPredictedVar2=" << stats.spectraPredictedVariance[2]
        << ";spectraPredictedVar3=" << stats.spectraPredictedVariance[3]
        << ";spectraScale0=" << stats.spectraAdaptationScale[0]
        << ";spectraScale1=" << stats.spectraAdaptationScale[1]
        << ";spectraScale2=" << stats.spectraAdaptationScale[2]
        << ";spectraScale3=" << stats.spectraAdaptationScale[3]
        << ";spectraSScale0=" << stats.spectraSAdaptationScale[0]
        << ";spectraSScale1=" << stats.spectraSAdaptationScale[1]
        << ";spectraSScale2=" << stats.spectraSAdaptationScale[2]
        << ";spectraSScale3=" << stats.spectraSAdaptationScale[3]
        << ";spectraOScale0=" << stats.spectraOAdaptationScale[0]
        << ";spectraOScale1=" << stats.spectraOAdaptationScale[1]
        << ";spectraOScale2=" << stats.spectraOAdaptationScale[2]
        << ";spectraOScale3=" << stats.spectraOAdaptationScale[3]
        << ";spectraFitConfidence0=" << stats.spectraRegressionConfidence[0]
        << ";spectraFitConfidence1=" << stats.spectraRegressionConfidence[1]
        << ";spectraFitConfidence2=" << stats.spectraRegressionConfidence[2]
        << ";spectraFitConfidence3=" << stats.spectraRegressionConfidence[3]
        << ";spectraSignalSpan0=" << stats.spectraSignalSpan[0]
        << ";spectraSignalSpan1=" << stats.spectraSignalSpan[1]
        << ";spectraSignalSpan2=" << stats.spectraSignalSpan[2]
        << ";spectraSignalSpan3=" << stats.spectraSignalSpan[3]
        << ";spectraFitBins0=" << stats.spectraRegressionBins[0]
        << ";spectraFitBins1=" << stats.spectraRegressionBins[1]
        << ";spectraFitBins2=" << stats.spectraRegressionBins[2]
        << ";spectraFitBins3=" << stats.spectraRegressionBins[3]
        << ";spectraFitEstimator0=" << stats.spectraFitEstimator[0]
        << ";spectraFitEstimator1=" << stats.spectraFitEstimator[1]
        << ";spectraFitEstimator2=" << stats.spectraFitEstimator[2]
        << ";spectraFitEstimator3=" << stats.spectraFitEstimator[3]
        << ";spectraFitStability0=" << stats.spectraFitStabilityByChannel[0]
        << ";spectraFitStability1=" << stats.spectraFitStabilityByChannel[1]
        << ";spectraFitStability2=" << stats.spectraFitStabilityByChannel[2]
        << ";spectraFitStability3=" << stats.spectraFitStabilityByChannel[3]
        << ";spectraFitPhysicalScore0=" << stats.spectraFitPhysicalScore[0]
        << ";spectraFitPhysicalScore1=" << stats.spectraFitPhysicalScore[1]
        << ";spectraFitPhysicalScore2=" << stats.spectraFitPhysicalScore[2]
        << ";spectraFitPhysicalScore3=" << stats.spectraFitPhysicalScore[3]
        << ";spectraFitInnovationMean0=" << stats.spectraFitInnovationMean[0]
        << ";spectraFitInnovationMean1=" << stats.spectraFitInnovationMean[1]
        << ";spectraFitInnovationMean2=" << stats.spectraFitInnovationMean[2]
        << ";spectraFitInnovationMean3=" << stats.spectraFitInnovationMean[3]
        << ";spectraFitInnovationVariance0=" << stats.spectraFitInnovationVariance[0]
        << ";spectraFitInnovationVariance1=" << stats.spectraFitInnovationVariance[1]
        << ";spectraFitInnovationVariance2=" << stats.spectraFitInnovationVariance[2]
        << ";spectraFitInnovationVariance3=" << stats.spectraFitInnovationVariance[3]
        << ";spectraFitResidualCorrelation0=" << stats.spectraFitResidualCorrelation[0]
        << ";spectraFitResidualCorrelation1=" << stats.spectraFitResidualCorrelation[1]
        << ";spectraFitResidualCorrelation2=" << stats.spectraFitResidualCorrelation[2]
        << ";spectraFitResidualCorrelation3=" << stats.spectraFitResidualCorrelation[3]
        << ";spectraFitHeavyTailFraction0=" << stats.spectraFitHeavyTailFraction[0]
        << ";spectraFitHeavyTailFraction1=" << stats.spectraFitHeavyTailFraction[1]
        << ";spectraFitHeavyTailFraction2=" << stats.spectraFitHeavyTailFraction[2]
        << ";spectraFitHeavyTailFraction3=" << stats.spectraFitHeavyTailFraction[3]
        << ";totalNativeDngMergeMs=" << stats.totalNativeDngMergeMs
        << ";raw10UnpackMs="
        << (stats.route == "RAW10" ? stats.anchorUnpackMs + stats.supportUnpackMs : 0.0)
        << ";raw10ToMasterRaw16Ms="
        << (stats.route == "RAW10" ? stats.totalNativeDngMergeMs : 0.0)
        << ";raw10MergeOrSingleMasterMs="
        << (stats.route == "RAW10" ? stats.totalNativeDngMergeMs : 0.0)
        << ";rawSensorReadMs="
        << (stats.route == "RAW_SENSOR" ? stats.anchorUnpackMs + stats.supportUnpackMs : 0.0)
        << ";rawSensorToMasterRaw16Ms="
        << (stats.route == "RAW_SENSOR" ? stats.totalNativeDngMergeMs : 0.0)
        << ";hardwareConfigMetadata=" << stats.hardwareConfigMetadata
        << ";dngStandardTagsOverriddenByLensSettings=false"
        << ";dngDescriptionIncludesLensHardwareSettings=true"
        << ";failureReason=" << stats.failureReason;
    return oss.str();
}
