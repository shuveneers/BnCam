#include "../../main/cpp/LocalTonePolicy.h"

#include <cassert>
#include <cmath>
#include <iostream>

int main() {
    using bncam::tone::LocalToneInput;
    using bncam::tone::guardAutomaticLowerMidLiftAgainstGlobalExposure;
    using bncam::tone::guardLocalToneAgainstGlobalExposure;
    using bncam::tone::resolveLocalTonePlan;

    const auto normal = resolveLocalTonePlan(LocalToneInput{
            .sceneMidtoneTarget = 0.163f,
            .shadowPressure = 0.20f,
            .dynamicRangePressure = 0.05f,
            .recoverableHighlightPressure = 0.10f,
            .sensorClipPressure = 0.01f,
            .noisePressure = 0.10f,
            .lowLightScene = false});
    const auto backlight = resolveLocalTonePlan(LocalToneInput{
            .sceneMidtoneTarget = 0.183f,
            .shadowPressure = 0.90f,
            .dynamicRangePressure = 0.85f,
            .recoverableHighlightPressure = 0.88f,
            .sensorClipPressure = 0.08f,
            .noisePressure = 0.15f,
            .lowLightScene = false});
    const auto noisyLowLight = resolveLocalTonePlan(LocalToneInput{
            .sceneMidtoneTarget = 0.179f,
            .shadowPressure = 0.90f,
            .dynamicRangePressure = 0.55f,
            .recoverableHighlightPressure = 0.55f,
            .sensorClipPressure = 0.03f,
            .noisePressure = 0.95f,
            .lowLightScene = true});
    const auto cleanLowLight = resolveLocalTonePlan(LocalToneInput{
            .sceneMidtoneTarget = 0.179f,
            .shadowPressure = 0.90f,
            .dynamicRangePressure = 0.55f,
            .recoverableHighlightPressure = 0.55f,
            .sensorClipPressure = 0.03f,
            .noisePressure = 0.05f,
            .lowLightScene = true});

    assert(normal.enabled);
    assert(normal.strength >= 0.05f && normal.strength < 0.20f);
    assert(backlight.strength > normal.strength + 0.25f);
    assert(backlight.maxLiftEv > normal.maxLiftEv);
    assert(backlight.maxCompressEv > normal.maxCompressEv);
    assert(std::abs(normal.sceneKey - 0.163f) < 1.0e-6f);
    assert(std::abs(backlight.sceneKey - 0.170f) < 1.0e-6f);
    assert(std::abs(noisyLowLight.sceneKey - 0.170f) < 1.0e-6f);
    assert(noisyLowLight.strength < cleanLowLight.strength);
    assert(backlight.maxLiftEv <= 0.62f + 1.0e-6f);
    assert(backlight.maxCompressEv <= 0.36f + 1.0e-6f);

    const auto neutralGainGuard = guardLocalToneAgainstGlobalExposure(backlight, 1.20f);
    assert(!neutralGainGuard.active);
    assert(std::abs(neutralGainGuard.plan.strength - backlight.strength) < 1.0e-6f);
    assert(std::abs(neutralGainGuard.plan.maxLiftEv - backlight.maxLiftEv) < 1.0e-6f);

    const auto highGainGuard = guardLocalToneAgainstGlobalExposure(backlight, 4.30f);
    assert(highGainGuard.active);
    assert(highGainGuard.globalLiftEv > 2.0f);
    assert(highGainGuard.attenuation > 0.99f);
    assert(highGainGuard.plan.strength < backlight.strength * 0.60f);
    assert(highGainGuard.plan.maxLiftEv < backlight.maxLiftEv * 0.35f);
    // Highlight compression is intentionally not weakened by the anti-fill-light guard.
    assert(std::abs(highGainGuard.plan.maxCompressEv - backlight.maxCompressEv) < 1.0e-6f);

    assert(std::abs(guardAutomaticLowerMidLiftAgainstGlobalExposure(0.055f, 0.0f) - 0.055f) < 1.0e-6f);
    const float guardedMainLikeLift = guardAutomaticLowerMidLiftAgainstGlobalExposure(0.055f, 1.0f);
    assert(guardedMainLikeLift > 0.010f && guardedMainLikeLift < 0.012f);
    assert(guardAutomaticLowerMidLiftAgainstGlobalExposure(-0.5f, 1.0f) == 0.0f);

    std::cout << "LOCAL_TONE_POLICY_TESTS_OK\n";
    return 0;
}
