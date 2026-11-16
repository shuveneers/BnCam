#include "../../main/cpp/RawPoissonGaussianEstimator.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <limits>
#include <random>
#include <string>
#include <vector>

using namespace bncam;

int main() {
    using sf2::RawNoiseView;
    using sf2::estimateRawPoissonGaussian;
    constexpr int W=640, H=640, STRIDE=648;
    const std::array<double,4> S{.00020,.00022,.00021,.00024};
    const std::array<double,4> O{.000030,.000031,.000032,.000034};
    for(int cfa=0;cfa<4;++cfa) {
        std::vector<float> mosaic(static_cast<std::size_t>(STRIDE)*H,0.0f);
        std::mt19937 rng(static_cast<unsigned>(2345+cfa*37));
        std::normal_distribution<double> gaussian(0.0,1.0);
        for(int by=0;by<H;by+=32) for(int bx=0;bx<W;bx+=32) {
            const int index=(by/32)*20+(bx/32);
            // A flat 32x32 region at each varying exposure-independent
            // scene intensity: enough dynamic span to identify S and O.
            const double signal=.065+.77*static_cast<double>((index*73)%400)/399.0;
            for(int y=by;y<by+32;++y)for(int x=bx;x<bx+32;++x) {
                const auto ch=static_cast<std::size_t>(
                    raw::canonicalPlaneAtMosaicSite(cfa,x,y));
                const double sigma=std::sqrt(S[ch]*signal+O[ch]);
                mosaic[static_cast<std::size_t>(y)*STRIDE+x]=
                    static_cast<float>(signal+gaussian(rng)*sigma);
            }
        }
        const RawNoiseView view{mosaic.data(),W,H,STRIDE,cfa};
        const auto estimate=estimateRawPoissonGaussian(view,S,O);
        assert(estimate.valid && !estimate.overridesPhysicalModel &&
               !estimate.appliedToPixels && !estimate.inputWasReadBackFromGpu);
        assert(std::string(estimate.status)=="DIAGNOSTIC_FIT_READY_NO_COEFFICIENT_UPDATE");
        assert(estimate.scannedTiles==400);
        for(int ch=0;ch<4;++ch) {
            assert(estimate.acceptedTiles[ch]>=96);
            assert(estimate.signalSpan[ch]>=.25);
            assert(estimate.supportConfidence[ch]>=.18);
            assert(estimate.fitScore[ch]>0);
            assert(std::abs(estimate.slope[ch]/S[ch]-1.0)<.48);
            assert(std::abs(estimate.offset[ch]/O[ch]-1.0)<.78);
            assert(std::string(estimate.channelStatus[ch])=="DIAGNOSTIC_FIT_ONLY");
        }
        const auto output=sf2::formatRawPoissonGaussianDiagnostic(estimate);
        assert(output.find("overridesPhysicalModel=false")!=std::string::npos);
        assert(output.find("appliedToPixels=false")!=std::string::npos);
        assert(output.find("inputWasReadBackFromGpu=false")!=std::string::npos);
        assert(output.find("diagnosticSlope=[")!=std::string::npos);
        // Model input is an immutable argument: the fit cannot modify S/O.
        assert(S[0]==.00020 && O[0]==.000030);

        // A structureless constant cannot fit nonzero variance/slope.
        std::fill(mosaic.begin(),mosaic.end(),.3f);
        const auto noNoise=estimateRawPoissonGaussian(view,S,O);
        assert(!noNoise.valid);
        assert(sf2::formatRawPoissonGaussianDiagnostic(noNoise)
                    .find("diagnosticCoefficients=UNAVAILABLE")!=std::string::npos);
        // Saturated/near-black data must not be used as noise observations.
        std::fill(mosaic.begin(),mosaic.end(),.999f);
        assert(!estimateRawPoissonGaussian(view,S,O).valid);
        std::fill(mosaic.begin(),mosaic.end(),.001f);
        assert(!estimateRawPoissonGaussian(view,S,O).valid);
        // Strong repeated Bayer texture lacks independent noise support.
        for(int y=0;y<H;++y)for(int x=0;x<W;++x)
            mosaic[static_cast<std::size_t>(y)*STRIDE+x]=
                static_cast<float>((x/2)&1 ? .2:.8);
        assert(!estimateRawPoissonGaussian(view,S,O).valid);
    }
    assert(raw::canonicalPlaneAtMosaicSite(0,0,0)==0);
    assert(raw::canonicalPlaneAtMosaicSite(1,1,0)==0);
    assert(raw::canonicalPlaneAtMosaicSite(2,0,1)==0);
    assert(raw::canonicalPlaneAtMosaicSite(3,1,1)==0);
    assert(raw::canonicalPlaneAtMosaicSite(3,0,0)==3);
    assert(raw::canonicalPlaneAtMosaicSite(0,1,0)==1);
    assert(raw::canonicalPlaneAtMosaicSite(0,0,1)==2);

    // Contract validation, including explicit fallback with missing physical
    // model; a fitted model must never become a new default authority.
    std::vector<float> image(static_cast<std::size_t>(STRIDE)*H,.3f);
    const RawNoiseView good{image.data(),W,H,STRIDE,0};
    const RawNoiseView nullRaw{nullptr,W,H,STRIDE,0};
    assert(std::string(estimateRawPoissonGaussian(nullRaw,S,O).status)=="NO_HOST_RAW");
    assert(std::string(estimateRawPoissonGaussian(
        {image.data(),W,H,static_cast<std::size_t>(W-1),0},S,O).status)
        =="INVALID_VIEW_OR_CFA");
    assert(std::string(estimateRawPoissonGaussian(
        {image.data(),W,H,STRIDE,4},S,O).status)=="INVALID_VIEW_OR_CFA");
    assert(std::string(estimateRawPoissonGaussian(
        {image.data(),64,64,STRIDE,0},S,O).status)=="INVALID_VIEW_OR_CFA");
    auto invalidS=S;
    invalidS[0]=std::numeric_limits<double>::quiet_NaN();
    assert(std::string(estimateRawPoissonGaussian(good,invalidS,O).status)
        =="PHYSICAL_SO_UNAVAILABLE_NO_FIT");
    invalidS=S;invalidS[3]=-1;
    assert(!estimateRawPoissonGaussian(good,invalidS,O).valid);
    invalidS=S;invalidS[2]=0;
    auto invalidO=O;invalidO[2]=0;
    assert(!estimateRawPoissonGaussian(good,invalidS,invalidO).valid);
    return 0;
}
