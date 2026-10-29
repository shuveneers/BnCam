#include "SoftHighlightTone.h"
#include <cassert>
#include <set>
#include <iostream>
using bncam::highlight::Rgb;
using bncam::tone::softHighlightTone;
float srgb(float v) { return v<=.0031308f ? 12.92f*v : 1.055f*std::pow(v,1.f/2.4f)-.055f; }
int main() {
    for (float v:{0.f,.01f,.1f,.25f,.40f}) {
        auto c=softHighlightTone({v,v,v});
        assert(std::abs(c.r-v)<1e-7f && c.r==c.g && c.g==c.b);
    }
    // Frozen previous single-shot output below the new high-radiance transition.
    const float normalInput[]={.5f,.7f,.9f,1.f};
    const float normalOutput[]={.48734375f,.65217391f,.75757576f,.78947368f};
    for(int i=0;i<4;++i)assert(std::abs(softHighlightTone({normalInput[i],normalInput[i],normalInput[i]}).r-normalOutput[i])<2e-7f);
    float last=-1.f;
    std::set<int> codes;
    for(int i=0;i<=10000;++i) {
        float v=.4f+3.6f*i/10000.f;
        auto c=softHighlightTone({v,v,v});
        assert(c.r>last && c.r<1.f && c.r==c.g && c.g==c.b);
        last=c.r;
        if(v>=.8f && v<=2.f) codes.insert(int(std::lround(srgb(c.r)*255.f)));
    }
    assert(codes.size()>=25); // Actual display-code room, not only float monotonicity.
    assert(*codes.rbegin()<250);
    for(float peak:{.44f,.60f,.64f,1.04f,1.14f,2.44f}) {
        auto a=softHighlightTone({peak-1e-5f,peak-1e-5f,peak-1e-5f});
        auto b=softHighlightTone({peak+1e-5f,peak+1e-5f,peak+1e-5f});
        assert(b.r>a.r && b.r-a.r<3e-5f);
    }
    for(float peak:{1.04f,1.14f,2.44f}) {
        const float h=.0002f;
        float a=softHighlightTone({peak-h,peak-h,peak-h}).r;
        float b=softHighlightTone({peak,peak,peak}).r;
        float c=softHighlightTone({peak+h,peak+h,peak+h}).r;
        assert(std::abs((b-a)/h-(c-b)/h)<.006f); // C1 at both shoulder joins.
    }
    for(float peak:{1.5f,2.f}) {
        const float h=.01f;
        const float a=softHighlightTone({peak,peak,peak}).r;
        const float b=softHighlightTone({peak+h,peak+h,peak+h}).r;
        assert((b-a)/h>.119f); // Reserve actual gradient slope throughout the cloud corridor.
    }
    for(Rgb in: {Rgb{2.f,1.5f,.9f},Rgb{.8f,1.1f,2.f},Rgb{0.f,3.f,1.f}}) {
        auto c=softHighlightTone(in);
        assert(bncam::highlight::finiteRgb(c));
        assert(bncam::highlight::luma(c)<1.f && std::min({c.r,c.g,c.b})>=0.f);
        c=bncam::tone::softHighlightGamut(c);
        assert(std::max({c.r,c.g,c.b})<=1.f && std::min({c.r,c.g,c.b})>=0.f);
        if(in.r>in.g) assert(c.r>c.g);
        if(in.b>in.g) assert(c.b>c.g);
    }
    const float target=bncam::highlight::luma(softHighlightTone({1.5f,1.5f,1.5f}));
    for(int i=0;i<100;++i){
        const float red=1.5f+i*.01f, other=1.5f-(red-1.5f)*(.2126f/.7874f);
        auto toned=bncam::tone::softHighlightGamut(softHighlightTone({red,other,other}));
        assert(std::abs(bncam::highlight::luma(toned)-target)<3e-7f);
    } // High-radiance chroma changes cannot create brightness steps.
    std::cout<<"SOFT_HIGHLIGHT_TONE_PASS displayCodes="<<codes.size()<<'\n';
}
