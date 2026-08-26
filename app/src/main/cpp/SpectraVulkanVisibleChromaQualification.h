#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::spectra2 {

struct VulkanVisibleChromaBenchmarkSample {
    std::string routeKey;
    bool success = false;
    bool candidateComparisonPerformed = false;
    bool finalDecisionComparisonPerformed = false;
    float candidateMaximumAbsoluteDelta = 0.0f;
    float finalDecisionMaximumAbsoluteDelta = 0.0f;
    float cpuCandidateReferenceMs = 0.0f;
    float gpuKernelMs = 0.0f;
    float gpuTotalMs = 0.0f;
    float transferAndSyncMs = 0.0f;
    std::uint64_t inputBytes = 0u;
    std::uint64_t outputBytes = 0u;
};

struct VulkanVisibleChromaQualificationSnapshot {
    std::string routeKey;
    bool warmupDiscarded = false;
    int successfulSampleCount = 0;
    int failedSampleCount = 0;
    bool benchmarkPerformed = false;
    bool candidateNumericalEquivalencePassed = false;
    bool finalDecisionCompatibilityPassed = false;
    bool deviceQualificationSelected = false;
    bool benchmarkAborted = false;
    float candidateMaximumAbsoluteDelta = 0.0f;
    float finalDecisionMaximumAbsoluteDelta = 0.0f;
    float cpuCandidateMedianMs = 0.0f;
    float gpuKernelMedianMs = 0.0f;
    float gpuTotalMedianMs = 0.0f;
    float transferAndSyncMedianMs = 0.0f;
    float measuredSpeedup = 0.0f;
    float transferFraction = 1.0f;
    std::uint64_t inputBytes = 0u;
    std::uint64_t outputBytes = 0u;
    std::string status = "NO_VULKAN_VISIBLE_CHROMA_SAMPLES";
};

namespace vulkan_visible_chroma_qualification_detail {
inline float percentile(std::vector<float> values, float p) {
    values.erase(std::remove_if(values.begin(), values.end(), [](float value) {
        return !std::isfinite(value) || value < 0.0f;
    }), values.end());
    if (values.empty()) return 0.0f;
    std::sort(values.begin(), values.end());
    const float position = std::clamp(p, 0.0f, 1.0f) *
            static_cast<float>(values.size() - 1u);
    const std::size_t lower = static_cast<std::size_t>(std::floor(position));
    const std::size_t upper = std::min(values.size() - 1u, lower + 1u);
    const float fraction = position - static_cast<float>(lower);
    return values[lower] * (1.0f - fraction) + values[upper] * fraction;
}
} // namespace vulkan_visible_chroma_qualification_detail

class VulkanVisibleChromaQualifier final {
public:
    explicit VulkanVisibleChromaQualifier(std::size_t maximumWarmSamples = 8u)
        : maximumWarmSamples_(std::max<std::size_t>(5u, maximumWarmSamples)) {}

    VulkanVisibleChromaQualificationSnapshot record(
            const VulkanVisibleChromaBenchmarkSample& sample
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (sample.routeKey.empty()) return snapshotLocked();
        if (routeKey_ != sample.routeKey) resetLocked(sample.routeKey);
        if (!sample.success || !sample.candidateComparisonPerformed ||
            !sample.finalDecisionComparisonPerformed ||
            !std::isfinite(sample.candidateMaximumAbsoluteDelta) ||
            !std::isfinite(sample.finalDecisionMaximumAbsoluteDelta) ||
            !std::isfinite(sample.cpuCandidateReferenceMs) ||
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

    VulkanVisibleChromaQualificationSnapshot snapshot(const std::string& routeKey) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (routeKey != routeKey_) {
            VulkanVisibleChromaQualificationSnapshot out{};
            out.routeKey = routeKey;
            out.status = "ROUTE_NOT_YET_BENCHMARKED";
            return out;
        }
        return snapshotLocked();
    }

    bool needsBenchmark(const std::string& routeKey) const {
        const auto current = snapshot(routeKey);
        return !current.benchmarkPerformed && !current.benchmarkAborted;
    }

    void setDeviceQualificationSelected(const std::string& routeKey, bool selected) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (routeKey.empty()) return;
        if (routeKey_ != routeKey) resetLocked(routeKey);
        deviceQualificationSelected_ = selected;
    }

private:
    void resetLocked(const std::string& routeKey) {
        routeKey_ = routeKey;
        warmupDiscarded_ = false;
        failedSampleCount_ = 0;
        deviceQualificationSelected_ = false;
        samples_.clear();
    }

    VulkanVisibleChromaQualificationSnapshot snapshotLocked() const {
        VulkanVisibleChromaQualificationSnapshot out{};
        out.routeKey = routeKey_;
        out.warmupDiscarded = warmupDiscarded_;
        out.successfulSampleCount = static_cast<int>(samples_.size());
        out.failedSampleCount = failedSampleCount_;
        out.deviceQualificationSelected = deviceQualificationSelected_;
        out.benchmarkAborted = failedSampleCount_ >= kMaximumFailedSamples;
        if (samples_.empty()) {
            out.status = out.benchmarkAborted
                    ? "VULKAN_VISIBLE_CHROMA_BENCHMARK_ABORTED_AFTER_REPEATED_FAILURES"
                    : (warmupDiscarded_
                            ? "WAITING_FOR_WARM_VULKAN_VISIBLE_CHROMA_SAMPLES"
                            : "WAITING_FOR_VULKAN_VISIBLE_CHROMA_WARMUP");
            return out;
        }

        std::vector<float> cpu;
        std::vector<float> kernel;
        std::vector<float> total;
        std::vector<float> transfer;
        float candidateMax = 0.0f;
        float finalMax = 0.0f;
        for (const auto& sample : samples_) {
            cpu.push_back(sample.cpuCandidateReferenceMs);
            kernel.push_back(sample.gpuKernelMs);
            total.push_back(sample.gpuTotalMs);
            transfer.push_back(sample.transferAndSyncMs);
            candidateMax = std::max(candidateMax, sample.candidateMaximumAbsoluteDelta);
            finalMax = std::max(finalMax, sample.finalDecisionMaximumAbsoluteDelta);
            out.inputBytes = std::max(out.inputBytes, sample.inputBytes);
            out.outputBytes = std::max(out.outputBytes, sample.outputBytes);
        }
        out.candidateMaximumAbsoluteDelta = candidateMax;
        out.finalDecisionMaximumAbsoluteDelta = finalMax;
        out.cpuCandidateMedianMs = vulkan_visible_chroma_qualification_detail::percentile(cpu, 0.50f);
        out.gpuKernelMedianMs = vulkan_visible_chroma_qualification_detail::percentile(kernel, 0.50f);
        out.gpuTotalMedianMs = vulkan_visible_chroma_qualification_detail::percentile(total, 0.50f);
        out.transferAndSyncMedianMs = vulkan_visible_chroma_qualification_detail::percentile(transfer, 0.50f);
        out.measuredSpeedup = out.gpuTotalMedianMs > 1.0e-6f
                ? out.cpuCandidateMedianMs / out.gpuTotalMedianMs : 0.0f;
        out.transferFraction = out.gpuTotalMedianMs > 1.0e-6f
                ? std::clamp(out.transferAndSyncMedianMs / out.gpuTotalMedianMs, 0.0f, 1.0f)
                : 1.0f;
        out.candidateNumericalEquivalencePassed = candidateMax <= 3.0e-6f;
        out.finalDecisionCompatibilityPassed = finalMax <= 3.0e-6f;
        out.benchmarkPerformed = samples_.size() >= 5u;
        if (out.benchmarkAborted && !out.benchmarkPerformed) {
            out.status = "VULKAN_VISIBLE_CHROMA_BENCHMARK_ABORTED_AFTER_REPEATED_FAILURES";
        } else if (!out.benchmarkPerformed) {
            out.status = "COLLECTING_VULKAN_VISIBLE_CHROMA_DEVICE_BENCHMARKS";
        } else if (!out.candidateNumericalEquivalencePassed) {
            out.status = "VULKAN_VISIBLE_CHROMA_SHADER_EQUIVALENCE_FAILED";
        } else if (!out.finalDecisionCompatibilityPassed) {
            out.status = "VULKAN_VISIBLE_CHROMA_FINAL_DECISION_COMPATIBILITY_FAILED";
        } else if (out.measuredSpeedup < 1.15f) {
            out.status = "VULKAN_VISIBLE_CHROMA_TOTAL_PATH_SPEEDUP_INSUFFICIENT";
        } else if (out.transferFraction > 0.35f) {
            out.status = "VULKAN_VISIBLE_CHROMA_TRANSFER_FRACTION_TOO_HIGH";
        } else {
            out.status = "VULKAN_VISIBLE_CHROMA_DEVICE_BENCHMARK_READY";
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
    std::deque<VulkanVisibleChromaBenchmarkSample> samples_;
};

inline VulkanVisibleChromaQualifier& vulkanVisibleChromaQualifier() {
    static VulkanVisibleChromaQualifier qualifier{};
    return qualifier;
}

} // namespace bncam::spectra2
