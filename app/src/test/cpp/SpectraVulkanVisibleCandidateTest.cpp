#include "SpectraVulkanVisibleChromaCandidate.h"
#include <cassert>
#include <cmath>
#include <iostream>

int main() {
    constexpr int width = 9;
    constexpr int height = 7;
    std::vector<float> rgb(static_cast<std::size_t>(width * height * 3), 0.0f);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const std::size_t i = static_cast<std::size_t>(y * width + x) * 3u;
            rgb[i] = 0.20f + 0.01f * static_cast<float>(x);
            rgb[i + 1] = 0.18f + 0.005f * static_cast<float>(y);
            rgb[i + 2] = 0.16f + ((x == 4 && y == 3) ? 0.12f : 0.0f);
        }
    }
    bncam::spectra2::RgbFloatFrameView view{rgb.data(), width, height,
                                             static_cast<std::size_t>(width * 3)};
    bncam::spectra2::VulkanVisibleCandidateFrame off{};
    assert(bncam::spectra2::buildVulkanVisibleCandidateCpuReference(view, 0.0f, off));
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const std::size_t src = static_cast<std::size_t>(y * width + x) * 3u;
            const float* dst = off.pixel(x, y);
            assert(dst != nullptr);
            // Shader YUV round-trip has small coefficient error, but should remain close.
            assert(std::abs(dst[0] - rgb[src]) < 5.0e-5f);
            assert(std::abs(dst[1] - rgb[src + 1u]) < 5.0e-5f);
            assert(std::abs(dst[2] - rgb[src + 2u]) < 5.0e-5f);
        }
    }
    bncam::spectra2::VulkanVisibleCandidateFrame filtered{};
    assert(bncam::spectra2::buildVulkanVisibleCandidateCpuReference(view, 0.35f, filtered));
    const float* centerOff = off.pixel(4, 3);
    const float* centerFiltered = filtered.pixel(4, 3);
    assert(centerOff && centerFiltered);
    const float offBg = centerOff[2] - centerOff[1];
    const float filteredBg = centerFiltered[2] - centerFiltered[1];
    assert(std::abs(filteredBg) < std::abs(offBg));
    const auto comparison = bncam::spectra2::compareVulkanVisibleCandidateFrames(filtered, filtered);
    assert(comparison.performed);
    assert(comparison.maximumAbsoluteDelta == 0.0f);
    std::cout << "SPECTRA_VULKAN_VISIBLE_CANDIDATE_TESTS_OK\n";
}
