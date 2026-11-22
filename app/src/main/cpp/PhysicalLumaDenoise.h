#pragma once
#include "PhysicalChromaDenoise.h"
#include <atomic>
#include <cstdint>

namespace bncam::luma {
using Pixel = chroma::Pixel;
// SF2-FINAL: Balanced is the single-shot RAW / Spectra OFF product candidate.
// Off remains the non-RAW, multi-frame and invalid-model fallback. Other stages
// are archived for debug GPU regression and reproducible rollback only.
enum class Stage : std::uint32_t { OFF = 0, LEGACY_REFERENCE = 1, GUIDED_V2 = 2, GUIDED_V2_BALANCED = 3,
                                   GUIDED_V2_FINE_COARSE = 4,
                                   GUIDED_V2_CONFIDENCE = 5, GUIDED_V2_BALANCED_REFERENCE = 6 };
// Diagnostic stage is separate from the product default: old debug tooling cannot
// silently replace the standard rendering path until an explicit override.
inline std::atomic<Stage> selectedStage{Stage::OFF};
inline std::atomic<bool> debugStageOverrideActive{false};
#ifndef NDEBUG
// A synchronous debug replay can select a stage without changing the camera's
// global override or a concurrent render on another thread.
inline thread_local int benchmarkStage = -1;
#endif
inline Stage resolveStage(bool singleShotRaw, bool spectraOff) {
    if (!singleShotRaw || !spectraOff) return Stage::OFF;
#ifndef NDEBUG
    if (benchmarkStage >= 0) return static_cast<Stage>(benchmarkStage);
#endif
    if (debugStageOverrideActive.load(std::memory_order_relaxed))
        return selectedStage.load(std::memory_order_relaxed);
    return Stage::GUIDED_V2_BALANCED;
}
inline constexpr bool legacyAuthorized(Stage value) { return value == Stage::LEGACY_REFERENCE; }
inline constexpr bool balancedAuthorized(Stage value) {
    return value == Stage::GUIDED_V2_BALANCED || value == Stage::GUIDED_V2_BALANCED_REFERENCE;
}
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
// weak lines. All evidence reads immutable demosaic Y; the luma correction is
// added to post-chroma RGB. The CPU uses the same immutable source, including
// when chroma preserves WB/CCM-domain Y rather than camera-domain Y.
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
