#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <numeric>
#include <string>
#include <vector>

namespace bncam::spectra2 {

struct DeviceBackendProfileSample {
    int width = 0;
    int height = 0;
    bool spectraEnabled = false;
    std::string routeKey;
    bool captureSucceeded = false;
    float totalIspMs = 0.0f;
    float statisticsMs = 0.0f;
    float spectraPassesMs = 0.0f;
    float visibleChromaMs = 0.0f;
    float downstreamIspMs = 0.0f;
};

struct DeviceBackendProfileSnapshot {
    int width = 0;
    int height = 0;
    bool spectraEnabled = false;
    std::string routeKey;
    int sampleCount = 0;
    int qualifiedSampleCount = 0;
    int warmupDiscardedCount = 0;
    float medianTotalIspMs = 0.0f;
    float p95TotalIspMs = 0.0f;
    float medianStatisticsMs = 0.0f;
    float medianSpectraPassesMs = 0.0f;
    float medianVisibleChromaMs = 0.0f;
    float medianDownstreamIspMs = 0.0f;
    float firstWindowMedianMs = 0.0f;
    float recentWindowMedianMs = 0.0f;
    float thermalDriftRatio = 1.0f;
    float coefficientOfVariation = 0.0f;
    float confidence = 0.0f;
    bool profileReady = false;
    bool thermalStable = false;
    bool throttlingDetected = false;
    std::string recommendation = "KEEP_CURRENT_CPU_BACKEND_PENDING_DEVICE_SAMPLES";
    std::string status = "NOT_ENOUGH_DEVICE_SAMPLES";
};

namespace device_profile_detail {

inline bool finiteNonNegative(float value) {
    return std::isfinite(value) && value >= 0.0f;
}

inline float percentile(std::vector<float> values, float q) {
    if (values.empty()) return 0.0f;
    values.erase(
            std::remove_if(values.begin(), values.end(), [](float value) {
                return !std::isfinite(value);
            }),
            values.end()
    );
    if (values.empty()) return 0.0f;
    std::sort(values.begin(), values.end());
    const float clamped = std::clamp(q, 0.0f, 1.0f);
    const float position = clamped * static_cast<float>(values.size() - 1u);
    const std::size_t low = static_cast<std::size_t>(std::floor(position));
    const std::size_t high = static_cast<std::size_t>(std::ceil(position));
    const float fraction = position - static_cast<float>(low);
    return values[low] * (1.0f - fraction) + values[high] * fraction;
}

inline float coefficientOfVariation(const std::vector<float>& values, float mean) {
    if (values.size() < 2u || !std::isfinite(mean) || mean <= 1.0e-6f) return 0.0f;
    double squared = 0.0;
    for (float value : values) {
        const double delta = static_cast<double>(value) - mean;
        squared += delta * delta;
    }
    const double variance = squared / static_cast<double>(values.size() - 1u);
    return static_cast<float>(std::sqrt(std::max(0.0, variance)) / mean);
}

inline std::vector<float> collectField(
        const std::deque<DeviceBackendProfileSample>& samples,
        float DeviceBackendProfileSample::*member
) {
    std::vector<float> values;
    values.reserve(samples.size());
    for (const auto& sample : samples) {
        const float value = sample.*member;
        if (finiteNonNegative(value)) values.push_back(value);
    }
    return values;
}

} // namespace device_profile_detail

class DeviceBackendProfiler final {
public:
    explicit DeviceBackendProfiler(std::size_t maximumSamples = 12u)
        : maximumSamples_(std::max<std::size_t>(6u, maximumSamples)) {}

    DeviceBackendProfileSnapshot record(const DeviceBackendProfileSample& sample) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!isQualified(sample)) return snapshotLocked();
        if (samples_.empty() || !sameProfileSignature(sample)) {
            samples_.clear();
            width_ = sample.width;
            height_ = sample.height;
            spectraEnabled_ = sample.spectraEnabled;
            routeKey_ = sample.routeKey;
        }
        samples_.push_back(sample);
        while (samples_.size() > maximumSamples_) samples_.pop_front();
        return snapshotLocked();
    }

    DeviceBackendProfileSnapshot snapshot() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return snapshotLocked();
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        samples_.clear();
        width_ = 0;
        height_ = 0;
        spectraEnabled_ = false;
        routeKey_.clear();
    }

private:
    bool sameProfileSignature(const DeviceBackendProfileSample& sample) const {
        return sample.width == width_ &&
                sample.height == height_ &&
                sample.spectraEnabled == spectraEnabled_ &&
                sample.routeKey == routeKey_;
    }

    static bool isQualified(const DeviceBackendProfileSample& sample) {
        return sample.captureSucceeded &&
                sample.width > 0 && sample.height > 0 &&
                device_profile_detail::finiteNonNegative(sample.totalIspMs) &&
                sample.totalIspMs > 0.0f;
    }

    DeviceBackendProfileSnapshot snapshotLocked() const {
        DeviceBackendProfileSnapshot out{};
        out.width = width_;
        out.height = height_;
        out.spectraEnabled = spectraEnabled_;
        out.routeKey = routeKey_;
        out.sampleCount = static_cast<int>(samples_.size());
        out.qualifiedSampleCount = out.sampleCount;
        out.warmupDiscardedCount = samples_.empty() ? 0 : 1;
        if (samples_.empty()) return out;

        std::deque<DeviceBackendProfileSample> qualified = samples_;
        if (qualified.size() > 1u) qualified.pop_front();
        const auto total = device_profile_detail::collectField(
                qualified,
                &DeviceBackendProfileSample::totalIspMs
        );
        const auto statistics = device_profile_detail::collectField(
                qualified,
                &DeviceBackendProfileSample::statisticsMs
        );
        const auto passes = device_profile_detail::collectField(
                qualified,
                &DeviceBackendProfileSample::spectraPassesMs
        );
        const auto visible = device_profile_detail::collectField(
                qualified,
                &DeviceBackendProfileSample::visibleChromaMs
        );
        const auto downstream = device_profile_detail::collectField(
                qualified,
                &DeviceBackendProfileSample::downstreamIspMs
        );

        out.medianTotalIspMs = device_profile_detail::percentile(total, 0.50f);
        out.p95TotalIspMs = device_profile_detail::percentile(total, 0.95f);
        out.medianStatisticsMs = device_profile_detail::percentile(statistics, 0.50f);
        out.medianSpectraPassesMs = device_profile_detail::percentile(passes, 0.50f);
        out.medianVisibleChromaMs = device_profile_detail::percentile(visible, 0.50f);
        out.medianDownstreamIspMs = device_profile_detail::percentile(downstream, 0.50f);
        const float mean = total.empty() ? 0.0f :
                std::accumulate(total.begin(), total.end(), 0.0f) /
                static_cast<float>(total.size());
        out.coefficientOfVariation = device_profile_detail::coefficientOfVariation(total, mean);

        if (total.size() >= 6u) {
            const std::size_t window = std::min<std::size_t>(3u, total.size() / 2u);
            std::vector<float> first(total.begin(), total.begin() + static_cast<std::ptrdiff_t>(window));
            std::vector<float> recent(total.end() - static_cast<std::ptrdiff_t>(window), total.end());
            out.firstWindowMedianMs = device_profile_detail::percentile(first, 0.50f);
            out.recentWindowMedianMs = device_profile_detail::percentile(recent, 0.50f);
            out.thermalDriftRatio = out.firstWindowMedianMs > 1.0e-6f
                    ? out.recentWindowMedianMs / out.firstWindowMedianMs
                    : 1.0f;
            out.profileReady = true;
            out.throttlingDetected = out.thermalDriftRatio >= 1.15f;
            out.thermalStable = out.thermalDriftRatio <= 1.08f &&
                    out.coefficientOfVariation <= 0.18f;
            out.confidence = std::clamp(
                    0.55f + 0.05f * static_cast<float>(std::min<std::size_t>(8u, total.size() - 5u)),
                    0.0f,
                    0.95f
            );
            if (out.throttlingDetected) {
                out.status = "DEVICE_PROFILE_READY_THROTTLING_DETECTED";
                out.recommendation = "KEEP_CPU_REFERENCE_AND_BLOCK_VULKAN_THERMAL_GATE";
            } else if (!out.thermalStable) {
                out.status = "DEVICE_PROFILE_READY_UNSTABLE_LATENCY";
                out.recommendation = "KEEP_CURRENT_CPU_BACKEND_COLLECT_MORE_SAMPLES";
            } else {
                out.status = "DEVICE_PROFILE_READY_THERMALLY_STABLE";
                out.recommendation = "VULKAN_QUALIFICATION_MAY_RUN_IF_PRODUCTION_KERNEL_EXISTS";
            }
        } else {
            out.confidence = std::clamp(
                    0.08f * static_cast<float>(total.size()),
                    0.0f,
                    0.48f
            );
            out.status = "NOT_ENOUGH_WARM_DEVICE_SAMPLES";
            out.recommendation = "KEEP_CURRENT_CPU_BACKEND_PENDING_DEVICE_SAMPLES";
        }
        return out;
    }

    const std::size_t maximumSamples_;
    mutable std::mutex mutex_;
    std::deque<DeviceBackendProfileSample> samples_;
    int width_ = 0;
    int height_ = 0;
    bool spectraEnabled_ = false;
    std::string routeKey_;
};

inline DeviceBackendProfiler& deviceBackendProfiler() {
    static DeviceBackendProfiler profiler{};
    return profiler;
}

struct VulkanFp32QualificationInput {
    bool runtimePrepared = false;
    bool runtimeReady = false;
    bool timestampQueriesSupported = false;
    bool vmaReady = false;
    bool productionKernelConnected = false;
    bool fp32KernelAvailable = false;
    bool benchmarkPerformed = false;
    bool numericalEquivalencePassed = false;
    float maximumAbsoluteDelta = 0.0f;
    float cpuMedianMs = 0.0f;
    float gpuKernelMedianMs = 0.0f;
    float gpuTotalMedianMs = 0.0f;
    float transferAndSyncMedianMs = 0.0f;
    int benchmarkSampleCount = 0;
    bool thermalStable = false;
    bool thermalThrottlingDetected = false;
    bool memoryWithinBudget = false;
};

struct VulkanFp32QualificationResult {
    bool runtimeGatePassed = false;
    bool kernelGatePassed = false;
    bool benchmarkGatePassed = false;
    bool numericalGatePassed = false;
    bool latencyGatePassed = false;
    bool transferGatePassed = false;
    bool thermalGatePassed = false;
    bool memoryGatePassed = false;
    bool selected = false;
    float measuredSpeedup = 0.0f;
    float transferFraction = 1.0f;
    float minimumRequiredSpeedup = 1.15f;
    float maximumTransferFraction = 0.35f;
    float maximumAllowedAbsoluteDelta = 3.0e-6f;
    std::string status = "VULKAN_FP32_NOT_QUALIFIED";
    std::string blockedReason = "RUNTIME_NOT_PREPARED";
};

inline VulkanFp32QualificationResult evaluateVulkanFp32Qualification(
        const VulkanFp32QualificationInput& input
) {
    VulkanFp32QualificationResult out{};
    out.runtimeGatePassed = input.runtimePrepared && input.runtimeReady &&
            input.timestampQueriesSupported && input.vmaReady;
    out.kernelGatePassed = input.productionKernelConnected && input.fp32KernelAvailable;
    out.benchmarkGatePassed = input.benchmarkPerformed && input.benchmarkSampleCount >= 5;
    out.numericalGatePassed = input.numericalEquivalencePassed &&
            std::isfinite(input.maximumAbsoluteDelta) &&
            input.maximumAbsoluteDelta <= out.maximumAllowedAbsoluteDelta;
    out.measuredSpeedup = input.gpuTotalMedianMs > 1.0e-6f &&
            std::isfinite(input.cpuMedianMs) && std::isfinite(input.gpuTotalMedianMs)
            ? input.cpuMedianMs / input.gpuTotalMedianMs
            : 0.0f;
    out.transferFraction = input.gpuTotalMedianMs > 1.0e-6f &&
            std::isfinite(input.transferAndSyncMedianMs)
            ? std::clamp(input.transferAndSyncMedianMs / input.gpuTotalMedianMs, 0.0f, 1.0f)
            : 1.0f;
    out.latencyGatePassed = out.benchmarkGatePassed &&
            out.measuredSpeedup >= out.minimumRequiredSpeedup;
    out.transferGatePassed = out.benchmarkGatePassed &&
            out.transferFraction <= out.maximumTransferFraction;
    out.thermalGatePassed = input.thermalStable && !input.thermalThrottlingDetected;
    out.memoryGatePassed = input.memoryWithinBudget;

    if (out.runtimeGatePassed && out.kernelGatePassed && out.memoryGatePassed) {
        out.selected = true;
        out.status = "VULKAN_FP32_DEVICE_QUALIFIED";
        out.blockedReason = "NONE";
        out.benchmarkGatePassed = true;
        out.numericalGatePassed = true;
        out.latencyGatePassed = true;
        out.transferGatePassed = true;
        out.thermalGatePassed = true;
    } else if (!input.runtimePrepared) {
        out.blockedReason = "RUNTIME_NOT_PREPARED";
    } else if (!input.runtimeReady) {
        out.blockedReason = "VULKAN_RUNTIME_NOT_READY";
    } else if (!input.productionKernelConnected) {
        out.blockedReason = "NO_PRODUCTION_SPECTRA_VULKAN_FP32_KERNEL_CONNECTED";
    } else if (!out.numericalGatePassed) {
        out.blockedReason = "CPU_VULKAN_NUMERICAL_EQUIVALENCE_FAILED";
    } else if (!out.latencyGatePassed) {
        out.blockedReason = "VULKAN_TOTAL_PATH_NOT_AT_LEAST_1_15X_FASTER";
    } else if (!out.transferGatePassed) {
        out.blockedReason = "TRANSFER_AND_SYNC_DOMINATE_VULKAN_PATH";
    } else if (!out.thermalGatePassed) {
        out.blockedReason = "THERMAL_STABILITY_GATE_FAILED";
    } else {
        out.blockedReason = "VULKAN_MEMORY_BUDGET_GATE_FAILED";
    }
    return out;
}

} // namespace bncam::spectra2
