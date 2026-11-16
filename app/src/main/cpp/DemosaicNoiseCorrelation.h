#pragma once
// SF2-D02: compact read-only post-demosaic Y spatial noise contract.
// Only fixed Malvar/Bilinear admits a reliable linear kernel prediction.
// AMaZE and spatial Auto Hybrid are deliberately marked UNKNOWN (not rho=0).
#include "SpectraNoisePropagation.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <string>

namespace bncam::sf2 {
struct SpatialNoiseCorrelation {
    double rhoHorizontal=0, rhoVertical=0, rhoDiagonal=0;
    double confidence=0;
    bool known=false;
    const char* method="UNKNOWN_ADAPTIVE_KERNEL";
};
inline std::array<double,3> fixedKernelAt(int phase,int x,int y,bool malvar) {
    const auto s=[&](int xx,int yy){return xx==x&&yy==y?1.0:0.0;};
    const double c=s(0,0),h=s(-1,0)+s(1,0),v=s(0,-1)+s(0,1);
    const double h2=s(-2,0)+s(2,0),v2=s(0,-2)+s(0,2);
    const double d=s(-1,-1)+s(-1,1)+s(1,-1)+s(1,1);
    const double g=malvar?(4*c+2*(h+v)-h2-v2)/8:(h+v)/4;
    const double opp=malvar?(6*c+2*d-1.5*(h2+v2))/8:d/4;
    const double ch=malvar?(5*c+4*h-h2-d+.5*v2)/8:h/2;
    const double cv=malvar?(5*c+4*v-v2-d+.5*h2)/8:v/2;
    if(phase==0)return {c,g,opp};
    if(phase==3)return {opp,g,c};
    return phase==1?std::array<double,3>{ch,c,cv}:std::array<double,3>{cv,c,ch};
}
inline SpatialNoiseCorrelation predictSpatialCorrelation(
        const spectra2::NoiseState& finalized, const spectra2::NoiseState& demosaiced,
        spectra2::DemosaicModel model, bool autoHybrid) {
    if(autoHybrid || (model!=spectra2::DemosaicModel::Malvar2004 &&
                      model!=spectra2::DemosaicModel::Bilinear))
        return {}; // Unknown adaptive weights; never invent local confidence.
    const bool malvar=model==spectra2::DemosaicModel::Malvar2004;
    std::array<double,3> v{};
    for(int c=0;c<3;++c) {
        v[c]=finalized.covariance.at(c,c);
        if(!(v[c]>0) || !std::isfinite(v[c]))return {};
        for(int d=0;d<3;++d)
            if(c!=d && finalized.covariance.at(c,d)!=0)return {};
    }
    if(!(demosaiced.confidence>0 && demosaiced.confidence<=1))return {};
    const auto yKernel=[&](int phase,int x,int y){
        auto k=fixedKernelAt(phase,x,y,malvar);
        return .2126*k[0]+.7152*k[1]+.0722*k[2];
    };
    const auto covariance=[&](int dx,int dy){
        double sumCov=0,sumV0=0,sumV1=0;
        for(int phase=0;phase<4;++phase) {
            const int shifted=((phase/2+dy+16)&1)*2 + ((phase%2+dx+16)&1);
            for(int y=-4;y<=4;++y)for(int x=-4;x<=4;++x) {
                const int xx=(phase%2+x+16)&1,yy=(phase/2+y+16)&1;
                const int channel=xx==0&&yy==0?0:xx==1&&yy==1?2:1;
                const double a=yKernel(phase,x,y);
                const double b=yKernel(shifted,x-dx,y-dy);
                sumCov+=v[channel]*a*b;
                sumV0+=v[channel]*a*a;
                sumV1+=v[channel]*b*b;
            }
        }
        const double denominator=std::sqrt(sumV0*sumV1);
        return denominator>0?std::clamp(sumCov/denominator,-1.0,1.0):0.0;
    };
    return {covariance(1,0),covariance(0,1),covariance(1,1),
            demosaiced.confidence,true,
            malvar?"MALVAR_EXACT_FIXED_KERNEL_PHASE_AVG":"BILINEAR_EXACT_FIXED_KERNEL_PHASE_AVG"};
}
} // namespace bncam::sf2
