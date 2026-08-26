#pragma once

#include "SpectraPhysicalBaselineNr.h"

#include <algorithm>
#include <cmath>

namespace bncam::singleframe {

/**
 * Physical pre-demosaic single-frame RAW denoise policy.
 *
 * Authority is derived only from the calibrated sensor variance model V(x)=S*x+O.
 * RAW container type and capture ISO are deliberately absent from the API. The shader
 * remains responsible for per-pixel, per-CFA noise significance and structure protection;
 * this plan only maps measured frame noise pressure onto conservative operating limits.
 */
struct RawDenoisePlan {
    bool active = false;
    float modelConfidence = 0.0f;
    float physicalNoisePressure = 0.0f;
    float lumaAuthority = 0.0f;
    float chromaAuthority = 0.0f;
    float lowFrequencyChromaAuthority = 0.0f;
    float blendStrength = 0.0f;
    float targetFloorScale = 1.0f;
    float minimumResidualRatio = 1.0f;
    float detailRetentionFloor = 1.0f;
    float maxLinearShift = 0.0f;
};

inline RawDenoisePlan resolveRawDenoisePlan(
        float meanSensorNoiseVariance,
        float calibrationFactor,
        float modelConfidence) {
    RawDenoisePlan plan{};
    const auto physical = bncam::spectra2::resolvePhysicalChromaBaseStrength(
            meanSensorNoiseVariance, calibrationFactor, modelConfidence);
    if (!physical.modelDriven || physical.modelConfidence < 0.25f ||
        !(physical.calibratedNoiseSigma > 0.0f)) {
        return plan;
    }

    plan.active = true;
    plan.modelConfidence = physical.modelConfidence;
    plan.physicalNoisePressure = physical.combinedNoisePressure;
    const float p = plan.physicalNoisePressure;
    const float c = plan.modelConfidence;

    // Profiled luma baseline: the VST domain equalises the calibrated S/O noise,
    // while the shader separates fine/mid/coarse same-CFA bands.  Physical authority
    // may therefore be stronger in noise-dominant regions without lowering the
    // independent structure-retention floor.
    plan.lumaAuthority = std::clamp((0.16f + 0.70f * p) * c, 0.10f, 0.88f);
    // CFA opponent chroma can tolerate more authority because green-guided R-G/B-G
    // shrinkage does not low-pass the luminance structure itself.
    plan.chromaAuthority = std::clamp((0.24f + 0.70f * p) * c, 0.12f, 0.94f);
    plan.lowFrequencyChromaAuthority = std::clamp((0.08f + 0.42f * p) * c, 0.04f, 0.50f);

    plan.blendStrength = std::clamp((0.30f + 1.00f * p) * (0.76f + 0.24f * c), 0.14f, 1.28f);

    // The previous Phase-4 floor intentionally retained too much stochastic energy:
    // device captures still showed visible grain while the candidate remained strongly
    // structure protected.  For the physical single-frame baseline, let flat/noisy
    // regions approach a substantially cleaner posterior floor, but *raise* the
    // independent structure floor so coherent edges/textures are not traded for blur.
    plan.targetFloorScale = std::clamp(1.02f - 0.18f * p, 0.82f, 1.04f);
    plan.minimumResidualRatio = std::clamp(0.78f - 0.44f * p, 0.30f, 0.82f);
    plan.detailRetentionFloor = std::clamp(0.992f - 0.090f * p, 0.90f, 0.995f);
    plan.maxLinearShift = std::clamp(0.0018f + 0.0100f * p, 0.0012f, 0.0135f);
    return plan;
}

}  // namespace bncam::singleframe
