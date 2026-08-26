#include "../../main/cpp/SpectraNoiseProfileUncertainty.h"
#include "../../main/cpp/SpectraVisibleChroma.h"

#include <cassert>
#include <cmath>
#include <iostream>

using namespace bncam::spectra2;

int main() {
    assert(std::abs(resolveNoiseProfileAuthorityConfidence(1.0f) - 1.0f) < 1.0e-6f);
    const float medium = resolveNoiseProfileAuthorityConfidence(0.426384f);
    assert(medium > 0.80f && medium < 0.90f);
    assert(resolveNoiseProfileAuthorityConfidence(0.10f) > 0.65f);

    const auto deviceLikePlan = buildVisibleChromaPlan(
            true, 3.85e-6f, 5.339e-6f, 5.13e-7f,
            0.426384f, 0.40f, 0.153785f, 0.65f);
    assert(deviceLikePlan.enabled);
    assert(deviceLikePlan.authorityConfidenceFactor == medium);
    // The old linear-confidence formulation produced ~0.16 authority for this class of plan.
    assert(deviceLikePlan.authority > 0.28f);
    assert(deviceLikePlan.authority <= 0.95f);

    const auto fullConfidencePlan = buildVisibleChromaPlan(
            true, 3.85e-6f, 5.339e-6f, 5.13e-7f,
            1.0f, 0.40f, 0.153785f, 0.65f);
    assert(fullConfidencePlan.authority > deviceLikePlan.authority);

    const auto unusable = buildVisibleChromaPlan(
            true, 3.85e-6f, 5.339e-6f, 5.13e-7f,
            0.05f, 0.40f, 0.153785f, 0.65f);
    assert(!unusable.enabled);
    assert(unusable.status == "MODEL_CONFIDENCE_TOO_LOW");

    std::cout << "SPECTRA_NOISE_PROFILE_AUTHORITY_CONFIDENCE_TESTS_OK\n";
    return 0;
}
