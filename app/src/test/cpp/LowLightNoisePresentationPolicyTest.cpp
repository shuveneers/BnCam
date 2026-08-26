#include "../../main/cpp/LowLightNoisePresentationPolicy.h"

#include <cassert>
#include <cmath>
#include <iostream>

int main() {
    using bncam::noise::LowLightPresentationInput;
    using bncam::noise::resolveLowLightPresentationPlan;

    const auto normal = resolveLowLightPresentationPlan(LowLightPresentationInput{
            true, false, false, false, 0.0f, 0.0f, 0.0f});
    assert(std::abs(normal.rawBaseVibrance - 1.16f) < 1.0e-6f);
    assert(normal.presentationNoisePressure == 0.0f);

    const auto cleanLowLight = resolveLowLightPresentationPlan(LowLightPresentationInput{
            true, true, true, true, 0.80f, 0.02f, 0.0f});
    const auto noisyLowLight = resolveLowLightPresentationPlan(LowLightPresentationInput{
            true, true, true, true, 0.80f, 0.95f, 0.0f});
    assert(cleanLowLight.rawBaseVibrance <= 1.22f + 1.0e-6f);
    assert(cleanLowLight.rawBaseVibrance >= 1.12f - 1.0e-6f);
    assert(noisyLowLight.rawBaseVibrance <= cleanLowLight.rawBaseVibrance);
    assert(noisyLowLight.rawBaseVibrance >= 1.12f - 1.0e-6f);
    assert(noisyLowLight.automaticMidtoneLiftAttenuation > cleanLowLight.automaticMidtoneLiftAttenuation);

    const auto nonRaw = resolveLowLightPresentationPlan(LowLightPresentationInput{
            false, true, true, true, 1.0f, 1.0f, 1.0f});
    assert(nonRaw.rawBaseVibrance == 1.0f);

    std::cout << "LOW_LIGHT_NOISE_PRESENTATION_POLICY_TESTS_OK\n";
    return 0;
}
