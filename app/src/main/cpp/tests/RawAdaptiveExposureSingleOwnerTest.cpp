#include "../RawAdaptiveExposurePolicy.h"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstddef>
#include <iostream>

namespace {
using bncam::raw_exposure::Plan;
using bncam::raw_exposure::TileEvidence;
using bncam::raw_exposure::kGridHeight;
using bncam::raw_exposure::kGridWidth;
using bncam::raw_exposure::kTileCount;

std::array<TileEvidence, kTileCount> makeScene(float baseLuma, float snr) {
    std::array<TileEvidence, kTileCount> tiles{};
    for (std::uint32_t y = 0; y < kGridHeight; ++y) {
        for (std::uint32_t x = 0; x < kGridWidth; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * kGridWidth + x;
            const float ramp = 0.65f + 0.70f * static_cast<float>(x) /
                    static_cast<float>(kGridWidth - 1u);
            tiles[i] = TileEvidence{
                baseLuma * ramp,
                baseLuma * ramp * 1.05f,
                snr,
                0.0f,
                true
            };
        }
    }
    return tiles;
}

void assertNoNegativeAuthority(const Plan& plan) {
    assert(plan.valid);
    for (float ev : plan.ev) {
        assert(std::isfinite(ev));
        assert(ev >= -1.0e-7f);
    }
    assert(plan.minEv >= -1.0e-7f);
    assert(plan.negativeFraction == 0.0f);
    assert(plan.meanNegativeEv == 0.0f);
}

void ordinaryBrightObjectIsNotPreDemosaicDarkened() {
    auto tiles = makeScene(0.055f, 8.0f);
    // Contiguous bright subject: clearly above the scene median but still far from sensor white.
    // The previous signed policy assigned this region negative EV merely because it was relative
    // scene brightness. Tone mapping owns that relationship now.
    for (std::uint32_t y = 14u; y < 34u; ++y) {
        for (std::uint32_t x = 36u; x < 58u; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * kGridWidth + x;
            tiles[i].luma = 0.42f;
            tiles[i].highSignal = 0.48f;
            tiles[i].snr = 12.0f;
        }
    }
    const Plan plan = bncam::raw_exposure::resolve(tiles, true);
    assertNoNegativeAuthority(plan);
}

void clippedHighlightStillDoesNotGetNegativeRawExposure() {
    auto tiles = makeScene(0.045f, 7.0f);
    for (std::uint32_t y = 18u; y < 30u; ++y) {
        for (std::uint32_t x = 42u; x < 54u; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * kGridWidth + x;
            tiles[i].luma = 0.88f;
            tiles[i].highSignal = 1.0f;
            tiles[i].clipFraction = 0.35f;
            tiles[i].snr = 18.0f;
        }
    }
    const Plan plan = bncam::raw_exposure::resolve(tiles, true);
    assertNoNegativeAuthority(plan);
}

void highSnrSupportedShadowCanStillReceivePositiveRecovery() {
    auto tiles = makeScene(0.18f, 10.0f);
    // A broad supported shadow region with physical S/O evidence should retain the original
    // positive recovery capability. This proves the fix did not simply disable Phase-5 exposure.
    for (std::uint32_t y = 10u; y < 38u; ++y) {
        for (std::uint32_t x = 2u; x < 20u; ++x) {
            const std::size_t i = static_cast<std::size_t>(y) * kGridWidth + x;
            tiles[i].luma = 0.025f;
            tiles[i].highSignal = 0.030f;
            tiles[i].snr = 9.0f;
        }
    }
    const Plan plan = bncam::raw_exposure::resolve(tiles, true);
    assertNoNegativeAuthority(plan);
    const bool anyPositive = std::any_of(plan.ev.begin(), plan.ev.end(),
            [](float ev) { return ev > 0.015f; });
    assert(anyPositive);
    assert(plan.positiveFraction > 0.0f);
}

} // namespace

int main() {
    ordinaryBrightObjectIsNotPreDemosaicDarkened();
    clippedHighlightStillDoesNotGetNegativeRawExposure();
    highSnrSupportedShadowCanStillReceivePositiveRecovery();
    std::cout << "RawAdaptiveExposureSingleOwnerTest PASS\n";
    return 0;
}
