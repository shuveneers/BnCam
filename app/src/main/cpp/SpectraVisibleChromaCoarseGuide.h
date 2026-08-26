#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

struct VisibleChromaCoarseGuideDecision {
    float noiseNeed = 0.0f;
    float supportWeight = 0.0f;
    float contextRelease = 0.0f;
    float shadowContext = 0.0f;
    float blend = 0.0f;
};

inline float coarseGuideSmoothstep(float edge0, float edge1, float value) {
    if (!std::isfinite(value) || !(edge1 > edge0)) return 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// Sparse coarse opponent-colour guide for the late SPECTRA residual stage.
// The guide is only allowed to influence the fine 5x5 candidate when:
//  - it differs from the center by a noise-significant amount,
//  - enough coarse neighbours support it,
//  - fine context does not confirm a hard luma/colour edge.
// Profile authority remains upstream in `visibleAuthority`.
inline VisibleChromaCoarseGuideDecision resolveVisibleChromaCoarseGuide(
        float visibleAuthority,
        float coarseMahalanobisSquared,
        float coarseSupportFraction,
        float lumaEdgeSigma,
        float colourEdgeSigma,
        float luminance,
        float localNoiseScale) {
    VisibleChromaCoarseGuideDecision out{};
    const float authority = std::clamp(
            std::isfinite(visibleAuthority) ? visibleAuthority : 0.0f, 0.0f, 1.0f);
    const float coarseDistance = std::max(
            0.0f,
            std::isfinite(coarseMahalanobisSquared) ? coarseMahalanobisSquared : 0.0f);
    const float support = std::clamp(
            std::isfinite(coarseSupportFraction) ? coarseSupportFraction : 0.0f,
            0.0f, 1.0f);
    const float lumaEdge = std::max(0.0f, std::isfinite(lumaEdgeSigma) ? lumaEdgeSigma : 0.0f);
    const float colourEdge = std::max(0.0f, std::isfinite(colourEdgeSigma) ? colourEdgeSigma : 0.0f);
    const float y = std::clamp(std::isfinite(luminance) ? luminance : 0.0f, 0.0f, 1.0f);
    const float noiseScale = std::clamp(
            std::isfinite(localNoiseScale) ? localNoiseScale : 1.0f,
            0.55f, 2.25f);

    out.noiseNeed = coarseGuideSmoothstep(0.12f, 2.10f, coarseDistance);
    out.supportWeight = coarseGuideSmoothstep(0.12f, 0.48f, support);

    const float lumaProtection = coarseGuideSmoothstep(1.25f, 2.30f, lumaEdge);
    const float colourProtection = coarseGuideSmoothstep(1.85f, 3.35f, colourEdge);
    out.contextRelease = std::clamp(
            1.0f - (0.58f * lumaProtection + 0.42f * colourProtection),
            0.0f, 1.0f);
    out.shadowContext = 0.45f + 0.55f *
            (1.0f - coarseGuideSmoothstep(0.18f, 0.62f, y));
    const float noiseScaleGain = std::clamp(
            0.80f + 0.20f * (noiseScale - 0.55f) / 1.70f,
            0.80f, 1.0f);

    out.blend = std::clamp(
            authority * 0.72f * out.noiseNeed * out.supportWeight *
                    out.contextRelease * out.shadowContext * noiseScaleGain,
            0.0f, 0.65f);
    return out;
}

}  // namespace bncam::spectra2
