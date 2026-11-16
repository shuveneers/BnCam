#include "../../main/cpp/SingleFrameGuidedLuma.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <limits>
#include <random>
#include <vector>

using namespace bncam;
using P=sf2::Pixel;

static bool close(float a,float b,float tol=2e-6f) {
    return std::abs(a-b)<=tol;
}

int main() {
    constexpr int W=112,H=112;
    const luma::Model model{.000040f,1.f};
    std::mt19937 rng(417);
    std::normal_distribution<float> gaussian(0.f,1.f);
    std::vector<P> source(W*H),processed(W*H);
    auto read=[&](int x,int y)->P {
        return source[static_cast<std::size_t>(std::clamp(y,0,H-1))*W+
                      static_cast<std::size_t>(std::clamp(x,0,W-1))];
    };
    const auto shape=[](int,int){return 1.f;};
    const auto channel=[](P rgb) {return chroma::opponent(rgb);};
    double mseBefore=0,mseAfter=0,maxChromaChange=0;
    int pixels=0,engaged=0;
    // Sensor-style independent luma noise + frozen nonzero chroma opponents.
    for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
        const float truth=.025f;
        const float sample=truth+gaussian(rng)*std::sqrt(model.varianceY);
        source[y*W+x]=chroma::rgb({sample,.012f,-.018f});
    }
    for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
        const auto p=sf2::guidedFine(x,y,model,source[y*W+x],read,shape);
        assert(chroma::finite(p.pixel));
        assert(p.fineAuthority>=0 && p.fineAuthority<=.78001f);
        assert(p.coarseAuthority==0);
        assert(p.structure>=0 && p.structure<=1.00001f);
        processed[y*W+x]=p.pixel;
        if(p.fineAuthority>0.00001f)++engaged;
        auto before=channel(source[y*W+x]);
        auto after=channel(p.pixel);
        maxChromaChange=std::max(maxChromaChange,
            double(std::abs(before[1]-after[1])+std::abs(before[2]-after[2])));
        if(x>=5&&y>=5&&x<W-5&&y<H-5) {
            mseBefore+=(before[0]-.025f)*(before[0]-.025f);
            mseAfter+=(after[0]-.025f)*(after[0]-.025f);
            ++pixels;
        }
    }
    assert(engaged>W*H/2 && pixels>0);
    assert(mseAfter < .91*mseBefore); // synthetic reduction, no phone quality claim
    assert(maxChromaChange < 3e-6);

    // The result is ADDITIVE in RGB, preserving post-chroma opponent differences,
    // not recomputing/changing the original post-chroma RGB via a second chroma filter.
    {
        const P post{.7f,.4f,.2f};
        auto p=sf2::guidedFine(30,30,model,post,read,shape).pixel;
        assert(close(p[0]-post[0],p[1]-post[1]));
        assert(close(p[1]-post[1],p[2]-post[2]));
    }
    // A constant Y field, with unrelated chroma opponents, is pixel-identical.
    {
        std::fill(source.begin(),source.end(),chroma::rgb({.30f,.035f,-.028f}));
        const P original{.42f,.33f,.27f};
        auto p=sf2::guidedFine(35,35,model,original,read,shape);
        assert(p.pixel==original);
        assert(p.coarseAuthority==0);
    }

    // Strong step edges and isolated thin lines should retain most contrast.
    // Device RAW validation will still be required for low-contrast/real detail.
    for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
        const float truth=x<W/2 ? .035f : .31f;
        source[y*W+x]=chroma::rgb({truth,.017f,-.007f});
    }
    const auto nearLeft=channel(sf2::guidedFine(W/2-1,H/2,model,
            read(W/2-1,H/2),read,shape).pixel)[0];
    const auto nearRight=channel(sf2::guidedFine(W/2,H/2,model,
            read(W/2,H/2),read,shape).pixel)[0];
    assert(nearRight-nearLeft > .95f*(.31f-.035f));

    for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
        const float truth=(x==W/2 || y==H/2) ? .13f : .03f;
        source[y*W+x]=chroma::rgb({truth,.005f,-.009f});
    }
    const auto thin=sf2::guidedFine(W/2,H/2-8,model,read(W/2,H/2-8),read,shape);
    const auto thinY=channel(thin.pixel)[0];
    assert(thinY>.12f);
    assert(thin.structure>=0 && thin.structure<=1);

    // Exact bypass on invalid model, invalid source, invalid shape or underflow
    // physical variance. No NaN may leak into the post-chroma output.
    const P post{.31f,.29f,.24f};
    for(const auto m:{luma::Model{},luma::Model{NAN,1.f},
                       luma::Model{.01f,-1.f},luma::Model{.01f,1.01f},
                       luma::Model{std::numeric_limits<float>::infinity(),1.f},
                       luma::Model{1.e-16f,1.f}}) {
        assert(sf2::guidedFine(31,31,m,post,read,shape).pixel==post);
    }
    assert(sf2::guidedFine(31,31,model,post,read,
        [](int,int){return std::numeric_limits<float>::quiet_NaN();}).pixel==post);
    assert(sf2::guidedFine(31,31,model,post,read,
        [](int,int){return 0.f;}).pixel==post);
    assert(sf2::guidedFine(31,31,model,post,
        [](int,int)->P{return {NAN,0,0};},shape).pixel==post);
    assert(sf2::guidedFine(31,31,model,P{NAN,0.f,0.f},read,shape).pixel[0] !=
            sf2::guidedFine(31,31,model,P{NAN,0.f,0.f},read,shape).pixel[0]);
    return 0;
}
