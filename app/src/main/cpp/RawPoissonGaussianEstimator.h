#pragma once
// SF2-D03: diagnostic single-exposure Poisson-Gaussian S/O fit in normalized
// pre-demosaic Bayer space. It NEVER replaces the calibrated physical model.
// Requires a host-accessible RAW view; never read back Vulkan memory to run it.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>
namespace bncam::sf2 {
struct RawNoiseView {
    const float* data=nullptr;
    int width=0,height=0,stride=0; // stride in floats
    unsigned cfa=0; // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR
};
struct RawNoiseObservation {
    std::array<double,4> slope{},offset{},confidence{};
    std::array<int,4> acceptedTiles{};
    std::array<double,4> signalSpan{};
    bool valid=false;
    bool overridesPhysicalModel=false; // always false: diagnostic only
};
inline int cfaNoiseChannel(unsigned cfa,int px,int py) {
    if(cfa>3)return -1;
    // CFA: R, Gr (green in R row), Gb (green in B row), B
    const int rX=(cfa==1||cfa==3)?1:0, rY=(cfa==2||cfa==3)?1:0;
    const int x=px^rX,y=py^rY;
    if(x==0&&y==0)return 0;
    if(x==1&&y==0)return 1;
    if(x==0&&y==1)return 2;
    return 3;
}
inline RawNoiseObservation estimateRawPoissonGaussian(const RawNoiseView& view,
       const std::array<double,4>& physicalS,const std::array<double,4>& physicalO) {
    RawNoiseObservation out{};
    if(!view.data || view.width<64 || view.height<64 || view.stride<view.width ||
       view.cfa>3)return out;
    for(int k=0;k<4;++k) {
        if(!std::isfinite(physicalS[k])||!std::isfinite(physicalO[k])||
           physicalS[k]<0||physicalO[k]<0||!(physicalS[k]+physicalO[k]>0))return out;
    }
    struct Tile {double mean=0,var=0;};
    std::array<std::vector<Tile>,4> tiles;
    constexpr int step=16;
    // A homogeneous 16x16 block supplies 64 independent samples of each
    // Bayer phase. Reject clipping, invalid pixels and strong gradients.
    for(int by=0;by+step<=view.height;by+=step)
    for(int bx=0;bx+step<=view.width;bx+=step) {
        for(int py=0;py<2;++py)for(int px=0;px<2;++px) {
            int ch=cfaNoiseChannel(view.cfa,px,py);
            double mean=0,m2=0,minV=1e30,maxV=-1e30;int n=0;
            bool bad=false;
            for(int y=by+py;y<by+step;y+=2)
            for(int x=bx+px;x<bx+step;x+=2) {
                const double v=view.data[static_cast<std::size_t>(y)*view.stride+x];
                if(!std::isfinite(v)||v<=.005||v>=.985){bad=true;break;}
                ++n;const double d=v-mean;mean+=d/n;m2+=d*(v-mean);
                minV=std::min(minV,v);maxV=std::max(maxV,v);
            }
            if(bad||n<48)continue;
            const double var=m2/(n-1);
            const double predicted=physicalS[ch]*mean+physicalO[ch];
            // High-frequency texture can masquerade as noise; conservative
            // rejection, NOT positive evidence of a calibrated variance.
            if(!(predicted>0)||!(var>0)||var>3*predicted ||
               maxV-minV>std::max(.08,12*std::sqrt(predicted)))continue;
            tiles[ch].push_back({mean,var});
        }
    }
    for(int ch=0;ch<4;++ch) {
        auto& t=tiles[ch];
        out.acceptedTiles[ch]=static_cast<int>(t.size());
        if(t.size()<12)continue;
        double lo=1,hi=0,sx=0,sy=0,sxx=0,sxy=0;
        for(const auto& a:t) {
            lo=std::min(lo,a.mean);hi=std::max(hi,a.mean);
            sx+=a.mean;sy+=a.var;sxx+=a.mean*a.mean;sxy+=a.mean*a.var;
        }
        const double span=hi-lo;out.signalSpan[ch]=span;
        if(span<.22)continue; // no identifiable S/O slope from a flat histogram
        const double n=static_cast<double>(t.size());
        const double det=n*sxx-sx*sx;
        if(!(det>1e-6))continue;
        double slope=(n*sxy-sx*sy)/det;
        double offset=(sy-slope*sx)/n;
        if(!std::isfinite(slope)||!std::isfinite(offset)||slope<=0||offset<=0)
            continue;
        double error=0,power=0;
        for(const auto& a:t){
            double e=a.var-(slope*a.mean+offset);
            error+=e*e;power+=a.var*a.var;
        }
        const double fit=std::clamp(1-error/std::max(1e-15,power),0.0,1.0);
        const double confidence=std::min(1.0,n/96.0)*
            std::min(1.0,span/.55)*fit;
        out.slope[ch]=slope;out.offset[ch]=offset;
        out.confidence[ch]=confidence;
    }
    out.valid=true;
    for(int ch=0;ch<4;++ch) {
        if(out.confidence[ch]<.18){out.valid=false;break;}
        // Diagnostic disagreement is not allowed to redefine the OEM model.
        const double sRatio=out.slope[ch]/std::max(1e-12,physicalS[ch]);
        const double oRatio=out.offset[ch]/std::max(1e-12,physicalO[ch]);
        if(sRatio<.25||sRatio>4||oRatio<.25||oRatio>4){out.valid=false;break;}
    }
    return out;
}
} // namespace bncam::sf2
