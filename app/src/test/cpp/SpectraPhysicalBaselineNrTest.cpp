#include "../../main/cpp/SpectraPhysicalBaselineNr.h"

#include <cassert>
#include <cmath>

int main() {
    using bncam::spectra2::resolvePhysicalChromaBaseStrength;
    using bncam::spectra2::resolveNoiseTruthDynamicHeadroomFraction;

    // No physical model => no invented ISO/format-based physical authority.
    const auto noModel = resolvePhysicalChromaBaseStrength(0.0f, 1.0f, 1.0f);
    assert(!noModel.modelDriven);
    assert(noModel.baseStrength == 0.0f);

    const auto low = resolvePhysicalChromaBaseStrength(3.30312e-06f, 1.0f, 1.0f);
    const auto mid = resolvePhysicalChromaBaseStrength(1.31565e-05f, 1.0f, 1.0f);
    const auto high = resolvePhysicalChromaBaseStrength(1.93349e-04f, 1.0f, 1.0f);
    assert(low.modelDriven && mid.modelDriven && high.modelDriven);
    assert(low.baseStrength >= 0.06f);
    assert(mid.baseStrength > low.baseStrength);
    assert(high.baseStrength > mid.baseStrength);
    assert(high.baseStrength <= 0.400001f);

    // RAW container type is intentionally absent: equal normalized S/O variance => equal authority.
    const auto samePhysicalTruthA = resolvePhysicalChromaBaseStrength(1.74249e-04f, 1.0f, 1.0f);
    const auto samePhysicalTruthB = resolvePhysicalChromaBaseStrength(1.74249e-04f, 1.0f, 1.0f);
    assert(std::abs(samePhysicalTruthA.baseStrength - samePhysicalTruthB.baseStrength) < 1.0e-7f);

    // Calibration scales variance; confidence gates authority rather than creating a fallback.
    const auto calibratedUp = resolvePhysicalChromaBaseStrength(1.31565e-05f, 4.0f, 1.0f);
    assert(std::abs(calibratedUp.calibratedNoiseSigma - 2.0f * mid.rawNoiseSigma) < 1.0e-6f);
    assert(calibratedUp.baseStrength > mid.baseStrength);
    const auto lowConfidence = resolvePhysicalChromaBaseStrength(1.31565e-05f, 1.0f, 0.25f);
    assert(lowConfidence.baseStrength < mid.baseStrength);

    // Dynamic ISO consumes SPECTRA chroma headroom only when physical noise truth reports pressure.
    assert(resolveNoiseTruthDynamicHeadroomFraction(1.0f, 0.0f, 5.0f) == 0.0f);
    assert(resolveNoiseTruthDynamicHeadroomFraction(0.0f, 1.0f, 5.0f) == 0.0f);
    assert(resolveNoiseTruthDynamicHeadroomFraction(1.0f, 1.0f, 0.0f) == 0.0f);
    const float d25 = resolveNoiseTruthDynamicHeadroomFraction(0.25f, 0.8f, 4.0f);
    const float d50 = resolveNoiseTruthDynamicHeadroomFraction(0.50f, 0.8f, 4.0f);
    const float d100 = resolveNoiseTruthDynamicHeadroomFraction(1.00f, 0.8f, 4.0f);
    assert(d25 > 0.0f && d25 < d50 && d50 < d100);
    assert(std::abs(d100 - 0.64f) < 1.0e-6f);
    return 0;
}
