#include "../../main/cpp/DynamicRangeTonePolicy.h"

#include <cassert>
#include <iostream>

using bncam::tone::allowLowLightSubUnityExposureGain;

int main() {
    // 2026-08-15 device regression: low-light RAW, no sensor clipping, one isolated
    // recoverable highlight and only ~0.008% near-white occupancy. This must never
    // authorize whole-frame darkening.
    assert(!allowLowLightSubUnityExposureGain(
            true, true, true, true,
            false, false,
            0.0f, 0.0f,
            0.00798f,
            0.38f, 0.45f));

    // A broad, coherent highlight tail may still request a sub-unity scene gain.
    assert(allowLowLightSubUnityExposureGain(
            true, true, true, true,
            true, false,
            0.55f, 0.70f,
            2.5f,
            0.86f, 0.98f));

    // Occupancy alone is insufficient without a high highlight tail/confidence.
    assert(!allowLowLightSubUnityExposureGain(
            true, true, true, true,
            false, false,
            0.10f, 0.10f,
            3.0f,
            0.45f, 0.60f));

    // Normal/non-low-light or non-RAW paths do not use this special allowance.
    assert(!allowLowLightSubUnityExposureGain(
            true, false, true, true,
            true, true, 1.0f, 1.0f, 10.0f, 1.0f, 1.0f));
    assert(!allowLowLightSubUnityExposureGain(
            false, true, true, true,
            true, true, 1.0f, 1.0f, 10.0f, 1.0f, 1.0f));


    // Global tone mapping keeps midtones usable instead of protecting highlights by globally
    // darkening the frame. The policy must preserve an explicit display-mid target.
    const auto normalPlan = bncam::tone::resolveDynamicRangeTonePlan({
            0.12f, 0.28f, 0.65f, 0.78f, 0.90f,
            0.02f, 0.002f, 0.0f, 0.0f,
            false, false, false
    });
    assert(normalPlan.sceneMidtoneTarget >= 0.149f);
    assert(normalPlan.sceneMidtoneTarget <= 0.162f);
    assert(normalPlan.automaticBlackAnchor <= 0.0080f);
    assert(normalPlan.shoulderStart >= 0.62f);

    const auto backlightPlan = bncam::tone::resolveDynamicRangeTonePlan({
            0.045f, 0.11f, 0.86f, 0.96f, 1.02f,
            0.10f, 0.030f, 0.80f, 0.15f,
            false, false, true
    });
    assert(backlightPlan.dynamicRangePressure > 0.35f);
    assert(backlightPlan.sceneMidtoneTarget >= normalPlan.sceneMidtoneTarget);
    assert(backlightPlan.requestedLowerMidLift > 0.010f);
    assert(backlightPlan.shoulderStart < normalPlan.shoulderStart);
    assert(backlightPlan.shoulderStrength > normalPlan.shoulderStrength);

    const auto darkNoHighlightPlan = bncam::tone::resolveDynamicRangeTonePlan({
            0.035f, 0.09f, 0.24f, 0.30f, 0.34f,
            0.01f, 0.0001f, 0.0f, 0.85f,
            true, false, false
    });
    assert(darkNoHighlightPlan.sceneMidtoneTarget < normalPlan.sceneMidtoneTarget);
    assert(darkNoHighlightPlan.sceneMidtoneTarget >= 0.130f);
    assert(darkNoHighlightPlan.shoulderStart >= 0.73f);

    // Sky/highlight classification must not globally dim the scene. The shoulder owns highlight
    // compression; global midtone placement remains at least the ordinary baseline.
    const auto outdoorSkyPlan = bncam::tone::resolveDynamicRangeTonePlan({
            0.10f, 0.24f, 0.74f, 0.91f, 1.01f,
            0.08f, 0.020f, 0.70f, 0.10f,
            false, true, true
    });
    assert(outdoorSkyPlan.sceneMidtoneTarget >= 0.149f);
    assert(outdoorSkyPlan.shoulderStart < normalPlan.shoulderStart);

    // Post-AgX white placement is not another exposure adjustment. It must leave the
    // lower/middle display range untouched, remain monotonic, and preserve both endpoints.
    using bncam::tone::applyDisplayWhiteExpansion;
    const float whiteStart = normalPlan.displayWhiteExpansionStart;
    const float whiteGamma = normalPlan.displayWhiteExpansionGamma;
    assert(std::abs(applyDisplayWhiteExpansion(0.0f, whiteStart, whiteGamma)) < 1.0e-7f);
    assert(std::abs(applyDisplayWhiteExpansion(1.0f, whiteStart, whiteGamma) - 1.0f) < 1.0e-7f);
    assert(std::abs(applyDisplayWhiteExpansion(0.34f, whiteStart, whiteGamma) - 0.34f) < 1.0e-7f);
    float previous = 0.0f;
    for (int i = 0; i <= 1000; ++i) {
        const float x = static_cast<float>(i) / 1000.0f;
        const float y = applyDisplayWhiteExpansion(x, whiteStart, whiteGamma);
        assert(y + 1.0e-7f >= previous);
        previous = y;
    }
    // Current device capture shape: p99 around 0.74 should gain upper-range separation
    // without moving the p50 around 0.34.
    assert(applyDisplayWhiteExpansion(0.74f, whiteStart, whiteGamma) > 0.82f);
    assert(backlightPlan.displayWhiteExpansionGamma <= normalPlan.displayWhiteExpansionGamma + 0.25f);

    std::cout << "RAW_LOW_LIGHT_EXPOSURE_POLICY_TESTS_OK\n";
    return 0;
}
