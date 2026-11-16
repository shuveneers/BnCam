#include "../../main/cpp/SingleFrameGuidedCoarse.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <limits>
#include <random>
#include <vector>
#include <iostream>

using namespace bncam;
using P=sf2::Pixel;
constexpr int W=256,H=192;
constexpr float PI=3.14159265358979323846f;
static bool near(float a,float b,float tol=1.e-5f){return std::abs(a-b)<=tol;}
static P make(float y,float rg=.014f,float bg=-.008f){
    return chroma::rgb({y,rg,bg});
}

int main(){
    const luma::Model model{.000064f,1.f};
    const auto shape=[](int,int){return 1.f;};
    std::vector<P> source(W*H);
    auto read=[&](int x,int y)->P{
        return source[static_cast<std::size_t>(std::clamp(y,0,H-1))*W+
                      std::clamp(x,0,W-1)];
    };
    auto meanY=[&](int x,int y){return chroma::opponent(read(x,y))[0];};

    // 4x4 binomial anti-alias: exact DC, checkerboard rejection and
    // 0.09765625 independent-site variance energy for uniform sigma.
    {
        for(int y=0;y<H;++y)for(int x=0;x<W;++x)source[y*W+x]=
            make(.2f+((x+y)&1 ? .027f:-.027f));
        auto cell=sf2::downsampleCoarseCell(4,6,W,H,model,read,shape);
        assert(cell.valid);
        assert(near(cell.y,.2f,1.e-6f));
        assert(near(cell.varianceIndependent,model.varianceY*(25.f/256.f),1.e-8f));
        assert(near(cell.varianceUpperBound,model.varianceY,1.e-8f));
        const auto map=sf2::buildGuidedCoarse(W,H,model,read,shape);
        assert(map.valid&&map.invalidCells==0);
        assert(map.width==W/4&&map.height==H/4);
        assert(map.maxAbsCorrection < 2.e-6f);
    }
    // Constant fields have exact coarse bypass and preserve arbitrary
    // post-chroma opponent differences (no tone/DC shift).
    {
        std::fill(source.begin(),source.end(),make(.18f));
        auto map=sf2::buildGuidedCoarse(W,H,model,read,shape);
        assert(map.valid && map.maxAbsCorrection<1.e-6f);
        for(int y=8;y<H-8;y+=9)for(int x=8;x<W-8;x+=7){
            auto fine=sf2::guidedFine(x,y,model,read(x,y),read,shape);
            auto out=sf2::addCoarseResidual(fine.pixel,sf2::coarseAtPixel(map,x,y));
            assert(near(chroma::opponent(out)[0],.18f,2.e-6f));
            assert(std::abs((out[0]-out[1])-(fine.pixel[0]-fine.pixel[1]))<1.e-6f);
            assert(std::abs((out[2]-out[1])-(fine.pixel[2]-fine.pixel[1]))<1.e-6f);
        }
    }
    // A genuine slowly varying exposure/shadow ramp must remain a ramp.
    {
        for(int y=0;y<H;++y)for(int x=0;x<W;++x)
            source[y*W+x]=make(.06f+.00030f*x+.00018f*y);
        auto map=sf2::buildGuidedCoarse(W,H,model,read,shape);
        assert(map.valid);
        for(int y=24;y<H-24;y+=13)for(int x=24;x<W-24;x+=11)
            assert(std::abs(sf2::coarseAtPixel(map,x,y))<2.e-4f);
    }

    // Broad low-amplitude correlated noise vs truly constant ground truth:
    // verify the coarse stage can make a measurable change beyond fine-only.
    double fineError=0,combinedError=0,differenceEnergy=0,chromaError=0;
    {
        std::mt19937 rng(31);
        std::normal_distribution<float> n(0.f,1.f);
        for(int y=0;y<H;++y)for(int x=0;x<W;++x){
            const float coarseNoise=.0030f*std::sin(2.f*PI*x/27.f)*
                                    std::cos(2.f*PI*y/29.f);
            const float microNoise=.004f*n(rng);
            source[y*W+x]=make(.11f+coarseNoise+microNoise);
        }
        const auto map=sf2::buildGuidedCoarse(W,H,model,read,shape);
        assert(map.valid&&map.correctedCells>0&&map.maxAbsCorrection>1.e-8f);
        for(int y=8;y<H-8;++y)for(int x=8;x<W-8;++x){
            const P initial=read(x,y);
            const auto fine=sf2::guidedFine(x,y,model,initial,read,shape);
            const float corrected=sf2::coarseAtPixel(map,x,y);
            const P combined=sf2::addCoarseResidual(fine.pixel,corrected);
            const float yf=chroma::opponent(fine.pixel)[0];
            const float yc=chroma::opponent(combined)[0];
            fineError+=double(yf-.11f)*double(yf-.11f);
            combinedError+=double(yc-.11f)*double(yc-.11f);
            differenceEnergy+=double(yc-yf)*double(yc-yf);
            const auto a=chroma::opponent(fine.pixel),b=chroma::opponent(combined);
            chromaError=std::max(chromaError,double(std::abs(a[1]-b[1])+std::abs(a[2]-b[2])));
        }
        assert(differenceEnergy>1.e-10);
        assert(chromaError<3.e-6);
        // Deliberately weak regression limit: real-photo benefit remains unproven.
        assert(combinedError < 1.01*fineError);
    }
    // Genuine broad shadow step plus a fine textile pattern: protect
    // the step across the boundary, do not turn coarse into tone mapping.
    {
        for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
            float yv=x<W/2 ? .045f:.28f;
            yv+=((x/2+y/2)&1)? .012f:-.012f;
            source[y*W+x]=make(yv,.016f,-.013f);
        }
        auto map=sf2::buildGuidedCoarse(W,H,model,read,shape);
        assert(map.valid);
        auto left=read(W/2-2,H/2),right=read(W/2+2,H/2);
        auto a=sf2::addCoarseResidual(left,sf2::coarseAtPixel(map,W/2-2,H/2));
        auto b=sf2::addCoarseResidual(right,sf2::coarseAtPixel(map,W/2+2,H/2));
        assert(chroma::opponent(b)[0]-chroma::opponent(a)[0]>.90f*(.28f-.045f));
    }

    // Tile interpolation is continuous across 4-pixel cell boundaries.
    {
        for(int y=0;y<H;++y)for(int x=0;x<W;++x)
            source[y*W+x]=make(.09f+.002f*std::sin(2.f*PI*x/43.f)*
                                     std::cos(2.f*PI*y/37.f));
        auto map=sf2::buildGuidedCoarse(W,H,model,read,shape);
        assert(map.valid);
        for(int y=12;y<H-12;y+=7)for(int x=12;x<W-12;x+=4) {
            const auto left=sf2::coarseAtPixel(map,x-1,y);
            const auto right=sf2::coarseAtPixel(map,x+1,y);
            assert(std::abs(left-right)<=2*map.maxAbsCorrection+1.e-7f);
        }
    }
    // Fail closed: no coarse correction on missing/invalid noise evidence
    // or NaN/negative spatial shape. No invented fallback coefficients.
    {
        auto map=sf2::buildGuidedCoarse(W,H,{},read,shape);
        assert(!map.valid);
        assert(sf2::coarseAtPixel(map,10,10)==0.0f);
        map=sf2::buildGuidedCoarse(W,H,model,read,
             [](int,int){return std::numeric_limits<float>::quiet_NaN();});
        assert(!map.valid);
        map=sf2::buildGuidedCoarse(W,H,model,read,[](int,int){return -1.f;});
        assert(!map.valid);
        const P p{.45f,.33f,.2f};
        assert(sf2::addCoarseResidual(p,std::numeric_limits<float>::quiet_NaN())==p);
        assert(sf2::addCoarseResidual(p,0.f)==p);
        const auto small=sf2::buildGuidedCoarse(8,8,model,read,shape);
        assert(!small.valid);
    }
    std::cout<<"fineMse="<<fineError<<" combinedMse="<<combinedError
             <<" correctedDeltaEnergy="<<differenceEnergy<<"\n";
    return 0;
}
