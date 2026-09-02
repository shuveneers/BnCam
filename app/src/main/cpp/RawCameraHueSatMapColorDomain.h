#pragma once

#include "RawCameraHueSatMap.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <string>

namespace bncam::color {

using RawCameraVec3 = std::array<float, 3>;
using RawCameraMat3 = std::array<float, 9>;

inline RawCameraVec3 rawCameraMat3Mul(
        const RawCameraMat3& m,
        const RawCameraVec3& v) noexcept {
    return {
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
    };
}

inline RawCameraMat3 rawCameraMat3Mul(
        const RawCameraMat3& a,
        const RawCameraMat3& b) noexcept {
    RawCameraMat3 out{};
    for (int row = 0; row < 3; ++row) {
        for (int col = 0; col < 3; ++col) {
            float value = 0.0f;
            for (int k = 0; k < 3; ++k) {
                value += a[row * 3 + k] * b[k * 3 + col];
            }
            out[row * 3 + col] = value;
        }
    }
    return out;
}

inline RawCameraMat3 rawCameraDiagonalMat3(const RawCameraVec3& d) noexcept {
    return {d[0], 0.0f, 0.0f,
            0.0f, d[1], 0.0f,
            0.0f, 0.0f, d[2]};
}

inline RawCameraVec3 rawCameraXyToXyz(float x, float y) noexcept {
    if (!std::isfinite(x) || !std::isfinite(y) || y <= 0.0f || x < 0.0f || x + y > 1.0f) {
        return {0.0f, 0.0f, 0.0f};
    }
    return {x / y, 1.0f, (1.0f - x - y) / y};
}

// IEC sRGB/BT.709 primaries with D65 white, linear-light matrix.
inline constexpr RawCameraMat3 kRawCameraLinearSrgbToXyzD65{
        0.4124564f, 0.3575761f, 0.1804375f,
        0.2126729f, 0.7151522f, 0.0721750f,
        0.0193339f, 0.1191920f, 0.9503041f
};
inline constexpr RawCameraMat3 kRawCameraXyzD65ToLinearSrgb{
         3.2404542f, -1.5371385f, -0.4985314f,
        -0.9692660f,  1.8760108f,  0.0415560f,
         0.0556434f, -0.2040259f,  1.0572252f
};

// Bradford cone-response transform. This is a standards colour-adaptation matrix, not tuning.
inline constexpr RawCameraMat3 kRawCameraBradford{
         0.8951f,  0.2664f, -0.1614f,
        -0.7502f,  1.7135f,  0.0367f,
         0.0389f, -0.0685f,  1.0296f
};
inline constexpr RawCameraMat3 kRawCameraBradfordInverse{
         0.9869929f, -0.1470543f,  0.1599627f,
         0.4323053f,  0.5183603f,  0.0492912f,
        -0.0085287f,  0.0400428f,  0.9684867f
};

// DNG's HueSatMap PCS uses linear ProPhoto/ROMM-family primaries with D50 white.
// Values are derived from the published ROMM RGB primaries and D50 white, with no transfer curve.
inline constexpr RawCameraMat3 kRawCameraLinearRimmToXyzD50{
        0.79776049f, 0.13518584f, 0.03134935f,
        0.28807113f, 0.71184322f, 0.00008565f,
        0.00000000f, 0.00000000f, 0.82510460f
};
inline constexpr RawCameraMat3 kRawCameraXyzD50ToLinearRimm{
         1.34579897f, -0.25558010f, -0.05110629f,
        -0.54462249f,  1.50823274f,  0.02053603f,
         0.00000000f,  0.00000000f,  1.21196755f
};

inline RawCameraMat3 rawCameraBradfordAdaptation(
        const RawCameraVec3& sourceWhiteXyz,
        const RawCameraVec3& destinationWhiteXyz) noexcept {
    const RawCameraVec3 sourceLms = rawCameraMat3Mul(kRawCameraBradford, sourceWhiteXyz);
    const RawCameraVec3 destinationLms = rawCameraMat3Mul(kRawCameraBradford, destinationWhiteXyz);
    RawCameraVec3 scale{1.0f, 1.0f, 1.0f};
    for (int i = 0; i < 3; ++i) {
        if (!std::isfinite(sourceLms[i]) || !std::isfinite(destinationLms[i]) ||
            std::abs(sourceLms[i]) <= 1.0e-8f) {
            return {1.0f, 0.0f, 0.0f,
                    0.0f, 1.0f, 0.0f,
                    0.0f, 0.0f, 1.0f};
        }
        scale[i] = destinationLms[i] / sourceLms[i];
    }
    return rawCameraMat3Mul(
            kRawCameraBradfordInverse,
            rawCameraMat3Mul(rawCameraDiagonalMat3(scale), kRawCameraBradford));
}

inline RawCameraVec3 rawCameraD65WhiteXyz() noexcept {
    return rawCameraXyToXyz(0.3127f, 0.3290f);
}

inline RawCameraVec3 rawCameraD50WhiteXyz() noexcept {
    return rawCameraXyToXyz(0.3457f, 0.3585f);
}

inline RawCameraVec3 rawCameraLinearSrgbToXyzD50(const RawCameraVec3& rgb) noexcept {
    const RawCameraVec3 xyzD65 = rawCameraMat3Mul(kRawCameraLinearSrgbToXyzD65, rgb);
    return rawCameraMat3Mul(
            rawCameraBradfordAdaptation(rawCameraD65WhiteXyz(), rawCameraD50WhiteXyz()),
            xyzD65);
}

inline RawCameraVec3 rawCameraXyzD50ToLinearSrgb(const RawCameraVec3& xyzD50) noexcept {
    const RawCameraVec3 xyzD65 = rawCameraMat3Mul(
            rawCameraBradfordAdaptation(rawCameraD50WhiteXyz(), rawCameraD65WhiteXyz()),
            xyzD50);
    return rawCameraMat3Mul(kRawCameraXyzD65ToLinearSrgb, xyzD65);
}

inline RawCameraVec3 rawCameraXyzD50ToLinearRimm(const RawCameraVec3& xyzD50) noexcept {
    return rawCameraMat3Mul(kRawCameraXyzD50ToLinearRimm, xyzD50);
}

inline RawCameraVec3 rawCameraLinearRimmToXyzD50(const RawCameraVec3& rimm) noexcept {
    return rawCameraMat3Mul(kRawCameraLinearRimmToXyzD50, rimm);
}

inline RawCameraVec3 rawCameraLinearSrgbToLinearRimm(const RawCameraVec3& rgb) noexcept {
    return rawCameraXyzD50ToLinearRimm(rawCameraLinearSrgbToXyzD50(rgb));
}

inline RawCameraVec3 rawCameraLinearRimmToLinearSrgb(const RawCameraVec3& rimm) noexcept {
    return rawCameraXyzD50ToLinearSrgb(rawCameraLinearRimmToXyzD50(rimm));
}

enum class RawCameraHueSatMapUpstreamColorOwner : std::uint8_t {
    NONE = 0,
    CAMERA2_EXACT_FRAME_LINEAR_SRGB = 1,
    DNG_PAIRED_FORWARD_MATRIX_XYZ_D50 = 2,
    APP_PAIRED_LINEAR_SRGB_PROFILE = 3,
    DNG_PAIRED_FORWARD_MATRIX_LINEAR_SRGB = 4
};

struct RawCameraHueSatMapDomainPlan {
    bool ready = false;
    bool inputAlreadyXyzD50 = false;
    bool bridgeLinearSrgbToXyzD50 = false;
    bool useLinearRimmHsv = false;
    std::string reason = "NO_COLOR_OWNER";
};

inline bool rawCameraHueSatMapIsDngProfileSource(RawCameraHueSatMapSource source) noexcept {
    return source == RawCameraHueSatMapSource::DNG_PROFILE_DATA_1 ||
           source == RawCameraHueSatMapSource::DNG_PROFILE_DATA_2 ||
           source == RawCameraHueSatMapSource::DNG_PROFILE_DATA_3 ||
           source == RawCameraHueSatMapSource::DNG_INTERPOLATED_PROFILE;
}

/**
 * HueSatMap and the upstream camera->PCS transform are a paired camera characterization.
 * A DNG map must never be silently stacked onto an unrelated Camera2 exact-frame CCM merely
 * because both eventually describe RGB. Doing so changes the coordinates the LUT was calibrated in.
 */
inline RawCameraHueSatMapDomainPlan resolveRawCameraHueSatMapDomainPlan(
        const RawCameraHueSatMap& map,
        RawCameraHueSatMapUpstreamColorOwner upstreamOwner,
        const std::string& hueSatProfileId,
        const std::string& upstreamTransformProfileId) {
    RawCameraHueSatMapDomainPlan out{};
    if (!map.productEligible()) {
        out.reason = "HUESATMAP_NOT_PRODUCT_ELIGIBLE";
        return out;
    }
    if (map.colorDomain != RawCameraHueSatMapColorDomain::DNG_RIMM_LINEAR_FROM_XYZ_D50) {
        out.reason = "UNSUPPORTED_HUESATMAP_COLOR_DOMAIN";
        return out;
    }

    const bool profileIdsMatch = !hueSatProfileId.empty() &&
            hueSatProfileId == upstreamTransformProfileId;
    if (rawCameraHueSatMapIsDngProfileSource(map.source)) {
        const bool pairedXyz = upstreamOwner ==
                RawCameraHueSatMapUpstreamColorOwner::DNG_PAIRED_FORWARD_MATRIX_XYZ_D50;
        const bool pairedLinearSrgb = upstreamOwner ==
                RawCameraHueSatMapUpstreamColorOwner::DNG_PAIRED_FORWARD_MATRIX_LINEAR_SRGB;
        if (!pairedXyz && !pairedLinearSrgb) {
            out.reason = upstreamOwner == RawCameraHueSatMapUpstreamColorOwner::CAMERA2_EXACT_FRAME_LINEAR_SRGB
                    ? "UNPAIRED_CAMERA2_CCM_NOT_DNG_PROFILE_PCS"
                    : "DNG_HUESATMAP_REQUIRES_PAIRED_FORWARD_MATRIX";
            return out;
        }
        if (!profileIdsMatch) {
            out.reason = "DNG_PROFILE_ID_MISMATCH";
            return out;
        }
        out.ready = true;
        out.inputAlreadyXyzD50 = pairedXyz;
        out.bridgeLinearSrgbToXyzD50 = pairedLinearSrgb;
        out.useLinearRimmHsv = true;
        out.reason = pairedXyz
                ? "DNG_PAIRED_XYZ_D50_TO_RIMM_READY"
                : "DNG_PAIRED_LINEAR_SRGB_TO_RIMM_READY";
        return out;
    }

    if (map.source == RawCameraHueSatMapSource::APP_CALIBRATED_PROFILE) {
        if (upstreamOwner != RawCameraHueSatMapUpstreamColorOwner::APP_PAIRED_LINEAR_SRGB_PROFILE) {
            out.reason = "APP_HUESATMAP_REQUIRES_EXPLICIT_PAIRED_TRANSFORM";
            return out;
        }
        if (!profileIdsMatch) {
            out.reason = "APP_PROFILE_ID_MISMATCH";
            return out;
        }
        out.ready = true;
        out.bridgeLinearSrgbToXyzD50 = true;
        out.useLinearRimmHsv = true;
        out.reason = "APP_PAIRED_LINEAR_SRGB_TO_DNG_PCS_READY";
        return out;
    }

    out.reason = "UNKNOWN_PROFILE_SOURCE";
    return out;
}

} // namespace bncam::color
