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
// Difference covariance = covariance(center)+covariance(neighbour). The 1e-5
// relative diagonal floor bounds inversion near rank one; it is numerical, not
// a synthetic noise floor. Zero/missing physical variance is always identity.
inline float distance(Pixel a, Pixel b, Model m, float scale) {
    if (!finite(b)) return 1e20f;
    float rho=std::clamp(m.cross/(std::sqrt(m.rg)*std::sqrt(m.bg)),-1.f,1.f);
    float u=(a[1]-b[1])/std::sqrt(m.rg), v=(a[2]-b[2])/std::sqrt(m.bg);
    // Whiten then diagonalize the 2x2 correlation matrix. This avoids the
    // catastrophic subtraction in determinant/inverse evaluation near rank one.
    float dc=0.5f*((u+v)*(u+v)/(1.f+rho+1e-5f)+(u-v)*(u-v)/(1.f-rho+1e-5f));
    float dy=a[0]-b[0];
    return dc/scale+dy*dy/(m.y*scale);
}
struct Result { Pixel pixel; float hf=0, lf=0; };
// read(x,y) is immutable POST_DEMOSAIC_LINEAR_RGB; shape(x,y) is the existing
// local/mean physical sigma ratio (including LSC), or unity when unavailable.
template<class Read, class Shape>
Result filter(int x, int y, Model m, Read read, Shape shape, bool adaptive = true) {
    Pixel in = read(x,y);
    if (!m.valid() || !finite(in)) return {in};
    Pixel p = opponent(in);
    float s = shape(x,y); s *= s;
    const float v = 0.5f*(m.rg+m.bg)*s;
    // Dimensionless SNR knee at signal RMS / chroma RMS = 10. No ISO curve.
    float authority = v / (v + 0.01f*p[0]*p[0]);
    const float pressure = adaptive ? physical::noisePressure(v,p[0]) : 0.f;
    authority *= 1.f + 0.75f*pressure;
    float hr=p[1], hb=p[2], hw=1, lr=p[1], lb=p[2], lw=1;
    for (int dy=-1; dy<=1; ++dy) for (int dx=-1; dx<=1; ++dx) {
        if (!dx && !dy) continue;
        Pixel q = opponent(read(x+dx,y+dy));
        float ns=shape(x+dx,y+dy);
        float d=distance(p,q,m,s+ns*ns);
        float w=std::exp(-0.25f*d);
        if (finite(q)) { hr+=w*q[1]; hb+=w*q[2]; hw+=w; }
        // Larger support cannot jump a narrow intervening colour/luma boundary.
        Pixel mid=opponent(read(x+2*dx,y+2*dy));
        Pixel far=opponent(read(x+3*dx,y+3*dy));
        float ms=shape(x+2*dx,y+2*dy), fs=shape(x+3*dx,y+3*dy);
        float gate=std::max({d, distance(p,mid,m,s+ms*ms), distance(p,far,m,s+fs*fs)});
        float wl=std::exp(-gate); // four times stricter than HF
        if (finite(far)) { lr+=wl*far[1]; lb+=wl*far[2]; lw+=wl; }
    }
    // Keep >=10% centre weight even at extreme pressure. Neighbour gates and
    // the HF/LF estimators are unchanged; no extrapolation across colour edges.
    const float support=0.75f*(1.f-1.f/hw)+0.25f*(1.f-1.f/lw);
    if(support>0.f) authority=std::min(authority,0.90f/support);
    float cr=p[1]+authority*(0.75f*(hr/hw-p[1])+0.25f*(lr/lw-p[1]));
    float cb=p[2]+authority*(0.75f*(hb/hw-p[2])+0.25f*(lb/lw-p[2]));
    if (cr==p[1] && cb==p[2]) return {in};
    Pixel out=rgb({p[0],cr,cb}); // never clip: clipping would violate Y identity
    if (!finite(out)) return {in};
    return {out, authority*0.75f*(1-1/hw), authority*0.25f*(1-1/lw)};
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
