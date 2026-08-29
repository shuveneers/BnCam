#include "../../main/cpp/Bgr8PublicationStats.h"

#include <array>
#include <cstdint>
#include <iostream>
#include <random>
#include <utility>
#include <vector>

namespace {

using bncam::publication::Bgr8PublicationStats;

bool equalStats(const Bgr8PublicationStats& lhs, const Bgr8PublicationStats& rhs) {
    return lhs.pixelCount == rhs.pixelCount &&
           lhs.redClipped == rhs.redClipped &&
           lhs.greenClipped == rhs.greenClipped &&
           lhs.blueClipped == rhs.blueClipped &&
           lhs.redSum == rhs.redSum &&
           lhs.greenSum == rhs.greenSum &&
           lhs.blueSum == rhs.blueSum;
}

Bgr8PublicationStats referenceScan(const std::vector<std::uint8_t>& bgr, std::size_t width,
                                   std::size_t height) {
    Bgr8PublicationStats stats{};
    for (std::size_t y = 0u; y < height; ++y) {
        const std::uint8_t* row = bgr.data() + y * width * 3u;
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
            ++stats.pixelCount;
        }
    }
    return stats;
}

} // namespace

int main() {
    using namespace bncam::publication;

    // Explicit boundary contract: clipping remains byte >= 254 exactly as in IspCore.
    const std::array<std::uint8_t, 12> boundaryBgr{
            0u, 253u, 254u,   // B,G,R
            254u, 254u, 253u,
            255u, 1u, 255u,
            253u, 255u, 0u
    };
    Bgr8PublicationStats boundary{};
    accumulateBgr8Row(boundaryBgr.data(), 4u, boundary);
    if (boundary.pixelCount != 4u ||
        boundary.redClipped != 2u ||
        boundary.greenClipped != 2u ||
        boundary.blueClipped != 2u ||
        boundary.redSum != (254u + 253u + 255u + 0u) ||
        boundary.greenSum != (253u + 254u + 1u + 255u) ||
        boundary.blueSum != (0u + 254u + 255u + 253u)) {
        std::cerr << "boundary contract failed\n";
        return 1;
    }

    constexpr std::size_t width = 257u;
    constexpr std::size_t height = 129u;
    std::vector<std::uint8_t> image(width * height * 3u);
    std::mt19937 rng(0xB8A8u);
    std::uniform_int_distribution<int> byteDistribution(0, 255);
    for (std::uint8_t& value : image) {
        value = static_cast<std::uint8_t>(byteDistribution(rng));
    }

    const Bgr8PublicationStats reference = referenceScan(image, width, height);
    Bgr8PublicationStats rowAccumulated{};
    for (std::size_t y = 0u; y < height; ++y) {
        accumulateBgr8Row(image.data() + y * width * 3u, width, rowAccumulated);
    }
    if (!equalStats(reference, rowAccumulated) ||
        !hasCompleteBgr8PublicationStats(rowAccumulated, width * height)) {
        std::cerr << "row accumulator parity failed\n";
        return 2;
    }

    // Strip merging must be exactly equivalent to one-shot full-frame accumulation.
    Bgr8PublicationStats merged{};
    constexpr std::size_t splitA = 17u;
    constexpr std::size_t splitB = 96u;
    const std::array<std::pair<std::size_t, std::size_t>, 3> strips{{
            {0u, splitA}, {splitA, splitB}, {splitB, height}
    }};
    for (const auto& strip : strips) {
        Bgr8PublicationStats local{};
        for (std::size_t y = strip.first; y < strip.second; ++y) {
            accumulateBgr8Row(image.data() + y * width * 3u, width, local);
        }
        mergeBgr8PublicationStats(merged, local);
    }
    if (!equalStats(reference, merged) ||
        hasCompleteBgr8PublicationStats(merged, width * height - 1u)) {
        std::cerr << "strip merge parity failed\n";
        return 3;
    }

    std::cout << "PASS pixels=" << merged.pixelCount
              << " clipR=" << merged.redClipped
              << " clipG=" << merged.greenClipped
              << " clipB=" << merged.blueClipped << '\n';
    return 0;
}
