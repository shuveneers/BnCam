#pragma once

#include "NativeRenderQualityConfig.h"

#include <algorithm>
#include <cmath>
#include <cstdint>

inline float bncamProfileContrastCurve(float value, float signedControl) {
    const float v = std::clamp(value, 0.0f, 1.0f);
    const float factor = std::exp2(std::clamp(signedControl, -1.0f, 1.0f) * 0.75f);
    if (std::abs(factor - 1.0f) < 1.0e-4f) return v;
    if (v < 0.5f) {
        return 0.5f * std::pow(std::max(0.0f, 2.0f * v), factor);
    }
    return 1.0f - 0.5f * std::pow(std::max(0.0f, 2.0f * (1.0f - v)), factor);
}

inline bool bncamProfileCreativeCarrierEncoded(float value) {
    return std::isfinite(value) && value <= -1.5f;
}

inline std::uint32_t bncamProfileCreativePayload(float value) {
    if (!bncamProfileCreativeCarrierEncoded(value)) return 0u;
    const float payload = std::clamp(std::round(-value - 2.0f), 0.0f, 8388607.0f);
    return static_cast<std::uint32_t>(payload);
}

inline float bncamProfileDecodeSigned8(std::uint32_t code) {
    const int signedCode = static_cast<int>(code & 0xffu) - 128;
    return signedCode >= 0
            ? std::clamp(static_cast<float>(signedCode) / 127.0f, 0.0f, 1.0f)
            : std::clamp(static_cast<float>(signedCode) / 128.0f, -1.0f, 0.0f);
}

inline float bncamDecodedProfileSaturation(float carrier) {
    if (!bncamProfileCreativeCarrierEncoded(carrier)) {
        return std::clamp(std::isfinite(carrier) ? carrier : 0.0f, -1.0f, 1.0f);
    }
    return bncamProfileDecodeSigned8(bncamProfileCreativePayload(carrier));
}

inline float bncamDecodedProfileColorRecovery(float carrier) {
    if (!bncamProfileCreativeCarrierEncoded(carrier)) return 0.0f;
    return bncamProfileDecodeSigned8((bncamProfileCreativePayload(carrier) >> 15u) & 0xffu);
}

inline float bncamSmoothstep(float edge0, float edge1, float value) {
    if (!(edge1 > edge0)) return value < edge0 ? 0.0f : 1.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
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
    const float saturationControl = bncamDecodedProfileSaturation(cfg.profileColorSaturation);
    const float colorRecoveryControl = bncamDecodedProfileColorRecovery(cfg.profileColorSaturation);
    const float contrastControl = std::clamp(cfg.profileColorContrast, -1.0f, 1.0f);
    if (std::abs(vibranceControl) < 1.0e-4f &&
        std::abs(saturationControl) < 1.0e-4f &&
        std::abs(colorRecoveryControl) < 1.0e-4f &&
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

    if (std::abs(colorRecoveryControl) >= 1.0e-4f) {
        const float maximum = std::max({r, g, b});
        const float minimum = std::min({r, g, b});
        const float chroma = std::max(0.0f, maximum - minimum);
        const float saturation = std::clamp(chroma / std::max(1.0e-4f, std::abs(maximum)), 0.0f, 1.0f);
        const float evidence = bncamSmoothstep(0.012f, 0.055f, chroma);
        const float mutedHeadroom = 1.0f - bncamSmoothstep(0.40f, 0.78f, saturation);
        const float shadowWindow = bncamSmoothstep(0.025f, 0.12f, y);
        const float highlightWindow = 1.0f - bncamSmoothstep(0.82f, 0.98f, y);
        const float authority = colorRecoveryControl * evidence * mutedHeadroom * shadowWindow * highlightWindow;
        const float factor = std::exp2(authority * (colorRecoveryControl >= 0.0f ? 0.48f : 0.36f));
        r = y + (r - y) * factor;
        g = y + (g - y) * factor;
        b = y + (b - y) * factor;
    }

    // Pop intentionally remains Vulkan/GPU-only. CPU YUV fallback decodes the carrier so it
    // never misreads Pop/Color Recovery as -100% Saturation, but it does not duplicate Pop.
    if (finalizeGamut) bncamCompressToUnitGamutPreserveLuma(r, g, b);
}
