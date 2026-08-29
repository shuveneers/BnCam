#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>

namespace bncam {

/**
 * Identity luma contract for Camera2 YUV.
 *
 * Camera2 YUV is already vendor-ISP rendered. The neutral BnCam baseline therefore preserves
 * the received luma code values exactly instead of adding another toe/shoulder. Explicit
 * profile tone controls are applied separately after this neutral LUT.
 */
struct NeutralYuvToneMapper {
    static float map(float input) {
        return std::clamp(input, 0.0f, 1.0f);
    }

    static std::array<uint8_t, 256> buildLut() {
        std::array<uint8_t, 256> lut{};
        for (int i = 0; i < 256; ++i) {
            lut[static_cast<size_t>(i)] = static_cast<uint8_t>(i);
        }
        return lut;
    }
};

} // namespace bncam
