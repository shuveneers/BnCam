#pragma once
#include "../PhysicalLumaDenoise.h"
#include <functional>
#include <iomanip>
#include <random>
#include <sstream>
#include <vector>
#include <cstring>
#include <stdexcept>

namespace bncam::luma::test {
constexpr int W=96,H=96;
using Image=std::vector<Pixel>;
using Engine=std::function<Image(const Image&,Model)>;
inline Image reference(const Image& in, Model m) {
    Image out(in.size());
    auto read=[&](int x,int y){return in[std::clamp(y,0,H-1)*W+std::clamp(x,0,W-1)];};
    for(int y=0;y<H;++y) for(int x=0;x<W;++x)
        out[y*W+x]=filter(x,y,m,read(x,y),read,[](int,int){return 1.f;}).pixel;
    return out;
}
struct Metrics {
    double mse=0, correlation=1, projection=1, gradientAgreement=1;
    double amplitude=0, position=0, spread=0;
};
inline float Y(Pixel p){return chroma::opponent(p)[0];}
inline Metrics metrics(const Image& img,const Image& truth) {
    Metrics m; double a=0,b=0,aa=0,bb=0,ab=0,g=0,gw=0; int count=0;
    double profile[W]{}, ref[W]{};
    for(int y=4;y<H-4;++y) for(int x=4;x<W-4;++x) {
        int i=y*W+x; double p=Y(img[i]),t=Y(truth[i]);
        a+=p;b+=t;aa+=p*p;bb+=t*t;ab+=p*t;m.mse+=(p-t)*(p-t);++count;
        profile[x]+=p/(H-8);ref[x]+=t/(H-8);
        double tx=Y(truth[i+1])-t,ty=Y(truth[i+W])-t;
        double px=Y(img[i+1])-p,py=Y(img[i+W])-p;
        double n=std::hypot(tx,ty),q=std::hypot(px,py);
        if(n>1e-6) {g+=n*(q>1e-12?(tx*px+ty*py)/(n*q):0);gw+=n;}
    }
    m.mse/=count; double va=aa-a*a/count,vb=bb-b*b/count,cov=ab-a*b/count;
    if(vb>1e-9) {m.projection=cov/vb;m.correlation=cov/std::sqrt(std::max(1e-30,va*vb));}
    m.gradientAgreement=gw>0?g/gw:1;
    // Profile edge contrast, absolute-gradient centroid/spread around the known edge.
    for(int x=W/2-12;x<W/2-4;++x) m.amplitude-=profile[x]/8;
    for(int x=W/2+4;x<W/2+12;++x) m.amplitude+=profile[x]/8;
    double mass=0;
    for(int x=W/2-3;x<W/2+3;++x) {double d=std::abs(profile[x+1]-profile[x]);mass+=d;m.position+=d*(x+.5);}
    if(mass>1e-12) m.position/=mass;
    for(int x=W/2-3;x<W/2+3;++x) m.spread+=std::abs(profile[x+1]-profile[x])*std::pow(x+.5-m.position,2);
    m.spread=mass>1e-12?std::sqrt(m.spread/mass):0;
    return m;
}
inline bool structureAccepted(const Metrics& before,const Metrics& after,double tolerance) {
    return std::isfinite(after.mse) && after.mse<=before.mse*1.03+1e-12 &&
        std::abs(after.projection-1)<=std::abs(before.projection-1)+tolerance &&
        after.correlation>=before.correlation-.01 &&
        after.gradientAgreement>=before.gradientAgreement-.02;
}
inline std::string run(Engine engine=reference, unsigned seed=9137) {
    const char* names[]={"dark_flat","midtone_flat","bright_high_snr","strong_edge","weak_edge",
        "one_pixel_line","two_pixel_line","text_strokes","checkerboard","weave","wood",
        "foliage","weak_repeating","random_only","sub_sigma_coherent","isolated_excursion",
        "missing_model","near_zero_model","single_covariance","reduced_covariance","clean_colour"};
    bool pass=true; std::ostringstream report;report<<std::setprecision(10);
    double singleChange=0;
    for(int test=0;test<21;++test) {
        Image clean(W*H),in(W*H);std::mt19937 rng(seed);std::normal_distribution<float> normal(0,1);
        // Known S/O at the flat reference; heteroscedastic samples below use S*Y+O.
        float S=.0001f,O=.000024f;
        if(test==2||test==17||test==20) {S=1e-12f;O=1e-14f;}
        float meanVariance=0;
        for(int y=0;y<H;++y) for(int x=0;x<W;++x) {
            float t=.01f;
            if(test==1)t=.15f;
            if(test==2)t=.85f;
            if(test==3)t=x<W/2?.02f:.65f;
            if(test==4)t=x<W/2?.01f:.017f;
            if(test==5||test==6)t+=(x>=W/2 && x<W/2+(test==5?1:2))?.018f:0;
            if(test==7)t+=((x%13==3 && y%17<13)||(y%17==3 && x%13<9))?.03f:0;
            if(test==8)t+=(x+y)%2?.035f:0;
            if(test==9)t+=.012f*std::sin(2.1f*x)+.012f*std::sin(2.1f*y);
            if(test==10)t+=.012f*std::sin(.8f*x+.8f*std::sin(.12f*y));
            if(test==11)t+=.01f*std::sin(.7f*x)*std::sin(.9f*y)+.014f*std::sin(.25f*x+.3f*y);
            if(test==12)t+=.006f*std::sin(1.2f*x);
            if(test==14)t+=.004f*std::sin(.8f*x);
            if(test==15 && x==W/2 && y==H/2)t+=.3f;
            if(test==18||test==19)t+=.008f*std::sin(.8f*x);
            if(test==20)t=.3f;
            float variance=S*std::max(t,0.f)+O;
            meanVariance+=variance/(W*H);
            Pixel p=chroma::rgb({t,.025f,-.013f});clean[y*W+x]=p;
            float n=test==20?0:std::sqrt(variance)*normal(rng);
            in[y*W+x]={p[0]+n,p[1]+n,p[2]+n};
        }
        Model model{meanVariance,1};if(test==16)model={};if(test==19)model.varianceY*=.25f;
        Image out=engine(in,model);if(out.size()!=in.size())throw std::runtime_error("output size");
        auto before=metrics(in,clean),after=metrics(out,clean);
        double rg=0,bg=0,change=0,changePower=0;
        for(size_t i=0;i<out.size();++i) {
            auto a=chroma::opponent(in[i]),b=chroma::opponent(out[i]);
            rg=std::max(rg,double(std::abs(a[1]-b[1])));bg=std::max(bg,double(std::abs(a[2]-b[2])));
            change=std::max(change,double(std::abs(a[0]-b[0])));changePower+=std::pow(a[0]-b[0],2);
        }
        bool ok=rg<1e-6&&bg<1e-6;
        if(test==0||test==1||test==13)ok &= after.mse<before.mse*.85 && after.mse>before.mse*.25;
        if(test>=3&&test<=12||test==14||test==18)ok &= structureAccepted(before,after,test==14?.10:.05);
        if(test==3||test==4)ok &= std::abs(after.amplitude-before.amplitude)<.03*std::abs(before.amplitude) &&
            std::abs(after.position-before.position)<.15 && after.spread<before.spread+.15;
        if(test==5||test==6) {
            double a=0,b=0,wa=0,wb=0;
            for(int y=4;y<H-4;++y)for(int x=W/2-3;x<W/2+5;++x){int i=y*W+x;
                double da=Y(in[i])-.01,db=Y(out[i])-.01;
                if(x>=W/2&&x<W/2+(test==5?1:2)){a+=da;b+=db;}
                wa+=std::max(0.0,da)*std::abs(x-W/2);wb+=std::max(0.0,db)*std::abs(x-W/2);
            }
            ok &= std::abs(b/a-1)<.05 && wb/std::max(.00001,b)<wa/std::max(.00001,a)+.1;
            report<<"lineAmplitudeRetention="<<b/a<<" lineWidthBefore="<<wa/a<<" lineWidthAfter="<<wb/b<<'\n';
        }
        if(test==2||test==17||test==20)ok &= change<1e-6;
        if(test==15)ok &= std::abs(Y(out[(H/2)*W+W/2])-Y(in[(H/2)*W+W/2]))<.001f;
        if(test==16)ok &= std::memcmp(in.data(),out.data(),in.size()*sizeof(Pixel))==0;
        if(test==18)singleChange=changePower;
        if(test==19)ok &= changePower<singleChange;
        for(auto p:out)ok &= chroma::finite(p);
        pass &= ok;
        report<<names[test]<<" pass="<<ok<<" noiseMseBefore="<<before.mse<<" noiseMseAfter="<<after.mse
            <<" mseRatio="<<after.mse/std::max(1e-30,before.mse)<<" projectionBefore="<<before.projection
            <<" projectionAfter="<<after.projection<<" correlationBefore="<<before.correlation<<" correlationAfter="<<after.correlation
            <<" orientationBefore="<<before.gradientAgreement<<" orientationAfter="<<after.gradientAgreement
            <<" edgeAmplitudeBefore="<<before.amplitude<<" edgeAmplitudeAfter="<<after.amplitude
            <<" edgePositionBefore="<<before.position<<" edgePositionAfter="<<after.position
            <<" edgeSpreadBefore="<<before.spread<<" edgeSpreadAfter="<<after.spread
            <<" maxRgError="<<rg<<" maxBgError="<<bg<<" maxYChange="<<change<<'\n';
    }
    Metrics a,b;a.mse=b.mse=1;a.projection=1;b.projection=.8;
    pass &= !structureAccepted(a,b,.05);
    report<<"allPassed="<<(pass?"true":"false")<<'\n';return report.str();
}
}
