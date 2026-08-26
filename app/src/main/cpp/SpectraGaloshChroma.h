#pragma once

#include <algorithm>
#include <array>
#include <cmath>

namespace bncam::spectra2 {

// Clean-room numerical contract for the GALOSH-inspired Spectra Chroma V2
// Bayer core.  The production implementation is Vulkan; these helpers keep
// transform/inverse and guide-weight behaviour unit-testable on the host.

struct GaloshOpponent444 {
    float y = 0.0f;
    float rMinusY = 0.0f;
    float bMinusY = 0.0f;
};

inline GaloshOpponent444 galoshOpponent444(float r, float g, float b) {
    const float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    return {y, r - y, b - y};
}

inline std::array<float, 3> galoshOpponent444ToRgb(const GaloshOpponent444& c) {
    const float r = c.y + c.rMinusY;
    const float b = c.y + c.bMinusY;
    const float g = (c.y - 0.2126f * r - 0.0722f * b) / 0.7152f;
    return {r, g, b};
}

inline float galoshBt709Luma(const std::array<float, 3>& rgb) {
    return 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2];
}

struct GaloshWhtBlock {
    float luma = 0.0f;
    float c1 = 0.0f;
    float c2 = 0.0f;
    float c3 = 0.0f;
};

inline GaloshWhtBlock galoshWht2x2(float p00, float p01, float p10, float p11) {
    return {
        0.5f * (p00 + p01 + p10 + p11),
        0.5f * (p00 - p01 + p10 - p11),
        0.5f * (p00 + p01 - p10 - p11),
        0.5f * (p00 - p01 - p10 + p11)
    };
}

inline std::array<float, 4> galoshInverseWht2x2(const GaloshWhtBlock& b) {
    return {
        0.5f * (b.luma + b.c1 + b.c2 + b.c3),
        0.5f * (b.luma - b.c1 + b.c2 - b.c3),
        0.5f * (b.luma + b.c1 - b.c2 - b.c3),
        0.5f * (b.luma - b.c1 - b.c2 + b.c3)
    };
}

inline float galoshLumaGuideWeight(
        float centerLuma,
        float neighbourLuma,
        float centerLumaVariance,
        float neighbourLumaVariance,
        float modelConfidence,
        float bandwidthSigma = 3.0f) {
    if (!std::isfinite(centerLuma) || !std::isfinite(neighbourLuma) ||
        !std::isfinite(centerLumaVariance) || !std::isfinite(neighbourLumaVariance) ||
        !(bandwidthSigma > 0.0f)) return 0.0f;
    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f, 0.0f, 1.0f);
    const float uncertainty = 1.0f + 0.30f * (1.0f - confidence);
    const float sigma = std::sqrt(std::max(
            1.0e-12f,
            std::max(0.0f, centerLumaVariance) + std::max(0.0f, neighbourLumaVariance))) * uncertainty;
    const float z = (neighbourLuma - centerLuma) / std::max(1.0e-7f, sigma);
    const float u = z / bandwidthSigma;
    return std::clamp(std::exp(-0.5f * u * u), 0.0f, 1.0f);
}

inline float galoshLumaVarianceFromIndependentSensels(
        float v00, float v01, float v10, float v11) {
    return 0.25f * (std::max(0.0f, v00) + std::max(0.0f, v01) +
                    std::max(0.0f, v10) + std::max(0.0f, v11));
}

} // namespace bncam::spectra2
