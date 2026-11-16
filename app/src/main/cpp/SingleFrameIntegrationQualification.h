#pragma once
// SF2-D07: common deterministic CPU reference and explicit GPU qualification fixture.
// This header is not called in production. It does not modify the ISP.
#include "SingleFrameResidualConfidence.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>

namespace bncam::sf2::qualification {
using Pixel = bncam::sf2::Pixel;
inline constexpr int kWidth=128;
inline constexpr int kHeight=96;
inline constexpr float kGpuParityTolerance=1.0e-4f; // qualification threshold, not a calibrated guarantee

inline std::vector<Pixel> fixture() {
    std::vector<Pixel> out(kWidth*kHeight);
    for(int y=0;y<kHeight;++y)for(int x=0;x<kWidth;++x) {
        const float step=x>=kWidth/2?0.105f:0.f;
        const float thin=(x==kWidth/3||y==kHeight/3)?0.018f:0.f;
        const float grain=0.0038f*std::sin(0.91f*x+2.13f*y);
        const float coarse=0.0042f*std::sin(.19f*x)*std::cos(.21f*y);
        const float yValue=.055f+step+thin+grain+coarse
                +.00009f*(x+y);
        // Real opponent differences: qualification must never erase R-G or B-G.
        out[y*kWidth+x]=bncam::chroma::rgb(
                {yValue,0.011f+0.001f*std::sin(.06f*y),
                -0.007f+0.001f*std::cos(.05f*x)});
    }
    return out;
}
inline bncam::luma::Model noise() { return {.000064f,.9f}; }
inline bool isEnabledStage(bncam::luma::Stage stage) {
    return stage==bncam::luma::Stage::GUIDED_V2 ||
           stage==bncam::luma::Stage::GUIDED_V2_FINE_COARSE ||
           stage==bncam::luma::Stage::GUIDED_V2_CONFIDENCE;
}

// CPU oracle reads only immutable synthetic post-demosaic RGB.
// No second application of noise propagation, no GPU output reuse.
inline std::vector<Pixel> cpuReference(const std::vector<Pixel>& input,
                                       bncam::luma::Stage stage,
                                       bool kernelProxyAvailable=false) {
    if(input.size()!=static_cast<std::size_t>(kWidth*kHeight))return {};
    if(stage==bncam::luma::Stage::OFF)return input;
    if(!isEnabledStage(stage)&&stage!=bncam::luma::Stage::LEGACY_REFERENCE)return {};
    const auto model=noise();
    auto read=[&](int x,int y)->Pixel {
        return input[std::clamp(y,0,kHeight-1)*kWidth+std::clamp(x,0,kWidth-1)];
    };
    const auto shape=[](int,int)->float {return 1.0f;};
    const bool combined=stage==bncam::luma::Stage::GUIDED_V2_FINE_COARSE ||
                        stage==bncam::luma::Stage::GUIDED_V2_CONFIDENCE;
    const auto coarse=combined
        ? bncam::sf2::buildGuidedCoarse(kWidth,kHeight,model,read,shape)
        : bncam::sf2::CoarseLumaMap{};
    if(combined && !coarse.valid)return {};
    std::vector<Pixel> out(input.size());
    for(int y=0;y<kHeight;++y)for(int x=0;x<kWidth;++x) {
        const auto i=y*kWidth+x, post=input[i];
        const auto fine=bncam::sf2::guidedFine(x,y,model,post,read,shape,true);
        Pixel p=stage==bncam::luma::Stage::LEGACY_REFERENCE
                ? bncam::luma::filter(x,y,model,post,read,shape).pixel
                : fine.pixel;
        if(combined) {
            const float coarseDelta=bncam::sf2::coarseAtPixel(coarse,x,y);
            if(stage==bncam::luma::Stage::GUIDED_V2_CONFIDENCE) {
                const auto confidence=bncam::sf2::residualConfidenceAt(
                    x,y,model,fine.pixel[0]-post[0],coarseDelta,
                    kernelProxyAvailable,read,shape);
                p=bncam::sf2::applyResidualConfidence(
                    post,fine.pixel[0]-post[0],coarseDelta,confidence);
            } else p=bncam::sf2::addCoarseResidual(fine.pixel,coarseDelta);
        }
        out[i]=p;
    }
    return out;
}
struct DeltaSummary final {
    float maxAbsoluteRgb=0.f;
    float meanAbsoluteRgb=0.f;
    float maxOpponentDelta=0.f;
    std::size_t changedPixels=0;
    bool finite=true;
    bool valid=false;
};
inline DeltaSummary compare(const std::vector<Pixel>& expected,
                            const std::vector<float>& observed) {
    DeltaSummary out{};
    if(expected.empty()||observed.size()!=expected.size()*3u)return out;
    double sum=0;
    for(std::size_t i=0;i<expected.size();++i) {
        bool changed=false;
        Pixel rgb{observed[i*3],observed[i*3+1],observed[i*3+2]};
        if(!bncam::chroma::finite(expected[i])||!bncam::chroma::finite(rgb)){
            out.finite=false;return out;
        }
        for(int c=0;c<3;++c){
            const float d=std::abs(expected[i][c]-rgb[c]);
            out.maxAbsoluteRgb=std::max(out.maxAbsoluteRgb,d);
            sum+=d;
            changed=changed||(d>1.e-7f);
        }
        if(changed)++out.changedPixels;
        // Existing chroma/opponent differences must remain unchanged by stage.
        const Pixel originalOpponent=bncam::chroma::opponent(expected[i]);
        const Pixel outputOpponent=bncam::chroma::opponent(rgb);
        out.maxOpponentDelta=std::max(out.maxOpponentDelta,std::max(
                std::abs(outputOpponent[1]-originalOpponent[1]),
                std::abs(outputOpponent[2]-originalOpponent[2])));
    }
    out.meanAbsoluteRgb=static_cast<float>(sum/observed.size());
    out.valid=true;
    return out;
}
} // namespace bncam::sf2::qualification
