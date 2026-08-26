#include "../../main/cpp/YuvSignalDiagnostics.h"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <vector>

int main() {
    constexpr std::uint32_t w = 8u;
    constexpr std::uint32_t h = 8u;
    const std::size_t yBytes = static_cast<std::size_t>(w) * h;
    std::vector<std::uint8_t> nv21(yBytes + yBytes / 2u, 0u);

    // Uniform neutral frame: Y=64, V=U=128.
    std::fill(nv21.begin(), nv21.begin() + static_cast<std::ptrdiff_t>(yBytes), 64u);
    for (std::size_t i = yBytes; i + 1u < nv21.size(); i += 2u) {
        nv21[i] = 128u;     // V
        nv21[i + 1u] = 128u; // U
    }
    auto uniform = bncam::yuvdiag::summarizeNv21Signal(nv21.data(), nv21.size(), w, h, 16u);
    assert(uniform.sampleCount > 0u);
    assert(std::fabs(uniform.yMean - 64.0f / 255.0f) < 1.0e-5f);
    assert(uniform.yStdDev < 1.0e-6f);
    assert(std::fabs(uniform.uCenteredMean) < 1.0e-6f);
    assert(std::fabs(uniform.vCenteredMean) < 1.0e-6f);
    assert(uniform.chromaRmsFromNeutral < 1.0e-6f);
    assert(uniform.lumaNeighbourDeltaMean < 1.0e-6f);
    assert(uniform.chromaNeighbourDeltaMean < 1.0e-6f);
    assert(std::fabs(uniform.yP50 - 64.0f / 255.0f) < 1.0e-5f);
    assert(uniform.blackClippedFraction == 0.0f);
    assert(uniform.whiteClippedFraction == 0.0f);

    // Add real spatial and chroma variation. Diagnostics must observe it, without calling it noise.
    for (std::uint32_t y = 0u; y < h; ++y) {
        for (std::uint32_t x = 0u; x < w; ++x) {
            nv21[static_cast<std::size_t>(y) * w + x] = static_cast<std::uint8_t>(40u + 10u * x);
        }
    }
    for (std::size_t i = yBytes; i + 1u < nv21.size(); i += 4u) {
        nv21[i] = 150u;
        nv21[i + 1u] = 110u;
    }
    auto varied = bncam::yuvdiag::summarizeNv21Signal(nv21.data(), nv21.size(), w, h, 16u);
    assert(varied.yStdDev > 0.01f);
    assert(varied.lumaNeighbourDeltaMean > 0.01f);
    assert(varied.chromaRmsFromNeutral > 0.01f);

    // Invalid/truncated input must be typed as no samples, not fabricated values.
    auto invalid = bncam::yuvdiag::summarizeNv21Signal(nv21.data(), yBytes, w, h, 16u);
    assert(invalid.sampleCount == 0u);
    auto odd = bncam::yuvdiag::summarizeNv21Signal(nv21.data(), nv21.size(), 7u, h, 16u);
    assert(odd.sampleCount == 0u);
    return 0;
}
