#include "SpectraPhysicalBaselineNr.h"
#include <cassert>
#include <cmath>

int main() {
    using bncam::spectra2::resolvePhysicalBaselineNr;
    const float luma = 0.00364474f;
    const float chroma = 0.0112576f;
    const auto p = resolvePhysicalBaselineNr(luma, chroma, true, false);
    assert(p.active);
    assert(std::abs(p.lumaFraction - 0.78f) < 1e-6f);
    assert(std::abs(p.chromaFraction - 1.08f) < 1e-6f);
    assert(p.lumaSigma > luma * 0.75f && p.lumaSigma < luma * 0.82f);
    assert(p.chromaSigma > chroma && p.chromaSigma < chroma * 1.12f);

    const auto on = resolvePhysicalBaselineNr(luma, chroma, true, true);
    assert(!on.active && on.lumaSigma == 0.0f && on.chromaSigma == 0.0f);
    return 0;
}
