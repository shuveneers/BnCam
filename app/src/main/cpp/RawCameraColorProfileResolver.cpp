#include "RawCameraColorProfileResolver.h"
#include "RawCameraDngIlluminantCalibration.h"

#include <algorithm>
#include <cmath>

namespace bncam::color {
namespace {

constexpr float kMaxMatrixShapeDistance = 0.30f;
constexpr float kAmbiguousMatrixDistanceMargin = 0.020f;
constexpr float kMaxDualWbFitLogRmse = 0.30f;

using Mat3 = std::array<float, 9>;
using Vec3 = std::array<float, 3>;

bool finite3(const Vec3& v) noexcept {
    return std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]);
}

Mat3 identity3() noexcept {
    return {1.0f,0.0f,0.0f, 0.0f,1.0f,0.0f, 0.0f,0.0f,1.0f};
}

Vec3 mul(const Mat3& m, const Vec3& v) noexcept {
    return {
        m[0]*v[0] + m[1]*v[1] + m[2]*v[2],
        m[3]*v[0] + m[4]*v[1] + m[5]*v[2],
        m[6]*v[0] + m[7]*v[1] + m[8]*v[2]
    };
}

Mat3 mul(const Mat3& a, const Mat3& b) noexcept {
    Mat3 out{};
    for (int r = 0; r < 3; ++r) {
        for (int c = 0; c < 3; ++c) {
            float sum = 0.0f;
            for (int k = 0; k < 3; ++k) sum += a[r*3+k] * b[k*3+c];
            out[r*3+c] = sum;
        }
    }
    return out;
}

Vec3 targetWhiteXyz(float kelvin) noexcept {
    const double t = std::clamp(static_cast<double>(kelvin), 1667.0, 25000.0);
    const double t2 = t * t;
    const double t3 = t2 * t;
    double x = 0.3127;
    double y = 0.3290;
    if (t < 4000.0) {
        x = -0.2661239 * (1.0e9 / t3) - 0.2343580 * (1.0e6 / t2) +
                0.8776956 * (1.0e3 / t) + 0.179910;
        const double x2 = x*x, x3 = x2*x;
        y = t <= 2222.0
                ? (-1.1063814*x3 - 1.34811020*x2 + 2.18555832*x - 0.20219683)
                : (-0.95494760*x3 - 1.37418593*x2 + 2.09137015*x - 0.16748867);
    } else {
        x = t <= 7000.0
                ? (-4.6070*(1.0e9/t3) + 2.9678*(1.0e6/t2) + 0.09911*(1.0e3/t) + 0.244063)
                : (-2.0064*(1.0e9/t3) + 1.9018*(1.0e6/t2) + 0.24748*(1.0e3/t) + 0.237040);
        y = -3.000*x*x + 2.870*x - 0.275;
    }
    if (!(y > 1.0e-8) || !std::isfinite(x) || !std::isfinite(y)) return {0.95047f,1.0f,1.08883f};
    return {
        static_cast<float>(x / y),
        1.0f,
        static_cast<float>((1.0 - x - y) / y)
    };
}

Mat3 interpolatedMatrix(const Mat3& a, const Mat3& b, float weightFirst) noexcept {
    Mat3 out{};
    const float w1 = std::clamp(weightFirst, 0.0f, 1.0f);
    const float w2 = 1.0f - w1;
    for (std::size_t i = 0; i < out.size(); ++i) out[i] = w1*a[i] + w2*b[i];
    return out;
}

float wbLogRmse(const Vec3& predicted, const Vec3& observed) noexcept {
    if (!finite3(predicted) || !finite3(observed) || predicted[0] <= 0.0f || predicted[1] <= 0.0f ||
        predicted[2] <= 0.0f || observed[0] <= 0.0f || observed[1] <= 0.0f || observed[2] <= 0.0f) {
        return std::numeric_limits<float>::infinity();
    }
    const float pg = predicted[1];
    const float og = observed[1];
    const float pr = predicted[0] / pg;
    const float pb = predicted[2] / pg;
    const float orr = observed[0] / og;
    const float ob = observed[2] / og;
    const float er = std::log(pr / orr);
    const float eb = std::log(pb / ob);
    return std::sqrt(0.5f * (er*er + eb*eb));
}

bool estimateSceneCct(
        const RawCameraNativeHueSatProfile& p,
        const Vec3& observedWb,
        float& sceneCct,
        float& fitError,
        float& w1,
        float& w2) noexcept {
    const auto t1 = rawCameraDngIlluminantTemperature(p.calibrationIlluminant1);
    const auto t2 = rawCameraDngIlluminantTemperature(p.calibrationIlluminant2);
    if (!t1.available || !t2.available || !p.dualIlluminant()) return false;
    const float lo = std::min(t1.kelvin, t2.kelvin);
    const float hi = std::max(t1.kelvin, t2.kelvin);
    if (!(hi > lo)) return false;

    float bestT = 0.0f;
    float best = std::numeric_limits<float>::infinity();
    // Coarse search in reciprocal-temperature space so sampling matches the interpolation model.
    constexpr int kSteps = 96;
    const double invLo = 1.0 / static_cast<double>(lo);
    const double invHi = 1.0 / static_cast<double>(hi);
    for (int i = 0; i <= kSteps; ++i) {
        const double u = static_cast<double>(i) / static_cast<double>(kSteps);
        const double invT = invLo + (invHi - invLo) * u;
        const float t = static_cast<float>(1.0 / invT);
        Vec3 predicted{};
        if (!rawCameraPredictProfileWbRgb(p, t, predicted)) continue;
        const float e = wbLogRmse(predicted, observedWb);
        if (e < best) { best = e; bestT = t; }
    }
    if (!std::isfinite(best) || bestT <= 0.0f) return false;

    // Local refinement around the best reciprocal-temperature sample.
    float refineLo = std::max(lo, bestT - (hi - lo) / static_cast<float>(kSteps));
    float refineHi = std::min(hi, bestT + (hi - lo) / static_cast<float>(kSteps));
    for (int pass = 0; pass < 3; ++pass) {
        float localBestT = bestT;
        for (int i = 0; i <= 24; ++i) {
            const float t = refineLo + (refineHi - refineLo) * (static_cast<float>(i) / 24.0f);
            Vec3 predicted{};
            if (!rawCameraPredictProfileWbRgb(p, t, predicted)) continue;
            const float e = wbLogRmse(predicted, observedWb);
            if (e < best) { best = e; localBestT = t; }
        }
        bestT = localBestT;
        const float radius = std::max(0.5f, (refineHi - refineLo) * 0.12f);
        refineLo = std::max(lo, bestT - radius);
        refineHi = std::min(hi, bestT + radius);
    }

    const auto weights = rawCameraResolveDngDualWeight(
            p.calibrationIlluminant1, p.calibrationIlluminant2, bestT);
    if (!weights.ready || best > kMaxDualWbFitLogRmse) return false;
    sceneCct = bestT;
    fitError = best;
    w1 = weights.weightFirst;
    w2 = weights.weightSecond;
    return true;
}

} // namespace

float rawCameraColorMatrixShapeDistance(const Mat3& a, const Mat3& b) noexcept {
    double na2 = 0.0, nb2 = 0.0;
    for (std::size_t i = 0; i < a.size(); ++i) {
        if (!std::isfinite(a[i]) || !std::isfinite(b[i])) return std::numeric_limits<float>::infinity();
        na2 += static_cast<double>(a[i]) * a[i];
        nb2 += static_cast<double>(b[i]) * b[i];
    }
    if (!(na2 > 1.0e-12) || !(nb2 > 1.0e-12)) return std::numeric_limits<float>::infinity();
    const double na = std::sqrt(na2), nb = std::sqrt(nb2);
    double d2 = 0.0;
    for (std::size_t i = 0; i < a.size(); ++i) {
        const double d = static_cast<double>(a[i]) / na - static_cast<double>(b[i]) / nb;
        d2 += d*d;
    }
    return static_cast<float>(std::sqrt(d2 / 9.0));
}

bool rawCameraPredictProfileWbRgb(
        const RawCameraNativeHueSatProfile& p,
        float sceneCctKelvin,
        Vec3& wbRgbOut) noexcept {
    if (!p.valid() || !std::isfinite(sceneCctKelvin) || sceneCctKelvin <= 0.0f) return false;
    Mat3 cm = p.colorMatrix1;
    Mat3 cc = p.hasCameraCalibration1 ? p.cameraCalibration1 : identity3();
    if (p.dualIlluminant()) {
        const auto weights = rawCameraResolveDngDualWeight(
                p.calibrationIlluminant1, p.calibrationIlluminant2, sceneCctKelvin);
        if (!weights.ready) return false;
        cm = interpolatedMatrix(p.colorMatrix1, p.colorMatrix2, weights.weightFirst);
        const Mat3 cc2 = p.hasCameraCalibration2 ? p.cameraCalibration2 : identity3();
        cc = interpolatedMatrix(cc, cc2, weights.weightFirst);
    }
    const Mat3 analog = {
        p.analogBalance[0], 0.0f, 0.0f,
        0.0f, p.analogBalance[1], 0.0f,
        0.0f, 0.0f, p.analogBalance[2]};
    const Vec3 neutral = mul(mul(analog, mul(cc, cm)), targetWhiteXyz(sceneCctKelvin));
    if (!finite3(neutral) || neutral[0] <= 1.0e-6f || neutral[1] <= 1.0e-6f || neutral[2] <= 1.0e-6f) return false;
    wbRgbOut = {neutral[1] / neutral[0], 1.0f, neutral[1] / neutral[2]};
    return finite3(wbRgbOut) && wbRgbOut[0] > 0.0f && wbRgbOut[2] > 0.0f;
}

RawCameraColorProfileResolution resolveRawCameraColorProfile(
        const RawCameraProfileRegistrySnapshot& registry,
        const RawCameraColorProfileResolveRequest& request) noexcept {
    RawCameraColorProfileResolution out{};
    if (registry.profiles.empty()) return out;

    std::size_t bestIndex = std::numeric_limits<std::size_t>::max();
    float bestDistance = std::numeric_limits<float>::infinity();
    float secondDistance = std::numeric_limits<float>::infinity();
    for (std::size_t i = 0; i < registry.profiles.size(); ++i) {
        const auto& p = registry.profiles[i];
        if (!p.valid()) continue;
        const float d = rawCameraColorMatrixShapeDistance(request.currentEffectiveCcm, p.discoveryEffectiveCcm);
        if (d < bestDistance) {
            secondDistance = bestDistance;
            bestDistance = d;
            bestIndex = i;
        } else if (d < secondDistance) {
            secondDistance = d;
        }
    }
    out.matrixShapeDistance = bestDistance;
    out.secondBestMatrixShapeDistance = secondDistance;
    if (bestIndex == std::numeric_limits<std::size_t>::max()) {
        out.status = "NO_VALID_REGISTERED_PROFILE";
        return out;
    }
    if (!(bestDistance <= kMaxMatrixShapeDistance)) {
        out.status = "CURRENT_CCM_DOES_NOT_MATCH_ANY_DISCOVERED_PROFILE";
        return out;
    }
    if (std::isfinite(secondDistance) && secondDistance - bestDistance < kAmbiguousMatrixDistanceMargin) {
        out.status = "AMBIGUOUS_PROFILE_IDENTITY_FROM_CURRENT_CCM";
        return out;
    }

    const auto& selected = registry.profiles[bestIndex];
    out.profileIndex = bestIndex;
    out.profileId = selected.profileId;
    out.sourcePriority = selected.sourcePriority;
    if (!selected.dualIlluminant()) {
        out.ready = true;
        out.hueSatWeightFirst = 1.0f;
        out.hueSatWeightSecond = 0.0f;
        out.status = "READY_SINGLE_ILLUMINANT_HUESATMAP";
        return out;
    }

    float cct = 0.0f, fit = std::numeric_limits<float>::infinity(), w1 = 1.0f, w2 = 0.0f;
    if (!estimateSceneCct(selected, request.currentWbRgb, cct, fit, w1, w2)) {
        out.wbFitLogRmse = fit;
        out.status = "DUAL_ILLUMINANT_WB_FIT_UNRELIABLE";
        return out;
    }
    out.sceneCctKelvin = cct;
    out.wbFitLogRmse = fit;
    out.hueSatWeightFirst = w1;
    out.hueSatWeightSecond = w2;
    out.ready = true;
    out.status = "READY_DUAL_ILLUMINANT_ADAPTIVE_HUESATMAP";
    return out;
}

} // namespace bncam::color
