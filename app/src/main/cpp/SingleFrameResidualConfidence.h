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
    float coherence=0.f,edge=0.f,eraseRisk=0.f;
    const float delta=fineDelta+coarseDelta;
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
        const float lineEvidence=boundedCoherentEvidence(
                lineSum*inv,lineNoiseSum*(inv*inv));
        const float edgeEvidence=boundedCoherentEvidence(
                edgeSum*inv,edgeNoiseSum*(inv*inv));
        coherence=std::max(coherence,lineEvidence);
        edge=std::max(edge,edgeEvidence);
        // Protect only when the proposed delta ACTUALLY opposes a coherent
        // signed line/edge residual, rather than suppressing all noise detail.
        const float opposition=std::max(0.f,-delta*(lineSum*inv));
        const float signRisk=opposition/
                (std::abs(delta*(lineSum*inv))+.05f*variance+1.e-12f);
        const float signal=std::clamp(
                (std::max(lineEvidence,.5f*edgeEvidence)-.42f)/.58f,0.f,1.f);
        const float smooth=signal*signal*(3.f-2.f*signal);
        eraseRisk=std::max(eraseRisk,smooth*signRisk);
    }
    out.structureEvidence=coherence;
    out.edgeEvidence=edge;
    out.demosaicSupport=kernelProxyAvailable ? .92f : .78f;
    // Existing fine/coarse authority has already consumed model.confidence.
    // Only low-confidence predictions (<0.5) are further restricted here;
    // don't re-multiply near-1 confidence as if it were independent evidence.
    const float physicalSupport=std::clamp(2.f*model.confidence,0.f,1.f);

    const float normalized=std::abs(delta)/
              (1.6f*std::sqrt(variance)+1.e-12f);
    // Ordinary noise-scale corrections are *not* penalized. Large outliers
    // (>approximately 2.8 sigma) are soft-limited.
    const float beyond=std::max(0.f,normalized-1.75f);
    out.residualSupport=1.f/(1.f+.7f*beyond*beyond);

    const float opposition=std::max(0.f,-fineDelta*coarseDelta);
    const float support=std::abs(fineDelta*coarseDelta)+.10f*variance+1.e-12f;
    out.levelAgreement=1.f-.15f*opposition/support;

    // Unknown demosaic correlation increases the penalty ONLY when a
    // coherent, signed detail-erasing candidate was detected. It must not
    // globally suppress useful noise reduction in otherwise flat regions.
    const float riskStrength=1.70f-out.demosaicSupport; // .78 proxy; .92 unknown
    const float structureSupport=std::clamp(
                1.f-riskStrength*eraseRisk,.08f,1.f);
    out.weight=std::clamp(physicalSupport*structureSupport*
                         out.residualSupport*out.levelAgreement,0.f,1.f);
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
