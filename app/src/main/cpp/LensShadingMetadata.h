#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace bncam::lsc {
// Camera2 LensShadingMap.MINIMUM_GAIN_FACTOR is 1. There is no upper gain cap.
inline float safeGain(float value) noexcept {
    return std::isfinite(value) && value >= 1.0f ? value : 1.0f;
}
inline bool validShape(std::size_t count, std::uint32_t columns, std::uint32_t rows) noexcept {
    // Division also prevents dimension multiplication overflow and shader index overflow.
    return columns > 0 && rows > 0 && columns <= count / 4 / rows &&
           std::uint64_t(columns) * rows <= UINT32_MAX / 4;
}
}
