#include "../../main/cpp/SingleFrameGuidedLuma.h"
#include "../../main/cpp/SingleFrameIntegrationQualification.h"
#include <algorithm>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <random>
#include <vector>

int main() {
    using bncam::sf2::Pixel;
    namespace sf=bncam::sf2;
    namespace c=bncam::chroma;
    constexpr int W=96,H=96;
    const bncam::luma::Model model{.00004f,1.f};
    const auto shape=[](int,int){return 1.f;};
    std::vector<Pixel> source(W*H);
    auto read=[&](int x,int y)->Pixel {
        return source[std::clamp(y,0,H-1)*W+std::clamp(x,0,W-1)];
    };
    const auto Y=[](const Pixel& p){return c::opponent(p)[0];};
    std::mt19937 rng(8108);
    std::normal_distribution<float> gaussian(0.f,1.f);
    auto render=[&](bool balanced) {
        std::vector<Pixel> out(W*H);
        double authoritySum=0;
        for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
            const auto p=sf::guidedFine(x,y,model,source[y*W+x],read,shape,true,balanced);
            assert(c::finite(p.pixel));
            assert(p.fineAuthority>=0.f && p.fineAuthority<=.780001f);
            assert(p.coarseAuthority==0.f);
            assert(p.structure>=0.f && p.structure<=1.000001f);
            const auto before=c::opponent(source[y*W+x]);
            const auto after=c::opponent(p.pixel);
            assert(std::abs(before[1]-after[1])<2e-6f);
            assert(std::abs(before[2]-after[2])<2e-6f);
            authoritySum+=p.fineAuthority;
            out[y*W+x]=p.pixel;
        }
        return std::make_pair(out,authoritySum/double(W*H));
    };

    // Independent synthetic flat-field noise with exact target.
    // More smoothing is useful only if it lowers error to the known signal.
    constexpr float flatTruth=.045f;
    for(auto& p:source) {
        const float noisy=flatTruth+gaussian(rng)*std::sqrt(model.varianceY);
        p=c::rgb({noisy,.008f,-.013f});
    }
    const auto [fine, fineAuthority]=render(false);
    const auto [balanced, balancedAuthority]=render(true);
    double mseInput=0,mseFine=0,mseBalanced=0;
    int pixels=0,moreFineAuthority=0;
    for(int y=5;y<H-5;++y)for(int x=5;x<W-5;++x) {
        const int i=y*W+x;
        const double a=Y(source[i])-flatTruth;
        const double f=Y(fine[i])-flatTruth;
        const double b=Y(balanced[i])-flatTruth;
        mseInput+=a*a;mseFine+=f*f;mseBalanced+=b*b;
        const auto original=sf::guidedFine(x,y,model,source[i],read,shape);
        const auto existing=sf::guidedFine(x,y,model,source[i],read,shape,true,false);
        assert(original.pixel==existing.pixel);
        if(std::abs(Y(balanced[i])-Y(source[i])) > std::abs(Y(fine[i])-Y(source[i]))+1.e-8f)
            ++moreFineAuthority;
        ++pixels;
    }
    assert(mseFine<mseInput*.92); // existing Fine noise reduction must remain
    assert(mseBalanced<mseInput*.92);
    assert(mseBalanced<mseFine*1.01); // no meaningful measured flat-field regression
    assert(moreFineAuthority>pixels/2); // Balanced makes a nontrivial flat-field change

    // Known synthetic texture: continuous dark thread on a noisier fabric.
    rng.seed(8210);
    for(int y=0;y<H;++y)for(int x=0;x<W;++x) {
        const float truth=.055f+(x==W/2?.045f:0.f);
        source[y*W+x]=c::rgb({truth+gaussian(rng)*std::sqrt(model.varianceY),
                             .008f,-.013f});
    }
    const auto [fineTextured, ignoredFineAuthority]=render(false);
    const auto [balancedTextured, ignoredBalancedAuthority]=render(true);
    auto contrast=[&](const std::vector<Pixel>& image) {
        double line=0,background=0;
        for(int y=8;y<H-8;++y) {
            line+=Y(image[y*W+W/2]);
            background+=.5*(Y(image[y*W+W/2-4])+Y(image[y*W+W/2+4]));
        }
        return (line-background)/double(H-16);
    };
    const double lineFine=contrast(fineTextured);
    const double lineBalanced=contrast(balancedTextured);
    assert(lineFine>.95*.045);
    assert(lineBalanced>.95*lineFine); // protects true texture modulation
    assert(lineBalanced>.94*.045);

    // Exact bypass on constant, missing/invalid physical noise, and invalid shape.
    // Use the frozen reference's exactly representable constant-field case;
    // other constant fields may differ at float roundoff level.
    std::fill(source.begin(),source.end(),c::rgb({.30f,.035f,-.028f}));
    const Pixel original{.42f,.33f,.27f};
    assert(sf::guidedFine(32,32,model,original,read,shape,true,true).pixel==original);
    assert(sf::guidedFine(32,32,{},original,read,shape,true,true).pixel==original);
    assert(sf::guidedFine(32,32,model,original,read,
        [](int,int){return std::numeric_limits<float>::quiet_NaN();},true,true).pixel==original);

    // Stage 3 is a separate oracle; no unrequested product stage is enabled.
    const auto fixture=sf::qualification::fixture();
    const auto old=sf::qualification::cpuReference(fixture,bncam::luma::Stage::GUIDED_V2);
    const auto newer=sf::qualification::cpuReference(fixture,bncam::luma::Stage::GUIDED_V2_BALANCED);
    assert(newer.size()==old.size());
    std::size_t changed=0;
    for(std::size_t i=0;i<newer.size();++i)
        if(std::abs(newer[i][0]-old[i][0])>1e-8f)++changed;
    assert(changed>100);
    assert(bncam::luma::selectedStage.load()==bncam::luma::Stage::OFF);

    std::cout<<"SF2-D08 flat MSE: input="<<mseInput/pixels
             <<" fine="<<mseFine/pixels<<" balanced="<<mseBalanced/pixels
             <<"; fineAuthority="<<fineAuthority
             <<"; balancedAuthority="<<balancedAuthority
             <<"; threadContrastFine="<<lineFine
             <<"; threadContrastBalanced="<<lineBalanced
             <<"; changedFixturePixels="<<changed<<"\n";
    return 0;
}
