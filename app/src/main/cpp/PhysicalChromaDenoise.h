#pragma once
#include "SpectraNoisePropagation.h"
#include "PhysicalNoiseAuthority.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

namespace bncam::chroma {
// No Spectra/demosaic/ISO switch belongs in this contract. Covariance is already
// propagated through the selected reconstruction and actual RAW exposure gains.
struct Model {
    float y = 0, rg = 0, bg = 0, cross = 0;
    // Optional exact WB/CCM analysis domain. Identity is the synthetic/oracle default.
    std::array<float,9> transform{1,0,0,0,1,0,0,0,1};
    std::array<float,9> inverse{1,0,0,0,1,0,0,0,1};
    bool colorDomain = false;
    bool valid() const {
        return std::isfinite(y) && std::isfinite(rg) && std::isfinite(bg) &&
               std::isfinite(cross) && y > 0 && rg > 0 && bg > 0 &&
               std::abs(cross) <= (std::sqrt(rg) * std::sqrt(bg)) * 1.00001f;
    }
};
inline Model fromNoiseState(const spectra2::NoiseState& n, bool physicalAvailable) {
    if (!physicalAvailable || !(n.confidence > 0)) return {};
    Model m{float(n.varianceY), float(n.varianceRG), float(n.varianceBG), float(n.covarianceRgBg)};
    return m.valid() ? m : Model{};
}
using Pixel = std::array<float, 3>;
inline Pixel transformPixel(const std::array<float,9>& t, Pixel p) {
    return {t[0]*p[0]+t[1]*p[1]+t[2]*p[2],t[3]*p[0]+t[4]*p[1]+t[5]*p[2],
            t[6]*p[0]+t[7]*p[1]+t[8]*p[2]};
}
inline Model inColorDomain(Model original, const spectra2::NoiseState& input,
                           const std::array<float,9>& t, float varianceScale) {
    if(!original.valid()) return original;
    spectra2::Covariance3 tm; for(int i=0;i<9;++i) tm.values[i]=t[i];
    const double det=spectra2::determinant(tm);
    if(!std::isfinite(det)||std::abs(det)<1e-6) return original;
    auto propagated=spectra2::propagateColourMatrix(input,tm.values,"CHROMA_ANALYSIS_WB_CCM");
    auto out=fromNoiseState(propagated,true);
    if(!out.valid()) return original;
    out.y*=varianceScale;out.rg*=varianceScale;out.bg*=varianceScale;out.cross*=varianceScale;
    if(!out.valid())return original;
    out.transform=t;out.colorDomain=true;
    out.inverse={float((t[4]*t[8]-t[5]*t[7])/det),float((t[2]*t[7]-t[1]*t[8])/det),float((t[1]*t[5]-t[2]*t[4])/det),
        float((t[5]*t[6]-t[3]*t[8])/det),float((t[0]*t[8]-t[2]*t[6])/det),float((t[2]*t[3]-t[0]*t[5])/det),
        float((t[3]*t[7]-t[4]*t[6])/det),float((t[1]*t[6]-t[0]*t[7])/det),float((t[0]*t[4]-t[1]*t[3])/det)};
    return out;
}
inline Pixel opponent(Pixel p) {
    return {0.2126f*p[0] + 0.7152f*p[1] + 0.0722f*p[2], p[0]-p[1], p[2]-p[1]};
}
inline Pixel rgb(Pixel p) {
    float g = p[0] - 0.2126f*p[1] - 0.0722f*p[2];
    return {g+p[1], g, g+p[2]};
}
inline bool finite(Pixel p) {
    return std::isfinite(p[0]) && std::isfinite(p[1]) && std::isfinite(p[2]);
}
struct Result { Pixel pixel; float hf=0, lf=0; };
// read(x,y) is immutable POST_DEMOSAIC_LINEAR_RGB; shape(x,y) is the existing
// local/mean physical sigma ratio (including LSC), or unity when unavailable.
template<class Read, class Shape>
Result filter(int x, int y, Model m, Read read, Shape shape) {
    Pixel in = read(x,y);
    if (!m.valid() || !finite(in)) return {in};
    // A single estimator in the linearly transformed domain; return its correction
    // through the inverse transform so the established colour owner still runs once.
    auto analyse=[&](int xx,int yy) {auto p=read(xx,yy);return opponent(m.colorDomain?transformPixel(m.transform,p):p);};
    Pixel p=analyse(x,y);
    float s=shape(x,y);s*=s;
    const float sr=std::sqrt(m.rg*s), sb=std::sqrt(m.bg*s);
    const float rho=std::clamp(m.cross/std::sqrt(m.rg*m.bg),-.99999f,.99999f);
    const float tail=std::sqrt(1-rho*rho);
    float sumR=0,sumB=0,rr=0,bb=0,rb=0,weight=0,weight2=0;
    for(int dy=-2;dy<=2;++dy) for(int dx=-2;dx<=2;++dx) {
        auto q=analyse(x+dx,y+dy);if(!finite(q))continue;
        float ns=shape(x+dx,y+dy),dyv=q[0]-p[0];
        float w=std::exp(-dyv*dyv/(4*m.y*(s+ns*ns)));
        float u=(q[1]-p[1])/sr,v=((q[2]-p[2])/sb-rho*u)/tail;
        sumR+=w*u;sumB+=w*v;rr+=w*u*u;bb+=w*v*v;rb+=w*u*v;weight+=w;weight2+=w*w;
    }
    if(!(weight>1.001f))return {in};
    float mr=sumR/weight,mb=sumB/weight;
    float correction=weight*weight/std::max(1e-6f,weight*weight-weight2);
    float a=std::max(0.f,(rr/weight-mr*mr)*correction);
    float d=std::max(0.f,(bb/weight-mb*mb)*correction);
    float b=(rb/weight-mr*mb)*correction;
    float gap=std::sqrt((a-d)*(a-d)+4*b*b);
    float lo=std::max(0.f,.5f*(a+d-gap)),hi=.5f*(a+d+gap);
    // Unit variance in whitened coordinates is noise. >=16 is coherent,
    // >=4-sigma colour structure and is left exactly unchanged.
    auto shrink=[](float eigen){return eigen>=16.f?0.f:std::min(1.f,1.f/std::max(1.f,eigen));};
    float sl=shrink(lo),sh=shrink(hi),dr=sl*mr,db=sl*mb;
    if(gap>1e-5f){float k=(sh-sl)/gap;dr+=k*((a-lo)*mr+b*mb);db+=k*(b*mr+(d-lo)*mb);}
    float v=.5f*(m.rg+m.bg)*s;
    float authority=std::min(.90f,v/(v+.01f*p[0]*p[0]));
    if(authority<1e-6f)return {in};
    float cr=p[1]+authority*sr*dr,cb=p[2]+authority*sb*(rho*dr+tail*db);
    Pixel delta=rgb({0,cr-p[1],cb-p[2]});
    if(m.colorDomain)delta=transformPixel(m.inverse,delta);
    Pixel out{in[0]+delta[0],in[1]+delta[1],in[2]+delta[2]};
    return finite(out)?Result{out,authority,0}:Result{in};
}
// Float32 acceptance on normalized linear RGB (including values up to 4).
// Eight float epsilons per unit magnitude; relative edge/HF tolerance 2e-5.
constexpr double kLumaTolerance = 8.0 * 1.1920928955078125e-7;
constexpr double kDetailRatioTolerance = 2e-5;
inline bool detailAccepted(double maxError, double magnitude, double edgeRatio, double hfRatio) {
    return std::isfinite(maxError) && std::isfinite(edgeRatio) && std::isfinite(hfRatio) &&
           maxError <= kLumaTolerance*std::max(1.0,magnitude) &&
           std::abs(edgeRatio-1) <= kDetailRatioTolerance &&
           std::abs(hfRatio-1) <= kDetailRatioTolerance;
}
} // namespace bncam::chroma
