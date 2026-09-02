#include "../../main/cpp/RawCameraHueSatMapProfile.h"

#include <cassert>
#include <cmath>
#include <iostream>
#include <string>

using namespace bncam::color;

namespace {

bool near(float a, float b, float eps = 1.0e-4f) {
    return std::abs(a - b) <= eps;
}

RawCameraHueSatMap mapWithScale(float scale, RawCameraHueSatMapSource source) {
    RawCameraHueSatMap map{};
    map.hueDivisions = 2;
    map.saturationDivisions = 2;
    map.valueDivisions = 1;
    map.source = source;
    map.sourceId = "test";
    map.trustedCalibration = true;
    map.entries.resize(4);
    for (int h = 0; h < 2; ++h) {
        map.entries[map.index(h, 0, 0)] = {0.0f, 1.0f, 1.0f};
        map.entries[map.index(h, 1, 0)] = {0.0f, scale, 1.0f};
    }
    return map;
}

void testExactIdentityMatching() {
    RawCameraHueSatMapProfile profile{};
    profile.cameraId = "camera_0";
    profile.lensId = "wide";
    assert(rawCameraHueSatMapProfileMatches(profile, "camera_0", "wide"));
    assert(!rawCameraHueSatMapProfileMatches(profile, "camera_1", "wide"));
    assert(!rawCameraHueSatMapProfileMatches(profile, "camera_0", "tele"));
}

void testSingleMapResolution() {
    RawCameraHueSatMapProfile profile{};
    profile.profileId = "single";
    profile.mapA = mapWithScale(1.2f, RawCameraHueSatMapSource::APP_CALIBRATED_PROFILE);
    profile.trustedCalibration = true;
    const auto resolved = resolveRawCameraHueSatMapProfile(profile, 5000.0f);
    assert(resolved.resolved);
    assert(resolved.calibrationCount == 1);
    assert(near(resolved.interpolationWeightA, 1.0f));
}

void testDualIlluminantUsesInverseCctInterpolation() {
    RawCameraHueSatMapProfile profile{};
    profile.profileId = "dual";
    profile.mapA = mapWithScale(1.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_1);
    profile.mapB = mapWithScale(2.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_2);
    profile.calibrationCctA = 3000.0f;
    profile.calibrationCctB = 6000.0f;
    profile.trustedCalibration = true;

    const auto resolved = resolveRawCameraHueSatMapProfile(profile, 4000.0f);
    assert(resolved.resolved);
    assert(resolved.interpolated);
    assert(resolved.calibrationCount == 2);
    assert(near(resolved.interpolationWeightB, 0.5f, 1.0e-3f));
    assert(near(resolved.map.entries[resolved.map.index(0, 1, 0)].saturationScale, 1.5f, 1.0e-3f));
}

void testDualContractMismatchFailsClosed() {
    RawCameraHueSatMapProfile profile{};
    profile.mapA = mapWithScale(1.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_1);
    profile.mapB = mapWithScale(2.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_2);
    profile.mapB.encoding = RawCameraHueSatMapEncoding::SRGB;
    profile.calibrationCctA = 3000.0f;
    profile.calibrationCctB = 6000.0f;
    profile.trustedCalibration = true;
    const auto resolved = resolveRawCameraHueSatMapProfile(profile, 4000.0f);
    assert(!resolved.resolved);
    assert(resolved.reason == "DUAL_MAP_CONTRACT_MISMATCH");
}

void testTripleCctOnlyResolutionFailsClosed() {
    RawCameraHueSatMapProfile profile{};
    profile.mapA = mapWithScale(1.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_1);
    profile.mapB = mapWithScale(2.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_2);
    profile.mapC = mapWithScale(3.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_3);
    profile.trustedCalibration = true;
    const auto resolved = resolveRawCameraHueSatMapProfile(profile, 4500.0f);
    assert(!resolved.resolved);
    assert(resolved.reason == "TRIPLE_ILLUMINANT_REQUIRES_WHITE_POINT_WEIGHTS");
}

void testTripleExplicitStandardsWeights() {
    RawCameraHueSatMapProfile profile{};
    profile.profileId = "triple";
    profile.mapA = mapWithScale(1.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_1);
    profile.mapB = mapWithScale(2.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_2);
    profile.mapC = mapWithScale(4.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_3);
    profile.trustedCalibration = true;

    const auto resolved = resolveRawCameraHueSatMapProfileWithWeights(profile, {0.2f, 0.3f, 0.5f});
    assert(resolved.resolved);
    assert(resolved.interpolated);
    assert(resolved.calibrationCount == 3);
    assert(near(resolved.interpolationWeightA, 0.2f));
    assert(near(resolved.interpolationWeightB, 0.3f));
    assert(near(resolved.interpolationWeightC, 0.5f));
    assert(near(resolved.map.entries[resolved.map.index(0, 1, 0)].saturationScale, 2.8f));
}

void testTripleWeightsNormalizeDeterministically() {
    RawCameraHueSatMapProfile profile{};
    profile.mapA = mapWithScale(1.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_1);
    profile.mapB = mapWithScale(2.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_2);
    profile.mapC = mapWithScale(3.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_3);
    profile.trustedCalibration = true;
    const auto resolved = resolveRawCameraHueSatMapProfileWithWeights(profile, {2.0f, 1.0f, 1.0f});
    assert(resolved.resolved);
    assert(near(resolved.interpolationWeightA, 0.5f));
    assert(near(resolved.interpolationWeightB, 0.25f));
    assert(near(resolved.interpolationWeightC, 0.25f));
}

void testPartialTripleFailsClosed() {
    RawCameraHueSatMapProfile profile{};
    profile.mapA = mapWithScale(1.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_1);
    profile.mapC = mapWithScale(3.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_3);
    profile.trustedCalibration = true;
    const auto resolved = resolveRawCameraHueSatMapProfile(profile, 4500.0f);
    assert(!resolved.resolved);
    assert(resolved.reason == "TRIPLE_PROFILE_MISSING_SECOND_MAP");
}

void testHueShiftCalibrationBlendIsScalar() {
    RawCameraHueSatMapProfile profile{};
    profile.mapA = mapWithScale(1.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_1);
    profile.mapB = mapWithScale(1.0f, RawCameraHueSatMapSource::DNG_PROFILE_DATA_2);
    profile.mapA.entries[profile.mapA.index(0, 1, 0)].hueShiftDegrees = 170.0f;
    profile.mapB.entries[profile.mapB.index(0, 1, 0)].hueShiftDegrees = -170.0f;
    profile.calibrationCctA = 3000.0f;
    profile.calibrationCctB = 6000.0f;
    profile.trustedCalibration = true;
    const auto resolved = resolveRawCameraHueSatMapProfile(profile, 4000.0f);
    assert(resolved.resolved);
    assert(near(resolved.map.entries[resolved.map.index(0, 1, 0)].hueShiftDegrees, 0.0f, 1.0e-3f));
}

} // namespace

int main() {
    testExactIdentityMatching();
    testSingleMapResolution();
    testDualIlluminantUsesInverseCctInterpolation();
    testDualContractMismatchFailsClosed();
    testTripleCctOnlyResolutionFailsClosed();
    testTripleExplicitStandardsWeights();
    testTripleWeightsNormalizeDeterministically();
    testPartialTripleFailsClosed();
    testHueShiftCalibrationBlendIsScalar();
    std::cout << "RawCameraHueSatMapProfileTest PASS\n";
    return 0;
}
