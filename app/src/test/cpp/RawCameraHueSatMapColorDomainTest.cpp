#include "../../main/cpp/RawCameraHueSatMapColorDomain.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::color;

namespace {

bool near(float a, float b, float eps = 2.0e-4f) {
    return std::abs(a - b) <= eps;
}

void assertNear3(const RawCameraVec3& a, const RawCameraVec3& b, float eps = 2.0e-4f) {
    for (int i = 0; i < 3; ++i) assert(near(a[i], b[i], eps));
}

RawCameraHueSatMap eligibleMap(RawCameraHueSatMapSource source) {
    RawCameraHueSatMap map{};
    map.hueDivisions = 4;
    map.saturationDivisions = 2;
    map.valueDivisions = 1;
    map.entries.resize(8);
    map.source = source;
    map.sourceId = "profile_A";
    map.trustedCalibration = true;
    return map;
}

void testD65WhiteAdaptsToD50AndRimmNeutral() {
    const RawCameraVec3 srgbWhite{1.0f, 1.0f, 1.0f};
    const auto xyzD50 = rawCameraLinearSrgbToXyzD50(srgbWhite);
    assertNear3(xyzD50, rawCameraD50WhiteXyz(), 4.0e-4f);
    const auto rimm = rawCameraXyzD50ToLinearRimm(xyzD50);
    assertNear3(rimm, {1.0f, 1.0f, 1.0f}, 5.0e-4f);
}

void testLinearSrgbRimmRoundTripIsStable() {
    for (const RawCameraVec3 rgb : {
            RawCameraVec3{0.18f, 0.18f, 0.18f},
            RawCameraVec3{0.90f, 0.20f, 0.05f},
            RawCameraVec3{0.02f, 0.35f, 0.80f},
            RawCameraVec3{0.70f, 0.65f, 0.10f}}) {
        const auto rimm = rawCameraLinearSrgbToLinearRimm(rgb);
        const auto roundTrip = rawCameraLinearRimmToLinearSrgb(rimm);
        assertNear3(roundTrip, rgb, 8.0e-4f);
    }
}

void testDngMapRejectsUnpairedCamera2Ccm() {
    const auto map = eligibleMap(RawCameraHueSatMapSource::DNG_PROFILE_DATA_1);
    const auto plan = resolveRawCameraHueSatMapDomainPlan(
            map,
            RawCameraHueSatMapUpstreamColorOwner::CAMERA2_EXACT_FRAME_LINEAR_SRGB,
            "profile_A",
            "camera2_frame_ccm");
    assert(!plan.ready);
    assert(plan.reason == "UNPAIRED_CAMERA2_CCM_NOT_DNG_PROFILE_PCS");
}

void testDngMapAcceptsOnlyPairedForwardMatrix() {
    const auto map = eligibleMap(RawCameraHueSatMapSource::DNG_INTERPOLATED_PROFILE);
    const auto plan = resolveRawCameraHueSatMapDomainPlan(
            map,
            RawCameraHueSatMapUpstreamColorOwner::DNG_PAIRED_FORWARD_MATRIX_XYZ_D50,
            "profile_A",
            "profile_A");
    assert(plan.ready);
    assert(plan.inputAlreadyXyzD50);
    assert(!plan.bridgeLinearSrgbToXyzD50);
    assert(plan.useLinearRimmHsv);
}

void testDngProfileMismatchFailsClosed() {
    const auto map = eligibleMap(RawCameraHueSatMapSource::DNG_PROFILE_DATA_2);
    const auto plan = resolveRawCameraHueSatMapDomainPlan(
            map,
            RawCameraHueSatMapUpstreamColorOwner::DNG_PAIRED_FORWARD_MATRIX_XYZ_D50,
            "profile_A",
            "profile_B");
    assert(!plan.ready);
    assert(plan.reason == "DNG_PROFILE_ID_MISMATCH");
}

void testAppCalibratedMapNeedsExplicitPairedLinearSrgbProfile() {
    const auto map = eligibleMap(RawCameraHueSatMapSource::APP_CALIBRATED_PROFILE);
    const auto bad = resolveRawCameraHueSatMapDomainPlan(
            map,
            RawCameraHueSatMapUpstreamColorOwner::CAMERA2_EXACT_FRAME_LINEAR_SRGB,
            "profile_A",
            "profile_A");
    assert(!bad.ready);
    assert(bad.reason == "APP_HUESATMAP_REQUIRES_EXPLICIT_PAIRED_TRANSFORM");

    const auto good = resolveRawCameraHueSatMapDomainPlan(
            map,
            RawCameraHueSatMapUpstreamColorOwner::APP_PAIRED_LINEAR_SRGB_PROFILE,
            "profile_A",
            "profile_A");
    assert(good.ready);
    assert(good.bridgeLinearSrgbToXyzD50);
    assert(good.useLinearRimmHsv);
}

} // namespace

int main() {
    testD65WhiteAdaptsToD50AndRimmNeutral();
    testLinearSrgbRimmRoundTripIsStable();
    testDngMapRejectsUnpairedCamera2Ccm();
    testDngMapAcceptsOnlyPairedForwardMatrix();
    testDngProfileMismatchFailsClosed();
    testAppCalibratedMapNeedsExplicitPairedLinearSrgbProfile();
    std::cout << "RawCameraHueSatMapColorDomainTest PASS\n";
    return 0;
}
