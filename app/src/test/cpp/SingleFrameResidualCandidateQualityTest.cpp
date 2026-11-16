#include "../../main/cpp/SingleFrameResidualConfidence.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>

using namespace bncam;
using P=sf2::Pixel;
constexpr int W=160,H=128;
static P make(float y){return chroma::rgb({y,.012f,-.006f});}
static float py(P p){return chroma::opponent(p)[0];}

int main(){
    // End-to-end *native oracle* on known synthetic truth: actual phase-4
    // guidedFine + phase-5 coarse candidate, NOT arbitrary correction data.
    // No GPU execution, real camera samples, or measured demosaic rho.
    const luma::Model model{.000064f,1.f};
    const auto shape=[](int,int){return 1.f;};
    std::vector<P> input(W*H);
    std::vector<float> truth(W*H);
    std::mt19937 random(62026);
    std::normal_distribution<float> noise(0.f,.008f);
    for(int y=0;y<H;++y)for(int x=0;x<W;++x){
        const bool hairA=x==41 && y>10 && y<H-10;
        const bool hairB=x==54 && y>10 && y<H-10;
        const bool texture=(x>85 && x<145 && y>12 && y<76);
        const bool edge=(y>92);
        const float scene=.10f+(hairA?.020f:0.f)+(hairB?.011f:0.f)+
             (texture?(((x/2+y/3)&1)? .011f:-.011f):0.f)+
             (edge?.14f:0.f);
        truth[y*W+x]=scene;
        input[y*W+x]=make(scene+noise(random));
    }
    auto read=[&](int x,int y)->P{
        return input[static_cast<std::size_t>(std::clamp(y,0,H-1))*W+
                     std::clamp(x,0,W-1)];
    };
    const auto coarse=sf2::buildGuidedCoarse(W,H,model,read,shape);
    assert(coarse.valid && coarse.correctedCells>0);

    double preHair=0,plainHair=0,gatedHair=0;
    double preTexture=0,plainTexture=0,gatedTexture=0;
    double preFlat=0,plainFlat=0,gatedFlat=0;
    double preEdge=0,plainEdge=0,gatedEdge=0;
    int hair=0,texture=0,flat=0,edge=0;
    double weighted=0;
    std::vector<float> plainY(W*H,0.f),gatedY(W*H,0.f);
    for(int y=8;y<H-8;++y)for(int x=8;x<W-8;++x){
        const int i=y*W+x;
        const P post=input[i];
        const auto fine=sf2::guidedFine(x,y,model,post,read,shape,true);
        const float fineDelta=fine.pixel[0]-post[0];
        const float coarseDelta=sf2::coarseAtPixel(coarse,x,y);
        const auto gate=sf2::residualConfidenceAt(
                x,y,model,fineDelta,coarseDelta,false,read,shape);
        assert(gate.valid && gate.weight>=0.f && gate.weight<=1.f);
        const float before=py(post),raw=before+fineDelta+coarseDelta;
        const float safe=py(sf2::applyResidualConfidence(post,fineDelta,coarseDelta,gate));
        plainY[i]=raw;
        gatedY[i]=safe;
        const double errPre=(before-truth[i])*(before-truth[i]);
        const double errPlain=(raw-truth[i])*(raw-truth[i]);
        const double errSafe=(safe-truth[i])*(safe-truth[i]);
        const bool h=(x==41||x==54) && y>15 && y<87;
        const bool t=x>93 && x<135 && y>20 && y<68;
        const bool e=y>90&&y<98 && x>12 && x<76;
        const bool f=x>62&&x<82&&y>19&&y<70;
        if(h){preHair+=errPre;plainHair+=errPlain;gatedHair+=errSafe;++hair;}
        if(t){preTexture+=errPre;plainTexture+=errPlain;gatedTexture+=errSafe;++texture;}
        if(e){preEdge+=errPre;plainEdge+=errPlain;gatedEdge+=errSafe;++edge;}
        if(f){preFlat+=errPre;plainFlat+=errPlain;gatedFlat+=errSafe;++flat;}
        weighted+=gate.weight;
    }
    assert(hair>0&&texture>0&&edge>0&&flat>0);
    // The gate must be nontrivial, finite and independently selectable.
    assert(std::isfinite(weighted) && weighted>0.0);
    // Compare coherent *contrast retention* as well as pixel MSE. More
    // retained modulation can accompany slightly worse raw MSE on noisy lines;
    // both metrics must be reported, never select just one favourable number.
    auto hairContrast=[&](const std::vector<float>& image,int x){
        double sum=0.;
        for(int y=20;y<86;++y) {
            const int i=y*W+x;
            sum+=image[i]-.5*(image[i-1]+image[i+1]);
        }
        return sum/66.;
    };
    auto textileModulation=[&](const std::vector<float>& image){
        double contrast=0.,count=0.;
        for(int y=24;y<66;++y)for(int x=96;x<133;++x) {
            const float sign=((x/2+y/3)&1)?1.f:-1.f;
            contrast+=double(sign)*(image[y*W+x]-.10f);
            count+=1.;
        }
        return contrast/count;
    };
    auto edgeContrast=[&](const std::vector<float>& image){
        double sum=0;
        for(int x=25;x<75;++x)
            sum+=image[93*W+x]-image[92*W+x];
        return sum/50.;
    };
    // Actual phase-4/5 candidate regression: more TRUE deterministic
    // hair/textile modulation must survive confidence protection; the
    // MSE tradeoff is separately constrained, not hidden.
    assert(std::abs(hairContrast(gatedY,41)-.020)
           < .96*std::abs(hairContrast(plainY,41)-.020));
    assert(std::abs(hairContrast(gatedY,54)-.011)
           < .96*std::abs(hairContrast(plainY,54)-.011));
    assert(std::abs(textileModulation(gatedY)-.011)
           < .80*std::abs(textileModulation(plainY)-.011));
    assert(std::abs(edgeContrast(gatedY)-.140)
           < 1.10*std::abs(edgeContrast(plainY)-.140));
    assert(gatedFlat <= 1.10*plainFlat);
    assert(gatedHair <= 1.10*plainHair);
    assert(gatedTexture <= 1.10*plainTexture);
    assert(gatedEdge <= 1.10*plainEdge);
    std::cout<<"actualCandidateDetailRetention"
        <<" hair20="<<hairContrast(plainY,41)<<","<<hairContrast(gatedY,41)
        <<" hair11="<<hairContrast(plainY,54)<<","<<hairContrast(gatedY,54)
        <<" textile11="<<textileModulation(plainY)<<","<<textileModulation(gatedY)
        <<" step140="<<edgeContrast(plainY)<<","<<edgeContrast(gatedY)
        <<"\n";
    std::cout<<"actualCandidateSynthetic"
        <<" hair="<<preHair<<","<<plainHair<<","<<gatedHair
        <<" textile="<<preTexture<<","<<plainTexture<<","<<gatedTexture
        <<" edge="<<preEdge<<","<<plainEdge<<","<<gatedEdge
        <<" flat="<<preFlat<<","<<plainFlat<<","<<gatedFlat<<"\n";
    return 0;
}
