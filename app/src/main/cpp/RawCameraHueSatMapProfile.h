#pragma once

#include "RawCameraHueSatMap.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <string>

namespace bncam::color {

struct RawCameraHueSatMapProfile {
    std::string profileId;
    std::string cameraId;
    std::string lensId;
    RawCameraHueSatMap mapA;
    RawCameraHueSatMap mapB;
    RawCameraHueSatMap mapC;
    float calibrationCctA = 0.0f;
    float calibrationCctB = 0.0f;
    float calibrationCctC = 0.0f;
    bool trustedCalibration = false;
};

struct RawCameraHueSatMapCalibrationWeights {
    float a = 0.0f;
    float b = 0.0f;
    float c = 0.0f;
};

struct RawCameraHueSatMapResolution {
    RawCameraHueSatMap map;
    bool resolved = false;
    bool interpolated = false;
    int calibrationCount = 0;
    float interpolationWeightA = 0.0f;
    float interpolationWeightB = 0.0f;
    float interpolationWeightC = 0.0f;
    std::string profileId;
    std::string reason = "NO_PROFILE";
};

inline bool rawCameraHueSatMapSameContract(
        const RawCameraHueSatMap& a,
        const RawCameraHueSatMap& b) noexcept {
    return a.hueDivisions == b.hueDivisions &&
           a.saturationDivisions == b.saturationDivisions &&
           a.valueDivisions == b.valueDivisions &&
           a.encoding == b.encoding &&
           a.dynamicRange == b.dynamicRange &&
           a.colorDomain == b.colorDomain &&
           a.expectedEntryCount() == b.expectedEntryCount();
}

inline float rawCameraMired(float cctKelvin) noexcept {
    if (!std::isfinite(cctKelvin) || cctKelvin <= 0.0f) return 0.0f;
    return 1.0e6f / cctKelvin;
}

inline bool rawCameraHueSatMapNormalizeWeights(
        RawCameraHueSatMapCalibrationWeights in,
        RawCameraHueSatMapCalibrationWeights* out) noexcept {
    if (out == nullptr || !std::isfinite(in.a) || !std::isfinite(in.b) || !std::isfinite(in.c) ||
        in.a < 0.0f || in.b < 0.0f || in.c < 0.0f) {
        return false;
    }
    const float sum = in.a + in.b + in.c;
    if (!(sum > 1.0e-8f) || !std::isfinite(sum)) return false;
    out->a = in.a / sum;
    out->b = in.b / sum;
    out->c = in.c / sum;
    return true;
}

inline RawCameraHueSatMapEntry rawCameraHueSatMapWeighted3(
        const RawCameraHueSatMapEntry& a,
        const RawCameraHueSatMapEntry& b,
        const RawCameraHueSatMapEntry& c,
        const RawCameraHueSatMapCalibrationWeights& w) noexcept {
    return {
        a.hueShiftDegrees * w.a + b.hueShiftDegrees * w.b + c.hueShiftDegrees * w.c,
        a.saturationScale * w.a + b.saturationScale * w.b + c.saturationScale * w.c,
        a.valueScale * w.a + b.valueScale * w.b + c.valueScale * w.c
    };
}

/**
 * Resolve a triple-illuminant profile from weights computed by a standards-correct white-point
 * calibration resolver. Adobe's DNG SDK derives these weights from the selected white xy and all
 * three calibration illuminant white points; a single CCT is not sufficient to reproduce that
 * algorithm faithfully for arbitrary/custom third illuminants.
 */
inline RawCameraHueSatMapResolution resolveRawCameraHueSatMapProfileWithWeights(
        const RawCameraHueSatMapProfile& profile,
        RawCameraHueSatMapCalibrationWeights requestedWeights) {
    RawCameraHueSatMapResolution out{};
    out.profileId = profile.profileId;

    if (!profile.trustedCalibration) {
        out.reason = "PROFILE_NOT_TRUSTED";
        return out;
    }
    if (!profile.mapA.productEligible()) {
        out.reason = "PRIMARY_MAP_NOT_PRODUCT_ELIGIBLE";
        return out;
    }
    if (!profile.mapB.productEligible() || !profile.mapC.productEligible()) {
        out.reason = "TRIPLE_PROFILE_REQUIRES_ALL_THREE_MAPS";
        return out;
    }
    if (!rawCameraHueSatMapSameContract(profile.mapA, profile.mapB) ||
        !rawCameraHueSatMapSameContract(profile.mapA, profile.mapC)) {
        out.reason = "TRIPLE_MAP_CONTRACT_MISMATCH";
        return out;
    }

    RawCameraHueSatMapCalibrationWeights w{};
    if (!rawCameraHueSatMapNormalizeWeights(requestedWeights, &w)) {
        out.reason = "TRIPLE_INTERPOLATION_WEIGHTS_INVALID";
        return out;
    }

    RawCameraHueSatMap resolved = profile.mapA;
    resolved.source = RawCameraHueSatMapSource::DNG_INTERPOLATED_PROFILE;
    resolved.sourceId = profile.profileId.empty() ? "triple_illuminant_interpolated" : profile.profileId;
    resolved.trustedCalibration = true;
    resolved.entries.resize(profile.mapA.entries.size());
    for (std::size_t i = 0; i < resolved.entries.size(); ++i) {
        resolved.entries[i] = rawCameraHueSatMapWeighted3(
                profile.mapA.entries[i], profile.mapB.entries[i], profile.mapC.entries[i], w);
    }

    if (!resolved.productEligible()) {
        out.reason = "TRIPLE_INTERPOLATED_MAP_INVALID";
        return out;
    }
    out.map = std::move(resolved);
    out.resolved = true;
    out.interpolated = w.a < 0.999999f && w.b < 0.999999f && w.c < 0.999999f;
    out.calibrationCount = 3;
    out.interpolationWeightA = w.a;
    out.interpolationWeightB = w.b;
    out.interpolationWeightC = w.c;
    out.reason = out.interpolated
            ? "TRIPLE_ILLUMINANT_STANDARDS_WEIGHT_INTERPOLATION"
            : (w.a >= 0.999999f ? "TRIPLE_ILLUMINANT_CLAMP_A" :
               (w.b >= 0.999999f ? "TRIPLE_ILLUMINANT_CLAMP_B" : "TRIPLE_ILLUMINANT_CLAMP_C"));
    return out;
}

/**
 * Single/dual resolver. DNG 1.2+ defines dual-calibration interpolation as linear interpolation
 * in inverse correlated color temperature. Triple-illuminant profiles deliberately do not use
 * this CCT-only path; they require the white-point-based weight resolver above.
 */
inline RawCameraHueSatMapResolution resolveRawCameraHueSatMapProfile(
        const RawCameraHueSatMapProfile& profile,
        float sceneCctKelvin) {
    RawCameraHueSatMapResolution out{};
    out.profileId = profile.profileId;

    if (!profile.trustedCalibration) {
        out.reason = "PROFILE_NOT_TRUSTED";
        return out;
    }
    if (!profile.mapA.productEligible()) {
        out.reason = "PRIMARY_MAP_NOT_PRODUCT_ELIGIBLE";
        return out;
    }

    const bool hasSecond = profile.mapB.productEligible();
    const bool hasThird = profile.mapC.productEligible();
    if (hasThird) {
        if (!hasSecond) {
            out.reason = "TRIPLE_PROFILE_MISSING_SECOND_MAP";
            return out;
        }
        if (!rawCameraHueSatMapSameContract(profile.mapA, profile.mapB) ||
            !rawCameraHueSatMapSameContract(profile.mapA, profile.mapC)) {
            out.reason = "TRIPLE_MAP_CONTRACT_MISMATCH";
            return out;
        }
        out.reason = "TRIPLE_ILLUMINANT_REQUIRES_WHITE_POINT_WEIGHTS";
        return out;
    }

    if (!hasSecond) {
        out.map = profile.mapA;
        out.map.sourceId = profile.profileId.empty() ? profile.mapA.sourceId : profile.profileId;
        out.resolved = true;
        out.calibrationCount = 1;
        out.interpolationWeightA = 1.0f;
        out.reason = "PRIMARY_MAP_ONLY";
        return out;
    }
    if (!rawCameraHueSatMapSameContract(profile.mapA, profile.mapB)) {
        out.reason = "DUAL_MAP_CONTRACT_MISMATCH";
        return out;
    }

    const float miredA = rawCameraMired(profile.calibrationCctA);
    const float miredB = rawCameraMired(profile.calibrationCctB);
    const float sceneMired = rawCameraMired(sceneCctKelvin);
    if (!(miredA > 0.0f) || !(miredB > 0.0f) || !(sceneMired > 0.0f) ||
        std::abs(miredB - miredA) < 1.0e-6f) {
        out.map = profile.mapA;
        out.map.sourceId = profile.profileId.empty() ? profile.mapA.sourceId : profile.profileId;
        out.resolved = true;
        out.calibrationCount = 2;
        out.interpolationWeightA = 1.0f;
        out.reason = "PRIMARY_MAP_INVALID_INTERPOLATION_CCT";
        return out;
    }

    const float weightB = std::clamp(
            (sceneMired - miredA) / (miredB - miredA), 0.0f, 1.0f);
    const float weightA = 1.0f - weightB;
    RawCameraHueSatMap resolved = profile.mapA;
    resolved.source = RawCameraHueSatMapSource::DNG_INTERPOLATED_PROFILE;
    resolved.sourceId = profile.profileId.empty() ? "dual_illuminant_interpolated" : profile.profileId;
    resolved.trustedCalibration = true;
    resolved.entries.resize(profile.mapA.entries.size());
    for (std::size_t i = 0; i < resolved.entries.size(); ++i) {
        resolved.entries[i] = rawCameraHueSatMapLerp(
                profile.mapA.entries[i], profile.mapB.entries[i], weightB);
    }

    if (!resolved.productEligible()) {
        out.reason = "INTERPOLATED_MAP_INVALID";
        return out;
    }
    out.map = std::move(resolved);
    out.resolved = true;
    out.interpolated = weightB > 0.0f && weightB < 1.0f;
    out.calibrationCount = 2;
    out.interpolationWeightA = weightA;
    out.interpolationWeightB = weightB;
    out.reason = out.interpolated ? "DUAL_ILLUMINANT_INVERSE_CCT_INTERPOLATION" :
            (weightB <= 0.0f ? "DUAL_ILLUMINANT_CLAMP_A" : "DUAL_ILLUMINANT_CLAMP_B");
    return out;
}

inline bool rawCameraHueSatMapProfileMatches(
        const RawCameraHueSatMapProfile& profile,
        const std::string& cameraId,
        const std::string& lensId) noexcept {
    if (profile.cameraId.empty() || cameraId.empty()) return false;
    if (profile.cameraId != cameraId) return false;
    if (!profile.lensId.empty() && profile.lensId != lensId) return false;
    return true;
}

} // namespace bncam::color
