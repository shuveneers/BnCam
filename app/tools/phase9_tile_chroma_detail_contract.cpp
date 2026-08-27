#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <iostream>

namespace {
float smooth(float e0, float e1, float v) {
    if (!(e1 > e0)) return 0.0f;
    float t = std::clamp((v - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}
struct Decision {
    float detailProtection = 0.0f;
    float stochasticEvidence = 0.0f;
    float authority = 0.0f;
};
Decision decide(float localVarC, float tileVarC, float coherentChromaGradient,
                float lumaGradient, float tileSigma, float guideSigma,
                float flatness, float noisePressure, float strength,
                float saturation, float y) {
    Decision d{};
    const float chromaDetail = smooth(2.25f * tileSigma, 5.25f * tileSigma,
                                      coherentChromaGradient);
    const float lumaDetail = smooth(std::max(0.004f, 0.70f * guideSigma),
                                    std::max(0.012f, 2.40f * guideSigma), lumaGradient);
    d.detailProtection = std::max(chromaDetail, lumaDetail);
    d.stochasticEvidence = smooth(0.45f * tileVarC, 1.85f * tileVarC, localVarC) *
                           (1.0f - d.detailProtection);
    const float satProtect = 1.0f - 0.70f * smooth(0.34f, 0.78f, saturation);
    const float hiProtect = 1.0f - 0.78f * smooth(0.70f, 1.08f, y);
    d.authority = std::clamp(strength * noisePressure * flatness * d.stochasticEvidence *
                             satProtect * hiProtect, 0.0f, 0.94f);
    return d;
}
}

int main() {
    int passed = 0;
    // Flat neutral/noisy tile: stochastic opponent variance earns substantial correction authority.
    {
        auto d = decide(8.0e-5f, 5.0e-5f, 0.002f, 0.002f, 0.0071f, 0.018f,
                        0.92f, 0.88f, 0.76f, 0.08f, 0.20f);
        assert(d.detailProtection < 0.20f);
        assert(d.stochasticEvidence > 0.55f);
        assert(d.authority > 0.25f);
        ++passed;
    }
    // Isoluminant real colour edge: coherent opponent gradient vetoes denoise even with flat Y.
    {
        auto d = decide(9.0e-5f, 5.0e-5f, 0.050f, 0.001f, 0.0071f, 0.018f,
                        0.90f, 0.90f, 0.78f, 0.25f, 0.25f);
        assert(d.detailProtection > 0.90f);
        assert(d.authority < 0.03f);
        ++passed;
    }
    // Real luminance detail: Y edge independently protects texture regardless of chroma variance.
    {
        auto d = decide(9.0e-5f, 5.0e-5f, 0.004f, 0.060f, 0.0071f, 0.018f,
                        0.85f, 0.90f, 0.78f, 0.12f, 0.25f);
        assert(d.detailProtection > 0.90f);
        assert(d.authority < 0.03f);
        ++passed;
    }
    // Clean flat colour patch: low stochastic variance does not get treated as noise.
    {
        auto d = decide(5.0e-6f, 5.0e-5f, 0.001f, 0.001f, 0.0071f, 0.018f,
                        0.95f, 0.85f, 0.78f, 0.28f, 0.25f);
        assert(d.stochasticEvidence < 0.05f);
        assert(d.authority < 0.03f);
        ++passed;
    }
    // Strong real saturation receives explicit colour protection.
    {
        auto d = decide(8.0e-5f, 5.0e-5f, 0.002f, 0.002f, 0.0071f, 0.018f,
                        0.92f, 0.88f, 0.76f, 0.90f, 0.25f);
        auto neutral = decide(8.0e-5f, 5.0e-5f, 0.002f, 0.002f, 0.0071f, 0.018f,
                              0.92f, 0.88f, 0.76f, 0.05f, 0.25f);
        assert(d.authority < neutral.authority * 0.45f);
        ++passed;
    }
    // Highlight colour gets reduced authority; Phase 9 clipping-confidence remains the safety owner.
    {
        auto hi = decide(8.0e-5f, 5.0e-5f, 0.002f, 0.002f, 0.0071f, 0.018f,
                         0.92f, 0.88f, 0.76f, 0.05f, 0.95f);
        auto mid = decide(8.0e-5f, 5.0e-5f, 0.002f, 0.002f, 0.0071f, 0.018f,
                          0.92f, 0.88f, 0.76f, 0.05f, 0.25f);
        assert(hi.authority < mid.authority * 0.50f);
        ++passed;
    }
    // Low tile noise pressure cannot create denoise authority by itself.
    {
        auto d = decide(8.0e-5f, 5.0e-5f, 0.002f, 0.002f, 0.0071f, 0.018f,
                        0.92f, 0.02f, 0.76f, 0.05f, 0.25f);
        assert(d.authority < 0.02f);
        ++passed;
    }
    std::cout << "PHASE9_TILE_CHROMA_DETAIL_CONTRACT_PASS=" << passed << "\n";
    return passed == 7 ? 0 : 1;
}
