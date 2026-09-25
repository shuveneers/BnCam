#pragma once

#include "NeuralRawDenoisePolicy.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

namespace bncam::spectra::neural {

// Neural-local residual basis. This deliberately does not reuse the retired
// SpectraCfaOrthonormalSupport / classical SPECTRA correction machinery.
// It transforms only the Student-predicted residual [R,G1,G2,B].
struct NeuralResidualBasis4 {
    float common = 0.0f;
    float chromaRb = 0.0f;
    float chromaGreen = 0.0f;
    float greenSplit = 0.0f;
};

constexpr float kNeuralInvSqrt2 = 0.70710678118654752440f;

inline NeuralResidualBasis4 neuralResidualForward(
        const std::array<float, 4>& residual) noexcept {
    const float r = residual[0];
    const float g1 = residual[1];
    const float g2 = residual[2];
    const float b = residual[3];
    return {
        0.5f * (r + g1 + g2 + b),
        kNeuralInvSqrt2 * (r - b),
        0.5f * (r - g1 - g2 + b),
        kNeuralInvSqrt2 * (g1 - g2),
    };
}

inline std::array<float, 4> neuralResidualInverse(
        const NeuralResidualBasis4& basis) noexcept {
    return {
        0.5f * basis.common + kNeuralInvSqrt2 * basis.chromaRb + 0.5f * basis.chromaGreen,
        0.5f * basis.common - 0.5f * basis.chromaGreen + kNeuralInvSqrt2 * basis.greenSplit,
        0.5f * basis.common - 0.5f * basis.chromaGreen - kNeuralInvSqrt2 * basis.greenSplit,
        0.5f * basis.common - kNeuralInvSqrt2 * basis.chromaRb + 0.5f * basis.chromaGreen,
    };
}

inline std::array<float, 4> applyNeuralResidualComponentAuthorities(
        const std::array<float, 4>& residual,
        const NeuralDenoiseControls& controls) noexcept {
    NeuralResidualBasis4 basis = neuralResidualForward(residual);
    const float master = std::clamp(controls.noiseReduction, 0.0f, 1.0f);
    const float luma = std::clamp(controls.lumaNoise, 0.0f, 1.0f);
    const float chroma = std::clamp(controls.chromaNoise, 0.0f, 1.0f);
    basis.common *= master * luma;
    basis.chromaRb *= master * chroma;
    basis.chromaGreen *= master * chroma;
    basis.greenSplit *= master * chroma;
    return neuralResidualInverse(basis);
}

enum class NeuralCharacterPreset : std::uint8_t {
    Natural = 0,
    Clean = 1,
    Texture = 2,
    Night = 3,
};

// Character is never stored as hidden native state. Selecting a character
// resolves immediately to the same visible control vector used by Custom.
inline NeuralDenoiseControls neuralCharacterControls(
        NeuralCharacterPreset preset,
        bool enabled = true) noexcept {
    NeuralDenoiseControls out{};
    out.enabled = enabled;
    switch (preset) {
        case NeuralCharacterPreset::Clean:
            out.noiseReduction = 1.0f;
            out.lumaNoise = 0.775f;
            out.chromaNoise = 0.925f;
            out.detailProtection = 0.625f;
            out.lowFrequencyCleanup = 0.900f;
            out.adaptiveResponse = 1.0f;
            break;
        case NeuralCharacterPreset::Texture:
            out.noiseReduction = 1.0f;
            out.lumaNoise = 0.450f;
            out.chromaNoise = 0.700f;
            out.detailProtection = 0.825f;
            out.lowFrequencyCleanup = 0.675f;
            out.adaptiveResponse = 1.0f;
            break;
        case NeuralCharacterPreset::Night:
            out.noiseReduction = 1.0f;
            out.lumaNoise = 0.675f;
            out.chromaNoise = 0.950f;
            out.detailProtection = 0.575f;
            out.lowFrequencyCleanup = 0.960f;
            out.adaptiveResponse = 1.0f;
            break;
        case NeuralCharacterPreset::Natural:
        default:
            out.noiseReduction = 1.0f;
            out.lumaNoise = 0.600f;
            out.chromaNoise = 0.800f;
            out.detailProtection = 0.675f;
            out.lowFrequencyCleanup = 0.800f;
            out.adaptiveResponse = 1.0f;
            break;
    }
    return out;
}

inline NeuralDenoiseControls sanitizedNeuralControls(
        NeuralDenoiseControls controls) noexcept {
    if (controls.schemaVersion != kNeuralDenoiseControlsSchemaVersion) {
        return NeuralDenoiseControls{};
    }
    const auto unit = [](float value) noexcept {
        return std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 0.0f;
    };
    controls.noiseReduction = unit(controls.noiseReduction);
    controls.lumaNoise = unit(controls.lumaNoise);
    controls.chromaNoise = unit(controls.chromaNoise);
    controls.detailProtection = unit(controls.detailProtection);
    controls.lowFrequencyCleanup = unit(controls.lowFrequencyCleanup);
    controls.adaptiveResponse = unit(controls.adaptiveResponse);
    return controls;
}

} // namespace bncam::spectra::neural
