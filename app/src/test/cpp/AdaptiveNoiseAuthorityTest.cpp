#include "../../main/cpp/tests/PhysicalLumaValidation.h"
#include <iostream>
using namespace bncam;
using namespace bncam::luma::test;
int main() {
    bool pass=true;
    auto check=[&](bool ok,const char* name){std::cout<<name<<"="<<ok<<'\n';pass&=ok;};
    float previous=0;
    for(int i=1;i<=100;++i) {
        float sigma=.0004f*i,p=physical::noisePressure(sigma*sigma,.10f);
        check(p>=previous&&p>=0&&p<=1,"variance_monotonic");previous=p;
    }
    previous=1;
    for(int i=0;i<=100;++i) {
        float p=physical::noisePressure(.0009f,float(i)*.01f);
        check(p<=previous,"signal_monotonic");previous=p;
    }
    check(physical::noisePressure(NAN,.1f)==0&&physical::noisePressure(0,.1f)==0,"invalid_identity");
    for(float sigma : {.001f,.00723f,.02767f}) {
        float p=physical::noisePressure(sigma*sigma,.10f);
        std::cout<<"sigma="<<sigma<<" pressure="<<p<<" hfBefore=.28 hfAfter="<<.28f+.32f*p
                 <<" midBefore=.16 midAfter="<<.16f+.20f*p<<" chromaMultiplier="<<1.f+.75f*p<<'\n';
        if(sigma<.008f) check((.32f*p)/.28f<.02f,"normal_available_within_2percent");
        else check((.28f+.32f*p)/.28f>1.7f,"extreme_available_increase");
    }
    float shapeMap[4]={.5f,2.f,.5f,2.f};
    float left=physical::spatialSigma(47,48,W,H,2,2,shapeMap);
    float right=physical::spatialSigma(48,48,W,H,2,2,shapeMap);
    check(std::abs(left-right)<.05f,"no_map_boundary_jump");
    check(physical::noisePressure(.0001f*right*right,.1f)>physical::noisePressure(.0001f*left*left,.1f),"local_variance_contributes");
    double randomStructure[2]{};
    for(int scene=0;scene<5;++scene) {
        float sigma=scene==0?.005f:scene==1?.02767f:scene==2?.00723f:.02767f;
        Image input(W*H),truth(W*H),old(W*H),out(W*H);
        std::mt19937 rng(812);std::normal_distribution<float> normal(0,1);
        for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
            float t=.10f;
            if(scene==2)t+=.03f*std::sin(.8f*x+.1f*y);
            if(scene==3)t+=(x==W/2||x==W/2+1)?.12f:0.f;
            if(scene==4)t+=.028f*std::sin(.8f*x);
            truth[y*W+x]={t,t,t};float n=sigma*normal(rng);input[y*W+x]={t+n,t+n,t+n};
        }
        auto read=[&](int x,int y){return input[std::clamp(y,0,H-1)*W+std::clamp(x,0,W-1)];};
        auto shape=[](int,int){return 1.f;};
        double oldHf=0,newHf=0,structure=0,difference=0;int count=0;
        for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
            auto a=luma::filter(x,y,{sigma*sigma,1.f},read(x,y),read,shape,false);
            auto b=luma::filter(x,y,{sigma*sigma,1.f},read(x,y),read,shape);
            old[y*W+x]=a.pixel;out[y*W+x]=b.pixel;
            if(x>4&&x<W-5&&y>4&&y<H-5){oldHf+=a.hf;newHf+=b.hf;structure+=b.structure;++count;difference+=std::pow(a.pixel[0]-b.pixel[0],2);}
        }
        auto ma=metrics(old,truth),mb=metrics(out,truth);
        std::cout<<"scene="<<scene<<" structure="<<structure/count<<" hfRatio="<<newHf/oldHf<<" mseRatio="<<mb.mse/ma.mse<<" projectionDelta="<<mb.projection-ma.projection<<'\n';
        if(scene<2) randomStructure[scene]=structure/count;
        if(scene==1)check(newHf/oldHf>1.7&&mb.mse/ma.mse<.85,"extreme_flat_stronger_actual");
        if(scene==2)check(std::sqrt(difference/count)<sigma*.01&&std::abs(mb.projection-ma.projection)<.001,"normal_texture_stable");
        if(scene>=3)check(std::abs(mb.projection-ma.projection)<.03&&mb.projection>.95&&mb.mse<ma.mse,"extreme_coherent_line_protected");
    }
    std::cout<<"randomStructureLow="<<randomStructure[0]<<" randomStructureExtreme="<<randomStructure[1]<<'\n';
    check(std::abs(randomStructure[0]-randomStructure[1])<.001,"random_noise_not_extra_structure");
    // Same physical opponent estimator, independent known Gaussian channels.
    Image rgb(W*H);std::mt19937 rng(44);std::normal_distribution<float> normal(0,1);
    constexpr float sigma=.028f;
    for(auto& p:rgb)p=chroma::rgb({.1f+sigma*normal(rng),sigma*normal(rng),sigma*normal(rng)});
    auto read=[&](int x,int y){return rgb[std::clamp(y,0,H-1)*W+std::clamp(x,0,W-1)];};
    auto shape=[](int,int){return 1.f;};
    double oldPower=0,newPower=0,oldHf=0,newHf=0,maxY=0;
    for(int y=4;y<H-4;++y)for(int x=4;x<W-4;++x) {
        chroma::Model m{sigma*sigma,sigma*sigma,sigma*sigma,0};
        auto a=chroma::filter(x,y,m,read,shape,false),b=chroma::filter(x,y,m,read,shape);
        auto pa=chroma::opponent(a.pixel),pb=chroma::opponent(b.pixel);
        oldPower+=pa[1]*pa[1]+pa[2]*pa[2];newPower+=pb[1]*pb[1]+pb[2]*pb[2];
        oldHf+=a.hf;newHf+=b.hf;maxY=std::max(maxY,double(std::abs(pb[0]-chroma::opponent(read(x,y))[0])));
        pass&=b.hf+b.lf<=.900001f;
    }
    std::cout<<"chromaPowerRatio="<<newPower/oldPower<<" chromaHfRatio="<<newHf/oldHf<<" maxY="<<maxY<<'\n';
    check(newHf/oldHf>1.2&&newPower/oldPower<.85&&maxY<1e-6,"chroma_extreme_and_luma_identity");
    std::cout<<"adaptiveAllPassed="<<pass<<'\n';return !pass;
}
