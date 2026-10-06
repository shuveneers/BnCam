#pragma once

// Reference/runtime boundary only. This is not a trained or activated backend.
// No noise, WB, CCM, exposure or classical demosaic inputs belong in this API.
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <vector>

namespace bncam::bnc {
enum class Cfa : std::uint32_t { Rggb=0, Grbg=1, Gbrg=2, Bggr=3 };
struct Geometry {
    std::uint32_t width, height, packedWidth, packedHeight;
    bool flipX, flipY;
    std::size_t packedPixels() const { return std::size_t(packedWidth)*packedHeight; }
};
inline Geometry geometry(std::uint32_t width, std::uint32_t height, Cfa pattern,
                         std::int32_t originX=0, std::int32_t originY=0,
                         std::int32_t cropX=0, std::int32_t cropY=0) {
    auto p=static_cast<std::uint32_t>(pattern);
    if (width<2 || height<2 || width>32768 || height>32768 || p>3)
        throw std::invalid_argument("BNC_NEURAL_CFA_GEOMETRY_INVALID");
    // Unsigned parity arithmetic also handles negative origins without signed overflow.
    auto x=(static_cast<std::uint32_t>(originX)+static_cast<std::uint32_t>(cropX))&1u;
    auto y=(static_cast<std::uint32_t>(originY)+static_cast<std::uint32_t>(cropY))&1u;
    return {width,height,(width+1)/2,(height+1)/2,bool((p&1u)^x),bool(((p>>1u)&1u)^y)};
}
inline std::uint32_t sourceCoordinate(std::uint32_t canonical, std::uint32_t length, bool flip) {
    auto padded=(length+1)/2*2;
    auto value=flip ? padded-1-canonical : canonical;
    return value<length ? value : length-2; // same-parity reflected odd border
}
inline std::vector<float> pack(const std::vector<float>& raw, const Geometry& g) {
    if(raw.size()!=std::size_t(g.width)*g.height)
        throw std::invalid_argument("BNC_NEURAL_INPUT_SIZE_INVALID");
    for(float v:raw) if(!std::isfinite(v) || v<0 || v>4)
        throw std::invalid_argument("BNC_NEURAL_INPUT_DOMAIN_INVALID");
    std::vector<float> out(g.packedPixels()*4);
    for(std::uint32_t y=0;y<g.packedHeight;++y) for(std::uint32_t x=0;x<g.packedWidth;++x)
        for(std::uint32_t c=0;c<4;++c) {
            auto sx=sourceCoordinate(2*x+(c&1),g.width,g.flipX);
            auto sy=sourceCoordinate(2*y+(c>>1),g.height,g.flipY);
            out[(std::size_t(y)*g.packedWidth+x)*4+c]=raw[std::size_t(sy)*g.width+sx]*.25f;
        }
    return out;
}
inline std::vector<float> merge(const std::vector<float>& raw, const std::vector<float>& missing,
                                const Geometry& g) {
    if(raw.size()!=std::size_t(g.width)*g.height || missing.size()!=g.packedPixels()*8)
        throw std::invalid_argument("BNC_NEURAL_PREDICTION_SIZE_INVALID");
    for(float v:missing) if(!std::isfinite(v)) throw std::invalid_argument("BNC_NEURAL_PREDICTION_INVALID");
    constexpr std::uint32_t measured[4]={0,1,1,2};
    constexpr std::uint32_t others[4][2]={{1,2},{0,2},{0,2},{0,1}};
    std::vector<float> rgb(raw.size()*3);
    for(std::uint32_t y=0;y<g.height;++y) for(std::uint32_t x=0;x<g.width;++x) {
        auto cx=g.flipX ? g.packedWidth*2-1-x : x;
        auto cy=g.flipY ? g.packedHeight*2-1-y : y;
        auto site=(cy&1)*2+(cx&1);
        auto src=(std::size_t(cy/2)*g.packedWidth+cx/2)*8+site*2;
        auto pixel=std::size_t(y)*g.width+x;
        rgb[pixel*3+measured[site]]=raw[pixel]; // exact original FP32, never FP16
        for(std::uint32_t k=0;k<2;++k) rgb[pixel*3+others[site][k]]=missing[src+k]*4.f;
    }
    return rgb;
}
} // namespace bncam::bnc
