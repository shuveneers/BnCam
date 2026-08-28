#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::tone {

struct ProfileToneRenderInput {
    float exposure = 0.0f;
    float highlights = 0.0f;
    float shadows = 0.0f;
    float whites = 0.0f;
    float blacks = 0.0f;
    float contrast = 0.0f;
    float localToneBias = 0.0f;
};

struct ProfileToneRenderPlan {
    float exposureEv = 0.0f;
    float exposureMultiplier = 1.0f;
    // Legacy shoulder fields stay ABI-stable but profile Highlights no longer modulates the
    // pre-DRT shoulder. All Lightroom-style tonal range controls are applied in one
    // endpoint-preserving display-linear look LUT after the scene-to-display transform.
    float shoulderStartDelta = 0.0f;
    float shoulderStrengthScale = 1.0f;
    float shadowRangeDelta = 0.0f;
    float highlightRangeDelta = 0.0f;
    float whiteRangeDelta = 0.0f;
    // Phase 10: Blacks is a creative display-range control, never an automatic black pedestal.
    float blackAnchorDelta = 0.0f; // Retained ABI/debug field; RAW architecture keeps this at zero.
    float blackRangeDelta = 0.0f;
    float contrastDelta = 0.0f;
    float localToneStrengthScale = 1.0f;
    float localToneLiftScale = 1.0f;
    float localToneCompressScale = 1.0f;
};

inline float sanitizeSignedTone(float value) noexcept {
    return std::isfinite(value) ? std::clamp(value, -1.0f, 1.0f) : 0.0f;
}

/**
 * Maps profile-owned Lightroom-style controls to bounded renderer parameters.
 *
 * This policy is render-only: no value in this plan is allowed to influence Camera2 shutter or
 * sensitivity. Zero on every control is an exact identity over BnCam's automatic GTM/LTM plan.
 */
inline ProfileToneRenderPlan resolveProfileToneRenderPlan(
        const ProfileToneRenderInput& input) noexcept {
    ProfileToneRenderPlan out{};
    const float exposure = sanitizeSignedTone(input.exposure);
    const float highlights = sanitizeSignedTone(input.highlights);
    const float shadows = sanitizeSignedTone(input.shadows);
    const float whites = sanitizeSignedTone(input.whites);
    const float blacks = sanitizeSignedTone(input.blacks);
    const float contrast = sanitizeSignedTone(input.contrast);
    const float localToneBias = sanitizeSignedTone(input.localToneBias);

    out.exposureEv = 2.0f * exposure;
    out.exposureMultiplier = std::exp2(out.exposureEv);

    // Highlights is a display-referred tonal range control, not an AgX/filmic shoulder control.
    // Keeping it in the same endpoint-preserving LUT as Shadows/Whites prevents RAW AgX, RAW
    // preview and CPU fallback from silently giving the slider different meanings.
    out.shoulderStartDelta = 0.0f;
    out.shoulderStrengthScale = 1.0f;

    // Range controls are deliberately bounded and preserve the black/white endpoints in the LUT.
    out.shadowRangeDelta = 0.085f * shadows;
    out.highlightRangeDelta = 0.055f * highlights;
    out.whiteRangeDelta = 0.080f * whites;

    // Phase 10: preserve true zero. Blacks reshapes the near-black range with an endpoint-
    // preserving window instead of subtracting a pedestal from every display value.
    out.blackAnchorDelta = 0.0f;
    out.blackRangeDelta = 0.060f * blacks;
    out.contrastDelta = 0.090f * contrast;

    // Local tone is a bias over Auto, not a second independent exposure control.
    out.localToneStrengthScale = std::exp2(0.75f * localToneBias);
    out.localToneLiftScale = std::exp2(0.45f * localToneBias);
    out.localToneCompressScale = std::exp2(0.30f * localToneBias);
    return out;
}

inline float applyProfileTonalRanges(float luma, const ProfileToneRenderPlan& plan) noexcept {
    float out = std::clamp(std::isfinite(luma) ? luma : 0.0f, 0.0f, 1.0f);

    // Endpoint-preserving polynomial windows. Their bounded derivatives keep the mapping monotonic
    // across the full -1..+1 control range, avoiding local luminance inversions/banding.
    const float oneMinusBlack = 1.0f - out;
    const float blackWindow = 6.0f * out * oneMinusBlack * oneMinusBlack *
            oneMinusBlack * oneMinusBlack * oneMinusBlack;
    out = std::clamp(out + plan.blackRangeDelta * blackWindow, 0.0f, 1.0f);

    const float oneMinusShadow = 1.0f - out;
    const float shadowWindow = 4.0f * out * oneMinusShadow * oneMinusShadow * oneMinusShadow;
    out = std::clamp(out + plan.shadowRangeDelta * shadowWindow, 0.0f, 1.0f);

    const float oneMinusHighlight = 1.0f - out;
    const float highlightWindow =
            16.0f * out * out * out * oneMinusHighlight * oneMinusHighlight;
    out = std::clamp(out + plan.highlightRangeDelta * highlightWindow, 0.0f, 1.0f);

    const float oneMinusWhite = 1.0f - out;
    const float whiteWindow = 4.0f * out * out * out * oneMinusWhite;
    out = std::clamp(out + plan.whiteRangeDelta * whiteWindow, 0.0f, 1.0f);
    return out;
}


} // namespace bncam::tone
