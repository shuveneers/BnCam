#pragma once
// SF2-D04: finite, bounded, post-chroma additive camera-RGB Y correction.
// Immutable demosaic samples provide guidance; chroma is not filtered twice.
// CPU oracle for Vulkan Guided V2 fine. All values in normalized linear RGB.
// This model is a locally bounded heuristic, not a proof of correct treatment of
// spatially correlated AMaZE/Hybrid noise; device/RAW scene validation is required.
// Phase 4 is explicitly debug-only and OFF by default.
#include "PhysicalLumaDenoise.h"
#include <algorithm>
#include <array>
#include <cmath>

namespace bncam::sf2 {
using Pixel = bncam::luma::Pixel;
struct GuidedResult {
    Pixel pixel{};
    float fineAuthority=0,coarseAuthority=0,structure=0,pressure=0;
};
template<class Read,class Shape>
GuidedResult guidedFine(int x,int y,bncam::luma::Model m,Pixel postChroma,
                        Read read,Shape shape,bool gate=true) {
    if(!m.valid()||!bncam::chroma::finite(postChroma))return {postChroma};
    const auto centreRgb=read(x,y);
    if(!bncam::chroma::finite(centreRgb))return {postChroma};
    const float centre=bncam::chroma::opponent(centreRgb)[0];
    const float sigmaShape=shape(x,y);
    const float v=m.varianceY*sigmaShape*sigmaShape;
    if(!(v>1.e-12f)||!std::isfinite(v)||!std::isfinite(centre))return {postChroma};
    const float physical=m.confidence*v/(v+.001f*centre*centre);
    // Small 3x3 guided local regression: scene variance vs modelled noise.
    float total=0,sum=0,sum2=0,noise=0;
    for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx) {
        const Pixel sample=read(x+dx,y+dy);
        const float s=shape(x+dx,y+dy);
        if(!bncam::chroma::finite(sample)||!(s>0)||!std::isfinite(s))return {postChroma};
        const float p=bncam::chroma::opponent(sample)[0];
        const float dist=float(dx*dx+dy*dy);
        const float diff=p-centre;
        const float w=std::exp(-diff*diff/(8.f*v+1.e-12f))/(1.f+.4f*dist);
        total+=w;sum+=w*p;sum2+=w*p*p;noise+=w*m.varianceY*s*s;
    }
    if(!(total>0)||!std::isfinite(total))return {postChroma};
    const float mean=sum/total,expected=noise/total;
    const float sceneVariance=std::max(0.f,sum2/total-mean*mean-.8f*expected);
    const float wiener=v/(v+2.f*sceneVariance+1.e-12f);
    // Coherent second differences discriminate thin lines from isolated noise.
    float structure=0;
    if(gate)for(int axis=0;axis<2;++axis) {
        float residualMean=0,meanNoise=0;
        for(int k=-1;k<=1;++k) {
            const int px=x+(axis==0?0:k),py=y+(axis==0?k:0);
            const int dx=axis==0?1:0,dy=axis==0?0:1;
            const float a=bncam::chroma::opponent(read(px-dx,py-dy))[0];
            const float b=bncam::chroma::opponent(read(px+dx,py+dy))[0];
            const float c=bncam::chroma::opponent(read(px,py))[0];
            const float sa=shape(px-dx,py-dy),sb=shape(px+dx,py+dy),sc=shape(px,py);
            if(!std::isfinite(a)||!std::isfinite(b)||!std::isfinite(c)||
               !(sa>0&&sb>0&&sc>0))return {postChroma};
            residualMean+=c-.5f*(a+b);
            meanNoise+=m.varianceY*(sc*sc+.25f*(sa*sa+sb*sb));
        }
        residualMean/=3.f;
        const float meanVariance=meanNoise/9.f;
        const float evidence=std::max(0.f,residualMean*residualMean-meanVariance)/
                (residualMean*residualMean+meanVariance+1.e-12f);
        structure=std::max(structure,evidence);
    }
    const float gateFactor=gate?1.f-.98f*structure:1.f;
    const float authority=std::clamp(.78f*physical*wiener*gateFactor,0.f,.78f);
    const float correction=authority*(mean-centre);
    const Pixel result{postChroma[0]+correction,postChroma[1]+correction,
                       postChroma[2]+correction};
    if(!bncam::chroma::finite(result))return {postChroma};
    return {result,authority,0.f,structure,physical};
}
} // namespace bncam::sf2
