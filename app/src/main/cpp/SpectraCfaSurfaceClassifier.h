#pragma once

#include <algorithm>
#include <cmath>

#include "SpectraNoiseProfileUncertainty.h"

namespace bncam::spectra2 {

struct CfaSurfaceClassification {
    float dispersionTextureEvidence = 0.0f;
    float coherentTextureEvidence = 0.0f;
    float directionalTextureEvidence = 0.0f;
    float textureConfidence = 0.0f;
    float flatConfidence = 1.0f;
    float authorityScale = 1.0f;
    float contextMixScale = 1.0f;
};

inline float surfaceSmoothstep(float edge0, float edge1, float value) {
    if (!std::isfinite(value) || !(edge1 > edge0)) return 0.0f;
    const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

// Classifies a same-CFA neighbourhood using signal statistics rather than raw
// gradient magnitude alone. `normalizedMad` is the robust VST median absolute
// deviation divided by the expected nine-sample Gaussian MAD (~0.586). Thus a
// truly flat sensor-noise-only region naturally sits around 1.0 even at high ISO.
inline CfaSurfaceClassification resolveCfaSurfaceClassification(
        float normalizedMad,
        float coherentStructureZ,
        float tensorConfidence,
        float combinedNoisePressure,
        float modelConfidence) {
    CfaSurfaceClassification out{};
    const float confidence = std::clamp(
            std::isfinite(modelConfidence) ? modelConfidence : 0.0f,
            0.0f, 1.0f);
    const float uncertainty = resolveNoiseProfileUncertaintyEnvelope(confidence);
    const float mad = std::max(0.0f,
            std::isfinite(normalizedMad) ? normalizedMad : 4.0f) / uncertainty;
    const float coherent = std::max(0.0f,
            std::isfinite(coherentStructureZ) ? coherentStructureZ : 4.0f) / uncertainty;
    const float tensor = std::clamp(
            std::isfinite(tensorConfidence) ? tensorConfidence : 0.0f,
            0.0f, 1.0f);
    const float noise = std::clamp(
            std::isfinite(combinedNoisePressure) ? combinedNoisePressure : 0.0f,
            0.0f, 1.0f);

    // Nine independent unit-Gaussian VST samples have a sample MAD centred near
    // 0.586.  Values well above that floor are evidence of real microtexture or
    // repeated structure rather than an isolated noise excursion.
    out.dispersionTextureEvidence = surfaceSmoothstep(1.55f, 2.60f, mad);
    out.coherentTextureEvidence = surfaceSmoothstep(1.45f, 3.80f, coherent);

    // A structure tensor is useful only when the same physical structure also
    // has noise-normalized support. This prevents random grain from becoming a
    // self-fulfilling directional "texture" classification.
    out.directionalTextureEvidence = std::clamp(
            tensor * surfaceSmoothstep(0.90f, 2.60f, coherent), 0.0f, 1.0f);

    out.textureConfidence = std::clamp(std::max({
            out.coherentTextureEvidence,
            0.90f * out.dispersionTextureEvidence,
            0.85f * out.directionalTextureEvidence
    }), 0.0f, 1.0f);

    out.flatConfidence = std::clamp(
            (1.0f - out.dispersionTextureEvidence) *
            (1.0f - 0.88f * out.coherentTextureEvidence) *
            (1.0f - 0.55f * out.directionalTextureEvidence),
            0.0f, 1.0f);

    // Flat/noisy surfaces may use more of the existing Wiener/context candidate;
    // true texture is pulled back. This is an authority policy only: it never
    // invents a new blur target, and No-Regret remains the final hard boundary.
    out.authorityScale = std::clamp(
            1.0f + (0.18f + 0.12f * noise) * out.flatConfidence -
            0.30f * out.textureConfidence,
            0.58f, 1.30f);
    out.contextMixScale = std::clamp(
            0.88f + 0.27f * out.flatConfidence -
            0.25f * out.textureConfidence,
            0.65f, 1.15f);
    return out;
}

} // namespace bncam::spectra2
