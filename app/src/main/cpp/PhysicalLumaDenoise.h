#pragma once
#include "PhysicalChromaDenoise.h"
#include <atomic>

namespace bncam::luma {
using Pixel = chroma::Pixel;
// Debug process-local override; no saved profile setting or release UI control.
inline std::atomic<bool> debugEnabled{true};
struct Model {
    float varianceY = 0, confidence = 0;
    bool valid() const {
        return std::isfinite(varianceY) && varianceY > 0 &&
               std::isfinite(confidence) && confidence > 0 && confidence <= 1;
    }
};
inline Model fromNoiseState(const spectra2::NoiseState& state, bool available) {
    Model m{float(state.varianceY), float(state.confidence)};
    return available && m.valid() ? m : Model{};
}
struct Result { Pixel pixel; float hf=0, mid=0, structure=0, pressure=0; };
// Redundant directional lifting residuals at spacing 1 and 2. Each prediction
// reproduces affine illumination exactly; no coarse/DC band is attenuated.
// Five parallel residuals provide noise-normalized continuity evidence, including
// weak lines. All evidence reads immutable Y; chroma's Y-invariance lets the GPU
// reuse demosaic RGB without materializing a second post-chroma image.
template<class Read, class Shape>
Result filter(int x, int y, Model model, Pixel postChroma, Read read, Shape shape, bool adaptive = true) {
    if (!model.valid() || !chroma::finite(postChroma)) return {postChroma};
    const float center=chroma::opponent(read(x,y))[0];
    const float localShape=shape(x,y);
    const float v=model.varianceY*localShape*localShape;
    if (!(v>0) || !std::isfinite(v)) return {postChroma};
    // Dimensionless SNR knee sqrt(1000), independent of ISO and camera identity.
    const float physical=model.confidence*v/(v+0.001f*center*center);
    const float pressure=adaptive ? bncam::physical::noisePressure(v,center) : 0.f;
    float correction=0, authority[2]{}, structure=0;
    const int dx[4]={1,0,1,1}, dy[4]={0,1,1,-1};
    for(int band=0;band<2;++band) {
        const int radius=band+1;
        float residuals[4]{}, likelihood[4]{}, bandStructure=0;
        for(int dir=0;dir<4;++dir) {
            float sum=0, power=0, noise=0;
            for(int k=-2;k<=2;++k) {
                int xx=x-k*dy[dir], yy=y+k*dx[dir];
                int ax=xx-radius*dx[dir], ay=yy-radius*dy[dir];
                int bx=xx+radius*dx[dir], by=yy+radius*dy[dir];
                float c=chroma::opponent(read(xx,yy))[0];
                float a=chroma::opponent(read(ax,ay))[0];
                float b=chroma::opponent(read(bx,by))[0];
                float s=shape(xx,yy), sa=shape(ax,ay), sb=shape(bx,by);
                float n=model.varianceY*(s*s+0.25f*(sa*sa+sb*sb));
                float d=c-0.5f*(a+b);
                if(!std::isfinite(d) || !std::isfinite(n) || !(n>0)) return {postChroma};
                if(k==0) residuals[dir]=d;
                sum+=d; power+=d*d; noise+=n;
            }
            power*=0.2f; noise*=0.2f;
            const float mean=sum*0.2f;
            // Independent parallel stencils: variance(mean)=noise/5. Coherence
            // is continuous, not an amplitude threshold on the centre coefficient.
            const float coherent=std::max(0.f,mean*mean-noise*0.2f);
            const float signal=std::max(0.f,power-noise);
            const float confidence=coherent/(coherent+noise*0.2f);
            bandStructure=std::max(bandStructure,confidence);
            likelihood[dir]=noise/(noise+4.f*signal);
        }
        structure=std::max(structure,bandStructure);
        float available=band==0 ? 0.28f+0.32f*pressure : 0.16f+0.20f*pressure;
        float a=physical*(1.f-bandStructure)*available;
        for(int dir=0;dir<4;++dir) {
            correction+=0.25f*a*likelihood[dir]*residuals[dir];
            authority[band]+=0.25f*a*likelihood[dir];
        }
    }
    Pixel out{postChroma[0]-correction,postChroma[1]-correction,postChroma[2]-correction};
    if(!chroma::finite(out)) return {postChroma};
    return {out,authority[0],authority[1],structure,pressure};
}
} // namespace bncam::luma
