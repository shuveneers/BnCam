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
    // Legacy aggregate-initializer compatibility only. The removed local-tone bias no longer
    // owns any image operation; keeping the slot prevents older callers from breaking while the
    // profile schema is migrated end-to-end.
    float localToneBias = 0.0f;
};

struct ProfileToneRenderPlan {
    float exposureEv = 0.0f;
    float exposureMultiplier = 1.0f;
    float shadowRangeDelta = 0.0f;
    float highlightRangeDelta = 0.0f;
    float whiteRangeDelta = 0.0f;
    float blackRangeDelta = 0.0f;
    float contrastDelta = 0.0f;
};

inline float sanitizeSignedTone(float value) noexcept {
    return std::isfinite(value) ? std::clamp(value, -1.0f, 1.0f) : 0.0f;
}

/**
 * Explicit profile controls only. Camera2 acquisition, GlobalSceneExposurePlan, GTM and FLLF
 * never read these values. Phase 11F moves profile Exposure into the explicit post-display look
 * stage so it cannot become a second automatic scene-placement owner. Remaining controls are
 * bounded display-linear tonal-range operations in that same explicit profile stage.
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

    out.exposureEv = 2.0f * exposure;
    out.exposureMultiplier = std::exp2(out.exposureEv);
    out.shadowRangeDelta = 0.085f * shadows;
    out.highlightRangeDelta = 0.055f * highlights;
    out.whiteRangeDelta = 0.080f * whites;
    out.blackRangeDelta = 0.060f * blacks;
    out.contrastDelta = 0.090f * contrast;
    return out;
}

inline float applyProfileTonalRanges(float luma, const ProfileToneRenderPlan& plan) noexcept {
    float out = std::clamp(std::isfinite(luma) ? luma : 0.0f, 0.0f, 1.0f);

    const float oneMinusBlack = 1.0f - out;
    const float blackWindow = 6.0f * out * oneMinusBlack * oneMinusBlack *
            oneMinusBlack * oneMinusBlack * oneMinusBlack;
    out = std::clamp(out + plan.blackRangeDelta * blackWindow, 0.0f, 1.0f);

    const float oneMinusShadow = 1.0f - out;
    const float shadowWindow = 4.0f * out * oneMinusShadow * oneMinusShadow * oneMinusShadow;
    out = std::clamp(out + plan.shadowRangeDelta * shadowWindow, 0.0f, 1.0f);

    const float oneMinusHighlight = 1.0f - out;
    const float highlightWindow = 16.0f * out * out * out *
            oneMinusHighlight * oneMinusHighlight;
    out = std::clamp(out + plan.highlightRangeDelta * highlightWindow, 0.0f, 1.0f);

    const float oneMinusWhite = 1.0f - out;
    const float whiteWindow = 4.0f * out * out * out * oneMinusWhite;
    out = std::clamp(out + plan.whiteRangeDelta * whiteWindow, 0.0f, 1.0f);
    return out;
}

} // namespace bncam::tone
