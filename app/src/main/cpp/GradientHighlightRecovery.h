#pragma once
#include "HighlightGamutProtectionV2.h"

namespace bncam::highlight {
inline Rgb cellChannelReliability(const std::array<float,4>& values, unsigned pattern) {
    std::array<float,4> q{};
    for(int i=0;i<4;++i) q[i]=float(std::lround((1-defaultRawSampleLoss(values[i]))*255.f))/255.f;
    const int red[4]={0,1,2,3}, blue[4]={3,2,1,0};
    const int r=red[std::min(pattern,3u)], b=blue[std::min(pattern,3u)];
    float green=0; for(int i=0;i<4;++i) if(i!=r && i!=b) green+=q[i]*.5f;
    return {q[r],green,q[b]};
}
template<class Read>
Rgb channelReliabilityAt(int x,int y,Read read) {
    const float cx=x*.5f-.25f, cy=y*.5f-.25f;
    const int ix=int(std::floor(cx)), iy=int(std::floor(cy));
    const auto wx=defaultRawConfidenceWeights(cx-ix), wy=defaultRawConfidenceWeights(cy-iy);
    Rgb q{};
    for(int j=0;j<4;++j) for(int i=0;i<4;++i) {
        const auto c=read(ix+i-1,iy+j-1); const float w=wx[i]*wy[j];
        for(int k=0;k<3;++k) q[k]+=w*c[k];
    }
    return q;
}
// Multiscale, edge-aware log-ratio extension. Only censored channels are estimated.
// The reliable guide supplies its measured gradients; ratios are low-frequency
// boundary conditions, with hue/guide edges vetoing transport across colour borders.
template<class ReadRgb,class ReadConfidence>
Rgb reconstructHighlight(int x,int y,const Rgb& center,const Rgb& q,
        const Rgb& gains,ReadRgb read,ReadConfidence reliability) {
    if(std::min({q.r,q.g,q.b})>.999999f) return center;
    int guide=1;
    for(int c:{0,2}) if(q[c]>q[guide]) guide=c;
    if(center[guide]<.02f) return center;
    const float neutralFloor=std::max({center.r*gains.r,center.g*gains.g,center.b*gains.b});
    if(q[guide]<=1e-6f) return {neutralFloor/gains.r,neutralFloor/gains.g,neutralFloor/gains.b};
    const int dx[8]={1,-1,0,0,1,1,-1,-1},dy[8]={0,0,1,-1,1,-1,1,-1};
    Rgb out=center;
    for(int c=0;c<3;++c) {
        if(c==guide || q[c]>.999999f) continue;
        float sum=0, weights=0;
        for(int radius:{2,8,32,128}) for(int d=0;d<8;++d) {
            const int nx=x+dx[d]*radius, ny=y+dy[d]*radius;
            const Rgb n=read(nx,ny), nq=reliability(nx,ny);
            if(n[guide]<.02f || n[c]<.01f) continue;
            const float edge=std::log2(std::max(.001f,n[guide]/center[guide]));
            float hue=0;
            for(int k=0;k<3;++k) if(k!=guide && q[k]>.75f && nq[k]>.75f) {
                const float a=center[k]/std::max(.001f,center[guide]);
                const float b=n[k]/std::max(.001f,n[guide]);
                hue=std::max(hue,std::abs(std::log2(std::max(.001f,a)/std::max(.001f,b))));
            }
            const float w=nq[c]*nq[guide]*std::exp(-1.5f*edge*edge-8.f*hue*hue);
            sum+=w*std::clamp(std::log2(n[c]/n[guide]),-4.f,4.f); weights+=w;
        }
        // With no compatible boundary, use only a neutral WB ratio. No fabricated texture.
        const float neutral=std::log2(std::max(.001f,gains[guide])/std::max(.001f,gains[c]));
        float other=0;for(int k=0;k<3;++k)if(k!=guide)other=std::max(other,q[k]);
        const float support=weights/(weights+.5f)*other;
        const float ratio=support*(sum/std::max(weights,1e-8f))+(1-support)*neutral;
        float estimate=center[guide]*std::exp2(ratio);
        const float e=estimate-center[c];
        estimate=center[c]+.5f*(e+std::sqrt(e*e+1e-6f));
        estimate=std::min(estimate,std::max(.02f,center[c])*4.f);
        out[c]=center[c]+(estimate-center[c])*(1-q[c]);
    }
    const float fullLoss=1-smoothstep(0.f,.65f,q[guide]);
    for(int c=0;c<3;++c) out[c]=out[c]*(1-fullLoss)+(neutralFloor/gains[c])*fullLoss;
    return out;
}
inline float chromaticReliability(Rgb q) {
    return std::max({std::min(q.r,q.g),std::min(q.r,q.b),std::min(q.g,q.b)});
}
}
