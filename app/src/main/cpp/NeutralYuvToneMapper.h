#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

namespace bncam {

/** Neutral luma-only normalization for YUV that has already been tone-mapped by the HAL. */
struct NeutralYuvToneMapper {
    static float map(float input) {
        const float x = std::clamp(input, 0.0f, 1.0f);

        // Keep the middle of the curve nearly identity while moving hard code-value endpoints
        // away from JPEG black/white clipping.
        float value = 0.008f + 0.984f * x;

        // A small, smooth toe lift. It reaches zero by 25% luma and cannot create a kink.
        if (x < 0.25f) {
            const float t = 1.0f - x / 0.25f;
            value += 0.012f * t * t;
        }

        // Soft shoulder for already-rendered ISP highlights. Derivative remains positive.
        if (x > 0.82f) {
            const float t = (x - 0.82f) / 0.18f;
            value -= 0.025f * t * t;
        }
        return std::clamp(value, 0.0f, 1.0f);
    }

    static std::array<uint8_t, 256> buildLut() {
        std::array<uint8_t, 256> lut{};
        uint8_t previous = 0;
        for (int i = 0; i < 256; ++i) {
            const auto mapped = static_cast<int>(std::lround(map(i / 255.0f) * 255.0f));
            lut[static_cast<size_t>(i)] = static_cast<uint8_t>(std::max(mapped, static_cast<int>(previous)));
            previous = lut[static_cast<size_t>(i)];
        }
        return lut;
    }
};

} // namespace bncam
