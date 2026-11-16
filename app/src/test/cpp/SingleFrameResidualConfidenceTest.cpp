#include "../../main/cpp/SingleFrameResidualConfidence.h"
#include <array>
#include <cassert>
#include <algorithm>
#include <cmath>
#include <limits>
#include <random>
#include <vector>
#include <iostream>

using namespace bncam;
using P=sf2::Pixel;
constexpr int W=160,H=128;
const luma::Model model{.000064f,1.f};
static P pix(float y){return chroma::rgb({y,.016f,-.012f});}
static float yOf(P p){return chroma::opponent(p)[0];}
static bool near(float a,float b,float e=2e-6f){return std::abs(a-b)<=e;}

int main(){
    std::vector<P> frame(W*H);
    auto read=[&](int x,int y)->P {
        return frame[static_cast<std::size_t>(std::clamp(y,0,H-1))*W+
                     static_cast<std::size_t>(std::clamp(x,0,W-1))];
    };
    const auto shape=[](int,int){return 1.f;};
    auto check=[&](sf2::ResidualConfidence e) {
        assert(e.valid);
        assert(std::isfinite(e.weight)&&e.weight>=0.f&&e.weight<=1.f);
        assert(e.structureEvidence>=0&&e.structureEvidence<=1);
        assert(e.edgeEvidence>=0&&e.edgeEvidence<=1);
        assert(e.residualSupport>0&&e.residualSupport<=1);
        assert(e.levelAgreement>=.5f&&e.levelAgreement<=1);
        assert(e.demosaicSupport>.0f&&e.demosaicSupport<=1);
    };
    // Flat/noiseless => no spurious line/edge confidence, smooth nonzero weight.
    std::fill(frame.begin(),frame.end(),pix(.12f));
    const auto flat=sf2::residualConfidenceAt(45,48,model,-.004f,-.001f,true,read,shape);
    check(flat);
    assert(flat.structureEvidence==0&&flat.edgeEvidence==0);
    assert(flat.weight>.85f);
    // Physical model uncertainty is NOT blindly counted twice if near-one,
    // but weak predicted confidence may only reduce application.
    const auto uncertainModel=sf2::residualConfidenceAt(45,48,{.000064f,.15f},
            -.004f,-.001f,true,read,shape);
    check(uncertainModel);
    assert(uncertainModel.weight<flat.weight*.4f);
    // AMaZE/Auto Hybrid unknown spatial correlation does not become rho=0.
    const auto adaptive=sf2::residualConfidenceAt(45,48,model,-.004f,-.001f,false,read,shape);
    check(adaptive);
    assert(adaptive.weight<flat.weight);
    assert(adaptive.demosaicSupport==.78f);

    // Directional coherence on thin hair: neither a mere gradient threshold
    // nor a global noise-power statistic; the signal repeats along the line.
    for(int y=0;y<H;++y)for(int x=0;x<W;++x)
        frame[y*W+x]=pix(.12f+(x==80&&y>12&&y<H-12?.022f:0.f));
    const auto hair=sf2::residualConfidenceAt(80,64,model,-.012f,-.004f,true,read,shape);
    const auto hairBackground=sf2::residualConfidenceAt(57,64,model,-.012f,-.004f,true,read,shape);
    check(hair);check(hairBackground);
    assert(hair.structureEvidence>hairBackground.structureEvidence+.25f);
    assert(hair.weight<hairBackground.weight*.75f);
    // A weak line at physical noise scale has reproducible but nonbinary
    // evidence, rather than a sharp threshold transition.
    for(int y=0;y<H;++y)for(int x=0;x<W;++x)
        frame[y*W+x]=pix(.12f+(x==80&&y>12&&y<H-12?.010f:0.f));
    const auto subtle=sf2::residualConfidenceAt(80,64,model,-.008f,-.002f,true,read,shape);
    check(subtle);
    assert(subtle.weight>0.f && subtle.weight<hairBackground.weight);
    // Coherent broad step/silhouette should resist correction on the boundary.
    for(int y=0;y<H;++y)for(int x=0;x<W;++x)
        frame[y*W+x]=pix(x<W/2?.06f:.30f);
    const auto sharp=sf2::residualConfidenceAt(W/2,64,model,-.012f,-.003f,true,read,shape);
    check(sharp);
    assert(sharp.edgeEvidence>.75f);
    assert(sharp.weight<hairBackground.weight*.5f);

    // Textiles/periodic fine detail: no arbitrary colour shift.
    for(int y=0;y<H;++y)for(int x=0;x<W;++x)
        frame[y*W+x]=pix(.11f + (((x/2)+(y/2))%2==0 ? .02f:-.02f));
    const auto textile=sf2::residualConfidenceAt(61,56,model,-.012f,-.003f,true,read,shape);
    check(textile);
    assert(textile.weight<hairBackground.weight);

    // Fine/coarse opposition reduces confidence continuously, including
    // as one candidate approaches zero.
    std::fill(frame.begin(),frame.end(),pix(.12f));
    const auto same=sf2::residualConfidenceAt(50,51,model,-.012f,-.004f,true,read,shape);
    const auto opposite=sf2::residualConfidenceAt(50,51,model,-.012f,+.004f,true,read,shape);
    check(same);check(opposite);
    assert(opposite.levelAgreement<same.levelAgreement);
    assert(opposite.weight<same.weight);

    // Correction significance: surprising >few-sigma correction is gated.
    const auto large=sf2::residualConfidenceAt(50,51,model,-.09f,-.02f,true,read,shape);
    check(large);
    assert(large.residualSupport<same.residualSupport*.3f);
    assert(large.weight<same.weight*.3f);

    // Gate is a scalar; preserves BOTH opponent colour differences exactly.
    const P post{.47f,.36f,.21f};
    const auto gated=sf2::applyResidualConfidence(post,-.007f,-.002f,same);
    assert(near(gated[0]-gated[1],post[0]-post[1]));
    assert(near(gated[2]-gated[1],post[2]-post[1]));
    const float applied=gated[0]-post[0];
    assert(near(applied,same.weight*(-.009f)));

    // Exact bypass when physical data, structure neighbours or candidate is
    // invalid; no NaN/Inf can leak into the post-chroma result.
    const auto nan=std::numeric_limits<float>::quiet_NaN();
    assert(!sf2::residualConfidenceAt(50,51,{},-.006f,0.f,true,read,shape).valid);
    assert(!sf2::residualConfidenceAt(50,51,model,nan,0.f,true,read,shape).valid);
    assert(!sf2::residualConfidenceAt(50,51,model,-.006f,0.f,true,read,
        [](int,int){return -1.f;}).valid);
    const auto bad=sf2::residualConfidenceAt(50,51,model,-.006f,0.f,true,
        [](int,int)->P{return {NAN,0.f,0.f};},shape);
    assert(!bad.valid);
    assert(sf2::applyResidualConfidence(post,-.006f,0.f,bad)==post);
    assert(sf2::applyResidualConfidence(post,nan,0.f,flat)==post);

    // Controlled structure-preservation experiment: a known true narrow
    // hair/textile signal is present in immutable guidance. Stage-4 would
    // over-correct by the supplied candidate residual, while stage-5 keeps
    // more real structure. This isolates the gate; actual camera JPEGs remain
    // a separate acceptance requirement.
    for(int y=0;y<H;++y)for(int x=0;x<W;++x)
        frame[y*W+x]=pix(.1f+(x==76?.014f:0.f)+
                             ((x/2+y/2)%2==0?.006f:-.006f));
    double withoutGate=0,withGate=0;
    for(int y=12;y<H-12;++y) {
        const int x=76;
        const float truth=.114f+((x/2+y/2)%2==0?.006f:-.006f);
        const float fineCandidate=-.007f;
        const float coarseCandidate=-.003f;
        const auto e=sf2::residualConfidenceAt(x,y,model,
                     fineCandidate,coarseCandidate,false,read,shape);
        check(e);
        const float plain=truth+fineCandidate+coarseCandidate;
        const auto safe=sf2::applyResidualConfidence(pix(truth),
                             fineCandidate,coarseCandidate,e);
        withoutGate+=double(plain-truth)*(plain-truth);
        withGate+=double(yOf(safe)-truth)*(yOf(safe)-truth);
    }
    assert(withGate < withoutGate*.5);

    // Flat regions retain a nonzero correction for actual noise.
    std::mt19937 rng(2026);
    std::normal_distribution<float> normal(0.f,.008f);
    for(int y=0;y<H;++y)for(int x=0;x<W;++x)
        frame[y*W+x]=pix(.11f+normal(rng));
    double appliedInFlat=0.0,rawCandidate=0.0;
    for(int y=8;y<H-8;y+=3)for(int x=8;x<W-8;x+=3) {
        const auto e=sf2::residualConfidenceAt(x,y,model,
                         -.003f,-.001f,false,read,shape);
        check(e);
        appliedInFlat+=std::abs(e.weight*(-.004f));
        rawCandidate+=.004f;
    }
    assert(appliedInFlat>rawCandidate*.12f);
    std::cout<<"sf2Confidence hairMsePlain="<<withoutGate<<" hairMseGated="<<withGate
             <<" flatResidualRetained="<<appliedInFlat/rawCandidate<<"\n";
    return 0;
}
