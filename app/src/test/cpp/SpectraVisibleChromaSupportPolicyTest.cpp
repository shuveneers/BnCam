#include "../../main/cpp/SpectraVisibleChromaSupportPolicy.h"

#include <cassert>
#include <cmath>

int main() {
    using bncam::spectra2::lumaGuidedVisibleChromaAffinity;

    assert(std::fabs(lumaGuidedVisibleChromaAffinity(0.0f) - 1.0f) < 1.0e-6f);

    // Moderate chroma disagreement must no longer collapse neighbour support.
    const float moderate = lumaGuidedVisibleChromaAffinity(4.0f);
    assert(moderate > 0.80f && moderate < 0.82f);

    // Even an extreme chroma outlier keeps bounded luma-guided support so it cannot self-isolate.
    const float extreme = lumaGuidedVisibleChromaAffinity(64.0f);
    assert(extreme > 0.54f && extreme < 0.56f);

    // Invalid distances fail safe to the bounded floor, not NaN/zero support.
    const float invalid = lumaGuidedVisibleChromaAffinity(NAN);
    assert(std::isfinite(invalid));
    assert(invalid >= 0.50f && invalid < 0.51f);

    return 0;
}
