#include "../../main/cpp/ProfileColorManagement.h"
#include <cassert>
#include <cmath>
#include <iostream>
static float y(float r,float g,float b){return .2126f*r+.7152f*g+.0722f*b;}
int main(){
    float r=-0.12f,g=0.48f,b=1.18f;
    const float before=y(r,g,b);
    bncamCompressToUnitGamutPreserveLuma(r,g,b);
    assert(r>=0&&r<=1&&g>=0&&g<=1&&b>=0&&b<=1);
    assert(std::abs(y(r,g,b)-before)<2e-5f);

    NativeRenderQualityConfig cfg{};
    float a=-0.08f,c=0.42f,d=1.08f;
    const float aa=a,cc=c,dd=d;
    applyBncamProfileColorManagement(a,c,d,cfg,false);
    assert(std::abs(a-aa)<1e-7f&&std::abs(c-cc)<1e-7f&&std::abs(d-dd)<1e-7f);
    applyBncamProfileColorManagement(a,c,d,cfg,true);
    assert(a>=0&&a<=1&&c>=0&&c<=1&&d>=0&&d<=1);
    std::cout << "PHASE10_PROFILE_GAMUT_NUMERICAL_OK\n";
}
