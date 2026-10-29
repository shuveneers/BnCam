#include "GradientHighlightRecovery.h"
#include "SoftHighlightTone.h"
#include <cassert>
#include <iostream>
using namespace bncam::highlight;
float distance(Rgb a,Rgb b){return std::max({std::abs(a.r-b.r),std::abs(a.g-b.g),std::abs(a.b-b.b)});}
int main(){
    // Every CFA orientation identifies the measured red plane independently.
    const int reds[4]={0,1,2,3};
    for(unsigned pattern=0;pattern<4;++pattern){
        std::array<float,4> s{1,1,1,1};s[reds[pattern]]=.6f;
        auto q=cellChannelReliability(s,pattern);
        assert(q.r==1 && q.g==0 && q.b==0);
        assert(defaultRawCellConfidence(s)==.25f);
    }
    // Two censored planes: only the measured red plane carries the cloud wave.
    float minY=100,maxY=0;
    for(int x=0;x<256;++x){
        const float r=.55f+.07f*std::sin(x*.06f);
        Rgb in{r,1,1},q{1,0,0};
        auto rgb=[&](int,int){return in;};auto reliability=[](int,int){return Rgb{1,0,0};};
        auto out=reconstructHighlight(x,0,in,q,{3,1,1.5f},rgb,reliability);
        assert(out.r==in.r && out.g>=1 && out.b>=1);
        auto display=bncam::tone::softHighlightTone({out.r*3,out.g,out.b*1.5f});
        const float y=luma(display);minY=std::min(minY,y);maxY=std::max(maxY,y);
    }
    assert(maxY-minY>.018f); // Surviving measured wave must reach the display.
    for(Rgb in:{Rgb{.62f,.43f,.32f},Rgb{.15f,.25f,.8f},Rgb{.9f,.92f,.95f}}){
        auto rgb=[&](int,int){return in;};
        auto out=reconstructHighlight(0,0,in,{1,1,1},{2,1,1.5f},rgb,[](int,int){return Rgb{1,1,1};});
        assert(distance(out,in)==0); // Skin, coloured light, sun reflection below sensor loss.
    }
    Rgb fully{1,1,1};
    auto out=reconstructHighlight(0,0,fully,{0,0,0},{3,1,1.5f},
        [](int,int){return Rgb{.4f,.7f,.8f};},[](int,int){return Rgb{1,1,1};});
    assert(distance(out,{1,3,2})==0); // Neutral WB-balanced floor; no neighbouring cloud texture.
    assert(chromaticReliability({1,0,0})==0 && chromaticReliability({1,1,0})==1);
    float previous=-1;
    for(int i=0;i<=1000;++i){
        const float r=.85f+.15f*i/1000.f;Rgb raw{r,1,1};
        const float confidence=cellChannelReliability({r,1,1,1},0).r;
        auto recovered=reconstructHighlight(0,0,raw,{confidence,0,0},{3,1,1.5f},
            [&](int,int){return raw;},[&](int,int){return Rgb{confidence,0,0};});
        const float y=luma({recovered.r*3,recovered.g,recovered.b*1.5f});
        assert(y>=previous-1e-6f);previous=y;
        assert(std::abs(y-r*3)<.00001f); // No dark rim as the last plane reaches saturation.
    }
    // Colour-border veto: a reliable green/red ratio identifies incompatible neighbours.
    Rgb center{.6f,.6f,1},q{1,1,0};
    auto neutral=reconstructHighlight(0,0,center,q,{2,1,1},
        [](int,int){return Rgb{.6f,.6f,1};},[](int,int){return Rgb{1,1,0};});
    auto border=reconstructHighlight(0,0,center,q,{2,1,1},
        [](int,int){return Rgb{.6f,.1f,.8f};},[](int,int){return Rgb{1,1,1};});
    assert(distance(border,neutral)<.001f);
    // A coloured lamp/reflection losing one plane keeps its two measured planes.
    Rgb lamp{.85f,.45f,1};
    auto recoveredLamp=reconstructHighlight(0,0,lamp,{1,1,0},{2,1,1.5f},
        [](int,int){return Rgb{.68f,.36f,.9f};},[](int,int){return Rgb{1,1,1};});
    assert(recoveredLamp.r==lamp.r && recoveredLamp.g==lamp.g && recoveredLamp.b>=1);
    assert(recoveredLamp.b<1.5f); // No unbounded coloured streak from the neighbourhood.
    // Continuous gamut shoulder preserves both luma and opponent hue direction.
    float last=-1;
    for(int i=0;i<10000;++i){
        float t=.1f+i*.0001f;float lower=.7f-(.2126f/.7874f)*t;Rgb input{.7f+t,lower,lower};
        auto safe=bncam::tone::softHighlightGamut(input);
        assert(std::min({safe.r,safe.g,safe.b})>=0 && std::max({safe.r,safe.g,safe.b})<=1);
        assert(std::abs(luma(safe)-luma(input))<3e-7f);
        float chroma=safe.r-safe.g;assert(chroma>=last-1e-6f);last=chroma;
        assert(std::abs(safe.g-safe.b)<1e-7f);
    }
    std::cout<<"GRADIENT_HIGHLIGHT_PASS cloud_display_range="<<maxY-minY<<"\n";
}
