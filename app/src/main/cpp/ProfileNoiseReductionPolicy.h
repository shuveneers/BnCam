#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::profile_nr {

struct ProfileNoiseReductionPlan {
    float luminance = 0.0f;
    float luminanceDetail = 0.5f;
    float luminanceContrast = 0.0f;
    float color = 0.0f;
    float colorDetail = 0.5f;
    float colorSmoothness = 0.5f;
    float lumaCreativeBlend = 0.0f;
    float chromaCreativeBlend = 0.0f;
    float lowFrequencyChromaAuthority = 0.0f;
    bool requested = false;
};

inline float finiteUnit(float value, float fallback = 0.0f) {
    if (!std::isfinite(value)) return std::clamp(fallback, 0.0f, 1.0f);
    return std::clamp(value, 0.0f, 1.0f);
}

inline ProfileNoiseReductionPlan resolveProfileNoiseReduction(
        float luminance,
        float luminanceDetail,
        float luminanceContrast,
        float color,
        float colorDetail,
        float colorSmoothness) {
    ProfileNoiseReductionPlan out{};
    out.luminance = finiteUnit(luminance);
    out.luminanceDetail = finiteUnit(luminanceDetail, 0.5f);
    out.luminanceContrast = finiteUnit(luminanceContrast);
    out.color = finiteUnit(color);
    out.colorDetail = finiteUnit(colorDetail, 0.5f);
    out.colorSmoothness = finiteUnit(colorSmoothness, 0.5f);
    out.lumaCreativeBlend = out.luminance * 0.45f;
    out.chromaCreativeBlend = out.color * 0.55f;
    // Smoothness has no authority on its own: Color must be non-zero first.
    out.lowFrequencyChromaAuthority = out.color * out.colorSmoothness * 0.55f;
    out.requested = out.luminance > 0.005f || out.color > 0.005f;
    return out;
}

// Adds creative NR only into authority that the physical/SPECTRA base has not
// already consumed. This avoids simple additive double-denoise when SPECTRA is strong.
inline float combineWithResidualHeadroom(float base, float creative, float ceiling) {
    const float safeCeiling = std::max(0.0f, ceiling);
    const float b = std::clamp(std::isfinite(base) ? base : 0.0f, 0.0f, safeCeiling);
    const float c = std::clamp(std::isfinite(creative) ? creative : 0.0f, 0.0f, 1.0f);
    return std::clamp(b + (safeCeiling - b) * c, 0.0f, safeCeiling);
}

inline float lumaProfileStructureGate(
        float structureConfidence,
        const ProfileNoiseReductionPlan& plan) {
    if (plan.luminance <= 0.0f) return 1.0f;
    const float s = finiteUnit(structureConfidence);
    // Detail=0.5 preserves the previous ~0.65 structure attenuation. Higher
    // Detail protects more texture. Luminance Contrast protects medium/high
    // structure further instead of applying a second global contrast curve.
    const float detailProtection = 0.45f + 0.40f * plan.luminanceDetail;
    const float contrastProtection = 0.25f * plan.luminanceContrast * s;
    return std::clamp(1.0f - (detailProtection + contrastProtection) * s, 0.08f, 1.0f);
}

inline float chromaProfileStructureGate(
        float structureConfidence,
        const ProfileNoiseReductionPlan& plan) {
    if (plan.color <= 0.0f) return 1.0f;
    const float s = finiteUnit(structureConfidence);
    const float protection = 0.35f + 0.50f * plan.colorDetail;
    return std::clamp(1.0f - protection * s, 0.10f, 1.0f);
}

} // namespace bncam::profile_nr
