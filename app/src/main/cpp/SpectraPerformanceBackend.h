#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "SpectraPerformanceNeon.h"
#include "SpectraDeviceBackendProfiler.h"

namespace bncam::spectra2 {

enum class PerformanceBackendKind {
    ReferenceCpu,
    FusedTiledCpu,
    FusedTiledSimd,
    VulkanFp32
};

inline const char* performanceBackendName(PerformanceBackendKind backend) {
    switch (backend) {
        case PerformanceBackendKind::ReferenceCpu: return "REFERENCE_CPU";
        case PerformanceBackendKind::FusedTiledCpu: return "FUSED_TILED_CPU";
        case PerformanceBackendKind::FusedTiledSimd: return "FUSED_TILED_SIMD";
        case PerformanceBackendKind::VulkanFp32: return "VULKAN_FP32";
    }
    return "UNKNOWN";
}

struct FloatPlaneView {
    const float* data = nullptr;
    int width = 0;
    int height = 0;
    std::size_t strideFloats = 0;
    int cfaPattern = 0;

    bool valid() const {
        return data != nullptr && width > 0 && height > 0 && strideFloats >= static_cast<std::size_t>(width);
    }

    const float* row(int y) const {
        return data + static_cast<std::size_t>(y) * strideFloats;
    }
};

struct RawStatisticsRequest {
    bool collectSignal = true;
    bool collectResidual = true;
    bool collectChroma = true;
    int tileHeight = 128;
    int maximumWorkers = 4;
};

struct RawStatisticsSnapshot {
    std::array<double, 4> signalSum{0.0, 0.0, 0.0, 0.0};
    std::array<std::uint64_t, 4> signalCount{0, 0, 0, 0};
    double residualSquaredSum = 0.0;
    std::uint64_t residualSampleCount = 0;
    double chromaSquaredSum = 0.0;
    std::uint64_t chromaSampleCount = 0;
    float residualEnergy = 0.0f;
    float chromaResidualEnergy = 0.0f;
    std::uint64_t estimatedBytesRead = 0;
    std::uint64_t estimatedBytesWritten = 0;
    std::uint64_t scratchBytes = 0;
    std::uint64_t simdVectorizedLaneCount = 0;
    std::uint64_t simdRejectedNonFiniteLaneCount = 0;
    bool simdKernelUsed = false;
    int tileCount = 0;
    int workerCount = 1;
    float elapsedMs = 0.0f;
    std::string status = "NOT_RUN";
    std::string method = "UNINITIALIZED";
};

struct PerformanceBackendSelection {
    PerformanceBackendKind selected = PerformanceBackendKind::ReferenceCpu;
    bool neonCompiled = false;
    bool simdKernelActive = false;
    bool simdValidationPerformed = false;
    bool simdValidationPassed = false;
    float simdValidationMaximumAbsoluteDelta = 0.0f;
    float simdValidationMaximumRelativeDelta = 0.0f;
    float simdValidationElapsedMs = 0.0f;
    std::string simdValidationStatus = "NOT_RUN";
    std::string simdFallbackReason = "NONE";
    bool vulkanRuntimePrepared = false;
    bool vulkanSelected = false;
    std::string selectionReason = "REFERENCE_DEFAULT";
};

struct PerformanceTelemetry {
    PerformanceBackendSelection selection{};
    RawStatisticsSnapshot initialStatistics{};
    RawStatisticsSnapshot postPass1Statistics{};
    RawStatisticsSnapshot postPass2Statistics{};
    DeviceBackendProfileSnapshot deviceProfile{};
    VulkanFp32QualificationResult vulkanQualification{};
    std::uint64_t frameBytes = 0;
    int knownFullFrameCloneCount = 0;
    int fusedStatisticsDispatchCount = 0;
    float totalStatisticsMs = 0.0f;
    float estimatedReadPasses = 0.0f;
    std::string correctnessContract = "M8F_STRIPED_PERSISTENT_VULKAN_FP32_OPPONENT_AND_HARD_DEVICE_QUALIFICATION_GATES";
    std::string mixedPrecisionStatus = "FP16_DISABLED_CRITICAL_MATH_FP32";
    std::string vulkanStatus = "VULKAN_FP32_NOT_QUALIFIED";
};

inline bool neonCompiled() {
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    return true;
#else
    return false;
#endif
}

inline int performanceCfaChannel(int pattern, int x, int y) {
    const int xm = x & 1;
    const int ym = y & 1;
    switch (pattern >= 0 && pattern <= 3 ? pattern : 0) {
        case 0: return ym == 0 ? (xm == 0 ? 0 : 1) : (xm == 0 ? 2 : 3); // RGGB
        case 1: return ym == 0 ? (xm == 0 ? 1 : 0) : (xm == 0 ? 3 : 2); // GRBG
        case 2: return ym == 0 ? (xm == 0 ? 1 : 3) : (xm == 0 ? 0 : 2); // GBRG
        case 3: return ym == 0 ? (xm == 0 ? 3 : 1) : (xm == 0 ? 2 : 0); // BGGR
        default: return 0;
    }
}

namespace performance_detail {

struct PartialStatistics {
    std::array<double, 4> signalSum{0.0, 0.0, 0.0, 0.0};
    std::array<std::uint64_t, 4> signalCount{0, 0, 0, 0};
    double residualSquaredSum = 0.0;
    std::uint64_t residualSampleCount = 0;
    double chromaSquaredSum = 0.0;
    std::uint64_t chromaSampleCount = 0;
    std::uint64_t simdVectorizedLaneCount = 0;
    std::uint64_t simdRejectedNonFiniteLaneCount = 0;
};

inline int firstCongruentAtOrAfter(int start, int residue, int modulus) {
    const int normalized = ((start - residue) % modulus + modulus) % modulus;
    return normalized == 0 ? start : start + (modulus - normalized);
}

inline float greenReference(const FloatPlaneView& view, int x, int y) {
    return 0.25f * (
            view.row(y - 1)[x] + view.row(y + 1)[x] +
            view.row(y)[x - 1] + view.row(y)[x + 1]
    );
}

inline float chromaResidual(const FloatPlaneView& view, int x, int y) {
    return view.row(y)[x] - greenReference(view, x, y);
}

inline void processTile(
        const FloatPlaneView& view,
        const RawStatisticsRequest& request,
        int startY,
        int endY,
        PartialStatistics& partial
) {
    if (request.collectSignal) {
        for (int y = firstCongruentAtOrAfter(startY, 0, 8);
             y < std::min(endY, view.height - 1); y += 8) {
            for (int x = 0; x < view.width - 1; x += 8) {
                for (int dy = 0; dy < 2; ++dy) {
                    const float* row = view.row(y + dy);
                    for (int dx = 0; dx < 2; ++dx) {
                        const int channel = performanceCfaChannel(view.cfaPattern, x + dx, y + dy);
                        const float value = row[x + dx];
                        if (channel < 0 || channel >= 4 || !std::isfinite(value)) continue;
                        partial.signalSum[static_cast<std::size_t>(channel)] +=
                                std::clamp(static_cast<double>(value), 0.0, 1.0);
                        partial.signalCount[static_cast<std::size_t>(channel)]++;
                    }
                }
            }
        }
    }

    if (request.collectResidual && view.width >= 7 && view.height >= 7) {
        for (int y = firstCongruentAtOrAfter(std::max(startY, 2), 2, 4);
             y < std::min(endY, view.height - 3); y += 4) {
            for (int x = 2; x < view.width - 3; x += 4) {
                for (int dy = 0; dy < 2; ++dy) {
                    const int yy = y + dy;
                    const float* current = view.row(yy);
                    const float* minus2 = view.row(yy - 2);
                    const float* plus2 = view.row(yy + 2);
                    for (int dx = 0; dx < 2; ++dx) {
                        const int xx = x + dx;
                        const float center = current[xx];
                        const float neighbourMean = 0.25f * (
                                minus2[xx] + plus2[xx] + current[xx - 2] + current[xx + 2]
                        );
                        if (!std::isfinite(center) || !std::isfinite(neighbourMean)) continue;
                        const double difference = static_cast<double>(center - neighbourMean);
                        partial.residualSquaredSum += difference * difference;
                        partial.residualSampleCount++;
                    }
                }
            }
        }
    }

    if (request.collectChroma && view.width >= 9 && view.height >= 9) {
        for (int y = firstCongruentAtOrAfter(std::max(startY, 3), 3, 4);
             y < std::min(endY, view.height - 3); y += 4) {
            for (int x = 3; x < view.width - 3; x += 4) {
                for (int dy = 0; dy < 2; ++dy) {
                    const int yy = y + dy;
                    for (int dx = 0; dx < 2; ++dx) {
                        const int xx = x + dx;
                        const int channel = performanceCfaChannel(view.cfaPattern, xx, yy);
                        if (channel != 0 && channel != 3) continue;
                        const float center = chromaResidual(view, xx, yy);
                        const float neighbourMean = 0.25f * (
                                chromaResidual(view, xx - 2, yy) +
                                chromaResidual(view, xx + 2, yy) +
                                chromaResidual(view, xx, yy - 2) +
                                chromaResidual(view, xx, yy + 2)
                        );
                        if (!std::isfinite(center) || !std::isfinite(neighbourMean)) continue;
                        const double difference = static_cast<double>(center - neighbourMean);
                        partial.chromaSquaredSum += difference * difference;
                        partial.chromaSampleCount++;
                    }
                }
            }
        }
    }
}

inline void processTileSimd(
        const FloatPlaneView& view,
        const RawStatisticsRequest& request,
        int startY,
        int endY,
        PartialStatistics& partial
) {
#if BNCAM_SPECTRA_NEON_AVAILABLE
    if (request.collectSignal) {
        for (int y = firstCongruentAtOrAfter(startY, 0, 8);
             y < std::min(endY, view.height - 1); y += 8) {
            for (int x = 0; x < view.width - 1; x += 8) {
                float values[4]{};
                performance_neon::loadSignal2x2(view.row(y), view.row(y + 1), x, values);
                partial.simdVectorizedLaneCount += 4;
                for (int lane = 0; lane < 4; ++lane) {
                    const int dx = lane & 1;
                    const int dy = lane >> 1;
                    const int channel = performanceCfaChannel(view.cfaPattern, x + dx, y + dy);
                    const float value = values[lane];
                    if (channel < 0 || channel >= 4 || !std::isfinite(value)) {
                        partial.simdRejectedNonFiniteLaneCount += !std::isfinite(value) ? 1u : 0u;
                        continue;
                    }
                    partial.signalSum[static_cast<std::size_t>(channel)] +=
                            std::clamp(static_cast<double>(value), 0.0, 1.0);
                    partial.signalCount[static_cast<std::size_t>(channel)]++;
                }
            }
        }
    }

    if (request.collectResidual && view.width >= 7 && view.height >= 7) {
        for (int y = firstCongruentAtOrAfter(std::max(startY, 2), 2, 4);
             y < std::min(endY, view.height - 3); y += 4) {
            for (int x = 2; x < view.width - 3; x += 4) {
                for (int dy = 0; dy < 2; ++dy) {
                    const int yy = y + dy;
                    float differences[2]{};
                    performance_neon::residualDifferencePair(
                            view.row(yy), view.row(yy - 2), view.row(yy + 2), x, differences
                    );
                    partial.simdVectorizedLaneCount += 2;
                    for (int lane = 0; lane < 2; ++lane) {
                        const float difference = differences[lane];
                        if (!std::isfinite(difference)) {
                            partial.simdRejectedNonFiniteLaneCount++;
                            continue;
                        }
                        const double delta = static_cast<double>(difference);
                        partial.residualSquaredSum += delta * delta;
                        partial.residualSampleCount++;
                    }
                }
            }
        }
    }

    if (request.collectChroma && view.width >= 9 && view.height >= 9) {
        for (int y = firstCongruentAtOrAfter(std::max(startY, 3), 3, 4);
             y < std::min(endY, view.height - 3); y += 4) {
            for (int x = 3; x < view.width - 3; x += 4) {
                for (int dy = 0; dy < 2; ++dy) {
                    const int yy = y + dy;
                    float differences[2]{};
                    performance_neon::chromaDifferencePair(
                            view.row(yy - 3), view.row(yy - 2), view.row(yy - 1),
                            view.row(yy), view.row(yy + 1), view.row(yy + 2),
                            view.row(yy + 3), x, differences
                    );
                    partial.simdVectorizedLaneCount += 2;
                    for (int lane = 0; lane < 2; ++lane) {
                        const int xx = x + lane;
                        const int channel = performanceCfaChannel(view.cfaPattern, xx, yy);
                        if (channel != 0 && channel != 3) continue;
                        const float difference = differences[lane];
                        if (!std::isfinite(difference)) {
                            partial.simdRejectedNonFiniteLaneCount++;
                            continue;
                        }
                        const double delta = static_cast<double>(difference);
                        partial.chromaSquaredSum += delta * delta;
                        partial.chromaSampleCount++;
                    }
                }
            }
        }
    }
#else
    processTile(view, request, startY, endY, partial);
#endif
}

struct SimdEquivalenceReport {
    bool performed = false;
    bool passed = false;
    float maximumAbsoluteDelta = 0.0f;
    float maximumRelativeDelta = 0.0f;
    float elapsedMs = 0.0f;
    std::string status = "NOT_RUN";
};

inline void updateEquivalenceDelta(
        double reference,
        double candidate,
        double& maximumAbsoluteDelta,
        double& maximumRelativeDelta
) {
    const double absoluteDelta = std::abs(reference - candidate);
    const double denominator = std::max({1.0e-12, std::abs(reference), std::abs(candidate)});
    maximumAbsoluteDelta = std::max(maximumAbsoluteDelta, absoluteDelta);
    maximumRelativeDelta = std::max(maximumRelativeDelta, absoluteDelta / denominator);
}

inline SimdEquivalenceReport runSimdEquivalenceSelfTest() {
    SimdEquivalenceReport report{};
#if BNCAM_SPECTRA_NEON_AVAILABLE
    const auto started = std::chrono::steady_clock::now();
    report.performed = true;
    constexpr int width = 130;
    constexpr int height = 98;
    std::vector<float> fixture(static_cast<std::size_t>(width) * height);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const float ramp = static_cast<float>((x * 29 + y * 17) % 2048) / 2047.0f;
            const float phase = static_cast<float>(((x & 1) << 1) | (y & 1)) * 0.00037f;
            fixture[static_cast<std::size_t>(y) * width + x] = 0.012f + 0.82f * ramp + phase;
        }
    }
    fixture[17u * width + 19u] = std::numeric_limits<float>::quiet_NaN();
    fixture[41u * width + 43u] = std::numeric_limits<float>::infinity();

    const std::array<RawStatisticsRequest, 3> requests{{
            RawStatisticsRequest{true, true, false, 64, 1},
            RawStatisticsRequest{false, true, true, 64, 1},
            RawStatisticsRequest{true, true, true, 64, 1}
    }};
    bool countsEqual = true;
    double maximumAbsoluteDelta = 0.0;
    double maximumRelativeDelta = 0.0;
    for (int pattern = 0; pattern < 4; ++pattern) {
        const FloatPlaneView view{
                fixture.data(), width, height, static_cast<std::size_t>(width), pattern
        };
        for (const auto& request : requests) {
            PartialStatistics scalar{};
            PartialStatistics simd{};
            processTile(view, request, 0, height, scalar);
            processTileSimd(view, request, 0, height, simd);
            countsEqual = countsEqual &&
                    scalar.signalCount == simd.signalCount &&
                    scalar.residualSampleCount == simd.residualSampleCount &&
                    scalar.chromaSampleCount == simd.chromaSampleCount;
            for (std::size_t channel = 0; channel < 4; ++channel) {
                updateEquivalenceDelta(
                        scalar.signalSum[channel], simd.signalSum[channel],
                        maximumAbsoluteDelta, maximumRelativeDelta
                );
            }
            updateEquivalenceDelta(
                    scalar.residualSquaredSum, simd.residualSquaredSum,
                    maximumAbsoluteDelta, maximumRelativeDelta
            );
            updateEquivalenceDelta(
                    scalar.chromaSquaredSum, simd.chromaSquaredSum,
                    maximumAbsoluteDelta, maximumRelativeDelta
            );
        }
    }
    constexpr double maximumAllowedAbsoluteDelta = 2.5e-5;
    constexpr double maximumAllowedRelativeDelta = 2.5e-5;
    report.maximumAbsoluteDelta = static_cast<float>(maximumAbsoluteDelta);
    report.maximumRelativeDelta = static_cast<float>(maximumRelativeDelta);
    report.passed = countsEqual &&
            maximumAbsoluteDelta <= maximumAllowedAbsoluteDelta &&
            maximumRelativeDelta <= maximumAllowedRelativeDelta;
    report.status = !countsEqual
            ? "FAILED_SAMPLE_COUNT_MISMATCH"
            : (report.passed
                    ? "PASSED_RUNTIME_NEON_SCALAR_EQUIVALENCE"
                    : "FAILED_NUMERICAL_TOLERANCE");
    report.elapsedMs = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - started
    ).count();
#else
    report.status = "NEON_NOT_COMPILED";
#endif
    return report;
}

inline const SimdEquivalenceReport& cachedSimdEquivalenceSelfTest() {
    static const SimdEquivalenceReport report = runSimdEquivalenceSelfTest();
    return report;
}

} // namespace performance_detail


inline PerformanceBackendSelection selectPerformanceBackend(
        int width,
        int height,
        bool spectraEnabled
) {
    PerformanceBackendSelection selection{};
    selection.neonCompiled = neonCompiled();
#if defined(BNCAM_VULKAN_RUNTIME_PREPARED) && BNCAM_VULKAN_RUNTIME_PREPARED
    selection.vulkanRuntimePrepared = true;
#endif
    const std::int64_t pixels = static_cast<std::int64_t>(std::max(0, width)) * std::max(0, height);
    if (pixels < 512 * 512) {
        selection.selected = PerformanceBackendKind::ReferenceCpu;
        selection.selectionReason = "SMALL_FRAME_REFERENCE_LOWER_SCHEDULING_OVERHEAD";
        selection.simdFallbackReason = "SMALL_FRAME_SIMD_NOT_BENEFICIAL";
        return selection;
    }

#if defined(BNCAM_SPECTRA_DISABLE_NEON) && BNCAM_SPECTRA_DISABLE_NEON
    selection.selected = PerformanceBackendKind::FusedTiledCpu;
    selection.selectionReason = "M8B_FUSED_TILED_SCALAR_NEON_EXPLICITLY_DISABLED";
    selection.simdFallbackReason = "COMPILE_TIME_DISABLE";
#else
    if (selection.neonCompiled) {
        const auto& validation = performance_detail::cachedSimdEquivalenceSelfTest();
        selection.simdValidationPerformed = validation.performed;
        selection.simdValidationPassed = validation.passed;
        selection.simdValidationMaximumAbsoluteDelta = validation.maximumAbsoluteDelta;
        selection.simdValidationMaximumRelativeDelta = validation.maximumRelativeDelta;
        selection.simdValidationElapsedMs = validation.elapsedMs;
        selection.simdValidationStatus = validation.status;
        if (validation.passed) {
            selection.selected = PerformanceBackendKind::FusedTiledSimd;
            selection.simdKernelActive = true;
            selection.selectionReason = spectraEnabled
                    ? "M8B_NEON_RUNTIME_EQUIVALENT_SPECTRA_ACTIVE"
                    : "M8B_NEON_RUNTIME_EQUIVALENT_BASELINE";
            selection.simdFallbackReason = "NONE";
        } else {
            selection.selected = PerformanceBackendKind::FusedTiledCpu;
            selection.selectionReason = "M8B_SCALAR_FALLBACK_NEON_SELF_TEST_FAILED";
            selection.simdFallbackReason = validation.status;
        }
    } else {
        selection.selected = PerformanceBackendKind::FusedTiledCpu;
        selection.selectionReason = spectraEnabled
                ? "M8B_FUSED_TILED_SCALAR_NEON_UNAVAILABLE_SPECTRA_ACTIVE"
                : "M8B_FUSED_TILED_SCALAR_NEON_UNAVAILABLE_BASELINE";
        selection.simdValidationStatus = "NEON_NOT_COMPILED";
        selection.simdFallbackReason = "NEON_NOT_COMPILED";
    }
#endif
    selection.vulkanSelected = false;
    return selection;
}

inline RawStatisticsSnapshot collectReferenceRawStatistics(
        const FloatPlaneView& view,
        RawStatisticsRequest request = {}
) {
    RawStatisticsSnapshot snapshot{};
    const auto started = std::chrono::steady_clock::now();
    if (!view.valid()) {
        snapshot.status = "INVALID_FLOAT_PLANE";
        snapshot.method = "NOT_RUN";
        return snapshot;
    }
    performance_detail::PartialStatistics partial{};
    performance_detail::processTile(view, request, 0, view.height, partial);
    snapshot.signalSum = partial.signalSum;
    snapshot.signalCount = partial.signalCount;
    snapshot.residualSquaredSum = partial.residualSquaredSum;
    snapshot.residualSampleCount = partial.residualSampleCount;
    snapshot.chromaSquaredSum = partial.chromaSquaredSum;
    snapshot.chromaSampleCount = partial.chromaSampleCount;
    snapshot.residualEnergy = snapshot.residualSampleCount > 0
            ? static_cast<float>(snapshot.residualSquaredSum /
                    static_cast<double>(snapshot.residualSampleCount))
            : 0.0f;
    snapshot.chromaResidualEnergy = snapshot.chromaSampleCount > 0
            ? static_cast<float>(snapshot.chromaSquaredSum /
                    static_cast<double>(snapshot.chromaSampleCount))
            : 0.0f;
    const std::uint64_t signalSamples = snapshot.signalCount[0] + snapshot.signalCount[1] +
            snapshot.signalCount[2] + snapshot.signalCount[3];
    snapshot.estimatedBytesRead = signalSamples * sizeof(float) +
            snapshot.residualSampleCount * 5u * sizeof(float) +
            snapshot.chromaSampleCount * 25u * sizeof(float);
    snapshot.scratchBytes = sizeof(performance_detail::PartialStatistics);
    snapshot.tileCount = 1;
    snapshot.workerCount = 1;
    snapshot.elapsedMs = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - started
    ).count();
    snapshot.status = "OK";
    snapshot.method = "M8A_SEQUENTIAL_REFERENCE_STATISTICS";
    return snapshot;
}

inline RawStatisticsSnapshot collectFusedTiledRawStatisticsImpl(
        const FloatPlaneView& view,
        RawStatisticsRequest request,
        bool useSimd
) {
    RawStatisticsSnapshot snapshot{};
    const auto started = std::chrono::steady_clock::now();
    if (!view.valid()) {
        snapshot.status = "INVALID_FLOAT_PLANE";
        snapshot.method = "NOT_RUN";
        return snapshot;
    }

    request.tileHeight = std::clamp(request.tileHeight, 32, 512);
    request.maximumWorkers = std::clamp(request.maximumWorkers, 1, 8);
    const int tileCount = std::max(1, (view.height + request.tileHeight - 1) / request.tileHeight);
    const unsigned hardwareThreads = std::max(1u, std::thread::hardware_concurrency());
    const int workerCount = std::max(1, std::min({
            tileCount,
            request.maximumWorkers,
            static_cast<int>(hardwareThreads)
    }));
    std::vector<performance_detail::PartialStatistics> partials(static_cast<std::size_t>(tileCount));
    std::atomic<int> nextTile{0};
    auto worker = [&]() {
        while (true) {
            const int tile = nextTile.fetch_add(1, std::memory_order_relaxed);
            if (tile >= tileCount) break;
            const int startY = tile * request.tileHeight;
            const int endY = std::min(view.height, startY + request.tileHeight);
            if (useSimd) {
                performance_detail::processTileSimd(
                        view, request, startY, endY, partials[static_cast<std::size_t>(tile)]
                );
            } else {
                performance_detail::processTile(
                        view, request, startY, endY, partials[static_cast<std::size_t>(tile)]
                );
            }
        }
    };

    std::vector<std::thread> threads;
    threads.reserve(static_cast<std::size_t>(std::max(0, workerCount - 1)));
    for (int index = 1; index < workerCount; ++index) threads.emplace_back(worker);
    worker();
    for (auto& thread : threads) thread.join();

    // Deterministic tile-order reduction keeps results stable across worker scheduling.
    for (const auto& partial : partials) {
        for (std::size_t channel = 0; channel < 4; ++channel) {
            snapshot.signalSum[channel] += partial.signalSum[channel];
            snapshot.signalCount[channel] += partial.signalCount[channel];
        }
        snapshot.residualSquaredSum += partial.residualSquaredSum;
        snapshot.residualSampleCount += partial.residualSampleCount;
        snapshot.chromaSquaredSum += partial.chromaSquaredSum;
        snapshot.chromaSampleCount += partial.chromaSampleCount;
        snapshot.simdVectorizedLaneCount += partial.simdVectorizedLaneCount;
        snapshot.simdRejectedNonFiniteLaneCount += partial.simdRejectedNonFiniteLaneCount;
    }
    snapshot.residualEnergy = snapshot.residualSampleCount > 0
            ? static_cast<float>(snapshot.residualSquaredSum /
                    static_cast<double>(snapshot.residualSampleCount))
            : 0.0f;
    snapshot.chromaResidualEnergy = snapshot.chromaSampleCount > 0
            ? static_cast<float>(snapshot.chromaSquaredSum /
                    static_cast<double>(snapshot.chromaSampleCount))
            : 0.0f;
    const std::uint64_t signalSamples = snapshot.signalCount[0] + snapshot.signalCount[1] +
            snapshot.signalCount[2] + snapshot.signalCount[3];
    snapshot.estimatedBytesRead = signalSamples * sizeof(float) +
            snapshot.residualSampleCount * 5u * sizeof(float) +
            snapshot.chromaSampleCount * 25u * sizeof(float);
    snapshot.estimatedBytesWritten = 0;
    snapshot.scratchBytes = static_cast<std::uint64_t>(partials.size()) *
            sizeof(performance_detail::PartialStatistics);
    snapshot.tileCount = tileCount;
    snapshot.workerCount = workerCount;
    snapshot.elapsedMs = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - started
    ).count();
    snapshot.status = "OK";
    snapshot.simdKernelUsed = useSimd;
    snapshot.method = useSimd
            ? "M8B_DETERMINISTIC_FUSED_TILED_NEON_REDUCTION"
            : "M8B_DETERMINISTIC_FUSED_TILED_SCALAR_FALLBACK";
    return snapshot;
}

inline RawStatisticsSnapshot collectFusedTiledRawStatistics(
        const FloatPlaneView& view,
        RawStatisticsRequest request = {}
) {
    return collectFusedTiledRawStatisticsImpl(view, request, false);
}

inline RawStatisticsSnapshot collectFusedTiledSimdRawStatistics(
        const FloatPlaneView& view,
        RawStatisticsRequest request = {}
) {
#if BNCAM_SPECTRA_NEON_AVAILABLE
    return collectFusedTiledRawStatisticsImpl(view, request, true);
#else
    RawStatisticsSnapshot snapshot = collectFusedTiledRawStatisticsImpl(view, request, false);
    snapshot.method = "M8B_SCALAR_FALLBACK_NEON_NOT_COMPILED";
    return snapshot;
#endif
}

inline RawStatisticsSnapshot collectRawStatistics(
        const FloatPlaneView& view,
        const PerformanceBackendSelection& selection,
        RawStatisticsRequest request = {}
) {
    switch (selection.selected) {
        case PerformanceBackendKind::ReferenceCpu:
            return collectReferenceRawStatistics(view, request);
        case PerformanceBackendKind::FusedTiledSimd:
            return collectFusedTiledSimdRawStatistics(view, request);
        case PerformanceBackendKind::FusedTiledCpu:
        case PerformanceBackendKind::VulkanFp32:
            return collectFusedTiledRawStatistics(view, request);
    }
    return collectFusedTiledRawStatistics(view, request);
}

} // namespace bncam::spectra2
