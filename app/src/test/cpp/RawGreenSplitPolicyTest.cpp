#include "../../main/cpp/RawGreenSplitPolicy.h"

#include <cassert>
#include <cmath>
#include <vector>

using bncam::raw_green_split::DecisionReason;

int main() {
    // No systematic bias: alternating local scene gradients must not trigger a frame-wide correction.
    std::vector<float> even;
    std::vector<float> odd;
    for (int i = 0; i < 256; ++i) {
        const float base = 0.10f + 0.001f * float(i % 40);
        const float delta = (i & 1) ? 0.004f : -0.004f;
        even.push_back(base + delta);
        odd.push_back(base - delta);
    }
    auto neutral = bncam::raw_green_split::evaluate(even, odd);
    assert(!neutral.apply);
    assert(neutral.reason == DecisionReason::BELOW_THRESHOLD ||
           neutral.reason == DecisionReason::LOW_SIGN_CONSENSUS);

    // Stable 4% multiplicative split with small local perturbations should be accepted.
    even.clear(); odd.clear();
    for (int i = 0; i < 512; ++i) {
        const float base = 0.08f + 0.0007f * float(i % 100);
        const float jitter = 0.0003f * float((i % 5) - 2);
        even.push_back(base * 1.02f + jitter);
        odd.push_back(base * 0.98f - jitter);
    }
    auto split = bncam::raw_green_split::evaluate(even, odd);
    assert(split.apply);
    assert(split.reason == DecisionReason::APPLY);
    assert(split.relativeMedian > 0.03f && split.relativeMedian < 0.05f);
    assert(split.signConsensus > 0.90f);
    assert(split.evenScale < 1.0f);
    assert(split.oddScale > 1.0f);

    // Strong scene-dependent disagreement should fail the consensus gate even if
    // independent frame-wide green medians could differ.
    even.clear(); odd.clear();
    for (int i = 0; i < 512; ++i) {
        const float base = 0.12f;
        const float rel = (i % 3 == 0) ? 0.08f : -0.04f;
        even.push_back(base * (1.0f + 0.5f * rel));
        odd.push_back(base * (1.0f - 0.5f * rel));
    }
    auto inconsistent = bncam::raw_green_split::evaluate(even, odd);
    assert(!inconsistent.apply);
    assert(inconsistent.reason == DecisionReason::LOW_SIGN_CONSENSUS ||
           inconsistent.reason == DecisionReason::UNSTABLE_PAIR_DISTRIBUTION ||
           inconsistent.reason == DecisionReason::BELOW_THRESHOLD);

    // Excessive split is treated as scene/content evidence, never blindly corrected.
    even.assign(256, 0.115f);
    odd.assign(256, 0.10f);
    auto excessive = bncam::raw_green_split::evaluate(even, odd);
    assert(!excessive.apply);
    assert(excessive.reason == DecisionReason::ABOVE_THRESHOLD);

    return 0;
}
