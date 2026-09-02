#include "RawCameraDngForwardTransform.h"
#include "RawCameraHueSatMapColorDomain.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace bncam::color {
namespace {
using Mat3 = std::array<float, 9>;
using Vec3 = std::array<float, 3>;

Mat3 identity3() noexcept { return {1,0,0, 0,1,0, 0,0,1}; }
Mat3 diag(const Vec3& d) noexcept { return {d[0],0,0, 0,d[1],0, 0,0,d[2]}; }
Mat3 mul(const Mat3& a, const Mat3& b) noexcept { return rawCameraMat3Mul(a, b); }
Vec3 mul(const Mat3& a, const Vec3& b) noexcept { return rawCameraMat3Mul(a, b); }

bool finiteMatrix(const Mat3& m) noexcept {
    for (float v : m) if (!std::isfinite(v) || std::abs(v) > 128.0f) return false;
    return true;
}

float det(const Mat3& m) noexcept {
    return m[0]*(m[4]*m[8]-m[5]*m[7]) - m[1]*(m[3]*m[8]-m[5]*m[6]) +
           m[2]*(m[3]*m[7]-m[4]*m[6]);
}

bool invert(const Mat3& m, Mat3& out) noexcept {
    const float d = det(m);
    if (!std::isfinite(d) || std::abs(d) < 1.0e-9f) return false;
    const float q = 1.0f / d;
    out = {
        (m[4]*m[8]-m[5]*m[7])*q, (m[2]*m[7]-m[1]*m[8])*q, (m[1]*m[5]-m[2]*m[4])*q,
        (m[5]*m[6]-m[3]*m[8])*q, (m[0]*m[8]-m[2]*m[6])*q, (m[2]*m[3]-m[0]*m[5])*q,
        (m[3]*m[7]-m[4]*m[6])*q, (m[1]*m[6]-m[0]*m[7])*q, (m[0]*m[4]-m[1]*m[3])*q
    };
    return finiteMatrix(out);
}

Mat3 lerp(const Mat3& a, const Mat3& b, float wFirst) noexcept {
    const float w1 = std::clamp(wFirst, 0.0f, 1.0f);
    const float w2 = 1.0f - w1;
    Mat3 out{};
    for (std::size_t i = 0; i < 9u; ++i) out[i] = w1*a[i] + w2*b[i];
    return out;
}

// Adobe DNG SDK NormalizeForwardMatrix: force F * [1,1,1] to PCS D50.
bool normalizeForward(Mat3& m) noexcept {
    if (!finiteMatrix(m)) return false;
    const Vec3 xyz = mul(m, Vec3{1.0f, 1.0f, 1.0f});
    const Vec3 pcs = rawCameraD50WhiteXyz();
    if (xyz[0] <= 1.0e-9f || xyz[1] <= 1.0e-9f || xyz[2] <= 1.0e-9f) return false;
    const Mat3 scale = diag({pcs[0]/xyz[0], pcs[1]/xyz[1], pcs[2]/xyz[2]});
    m = mul(scale, m);
    return finiteMatrix(m);
}

Mat3 xyzD50ToLinearSrgbMatrix() noexcept {
    const auto e0 = rawCameraXyzD50ToLinearSrgb({1.0f, 0.0f, 0.0f});
    const auto e1 = rawCameraXyzD50ToLinearSrgb({0.0f, 1.0f, 0.0f});
    const auto e2 = rawCameraXyzD50ToLinearSrgb({0.0f, 0.0f, 1.0f});
    return {e0[0], e1[0], e2[0], e0[1], e1[1], e2[1], e0[2], e1[2], e2[2]};
}

float d50NeutralError(const Vec3& xyz) noexcept {
    const Vec3 d50 = rawCameraD50WhiteXyz();
    if (!(xyz[1] > 1.0e-9f) || !std::isfinite(xyz[0]) || !std::isfinite(xyz[1]) || !std::isfinite(xyz[2])) {
        return std::numeric_limits<float>::infinity();
    }
    const Vec3 normalized{xyz[0]/xyz[1], 1.0f, xyz[2]/xyz[1]};
    const Vec3 reference{d50[0]/d50[1], 1.0f, d50[2]/d50[1]};
    const float dr = normalized[0]-reference[0];
    const float db = normalized[2]-reference[2];
    return std::sqrt(0.5f*(dr*dr+db*db));
}
} // namespace

RawCameraDngForwardTransformResult resolveRawCameraDngForwardTransform(
        const RawCameraDngForwardTransformRequest& request) noexcept {
    RawCameraDngForwardTransformResult out{};
    const auto* p = request.profile;
    if (p == nullptr || !p->valid()) {
        out.status = "PROFILE_INVALID";
        return out;
    }
    out.profileId = p->profileId;
    const float w1 = std::clamp(request.weightFirst, 0.0f, 1.0f);
    const float w2 = std::clamp(request.weightSecond, 0.0f, 1.0f);
    if (!std::isfinite(w1) || !std::isfinite(w2) || std::abs((w1+w2)-1.0f) > 1.0e-3f) {
        out.status = "ILLUMINANT_WEIGHTS_INVALID";
        return out;
    }
    const bool useSecond = p->dualIlluminant() && w2 > 1.0e-5f;
    if (!p->hasForwardMatrix1 || (useSecond && !p->hasForwardMatrix2)) {
        out.status = "PAIRED_FORWARD_MATRIX_UNAVAILABLE";
        return out;
    }
    for (float g : request.currentWbRgb) {
        if (!std::isfinite(g) || g <= 1.0e-5f || g > 64.0f) {
            out.status = "CURRENT_WB_INVALID";
            return out;
        }
    }

    Mat3 cc1 = p->hasCameraCalibration1 ? p->cameraCalibration1 : identity3();
    Mat3 cc2 = p->hasCameraCalibration2 ? p->cameraCalibration2 : identity3();
    Mat3 cc = useSecond ? lerp(cc1, cc2, w1) : cc1;
    Mat3 fm1 = p->forwardMatrix1;
    Mat3 fm2 = p->forwardMatrix2;
    if (!normalizeForward(fm1) || (useSecond && !normalizeForward(fm2))) {
        out.status = "FORWARD_MATRIX_NORMALIZATION_FAILED";
        return out;
    }
    Mat3 fm = useSecond ? lerp(fm1, fm2, w1) : fm1;
    const Mat3 analog = diag(p->analogBalance);
    Mat3 analogCc = mul(analog, cc);
    Mat3 individualToReference{};
    if (!invert(analogCc, individualToReference)) {
        out.status = "ANALOG_CALIBRATION_INVERSION_FAILED";
        return out;
    }

    Vec3 cameraWhite{1.0f/request.currentWbRgb[0], 1.0f/request.currentWbRgb[1], 1.0f/request.currentWbRgb[2]};
    const float maxNeutral = std::max(cameraWhite[0], std::max(cameraWhite[1], cameraWhite[2]));
    if (!(maxNeutral > 1.0e-8f)) {
        out.status = "CAMERA_WHITE_INVALID";
        return out;
    }
    for (float& x : cameraWhite) x /= maxNeutral;
    const Vec3 refCameraWhite = mul(individualToReference, cameraWhite);
    if (refCameraWhite[0] <= 1.0e-7f || refCameraWhite[1] <= 1.0e-7f || refCameraWhite[2] <= 1.0e-7f ||
        !std::isfinite(refCameraWhite[0]) || !std::isfinite(refCameraWhite[1]) || !std::isfinite(refCameraWhite[2])) {
        out.status = "REFERENCE_CAMERA_WHITE_INVALID";
        return out;
    }
    const Mat3 invWhite = diag({1.0f/refCameraWhite[0], 1.0f/refCameraWhite[1], 1.0f/refCameraWhite[2]});
    out.cameraToXyzD50 = mul(fm, mul(invWhite, individualToReference));
    if (!finiteMatrix(out.cameraToXyzD50)) {
        out.status = "CAMERA_TO_PCS_NONFINITE";
        return out;
    }

    // Existing BnCam AWB is a diagonal multiplication before the matrix. Divide that operation
    // out of CameraToPCS so the composite camera -> PCS remains the DNG SDK transform exactly.
    const Mat3 inverseExistingWb = diag({1.0f/request.currentWbRgb[0],
                                        1.0f/request.currentWbRgb[1],
                                        1.0f/request.currentWbRgb[2]});
    const Mat3 postWbToXyz = mul(out.cameraToXyzD50, inverseExistingWb);
    out.postWbToLinearSrgb = mul(xyzD50ToLinearSrgbMatrix(), postWbToXyz);
    if (!finiteMatrix(out.postWbToLinearSrgb)) {
        out.status = "POST_WB_LINEAR_SRGB_MATRIX_NONFINITE";
        return out;
    }

    out.neutralD50Error = d50NeutralError(mul(out.cameraToXyzD50, cameraWhite));
    if (!std::isfinite(out.neutralD50Error) || out.neutralD50Error > 0.025f) {
        out.status = "PAIRED_FORWARD_MATRIX_NEUTRAL_CHECK_FAILED";
        return out;
    }
    out.ready = true;
    out.status = "READY_DNG_SDK_FORWARD_MATRIX_TO_LINEAR_SRGB";
    return out;
}

} // namespace bncam::color
