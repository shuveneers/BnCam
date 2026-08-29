#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace bncam::color {

inline const std::array<std::uint8_t, 4097>& srgbByteLut() {
    alignas(64) static const std::array<std::uint8_t, 4097> values = [] {
        std::array<std::uint8_t, 4097> lut{};
        for (std::size_t i = 0; i < lut.size(); ++i) {
            const float v = static_cast<float>(i) / 4096.0f;
            const float encoded = v <= 0.0031308f
                    ? 12.92f * v
                    : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
            lut[i] = static_cast<std::uint8_t>(std::round(encoded * 255.0f));
        }
        return lut;
    }();
    return values;
}

inline std::uint8_t quantizeSrgbByte(float value) {
    const float v = std::clamp(value, 0.0f, 1.0f);
    const int index = static_cast<int>(v * 4096.0f + 0.5f);
    return srgbByteLut()[static_cast<std::size_t>(index)];
}

// The Vulkan resident shader coalesces BGR bytes into 32-bit words; rows are padded to 4 bytes.
inline constexpr std::size_t packedBgr8RowStride(std::size_t width) {
    return ((width * 3u + 3u) / 4u) * 4u;
}

} // namespace bncam::color
