#include "../../main/cpp/RawAdaptiveExposurePolicy.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::raw_exposure;

namespace {
std::array<TileEvidence, kTileCount> makeScene(float background, float normal, float highlight,
                                               bool physicalNoise) {
    std::array<TileEvidence, kTileCount> tiles{};
    for (std::uint32_t y = 0; y < kGridHeight; ++y) {
        for (std::uint32_t x = 0; x < kGridWidth; ++x) {
            auto& t = tiles[static_cast<std::size_t>(y) * kGridWidth + x];
            t.valid = true;
            if (x < 18u) t.luma = background;
            else if (x < 50u) t.luma = normal;
            else t.luma = highlight;
            t.highSignal = t.luma * 1.08f;
            t.snr = physicalNoise ? (x < 18u ? 4.0f : 10.0f) : 0.0f;
            t.clipFraction = highlight > 0.98f && x >= 50u ? 0.08f : 0.0f;
        }
    }
    return tiles;
}
}

int main() {
    {
        // Dark room + bright display: all three spatial states must coexist.
        auto tiles = makeScene(0.020f, 0.055f, 0.20f, true);
        const auto plan = resolve(tiles, true);
        assert(plan.valid);
        assert(plan.positiveFraction > 0.10f);
        assert(plan.neutralFraction > 0.20f);
        assert(plan.negativeFraction > 0.10f);
        assert(plan.minEv < -0.05f);
        assert(plan.maxEv > 0.01f);
        assert(plan.p50Ev > -0.03f && plan.p50Ev < 0.03f);
    }
    {
        // Missing physical noise truth may still protect highlights, but must not lift shadows.
        auto tiles = makeScene(0.015f, 0.050f, 0.18f, false);
        const auto plan = resolve(tiles, false);
        assert(plan.valid);
        assert(plan.positiveFraction == 0.0f);
        assert(plan.negativeFraction > 0.10f);
    }
    {
        // A nearly uniform scene must collapse toward a neutral map, not manufacture local HDR.
        std::array<TileEvidence, kTileCount> tiles{};
        for (std::size_t i = 0; i < tiles.size(); ++i) {
            tiles[i].valid = true;
            tiles[i].luma = 0.12f * (1.0f + 0.005f * static_cast<float>(i % 3u));
            tiles[i].highSignal = tiles[i].luma;
            tiles[i].snr = 12.0f;
        }
        const auto plan = resolve(tiles, true);
        assert(plan.valid);
        assert(plan.spatialAuthority < 0.05f);
        assert(plan.neutralFraction > 0.95f);
    }
    {
        // Broad high sensor occupancy must retain negative protection without globally darkening
        // already-correct tiles.
        auto tiles = makeScene(0.10f, 0.30f, 1.02f, true);
        const auto plan = resolve(tiles, true);
        assert(plan.valid);
        assert(plan.negativeFraction > 0.10f);
        assert(plan.neutralFraction > 0.20f);
        assert(plan.p50Ev > -0.05f);
    }
    std::cout << "RAW_ADAPTIVE_EXPOSURE_POLICY_TESTS_OK\n";
    return 0;
}
