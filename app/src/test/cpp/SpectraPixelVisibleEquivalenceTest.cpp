#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstddef>
#include <iostream>
#include <vector>
#include "SpectraPixelBackend.h"
#include "SpectraVisibleChroma.h"

using namespace bncam::spectra2;

struct Pixel { float r,g,b; };

static constexpr float kSpatialWeight[5][5] = {
    {0.08f,0.16f,0.22f,0.16f,0.08f},
    {0.16f,0.38f,0.62f,0.38f,0.16f},
    {0.22f,0.62f,1.00f,0.62f,0.22f},
    {0.16f,0.38f,0.62f,0.38f,0.16f},
    {0.08f,0.16f,0.22f,0.16f,0.08f}
};

static Pixel applyOne(
    const std::vector<Pixel>& image, int width, int height, int x, int y,
    const VisibleChromaPlan& plan, const OpponentCovariance2& covariance,
    bool tileMode, const OpponentTileBuffer* tile, int tileX, int tileY
) {
    auto sampleOpponent = [&](int sx, int sy, float& luma, float& rg, float& bg, float& sat) {
        if (tileMode) {
            const int lx = sx - tileX + 2;
            const int ly = sy - tileY + 2;
            const auto index = tile->index(lx,ly);
            luma=tile->luma[index]; rg=tile->rg[index]; bg=tile->bg[index]; sat=tile->saturation[index];
        } else {
            sx=std::clamp(sx,0,width-1); sy=std::clamp(sy,0,height-1);
            const auto& p=image[static_cast<std::size_t>(sy*width+sx)];
            opponentScalar(p.r,p.g,p.b,luma,rg,bg,sat);
        }
    };
    float centerY, centerRG, centerBG, saturation;
    sampleOpponent(x,y,centerY,centerRG,centerBG,saturation);
    float maxLuma=0.0f,maxColour=0.0f,weightedRG=0.0f,weightedBG=0.0f,weightSum=0.0f;
    int supported=0;
    for(int dy=-2;dy<=2;++dy) for(int dx=-2;dx<=2;++dx) {
        float ny,nrg,nbg,ns;
        sampleOpponent(std::clamp(x+dx,0,width-1),std::clamp(y+dy,0,height-1),ny,nrg,nbg,ns);
        const float dY=ny-centerY,dRG=nrg-centerRG,dBG=nbg-centerBG;
        maxLuma=std::max(maxLuma,std::abs(dY));
        const float md=opponentMahalanobisSquared(covariance,dRG,dBG,1.0f);
        maxColour=std::max(maxColour,md);
        const float lw=std::exp(-0.5f*(dY/0.01f)*(dY/0.01f));
        const float cw=std::exp(-0.5f*std::min(16.0f,md));
        const float w=kSpatialWeight[dy+2][dx+2]*lw*cw;
        if((dx||dy)&&w>=0.035f) supported++;
        weightedRG+=w*nrg; weightedBG+=w*nbg; weightSum+=w;
    }
    const float fRG=weightSum>1e-6f?weightedRG/weightSum:centerRG;
    const float fBG=weightSum>1e-6f?weightedBG/weightSum:centerBG;
    auto decision=resolveVisibleChromaDecision(plan,covariance,centerRG,centerBG,fRG,fBG,1.0f,maxLuma/0.01f,std::sqrt(maxColour),maxLuma/0.01f,std::sqrt(maxColour),centerY,saturation,static_cast<float>(supported)/24.0f);
    const auto& c=image[static_cast<std::size_t>(y*width+x)];
    Pixel out=c;
    if(decision.supported) {
        const float g=centerY-0.2126f*decision.outputRG-0.0722f*decision.outputBG;
        Pixel candidate{g+decision.outputRG,g,g+decision.outputBG};
        float gamut=1.0f;
        const float center[3]={c.r,c.g,c.b}; float cand[3]={candidate.r,candidate.g,candidate.b};
        for(int i=0;i<3;++i){float d=cand[i]-center[i];if(d<-1e-9f)gamut=std::min(gamut,center[i]/-d);else if(d>1e-9f)gamut=std::min(gamut,(1.0f-center[i])/d);} 
        gamut=std::clamp(gamut,0.0f,1.0f);
        out={c.r+(candidate.r-c.r)*gamut,c.g+(candidate.g-c.g)*gamut,c.b+(candidate.b-c.b)*gamut};
    }
    return out;
}

int main(){
    constexpr int width=129,height=97,tileSize=32;
    std::vector<Pixel> image(static_cast<std::size_t>(width*height));
    std::vector<float> interleaved(static_cast<std::size_t>(width*height*3));
    for(int y=0;y<height;++y)for(int x=0;x<width;++x){
        const float base=0.08f+0.004f*std::sin(0.17f*x)+0.003f*std::cos(0.13f*y);
        Pixel p{base+0.006f*std::sin(0.7f*x),base,base+0.005f*std::cos(0.5f*y)};
        image[static_cast<std::size_t>(y*width+x)]=p;
        auto i=static_cast<std::size_t>((y*width+x)*3);interleaved[i]=p.r;interleaved[i+1]=p.g;interleaved[i+2]=p.b;
    }
    auto covariance=makeOpponentCovariance(4e-5f,5e-5f,1e-5f);
    auto plan=buildVisibleChromaPlan(true,4e-5f,5e-5f,1e-5f,0.9f,0.9f,1.0f,1.4f);
    assert(covariance.valid&&plan.enabled);
    std::vector<Pixel> reference(image.size()), tiled(image.size());
    for(int y=0;y<height;++y)for(int x=0;x<width;++x)reference[static_cast<std::size_t>(y*width+x)]=applyOne(image,width,height,x,y,plan,covariance,false,nullptr,0,0);
    RgbFloatFrameView view{interleaved.data(),width,height,static_cast<std::size_t>(width*3)};
    PixelKernelSelection selection{};selection.selected=PixelKernelBackendKind::TiledCpu;selection.tileSize=tileSize;
    for(int ty=0;ty<height;ty+=tileSize)for(int tx=0;tx<width;tx+=tileSize){
        int tw=std::min(tileSize,width-tx),th=std::min(tileSize,height-ty);
        OpponentTileBuffer tile;OpponentTileBuildTelemetry telemetry;
        buildOpponentTile(view,tx,ty,tw,th,2,selection,tile,telemetry);
        for(int ly=0;ly<th;++ly)for(int lx=0;lx<tw;++lx)tiled[static_cast<std::size_t>((ty+ly)*width+tx+lx)]=applyOne(image,width,height,tx+lx,ty+ly,plan,covariance,true,&tile,tx,ty);
    }
    float maxDelta=0.0f;
    for(std::size_t i=0;i<image.size();++i){maxDelta=std::max({maxDelta,std::abs(reference[i].r-tiled[i].r),std::abs(reference[i].g-tiled[i].g),std::abs(reference[i].b-tiled[i].b)});}
    assert(maxDelta<2e-6f);
    std::cout<<"SPECTRA_PIXEL_VISIBLE_EQUIVALENCE_OK maxDelta="<<maxDelta<<"\n";
}
