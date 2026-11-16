#pragma once
// SF2-D05: debug-only quarter-size guided coarse luminance residual.
// Operates on IMMUTABLE demosaic Y, never on already denoised pixels.
// Fine and coarse coefficients cover different bands: fine sees original 3x3
// detail, coarse sees a binomial-filtered/downsampled scene representation.
// All output additions are neutral RGB deltas after existing chroma processing.
#include "SingleFrameGuidedLuma.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <vector>

namespace bncam::sf2 {

struct CoarseLumaCell final {
    float y = 0.0f;
    // Variance under independent input sites: diagnostic assumption, NOT a bound.
    float varianceIndependent = 0.0f;
    // Cauchy bound (sum |w| sigma)^2, valid even for unknown correlation.
    float varianceUpperBound = 0.0f;
    bool valid = false;
};

struct CoarseLumaMap final {
    int width = 0, height = 0;
    std::vector<CoarseLumaCell> samples;
    std::vector<float> correction;
    bool valid = false;
    std::size_t invalidCells = 0;
    std::size_t correctedCells = 0;
    float maxAbsCorrection = 0.0f;
};

inline constexpr int kCoarseScale = 4;
inline constexpr std::array<float, 4> kBinomial4{
    0.125f, 0.375f, 0.375f, 0.125f
};
inline float coarseClamp(float value,float lo,float hi) noexcept {
    return std::clamp(value,lo,hi);
}

// Same center-of-pixel 4x4 separable binomial used by the Vulkan mode-14 pass.
// Reads a full-resolution demosaic guide, not a post-chroma/fine output.
template<class Read,class Shape>
CoarseLumaCell downsampleCoarseCell(
        int cx,int cy,int width,int height,
        bncam::luma::Model noise,Read read,Shape shape) {
    CoarseLumaCell out{};
    if (!noise.valid() || width<=0 || height<=0 || cx<0 || cy<0) return out;
    double mean=0.0, independent=0.0, upperSigma=0.0;
    for(int dy=0;dy<4;++dy)for(int dx=0;dx<4;++dx) {
        const int x=std::clamp(cx*4+dx,0,width-1);
        const int y=std::clamp(cy*4+dy,0,height-1);
        const Pixel rgb=read(x,y);
        const float shapeRatio=shape(x,y);
        if(!bncam::chroma::finite(rgb) ||
           !(shapeRatio>0.0f) || !std::isfinite(shapeRatio)) return out;
        const double v=double(noise.varianceY)*shapeRatio*shapeRatio;
        const double pixelY=bncam::chroma::opponent(rgb)[0];
        if(!(v>1.0e-15) || !std::isfinite(v) || !std::isfinite(pixelY))return out;
        const double w=double(kBinomial4[static_cast<std::size_t>(dx)])*
                       double(kBinomial4[static_cast<std::size_t>(dy)]);
        mean+=w*pixelY;
        independent+=w*w*v;
        upperSigma+=w*std::sqrt(v);
    }
    const double upper=upperSigma*upperSigma;
    if(!std::isfinite(mean)||!std::isfinite(independent)||
       !std::isfinite(upper)||!(independent>1.0e-15)||upper<independent)
        return out;
    out.y=static_cast<float>(mean);
    out.varianceIndependent=static_cast<float>(independent);
    out.varianceUpperBound=static_cast<float>(upper);
    out.valid=std::isfinite(out.y) && std::isfinite(out.varianceIndependent) &&
              std::isfinite(out.varianceUpperBound) && out.varianceIndependent>0.0f;
    return out;
}

// Zero-DC bandpass in the coarse domain. Symmetric neighbours exactly preserve
// a flat field and (away from borders) affine illumination. Contrast weighting,
// oriented second-difference evidence and a sigma cap bound edge damage.
// The variance used for authority is the INDEPENDENCE proxy, not an invented
// measured covariance or a falsely claimed decorrelation factor of 1/16.
template<class Get>
float guidedCoarseResidual(int x,int y,int w,int h,bncam::luma::Model model,
                            Get get,float* authorityOut=nullptr) noexcept {
    if(authorityOut)*authorityOut=0.0f;
    if(!model.valid() || w<3 || h<3 || x<0 || y<0 || x>=w || y>=h)return 0.0f;
    const auto center=get(x,y);
    if(!center.valid || !std::isfinite(center.y) ||
       !(center.varianceIndependent>1e-15f) ||
       !(center.varianceUpperBound>=center.varianceIndependent) ||
       !std::isfinite(center.varianceUpperBound))return 0.0f;
    const float variance=center.varianceIndependent;
    const float sigma=std::sqrt(variance);
    const float radiusVariance=std::max(4.f*variance,1e-12f);
    float sum=0.f,total=0.f;
    for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx) {
        if(dx==0 && dy==0)continue;
        const auto other=get(std::clamp(x+dx,0,w-1),std::clamp(y+dy,0,h-1));
        if(!other.valid || !std::isfinite(other.y))return 0.0f;
        const float delta=other.y-center.y;
        // Symmetric binomial spatial weights; range reject likely scene edges.
        const float spatial=(dx==0||dy==0)?2.f:1.f;
        const float weight=spatial*std::exp(-(delta*delta)/radiusVariance);
        total+=weight;
        sum+=weight*other.y;
    }
    if(!(total>1e-10f) || !std::isfinite(total)||!std::isfinite(sum))return 0.0f;
    const float candidate=(sum/total)-center.y;
    // Opposite-neighbour structure evidence, on BOTH axes. Real folds,
    // broad transitions and low-contrast lines reduce coarse authority.
    const auto left=get(std::clamp(x-1,0,w-1),y);
    const auto right=get(std::clamp(x+1,0,w-1),y);
    const auto top=get(x,std::clamp(y-1,0,h-1));
    const auto bottom=get(x,std::clamp(y+1,0,h-1));
    if(!left.valid||!right.valid||!top.valid||!bottom.valid)return 0.0f;
    const float edge=std::max(std::abs(right.y-left.y),std::abs(bottom.y-top.y));
    const float curvature=std::max(
        std::abs(center.y-0.5f*(left.y+right.y)),
        std::abs(center.y-0.5f*(top.y+bottom.y)));
    const float edgeGate=std::clamp(1.0f-edge/(3.5f*sigma+1e-12f),0.0f,1.0f);
    const float curvatureGate=std::clamp(1.0f-curvature/(2.8f*sigma+1e-12f),0.0f,1.0f);
    // Cap 0.38: intentionally less aggressive than fine (0.78).
    const float authority=std::clamp(
        0.38f*model.confidence*edgeGate*curvatureGate,0.0f,0.38f);
    float correction=std::clamp(candidate*authority,-0.65f*sigma,0.65f*sigma);
    if(!std::isfinite(correction))correction=0.0f;
    if(authorityOut)*authorityOut=authority;
    return correction;
}

// Bilinear reconstruction centred at coarse pixel (4*cx+1.5). No grid seams.
// Input correction is a *coarse, antialiased bandpass only*, not a new tone curve.
template<class Get>
float upsampleCoarseResidual(int x,int y,int w,int h,Get get) noexcept {
    if(w<=0||h<=0||x<0||y<0)return 0.0f;
    const float gx=(static_cast<float>(x)-1.5f)/4.0f;
    const float gy=(static_cast<float>(y)-1.5f)/4.0f;
    const int ix=static_cast<int>(std::floor(gx));
    const int iy=static_cast<int>(std::floor(gy));
    const float tx=gx-static_cast<float>(ix);
    const float ty=gy-static_cast<float>(iy);
    const auto at=[&](int px,int py)->float {
        const float c=get(std::clamp(px,0,w-1),std::clamp(py,0,h-1));
        return std::isfinite(c)?c:0.0f;
    };
    const float a=at(ix,iy)*(1.f-tx)+at(ix+1,iy)*tx;
    const float b=at(ix,iy+1)*(1.f-tx)+at(ix+1,iy+1)*tx;
    return a*(1.f-ty)+b*ty;
}

// CPU fallback only. Never allocate a CPU copy on the successful resident
// Vulkan path; caller owns the preexisting immutable demosaic buffer.
template<class Read,class Shape>
CoarseLumaMap buildGuidedCoarse(
        int width,int height,bncam::luma::Model model,Read read,Shape shape) {
    CoarseLumaMap out{};
    if(!model.valid()||width<12||height<12)return out;
    out.width=(width+3)/4;out.height=(height+3)/4;
    const auto length=static_cast<std::size_t>(out.width)*out.height;
    if(length>10'000'000u)return out;
    out.samples.resize(length);
    out.correction.assign(length,0.0f);
    for(int y=0;y<out.height;++y)for(int x=0;x<out.width;++x) {
        const auto i=static_cast<std::size_t>(y)*out.width+x;
        out.samples[i]=downsampleCoarseCell(x,y,width,height,model,read,shape);
        if(!out.samples[i].valid)++out.invalidCells;
    }
    if(out.invalidCells>0)return out;
    auto get=[&](int x,int y){return out.samples[static_cast<std::size_t>(y)*out.width+x];};
    for(int y=0;y<out.height;++y)for(int x=0;x<out.width;++x) {
        const auto i=static_cast<std::size_t>(y)*out.width+x;
        const float d=guidedCoarseResidual(x,y,out.width,out.height,model,get);
        out.correction[i]=d;
        out.maxAbsCorrection=std::max(out.maxAbsCorrection,std::abs(d));
        if(d!=0.0f)++out.correctedCells;
    }
    out.valid=true;
    return out;
}

inline float coarseAtPixel(const CoarseLumaMap& coarse,int x,int y) noexcept {
    if(!coarse.valid || coarse.width<=0 || coarse.height<=0 ||
       coarse.correction.size()!=static_cast<std::size_t>(coarse.width)*coarse.height)
        return 0.0f;
    return upsampleCoarseResidual(x,y,coarse.width,coarse.height,
        [&](int cx,int cy)->float {
            return coarse.correction[static_cast<std::size_t>(cy)*coarse.width+cx];
        });
}

inline Pixel addCoarseResidual(Pixel finePixel,float correction) noexcept {
    if(!bncam::chroma::finite(finePixel)||!std::isfinite(correction))
        return finePixel;
    const Pixel out{finePixel[0]+correction,finePixel[1]+correction,finePixel[2]+correction};
    return bncam::chroma::finite(out)?out:finePixel;
}
} // namespace bncam::sf2
