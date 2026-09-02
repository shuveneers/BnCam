#include "../RawGtmScenePolicy.h"

#include <cassert>
#include <cmath>
#include <cstring>
#include <iostream>
#include <limits>

namespace {
using bncam::tone::RawGtmSceneInput;
using bncam::tone::RawGtmScenePlan;

void assertNeutral(const RawGtmScenePlan& plan) {
    assert(!plan.active);
    assert(std::abs(plan.scenePlacementEv) < 1.0e-7f);
    assert(std::abs(plan.scenePlacementGain - 1.0f) < 1.0e-7f);
    assert(std::abs(plan.lowLightMoodPressure) < 1.0e-7f);
    assert(plan.highlightIsolatedLowEnd);
}

void darkLowLightFrameIsNotDarkenedAgain() {
    RawGtmSceneInput input{};
    input.p50 = 0.010f;
    input.p75 = 0.030f;
    input.indoorLowLightConfidence = 0.90f;
    input.dynamicRangePressure = 0.0f;
    input.lowLightScene = true;

    const auto plan = bncam::tone::resolveRawGtmScenePlan(input);
    assertNeutral(plan);
    assert(std::strcmp(plan.authority, "LOW_LIGHT_NEUTRAL") == 0);
}

void darkFrameWithHighlightPressureStillHasNoHiddenExposureOwner() {
    RawGtmSceneInput input{};
    input.p50 = 0.008f;
    input.p75 = 0.025f;
    input.indoorLowLightConfidence = 0.80f;
    input.dynamicRangePressure = 1.0f;
    input.lowLightScene = true;

    const auto plan = bncam::tone::resolveRawGtmScenePlan(input);
    assertNeutral(plan);
}

void outdoorAndOrdinaryScenesRemainNeutral() {
    RawGtmSceneInput outdoor{};
    outdoor.p50 = 0.20f;
    outdoor.p75 = 0.45f;
    outdoor.outdoorSkyScene = true;
    auto plan = bncam::tone::resolveRawGtmScenePlan(outdoor);
    assertNeutral(plan);
    assert(std::strcmp(plan.authority, "OUTDOOR_SKY_NEUTRAL") == 0);

    RawGtmSceneInput normal{};
    normal.p50 = 0.18f;
    normal.p75 = 0.38f;
    plan = bncam::tone::resolveRawGtmScenePlan(normal);
    assertNeutral(plan);
    assert(std::strcmp(plan.authority, "NON_LOW_LIGHT_NEUTRAL") == 0);
}

void invalidStatisticsCannotCreateGain() {
    RawGtmSceneInput input{};
    input.p50 = std::numeric_limits<float>::quiet_NaN();
    input.p75 = std::numeric_limits<float>::infinity();
    input.indoorLowLightConfidence = std::numeric_limits<float>::quiet_NaN();
    input.dynamicRangePressure = std::numeric_limits<float>::infinity();
    input.lowLightScene = true;

    const auto plan = bncam::tone::resolveRawGtmScenePlan(input);
    assertNeutral(plan);
}

} // namespace

int main() {
    darkLowLightFrameIsNotDarkenedAgain();
    darkFrameWithHighlightPressureStillHasNoHiddenExposureOwner();
    outdoorAndOrdinaryScenesRemainNeutral();
    invalidStatisticsCannotCreateGain();
    std::cout << "RawGtmScenePlacementSingleOwnerTest PASS\n";
    return 0;
}
