#pragma once
#include "PhysicalChromaValidation.h"

namespace bncam::chroma::detailtest {
using test::Image;
using test::Engine;
// Identity-colour CPU/GPU tests: numerical error must consume less than 1/32
// of one UNORM8 sRGB code, using the maximum sRGB transfer slope (12.92).
// WB/CCM-domain tests keep their separate 2e-6 transformed-domain limit.
constexpr double kIdentityGpuTolerance=1.0/(32.0*255.0*12.92);
inline double projection(const Image& image,const Image& truth,int channel) {
    double a=0,b=0,aa=0,ab=0;int count=0;
    for(int y=4;y<test::H-4;++y)for(int x=4;x<test::W-4;++x) {
        int i=y*test::W+x;double t=opponent(truth[i])[channel],p=opponent(image[i])[channel];
        a+=t;b+=p;aa+=t*t;ab+=t*p;++count;
    }
    return (ab-a*b/count)/std::max(1e-30,aa-a*a/count);
}
inline std::array<double,2> mse(const Image& image,const Image& truth) {
    std::array<double,2> error{};int count=0;
    for(int y=4;y<test::H-4;++y)for(int x=4;x<test::W-4;++x) {
        int i=y*test::W+x;auto p=opponent(image[i]),q=opponent(truth[i]);
        for(int c=0;c<2;++c)error[c]+=std::pow(p[c+1]-q[c+1],2);++count;
    }
    for(auto& e:error)e/=count;return error;
}
inline std::string run(Engine engine=test::reference) {
    bool pass=true;std::ostringstream report;report<<std::setprecision(10);
    // Ground truth is POST_DEMOSAIC RGB, not a Bayer sample masquerading as RGB.
    // Separate CFA reconstruction and full-render diagnostics audit that boundary.
    for(int channel=1;channel<=2;++channel)for(int pattern=0;pattern<9;++pattern)
    for(float amplitude:{.002f,.02f,.06f})for(float noiseRatio:{0.f,.25f,1.f}) {
        Image truth(test::W*test::H),in(truth.size());
        std::mt19937 rng(1729+pattern);std::normal_distribution<float> normal(0,1);
        Model model{.000025f,.0004f,.0004f,0};model.adaptiveSpatialNoise=true;
        for(int y=0;y<test::H;++y)for(int x=0;x<test::W;++x) {
            float feature=0;
            if(pattern<3)feature=(x>=32&&x<32+(1<<pattern))?1.f:0.f;
            if(pattern==3)feature=y>=32&&y<34?1.f:0.f;
            if(pattern==4)feature=x>=32?1.f:0.f;
            if(pattern==5)feature=.5f*std::sin(.45f*x);
            if(pattern==6)feature=x==y?1.f:0.f;
            if(pattern==7)feature=x+y==64?1.f:0.f;
            if(pattern==8)feature=x==32?1.f:0.f;
            float Y=.03f+(pattern==8?.006f*std::sin(.9f*x)*std::cos(.8f*y):0.f);
            Pixel p{Y,channel==1?amplitude*feature:0.f,channel==2?amplitude*feature:0.f};
            int i=y*test::W+x;truth[i]=rgb(p);
            p[1]+=.02f*noiseRatio*normal(rng);p[2]+=.02f*noiseRatio*normal(rng);
            p[0]+=.005f*noiseRatio*normal(rng);in[i]=rgb(p);
        }
        auto baseline=engine(in,model);model.protectCoherentDetail=true;auto out=engine(in,model);
        double before=projection(baseline,truth,channel),after=projection(out,truth,channel);
        auto e0=mse(baseline,truth),e1=mse(out,truth);auto v=test::metrics(in,out,truth);
        bool ok=detailAccepted(v.max,1,v.edge,v.hf);
        if(noiseRatio==0)ok &= (pattern==8||after>.99) && after>=before-1e-5;
        else {
            // Sampling uncertainty of projection at 0.1-sigma signal cannot be
            // treated as deterministic attenuation. Five percent of its known
            // standard error is a strict paired-realization tolerance.
            double mean=0,energy=0;int count=0;
            for(int y=4;y<test::H-4;++y)for(int x=4;x<test::W-4;++x) {
                double t=opponent(truth[y*test::W+x])[channel];mean+=t;energy+=t*t;++count;
            }
            energy-=mean*mean/count;
            double se=.02*noiseRatio/std::sqrt(std::max(1e-30,energy));
            ok &= std::abs(after-1)<=std::abs(before-1)+std::max(.01,.05*se);
            ok &= e1[0]<=e0[0]*1.03+1e-12&&e1[1]<=e0[1]*1.03+1e-12;
        }
        pass &= ok;
        report<<"detail channel="<<channel<<" pattern="<<pattern<<" amplitude="<<amplitude
              <<" noiseRatio="<<noiseRatio<<" baselineProjection="<<before<<" candidateProjection="<<after
              <<" baselineMseRG="<<e0[0]<<" candidateMseRG="<<e1[0]<<" baselineMseBG="<<e0[1]
              <<" candidateMseBG="<<e1[1]<<" maxYError="<<v.max<<" pass="<<ok<<'\n';
    }
    for(float sigma:{.002f,.02f,.06f})for(float rho:{0.f,.5f,.95f})for(unsigned seed=1;seed<=4;++seed) {
        Image truth(test::W*test::H,rgb({.03f,.015f,-.01f})),in(truth.size());
        std::mt19937 rng(seed);std::normal_distribution<float> normal(0,1);
        for(auto& p:in) {float a=normal(rng),b=normal(rng);p=rgb({.03f,.015f+sigma*a,-.01f+sigma*(rho*a+std::sqrt(1-rho*rho)*b)});}
        Model m{.000025f,sigma*sigma,sigma*sigma,rho*sigma*sigma};m.adaptiveSpatialNoise=true;
        auto baseline=engine(in,m);m.protectCoherentDetail=true;auto out=engine(in,m);
        auto e0=mse(baseline,truth),e1=mse(out,truth);
        bool ok=e1[0]<=e0[0]*1.02&&e1[1]<=e0[1]*1.02;pass &= ok;
        report<<"flat sigma="<<sigma<<" rho="<<rho<<" seed="<<seed<<" mseRatioRG="<<e1[0]/e0[0]
              <<" mseRatioBG="<<e1[1]/e0[1]<<" pass="<<ok<<'\n';
    }
    report<<"detailPassed="<<(pass?"true":"false")<<'\n';return report.str();
}
}
