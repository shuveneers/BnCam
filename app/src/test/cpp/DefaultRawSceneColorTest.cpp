#include "DefaultRawColorPipeline.h"
#include <cassert>
#include <cmath>
#include <iostream>
using namespace bncam;
int main() {
    // Rec.2020 green represented in signed linear sRGB: valid positive XYZ,
    // although two sRGB coordinates are negative. Scene transport must be exact.
    highlight::Rgb scene{-0.5876411f,1.1328999f,-0.1005789f};
    const auto saved = color::preserveSignedSceneColor(scene);
    assert(saved.excursion && !saved.applied);
    for(int c=0;c<3;++c) assert(saved.rgb[c]==scene[c]);
    const float y=highlight::luma(scene);
    const auto display=color::mapSceneToKhronosInput(scene);
    assert(display.r>=0 && display.g>=0 && display.b>=0);
    assert(std::abs(highlight::luma(display)-y)<2.e-7f);
    // A scalar FLLF correction commutes with cone mapping. Deferring the owner
    // preserves scene information; it need not change final pixels by itself.
    for(float gain:{.01f,.25f,1.f,2.f,16.f}) {
        highlight::Rgb scaled{scene.r*gain,scene.g*gain,scene.b*gain};
        const auto mapped=color::mapSceneToKhronosInput(scaled);
        for(int c=0;c<3;++c) assert(std::abs(mapped[c]-display[c]*gain)<4.e-6f);
    }
    for(highlight::Rgb in: {highlight::Rgb{.1f,.6f,.2f},highlight::Rgb{2.f,4.f,1.f}}) {
        const auto mapped=color::mapSceneToKhronosInput(in);
        for(int c=0;c<3;++c) assert(mapped[c]==in[c]);
    }
    const std::array<float,9> matrix{218/128.f,-71/128.f,-19/128.f,-14/128.f,
            176/128.f,-34/128.f,11/128.f,-95/128.f,213/128.f};
    const auto owner=color::exactCamera2Authority(matrix);
    assert(owner.ready && owner.profileWeight==0 && owner.exactFrameWeight==1);
    assert(owner.exactToProfileNeutralScale==1 && owner.effectivePostWbMatrix==matrix);
    std::cout<<"signed scene transport, valid XYZ counterexample, display mapping, exact matrix: PASS\n";
}
