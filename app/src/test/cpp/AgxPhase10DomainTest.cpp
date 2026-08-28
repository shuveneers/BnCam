#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <iostream>

using V = std::array<float,3>;
static V mul(const float m[9], const V& v) {
    // GLSL mat3 constructors are column-major: reproduce mat3 * vec3 exactly.
    return {
        m[0]*v[0] + m[3]*v[1] + m[6]*v[2],
        m[1]*v[0] + m[4]*v[1] + m[7]*v[2],
        m[2]*v[0] + m[5]*v[1] + m[8]*v[2]
    };
}
static float contrast(float x) {
    float x2=x*x, x4=x2*x2;
    return 15.5f*x4*x2 - 40.14f*x4*x + 31.96f*x4 - 6.868f*x2*x + 0.4298f*x2 + 0.1191f*x - 0.00232f;
}
static V agx(const V& in) {
    static const float inset[9] = {
        0.842479062253094f, 0.0423282422610123f, 0.0423756549057051f,
        0.0784335999999992f, 0.878468636469772f, 0.0784336f,
        0.0792237451477643f, 0.0791661274605434f, 0.879142973793104f
    };
    static const float outset[9] = {
        1.19687902425898f, -0.0528968517594562f, -0.0529716355084938f,
        -0.0980208811401368f, 1.15190312990417f, -0.0980434501171241f,
        -0.0990297440797205f, -0.0989611768448433f, 1.15107367264116f
    };
    constexpr float minEv=-12.47393f, maxEv=4.026069f;
    V v=mul(inset,in);
    for (float& x:v) {
        x=std::max(x,1e-6f);
        float n=(std::clamp(std::log2(x),minEv,maxEv)-minEv)/(maxEv-minEv);
        x=std::clamp(contrast(n),0.0f,1.0f);
    }
    V out = mul(outset,v);
    for (float& x : out) {
        x = std::copysign(std::pow(std::abs(x), 2.2f), x);
    }
    return out; // display-linear; signed until the final gamut owner
}
static float srgb(float x) {
    x = std::max(0.0f, x);
    return x <= 0.0031308f ? 12.92f*x : 1.055f*std::pow(x,1.0f/2.4f)-0.055f;
}
int main() {
    const V gray=agx({0.18f,0.18f,0.18f});
    const float mean=(gray[0]+gray[1]+gray[2])/3.0f;
    assert(mean > 0.19f && mean < 0.24f); // display-linear after the required AgX 2.2 EOTF
    const float visibleMean=(srgb(gray[0])+srgb(gray[1])+srgb(gray[2]))/3.0f;
    assert(visibleMean > 0.47f && visibleMean < 0.53f); // 18% scene gray stays near visible 50%
    assert(std::max({gray[0],gray[1],gray[2]})-std::min({gray[0],gray[1],gray[2]}) < 0.025f);
    const V black=agx({1e-6f,1e-6f,1e-6f});
    assert(std::isfinite(black[0]) && std::isfinite(black[1]) && std::isfinite(black[2]));
    std::cout << "PHASE10_AGX_EOTF_NUMERICAL_OK linearMean18=" << mean
              << " visibleMean18=" << visibleMean << "\n";
}
