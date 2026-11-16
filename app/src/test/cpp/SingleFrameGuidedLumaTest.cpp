#include "../../main/cpp/SingleFrameGuidedLuma.h"
#include <cassert>
#include <cmath>
#include <random>
#include <vector>
int main() {
    using namespace bncam;
    constexpr int W=96,H=96;
    using P=sf2::Pixel;
    std::vector<P> source(W*H),out(W*H);
    std::mt19937 rng(417);std::normal_distribution<float> n(0,1);
    constexpr float variance=.00004f;
    double noiseIn=0,noiseOut=0,chromamax=0;
    for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
        float truth=.025f;
        float z=n(rng)*std::sqrt(variance);
        source[y*W+x]=chroma::rgb({truth+z,.012f,-.018f});
    }
    auto read=[&](int x,int y){return source[std::clamp(y,0,H-1)*W+std::clamp(x,0,W-1)];};
    const auto shape=[](int,int){return 1.f;};
    for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
        int i=y*W+x;
        out[i]=sf2::guidedFine(x,y,{variance,1},source[i],read,shape).pixel;
        auto a=chroma::opponent(source[i]),b=chroma::opponent(out[i]);
        chromamax=std::max(chromamax,double(std::abs(a[1]-b[1])+std::abs(a[2]-b[2])));
        if(x>4&&y>4&&x<W-4&&y<H-4) {
            noiseIn+=(a[0]-.025)*(a[0]-.025);
            noiseOut+=(b[0]-.025)*(b[0]-.025);
        }
    }
    assert(noiseOut<.90*noiseIn);
    assert(chromamax<1e-6);
    const P p{.3f,.2f,.1f};
    auto no=[&](int,int){return p;};
    for(luma::Model model : {luma::Model{},luma::Model{NAN,1},luma::Model{.01f,-1}})
        assert(sf2::guidedFine(5,5,model,p,no,shape).pixel==p);
    assert(sf2::guidedFine(5,5,{.01f,1},p,no,[](int,int){return NAN;}).pixel==p);
    for(auto pixel:out)assert(chroma::finite(pixel));
}
