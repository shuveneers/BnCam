#include "SpectraPostDemosaicResidualNr.h"
#include <cassert>
#include <cmath>

int main() {
    using bncam::spectra2::resolvePostDemosaicResidualNr;

    // Device-like Natural defaults from the 2026-08-15 A/B. The old Dynamic-ISO mapping
    // would have produced ~1.87x / ~3.03x sigma. Residual cleanup must stay bounded below 1x.
    const auto natural = resolvePostDemosaicResidualNr(
            0.00353586f, 0.0108998f, 1.0f,
            0.18f, 0.05f, 0.45f, true);
    assert(natural.lumaFraction > 0.35f && natural.lumaFraction < 0.45f);
    assert(natural.chromaFraction > 1.00f && natural.chromaFraction < 1.05f);
    assert(natural.lumaSigma < 0.00353586f);
    assert(natural.chromaSigma > 0.0108998f && natural.chromaSigma < 0.0115f);

    const auto clean = resolvePostDemosaicResidualNr(
            0.00353586f, 0.0108998f, 1.0f,
            0.55f, 0.30f, 0.75f, true);
    assert(clean.lumaSigma >= natural.lumaSigma);
    assert(clean.chromaSigma >= natural.chromaSigma);

    const auto off = resolvePostDemosaicResidualNr(
            0.00353586f, 0.0108998f, 1.0f,
            0.18f, 0.05f, 0.45f, false);
    assert(off.lumaSigma == 0.0f && off.chromaSigma == 0.0f);

    const auto invalid = resolvePostDemosaicResidualNr(
            NAN, 0.0108998f, 1.0f, 0.0f, 0.0f, 0.0f, true);
    assert(invalid.lumaSigma == 0.0f && invalid.chromaSigma == 0.0f);
    return 0;
}
