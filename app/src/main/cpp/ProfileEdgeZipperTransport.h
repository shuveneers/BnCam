#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace bncam::profile_edge_zipper_transport {
inline float finiteClamp(float v, float lo, float hi, float fallback=0.0f) noexcept {
    return std::clamp(std::isfinite(v) ? v : fallback, lo, hi);
}
inline std::uint32_t quantizeSigned10(float v) noexcept {
    v = finiteClamp(v, -1.0f, 1.0f);
    return v >= 0.0f ? static_cast<std::uint32_t>(512 + std::lround(v * 511.0f))
                     : static_cast<std::uint32_t>(512 - std::lround((-v) * 512.0f));
}
inline float decodeSigned10(std::uint32_t c) noexcept {
    c=std::min<std::uint32_t>(c,1023u);
    return c>=512u ? std::clamp(float(c-512u)/511.0f,0.0f,1.0f)
                   : std::clamp((float(c)-512.0f)/512.0f,-1.0f,0.0f);
}
inline std::uint32_t quantizeUnsigned10(float v) noexcept {
    return static_cast<std::uint32_t>(std::lround(finiteClamp(v,0.0f,1.0f)*1023.0f));
}
inline float decodeUnsigned10(std::uint32_t c) noexcept {
    return float(std::min<std::uint32_t>(c,1023u))/1023.0f;
}
inline bool decodePacked(float v, std::uint32_t& out) noexcept {
    if (!std::isfinite(v) || v >= -1.0e-7f) { out=0u; return false; }
    constexpr float D=1048577.0f;
    out=static_cast<std::uint32_t>(std::lround(std::clamp((-std::clamp(v,-1.0f,0.0f)*D)-1.0f,0.0f,1048575.0f)));
    return true;
}
inline float encodePair(float edge,float zipper) noexcept {
    edge=finiteClamp(edge,-1.0f,1.0f); zipper=finiteClamp(zipper,0.0f,1.0f);
    if(std::abs(edge)<=1e-7f && zipper<=1e-7f) return 0.0f;
    std::uint32_t p=(quantizeSigned10(edge)&1023u)|((quantizeUnsigned10(zipper)&1023u)<<10u);
    return -float(p+1u)/1048577.0f;
}
inline float decodeEdge(float v) noexcept { std::uint32_t p=0u; return decodePacked(v,p)?decodeSigned10(p&1023u):0.0f; }
inline float decodeAntiZipper(float v) noexcept { std::uint32_t p=0u; return decodePacked(v,p)?decodeUnsigned10((p>>10u)&1023u):0.0f; }
}
