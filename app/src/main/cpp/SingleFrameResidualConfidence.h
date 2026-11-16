#pragma once
// SF2-D06: deterministic, read-only, per-pixel confidence for applying the
// EXISTING Guided Fine+Coarse candidate. No new physical S/O, no trained model,
// no sensor noise re-estimation and no sharpening/tone/colour modification.
// Kernel-only demosaic proxy is NOT measured spatial correlation: the only use
// of that metadata here is a conservative provenance-dependent confidence cap.
#include "SingleFrameGuidedCoarse.h"
#include <algorithm>
#include <array>
#include <cmath>

namespace bncam::sf2 {

struct ResidualConfidence final {
    float weight = 0.0f;            // 0..1, applied ONCE to fine+coarse Y
    float structureEvidence = 0.0f; // coherent oriented second differences
    float edgeEvidence = 0.0f;      // coherent, parallel-smoothed signed edges
    float residualSupport = 0.0f;   // correction vs physical Y sigma
    float levelAgreement = 0.0f;    // fine/coarse opposition, 1 if compatible
    float demosaicSupport = 0.0f;   // kernel-only proxy? .92 : .78 (heuristics)
    bool valid = false;
};

inline float boundedCoherentEvidence(float mean,float varianceOfMean) noexcept {
    if(!std::isfinite(mean)||!(varianceOfMean>0.f)||
       !std::isfinite(varianceOfMean))return 0.f;
    const float sq=mean*mean;
    if(!std::isfinite(sq))return 0.f;
    // Noise-bias subtraction: random residual energy does not automatically
    // qualify as structure. Not a calibrated posterior probability.
    return std::clamp(std::max(0.f,sq-varianceOfMean)/
                  (sq+varianceOfMean+1.e-12f),0.f,1.f);
}

// Four orientations × three parallel stencils; the evidence explicitly uses
// structure COHERENCE and edge CONSISTENCY, not gradient magnitude alone.
// All input Y values are read from the immutable demosaic frame.
template<class Read,class Shape>
ResidualConfidence residualConfidenceAt(
        int x,int y,bncam::luma::Model model,
        float fineDelta,float coarseDelta,
        bool kernelProxyAvailable,Read read,Shape shape) {
    ResidualConfidence out{};
    if(!model.valid() || !std::isfinite(fineDelta) ||
       !std::isfinite(coarseDelta))return out;
    const Pixel centerRgb=read(x,y);
    const float centerShape=shape(x,y);
    if(!bncam::chroma::finite(centerRgb) || !(centerShape>0.f) ||
       !std::isfinite(centerShape))return out;
    const float variance=model.varianceY*centerShape*centerShape;
    if(!(variance>1.e-12f) || !std::isfinite(variance))return out;
    constexpr int dx[4]={1,0,1,1};
    constexpr int dy[4]={0,1,1,-1};
    float coherence=0.f,edge=0.f;
    for(int dir=0;dir<4;++dir) {
        float lineSum=0.f,edgeSum=0.f;
        float lineNoiseSum=0.f,edgeNoiseSum=0.f;
        for(int k=-1;k<=1;++k) {
            const int px=x-k*dy[dir],py=y+k*dx[dir];
            const int ax=px-dx[dir],ay=py-dy[dir];
            const int bx=px+dx[dir],by=py+dy[dir];
            const Pixel cRgb=read(px,py),aRgb=read(ax,ay),bRgb=read(bx,by);
            const float sc=shape(px,py),sa=shape(ax,ay),sb=shape(bx,by);
            if(!bncam::chroma::finite(cRgb)||!bncam::chroma::finite(aRgb)||
               !bncam::chroma::finite(bRgb)||!std::isfinite(sc)||!std::isfinite(sa)||
               !std::isfinite(sb)||!(sc>0.f&&sa>0.f&&sb>0.f))return out;
            const float c=bncam::chroma::opponent(cRgb)[0];
            const float a=bncam::chroma::opponent(aRgb)[0];
            const float b=bncam::chroma::opponent(bRgb)[0];
            const float vc=model.varianceY*sc*sc;
            const float va=model.varianceY*sa*sa;
            const float vb=model.varianceY*sb*sb;
            if(!std::isfinite(vc)||!std::isfinite(va)||!std::isfinite(vb)||
               !(vc>0.f&&va>0.f&&vb>0.f))return out;
            lineSum+=c-.5f*(a+b);
            edgeSum+=.5f*(b-a);
            lineNoiseSum+=vc+.25f*(va+vb);
            edgeNoiseSum+=.25f*(va+vb);
        }
        // 3 parallel samples. The noise-on-average is a proxy under
        // independence, NOT measured demosaic correlation. Unknown correlation
        // is handled separately through conservative demosaicSupport.
        const float inv=1.f/3.f;
        coherence=std::max(coherence,
              boundedCoherentEvidence(lineSum*inv,lineNoiseSum*(inv*inv)));
        edge=std::max(edge,
              boundedCoherentEvidence(edgeSum*inv,edgeNoiseSum*(inv*inv)));
    }
    out.structureEvidence=coherence;
    out.edgeEvidence=edge;
    out.demosaicSupport=kernelProxyAvailable ? .92f : .78f;
    // Existing fine/coarse authority has already consumed model.confidence.
    // Only low-confidence predictions (<0.5) are further restricted here;
    // don't re-multiply near-1 confidence as if it were independent evidence.
    const float physicalSupport=std::clamp(2.f*model.confidence,0.f,1.f);

    const float delta=fineDelta+coarseDelta;
    const float normalized=std::abs(delta)/
              (1.6f*std::sqrt(variance)+1.e-12f);
    const float beyond=std::max(0.f,normalized-1.f);
    out.residualSupport=1.f/(1.f+.7f*beyond*beyond);

    const float opposition=std::max(0.f,-fineDelta*coarseDelta);
    const float support=std::abs(fineDelta*coarseDelta)+.10f*variance+1.e-12f;
    // Continuous as either candidate approaches zero; conflicting scales
    // reduce (never *increase*) application confidence.
    out.levelAgreement=1.f-.50f*opposition/support;

    const float structureSupport=std::clamp(
        1.f-.94f*std::max(out.structureEvidence,out.edgeEvidence),.06f,1.f);
    out.weight=std::clamp(physicalSupport*out.demosaicSupport*
                         structureSupport*out.residualSupport*
                         out.levelAgreement,0.f,1.f);
    if(!std::isfinite(out.weight))return ResidualConfidence{};
    out.valid=true;
    return out;
}

// Neutral, exact baseline bypass on invalid provenance or non-finite candidate.
inline Pixel applyResidualConfidence(Pixel postChroma,
                                     float fineDelta,float coarseDelta,
                                     const ResidualConfidence& confidence) noexcept {
    if(!bncam::chroma::finite(postChroma)||!confidence.valid ||
       !(confidence.weight>0.f)||!(confidence.weight<=1.f)||
       !std::isfinite(fineDelta)||!std::isfinite(coarseDelta))return postChroma;
    const float d=confidence.weight*(fineDelta+coarseDelta);
    if(!std::isfinite(d))return postChroma;
    const Pixel out{postChroma[0]+d,postChroma[1]+d,postChroma[2]+d};
    return bncam::chroma::finite(out)?out:postChroma;
}
} // namespace bncam::sf2
