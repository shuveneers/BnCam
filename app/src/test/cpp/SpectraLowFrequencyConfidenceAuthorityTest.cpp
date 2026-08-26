#include "../../main/cpp/SpectraChromaMultiscale.h"

#include <cassert>
#include <cmath>
#include <iostream>

using bncam::spectra2::resolveLowBandFieldDecision;

int main() {
    // Medium confidence must not collapse opacity after passing the hard guards.
    const auto medium = resolveLowBandFieldDecision(
            0.010f, 0.0030f, 0.70f, 0.426f, 1.0f, 0.47f, 2.0f);
    assert(medium.supported);
    const auto high = resolveLowBandFieldDecision(
            0.010f, 0.0030f, 0.70f, 1.0f, 1.0f, 1.0f, 2.0f);
    assert(high.supported);
    assert(std::abs(medium.correction) > 0.55f * std::abs(high.correction));

    // Truly unusable model confidence remains a hard stop.
    const auto invalid = resolveLowBandFieldDecision(
            0.010f, 0.0030f, 0.70f, 0.09f, 1.0f, 1.0f, 2.0f);
    assert(!invalid.supported);

    // Weak field support remains a hard stop rather than being rescued by the soft factor.
    const auto weakField = resolveLowBandFieldDecision(
            0.010f, 0.0030f, 0.70f, 0.90f, 1.0f, 0.14f, 2.0f);
    assert(!weakField.supported);

    // Scene structure remains authoritative protection.
    const auto structure = resolveLowBandFieldDecision(
            0.010f, 0.0030f, 0.70f, 0.90f, 0.02f, 1.0f, 2.0f);
    assert(!structure.supported);

    std::cout << "SpectraLowFrequencyConfidenceAuthorityTest passed\n";
    return 0;
}
