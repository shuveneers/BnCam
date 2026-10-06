#include "PhysicalLumaDenoise.h"
#include "PhysicalNoisePropagation.h"
#include <cstring>
#include <iostream>
#include <random>
#include <vector>
namespace baseline8aee2d2 { namespace spectra2=bncam::spectra2; }
#include "baseline_8aee2d2/PhysicalLumaDenoise.h"

int main() {
    using namespace bncam;
    constexpr int W=32,H=32;
    std::vector<chroma::Pixel> input(W*H);
    size_t checked=0;bool same=true,predictionDiffers=false;
    // Fixed samples: low/normal/extreme noise, dark/bright, thin lines, weak
    // texture, oblique hair, chroma edges/text at constant Y; same RGB oracle.
    for(int scene=0;scene<10;++scene)for(float sigma:{.00001f,.005f,.03f}) {
        std::mt19937 rng(415);std::normal_distribution<float> n(0,sigma);
        for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
            float Y=scene==1?.8f:.03f,rg=0,bg=0;
            if(scene==2)Y+=x==16?.05f:0;
            if(scene==3)Y+=x==16||x==17?.05f:0;
            if(scene==4)Y+=.006f*std::sin(.7f*x+.2f*y);
            if(scene==5)Y+=(x-y)%9==0?.03f:0;
            if(scene==6) {rg=x<16?.04f:-.04f;bg=-rg;}
            if(scene==7) {rg=.006f*std::sin(.7f*x);bg=.004f*std::cos(.9f*y);}
            if(scene==8)rg=x%9==0||y%9==0?.04f:0;
            if(scene==9)Y+=.015f*std::sin(2.1f*x)*std::cos(2.1f*y);
            input[y*W+x]=chroma::rgb({Y+n(rng),rg+n(rng),bg+n(rng)});
        }
        auto read=[&](int x,int y){return input[std::clamp(y,0,H-1)*W+std::clamp(x,0,W-1)];};
        for(float confidence:{0.f,.01f,.5f,1.f,NAN}) {
            auto seed=spectra2::makeState("RAW","S/O","VALID",1,
                spectra2::diagonalCovariance(sigma*sigma,2*sigma*sigma,3*sigma*sigma));
            auto legacy=spectra2::propagateDemosaic(seed,spectra2::DemosaicModel::Malvar2004);
            legacy.confidence=confidence;
            const auto saved=legacy;
            const auto prediction=physical::predictNoiseOnly(seed,spectra2::DemosaicModel::Malvar2004,legacy,true);
            predictionDiffers |= prediction.valid && prediction.state.varianceRG!=legacy.varianceRG;
            same &= legacy.covariance.values==saved.covariance.values;
            auto cm=chroma::fromNoiseState(legacy,true);
            auto oldCm=baseline8aee2d2::chroma::fromNoiseState(legacy,true);
            auto lm=luma::fromNoiseState(legacy,true);
            auto oldLm=baseline8aee2d2::luma::fromNoiseState(legacy,true);
            for(int shapeCase=0;shapeCase<3;++shapeCase) {
                auto shape=[&](int x,int y){return shapeCase==0?1.f:shapeCase==1?.5f+float(std::clamp(x+y,0,W+H-2))/(W+H-2):NAN;};
                for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
                    auto c=chroma::filter(x,y,cm,read,shape);
                    auto oldC=baseline8aee2d2::chroma::filter(x,y,oldCm,read,shape);
                    auto l=luma::filter(x,y,lm,c.pixel,read,shape);
                    auto oldL=baseline8aee2d2::luma::filter(x,y,oldLm,oldC.pixel,read,shape);
                    same &= std::memcmp(c.pixel.data(),oldC.pixel.data(),sizeof(c.pixel))==0;
                    same &= std::memcmp(l.pixel.data(),oldL.pixel.data(),sizeof(l.pixel))==0;
                    same &= c.hf==oldC.hf && c.lf==oldC.lf && l.hf==oldL.hf && l.mid==oldL.mid;
                    ++checked;
                }
            }
        }
    }
    std::cout<<"pixels="<<checked<<" bitExactBaseline="<<same<<" diagnosticCovarianceActuallyChanged="<<predictionDiffers<<'\n';
    return !(same&&predictionDiffers);
}
