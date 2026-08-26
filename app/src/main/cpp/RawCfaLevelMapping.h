#pragma once

#include <array>
#include <cstddef>

// RAW calibration levels cross two representations in BnCam:
//   * Kotlin/ISP calibration: canonical plane order [R, Gr, Gb, B]
//   * RAW mosaic normalization: sensor-origin positional order [00, 10, 01, 11]
// Never pass canonical levels directly to a spatial CFA-site indexer.
namespace bncam::raw {

constexpr int kCfaRggb = 0;
constexpr int kCfaGrbg = 1;
constexpr int kCfaGbrg = 2;
constexpr int kCfaBggr = 3;

constexpr int sanitizeCfaPattern(int pattern) noexcept {
    return pattern >= kCfaRggb && pattern <= kCfaBggr ? pattern : kCfaRggb;
}

// Returns canonical [R, Gr, Gb, B] plane index for sensor-origin positional
// mosaic index [00, 10, 01, 11]. Green identity follows Camera2 CFA semantics:
// Gr is green on the red row; Gb is green on the blue row.
constexpr std::array<int, 4> mosaicToCanonicalPlaneMap(int cfaPattern) noexcept {
    switch (sanitizeCfaPattern(cfaPattern)) {
        case kCfaRggb: return {{0, 1, 2, 3}};
        case kCfaGrbg: return {{1, 0, 3, 2}};
        case kCfaGbrg: return {{2, 3, 0, 1}};
        case kCfaBggr: return {{3, 2, 1, 0}};
        default: return {{0, 1, 2, 3}};
    }
}

constexpr int canonicalPlaneAtMosaicSite(int cfaPattern, int x, int y) noexcept {
    const auto map = mosaicToCanonicalPlaneMap(cfaPattern);
    const int site = ((y & 1) << 1) | (x & 1);
    return map[static_cast<std::size_t>(site)];
}

template <typename T>
constexpr std::array<T, 4> canonicalLevelsToMosaic(
        const std::array<T, 4>& canonical,
        int cfaPattern) noexcept {
    const auto map = mosaicToCanonicalPlaneMap(cfaPattern);
    return {{
            canonical[static_cast<std::size_t>(map[0])],
            canonical[static_cast<std::size_t>(map[1])],
            canonical[static_cast<std::size_t>(map[2])],
            canonical[static_cast<std::size_t>(map[3])]
    }};
}

template <typename T>
constexpr std::array<T, 4> mosaicLevelsToCanonical(
        const std::array<T, 4>& mosaic,
        int cfaPattern) noexcept {
    std::array<T, 4> canonical{};
    const auto map = mosaicToCanonicalPlaneMap(cfaPattern);
    canonical[static_cast<std::size_t>(map[0])] = mosaic[0];
    canonical[static_cast<std::size_t>(map[1])] = mosaic[1];
    canonical[static_cast<std::size_t>(map[2])] = mosaic[2];
    canonical[static_cast<std::size_t>(map[3])] = mosaic[3];
    return canonical;
}

} // namespace bncam::raw
