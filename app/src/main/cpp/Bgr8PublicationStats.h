#pragma once

#include <cstddef>
#include <cstdint>

namespace bncam::publication {

/**
 * Exact final-output telemetry accumulated from the already quantized BGR8 bytes.
 *
 * FASE 15: this contract lets the resident publication copy retain the existing
 * clipping/mean telemetry without scheduling a second full-frame CPU scan after
 * the JPEG-boundary BGR8 surface has already been materialized.
 */
struct Bgr8PublicationStats {
    std::uint64_t pixelCount = 0u;
    std::uint64_t redClipped = 0u;
    std::uint64_t greenClipped = 0u;
    std::uint64_t blueClipped = 0u;
    std::uint64_t redSum = 0u;
    std::uint64_t greenSum = 0u;
    std::uint64_t blueSum = 0u;
};

inline void accumulateBgr8Row(
        const std::uint8_t* row,
        std::size_t width,
        Bgr8PublicationStats& stats
) noexcept {
    if (row == nullptr || width == 0u) return;
    for (std::size_t x = 0u; x < width; ++x) {
        const std::size_t base = x * 3u;
        const std::uint8_t blue = row[base + 0u];
        const std::uint8_t green = row[base + 1u];
        const std::uint8_t red = row[base + 2u];
        if (red >= 254u) ++stats.redClipped;
        if (green >= 254u) ++stats.greenClipped;
        if (blue >= 254u) ++stats.blueClipped;
        stats.redSum += red;
        stats.greenSum += green;
        stats.blueSum += blue;
    }
    stats.pixelCount += static_cast<std::uint64_t>(width);
}

inline void mergeBgr8PublicationStats(
        Bgr8PublicationStats& destination,
        const Bgr8PublicationStats& source
) noexcept {
    destination.pixelCount += source.pixelCount;
    destination.redClipped += source.redClipped;
    destination.greenClipped += source.greenClipped;
    destination.blueClipped += source.blueClipped;
    destination.redSum += source.redSum;
    destination.greenSum += source.greenSum;
    destination.blueSum += source.blueSum;
}

inline bool hasCompleteBgr8PublicationStats(
        const Bgr8PublicationStats& stats,
        std::uint64_t expectedPixelCount
) noexcept {
    return expectedPixelCount > 0u && stats.pixelCount == expectedPixelCount;
}

} // namespace bncam::publication
