#pragma once

#include "NativeRenderQualityConfig.h"

#include <algorithm>
#include <cmath>

inline float bncamProfileContrastCurve(float value, float signedControl) {
    const float v = std::clamp(value, 0.0f, 1.0f);
    const float factor = std::exp2(std::clamp(signedControl, -1.0f, 1.0f) * 0.75f);
    if (std::abs(factor - 1.0f) < 1.0e-4f) return v;
    if (v < 0.5f) {
        return 0.5f * std::pow(std::max(0.0f, 2.0f * v), factor);
    }
    return 1.0f - 0.5f * std::pow(std::max(0.0f, 2.0f * (1.0f - v)), factor);
}

inline void bncamCompressToUnitGamutPreserveLuma(float& r, float& g, float& b) {
    if (!std::isfinite(r) || !std::isfinite(g) || !std::isfinite(b)) {
        r = g = b = 0.0f;
        return;
    }
    float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    if (!(y > 1.0e-8f)) { r = g = b = 0.0f; return; }
    if (y > 1.0f) { const float invY = 1.0f / y; r *= invY; g *= invY; b *= invY; y = 1.0f; }
    const float maximum = std::max({r, g, b});
    const float minimum = std::min({r, g, b});
    if (maximum <= 1.0f && minimum >= 0.0f) return;
    float authority = 1.0f;
    if (maximum > y + 1.0e-6f) authority = std::min(authority, (1.0f - y) / (maximum - y));
    if (minimum < y - 1.0e-6f) authority = std::min(authority, y / (y - minimum));
    authority = std::clamp(authority, 0.0f, 1.0f);
    r = std::clamp(y + (r - y) * authority, 0.0f, 1.0f);
    g = std::clamp(y + (g - y) * authority, 0.0f, 1.0f);
    b = std::clamp(y + (b - y) * authority, 0.0f, 1.0f);
}

inline void applyBncamProfileColorManagement(
        float& r,
        float& g,
        float& b,
        const NativeRenderQualityConfig& cfg,
        bool finalizeGamut = true
) {
    const float vibranceControl = std::clamp(cfg.profilePresenceVibrance, -1.0f, 1.0f);
    const float saturationControl = std::clamp(cfg.profileColorSaturation, -1.0f, 1.0f);
    const float contrastControl = std::clamp(cfg.profileColorContrast, -1.0f, 1.0f);
    if (std::abs(vibranceControl) < 1.0e-4f &&
        std::abs(saturationControl) < 1.0e-4f &&
        std::abs(contrastControl) < 1.0e-4f) {
        if (finalizeGamut) bncamCompressToUnitGamutPreserveLuma(r, g, b);
        return;
    }

    float y = std::max(0.0f, 0.2126f * r + 0.7152f * g + 0.0722f * b);

    if (std::abs(contrastControl) >= 1.0e-4f) {
        const float mappedY = bncamProfileContrastCurve(y, contrastControl);
        const float scale = y > 1.0e-6f ? mappedY / y : 1.0f;
        r *= scale;
        g *= scale;
        b *= scale;
        y = mappedY;
    }

    if (std::abs(vibranceControl) >= 1.0e-4f) {
        const float maximum = std::max({r, g, b});
        const float minimum = std::min({r, g, b});
        const float saturation = (maximum - minimum) / std::max(1.0e-4f, maximum);
        const float protection = std::clamp(1.0f - saturation, 0.0f, 1.0f);
        const float authority = vibranceControl >= 0.0f
                ? vibranceControl * (0.20f + 0.80f * protection)
                : vibranceControl * (0.55f + 0.45f * protection);
        const float factor = std::exp2(authority * 0.90f);
        r = y + (r - y) * factor;
        g = y + (g - y) * factor;
        b = y + (b - y) * factor;
    }

    if (std::abs(saturationControl) >= 1.0e-4f) {
        const float saturationFactor = std::exp2(saturationControl * 0.85f);
        r = y + (r - y) * saturationFactor;
        g = y + (g - y) * saturationFactor;
        b = y + (b - y) * saturationFactor;
    }

    if (finalizeGamut) bncamCompressToUnitGamutPreserveLuma(r, g, b);
}
