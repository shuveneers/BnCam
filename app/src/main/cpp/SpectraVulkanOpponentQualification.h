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

struct VulkanOpponentBenchmarkSample {
    std::string routeKey;
    bool success = false;
    bool numericalComparisonPerformed = false;
    float maximumAbsoluteDelta = 0.0f;
    float cpuReferenceMs = 0.0f;
    float gpuKernelMs = 0.0f;
    float gpuTotalMs = 0.0f;
    float transferAndSyncMs = 0.0f;
    std::uint64_t inputBytes = 0;
    std::uint64_t outputBytes = 0;
};

struct VulkanOpponentQualificationSnapshot {
    std::string routeKey;
    bool warmupDiscarded = false;
    int successfulSampleCount = 0;
    int failedSampleCount = 0;
    bool benchmarkPerformed = false;
    bool numericalEquivalencePassed = false;
    bool deviceQualificationSelected = false;
    bool benchmarkAborted = false;
    float maximumAbsoluteDelta = 0.0f;
    float cpuMedianMs = 0.0f;
    float gpuKernelMedianMs = 0.0f;
    float gpuTotalMedianMs = 0.0f;
    float transferAndSyncMedianMs = 0.0f;
    float measuredSpeedup = 0.0f;
    float transferFraction = 1.0f;
    std::uint64_t inputBytes = 0;
    std::uint64_t outputBytes = 0;
    std::string status = "NO_VULKAN_OPPONENT_SAMPLES";
};

namespace vulkan_opponent_qualification_detail {

inline float percentile(std::vector<float> values, float p) {
    values.erase(
            std::remove_if(values.begin(), values.end(), [](float value) {
                return !std::isfinite(value) || value < 0.0f;
            }),
            values.end()
    );
    if (values.empty()) return 0.0f;
    std::sort(values.begin(), values.end());
    const float clamped = std::clamp(p, 0.0f, 1.0f);
    const float position = clamped * static_cast<float>(values.size() - 1u);
    const std::size_t lower = static_cast<std::size_t>(std::floor(position));
    const std::size_t upper = std::min(values.size() - 1u, lower + 1u);
    const float fraction = position - static_cast<float>(lower);
    return values[lower] * (1.0f - fraction) + values[upper] * fraction;
}

} // namespace vulkan_opponent_qualification_detail

class VulkanOpponentQualifier final {
public:
    explicit VulkanOpponentQualifier(std::size_t maximumWarmSamples = 8u)
        : maximumWarmSamples_(std::max<std::size_t>(5u, maximumWarmSamples)) {
    }

    VulkanOpponentQualificationSnapshot record(const VulkanOpponentBenchmarkSample& sample) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (sample.routeKey.empty()) return snapshotLocked();
        if (routeKey_ != sample.routeKey) {
            routeKey_ = sample.routeKey;
            warmupDiscarded_ = false;
            failedSampleCount_ = 0;
            deviceQualificationSelected_ = false;
            samples_.clear();
        }
        if (!sample.success || !sample.numericalComparisonPerformed ||
            !std::isfinite(sample.maximumAbsoluteDelta) ||
            !std::isfinite(sample.cpuReferenceMs) ||
            !std::isfinite(sample.gpuKernelMs) ||
            !std::isfinite(sample.gpuTotalMs) ||
            !std::isfinite(sample.transferAndSyncMs)) {
            failedSampleCount_++;
            return snapshotLocked();
        }
        if (!warmupDiscarded_) {
            warmupDiscarded_ = true;
            return snapshotLocked();
        }
        samples_.push_back(sample);
        while (samples_.size() > maximumWarmSamples_) samples_.pop_front();
        return snapshotLocked();
    }

    VulkanOpponentQualificationSnapshot snapshot(const std::string& routeKey) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (routeKey != routeKey_) {
            VulkanOpponentQualificationSnapshot out{};
            out.routeKey = routeKey;
            out.status = "ROUTE_NOT_YET_BENCHMARKED";
            return out;
        }
        return snapshotLocked();
    }

    bool needsBenchmark(const std::string& routeKey) const {
        const VulkanOpponentQualificationSnapshot current = snapshot(routeKey);
        return !current.benchmarkPerformed && !current.benchmarkAborted;
    }

    void setDeviceQualificationSelected(const std::string& routeKey, bool selected) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (routeKey.empty()) return;
        if (routeKey_ != routeKey) {
            routeKey_ = routeKey;
            warmupDiscarded_ = false;
            failedSampleCount_ = 0;
            samples_.clear();
        }
        deviceQualificationSelected_ = selected;
    }

private:
    VulkanOpponentQualificationSnapshot snapshotLocked() const {
        VulkanOpponentQualificationSnapshot out{};
        out.routeKey = routeKey_;
        out.warmupDiscarded = warmupDiscarded_;
        out.successfulSampleCount = static_cast<int>(samples_.size());
        out.failedSampleCount = failedSampleCount_;
        out.deviceQualificationSelected = deviceQualificationSelected_;
        out.benchmarkAborted = failedSampleCount_ >= kMaximumFailedSamples;
        if (samples_.empty()) {
            if (out.benchmarkAborted) {
                out.status = "VULKAN_OPPONENT_BENCHMARK_ABORTED_AFTER_REPEATED_FAILURES";
            } else {
                out.status = warmupDiscarded_
                        ? "WAITING_FOR_WARM_VULKAN_OPPONENT_SAMPLES"
                        : "WAITING_FOR_VULKAN_OPPONENT_WARMUP";
            }
            return out;
        }

        std::vector<float> cpu;
        std::vector<float> kernel;
        std::vector<float> total;
        std::vector<float> transfer;
        cpu.reserve(samples_.size());
        kernel.reserve(samples_.size());
        total.reserve(samples_.size());
        transfer.reserve(samples_.size());
        float maximumDelta = 0.0f;
        bool allNumericallyEquivalent = true;
        for (const auto& sample : samples_) {
            cpu.push_back(sample.cpuReferenceMs);
            kernel.push_back(sample.gpuKernelMs);
            total.push_back(sample.gpuTotalMs);
            transfer.push_back(sample.transferAndSyncMs);
            maximumDelta = std::max(maximumDelta, sample.maximumAbsoluteDelta);
            allNumericallyEquivalent = allNumericallyEquivalent &&
                    sample.maximumAbsoluteDelta <= 3.0e-6f;
            out.inputBytes = std::max(out.inputBytes, sample.inputBytes);
            out.outputBytes = std::max(out.outputBytes, sample.outputBytes);
        }
        out.maximumAbsoluteDelta = maximumDelta;
        out.cpuMedianMs = vulkan_opponent_qualification_detail::percentile(cpu, 0.50f);
        out.gpuKernelMedianMs = vulkan_opponent_qualification_detail::percentile(kernel, 0.50f);
        out.gpuTotalMedianMs = vulkan_opponent_qualification_detail::percentile(total, 0.50f);
        out.transferAndSyncMedianMs =
                vulkan_opponent_qualification_detail::percentile(transfer, 0.50f);
        out.measuredSpeedup = out.gpuTotalMedianMs > 1.0e-6f
                ? out.cpuMedianMs / out.gpuTotalMedianMs
                : 0.0f;
        out.transferFraction = out.gpuTotalMedianMs > 1.0e-6f
                ? std::clamp(out.transferAndSyncMedianMs / out.gpuTotalMedianMs, 0.0f, 1.0f)
                : 1.0f;
        out.numericalEquivalencePassed = allNumericallyEquivalent &&
                std::isfinite(out.maximumAbsoluteDelta) && out.maximumAbsoluteDelta <= 3.0e-6f;
        out.benchmarkPerformed = samples_.size() >= 5u;
        if (out.benchmarkAborted && !out.benchmarkPerformed) {
            out.status = "VULKAN_OPPONENT_BENCHMARK_ABORTED_AFTER_REPEATED_FAILURES";
        } else if (!out.benchmarkPerformed) {
            out.status = "COLLECTING_VULKAN_OPPONENT_DEVICE_BENCHMARKS";
        } else if (!out.numericalEquivalencePassed) {
            out.status = "VULKAN_OPPONENT_NUMERICAL_EQUIVALENCE_FAILED";
        } else {
            out.status = "VULKAN_OPPONENT_DEVICE_BENCHMARK_READY";
        }
        return out;
    }

    static constexpr int kMaximumFailedSamples = 3;
    const std::size_t maximumWarmSamples_;
    mutable std::mutex mutex_;
    std::string routeKey_;
    bool warmupDiscarded_ = false;
    int failedSampleCount_ = 0;
    bool deviceQualificationSelected_ = false;
    std::deque<VulkanOpponentBenchmarkSample> samples_;
};

inline VulkanOpponentQualifier& vulkanOpponentQualifier() {
    static VulkanOpponentQualifier qualifier{};
    return qualifier;
}

} // namespace bncam::spectra2
