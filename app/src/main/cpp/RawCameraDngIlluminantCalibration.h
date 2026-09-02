#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <string>

namespace bncam::color {

using RawCameraCalibrationMat3 = std::array<float, 9>;

struct RawCameraDngIlluminantTemperature {
    bool available = false;
    float kelvin = 0.0f;
    const char* method = "DNG_LIGHTSOURCE_UNRESOLVED";
};

/**
 * DNG SDK-compatible calibration-temperature mapping for EXIF LightSource codes.
 * These are profile calibration temperatures, not a scene-CCT estimator and not UI tuning.
 * Unknown/Other fail closed because Android Camera2 does not expose DNG IlluminantData white-xy.
 */
inline RawCameraDngIlluminantTemperature rawCameraDngIlluminantTemperature(
        int lightSourceCode) noexcept {
    RawCameraDngIlluminantTemperature out{};
    switch (lightSourceCode) {
        case 17: // Standard Light A
        case 3:  // Tungsten
            out = {true, 2850.0f, "DNG_SDK_STANDARD_A_TUNGSTEN"};
            break;
        case 24: // ISO Studio Tungsten
            out = {true, 3200.0f, "DNG_SDK_ISO_STUDIO_TUNGSTEN"};
            break;
        case 23: // D50
            out = {true, 5000.0f, "DNG_SDK_D50"};
            break;
        case 20: // D55
        case 1:  // Daylight
        case 9:  // Fine weather
        case 4:  // Flash
        case 18: // Standard Light B
            out = {true, 5500.0f, "DNG_SDK_5500_GROUP"};
            break;
        case 21: // D65
        case 19: // Standard Light C
        case 10: // Cloudy weather
            out = {true, 6500.0f, "DNG_SDK_6500_GROUP"};
            break;
        case 22: // D75
        case 11: // Shade
            out = {true, 7500.0f, "DNG_SDK_7500_GROUP"};
            break;
        case 12: // Daylight fluorescent, D 5700-7100 K
            out = {true, 6400.0f, "DNG_SDK_DAYLIGHT_FLUORESCENT_MIDPOINT"};
            break;
        case 13: // Day white fluorescent, N 4600-5500 K
            out = {true, 5050.0f, "DNG_SDK_DAY_WHITE_FLUORESCENT_MIDPOINT"};
            break;
        case 14: // Cool white fluorescent / generic fluorescent 3800-4500 K
        case 2:
            out = {true, 4150.0f, "DNG_SDK_COOL_WHITE_FLUORESCENT_MIDPOINT"};
            break;
        case 15: // White fluorescent 3250-3800 K
            out = {true, 3525.0f, "DNG_SDK_WHITE_FLUORESCENT_MIDPOINT"};
            break;
        case 16: // Warm white fluorescent 2600-3250 K
            out = {true, 2925.0f, "DNG_SDK_WARM_WHITE_FLUORESCENT_MIDPOINT"};
            break;
        default:
            // 0 Unknown and 255 Other require real illuminant data/white xy to resolve honestly.
            break;
    }
    return out;
}

struct RawCameraDngDualWeight {
    bool ready = false;
    float weightFirst = 1.0f;
    float weightSecond = 0.0f;
    float calibrationTemperatureFirst = 0.0f;
    float calibrationTemperatureSecond = 0.0f;
    float sceneTemperature = 0.0f;
    std::string status = "UNAVAILABLE";
};

/** DNG dual-calibration interpolation: linear in reciprocal temperature. */
inline RawCameraDngDualWeight rawCameraResolveDngDualWeight(
        int referenceIlluminant1,
        int referenceIlluminant2,
        float sceneCctKelvin) noexcept {
    RawCameraDngDualWeight out{};
    const auto t1 = rawCameraDngIlluminantTemperature(referenceIlluminant1);
    const auto t2 = rawCameraDngIlluminantTemperature(referenceIlluminant2);
    out.calibrationTemperatureFirst = t1.kelvin;
    out.calibrationTemperatureSecond = t2.kelvin;
    out.sceneTemperature = sceneCctKelvin;

    if (!t1.available || !t2.available) {
        out.status = "REFERENCE_ILLUMINANT_TEMPERATURE_UNRESOLVED";
        return out;
    }
    if (!std::isfinite(sceneCctKelvin) || sceneCctKelvin <= 0.0f) {
        out.status = "SCENE_CCT_UNAVAILABLE";
        return out;
    }
    const double inv1 = 1.0 / static_cast<double>(t1.kelvin);
    const double inv2 = 1.0 / static_cast<double>(t2.kelvin);
    const double invScene = 1.0 / static_cast<double>(sceneCctKelvin);
    const double denom = inv1 - inv2;
    if (!std::isfinite(denom) || std::abs(denom) < 1.0e-12) {
        out.weightFirst = 1.0f;
        out.weightSecond = 0.0f;
        out.ready = true;
        out.status = "CALIBRATION_TEMPERATURES_EQUAL_PRIMARY_ONLY";
        return out;
    }

    const float first = std::clamp(
            static_cast<float>((invScene - inv2) / denom), 0.0f, 1.0f);
    out.weightFirst = first;
    out.weightSecond = 1.0f - first;
    out.ready = true;
    if (first >= 0.999999f) {
        out.status = "DUAL_ILLUMINANT_CLAMP_FIRST";
    } else if (first <= 0.000001f) {
        out.status = "DUAL_ILLUMINANT_CLAMP_SECOND";
    } else {
        out.status = "DUAL_ILLUMINANT_INVERSE_CCT_INTERPOLATION";
    }
    return out;
}

inline bool rawCameraCalibrationMatrixFinite(const RawCameraCalibrationMat3& m) noexcept {
    float maxAbs = 0.0f;
    for (float v : m) {
        if (!std::isfinite(v)) return false;
        maxAbs = std::max(maxAbs, std::abs(v));
    }
    const float det =
            m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6]);
    return maxAbs <= 64.0f && std::isfinite(det) && std::abs(det) >= 1.0e-8f;
}

inline RawCameraCalibrationMat3 rawCameraInterpolateCalibrationMatrix(
        const RawCameraCalibrationMat3& first,
        const RawCameraCalibrationMat3& second,
        float weightFirst) noexcept {
    RawCameraCalibrationMat3 out{};
    const float a = std::clamp(weightFirst, 0.0f, 1.0f);
    const float b = 1.0f - a;
    for (std::size_t i = 0; i < out.size(); ++i) {
        out[i] = a * first[i] + b * second[i];
    }
    return out;
}

struct RawCameraDngCalibrationSet {
    int referenceIlluminant = 0;
    RawCameraCalibrationMat3 colorTransform{};
    RawCameraCalibrationMat3 calibrationTransform{};
    RawCameraCalibrationMat3 forwardMatrix{};
    bool hasColorTransform = false;
    bool hasCalibrationTransform = false;
    bool hasForwardMatrix = false;

    bool complete() const noexcept {
        return rawCameraDngIlluminantTemperature(referenceIlluminant).available &&
               hasColorTransform && hasCalibrationTransform && hasForwardMatrix &&
               rawCameraCalibrationMatrixFinite(colorTransform) &&
               rawCameraCalibrationMatrixFinite(calibrationTransform) &&
               rawCameraCalibrationMatrixFinite(forwardMatrix);
    }
};

struct RawCameraDngCalibrationProfile {
    std::string profileId;
    RawCameraDngCalibrationSet set1;
    RawCameraDngCalibrationSet set2;
};

struct RawCameraDngCalibrationResolution {
    bool ready = false;
    bool interpolated = false;
    float weightFirst = 1.0f;
    float weightSecond = 0.0f;
    RawCameraCalibrationMat3 colorTransform{};
    RawCameraCalibrationMat3 calibrationTransform{};
    RawCameraCalibrationMat3 forwardMatrix{};
    std::string profileId;
    std::string status = "NO_CALIBRATION";
};

/**
 * Resolve the complete DNG/Camera2 static calibration tuple as one paired characterization.
 * It never mixes a ForwardMatrix from one illuminant weight with Color/Calibration transforms
 * from another weight.
 */
inline RawCameraDngCalibrationResolution rawCameraResolveDngCalibration(
        const RawCameraDngCalibrationProfile& profile,
        float sceneCctKelvin) noexcept {
    RawCameraDngCalibrationResolution out{};
    out.profileId = profile.profileId;
    if (!profile.set1.complete()) {
        out.status = "PRIMARY_CALIBRATION_INCOMPLETE";
        return out;
    }
    if (!profile.set2.complete()) {
        out.ready = true;
        out.colorTransform = profile.set1.colorTransform;
        out.calibrationTransform = profile.set1.calibrationTransform;
        out.forwardMatrix = profile.set1.forwardMatrix;
        out.status = "SINGLE_ILLUMINANT_PRIMARY_CALIBRATION";
        return out;
    }

    const RawCameraDngDualWeight weights = rawCameraResolveDngDualWeight(
            profile.set1.referenceIlluminant,
            profile.set2.referenceIlluminant,
            sceneCctKelvin);
    if (!weights.ready) {
        out.status = weights.status;
        return out;
    }

    out.weightFirst = weights.weightFirst;
    out.weightSecond = weights.weightSecond;
    out.colorTransform = rawCameraInterpolateCalibrationMatrix(
            profile.set1.colorTransform, profile.set2.colorTransform, weights.weightFirst);
    out.calibrationTransform = rawCameraInterpolateCalibrationMatrix(
            profile.set1.calibrationTransform, profile.set2.calibrationTransform, weights.weightFirst);
    out.forwardMatrix = rawCameraInterpolateCalibrationMatrix(
            profile.set1.forwardMatrix, profile.set2.forwardMatrix, weights.weightFirst);
    if (!rawCameraCalibrationMatrixFinite(out.colorTransform) ||
        !rawCameraCalibrationMatrixFinite(out.calibrationTransform) ||
        !rawCameraCalibrationMatrixFinite(out.forwardMatrix)) {
        out.status = "INTERPOLATED_CALIBRATION_INVALID";
        return out;
    }
    out.ready = true;
    out.interpolated = weights.weightFirst > 0.000001f && weights.weightFirst < 0.999999f;
    out.status = weights.status;
    return out;
}

} // namespace bncam::color
