#include "../../main/cpp/RawPoissonGaussianEstimator.h"
#include <array>
#include <cassert>
#include <cmath>
#include <random>
#include <vector>
int main() {
    using namespace bncam::sf2;
    constexpr int W=320,H=320;
    std::array<double,4> S{.0002,.00022,.00021,.00024};
    std::array<double,4> O{.00003,.000031,.000032,.000034};
    std::vector<float> raw(W*H);
    std::mt19937 rng(2345);std::normal_distribution<double> n(0,1);
    for(int ty=0;ty<H/16;++ty)for(int tx=0;tx<W/16;++tx) {
        const double signal=.07+.82*(double((ty*20+tx)%64)/63.0);
        for(int y=ty*16;y<ty*16+16;++y)
        for(int x=tx*16;x<tx*16+16;++x) {
            int ch=cfaNoiseChannel(0,x&1,y&1);
            raw[y*W+x]=static_cast<float>(signal+n(rng)*std::sqrt(S[ch]*signal+O[ch]));
        }
    }
    const RawNoiseView v{raw.data(),W,H,W,0};
    auto e=estimateRawPoissonGaussian(v,S,O);
    assert(e.valid && !e.overridesPhysicalModel);
    for(int c=0;c<4;++c) {
        assert(e.acceptedTiles[c]>=12);
        assert(e.confidence[c]>=.18);
        assert(std::abs(e.slope[c]/S[c]-1)<.55);
        assert(std::abs(e.offset[c]/O[c]-1)<.65);
    }
    for(auto& sample:raw)sample=.3f;
    assert(!estimateRawPoissonGaussian(v,S,O).valid);
    for(int y=0;y<H;++y)for(int x=0;x<W;++x)raw[y*W+x]=(x&1)?.2f:.8f;
    assert(!estimateRawPoissonGaussian(v,S,O).valid);
    assert(!estimateRawPoissonGaussian({nullptr,W,H,W,0},S,O).valid);
    assert(cfaNoiseChannel(3,1,1)==0 && cfaNoiseChannel(1,1,0)==0);
}
