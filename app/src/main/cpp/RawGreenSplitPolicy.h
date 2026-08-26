#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

namespace bncam::raw_green_split {

enum class DecisionReason {
    APPLY,
    INSUFFICIENT_PAIRS,
    INSUFFICIENT_SIGNAL,
    BELOW_THRESHOLD,
    ABOVE_THRESHOLD,
    LOW_SIGN_CONSENSUS,
    UNSTABLE_PAIR_DISTRIBUTION,
};

struct Evidence {
    bool apply = false;
    DecisionReason reason = DecisionReason::INSUFFICIENT_PAIRS;
    std::size_t pairCount = 0u;
    float relativeMedian = 0.0f;
    float relativeMad = 0.0f;
    float signConsensus = 0.0f;
    float evenScale = 1.0f;
    float oddScale = 1.0f;
};

inline float medianCopy(std::vector<float> values) {
    if (values.empty()) return 0.0f;
    const std::size_t mid = values.size() / 2u;
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(mid), values.end());
    const float upper = values[mid];
    if ((values.size() & 1u) != 0u) return upper;
    std::nth_element(values.begin(), values.begin() + static_cast<std::ptrdiff_t>(mid - 1u), values.begin() + static_cast<std::ptrdiff_t>(mid));
    return 0.5f * (upper + values[mid - 1u]);
}

/**
 * Evidence gate for multiplicative G-even/G-odd response correction.
 *
 * The old policy compared two independent frame-wide green medians. A scene with a
 * directional luminance gradient can legitimately make those medians differ even when
 * the sensor green planes are matched. This policy instead uses paired G samples from
 * the same 2x2 CFA cell and requires the signed split to agree across the frame.
 *
 * It is intentionally conservative: failure to prove a systematic split means no pixel
 * mutation. This protects real texture and scene gradients. The returned symmetric scales
 * are bounded to the historical +/-4% correction ceiling.
 */
inline Evidence evaluate(
        const std::vector<float>& greenEven,
        const std::vector<float>& greenOdd,
        float minimumSignal = 0.006f,
        float maximumSignal = 0.90f,
        std::size_t minimumPairs = 64u) {
    Evidence out{};
    const std::size_t n = std::min(greenEven.size(), greenOdd.size());
    if (n < minimumPairs) {
        out.reason = DecisionReason::INSUFFICIENT_PAIRS;
        return out;
    }

    std::vector<float> relativeSplits;
    relativeSplits.reserve(n);
    for (std::size_t i = 0; i < n; ++i) {
        const float ge = greenEven[i];
        const float go = greenOdd[i];
        if (!std::isfinite(ge) || !std::isfinite(go)) continue;
        const float average = 0.5f * (ge + go);
        if (!(average >= minimumSignal && average <= maximumSignal)) continue;

        const float relative = (ge - go) / std::max(average, 1.0e-6f);
        if (!std::isfinite(relative)) continue;

        // Extremely different diagonal green samples are dominated by local scene
        // structure/edges and are not suitable evidence for a frame-wide sensor bias.
        if (std::abs(relative) > 0.20f) continue;
        relativeSplits.push_back(relative);
    }

    out.pairCount = relativeSplits.size();
    if (out.pairCount < minimumPairs) {
        out.reason = DecisionReason::INSUFFICIENT_SIGNAL;
        return out;
    }

    out.relativeMedian = medianCopy(relativeSplits);
    const float absoluteMedian = std::abs(out.relativeMedian);
    if (absoluteMedian < 0.010f) {
        out.reason = DecisionReason::BELOW_THRESHOLD;
        return out;
    }
    if (absoluteMedian > 0.120f) {
        out.reason = DecisionReason::ABOVE_THRESHOLD;
        return out;
    }

    std::size_t sameSign = 0u;
    std::vector<float> deviations;
    deviations.reserve(relativeSplits.size());
    const bool positive = out.relativeMedian >= 0.0f;
    for (const float value : relativeSplits) {
        if ((value >= 0.0f) == positive) ++sameSign;
        deviations.push_back(std::abs(value - out.relativeMedian));
    }
    out.signConsensus = static_cast<float>(sameSign) /
            static_cast<float>(std::max<std::size_t>(1u, relativeSplits.size()));
    out.relativeMad = medianCopy(std::move(deviations));

    if (out.signConsensus < 0.75f) {
        out.reason = DecisionReason::LOW_SIGN_CONSENSUS;
        return out;
    }

    const float stabilityLimit = std::max(0.020f, 1.25f * absoluteMedian);
    if (out.relativeMad > stabilityLimit) {
        out.reason = DecisionReason::UNSTABLE_PAIR_DISTRIBUTION;
        return out;
    }

    // If d=(Ge-Go)/mean, then approximately Ge=mean*(1+d/2) and
    // Go=mean*(1-d/2). Scale each side back toward the common mean.
    const float half = 0.5f * out.relativeMedian;
    out.evenScale = std::clamp(1.0f / std::max(0.25f, 1.0f + half), 0.96f, 1.04f);
    out.oddScale = std::clamp(1.0f / std::max(0.25f, 1.0f - half), 0.96f, 1.04f);
    out.apply = true;
    out.reason = DecisionReason::APPLY;
    return out;
}

inline const char* reasonName(DecisionReason reason) {
    switch (reason) {
        case DecisionReason::APPLY: return "paired_green_split_consensus";
        case DecisionReason::INSUFFICIENT_PAIRS: return "green_split_insufficient_pairs";
        case DecisionReason::INSUFFICIENT_SIGNAL: return "green_split_insufficient_mid_signal_pairs";
        case DecisionReason::BELOW_THRESHOLD: return "green_split_below_threshold";
        case DecisionReason::ABOVE_THRESHOLD: return "green_split_too_large_rejected_as_scene_bias";
        case DecisionReason::LOW_SIGN_CONSENSUS: return "green_split_low_pair_consensus";
        case DecisionReason::UNSTABLE_PAIR_DISTRIBUTION: return "green_split_unstable_pair_distribution";
    }
    return "green_split_unknown";
}

} // namespace bncam::raw_green_split
