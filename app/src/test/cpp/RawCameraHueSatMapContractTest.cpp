#include "../../main/cpp/RawCameraHueSatMap.h"

#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>

using namespace bncam::color;

namespace {

bool near(float a, float b, float eps = 1.0e-4f) {
    return std::abs(a - b) <= eps;
}

RawCameraHueSatMap identityMap(int h = 4, int s = 2, int v = 2) {
    RawCameraHueSatMap map{};
    map.hueDivisions = h;
    map.saturationDivisions = s;
    map.valueDivisions = v;
    map.source = RawCameraHueSatMapSource::APP_CALIBRATED_PROFILE;
    map.sourceId = "test_identity";
    map.trustedCalibration = true;
    map.entries.resize(static_cast<std::size_t>(h * s * v));
    return map;
}

void testDngDimensionRules() {
    auto invalid = identityMap(4, 1, 1);
    assert(!invalid.dimensionsValid());
    auto valid = identityMap(1, 2, 1);
    assert(valid.dimensionsValid());
}

void testIdentityIsExact() {
    const auto map = identityMap();
    RawCameraHueSatMapSample sample{};
    const auto out = applyRawCameraHueSatMap(map, {359.5f, 0.73f, 0.41f}, &sample);
    assert(sample.applied);
    assert(near(out.hueDegrees, 359.5f));
    assert(near(out.saturation, 0.73f));
    assert(near(out.value, 0.41f));
}

void testStorageOrderValueHueSaturation() {
    auto map = identityMap(2, 2, 2);
    map.entries[map.index(1, 0, 1)].saturationScale = 1.75f;
    const std::size_t expected = (1u * 2u + 1u) * 2u + 0u;
    assert(map.index(1, 0, 1) == expected);
    assert(near(map.entries[expected].saturationScale, 1.75f));
}

void testTrilinearInterpolation() {
    auto map = identityMap(1, 2, 2);
    map.entries[map.index(0, 0, 0)] = {0.0f, 1.0f, 1.0f};
    map.entries[map.index(0, 1, 0)] = {10.0f, 2.0f, 1.0f};
    map.entries[map.index(0, 0, 1)] = {20.0f, 1.0f, 1.0f}; // sat0 V scale must remain 1
    map.entries[map.index(0, 1, 1)] = {30.0f, 2.0f, 2.0f};
    const auto sample = sampleRawCameraHueSatMap(map, 42.0f, 0.5f, 0.5f);
    assert(sample.applied);
    assert(near(sample.hueShiftDegrees, 15.0f));
    assert(near(sample.saturationScale, 1.5f));
    assert(near(sample.valueScale, 1.25f));
}

void testHueInputAxisWraps() {
    auto map = identityMap(4, 2, 1);
    map.entries[map.index(3, 1, 0)].saturationScale = 2.0f;
    map.entries[map.index(0, 1, 0)].saturationScale = 1.0f;
    const auto sample = sampleRawCameraHueSatMap(map, 315.0f, 1.0f, 0.5f);
    assert(sample.applied);
    assert(near(sample.saturationScale, 1.5f));
}

void testHueShiftIsScalarInterpolatedNotCircular() {
    auto map = identityMap(2, 2, 1);
    map.entries[map.index(0, 1, 0)].hueShiftDegrees = 170.0f;
    map.entries[map.index(1, 1, 0)].hueShiftDegrees = -170.0f;
    const auto sample = sampleRawCameraHueSatMap(map, 90.0f, 1.0f, 0.5f);
    assert(sample.applied);
    assert(near(sample.hueShiftDegrees, 0.0f, 1.0e-3f));
}

void testZeroSaturationValueScaleInvariant() {
    auto map = identityMap();
    map.entries[map.index(2, 0, 1)].valueScale = 0.99f;
    assert(!map.validData());
    assert(!map.productEligible());
}

void testSdrOutputBounds() {
    auto map = identityMap(1, 2, 1);
    map.entries[map.index(0, 1, 0)].saturationScale = 3.0f;
    map.entries[map.index(0, 1, 0)].valueScale = 3.0f;
    const auto out = applyRawCameraHueSatMap(map, {0.0f, 0.8f, 0.8f});
    assert(near(out.saturation, 1.0f));
    assert(near(out.value, 1.0f));
}

void testSrgbEncodedValueIdentityRoundTrip() {
    auto map = identityMap(1, 2, 2);
    map.encoding = RawCameraHueSatMapEncoding::SRGB;
    const float inputValue = 0.18f;
    const auto out = applyRawCameraHueSatMap(map, {10.0f, 0.4f, inputValue});
    assert(near(out.value, inputValue, 2.0e-4f));
}

void testHdr3dReferenceFailsClosedWithoutProfileDynamicRangeFunction() {
    auto map = identityMap(1, 2, 2);
    map.dynamicRange = RawCameraHueSatMapDynamicRange::HDR;
    const auto sample = sampleRawCameraHueSatMap(map, 20.0f, 0.4f, 0.5f);
    assert(!sample.applied);
    assert(std::string(sample.reason) == "IDENTITY_HDR_PROFILE_DYNAMIC_RANGE_ENCODING_REQUIRED");
}

void testHdr2dReferenceIsAllowedBecauseValueIsNotIndexed() {
    auto map = identityMap(1, 2, 1);
    map.dynamicRange = RawCameraHueSatMapDynamicRange::HDR;
    const auto sample = sampleRawCameraHueSatMap(map, 20.0f, 0.4f, 2.0f);
    assert(sample.applied);
}

void testMalformedMapFailsClosedToIdentity() {
    auto map = identityMap();
    map.entries.pop_back();
    RawCameraHueSatMapSample sample{};
    const RawCameraHueSatValue input{33.0f, 0.4f, 0.7f};
    const auto out = applyRawCameraHueSatMap(map, input, &sample);
    assert(!sample.applied);
    assert(std::string(sample.reason) == "IDENTITY_INVALID_HUESATMAP_DATA");
    assert(near(out.hueDegrees, input.hueDegrees));
    assert(near(out.saturation, input.saturation));
    assert(near(out.value, input.value));
}

void testUntrustedMapFailsClosedToIdentity() {
    auto map = identityMap();
    map.trustedCalibration = false;
    map.entries[map.index(0, 1, 0)].saturationScale = 4.0f;
    const auto sample = sampleRawCameraHueSatMap(map, 0.0f, 0.5f, 0.5f);
    assert(!sample.applied);
    assert(std::string(sample.reason) == "IDENTITY_NO_TRUSTED_CALIBRATION");
}

void testNonFiniteEntryRejected() {
    auto map = identityMap();
    map.entries[0].hueShiftDegrees = std::numeric_limits<float>::quiet_NaN();
    assert(!map.validData());
    assert(!map.productEligible());
}

void testNoPseudoProfileWithoutSource() {
    auto map = identityMap();
    map.source = RawCameraHueSatMapSource::NONE;
    assert(!map.productEligible());
}

} // namespace

int main() {
    testDngDimensionRules();
    testIdentityIsExact();
    testStorageOrderValueHueSaturation();
    testTrilinearInterpolation();
    testHueInputAxisWraps();
    testHueShiftIsScalarInterpolatedNotCircular();
    testZeroSaturationValueScaleInvariant();
    testSdrOutputBounds();
    testSrgbEncodedValueIdentityRoundTrip();
    testHdr3dReferenceFailsClosedWithoutProfileDynamicRangeFunction();
    testHdr2dReferenceIsAllowedBecauseValueIsNotIndexed();
    testMalformedMapFailsClosedToIdentity();
    testUntrustedMapFailsClosedToIdentity();
    testNonFiniteEntryRejected();
    testNoPseudoProfileWithoutSource();
    std::cout << "RawCameraHueSatMapContractTest PASS\n";
    return 0;
}
