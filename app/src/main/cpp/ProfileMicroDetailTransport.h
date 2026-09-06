#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace bncam::profile_microdetail_transport {

// Phase 4 compatibility transport.
//
// The old profileDetailDetail ABI field is still consumed by retired publication/preview code as
// an unsigned Lightroom-style Detail value and clamped to [0, 1]. The production sharpness owner
// now needs TWO independent signed controls in that one ABI field: Phase-3 microdetail and Phase-4
// Legibility. Both are therefore quantized to 10-bit signed values and packed into one strictly
// negative float. Legacy unsigned consumers collapse every non-neutral packed value to 0, while
// the production owner recovers Detail and Legibility independently. Exact transport 0 remains the
// joint neutral value, preserving default/legacy bypass semantics.
//
// The packed payload is 20 bits (2 x 10), safely below float's exact integer precision. Code 512
// represents exact signed zero; negative and positive sides use 512 and 511 steps respectively.
inline float sanitizeUser(float value) noexcept {
    return std::clamp(std::isfinite(value) ? value : 0.0f, -1.0f, 1.0f);
}

inline std::uint32_t quantizeSigned10(float value) noexcept {
    const float user = sanitizeUser(value);
    if (user >= 0.0f) {
        return static_cast<std::uint32_t>(512 + std::lround(user * 511.0f));
    }
    return static_cast<std::uint32_t>(512 - std::lround((-user) * 512.0f));
}

inline float decodeSigned10(std::uint32_t code) noexcept {
    code = std::min<std::uint32_t>(code, 1023u);
    if (code >= 512u) {
        return std::clamp(static_cast<float>(code - 512u) / 511.0f, 0.0f, 1.0f);
    }
    return std::clamp((static_cast<float>(code) - 512.0f) / 512.0f, -1.0f, 0.0f);
}

inline float encodePair(float detail, float legibility) noexcept {
    const float safeDetail = sanitizeUser(detail);
    const float safeLegibility = sanitizeUser(legibility);
    if (std::abs(safeDetail) <= 1.0e-7f && std::abs(safeLegibility) <= 1.0e-7f) return 0.0f;

    constexpr std::uint32_t kAxisBits = 10u;
    constexpr std::uint32_t kAxisMask = (1u << kAxisBits) - 1u;
    constexpr float kTransportDenominator = 1048577.0f; // 2^20 + 1; keeps payload strictly > -1.
    const std::uint32_t detailCode = quantizeSigned10(safeDetail) & kAxisMask;
    const std::uint32_t legibilityCode = quantizeSigned10(safeLegibility) & kAxisMask;
    const std::uint32_t packed = detailCode | (legibilityCode << kAxisBits);
    return -static_cast<float>(packed + 1u) / kTransportDenominator;
}

inline bool decodePacked(float transportValue, std::uint32_t& packedOut) noexcept {
    if (!std::isfinite(transportValue) || transportValue >= -1.0e-7f) {
        packedOut = 0u;
        return false;
    }
    constexpr float kTransportDenominator = 1048577.0f;
    const float packedFloat = (-std::clamp(transportValue, -1.0f, 0.0f) * kTransportDenominator) - 1.0f;
    const long rounded = std::lround(std::clamp(packedFloat, 0.0f, 1048575.0f));
    packedOut = static_cast<std::uint32_t>(rounded);
    return true;
}

inline float decodeDetail(float transportValue) noexcept {
    std::uint32_t packed = 0u;
    if (!decodePacked(transportValue, packed)) return 0.0f;
    return decodeSigned10(packed & 1023u);
}

inline float decodeLegibility(float transportValue) noexcept {
    std::uint32_t packed = 0u;
    if (!decodePacked(transportValue, packed)) return 0.0f;
    return decodeSigned10((packed >> 10u) & 1023u);
}

// Backwards source-level helpers: callers that only need Detail keep working and automatically
// reserve the Legibility half of the payload at exact neutral.
inline float encode(float userValue) noexcept {
    return encodePair(userValue, 0.0f);
}

inline float decode(float transportValue) noexcept {
    return decodeDetail(transportValue);
}

} // namespace bncam::profile_microdetail_transport
