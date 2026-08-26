#pragma once

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <mutex>
#include <string>
#include <vector>

#include "SpectraPixelBackendNeon.h"

namespace bncam::spectra2 {

enum class PixelKernelBackendKind {
    ScalarReference,
    TiledCpu,
    TiledNeon,
    VulkanFp32
};

inline const char* pixelKernelBackendName(PixelKernelBackendKind backend) {
    switch (backend) {
        case PixelKernelBackendKind::ScalarReference: return "SCALAR_REFERENCE";
        case PixelKernelBackendKind::TiledCpu: return "TILED_CPU";
        case PixelKernelBackendKind::TiledNeon: return "TILED_NEON";
        case PixelKernelBackendKind::VulkanFp32: return "VULKAN_FP32";
    }
    return "UNKNOWN";
}

struct RgbFloatFrameView {
    const float* data = nullptr;
    int width = 0;
    int height = 0;
    std::size_t strideFloats = 0;

    bool valid() const {
        return data != nullptr && width > 0 && height > 0 &&
                strideFloats >= static_cast<std::size_t>(width) * 3u;
    }

    const float* row(int y) const {
        return data + static_cast<std::size_t>(y) * strideFloats;
    }
};

struct OpponentFeatureFrame {
    int width = 0;
    int height = 0;
    std::vector<float> rgba; // Y, R-G, B-G, reserved.

    bool valid() const {
        return width > 0 && height > 0 &&
                rgba.size() == static_cast<std::size_t>(width) *
                        static_cast<std::size_t>(height) * 4u;
    }

    std::size_t index(int x, int y) const {
        return (static_cast<std::size_t>(y) * static_cast<std::size_t>(width) +
                static_cast<std::size_t>(x)) * 4u;
    }
};

struct OpponentFeatureBuildResult {
    OpponentFeatureFrame frame{};
    std::uint64_t rejectedNonFinitePixelCount = 0;
    std::uint64_t estimatedBytesRead = 0;
    std::uint64_t estimatedBytesWritten = 0;
    float elapsedMs = 0.0f;
    std::string status = "NOT_RUN";
};

struct PixelKernelSelfTestResult {
    bool performed = false;
    bool passed = false;
    float maximumAbsoluteDelta = 0.0f;
    float elapsedMs = 0.0f;
    bool latencyBenchmarkPerformed = false;
    bool latencyBenchmarkPassed = false;
    float scalarBenchmarkMs = 0.0f;
    float neonBenchmarkMs = 0.0f;
    float benchmarkSpeedup = 0.0f;
    std::string status = "NOT_RUN";
};

struct PixelKernelSelection {
    PixelKernelBackendKind selected = PixelKernelBackendKind::ScalarReference;
    bool neonCompiled = false;
    bool selfTestPerformed = false;
    bool selfTestPassed = false;
    float selfTestMaximumAbsoluteDelta = 0.0f;
    float selfTestElapsedMs = 0.0f;
    bool latencyBenchmarkPerformed = false;
    bool latencyBenchmarkPassed = false;
    float scalarBenchmarkMs = 0.0f;
    float neonBenchmarkMs = 0.0f;
    float benchmarkSpeedup = 0.0f;
    int tileSize = 64;
    std::string selectionReason = "UNINITIALIZED";
    std::string fallbackReason = "none";
};

struct OpponentTileBuildTelemetry {
    PixelKernelBackendKind backend = PixelKernelBackendKind::ScalarReference;
    std::uint64_t scalarPixelCount = 0;
    std::uint64_t vectorizedPixelCount = 0;
    std::uint64_t rejectedNonFinitePixelCount = 0;
    std::uint64_t estimatedBytesRead = 0;
    std::uint64_t estimatedBytesWritten = 0;
    std::uint64_t scratchBytes = 0;
    float elapsedMs = 0.0f;
};

struct OpponentTileBuffer {
    int width = 0;
    int height = 0;
    int halo = 0;
    std::vector<float> luma;
    std::vector<float> rg;
    std::vector<float> bg;
    std::vector<float> saturation;

    void resize(int requestedWidth, int requestedHeight, int requestedHalo) {
        width = std::max(0, requestedWidth);
        height = std::max(0, requestedHeight);
        halo = std::max(0, requestedHalo);
        const std::size_t count = static_cast<std::size_t>(width) *
                static_cast<std::size_t>(height);
        luma.resize(count);
        rg.resize(count);
        bg.resize(count);
        saturation.resize(count);
    }

    std::size_t index(int x, int y) const {
        return static_cast<std::size_t>(y) * static_cast<std::size_t>(width) +
                static_cast<std::size_t>(x);
    }

    std::uint64_t scratchBytes() const {
        return static_cast<std::uint64_t>(
                (luma.capacity() + rg.capacity() + bg.capacity() + saturation.capacity()) *
                        sizeof(float)
        );
    }
};

inline void opponentScalar(
        float r,
        float g,
        float b,
        float& y,
        float& rg,
        float& bg,
        float& saturation
) {
    y = (0.2126f * r + 0.7152f * g) + 0.0722f * b;
    rg = r - g;
    bg = b - g;
    const float maximum = std::max({r, g, b});
    const float minimum = std::min({r, g, b});
    saturation = (maximum - minimum) / std::max(0.02f, maximum);
}

inline bool finiteRgb(const float* rgb) {
    return std::isfinite(rgb[0]) && std::isfinite(rgb[1]) && std::isfinite(rgb[2]);
}

inline OpponentFeatureBuildResult buildOpponentFeatureFrameScalar(
        const RgbFloatFrameView& frame
) {
    OpponentFeatureBuildResult result{};
    const auto started = std::chrono::steady_clock::now();
    if (!frame.valid()) {
        result.status = "INVALID_RGB_FLOAT_FRAME";
        return result;
    }
    result.frame.width = frame.width;
    result.frame.height = frame.height;
    result.frame.rgba.resize(
            static_cast<std::size_t>(frame.width) *
            static_cast<std::size_t>(frame.height) * 4u
    );
    const float nan = std::numeric_limits<float>::quiet_NaN();
    for (int y = 0; y < frame.height; ++y) {
        const float* row = frame.row(y);
        for (int x = 0; x < frame.width; ++x) {
            const float* rgb = row + static_cast<std::size_t>(x) * 3u;
            const std::size_t out = result.frame.index(x, y);
            if (!finiteRgb(rgb)) {
                result.frame.rgba[out] = nan;
                result.frame.rgba[out + 1u] = nan;
                result.frame.rgba[out + 2u] = nan;
                result.frame.rgba[out + 3u] = 1.0f;
                result.rejectedNonFinitePixelCount++;
                continue;
            }
            float luma = 0.0f;
            float rg = 0.0f;
            float bg = 0.0f;
            float saturation = 0.0f;
            opponentScalar(rgb[0], rgb[1], rgb[2], luma, rg, bg, saturation);
            result.frame.rgba[out] = luma;
            result.frame.rgba[out + 1u] = rg;
            result.frame.rgba[out + 2u] = bg;
            result.frame.rgba[out + 3u] = saturation;
        }
    }
    const std::uint64_t pixels = static_cast<std::uint64_t>(frame.width) *
            static_cast<std::uint64_t>(frame.height);
    result.estimatedBytesRead = pixels * 3u * sizeof(float);
    result.estimatedBytesWritten = pixels * 4u * sizeof(float);
    result.elapsedMs = static_cast<float>(std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started
    ).count());
    result.status = "SCALAR_FULL_FRAME_OPPONENT_FEATURES_READY";
    return result;
}

inline float compareOpponentFeatureFrames(
        const OpponentFeatureFrame& reference,
        const OpponentFeatureFrame& candidate
) {
    if (!reference.valid() || !candidate.valid() ||
        reference.width != candidate.width || reference.height != candidate.height) {
        return std::numeric_limits<float>::infinity();
    }
    float maximumDelta = 0.0f;
    for (std::size_t pixel = 0; pixel <
            static_cast<std::size_t>(reference.width) *
            static_cast<std::size_t>(reference.height); ++pixel) {
        const std::size_t base = pixel * 4u;
        for (std::size_t channel = 0; channel < 3u; ++channel) {
            const float a = reference.rgba[base + channel];
            const float b = candidate.rgba[base + channel];
            if (!std::isfinite(a) || !std::isfinite(b)) {
                if (std::isnan(a) && std::isnan(b)) continue;
                return std::numeric_limits<float>::infinity();
            }
            maximumDelta = std::max(maximumDelta, std::abs(a - b));
        }
    }
    return maximumDelta;
}


struct OpponentFeatureScalarComparisonResult {
    bool performed = false;
    float maximumAbsoluteDelta = std::numeric_limits<float>::infinity();
    float cpuReferenceMs = 0.0f;
    std::uint64_t rejectedNonFinitePixelCount = 0;
    std::string status = "NOT_RUN";
};

inline OpponentFeatureScalarComparisonResult compareOpponentFeatureFrameToScalar(
        const RgbFloatFrameView& reference,
        const OpponentFeatureFrame& candidate
) {
    OpponentFeatureScalarComparisonResult result{};
    const auto started = std::chrono::steady_clock::now();
    if (!reference.valid() || !candidate.valid() ||
        reference.width != candidate.width || reference.height != candidate.height) {
        result.status = "INVALID_SCALAR_OR_CANDIDATE_FRAME";
        result.cpuReferenceMs = static_cast<float>(std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - started
        ).count());
        return result;
    }
    result.performed = true;
    float maximumDelta = 0.0f;
    for (int y = 0; y < reference.height; ++y) {
        const float* row = reference.row(y);
        for (int x = 0; x < reference.width; ++x) {
            const float* rgb = row + static_cast<std::size_t>(x) * 3u;
            const std::size_t base = candidate.index(x, y);
            if (!finiteRgb(rgb)) {
                result.rejectedNonFinitePixelCount++;
                const bool candidateNonFinite =
                        !std::isfinite(candidate.rgba[base]) &&
                        !std::isfinite(candidate.rgba[base + 1u]) &&
                        !std::isfinite(candidate.rgba[base + 2u]);
                if (!candidateNonFinite) maximumDelta = std::numeric_limits<float>::infinity();
                continue;
            }
            float expectedY = 0.0f;
            float expectedRG = 0.0f;
            float expectedBG = 0.0f;
            float saturation = 0.0f;
            opponentScalar(
                    rgb[0], rgb[1], rgb[2],
                    expectedY, expectedRG, expectedBG, saturation
            );
            const float expected[3]{expectedY, expectedRG, expectedBG};
            for (std::size_t channel = 0; channel < 3u; ++channel) {
                const float actual = candidate.rgba[base + channel];
                if (!std::isfinite(actual)) {
                    maximumDelta = std::numeric_limits<float>::infinity();
                    continue;
                }
                maximumDelta = std::max(maximumDelta, std::abs(expected[channel] - actual));
            }
        }
    }
    result.maximumAbsoluteDelta = maximumDelta;
    result.cpuReferenceMs = static_cast<float>(std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started
    ).count());
    result.status = std::isfinite(maximumDelta)
            ? "SCALAR_VULKAN_OPPONENT_COMPARISON_READY"
            : "SCALAR_VULKAN_OPPONENT_COMPARISON_FAILED";
    return result;
}


struct OpponentFeatureCpuBenchmarkResult {
    bool performed = false;
    float elapsedMs = 0.0f;
    std::uint64_t vectorizedPixelCount = 0;
    std::uint64_t scalarPixelCount = 0;
    std::uint64_t rejectedNonFinitePixelCount = 0;
    double checksum = 0.0;
    std::string backend = "NOT_RUN";
    std::string status = "NOT_RUN";
};

/**
 * Conservative isolated CPU reference for Vulkan opponent-stage qualification.
 *
 * It measures only the active CPU Y/R-G/B-G transform and intentionally excludes
 * candidate comparison. Vulkan is compared against its complete allocation,
 * packing, dispatch, synchronization and readback path, so this gate cannot gain
 * credit from comparison overhead. The result is consumed only as benchmark
 * evidence; it never changes image pixels.
 */
inline OpponentFeatureCpuBenchmarkResult benchmarkOpponentFeaturesCpu(
        const RgbFloatFrameView& frame,
        const PixelKernelSelection& selection
) {
    OpponentFeatureCpuBenchmarkResult result{};
    const auto started = std::chrono::steady_clock::now();
    if (!frame.valid()) {
        result.status = "INVALID_RGB_FLOAT_FRAME";
        return result;
    }
    result.performed = true;
    result.backend = pixelKernelBackendName(selection.selected);
    volatile double checksum = 0.0;
    for (int y = 0; y < frame.height; ++y) {
        const float* row = frame.row(y);
        int x = 0;
#if BNCAM_SPECTRA_PIXEL_NEON_AVAILABLE
        if (selection.selected == PixelKernelBackendKind::TiledNeon &&
            selection.selfTestPassed && selection.latencyBenchmarkPassed) {
            for (; x + 3 < frame.width; x += 4) {
                const float* first = row + static_cast<std::size_t>(x) * 3u;
                bool allFinite = true;
                for (int lane = 0; lane < 4; ++lane) {
                    allFinite = allFinite && finiteRgb(first + static_cast<std::size_t>(lane) * 3u);
                }
                if (allFinite) {
                    float luma[4]{}, rg[4]{}, bg[4]{}, saturation[4]{};
                    pixel_neon::opponentQuad(first, luma, rg, bg, saturation);
                    for (int lane = 0; lane < 4; ++lane) {
                        checksum += static_cast<double>(luma[lane]) +
                                static_cast<double>(rg[lane]) +
                                static_cast<double>(bg[lane]);
                    }
                    result.vectorizedPixelCount += 4u;
                    continue;
                }
                for (int lane = 0; lane < 4; ++lane) {
                    const float* rgb = first + static_cast<std::size_t>(lane) * 3u;
                    if (!finiteRgb(rgb)) {
                        result.rejectedNonFinitePixelCount++;
                        continue;
                    }
                    float luma = 0.0f, rg = 0.0f, bg = 0.0f, saturation = 0.0f;
                    opponentScalar(rgb[0], rgb[1], rgb[2], luma, rg, bg, saturation);
                    checksum += static_cast<double>(luma) + rg + bg;
                    result.scalarPixelCount++;
                }
            }
        }
#endif
        for (; x < frame.width; ++x) {
            const float* rgb = row + static_cast<std::size_t>(x) * 3u;
            if (!finiteRgb(rgb)) {
                result.rejectedNonFinitePixelCount++;
                continue;
            }
            float luma = 0.0f, rg = 0.0f, bg = 0.0f, saturation = 0.0f;
            opponentScalar(rgb[0], rgb[1], rgb[2], luma, rg, bg, saturation);
            checksum += static_cast<double>(luma) + rg + bg;
            result.scalarPixelCount++;
        }
    }
    result.checksum = checksum;
    result.elapsedMs = static_cast<float>(std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started
    ).count());
    result.status = std::isfinite(result.checksum)
            ? "CPU_OPPONENT_REFERENCE_BENCHMARK_READY"
            : "CPU_OPPONENT_REFERENCE_BENCHMARK_NON_FINITE";
    return result;
}

inline PixelKernelSelfTestResult runPixelKernelSelfTest() {
    PixelKernelSelfTestResult result{};
    result.performed = true;
    const auto start = std::chrono::steady_clock::now();
#if BNCAM_SPECTRA_PIXEL_NEON_AVAILABLE && !defined(BNCAM_SPECTRA_DISABLE_PIXEL_NEON)
    alignas(16) std::array<float, 12> rgb{
            0.0f, 0.0f, 0.0f,
            0.04f, 0.08f, 0.12f,
            0.65f, 0.31f, 0.16f,
            1.0f, 0.98f, 0.91f
    };
    float yNeon[4]{}, rgNeon[4]{}, bgNeon[4]{}, saturationNeon[4]{};
    pixel_neon::opponentQuad(
            rgb.data(), yNeon, rgNeon, bgNeon, saturationNeon
    );
    float maximumDelta = 0.0f;
    for (int lane = 0; lane < 4; ++lane) {
        float y = 0.0f, rg = 0.0f, bg = 0.0f, saturation = 0.0f;
        opponentScalar(
                rgb[static_cast<std::size_t>(lane) * 3u],
                rgb[static_cast<std::size_t>(lane) * 3u + 1u],
                rgb[static_cast<std::size_t>(lane) * 3u + 2u],
                y, rg, bg, saturation
        );
        maximumDelta = std::max({
                maximumDelta,
                std::abs(y - yNeon[lane]),
                std::abs(rg - rgNeon[lane]),
                std::abs(bg - bgNeon[lane]),
                std::abs(saturation - saturationNeon[lane])
        });
    }
    result.maximumAbsoluteDelta = maximumDelta;
    result.passed = std::isfinite(maximumDelta) && maximumDelta <= 3.0e-6f;
    if (result.passed) {
        // One-time fixed-workload qualification. The SIMD opponent prepass is
        // selected only when it is both numerically qualified and measurably
        // faster than scalar opponent conversion on the running CPU.
        constexpr int kPixels = 4096;
        constexpr int kRepeats = 24;
        std::vector<float> benchmarkRgb(static_cast<std::size_t>(kPixels) * 3u);
        for (int i = 0; i < kPixels; ++i) {
            benchmarkRgb[static_cast<std::size_t>(i) * 3u] =
                    0.05f + 0.00017f * static_cast<float>(i % 997);
            benchmarkRgb[static_cast<std::size_t>(i) * 3u + 1u] =
                    0.04f + 0.00013f * static_cast<float>(i % 991);
            benchmarkRgb[static_cast<std::size_t>(i) * 3u + 2u] =
                    0.03f + 0.00011f * static_cast<float>(i % 983);
        }
        volatile float benchmarkSink = 0.0f;
        const auto scalarStart = std::chrono::steady_clock::now();
        for (int repeat = 0; repeat < kRepeats; ++repeat) {
            for (int i = 0; i < kPixels; ++i) {
                const float* pixel = benchmarkRgb.data() +
                        static_cast<std::size_t>(i) * 3u;
                float y = 0.0f, rg = 0.0f, bg = 0.0f, saturation = 0.0f;
                opponentScalar(pixel[0], pixel[1], pixel[2], y, rg, bg, saturation);
                benchmarkSink += y + rg + bg + saturation;
            }
        }
        result.scalarBenchmarkMs = static_cast<float>(
                std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - scalarStart
                ).count()
        );
        const auto neonStart = std::chrono::steady_clock::now();
        for (int repeat = 0; repeat < kRepeats; ++repeat) {
            for (int i = 0; i < kPixels; i += 4) {
                float y[4]{}, rg[4]{}, bg[4]{}, saturation[4]{};
                pixel_neon::opponentQuad(
                        benchmarkRgb.data() + static_cast<std::size_t>(i) * 3u,
                        y, rg, bg, saturation
                );
                for (int lane = 0; lane < 4; ++lane) {
                    benchmarkSink += y[lane] + rg[lane] + bg[lane] + saturation[lane];
                }
            }
        }
        result.neonBenchmarkMs = static_cast<float>(
                std::chrono::duration<double, std::milli>(
                        std::chrono::steady_clock::now() - neonStart
                ).count()
        );
        (void)benchmarkSink;
        result.latencyBenchmarkPerformed = true;
        result.benchmarkSpeedup = result.neonBenchmarkMs > 1.0e-9f
                ? result.scalarBenchmarkMs / result.neonBenchmarkMs
                : 0.0f;
#if defined(BNCAM_SPECTRA_PIXEL_NEON_FORCE_LATENCY_FAIL)
        // Deterministic test hook. Never defined by the production build.
        result.latencyBenchmarkPassed = false;
#elif defined(BNCAM_SPECTRA_PIXEL_NEON_EMULATION)
        result.latencyBenchmarkPassed = true;
#else
        result.latencyBenchmarkPassed = std::isfinite(result.benchmarkSpeedup) &&
                result.benchmarkSpeedup >= 1.03f;
#endif
    }
    result.status = (result.passed && result.latencyBenchmarkPassed)
            ? "PIXEL_NEON_EQUIVALENCE_AND_LATENCY_QUALIFIED"
            : (maximumDelta > 3.0e-6f
                    ? "PIXEL_NEON_OPPONENT_EQUIVALENCE_FAILED"
                    : "PIXEL_NEON_LATENCY_NOT_BETTER_SCALAR_FALLBACK");
#else
    result.passed = false;
    result.status = "PIXEL_NEON_NOT_COMPILED";
#endif
    result.elapsedMs = static_cast<float>(std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start
    ).count());
    return result;
}

inline const PixelKernelSelfTestResult& cachedPixelKernelSelfTest() {
    static const PixelKernelSelfTestResult result = runPixelKernelSelfTest();
    return result;
}

inline PixelKernelSelection selectPixelKernelBackend(
        int width,
        int height,
        bool preferNeon,
        int tileSize = 64
) {
    PixelKernelSelection selection{};
    selection.tileSize = std::clamp(tileSize, 32, 128);
#if BNCAM_SPECTRA_PIXEL_NEON_AVAILABLE && !defined(BNCAM_SPECTRA_DISABLE_PIXEL_NEON)
    selection.neonCompiled = true;
#else
    selection.neonCompiled = false;
#endif
    const std::uint64_t pixels = static_cast<std::uint64_t>(std::max(0, width)) *
            static_cast<std::uint64_t>(std::max(0, height));
    if (pixels < 256u * 256u) {
        selection.selected = PixelKernelBackendKind::ScalarReference;
        selection.selectionReason = "SMALL_FRAME_REFERENCE_PATH";
        return selection;
    }
    selection.selected = PixelKernelBackendKind::TiledCpu;
    selection.selectionReason = "PRODUCTION_FRAME_TILED_CPU";
    if (!preferNeon) {
        selection.fallbackReason = "UPSTREAM_SIMD_BACKEND_NOT_QUALIFIED";
        return selection;
    }
    const PixelKernelSelfTestResult& selfTest = cachedPixelKernelSelfTest();
    selection.selfTestPerformed = selfTest.performed;
    selection.selfTestPassed = selfTest.passed;
    selection.selfTestMaximumAbsoluteDelta = selfTest.maximumAbsoluteDelta;
    selection.selfTestElapsedMs = selfTest.elapsedMs;
    selection.latencyBenchmarkPerformed = selfTest.latencyBenchmarkPerformed;
    selection.latencyBenchmarkPassed = selfTest.latencyBenchmarkPassed;
    selection.scalarBenchmarkMs = selfTest.scalarBenchmarkMs;
    selection.neonBenchmarkMs = selfTest.neonBenchmarkMs;
    selection.benchmarkSpeedup = selfTest.benchmarkSpeedup;
    if (selection.neonCompiled && selfTest.passed && selfTest.latencyBenchmarkPassed) {
        selection.selected = PixelKernelBackendKind::TiledNeon;
        selection.selectionReason = "PRODUCTION_FRAME_PIXEL_NEON_EQUIVALENCE_AND_LATENCY_QUALIFIED";
        selection.fallbackReason = "none";
    } else {
        selection.fallbackReason = selection.neonCompiled
                ? selfTest.status
                : "PIXEL_NEON_NOT_COMPILED";
    }
    return selection;
}

inline void buildOpponentTile(
        const RgbFloatFrameView& frame,
        int outputX,
        int outputY,
        int outputWidth,
        int outputHeight,
        int halo,
        const PixelKernelSelection& selection,
        OpponentTileBuffer& tile,
        OpponentTileBuildTelemetry& telemetry
) {
    const auto start = std::chrono::steady_clock::now();
    const int boundedHalo = std::max(0, halo);
    const int tileWidth = std::max(0, outputWidth) + 2 * boundedHalo;
    const int tileHeight = std::max(0, outputHeight) + 2 * boundedHalo;
    tile.resize(tileWidth, tileHeight, boundedHalo);
    telemetry.backend = selection.selected;
    telemetry.scratchBytes = tile.scratchBytes();
    if (!frame.valid() || tileWidth <= 0 || tileHeight <= 0) {
        telemetry.elapsedMs = static_cast<float>(std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now() - start
        ).count());
        return;
    }

    for (int localY = 0; localY < tileHeight; ++localY) {
        const int globalY = std::clamp(
                outputY + localY - boundedHalo,
                0,
                frame.height - 1
        );
        const float* sourceRow = frame.row(globalY);
        int localX = 0;
#if BNCAM_SPECTRA_PIXEL_NEON_AVAILABLE && !defined(BNCAM_SPECTRA_DISABLE_PIXEL_NEON)
        if (selection.selected == PixelKernelBackendKind::TiledNeon) {
            // Border replication is scalar. The contiguous interior is processed
            // four RGB pixels at a time through vld3q_f32.
            while (localX < tileWidth) {
                const int globalX = outputX + localX - boundedHalo;
                if (globalX >= 0 && globalX + 3 < frame.width) break;
                const int clampedX = std::clamp(globalX, 0, frame.width - 1);
                const float* rgb = sourceRow + static_cast<std::size_t>(clampedX) * 3u;
                const std::size_t index = tile.index(localX, localY);
                if (!finiteRgb(rgb)) {
                    const float nan = std::numeric_limits<float>::quiet_NaN();
                    tile.luma[index] = tile.rg[index] = tile.bg[index] = nan;
                    tile.saturation[index] = 0.0f;
                    telemetry.rejectedNonFinitePixelCount++;
                } else {
                    opponentScalar(
                            rgb[0], rgb[1], rgb[2],
                            tile.luma[index], tile.rg[index], tile.bg[index], tile.saturation[index]
                    );
                }
                telemetry.scalarPixelCount++;
                ++localX;
            }
            while (localX + 3 < tileWidth) {
                const int globalX = outputX + localX - boundedHalo;
                if (globalX < 0 || globalX + 3 >= frame.width) break;
                const float* rgb = sourceRow + static_cast<std::size_t>(globalX) * 3u;
                bool finite = true;
                for (int lane = 0; lane < 4; ++lane) {
                    finite = finite && finiteRgb(rgb + static_cast<std::size_t>(lane) * 3u);
                }
                if (!finite) break;
                float y[4]{}, rg[4]{}, bg[4]{}, saturation[4]{};
                pixel_neon::opponentQuad(rgb, y, rg, bg, saturation);
                for (int lane = 0; lane < 4; ++lane) {
                    const std::size_t index = tile.index(localX + lane, localY);
                    tile.luma[index] = y[lane];
                    tile.rg[index] = rg[lane];
                    tile.bg[index] = bg[lane];
                    tile.saturation[index] = saturation[lane];
                }
                telemetry.vectorizedPixelCount += 4u;
                localX += 4;
            }
        }
#endif
        for (; localX < tileWidth; ++localX) {
            const int globalX = std::clamp(
                    outputX + localX - boundedHalo,
                    0,
                    frame.width - 1
            );
            const float* rgb = sourceRow + static_cast<std::size_t>(globalX) * 3u;
            const std::size_t index = tile.index(localX, localY);
            if (!finiteRgb(rgb)) {
                const float nan = std::numeric_limits<float>::quiet_NaN();
                tile.luma[index] = tile.rg[index] = tile.bg[index] = nan;
                tile.saturation[index] = 0.0f;
                telemetry.rejectedNonFinitePixelCount++;
            } else {
                opponentScalar(
                        rgb[0], rgb[1], rgb[2],
                        tile.luma[index], tile.rg[index], tile.bg[index], tile.saturation[index]
                );
            }
            telemetry.scalarPixelCount++;
        }
    }
    const std::uint64_t pixels = static_cast<std::uint64_t>(tileWidth) *
            static_cast<std::uint64_t>(tileHeight);
    telemetry.estimatedBytesRead += pixels * 3u * sizeof(float);
    telemetry.estimatedBytesWritten += pixels * 4u * sizeof(float);
    telemetry.elapsedMs += static_cast<float>(std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start
    ).count());
}

} // namespace bncam::spectra2
