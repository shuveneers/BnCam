#include "../../main/cpp/FastLocalLaplacianPolicy.h"

#include <cassert>
#include <cmath>
#include <iostream>

using bncam::tone::FastLocalLaplacianInput;
using bncam::tone::resolveFastLocalLaplacianPlan;

int main() {
    FastLocalLaplacianInput ordinary{};
    ordinary.sceneMidtoneTarget = 0.15f;
    const auto quiet = resolveFastLocalLaplacianPlan(ordinary);
    assert(!quiet.enabled);
    assert(quiet.strength >= 0.28f && quiet.strength <= 0.82f);
    assert(quiet.pyramidLevels == 6u);
    assert(quiet.baseDownsample == 2u);

    FastLocalLaplacianInput hdr{};
    hdr.sceneMidtoneTarget = 0.15f;
    hdr.shadowPressure = 0.65f;
    hdr.dynamicRangePressure = 0.85f;
    hdr.recoverableHighlightPressure = 0.80f;
    hdr.sensorClipPressure = 0.15f;
    hdr.noisePressure = 0.20f;
    const auto strong = resolveFastLocalLaplacianPlan(hdr);
    assert(strong.enabled);
    assert(strong.strength > quiet.strength);
    assert(strong.maxLiftEv <= 1.20f + 1.0e-6f);
    assert(strong.maxCompressEv <= 0.85f + 1.0e-6f);
    assert(strong.maxLiftEv > 0.70f);
    assert(strong.maxCompressEv > 0.70f);
    assert(strong.edgeStopEv >= 0.54f && strong.edgeStopEv <= 0.78f);

    // Noise may restrict positive shadow lift, but must not disable the complete field: negative
    // local highlight compression remains safe and necessary at high ISO.
    hdr.lowLightScene = true;
    hdr.noisePressure = 1.0f;
    const auto noisy = resolveFastLocalLaplacianPlan(hdr);
    assert(noisy.strength >= strong.strength - 1.0e-6f);
    assert(noisy.maxLiftEv < strong.maxLiftEv);
    assert(noisy.maxCompressEv <= 0.72f + 1.0e-6f);
    assert(noisy.maxCompressEv > 0.60f);
    assert(noisy.maxLiftEv >= 0.18f);

    // 2026-08-30 device capture shapes. These are the measured tone pressures from the three
    // standard RAW_SENSOR frames and prove that local exposure authority is materially non-zero.
    FastLocalLaplacianInput flower{};
    flower.sceneMidtoneTarget = 0.147974f;
    flower.shadowPressure = 0.881389f;
    flower.dynamicRangePressure = 0.570487f;
    flower.recoverableHighlightPressure = 0.179794f;
    flower.noisePressure = 0.265516f;
    flower.lowLightScene = true;
    const auto flowerPlan = resolveFastLocalLaplacianPlan(flower);
    assert(flowerPlan.enabled);
    assert(flowerPlan.strength > 0.50f);
    assert(flowerPlan.maxLiftEv > 0.85f);
    assert(flowerPlan.maxCompressEv > 0.50f && flowerPlan.maxCompressEv < 0.65f);

    FastLocalLaplacianInput desk{};
    desk.sceneMidtoneTarget = 0.152160f;
    desk.shadowPressure = 1.0f;
    desk.dynamicRangePressure = 0.720000f;
    desk.recoverableHighlightPressure = 0.165830f;
    desk.noisePressure = 0.042820f;
    desk.lowLightScene = true;
    const auto deskPlan = resolveFastLocalLaplacianPlan(desk);
    assert(deskPlan.enabled);
    assert(deskPlan.strength > 0.58f);
    assert(deskPlan.maxLiftEv > 1.10f);
    assert(deskPlan.maxCompressEv > 0.50f && deskPlan.maxCompressEv < 0.60f);

    FastLocalLaplacianInput kitchen{};
    kitchen.sceneMidtoneTarget = 0.142818f;
    kitchen.shadowPressure = 0.707366f;
    kitchen.dynamicRangePressure = 0.386365f;
    kitchen.recoverableHighlightPressure = 0.151723f;
    kitchen.noisePressure = 0.255639f;
    kitchen.lowLightScene = true;
    const auto kitchenPlan = resolveFastLocalLaplacianPlan(kitchen);
    assert(kitchenPlan.enabled);
    assert(kitchenPlan.strength > 0.45f);
    assert(kitchenPlan.maxLiftEv > 0.70f);
    assert(kitchenPlan.maxCompressEv > 0.45f && kitchenPlan.maxCompressEv < 0.60f);

    // Post-full-fix device regressions (2026-08-30). Local-display scenes had high generic
    // dynamic-range pressure but weak physical highlight pressure. They must no longer receive
    // ~0.9 EV negative authority merely because the surrounding room is dark.
    FastLocalLaplacianInput displayA{};
    displayA.sceneMidtoneTarget = 0.152160f;
    displayA.shadowPressure = 1.0f;
    displayA.dynamicRangePressure = 0.720000f;
    displayA.recoverableHighlightPressure = 0.008182f;
    displayA.noisePressure = 0.204989f;
    displayA.lowLightScene = true;
    const auto displayAPlan = resolveFastLocalLaplacianPlan(displayA);
    assert(displayAPlan.enabled);
    assert(displayAPlan.maxCompressEv > 0.45f && displayAPlan.maxCompressEv < 0.50f);

    FastLocalLaplacianInput displayB{};
    displayB.sceneMidtoneTarget = 0.138925f;
    displayB.shadowPressure = 0.934235f;
    displayB.dynamicRangePressure = 0.247338f;
    displayB.recoverableHighlightPressure = 0.117352f;
    displayB.sensorClipPressure = 0.041065f;
    displayB.noisePressure = 0.170429f;
    displayB.lowLightScene = true;
    const auto displayBPlan = resolveFastLocalLaplacianPlan(displayB);
    assert(displayBPlan.enabled);
    assert(displayBPlan.maxCompressEv > 0.44f && displayBPlan.maxCompressEv < 0.48f);

    std::cout << "FAST_LOCAL_LAPLACIAN_POLICY_TESTS_OK\n";
    return 0;
}
