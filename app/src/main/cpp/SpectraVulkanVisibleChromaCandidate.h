#pragma once

#include "SpectraPixelBackend.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace bncam::spectra2 {

struct VulkanVisibleCandidateFrame {
    int width = 0;
    int height = 0;
    std::vector<float> rgba; // filtered linear R,G,B,1

    bool valid() const noexcept {
        return width > 0 && height > 0 &&
                rgba.size() == static_cast<std::size_t>(width) *
                        static_cast<std::size_t>(height) * 4u;
    }

    const float* pixel(int x, int y) const noexcept {
        if (!valid() || x < 0 || y < 0 || x >= width || y >= height) return nullptr;
        return rgba.data() +
                (static_cast<std::size_t>(y) * static_cast<std::size_t>(width) +
                 static_cast<std::size_t>(x)) * 4u;
    }
};

struct VulkanVisibleCandidateCpuBenchmark {
    bool performed = false;
    float elapsedMs = 0.0f;
    std::uint64_t processedPixelCount = 0;
    std::string status = "NOT_RUN";
};

struct VulkanVisibleCandidateComparison {
    bool performed = false;
    float maximumAbsoluteDelta = std::numeric_limits<float>::infinity();
    std::uint64_t comparedValueCount = 0;
    std::uint64_t rejectedNonFiniteCount = 0;
    std::string status = "NOT_RUN";
};

inline void shaderRgbToYuv(
        float r,
        float g,
        float b,
        float& y,
        float& u,
        float& v
) noexcept {
    y = 0.299f * r + 0.587f * g + 0.114f * b;
    u = -0.14713f * r - 0.28886f * g + 0.436f * b;
    v = 0.615f * r - 0.51499f * g - 0.10001f * b;
}

inline void shaderYuvToRgb(
        float y,
        float u,
        float v,
        float& r,
        float& g,
        float& b
) noexcept {
    r = y + 1.13983f * v;
    g = y - 0.39465f * u - 0.58060f * v;
    b = y + 2.03211f * u;
}

/** Exact scalar reference for vulkan/shaders/isp_chroma_denoise.comp. */
inline bool buildVulkanVisibleCandidateCpuReference(
        const RgbFloatFrameView& rgb,
        float chromaStrength,
        VulkanVisibleCandidateFrame& output
) {
    if (!rgb.valid()) return false;
    const float strength = std::clamp(chromaStrength, 0.0f, 1.0f);
    try {
        output.width = rgb.width;
        output.height = rgb.height;
        output.rgba.assign(
                static_cast<std::size_t>(rgb.width) *
                        static_cast<std::size_t>(rgb.height) * 4u,
                0.0f
        );
    } catch (...) {
        output = {};
        return false;
    }

    constexpr int radius = 2;
    for (int y = 0; y < rgb.height; ++y) {
        for (int x = 0; x < rgb.width; ++x) {
            const float* center = rgb.data + static_cast<std::size_t>(y) * rgb.strideFloats +
                    static_cast<std::size_t>(x) * 3u;
            float centerY = 0.0f;
            float centerU = 0.0f;
            float centerV = 0.0f;
            shaderRgbToYuv(center[0], center[1], center[2], centerY, centerU, centerV);
            float sumU = 0.0f;
            float sumV = 0.0f;
            float sumWeight = 0.0f;
            for (int dy = -radius; dy <= radius; ++dy) {
                const int yy = std::clamp(y + dy, 0, rgb.height - 1);
                const float* row = rgb.data + static_cast<std::size_t>(yy) * rgb.strideFloats;
                for (int dx = -radius; dx <= radius; ++dx) {
                    const int xx = std::clamp(x + dx, 0, rgb.width - 1);
                    const float* neighbour = row + static_cast<std::size_t>(xx) * 3u;
                    float neighbourY = 0.0f;
                    float neighbourU = 0.0f;
                    float neighbourV = 0.0f;
                    shaderRgbToYuv(
                            neighbour[0], neighbour[1], neighbour[2],
                            neighbourY, neighbourU, neighbourV
                    );
                    const float weight = 1.0f /
                            (1.0f + static_cast<float>(dx * dx + dy * dy));
                    sumU += neighbourU * weight;
                    sumV += neighbourV * weight;
                    sumWeight += weight;
                }
            }
            const float averageU = sumWeight > 1.0e-6f ? sumU / sumWeight : centerU;
            const float averageV = sumWeight > 1.0e-6f ? sumV / sumWeight : centerV;
            const float filteredU = centerU + (averageU - centerU) * strength;
            const float filteredV = centerV + (averageV - centerV) * strength;
            float r = 0.0f;
            float g = 0.0f;
            float b = 0.0f;
            shaderYuvToRgb(centerY, filteredU, filteredV, r, g, b);
            float* destination = output.rgba.data() +
                    (static_cast<std::size_t>(y) * static_cast<std::size_t>(rgb.width) +
                     static_cast<std::size_t>(x)) * 4u;
            destination[0] = std::max(0.0f, r);
            destination[1] = std::max(0.0f, g);
            destination[2] = std::max(0.0f, b);
            destination[3] = 1.0f;
        }
    }
    return output.valid();
}

inline VulkanVisibleCandidateCpuBenchmark benchmarkVulkanVisibleCandidateCpuReference(
        const RgbFloatFrameView& rgb,
        float chromaStrength
) {
    VulkanVisibleCandidateCpuBenchmark result{};
    if (!rgb.valid()) {
        result.status = "INVALID_RGB_VIEW";
        return result;
    }
    const auto started = std::chrono::steady_clock::now();
    VulkanVisibleCandidateFrame scratch{};
    result.performed = buildVulkanVisibleCandidateCpuReference(
            rgb, chromaStrength, scratch
    );
    result.elapsedMs = static_cast<float>(std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - started
    ).count());
    result.processedPixelCount = result.performed
            ? static_cast<std::uint64_t>(rgb.width) * static_cast<std::uint64_t>(rgb.height)
            : 0u;
    result.status = result.performed
            ? "CPU_SHADER_REFERENCE_READY"
            : "CPU_SHADER_REFERENCE_FAILED";
    return result;
}

inline VulkanVisibleCandidateComparison compareVulkanVisibleCandidateFrames(
        const VulkanVisibleCandidateFrame& reference,
        const VulkanVisibleCandidateFrame& candidate
) {
    VulkanVisibleCandidateComparison result{};
    if (!reference.valid() || !candidate.valid() ||
        reference.width != candidate.width || reference.height != candidate.height) {
        result.status = "INVALID_OR_MISMATCHED_CANDIDATE_FRAMES";
        return result;
    }
    float maximumDelta = 0.0f;
    std::uint64_t compared = 0u;
    std::uint64_t rejected = 0u;
    const std::size_t pixels = static_cast<std::size_t>(reference.width) *
            static_cast<std::size_t>(reference.height);
    for (std::size_t pixel = 0; pixel < pixels; ++pixel) {
        const std::size_t offset = pixel * 4u;
        for (std::size_t channel = 0; channel < 3u; ++channel) {
            const float lhs = reference.rgba[offset + channel];
            const float rhs = candidate.rgba[offset + channel];
            if (!std::isfinite(lhs) || !std::isfinite(rhs)) {
                rejected++;
                continue;
            }
            maximumDelta = std::max(maximumDelta, std::abs(lhs - rhs));
            compared++;
        }
    }
    result.performed = compared > 0u && rejected == 0u;
    result.maximumAbsoluteDelta = result.performed
            ? maximumDelta
            : std::numeric_limits<float>::infinity();
    result.comparedValueCount = compared;
    result.rejectedNonFiniteCount = rejected;
    result.status = result.performed
            ? "CANDIDATE_FRAMES_COMPARED"
            : "NON_FINITE_OR_EMPTY_COMPARISON";
    return result;
}

} // namespace bncam::spectra2
