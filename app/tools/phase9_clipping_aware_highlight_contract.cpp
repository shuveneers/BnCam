#include "../src/main/cpp/HighlightGamutProtectionV2.h"

#include <algorithm>
#include <cmath>
#include <iostream>

using bncam::highlight::Rgb;

namespace {
bool near(float a, float b, float eps = 2.0e-5f) { return std::abs(a-b) <= eps; }
float chromaSpan(const Rgb& v) {
    return std::max({v.r,v.g,v.b}) - std::min({v.r,v.g,v.b});
}
int check(bool ok, const char* name, int& passed) {
    if (!ok) { std::cerr << "FAIL: " << name << '\n'; return 1; }
    ++passed; return 0;
}
}

int main() {
    int passed = 0;
    {
        const auto c = bncam::highlight::resolveSensorColorConfidence({0.35f,0.40f,0.31f});
        if (check(near(c.confidence,1.0f), "unclipped confidence identity", passed)) return 1;
        const Rgb rgb{0.45f,0.30f,0.22f};
        const auto out = bncam::highlight::applyClippingAwareHighlightColor(rgb,c);
        if (check(!out.applied && near(out.rgb.r,rgb.r) && near(out.rgb.g,rgb.g) && near(out.rgb.b,rgb.b),
                  "unclipped RGB unchanged", passed)) return 1;
    }
    {
        const auto c = bncam::highlight::resolveSensorColorConfidence({0.980f,0.72f,0.68f});
        if (check(c.confidence > 0.0f && c.confidence < 1.0f && c.reduced,
                  "partial ceiling confidence continuous", passed)) return 1;
        const Rgb rgb{1.55f,0.74f,1.28f};
        const float y0 = bncam::highlight::luma(rgb);
        const auto out = bncam::highlight::applyClippingAwareHighlightColor(rgb,c);
        if (check(out.applied && chromaSpan(out.rgb) < chromaSpan(rgb),
                  "partial clipping reduces chroma", passed)) return 1;
        if (check(near(bncam::highlight::luma(out.rgb),y0),
                  "partial clipping preserves luma", passed)) return 1;
        if (check(std::max({out.rgb.r,out.rgb.g,out.rgb.b}) > 1.0f,
                  "scene-linear headroom not unit-clamped", passed)) return 1;
    }
    {
        const auto c = bncam::highlight::resolveSensorColorConfidence({0.990f,0.989f,0.992f});
        if (check(c.zero && near(c.confidence,0.0f),
                  "fully hard-clipped RGB forces zero confidence", passed)) return 1;
        const Rgb wbCcm{1.80f,0.82f,1.52f};
        const auto out = bncam::highlight::applyClippingAwareHighlightColor(wbCcm,c);
        const float y = bncam::highlight::luma(wbCcm);
        if (check(out.applied && near(out.rgb.r,y) && near(out.rgb.g,y) && near(out.rgb.b,y),
                  "fully clipped false magenta becomes neutral", passed)) return 1;
        if (check(near(bncam::highlight::luma(out.rgb),y),
                  "full neutralization preserves luma", passed)) return 1;
    }
    {
        const auto c0 = bncam::highlight::resolveSensorColorConfidence({0.959f,0.70f,0.60f});
        const auto c1 = bncam::highlight::resolveSensorColorConfidence({0.970f,0.70f,0.60f});
        const auto c2 = bncam::highlight::resolveSensorColorConfidence({0.985f,0.70f,0.60f});
        if (check(c0.confidence > c1.confidence && c1.confidence > c2.confidence,
                  "confidence monotonic toward white level", passed)) return 1;
    }
    {
        const auto c = bncam::highlight::resolveSensorColorConfidence({0.50f,0.50f,0.50f});
        const Rgb signedCcm{1.20f,-0.18f,0.80f};
        const auto confidenceSafe = bncam::highlight::applyClippingAwareHighlightColor(signedCcm,c);
        const auto gamut = bncam::highlight::protectSignedCcmLowerGamut(confidenceSafe.rgb);
        if (check(gamut.excursion && gamut.applied && std::min({gamut.rgb.r,gamut.rgb.g,gamut.rgb.b}) >= -2.0e-6f,
                  "lower gamut remains separate safety owner", passed)) return 1;
        if (check(near(bncam::highlight::luma(gamut.rgb),bncam::highlight::luma(signedCcm),4.0e-5f),
                  "gamut protection preserves luma", passed)) return 1;
    }
    std::cout << "PHASE9_CLIPPING_AWARE_HIGHLIGHT_CONTRACT_PASS=" << passed << '\n';
    return passed == 12 ? 0 : 2;
}
