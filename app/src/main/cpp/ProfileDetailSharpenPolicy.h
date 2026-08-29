#pragma once

#include <algorithm>
#include <cmath>

#include "NativeRenderQualityConfig.h"

namespace bncam::detail {

struct ProfileDetailControls {
    float amount = bncam::profile_defaults::kDetailAmount;
    float radius = bncam::profile_defaults::kDetailRadius;
    float detail = bncam::profile_defaults::kDetailDetail;
    float masking = bncam::profile_defaults::kDetailMasking;
};

struct DetailKernelCoefficients {
    float masterScale = 0.0f;
    float fineBandBias = 0.0f;
    float midBandBias = 0.0f;
    float broadBandBias = 0.0f;
    float edgeBandBias = 0.0f;
    float overshootGuard = 0.0f;
    float profileIntent = 0.0f;
};

inline ProfileDetailControls sanitize(ProfileDetailControls input) {
    input.amount = std::isfinite(input.amount)
            ? std::clamp(input.amount, 0.0f, 1.0f)
            : bncam::profile_defaults::kDetailAmount;
    const float storedRadius = std::isfinite(input.radius)
            ? std::clamp(input.radius, 0.0f, bncam::profile_defaults::kDetailMaxRadius)
            : bncam::profile_defaults::kDetailRadius;
    input.radius = input.amount <= 1.0e-4f
            ? 0.0f
            : std::clamp(storedRadius,
                         bncam::profile_defaults::kDetailMinRadius,
                         bncam::profile_defaults::kDetailMaxRadius);
    input.detail = std::isfinite(input.detail)
            ? std::clamp(input.detail, 0.0f, 1.0f)
            : bncam::profile_defaults::kDetailDetail;
    input.masking = std::isfinite(input.masking)
            ? std::clamp(input.masking, 0.0f, 1.0f)
            : bncam::profile_defaults::kDetailMasking;
    return input;
}

inline float centeredDelta(float value, float center, float minimum, float maximum) {
    if (value >= center) {
        const float span = std::max(maximum - center, 1.0e-6f);
        return std::clamp((value - center) / span, 0.0f, 1.0f);
    }
    const float span = std::max(center - minimum, 1.0e-6f);
    return std::clamp((value - center) / span, -1.0f, 0.0f);
}

/**
 * Converts Lightroom Detail controls into transient kernel coefficients.
 *
 * These coefficients are algorithm internals only: they are never persisted, imported/exported,
 * or transported across JNI/Vulkan ABI boundaries. Neutral profile controls resolve to exact
 * identity; active coefficients are derived only after the user deliberately raises Amount.
 */
inline DetailKernelCoefficients resolveKernelCoefficients(ProfileDetailControls input) {
    const ProfileDetailControls c = sanitize(input);
    if (c.amount <= 1.0e-4f) {
        DetailKernelCoefficients neutral{};
        neutral.masterScale = 0.0f;
        neutral.fineBandBias = 0.0f;
        neutral.midBandBias = 0.0f;
        neutral.broadBandBias = 0.0f;
        neutral.edgeBandBias = 0.0f;
        neutral.overshootGuard = 0.0f;
        neutral.profileIntent = 0.0f;
        return neutral;
    }
    const float radiusDelta = centeredDelta(
            c.radius,
            bncam::profile_defaults::kDetailRadius,
            bncam::profile_defaults::kDetailMinRadius,
            bncam::profile_defaults::kDetailMaxRadius
    );
    const float detailDelta = centeredDelta(
            c.detail,
            bncam::profile_defaults::kDetailDetail,
            0.0f,
            1.0f
    );

    const float masterOffset = std::clamp(-1.0f + 2.25f * c.amount, -1.0f, 1.0f);
    DetailKernelCoefficients out{};
    out.masterScale = std::clamp(1.0f + masterOffset, 0.0f, 2.0f);
    out.fineBandBias = std::clamp(-0.20f + 0.55f * detailDelta - 0.20f * radiusDelta, -1.0f, 1.0f);
    out.midBandBias = std::clamp(0.08f + 0.25f * detailDelta + 0.30f * radiusDelta, -1.0f, 1.0f);
    out.broadBandBias = std::clamp(0.45f * radiusDelta, -1.0f, 1.0f);
    out.edgeBandBias = std::clamp(-0.10f + 0.25f * detailDelta, -1.0f, 1.0f);
    out.overshootGuard = std::clamp(0.45f + 0.55f * c.masking, 0.0f, 1.0f);
    out.profileIntent = std::clamp(
            std::max({masterOffset, out.fineBandBias, out.midBandBias,
                      out.broadBandBias, out.edgeBandBias, 0.0f}),
            0.0f,
            1.0f
    );
    return out;
}

}  // namespace bncam::detail
