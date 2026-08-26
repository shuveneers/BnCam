#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <limits>
#include <vector>
#include "SpectraPerformanceBackend.h"

using bncam::spectra2::FloatPlaneView;
using bncam::spectra2::RawStatisticsRequest;

static void requireNear(double a, double b, double absoluteTolerance, double relativeTolerance) {
    const double delta = std::abs(a - b);
    const double scale = std::max({1.0e-12, std::abs(a), std::abs(b)});
    if (delta > absoluteTolerance && delta / scale > relativeTolerance) {
        std::cerr << "Mismatch " << a << " vs " << b << " delta=" << delta << "\n";
        std::abort();
    }
}

int main() {
#if !BNCAM_SPECTRA_NEON_AVAILABLE
    std::cout << "SPECTRA_PERFORMANCE_NEON_TESTS_SKIPPED_NEON_NOT_COMPILED\n";
    return 0;
#else
    constexpr int width = 514;
    constexpr int height = 386;
    std::vector<float> plane(static_cast<std::size_t>(width) * height);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const float base = 0.015f + 0.8f * static_cast<float>((x * 31 + y * 23) % 4096) / 4095.0f;
            const float phase = static_cast<float>(((x & 1) << 1) | (y & 1)) * 0.00021f;
            plane[static_cast<std::size_t>(y) * width + x] = base + phase;
        }
    }
    plane[17u * width + 19u] = std::numeric_limits<float>::quiet_NaN();
    plane[77u * width + 83u] = std::numeric_limits<float>::infinity();

    for (int pattern = 0; pattern < 4; ++pattern) {
        const FloatPlaneView view{plane.data(), width, height, static_cast<std::size_t>(width), pattern};
        for (const auto request : std::array<RawStatisticsRequest, 3>{{
                RawStatisticsRequest{true, true, false, 64, 4},
                RawStatisticsRequest{false, true, true, 96, 3},
                RawStatisticsRequest{true, true, true, 128, 4}
        }}) {
            const auto scalar = bncam::spectra2::collectFusedTiledRawStatistics(view, request);
            const auto simd = bncam::spectra2::collectFusedTiledSimdRawStatistics(view, request);
            assert(scalar.status == "OK");
            assert(simd.status == "OK");
            assert(simd.simdKernelUsed);
            assert(simd.simdVectorizedLaneCount > 0);
            assert(scalar.signalCount == simd.signalCount);
            assert(scalar.residualSampleCount == simd.residualSampleCount);
            assert(scalar.chromaSampleCount == simd.chromaSampleCount);
            for (std::size_t channel = 0; channel < 4; ++channel) {
                requireNear(scalar.signalSum[channel], simd.signalSum[channel], 2.5e-5, 2.5e-5);
            }
            requireNear(scalar.residualSquaredSum, simd.residualSquaredSum, 2.5e-5, 2.5e-5);
            requireNear(scalar.chromaSquaredSum, simd.chromaSquaredSum, 2.5e-5, 2.5e-5);
        }
    }

    const auto report = bncam::spectra2::performance_detail::cachedSimdEquivalenceSelfTest();
    assert(report.performed);
    assert(report.passed);
    const auto selection = bncam::spectra2::selectPerformanceBackend(4000, 3000, true);
    assert(selection.neonCompiled);
    assert(selection.simdValidationPerformed);
    assert(selection.simdValidationPassed);
    assert(selection.simdKernelActive);
    assert(selection.selected == bncam::spectra2::PerformanceBackendKind::FusedTiledSimd);
    assert(!selection.vulkanSelected);
    std::cout << "SPECTRA_PERFORMANCE_NEON_TESTS_OK\n";
    return 0;
#endif
}
