#pragma once

#include <algorithm>
#include <cmath>

namespace bncam::spectra2 {

struct PhysicalBaselineNrPlan {
    bool active = false;
    float lumaSigma = 0.0f;
    float chromaSigma = 0.0f;
    float lumaFraction = 0.0f;
    float chromaFraction = 0.0f;
    float upstreamLumaReduction = 0.0f;
    float upstreamChromaReduction = 0.0f;
};


struct PhysicalPreToneChromaPlan {
    bool enabled = false;
    bool physicalBaselineActive = false;
    bool spectraEnhancementActive = false;
    float noisePressure = 0.0f;
    float wbCcmPressure = 0.0f;
    float baselineStrength = 0.0f;
    float upstreamChromaReduction = 0.0f;
    float residualHeadroom = 1.0f;
    float spectraEnhancement = 0.0f;
    float finalStrength = 0.0f;
};

/**
 * Resolve the scene-linear, full-resolution chroma baseline before tone mapping.
 *
 * This is deliberately independent of SPECTRA Context Fusion: a normal RAW render must
 * already suppress most sensor chroma noise. SPECTRA is an enhancement layer that may add
 * a small amount of authority, never the switch that makes baseline denoising exist.
 *
 * The baseline is driven by physical/noise pressure and measured WB+CCM amplification.
 * It runs before tone so a nonlinear tone curve cannot amplify residual chroma first.
 */
inline PhysicalPreToneChromaPlan resolvePhysicalPreToneChroma(
        bool isRawBayer,
        bool physicalNoiseModelAvailable,
        bool spectraContextFusionActive,
        float combinedNoisePressure,
        float wbCcmPressure,
        float upstreamChromaReduction = 0.0f) {
    PhysicalPreToneChromaPlan plan{};
    if (!isRawBayer || !physicalNoiseModelAvailable) {
        return plan;
    }

    plan.enabled = true;
    plan.physicalBaselineActive = true;
    plan.spectraEnhancementActive = spectraContextFusionActive;
    plan.noisePressure = std::clamp(
            std::isfinite(combinedNoisePressure) ? combinedNoisePressure : 0.0f,
            0.0f, 1.0f);
    plan.wbCcmPressure = std::clamp(
            std::isfinite(wbCcmPressure) ? wbCcmPressure : 0.0f,
            0.0f, 1.0f);

    // Baseline authority intentionally sits close to the former SPECTRA-On stage.
    // Chroma is luma-guided and luminance-preserving, so it can be substantially cleaner
    // than luminance without softening real luminance detail.
    plan.upstreamChromaReduction = std::clamp(
            std::isfinite(upstreamChromaReduction) ? upstreamChromaReduction : 0.0f,
            0.0f, 1.0f);
    // Delta 0062 device correction: upstream authority is not the same as measured removal.
    // The 1x device gate still shows coherent chroma risk after the early stage, so do not
    // over-credit upstream cleanup. WB+CCM pressure is measured downstream amplification and
    // therefore gets more weight in the luma-guided chroma-only pre-tone baseline.
    plan.residualHeadroom = std::clamp(
            1.0f - 0.50f * plan.upstreamChromaReduction, 0.42f, 1.0f);
    plan.baselineStrength = std::clamp(
            (0.58f + 0.14f * plan.noisePressure + 0.18f * plan.wbCcmPressure) *
                    plan.residualHeadroom,
            0.0f, 0.86f);

    // Context Fusion is the finishing layer, not the baseline. Keep the increment modest.
    plan.spectraEnhancement = spectraContextFusionActive ? 0.10f : 0.0f;
    plan.finalStrength = std::clamp(
            plan.baselineStrength + plan.spectraEnhancement,
            0.0f, 0.94f);
    return plan;
}

struct PhysicalChromaBaseStrengthPlan {
    bool modelDriven = false;
    float rawNoiseSigma = 0.0f;
    float calibratedNoiseSigma = 0.0f;
    float modelConfidence = 0.0f;
    // Compatibility telemetry fields. Render gain and capture ISO no longer determine
    // physical denoise authority; combinedNoisePressure is the calibrated S/O pressure.
    float renderGainPressure = 0.0f;
    float captureIsoPressure = 0.0f;
    float combinedNoisePressure = 0.0f;
    float baseStrength = 0.0f;
};

inline float physicalBaselineSmoothstep(float edge0, float edge1, float value) {
    if (!std::isfinite(value) || !(edge1 > edge0)) {
        return 0.0f;
    }
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

/**
 * Resolve the early-ISP physical chroma baseline directly from the calibrated sensor
 * variance model. RAW10 and RAW_SENSOR are deliberately not inputs: after Phase-2
 * normalization both represent the same scene-linear sensor signal and therefore the same
 * S/O model must yield the same physical authority.
 *
 * `meanSensorNoiseVariance` is the compact GPU-evaluated mean V(x)=S*x+O in normalized
 * sensor space. `calibrationFactor` scales variance (not sigma). Confidence gates authority
 * rather than inventing an ISO/format fallback when the physical model is weak or absent.
 */
inline PhysicalChromaBaseStrengthPlan resolvePhysicalChromaBaseStrength(
        float meanSensorNoiseVariance,
        float calibrationFactor,
        float modelConfidence) {
    PhysicalChromaBaseStrengthPlan plan{};

    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f, 1.0f);
    const float variance = std::isfinite(meanSensorNoiseVariance)
            ? std::max(0.0f, meanSensorNoiseVariance)
            : 0.0f;
    if (!(variance > 0.0f) || confidence <= 0.0f) {
        return plan;
    }

    const float varianceScale = std::clamp(
            std::isfinite(calibrationFactor) ? calibrationFactor : 1.0f,
            0.25f, 4.0f);
    plan.modelDriven = true;
    plan.rawNoiseSigma = std::sqrt(variance);
    plan.calibratedNoiseSigma = std::sqrt(variance * varianceScale);
    plan.modelConfidence = confidence;

    // Device captures span roughly sigma 0.0018 at low ISO to ~0.014 at ISO 18k.
    // Work in stops so the mapping remains smooth across the wide Poisson-Gaussian range.
    // The constants only map an already-measured physical sigma onto conservative filter
    // authority; they do not estimate noise from ISO or output brightness.
    constexpr float kReferenceSigma = 0.0015f;
    constexpr float kFullAuthorityStops = 3.25f;
    const float sigmaStops = std::max(
            0.0f,
            std::log2(std::max(plan.calibratedNoiseSigma, kReferenceSigma) / kReferenceSigma));
    const float physicalPressure = physicalBaselineSmoothstep(
            0.0f, kFullAuthorityStops, sigmaStops);
    plan.combinedNoisePressure = std::clamp(physicalPressure * confidence, 0.0f, 1.0f);

    // One normalized-sensor baseline for both RAW formats. Keep low-noise authority modest
    // to protect texture; high physical pressure may approach 0.40, still below the common
    // downstream safety ceiling used by the ISP.
    plan.baseStrength = confidence * (0.06f + 0.34f * plan.combinedNoisePressure);
    plan.baseStrength = std::clamp(plan.baseStrength, 0.0f, 0.40f);
    return plan;
}

inline float resolveNoiseTruthDynamicHeadroomFraction(
        float coefficient,
        float modelNoisePressure,
        float effectiveChromaAuthorityStops) {
    const float safeCoefficient = std::clamp(
            std::isfinite(coefficient) ? coefficient : 0.0f,
            0.0f, 1.0f);
    const float safePressure = std::clamp(
            std::isfinite(modelNoisePressure) ? modelNoisePressure : 0.0f,
            0.0f, 1.0f);
    const float normalizedAuthority = std::clamp(
            (std::isfinite(effectiveChromaAuthorityStops) ? effectiveChromaAuthorityStops : 0.0f) / 5.0f,
            0.0f, 1.0f);
    return std::clamp(safeCoefficient * safePressure * normalizedAuthority, 0.0f, 1.0f);
}

inline float resolvePhysicalPreToneLumaAuthority(float preToneChromaStrength) {
    const float strength = std::clamp(
            std::isfinite(preToneChromaStrength) ? preToneChromaStrength : 0.0f,
            0.0f, 1.0f);
    // Luma is deliberately much more conservative than chroma. It only supplies a
    // physical flat-region grain baseline; profile Luminance NR remains the creative control.
    return std::clamp(0.08f + 0.28f * strength, 0.0f, 0.34f);
}

/**
 * Conventional physical-noise baseline used only while SPECTRA Context Fusion is Off.
 * It prevents "SPECTRA Off" from meaning "ignore the sensor variance model", while leaving
 * full physical sigma plus the pre-demosaic/context-aware SPECTRA stages as a clear On benefit.
 */
inline PhysicalBaselineNrPlan resolvePhysicalBaselineNr(
        float physicalLumaSigma,
        float physicalChromaSigma,
        bool physicalNoiseModelAvailable,
        bool spectraContextFusionActive,
        float upstreamLumaReduction = 0.0f,
        float upstreamChromaReduction = 0.0f,
        float preToneChromaStrength = 0.0f) {
    PhysicalBaselineNrPlan plan{};
    if (!physicalNoiseModelAvailable || spectraContextFusionActive ||
        !std::isfinite(physicalLumaSigma) || !std::isfinite(physicalChromaSigma) ||
        physicalLumaSigma <= 0.0f || physicalChromaSigma <= 0.0f) {
        return plan;
    }

    // SPECTRA Off is a real photographic baseline, not a deliberately noisy comparison mode.
    // Keep luma conservative to preserve microtexture, while allowing a modestly super-physical
    // chroma sigma because post-demosaic opponent noise is visually objectionable and can be
    // suppressed more strongly than luminance grain without softening edges.
    plan.active = true;
    plan.upstreamLumaReduction = std::clamp(
            std::isfinite(upstreamLumaReduction) ? upstreamLumaReduction : 0.0f, 0.0f, 1.0f);
    plan.upstreamChromaReduction = std::clamp(
            std::isfinite(upstreamChromaReduction) ? upstreamChromaReduction : 0.0f, 0.0f, 1.0f);
    const float preTone = std::clamp(
            std::isfinite(preToneChromaStrength) ? preToneChromaStrength : 0.0f, 0.0f, 1.0f);

    // The post-demosaic stage owns residual cleanup only. As the pre-demosaic VST/opponent
    // passes remove measured noise, its fraction decreases instead of applying a second full
    // physical sigma. Keep conservative floors so demosaic/WB/CCM-created residuals remain covered.
    plan.lumaFraction = std::clamp(
            0.78f * (1.0f - 0.72f * plan.upstreamLumaReduction), 0.24f, 0.78f);
    const float chromaHeadroom =
            (1.0f - 0.70f * plan.upstreamChromaReduction) * (1.0f - 0.48f * preTone);
    plan.chromaFraction = std::clamp(1.08f * chromaHeadroom, 0.34f, 1.08f);
    plan.lumaSigma = std::clamp(physicalLumaSigma * plan.lumaFraction, 0.0f, 0.15f);
    plan.chromaSigma = std::clamp(physicalChromaSigma * plan.chromaFraction, 0.0f, 0.35f);
    return plan;
}

}  // namespace bncam::spectra2
