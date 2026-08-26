#include <cassert>
#include <cmath>
#include <iostream>
#include <vector>
#include "SpectraPixelBackend.h"
using namespace bncam::spectra2;
int main() {
    const auto selection = selectPixelKernelBackend(640, 480, true, 64);
    assert(selection.selected == PixelKernelBackendKind::TiledNeon);
    assert(selection.selfTestPerformed && selection.selfTestPassed);
    constexpr int width=73, height=67;
    std::vector<float> rgb(width*height*3);
    for (int i=0;i<width*height;++i) {
        rgb[i*3]=std::fmod(0.013f*i,1.0f);
        rgb[i*3+1]=std::fmod(0.017f*i+0.02f,1.0f);
        rgb[i*3+2]=std::fmod(0.019f*i+0.07f,1.0f);
    }
    RgbFloatFrameView view{rgb.data(),width,height,static_cast<std::size_t>(width*3)};
    OpponentTileBuffer neon{}, scalar{};
    OpponentTileBuildTelemetry tn{}, ts{};
    buildOpponentTile(view,5,7,64,55,2,selection,neon,tn);
    PixelKernelSelection scalarSelection=selection;
    scalarSelection.selected=PixelKernelBackendKind::TiledCpu;
    buildOpponentTile(view,5,7,64,55,2,scalarSelection,scalar,ts);
    assert(tn.vectorizedPixelCount>0);
    assert(neon.luma.size()==scalar.luma.size());
    float maxDelta=0.0f;
    for (std::size_t i=0;i<neon.luma.size();++i) {
        maxDelta=std::max(maxDelta,std::abs(neon.luma[i]-scalar.luma[i]));
        maxDelta=std::max(maxDelta,std::abs(neon.rg[i]-scalar.rg[i]));
        maxDelta=std::max(maxDelta,std::abs(neon.bg[i]-scalar.bg[i]));
        maxDelta=std::max(maxDelta,std::abs(neon.saturation[i]-scalar.saturation[i]));
    }
    assert(maxDelta<=3e-6f);
    std::cout << "SPECTRA_PIXEL_NEON_TESTS_OK maxDelta=" << maxDelta << "\n";
}
