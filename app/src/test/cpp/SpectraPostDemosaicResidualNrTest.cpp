#include "SpectraPostDemosaicResidualNr.h"

#include <cassert>
#include <cmath>
#include <cstring>

int main() {
    using bncam::spectra2::resolvePostDemosaicResidualNr;

    // FASE 14 contract: the inputs are the already-propagated residual sigma in the late
    // post-tone domain. SPECTRA Off is still a meaningful physical baseline.
    const auto off = resolvePostDemosaicResidualNr(
            0.00353586f, 0.0108998f, 0.92f,
            0.0f, 0.20f, 0.60f,
            0.55f, 0.60f,
            true, false);
    assert(off.active);
    assert(off.physicalBaselineActive);
    assert(!off.spectraEnhancementActive);
    assert(off.residualCovarianceAuthoritative);
    assert(off.duplicatePhysicalSigmaPrevented);
    assert(std::strcmp(off.authoritySource,
            "PROPAGATED_RESIDUAL_PHYSICAL_BASELINE") == 0);
    assert(off.lumaSigma > 0.0f && off.lumaSigma < off.inputLumaSigma);
    assert(off.chromaSigma > 0.0f && off.chromaSigma < off.inputChromaSigma);

    // Neutral master strength (0) means calibrated SPECTRA authority, not zero authority.
    const auto natural = resolvePostDemosaicResidualNr(
            0.00353586f, 0.0108998f, 0.92f,
            0.0f, 0.20f, 0.60f,
            0.55f, 0.60f,
            true, true);
    assert(natural.active);
    assert(natural.spectraEnhancementActive);
    assert(natural.spectraLumaFraction > 0.0f);
    assert(natural.spectraChromaFraction > 0.0f);
    assert(natural.lumaSigma >= off.lumaSigma);
    assert(natural.chromaSigma >= off.chromaSigma);
    assert(natural.lumaSigma <= natural.inputLumaSigma);
    assert(natural.chromaSigma <= natural.inputChromaSigma);

    // More residual headroom / cleaner character may spend more of the same residual budget,
    // but can never recreate a sigma larger than the propagated residual itself.
    const auto clean = resolvePostDemosaicResidualNr(
            0.00353586f, 0.0108998f, 0.92f,
            0.0f, 0.55f, 0.85f,
            0.85f, 0.90f,
            true, true);
    assert(clean.lumaSigma >= natural.lumaSigma);
    assert(clean.chromaSigma >= natural.chromaSigma);
    assert(clean.lumaSigma <= clean.inputLumaSigma);
    assert(clean.chromaSigma <= clean.inputChromaSigma);

    // No physical S/O authority means no invented SPECTRA physical baseline.
    const auto noModel = resolvePostDemosaicResidualNr(
            0.00353586f, 0.0108998f, 0.92f,
            0.0f, 0.20f, 0.60f,
            1.0f, 1.0f,
            false, true);
    assert(!noModel.active);
    assert(noModel.lumaSigma == 0.0f && noModel.chromaSigma == 0.0f);

    const auto invalid = resolvePostDemosaicResidualNr(
            NAN, 0.0108998f, 0.92f,
            0.0f, 0.20f, 0.60f,
            1.0f, 1.0f,
            true, true);
    assert(!invalid.active);
    return 0;
}
