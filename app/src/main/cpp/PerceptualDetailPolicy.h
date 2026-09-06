#pragma once

#include <algorithm>
#include <cmath>
#include "ProfileMicroDetailTransport.h"
#include "ProfileEdgeZipperTransport.h"

namespace bncam::perceptual_detail {

struct Controls {
    float amount = 0.0f;
    float radius = 0.0f;
    float detail = 0.0f;
    float masking = 0.0f;
};

struct NoiseEvidence {
    bool physicalModelAvailable = false;
    float displayLumaSigma = 0.0f;
    float modelConfidence = 0.0f;
};

struct Plan {
    bool enabled = false;
    float authority = 0.0f;
    float radius = 0.0f;
    float detail = 0.0f;
    float masking = 0.0f;
    float displayLumaSigma = 0.0f;
    float minimumResidualSnr = 1.5f;
    float minimumGradientSnr = 1.0f;
    float hardHaloLimit = 0.0f;
    float modelConfidence = 0.0f;
    float predictedLumaVarianceGain = 1.0f;
    const char* authoritySource = "DISABLED";
};

inline float finiteOr(float value, float fallback) noexcept {
    return std::isfinite(value) ? value : fallback;
}

/**
 * Phase 12 profile-owned perceptual detail/output sharpening.
 *
 * This is the standalone signed Global Sharpness stage. It does not build or remap a local-tone
 * base, so FLLF remains the sole local exposure/contrast owner. Amount alone controls the effect:
 * negative softens, zero is exact identity, positive sharpens. Phase 4 retires the old Radius
 * pixel meaning. Phase 3 Detail and Phase 4 Legibility are multiplexed into the old Detail ABI
 * slot using a legacy-safe negative-only packed transport; old unsigned consumers therefore stay
 * neutral. Phase 2 uses the masking transport field for signed standalone Edge authority; the
 * same legacy-safe payload now also carries unsigned Anti-zipper. Anti-zipper is a final repair
 * guard after the creative sharpness owners and never scales Global Sharpness.
 * Physical noise is only an internal safety hint.
 */
inline Plan resolve(Controls controls, NoiseEvidence noise) noexcept {
    controls.amount = std::clamp(finiteOr(controls.amount, 0.0f), -1.0f, 1.0f);
    const float packedDetailLegibility = controls.detail;
    controls.detail = bncam::profile_microdetail_transport::decodeDetail(packedDetailLegibility);
    controls.radius = bncam::profile_microdetail_transport::decodeLegibility(packedDetailLegibility);
    const float packedEdgeZipper = finiteOr(controls.masking, 0.0f);
    const float edgeAuthority = bncam::profile_edge_zipper_transport::decodeEdge(packedEdgeZipper);
    const float antiZipperAuthority = bncam::profile_edge_zipper_transport::decodeAntiZipper(packedEdgeZipper);
    noise.displayLumaSigma = std::max(0.0f, finiteOr(noise.displayLumaSigma, 0.0f));

    Plan out{};
    if (std::abs(controls.amount) <= 1.0e-4f && std::abs(controls.radius) <= 1.0e-4f &&
        std::abs(controls.detail) <= 1.0e-4f && std::abs(edgeAuthority) <= 1.0e-4f && antiZipperAuthority <= 1.0e-4f) {
        out.authoritySource = "PROFILE_SHARPNESS_EDGE_DETAIL_LEGIBILITY_ZERO";
        return out;
    }

    // Global Sharpness is intentionally standalone. Radius, Detail and Edge belong to
    // separate controls and must never multiply, gate or disable this signed control.
    // Physical display-domain sigma is useful as an internal noise guard when available, but
    // missing metadata may not disable the user-requested effect.
    out.authority = controls.amount;
    // Phase 4: signed text/glyph Legibility was decoded from the packed legacy Detail ABI field.
    out.radius = controls.radius;
    // Phase 3: independent signed microtexture authority. It is transported alongside
    // Global Sharpness and Edge but does not multiply or gate either control.
    out.detail = controls.detail;
    // Phase 2: independent signed Edge authority. This does not multiply Global Sharpness.
    // Packed Edge + Anti-zipper transport stays intact until the Vulkan backend; the shader applies Anti-zipper last.
    out.masking = packedEdgeZipper;
    out.displayLumaSigma = noise.displayLumaSigma > 1.0e-7f
            ? noise.displayLumaSigma
            : (1.0f / 255.0f);
    out.minimumResidualSnr = 0.90f;
    out.minimumGradientSnr = 0.75f;
    out.hardHaloLimit = controls.amount > 0.0f
            ? (0.055f + 0.085f * controls.amount)
            : 0.0f;
    out.modelConfidence = 1.0f;

    const float positiveSharpness = std::max(0.0f, controls.amount);
    const float positiveLegibility = std::max(0.0f, controls.radius);
    const float positiveDetail = std::max(0.0f, controls.detail);
    out.predictedLumaVarianceGain = std::clamp(
            1.0f + 0.85f * positiveSharpness * positiveSharpness +
                    0.24f * positiveLegibility * positiveLegibility +
                    0.42f * positiveDetail * positiveDetail,
            1.0f,
            2.10f);
    out.enabled = true;
    if (std::abs(controls.radius) > 1.0e-4f && std::abs(controls.amount) <= 1.0e-4f &&
        std::abs(controls.detail) <= 1.0e-4f && std::abs(edgeAuthority) <= 1.0e-4f && antiZipperAuthority <= 1.0e-4f) {
        out.authoritySource = controls.radius < 0.0f
                ? "PROFILE_LEGIBILITY_SOFTEN"
                : "PROFILE_LEGIBILITY_SHARPEN";
    } else if (std::abs(controls.detail) > 1.0e-4f && std::abs(controls.amount) <= 1.0e-4f &&
        std::abs(edgeAuthority) <= 1.0e-4f && antiZipperAuthority <= 1.0e-4f) {
        out.authoritySource = controls.detail < 0.0f
                ? "PROFILE_MICRODETAIL_REDUCE"
                : "PROFILE_MICRODETAIL_ENHANCE";
    } else if (antiZipperAuthority > 1.0e-4f && std::abs(edgeAuthority) <= 1.0e-4f && std::abs(controls.amount) <= 1.0e-4f) {
        out.authoritySource = "PROFILE_ANTI_ZIPPER_RECONSTRUCT";
    } else if (std::abs(edgeAuthority) > 1.0e-4f && std::abs(controls.amount) <= 1.0e-4f) {
        out.authoritySource = edgeAuthority < 0.0f ? "PROFILE_EDGE_SMOOTH" : "PROFILE_EDGE_SHARPEN";
    } else {
        out.authoritySource = controls.amount < 0.0f
                ? "PROFILE_GLOBAL_SHARPNESS_SOFTEN"
                : "PROFILE_GLOBAL_SHARPNESS_SHARPEN";
    }
    return out;
}

} // namespace bncam::perceptual_detail
