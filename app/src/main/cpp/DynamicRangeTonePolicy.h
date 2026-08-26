#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::tone {

struct DynamicRangeToneInput {
    float p50 = 0.0f;
    float p75 = 0.0f;
    float p95 = 0.0f;
    float p98 = 0.0f;
    float p99 = 0.0f;
    float sensorSaturatedPct = 0.0f;   // percentage, 0..100
    float nearWhiteFraction = 0.0f;    // fraction, 0..1
    float displayHighlightConfidence = 0.0f;
    float indoorLowLightConfidence = 0.0f;
    bool lowLightScene = false;
    bool outdoorSkyScene = false;
    bool strongHighlightScene = false;
};

struct DynamicRangeTonePlan {
    float sensorClipPressure = 0.0f;
    float recoverableHighlightPressure = 0.0f;
    float shadowPressure = 0.0f;
    float dynamicRangePressure = 0.0f;
    float automaticBlackAnchor = 0.010f;
    float effectiveBlackAnchor = 0.010f;
    float sceneMidtoneTarget = 0.155f;
    float requestedLowerMidLift = 0.0f;
    float contrastStrength = 0.10f;
    float shoulderStart = 0.72f;
    float shoulderStrength = 0.90f;
    // AgX intentionally keeps substantial headroom. BnCam's normalized RAW pipeline rarely feeds
    // the +6.5 EV top of the stock transform, so an endpoint-preserving display-white placement
    // is applied after AgX. This is a display calibration, not extra exposure.
    float displayWhiteExpansionStart = 0.55f;
    float displayWhiteExpansionGamma = 2.20f;
};


inline float applyDisplayWhiteExpansion(
        float displayLuma,
        float start,
        float gamma) noexcept {
    const float x = std::clamp(std::isfinite(displayLuma) ? displayLuma : 0.0f, 0.0f, 1.0f);
    const float knee = std::clamp(std::isfinite(start) ? start : 0.55f, 0.45f, 0.80f);
    const float exponent = std::clamp(std::isfinite(gamma) ? gamma : 1.0f, 1.0f, 2.60f);
    if (x <= knee || exponent <= 1.0001f) return x;
    const float u = std::clamp((x - knee) / std::max(1.0e-6f, 1.0f - knee), 0.0f, 1.0f);
    // Monotonic, endpoint-preserving upper-range expansion. Unlike a polynomial white lift this
    // cannot fold the curve back near 1.0 even at maximum authority.
    const float expanded = 1.0f - std::pow(1.0f - u, exponent);
    return std::clamp(knee + (1.0f - knee) * expanded, 0.0f, 1.0f);
}

inline float toneSmoothstep(float edge0, float edge1, float x) noexcept {
    if (!(edge1 > edge0)) return x >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

/**
 * Decide whether a low-light RAW render may use a scene-wide sub-unity exposure gain.
 *
 * A single/local recoverable highlight is not sufficient evidence: those pixels are owned by
 * local highlight reconstruction and the tone shoulder. Global darkening is reserved for a
 * genuinely broad bright tail with coherent highlight confidence. This prevents indoor RAW
 * captures from being darkened by 1+ EV merely because one lamp/specular pixel was detected.
 */
inline bool allowLowLightSubUnityExposureGain(
        bool isRawBayer,
        bool lowLightScene,
        bool lowRawClipping,
        bool globalDarkeningRequested,
        bool strongHighlightScene,
        bool displayHighlightScene,
        float displayHighlightConfidence,
        float broadHighlightConfidence,
        float highlightOccupancyPct,
        float p98,
        float p99) noexcept {
    if (!isRawBayer || !lowLightScene || !lowRawClipping || !globalDarkeningRequested) {
        return false;
    }

    const float occupancy = std::max(0.0f, highlightOccupancyPct);
    const float displayConfidence = std::clamp(displayHighlightConfidence, 0.0f, 1.0f);
    const float broadConfidence = std::clamp(broadHighlightConfidence, 0.0f, 1.0f);
    const float safeP98 = std::max(0.0f, p98);
    const float safeP99 = std::max(0.0f, p99);

    const bool meaningfulOccupancy = occupancy >= 0.25f;
    const bool highTail = safeP98 >= 0.72f || safeP99 >= 0.94f;
    const bool coherentHighlightEvidence =
            strongHighlightScene || displayHighlightScene ||
            displayConfidence >= 0.40f || broadConfidence >= 0.42f;

    return meaningfulOccupancy && highTail && coherentHighlightEvidence;
}

/**
 * Scalar scene policy only; all full-frame tone application remains Vulkan-resident.
 *
 * The plan deliberately separates sensor saturation from bright-but-still-recoverable tail
 * pressure. A bright p98/p99 with little sensor saturation asks for an earlier shoulder while
 * preserving exposure; genuine sensor saturation cannot be reconstructed and therefore only
 * increases protective rolloff. Dark lower mids are lifted modestly only when the histogram
 * demonstrates shadow pressure, with the strongest lift reserved for simultaneous highlight
 * pressure (backlight/high-DR scenes). Deep black is not raised by this plan.
 */
inline DynamicRangeTonePlan resolveDynamicRangeTonePlan(
        const DynamicRangeToneInput& input) noexcept {
    DynamicRangeTonePlan out{};
    const float p50 = std::clamp(input.p50, 0.0f, 1.5f);
    const float p75 = std::clamp(input.p75, 0.0f, 1.5f);
    const float p95 = std::clamp(input.p95, 0.0f, 1.5f);
    const float p98 = std::clamp(input.p98, 0.0f, 1.5f);
    const float p99 = std::clamp(input.p99, 0.0f, 1.5f);
    const float sensorSaturatedPct = std::max(0.0f, input.sensorSaturatedPct);
    const float nearWhite = std::clamp(input.nearWhiteFraction, 0.0f, 1.0f);

    out.sensorClipPressure = std::clamp(
            0.65f * toneSmoothstep(0.05f, 0.60f, sensorSaturatedPct) +
            0.35f * toneSmoothstep(0.25f, 2.00f, sensorSaturatedPct),
            0.0f, 1.0f);

    const float highlightTailPressure = std::clamp(
            0.50f * toneSmoothstep(0.62f, 0.92f, p95) +
            0.30f * toneSmoothstep(0.74f, 0.97f, p98) +
            0.20f * toneSmoothstep(0.88f, 1.02f, p99),
            0.0f, 1.0f);
    const float occupancyPressure = toneSmoothstep(0.0025f, 0.045f, nearWhite);
    const float displayPressure = std::clamp(input.displayHighlightConfidence, 0.0f, 1.0f);
    const float brightTailEvidence = std::clamp(
            0.55f * highlightTailPressure +
            0.25f * occupancyPressure +
            0.20f * displayPressure,
            0.0f, 1.0f);
    out.recoverableHighlightPressure = std::clamp(
            brightTailEvidence * (1.0f - 0.78f * out.sensorClipPressure),
            0.0f, 1.0f);

    const float deepMedianPressure = 1.0f - toneSmoothstep(0.055f, 0.20f, p50);
    const float lowerMidPressure = 1.0f - toneSmoothstep(0.13f, 0.38f, p75);
    out.shadowPressure = std::clamp(
            0.62f * deepMedianPressure + 0.38f * lowerMidPressure,
            0.0f, 1.0f);

    const float highlightPressure = std::max(
            out.recoverableHighlightPressure,
            0.72f * out.sensorClipPressure);
    const float explicitHighDrEvidence = std::max(
            input.strongHighlightScene ? 1.0f : 0.0f,
            std::max(displayPressure, input.outdoorSkyScene ? 0.65f : 0.0f));
    out.dynamicRangePressure = std::clamp(
            out.shadowPressure * std::max(highlightPressure, 0.72f * explicitHighDrEvidence),
            0.0f, 1.0f);

    // Keep a real black point without using black subtraction as a global-darkening mechanism.
    // The former ~0.010 baseline visibly crushed lower tones after RAW normalization. High-DR
    // scenes need *less* black anchoring because the shoulder owns highlight compression.
    out.automaticBlackAnchor = std::clamp(
            0.0065f - 0.0035f * out.dynamicRangePressure -
            0.0010f * (input.lowLightScene ? out.shadowPressure : 0.0f),
            0.0025f, 0.0080f);
    if (input.outdoorSkyScene) {
        out.automaticBlackAnchor = std::max(out.automaticBlackAnchor, 0.0045f);
    }
    out.effectiveBlackAnchor = out.automaticBlackAnchor;

    // Mid-gray placement is explicit GTM policy. Highlight protection belongs to the shoulder,
    // not to a globally dark exposure target. In balanced or high-DR scenes, midtones are placed
    // comfortably around 0.155. In low light or genuinely dark scenes, do not force the entire frame
    // toward mid-grey; allow dark scenes to settle naturally without amplifying the noise floor.
    // Keep scene placement photographic rather than using the GTM exposure governor as a broad
    // shadow-fill control. The previous high-DR target reached ~0.174 and then LTM/lower-mid lift
    // raised most of the frame again, producing the flat/hazy rendering seen in backlit captures.
    const float shadowTargetLift = out.dynamicRangePressure > 0.10f
            ? 0.006f * out.dynamicRangePressure
            : 0.0f;
    const float highDrTargetLift = 0.004f * out.dynamicRangePressure;
    const float lowLightTargetReduction = input.lowLightScene
            ? 0.018f * (1.0f - out.dynamicRangePressure)
            : 0.0f;
    out.sceneMidtoneTarget = std::clamp(
            0.150f + shadowTargetLift + highDrTargetLift - lowLightTargetReduction,
            0.130f, 0.162f);

    const float lowLightOnlyLift = (input.lowLightScene && out.dynamicRangePressure > 0.15f)
            ? 0.008f * out.shadowPressure * (1.0f - 0.55f * highlightPressure)
            : 0.0f;
    const float highDrLift = 0.025f * out.dynamicRangePressure;
    out.requestedLowerMidLift = std::clamp(lowLightOnlyLift + highDrLift, 0.0f, 0.035f);

    // Preserve enough global separation that AgX + LTM does not turn broad backlit scenes into a
    // grey veil. LTM owns genuinely local dynamic range; GTM keeps the scene's global contrast.
    out.contrastStrength = std::clamp(
            0.105f - 0.042f * out.dynamicRangePressure,
            0.060f, 0.105f);

    // The shoulder is the primary global highlight authority. It starts late in ordinary scenes
    // and moves earlier/stronger only with recoverable or clipped highlight evidence. Because the
    // rational shoulder is asymptotic to display white, scene-linear values above 1 remain ordered
    // instead of being hard-clipped before the tone map.
    out.shoulderStart = std::clamp(
            0.72f -
            0.115f * out.recoverableHighlightPressure -
            0.055f * out.sensorClipPressure -
            0.030f * out.dynamicRangePressure,
            0.52f, 0.74f);
    out.shoulderStrength = std::clamp(
            0.90f +
            0.75f * out.recoverableHighlightPressure +
            0.55f * out.sensorClipPressure +
            0.30f * out.dynamicRangePressure,
            0.85f, 2.20f);

    // Stock AgX maps scene-linear 1.0 to a deliberately conservative display value and reserves
    // many stops above it. That is appropriate for a generic scene-linear compositor, but BnCam's
    // normalized mobile RAW path rarely occupies that whole range. Device A/B showed p99 around
    // 0.74 and p99.9 around 0.875 while the sensor itself was only ~0.2% saturated. Place display
    // white after AgX instead of increasing global exposure: midtones below the knee are unchanged,
    // highlight ordering remains monotonic, and genuine sensor clipping reduces the expansion.
    out.displayWhiteExpansionStart = std::clamp(
            0.55f + 0.035f * out.sensorClipPressure,
            0.55f, 0.60f);
    out.displayWhiteExpansionGamma = std::clamp(
            2.20f + 0.18f * out.recoverableHighlightPressure -
            0.55f * out.sensorClipPressure -
            (input.lowLightScene ? 0.12f * (1.0f - out.dynamicRangePressure) : 0.0f),
            1.55f, 2.40f);

    // A truly dark scene with no meaningful bright tail should not be flattened by a premature
    // shoulder merely because low-light classification is active.
    if (input.lowLightScene && highlightPressure < 0.15f && p95 < 0.55f) {
        out.shoulderStart = 0.74f;
        out.shoulderStrength = 0.85f;
    }
    return out;
}

} // namespace bncam::tone
