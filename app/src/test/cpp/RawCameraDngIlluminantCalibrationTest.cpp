#include "../../main/cpp/RawCameraDngIlluminantCalibration.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::color;

static RawCameraCalibrationMat3 identity(float scale = 1.0f) {
    return {scale, 0.0f, 0.0f,
            0.0f, scale, 0.0f,
            0.0f, 0.0f, scale};
}

int main() {
    assert(rawCameraDngIlluminantTemperature(17).kelvin == 2850.0f);
    assert(rawCameraDngIlluminantTemperature(21).kelvin == 6500.0f);
    assert(rawCameraDngIlluminantTemperature(12).kelvin == 6400.0f);
    assert(!rawCameraDngIlluminantTemperature(0).available);
    assert(!rawCameraDngIlluminantTemperature(255).available);

    const auto atFirst = rawCameraResolveDngDualWeight(17, 21, 2850.0f);
    assert(atFirst.ready);
    assert(atFirst.weightFirst > 0.9999f);

    const auto atSecond = rawCameraResolveDngDualWeight(17, 21, 6500.0f);
    assert(atSecond.ready);
    assert(atSecond.weightSecond > 0.9999f);

    const float reciprocalMid = 2.0f / (1.0f / 2850.0f + 1.0f / 6500.0f);
    const auto middle = rawCameraResolveDngDualWeight(17, 21, reciprocalMid);
    assert(middle.ready);
    assert(std::abs(middle.weightFirst - 0.5f) < 1.0e-4f);

    const auto unresolved = rawCameraResolveDngDualWeight(255, 21, 5000.0f);
    assert(!unresolved.ready);

    RawCameraDngCalibrationProfile profile{};
    profile.profileId = "camera/lens";
    profile.set1.referenceIlluminant = 17;
    profile.set1.colorTransform = identity(1.0f);
    profile.set1.calibrationTransform = identity(1.0f);
    profile.set1.forwardMatrix = identity(1.0f);
    profile.set1.hasColorTransform = profile.set1.hasCalibrationTransform = profile.set1.hasForwardMatrix = true;
    profile.set2.referenceIlluminant = 21;
    profile.set2.colorTransform = identity(2.0f);
    profile.set2.calibrationTransform = identity(2.0f);
    profile.set2.forwardMatrix = identity(2.0f);
    profile.set2.hasColorTransform = profile.set2.hasCalibrationTransform = profile.set2.hasForwardMatrix = true;

    const auto resolved = rawCameraResolveDngCalibration(profile, reciprocalMid);
    assert(resolved.ready && resolved.interpolated);
    assert(std::abs(resolved.forwardMatrix[0] - 1.5f) < 1.0e-4f);
    assert(std::abs(resolved.colorTransform[4] - 1.5f) < 1.0e-4f);
    assert(std::abs(resolved.calibrationTransform[8] - 1.5f) < 1.0e-4f);

    RawCameraDngCalibrationProfile single = profile;
    single.set2 = {};
    const auto singleResolved = rawCameraResolveDngCalibration(single, 4200.0f);
    assert(singleResolved.ready && !singleResolved.interpolated);
    assert(singleResolved.status == "SINGLE_ILLUMINANT_PRIMARY_CALIBRATION");

    std::cout << "RawCameraDngIlluminantCalibrationTest PASS\n";
    return 0;
}
