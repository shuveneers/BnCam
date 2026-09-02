#include "RawCameraCalibratedHueSatRuntime.h"
#include "RawCameraColorCharacterizationOwnership.h"
#include "RawCameraColorProfileResolver.h"
#include "RawCameraDngForwardTransform.h"
#include "RawCameraHueSatMapColorDomain.h"

#include <array>
#include <cassert>
#include <cmath>

using namespace bncam::color;

namespace {

RawCameraNativeHueSatProfile makeDualProfile() {
    RawCameraNativeHueSatProfile p{};
    p.profileId = "synthetic/oem/physical-lens";
    p.sourcePriority = 300;
    p.calibrationIlluminant1 = 17; // Standard A
    p.calibrationIlluminant2 = 21; // D65
    p.colorMatrix1 = {1,0,0, 0,1,0, 0,0,1};
    p.colorMatrix2 = p.colorMatrix1;
    p.hasColorMatrix2 = true;
    p.forwardMatrix1 = {1,0,0, 0,1,0, 0,0,1};
    p.forwardMatrix2 = p.forwardMatrix1;
    p.hasForwardMatrix1 = true;
    p.hasForwardMatrix2 = true;
    p.discoveryEffectiveCcm = {1,0,0, 0,1,0, 0,0,1};
    p.hueDivisions = 2;
    p.saturationDivisions = 2;
    p.valueDivisions = 1;
    p.encoding = 0;
    // Saturation-zero rows must stay valueScale==1. The saturated rows differ between
    // illuminants so the test can prove that reciprocal-temperature interpolation is used.
    p.hueSatData1 = {
            0,1,1,  8,1.12f,1,
            0,1,1, -6,0.88f,1
    };
    p.hueSatData2 = {
            0,1,1,  2,1.04f,1,
            0,1,1, -2,0.96f,1
    };
    return p;
}

RawCameraHueSatMap makeMap(
        const RawCameraNativeHueSatProfile& p,
        const std::vector<float>& data,
        RawCameraHueSatMapSource source) {
    RawCameraHueSatMap map{};
    map.hueDivisions = p.hueDivisions;
    map.saturationDivisions = p.saturationDivisions;
    map.valueDivisions = p.valueDivisions;
    map.source = source;
    map.sourceId = p.profileId;
    map.trustedCalibration = true;
    map.encoding = p.encoding == 1
            ? RawCameraHueSatMapEncoding::SRGB
            : RawCameraHueSatMapEncoding::LINEAR;
    for (std::size_t i = 0; i + 2 < data.size(); i += 3) {
        map.entries.push_back({data[i], data[i + 1], data[i + 2]});
    }
    return map;
}

bool finiteVec(const RawCameraVec3& v) {
    return std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]);
}

} // namespace

int main() {
    auto profile = makeDualProfile();
    assert(profile.valid());

    std::array<float, 3> currentWb{};
    assert(rawCameraPredictProfileWbRgb(profile, 5000.0f, currentWb));

    const RawCameraProfileRegistrySnapshot registry{{profile}, 7};
    const auto resolution = resolveRawCameraColorProfile(
            registry, {profile.discoveryEffectiveCcm, currentWb});
    assert(resolution.ready);
    assert(resolution.status == "READY_DUAL_ILLUMINANT_ADAPTIVE_HUESATMAP");
    assert(std::abs(resolution.sceneCctKelvin - 5000.0f) < 80.0f);
    assert(resolution.hueSatWeightFirst > 0.0f && resolution.hueSatWeightFirst < 1.0f);
    assert(resolution.hueSatWeightSecond > 0.0f && resolution.hueSatWeightSecond < 1.0f);

    const auto forward = resolveRawCameraDngForwardTransform({
            &profile,
            resolution.hueSatWeightFirst,
            resolution.hueSatWeightSecond,
            currentWb
    });
    assert(forward.ready);
    assert(forward.status == "READY_DNG_SDK_FORWARD_MATRIX_TO_LINEAR_SRGB");
    assert(forward.neutralD50Error < 0.025f);

    const auto first = makeMap(profile, profile.hueSatData1, RawCameraHueSatMapSource::DNG_PROFILE_DATA_1);
    const auto second = makeMap(profile, profile.hueSatData2, RawCameraHueSatMapSource::DNG_PROFILE_DATA_2);
    assert(first.productEligible());
    assert(second.productEligible());
    auto blended = rawCameraBlendHueSatMaps(first, &second, resolution.hueSatWeightFirst);
    blended.sourceId = profile.profileId;
    blended.trustedCalibration = true;
    assert(blended.productEligible());
    assert(blended.source == RawCameraHueSatMapSource::DNG_INTERPOLATED_PROFILE);

    const auto domain = resolveRawCameraHueSatMapDomainPlan(
            blended,
            RawCameraHueSatMapUpstreamColorOwner::DNG_PAIRED_FORWARD_MATRIX_XYZ_D50,
            profile.profileId,
            forward.profileId);
    assert(domain.ready);

    const auto ownership = resolveRawCameraColorCharacterizationOwnership({
            true,
            resolution.ready,
            blended.productEligible(),
            forward.ready && domain.ready,
            true
    });
    assert(ownership.owner == RawCameraColorCharacterizationOwner::CALIBRATED_HUESATMAP);
    assert(ownership.calibratedHueSatMapApply);
    assert(!ownership.legacyPresentationApply);
    assert(!ownership.failClosed);

    // Recreate the production ordering: diagonal AWB -> paired DNG forward transform -> HSM.
    const RawCameraVec3 syntheticCameraRgb{0.30f, 0.20f, 0.10f};
    const RawCameraVec3 wbRgb{
            syntheticCameraRgb[0] * currentWb[0],
            syntheticCameraRgb[1] * currentWb[1],
            syntheticCameraRgb[2] * currentWb[2]
    };
    const RawCameraVec3 pairedLinearSrgb = rawCameraMat3Mul(forward.postWbToLinearSrgb, wbRgb);
    assert(finiteVec(pairedLinearSrgb));
    bool applied = false;
    const RawCameraVec3 characterized = rawCameraApplyCalibratedHueSatMapLinearSrgb(
            blended, pairedLinearSrgb, &applied);
    assert(applied);
    assert(finiteVec(characterized));
    const float delta = std::abs(characterized[0] - pairedLinearSrgb[0]) +
            std::abs(characterized[1] - pairedLinearSrgb[1]) +
            std::abs(characterized[2] - pairedLinearSrgb[2]);
    assert(delta > 1.0e-5f);

    // Wider-gamut DNG PCS may be represented by a negative component in linear sRGB.
    // The runtime must bridge that signed transport into RIMM before HSM-domain clipping.
    const RawCameraVec3 signedTransport{0.82f, 0.23f, -0.01f};
    const RawCameraVec3 rimmBefore = rawCameraLinearSrgbToLinearRimm(signedTransport);
    assert(finiteVec(rimmBefore));
    bool signedApplied = false;
    const RawCameraVec3 signedResult = rawCameraApplyCalibratedHueSatMapLinearSrgb(
            blended, signedTransport, &signedApplied);
    assert(signedApplied);
    assert(finiteVec(signedResult));

    // A configured/matched profile with a broken PCS pairing must fail closed and must not
    // silently re-enable the legacy hue-preserving presentation renderer.
    const auto failClosed = resolveRawCameraColorCharacterizationOwnership({
            true, true, true, false, true
    });
    assert(failClosed.failClosed);
    assert(!failClosed.calibratedHueSatMapApply);
    assert(!failClosed.legacyPresentationApply);

    return 0;
}
