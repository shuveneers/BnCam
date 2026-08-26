#include "../../main/cpp/SpectraContextFusionChromaAuthority.h"

#include <cassert>
#include <cmath>
#include <limits>

int main() {
    using bncam::spectra2::resolveContextFusionChromaCandidateAuthority;

    // Exact profile zero must remain a real no-op.
    assert(resolveContextFusionChromaCandidateAuthority(0.8f, 1.0f, 0.0f) == 0.0f);

    // Invalid/very-low confidence must never create authority.
    assert(resolveContextFusionChromaCandidateAuthority(0.8f, 0.05f, 0.6f) == 0.0f);
    assert(resolveContextFusionChromaCandidateAuthority(
            0.8f, 1.0f, std::numeric_limits<float>::quiet_NaN()) == 0.0f);

    const float lowPressure = resolveContextFusionChromaCandidateAuthority(0.05f, 0.95f, 0.40f);
    const float highPressure = resolveContextFusionChromaCandidateAuthority(0.75f, 0.95f, 0.40f);
    assert(std::isfinite(lowPressure));
    assert(std::isfinite(highPressure));
    assert(lowPressure > 0.0f && lowPressure <= 0.98f);
    assert(highPressure >= lowPressure);

    const float weakProfile = resolveContextFusionChromaCandidateAuthority(0.45f, 0.95f, 0.20f);
    const float strongProfile = resolveContextFusionChromaCandidateAuthority(0.45f, 0.95f, 0.80f);
    assert(strongProfile > weakProfile);

    // Representative Chroma-Rescue condition: authority is deliberately not multiplied
    // down by a second conservative factor. It should remain materially above the old
    // ~0.28 candidate region while staying bounded by downstream safety gates.
    const float representative = resolveContextFusionChromaCandidateAuthority(0.50f, 0.95f, 0.4026f);
    assert(representative > 0.60f);
    assert(representative < 0.70f);

    return 0;
}
