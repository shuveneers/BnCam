#pragma once
#include "../PhysicalChromaDenoise.h"
#include <functional>
#include <iomanip>
#include <random>
#include <sstream>
#include <stdexcept>
#include <vector>

namespace bncam::chroma::test {
constexpr int W=64, H=64;
using Image=std::vector<Pixel>;
inline Pixel at(const Image& a,int x,int y) {
    return a[std::clamp(y,0,H-1)*W+std::clamp(x,0,W-1)];
}
inline Image reference(const Image& in,Model m) {
    Image out(in.size());
    for(int y=0;y<H;++y) for(int x=0;x<W;++x)
        out[y*W+x]=filter(x,y,m,[&](int xx,int yy){return at(in,xx,yy);},[](int,int){return 1.f;}).pixel;
    return out;
}
struct Metrics {
    double max=0, mean=0, rms=0, hf=1, edge=1, hfBefore=0, hfAfter=0, input=0, output=0, ratio=1, chromaError=0;
};
inline Metrics metrics(const Image& in,const Image& out,const Image& truth) {
    Metrics m; double h0=0,h1=0,e0=0,e1=0;
    for(int y=0;y<H;++y) for(int x=0;x<W;++x) {
        int i=y*W+x; auto p=opponent(in[i]),q=opponent(out[i]),t=opponent(truth[i]);
        double d=double(q[0])-p[0]; m.max=std::max(m.max,std::abs(d)); m.mean+=std::abs(d); m.rms+=d*d;
        for(int c=1;c<3;++c) { m.input+=std::pow(p[c]-t[c],2); m.output+=std::pow(q[c]-t[c],2);
            m.chromaError=std::max(m.chromaError,double(std::abs(p[c]-q[c]))); }
        if(x<W-1) { h0+=std::pow(p[0]-opponent(at(in,x+1,y))[0],2); h1+=std::pow(q[0]-opponent(at(out,x+1,y))[0],2); }
        if(y<H-1) { h0+=std::pow(p[0]-opponent(at(in,x,y+1))[0],2); h1+=std::pow(q[0]-opponent(at(out,x,y+1))[0],2); }
        if(x==W/2-1) { e0+=opponent(at(in,x+1,y))[0]-p[0]; e1+=opponent(at(out,x+1,y))[0]-q[0]; }
    }
    m.mean/=in.size(); m.rms=std::sqrt(m.rms/in.size());
    // A numerical-zero energy denominator has no meaningful ratio. Report the
    // identity convention explicitly; absolute Y errors remain enforced on these frames.
    m.hfBefore=h0; m.hfAfter=h1; m.hf=h0>1e-10?h1/h0:1; m.edge=std::abs(e0)>1e-6?e1/e0:1;
    m.input/=2*in.size(); m.output/=2*in.size(); m.ratio=m.input>1e-15?m.output/m.input:1;
    return m;
}
using Engine=std::function<Image(const Image&,Model)>;
inline std::string run(Engine engine=reference) {
    const char* names[]={"neutral_noise","dark_read_noise","bright_high_snr","bw_edge","low_edge",
        "red_green","blue_yellow","equal_Y_colour_edge","checkerboard","one_two_pixel_lines",
        "textile","text_strokes","high_snr_chromatic_texture","correlated","strong_off_diagonal",
        "missing_model","near_singular","LF_cloud","constant_colour","nonfinite_model"};
    std::ostringstream report; report<<std::setprecision(10); bool pass=true;
    for(int test=0;test<20;++test) {
        std::mt19937 rng(7123); std::normal_distribution<float> normal(0,1);
        Model m{0.000025f,0.0004f,0.0004f,0};
        if(test==2||test==12) m={1e-10f,2e-9f,2e-9f,0};
        if(test==13) m.cross=0.0002f;
        if(test==14) m.cross=0.000399f;
        if(test==16) m.cross=0.0004f;
        Image in(W*H),truth(W*H);
        for(int y=0;y<H;++y) for(int x=0;x<W;++x) {
            float Y=test==2?.8f:(test==0?.1f:.03f), cr=0,cb=0;
            if(test==3) Y=x<W/2?.03f:.9f;
            if(test==4) Y=x<W/2?.10f:.11f;
            if(test==5||test==6) { auto o=opponent(test==5?(x<W/2?Pixel{.7f,.1f,.1f}:Pixel{.1f,.7f,.1f}):
                (x<W/2?Pixel{.1f,.1f,.7f}:Pixel{.7f,.7f,.1f})); Y=o[0]; cr=o[1];cb=o[2]; }
            if(test==7) {Y=.4f;cr=x<W/2?.4f:-.4f;cb=x<W/2?-.2f:.2f;}
            if(test==8) Y=(x+y)%2?.8f:.1f;
            if(test==9) Y=(x%7==0||x%7==3||x%7==4)?.6f:.03f;
            if(test==10) Y=.2f+.07f*std::sin(float(x*2+y))+.04f*std::cos(float(y*3-x));
            if(test==11) Y=((x%9==0)||(y%11==0&&x%9<6))?.65f:.1f;
            if(test==12) {Y=.4f;cr=(x+y)%2?.12f:-.12f;cb=x%3==0?.15f:-.07f;}
            if(test==18) {Y=.3f;cr=.2f;cb=-.1f;}
            truth[y*W+x]=rgb({Y,cr,cb});
            float n0=normal(rng),n1=normal(rng),rho=m.cross/std::sqrt(m.rg*m.bg);
            if((test>=5&&test<=7)||test==12||test==18) n0=n1=0;
            float nr=std::sqrt(m.rg)*n0,nb=std::sqrt(m.bg)*(rho*n0+std::sqrt(std::max(0.f,1-rho*rho))*n1);
            if(test==17) {nr=.015f*std::sin(float(x)*.6f)*std::sin(float(y)*.6f);nb=-nr;}
            in[y*W+x]=rgb({Y,cr+nr,cb+nb});
        }
        if(test==15) m={};
        if(test==19) m.y=std::numeric_limits<float>::quiet_NaN();
        Image out=engine(in,m); if(out.size()!=in.size()) throw std::runtime_error("GPU output size");
        auto v=metrics(in,out,truth);
        bool ok=detailAccepted(v.max,1,v.edge,v.hf);
        if(test==0||test==1||test==13||test==14||test==16) ok &= v.ratio<.8;
        if(test==17) ok &= v.ratio<.99;
        if(test==2) ok &= v.chromaError<1e-6;
        if(test==5||test==6||test==7||test==12||test==18) ok &= v.chromaError<1e-6;
        if(test==15||test==19) ok &= in==out;
        for(auto p:out) ok &= finite(p);
        pass &= ok;
        report<<names[test]<<" pass="<<ok<<" inputChromaVariance="<<v.input<<" outputChromaVariance="<<v.output
              <<" chromaVarianceRatio="<<v.ratio<<" maxLumaError="<<v.max<<" meanLumaError="<<v.mean
              <<" rmsLumaError="<<v.rms<<" hfRatioDefined="<<(v.hfBefore>1e-10)<<" hfYEnergyBefore="<<v.hfBefore<<" hfYEnergyAfter="<<v.hfAfter<<" edgeAmplitudeRatio="<<v.edge<<" hfYEnergyRatio="<<v.hf<<'\n';
    }
    // Explicitly reject a tempting chroma improvement when ANY detail invariant fails.
    pass &= !detailAccepted(1e-3,1,1,1) && !detailAccepted(0,1,.99,1) && !detailAccepted(0,1,1,.99);
    report<<"allPassed="<<(pass?"true":"false")<<'\n';
    return report.str();
}
}
