#include "../../main/cpp/RawCameraColorCharacterizationOwnership.h"

#include <cassert>
#include <iostream>

using namespace bncam::color;

int main() {
    {
        RawCameraColorCharacterizationInput in{};
        in.calibratedProfileConfigured = true;
        in.hueSatMapResolved = true;
        in.hueSatMapProductEligible = true;
        in.domainPlanReady = true;
        in.legacyPresentationAvailable = true;
        const auto plan = resolveRawCameraColorCharacterizationOwnership(in);
        assert(plan.calibratedHueSatMapApply);
        assert(!plan.legacyPresentationApply);
        assert(!plan.failClosed);
        assert(plan.owner == RawCameraColorCharacterizationOwner::CALIBRATED_HUESATMAP);
    }
    {
        RawCameraColorCharacterizationInput in{};
        in.calibratedProfileConfigured = true;
        in.hueSatMapResolved = true;
        in.hueSatMapProductEligible = true;
        in.domainPlanReady = false;
        in.legacyPresentationAvailable = true;
        const auto plan = resolveRawCameraColorCharacterizationOwnership(in);
        assert(plan.failClosed);
        assert(!plan.calibratedHueSatMapApply);
        assert(!plan.legacyPresentationApply);
    }
    {
        RawCameraColorCharacterizationInput in{};
        in.legacyPresentationAvailable = true;
        const auto plan = resolveRawCameraColorCharacterizationOwnership(in);
        assert(plan.owner == RawCameraColorCharacterizationOwner::LEGACY_HUE_PRESERVING_PRESENTATION);
        assert(plan.legacyPresentationApply);
        assert(!plan.calibratedHueSatMapApply);
    }
    std::cout << "RawCameraColorCharacterizationOwnershipTest PASS\n";
    return 0;
}
