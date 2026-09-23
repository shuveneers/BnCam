#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>

namespace bncam::color {

struct RawAdaptiveColorAuthorityInput {
    bool exactFramePairAvailable = false;
    bool calibratedProfileMatrixAvailable = false;
    bool pairedHueSatMapActive = false;
    std::array<float, 9> exactFramePostWbMatrix{};
    std::array<float, 9> calibratedProfilePostWbMatrix{};
    float profileWbFitLogRmse = std::numeric_limits<float>::infinity();
};

struct RawAdaptiveColorAuthorityPlan {
    bool ready = false;
    float matrixShapeDistance = std::numeric_limits<float>::infinity();
    float expectedShapeDistance = 0.0f;
    float profileWeight = 0.0f;
    float exactFrameWeight = 0.0f;
    float exactToProfileNeutralScale = 1.0f;
    std::array<float, 9> effectivePostWbMatrix{};
    const char* status = "NO_VALID_COLOR_OWNER";
};

inline bool adaptiveColorFiniteMatrix(const std::array<float, 9>& m) noexcept {
    for (float v : m) {
        if (!std::isfinite(v) || std::abs(v) > 128.0f) return false;
    }
    return true;
}

inline float adaptiveColorNeutralMean(const std::array<float, 9>& m) noexcept {
    const float r = m[0] + m[1] + m[2];
    const float g = m[3] + m[4] + m[5];
    const float b = m[6] + m[7] + m[8];
    const float mean = (r + g + b) / 3.0f;
    return std::isfinite(mean) ? mean : 0.0f;
}

inline float adaptiveColorShapeDistance(
        const std::array<float, 9>& a,
        const std::array<float, 9>& b) noexcept {
    if (!adaptiveColorFiniteMatrix(a) || !adaptiveColorFiniteMatrix(b)) {
        return std::numeric_limits<float>::infinity();
    }
    double na2 = 0.0;
    double nb2 = 0.0;
    for (std::size_t i = 0; i < 9u; ++i) {
        na2 += static_cast<double>(a[i]) * a[i];
        nb2 += static_cast<double>(b[i]) * b[i];
    }
    if (!(na2 > 1.0e-12) || !(nb2 > 1.0e-12)) {
        return std::numeric_limits<float>::infinity();
    }
    const double na = std::sqrt(na2);
    const double nb = std::sqrt(nb2);
    double d2 = 0.0;
    for (std::size_t i = 0; i < 9u; ++i) {
        const double d = static_cast<double>(a[i]) / na - static_cast<double>(b[i]) / nb;
        d2 += d * d;
    }
    return static_cast<float>(std::sqrt(d2 / 9.0));
}

/**
 * Arbitrates between two already-validated camera-colour owners for the exact same frame.
 *
 * The DNG ForwardMatrix route remains authoritative when its post-WB matrix shape agrees with
 * the exact-frame Camera2 pair. When the two independently-valid routes materially diverge,
 * authority moves continuously toward the exact-frame pair. The expected disagreement envelope
 * expands with the profile's own WB-fit residual, so this is not tied to a lens id, camera role,
 * device model, ISO or fixed per-sensor correction.
 *
 * Matrix shape is compared scale-independently. Before blending, the exact-frame matrix is scaled
 * to the profile neutral-axis mean so color arbitration cannot become an accidental exposure
 * change. A genuine paired HueSatMap keeps full DNG authority because its PCS coordinates must
 * stay paired with the ForwardMatrix that calibrated the table.
 */
inline RawAdaptiveColorAuthorityPlan resolveRawAdaptiveColorAuthority(
        const RawAdaptiveColorAuthorityInput& input) noexcept {
    RawAdaptiveColorAuthorityPlan out{};

    const bool exactValid = input.exactFramePairAvailable &&
            adaptiveColorFiniteMatrix(input.exactFramePostWbMatrix);
    const bool profileValid = input.calibratedProfileMatrixAvailable &&
            adaptiveColorFiniteMatrix(input.calibratedProfilePostWbMatrix);

    if (!profileValid && exactValid) {
        out.ready = true;
        out.profileWeight = 0.0f;
        out.exactFrameWeight = 1.0f;
        out.effectivePostWbMatrix = input.exactFramePostWbMatrix;
        out.status = "EXACT_FRAME_ONLY";
        return out;
    }
    if (profileValid && !exactValid) {
        out.ready = true;
        out.profileWeight = 1.0f;
        out.exactFrameWeight = 0.0f;
        out.effectivePostWbMatrix = input.calibratedProfilePostWbMatrix;
        out.status = "CALIBRATED_PROFILE_ONLY";
        return out;
    }
    if (!profileValid || !exactValid) {
        return out;
    }

    out.matrixShapeDistance = adaptiveColorShapeDistance(
            input.exactFramePostWbMatrix,
            input.calibratedProfilePostWbMatrix);

    const float fit = std::isfinite(input.profileWbFitLogRmse)
            ? std::clamp(input.profileWbFitLogRmse, 0.0f, 0.30f)
            : 0.15f;
    // A profile whose illuminant fit is intrinsically less exact gets a wider coherence envelope.
    out.expectedShapeDistance = std::clamp(0.010f + 0.065f * fit, 0.010f, 0.030f);

    if (input.pairedHueSatMapActive) {
        out.profileWeight = 1.0f;
        out.exactFrameWeight = 0.0f;
        out.effectivePostWbMatrix = input.calibratedProfilePostWbMatrix;
        out.ready = true;
        out.status = "PAIRED_HUESATMAP_REQUIRES_DNG_MATRIX";
        return out;
    }

    const float ratio = std::isfinite(out.matrixShapeDistance) &&
            out.expectedShapeDistance > 1.0e-6f
            ? out.matrixShapeDistance / out.expectedShapeDistance
            : std::numeric_limits<float>::infinity();
    const float ratio2 = ratio * ratio;
    const float ratio4 = ratio2 * ratio2;
    out.profileWeight = std::isfinite(ratio4)
            ? std::clamp(1.0f / (1.0f + ratio4), 0.0f, 1.0f)
            : 0.0f;
    out.exactFrameWeight = 1.0f - out.profileWeight;

    const float exactNeutral = adaptiveColorNeutralMean(input.exactFramePostWbMatrix);
    const float profileNeutral = adaptiveColorNeutralMean(input.calibratedProfilePostWbMatrix);
    out.exactToProfileNeutralScale =
            exactNeutral > 1.0e-6f && std::isfinite(profileNeutral) && profileNeutral > 1.0e-6f
            ? std::clamp(profileNeutral / exactNeutral, 0.50f, 2.00f)
            : 1.0f;

    for (std::size_t i = 0; i < 9u; ++i) {
        const float exactScaled = input.exactFramePostWbMatrix[i] * out.exactToProfileNeutralScale;
        out.effectivePostWbMatrix[i] =
                out.profileWeight * input.calibratedProfilePostWbMatrix[i] +
                out.exactFrameWeight * exactScaled;
    }
    if (!adaptiveColorFiniteMatrix(out.effectivePostWbMatrix)) {
        out.effectivePostWbMatrix = input.exactFramePostWbMatrix;
        out.profileWeight = 0.0f;
        out.exactFrameWeight = 1.0f;
        out.exactToProfileNeutralScale = 1.0f;
        out.status = "BLEND_NONFINITE_EXACT_FRAME_FALLBACK";
    } else if (out.exactFrameWeight < 0.01f) {
        out.status = "DNG_PROFILE_COHERENT_WITH_EXACT_FRAME";
    } else if (out.profileWeight < 0.01f) {
        out.status = "EXACT_FRAME_SHAPE_AUTHORITY_PROFILE_EXPOSURE_SCALE";
    } else {
        out.status = "ADAPTIVE_DNG_EXACT_FRAME_COHERENCE_BLEND";
    }
    out.ready = true;
    return out;
}

} // namespace bncam::color
