#include "../../main/cpp/ProfileToneRenderPolicy.h"

#include <cassert>
#include <cmath>
#include <iostream>

using bncam::tone::ProfileToneRenderInput;
using bncam::tone::resolveProfileToneRenderPlan;
using bncam::tone::applyProfileTonalRanges;

static bool near(float a, float b, float eps = 1.0e-5f) {
    return std::abs(a - b) <= eps;
}

int main() {
    const auto neutral = resolveProfileToneRenderPlan({});
    assert(near(neutral.exposureEv, 0.0f));
    assert(near(neutral.exposureMultiplier, 1.0f));
    assert(near(neutral.shoulderStartDelta, 0.0f));
    assert(near(neutral.shoulderStrengthScale, 1.0f));
    assert(near(neutral.shadowRangeDelta, 0.0f));
    assert(near(neutral.highlightRangeDelta, 0.0f));
    assert(near(neutral.whiteRangeDelta, 0.0f));
    assert(near(neutral.blackAnchorDelta, 0.0f));
    assert(near(neutral.contrastDelta, 0.0f));
    assert(near(neutral.localToneStrengthScale, 1.0f));

    auto brighter = resolveProfileToneRenderPlan(ProfileToneRenderInput{1.0f});
    auto darker = resolveProfileToneRenderPlan(ProfileToneRenderInput{-1.0f});
    assert(near(brighter.exposureEv, 2.0f));
    assert(near(brighter.exposureMultiplier, 4.0f));
    assert(near(darker.exposureEv, -2.0f));
    assert(near(darker.exposureMultiplier, 0.25f));

    ProfileToneRenderInput highlightInput{};
    highlightInput.highlights = 1.0f;
    const auto openHighlights = resolveProfileToneRenderPlan(highlightInput);
    highlightInput.highlights = -1.0f;
    const auto compressHighlights = resolveProfileToneRenderPlan(highlightInput);
    assert(near(openHighlights.shoulderStartDelta, 0.0f));
    assert(near(openHighlights.shoulderStrengthScale, 1.0f));
    assert(openHighlights.highlightRangeDelta > 0.0f);
    assert(near(compressHighlights.shoulderStartDelta, 0.0f));
    assert(near(compressHighlights.shoulderStrengthScale, 1.0f));
    assert(compressHighlights.highlightRangeDelta < 0.0f);

    ProfileToneRenderInput rangeInput{};
    rangeInput.shadows = 1.0f;
    rangeInput.whites = -1.0f;
    rangeInput.blacks = 1.0f;
    rangeInput.contrast = 1.0f;
    rangeInput.localToneBias = 1.0f;
    const auto range = resolveProfileToneRenderPlan(rangeInput);
    assert(range.shadowRangeDelta > 0.0f);
    assert(range.whiteRangeDelta < 0.0f);
    assert(near(range.blackAnchorDelta, 0.0f));
    assert(range.blackRangeDelta > 0.0f);
    assert(range.contrastDelta > 0.0f);
    assert(range.localToneStrengthScale > 1.0f);
    assert(range.localToneLiftScale > 1.0f);

    // The bounded range controls must stay monotonic even at extreme combinations.
    for (float shadows : {-1.0f, 0.0f, 1.0f}) {
        for (float highlights : {-1.0f, 0.0f, 1.0f}) {
            for (float whites : {-1.0f, 0.0f, 1.0f}) {
                for (float blacks : {-1.0f, 0.0f, 1.0f}) {
                    ProfileToneRenderInput tonal{};
                    tonal.shadows = shadows;
                    tonal.highlights = highlights;
                    tonal.whites = whites;
                    tonal.blacks = blacks;
                    const auto plan = resolveProfileToneRenderPlan(tonal);
                    float previous = applyProfileTonalRanges(0.0f, plan);
                    assert(near(previous, 0.0f));
                    for (int i = 1; i <= 4096; ++i) {
                        const float x = static_cast<float>(i) / 4096.0f;
                        const float y = applyProfileTonalRanges(x, plan);
                        assert(y + 1.0e-5f >= previous);
                        previous = y;
                    }
                    assert(near(applyProfileTonalRanges(1.0f, plan), 1.0f));
                }
            }
        }
    }

    ProfileToneRenderInput invalid{};
    invalid.exposure = INFINITY;
    invalid.highlights = -5.0f;
    invalid.localToneBias = 9.0f;
    const auto sanitized = resolveProfileToneRenderPlan(invalid);
    assert(near(sanitized.exposureMultiplier, 1.0f));
    assert(near(sanitized.shoulderStartDelta, 0.0f));
    assert(sanitized.highlightRangeDelta >= -0.0551f);
    assert(sanitized.localToneStrengthScale <= std::exp2(0.75f) + 1.0e-5f);

    std::cout << "PROFILE_TONE_RENDER_POLICY_TESTS_OK\n";
    return 0;
}
