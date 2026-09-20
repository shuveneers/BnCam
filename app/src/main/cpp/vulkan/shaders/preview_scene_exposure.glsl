#ifndef BNCAM_PREVIEW_SCENE_EXPOSURE_GLSL
#define BNCAM_PREVIEW_SCENE_EXPOSURE_GLSL

// Phase 11G — RAW preview mirrors the capture GlobalSceneExposurePlan/GTM semantics.
// Preview alone adds temporal smoothing around the same evidence-derived EV target. This file
// never changes Camera2 sensor exposure; it owns display/development placement only.
struct PreviewSceneExposurePlan {
    float requestedEv;
    float highlightLimitedEv;
    float appliedEv;
    float sceneKey;
    float midtone;
    float highlightHeadroomEv;
    float sceneRangeEv;
    float highlightPressure;
    float noisePressure;
};

struct PreviewGlobalToneMappingPlan {
    bool enabled;
    float shoulderStart;
    float shoulderStrength;
    float highlightPressure;
    float p95CompressionEv;
    float p99CompressionEv;
};

float previewSceneSafeLog2(float value) {
    return log2(max(value, 1.0e-6));
}

float previewSceneSmoothstep(float edge0, float edge1, float x) {
    if (!(edge1 > edge0)) return x >= edge1 ? 1.0 : 0.0;
    float t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// Keep this target resolver numerically aligned with GlobalSceneExposurePolicy.h. The only
// preview-specific behavior is the final temporal smoothing step around highlightLimitedEv.
PreviewSceneExposurePlan resolvePreviewSceneExposurePolicy(
        float p35,
        float p50,
        float p75,
        float p90,
        float p95,
        float p99,
        float rawClipFraction,
        float sensorSaturatedFraction,
        float physicalNoiseSigmaY,
        float noiseModelConfidence,
        float previousGain,
        bool previousGainValid) {
    PreviewSceneExposurePlan outPlan;
    p35 = clamp(p35, 0.0, 8.0);
    p50 = clamp(p50, 0.0, 8.0);
    p75 = clamp(p75, 0.0, 8.0);
    p90 = clamp(p90, 0.0, 8.0);
    p95 = clamp(p95, 0.0, 8.0);
    p99 = clamp(p99, 0.0, 8.0);

    float midtone = max(0.001, 0.20 * p35 + 0.35 * p50 + 0.45 * p75);
    float shadowPressure = clamp(
            0.65 * (1.0 - previewSceneSmoothstep(0.060, 0.210, p50)) +
            0.35 * (1.0 - previewSceneSmoothstep(0.120, 0.360, p75)),
            0.0, 1.0);
    float sceneRangeEv = clamp(
            previewSceneSafeLog2(max(p95, 0.010)) -
            previewSceneSafeLog2(max(p35, 0.006)), 0.0, 10.0);
    float dynamicRangePressure = previewSceneSmoothstep(2.0, 5.0, sceneRangeEv) *
            previewSceneSmoothstep(0.28, 0.72, p95);

    float strictClipFraction = max(
            clamp(rawClipFraction, 0.0, 1.0),
            clamp(sensorSaturatedFraction, 0.0, 1.0));
    float highlightPressure = clamp(max(
            0.58 * previewSceneSmoothstep(0.58, 0.92, p95) +
            0.27 * previewSceneSmoothstep(0.76, 1.08, p99) +
            0.15 * previewSceneSmoothstep(0.60, 1.05, p90),
            previewSceneSmoothstep(0.0005, 0.020, strictClipFraction)), 0.0, 1.0);

    float sigmaY = max(0.0, physicalNoiseSigmaY);
    float confidence = clamp(noiseModelConfidence, 0.0, 1.0);
    float referenceSignal = max(0.025, min(0.24, 0.65 * p50 + 0.35 * p75));
    float noisePressure = confidence * previewSceneSmoothstep(
            0.025, 0.16, sigmaY / referenceSignal);

    float sceneKey = clamp(
            0.148 + 0.018 * shadowPressure + 0.006 * dynamicRangePressure -
            0.012 * highlightPressure - 0.010 * noisePressure,
            0.132, 0.170);

    const float maxPositiveEv = 1.25;
    const float maxNegativeEv = -0.50;
    float requestedEv = clamp(
            previewSceneSafeLog2(sceneKey / midtone), maxNegativeEv, maxPositiveEv);

    float robustHigh = max(0.020, 0.75 * p95 + 0.25 * p99);
    float highlightHeadroomEv = clamp(
            previewSceneSafeLog2(1.20 / robustHigh), maxNegativeEv, maxPositiveEv);

    float highlightLimitedEv = requestedEv;
    if (requestedEv > 0.0) {
        float clipPressure = previewSceneSmoothstep(0.0005, 0.020, strictClipFraction);
        float clipLimitedEv = (1.0 - clipPressure) * maxPositiveEv;
        float noiseLimitedEv = (1.0 - noisePressure) * maxPositiveEv +
                noisePressure * 0.65;
        highlightLimitedEv = clamp(
                min(requestedEv, min(highlightHeadroomEv, min(clipLimitedEv, noiseLimitedEv))),
                0.0, maxPositiveEv);
    }

    // Same target as capture, preview-smoothed only for visual stability. Falling gain is faster
    // so highlights respond promptly; first valid frame adopts the capture-equivalent target.
    float previousEv = previousGainValid
            ? clamp(previewSceneSafeLog2(max(1.0e-6, previousGain)), maxNegativeEv, maxPositiveEv)
            : highlightLimitedEv;
    float deltaEv = highlightLimitedEv - previousEv;
    float appliedEv = previousEv;
    if (abs(deltaEv) >= 0.020) {
        float alpha = deltaEv > 0.0 ? 0.22 : 0.45;
        appliedEv = previousEv + alpha * deltaEv;
    }

    outPlan.requestedEv = requestedEv;
    outPlan.highlightLimitedEv = highlightLimitedEv;
    outPlan.appliedEv = clamp(appliedEv, maxNegativeEv, maxPositiveEv);
    outPlan.sceneKey = sceneKey;
    outPlan.midtone = midtone;
    outPlan.highlightHeadroomEv = highlightHeadroomEv;
    outPlan.sceneRangeEv = sceneRangeEv;
    outPlan.highlightPressure = highlightPressure;
    outPlan.noisePressure = noisePressure;
    return outPlan;
}

float previewGtmMapLuma(float y, float shoulderStart, float shoulderStrength) {
    float luma = max(0.0, y);
    if (luma <= shoulderStart) return luma;
    float headroom = max(0.05, 1.0 - shoulderStart);
    float normalized = (luma - shoulderStart) / headroom;
    return shoulderStart + headroom * normalized /
            (1.0 + shoulderStrength * normalized);
}

// Numerically mirrors GlobalToneMappingPolicy.h. Feed the temporally-smoothed preview EV so the
// preview shoulder follows the brightness actually shown, while keeping the same capture policy.
PreviewGlobalToneMappingPlan resolvePreviewGlobalToneMappingPlan(
        float p90,
        float p95,
        float p99,
        float nearWhiteFraction,
        float rawClipFraction,
        float sceneRangeEv,
        float globalExposureEv) {
    PreviewGlobalToneMappingPlan outPlan;
    float gain = exp2(clamp(globalExposureEv, -0.50, 1.25));
    p90 = max(0.0, p90 * gain);
    p95 = max(0.0, p95 * gain);
    p99 = max(0.0, p99 * gain);
    float nearWhite = clamp(nearWhiteFraction, 0.0, 1.0);
    float clip = clamp(rawClipFraction, 0.0, 1.0);
    float rangePressure = previewSceneSmoothstep(2.0, 5.0, sceneRangeEv);
    float highlightPressure = clamp(max(
            0.15 * previewSceneSmoothstep(0.48, 0.82, p90) +
            0.35 * previewSceneSmoothstep(0.62, 0.92, p95) +
            0.30 * previewSceneSmoothstep(0.78, 1.18, p99) +
            0.20 * previewSceneSmoothstep(0.02, 0.14, nearWhite),
            previewSceneSmoothstep(0.0005, 0.020, clip)), 0.0, 1.0);

    float shoulderStart = clamp(
            0.86 - 0.16 * highlightPressure - 0.025 * rangePressure,
            0.67, 0.86);
    float shoulderStrength = clamp(
            0.55 + 0.30 * highlightPressure + 0.08 * rangePressure,
            0.55, 0.93);
    bool enabled = highlightPressure > 0.025 || p99 > 0.86;

    float p95CompressionEv = 0.0;
    float p99CompressionEv = 0.0;
    if (enabled) {
        float mapped95 = previewGtmMapLuma(p95, shoulderStart, shoulderStrength);
        float mapped99 = previewGtmMapLuma(p99, shoulderStart, shoulderStrength);
        p95CompressionEv = p95 > 1.0e-6
                ? max(0.0, log2(p95 / max(mapped95, 1.0e-6))) : 0.0;
        p99CompressionEv = p99 > 1.0e-6
                ? max(0.0, log2(p99 / max(mapped99, 1.0e-6))) : 0.0;
    }

    outPlan.enabled = enabled;
    outPlan.shoulderStart = shoulderStart;
    outPlan.shoulderStrength = enabled ? shoulderStrength : 0.0;
    outPlan.highlightPressure = highlightPressure;
    outPlan.p95CompressionEv = p95CompressionEv;
    outPlan.p99CompressionEv = p99CompressionEv;
    return outPlan;
}

#endif
