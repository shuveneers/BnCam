#include "../SpectraLowFrequencyOwnership.h"

#include <cassert>
#include <limits>

int main() {
    using bncam::spectra2::pass3OwnsLowFrequencyChroma;

    assert(pass3OwnsLowFrequencyChroma(true, true, true, 0.3078f));
    assert(pass3OwnsLowFrequencyChroma(true, true, true, 0.0200f));
    assert(!pass3OwnsLowFrequencyChroma(true, true, true, 0.0199f));
    assert(!pass3OwnsLowFrequencyChroma(false, true, true, 0.80f));
    assert(!pass3OwnsLowFrequencyChroma(true, false, true, 0.80f));
    assert(!pass3OwnsLowFrequencyChroma(true, true, false, 0.80f));
    assert(!pass3OwnsLowFrequencyChroma(
            true, true, true, std::numeric_limits<float>::quiet_NaN()));
    return 0;
}
