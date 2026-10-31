#include "../../main/cpp/NeutralRawHueSatAuthorityPolicy.h"
#include <cassert>
#include <iostream>

int main() {
    using namespace bncam::color;
    NeutralRawColorAuthorityInput in{};
    assert(!useCalibratedDngMatrixForNeutralRaw(in));
    assert(!usePairedDngHueSatForNeutralRaw(in));
    in = {true, true, true, true, true, true, true};
    assert(useCalibratedDngMatrixForNeutralRaw(in));
    assert(usePairedDngHueSatForNeutralRaw(in));
    for (int index = 0; index < 6; ++index) {
        auto missing = in;
        switch (index) {
            case 0: missing.neutralSingleFrameRaw = false; break;
            case 1: missing.exactCamera2PairPreserved = false; break;
            case 2: missing.matchedProfile = false; break;
            case 3: missing.profileResolved = false; break;
            case 4: missing.profileValid = false; break;
            case 5: missing.pairedForwardTransformReady = false; break;
        }
        assert(!useCalibratedDngMatrixForNeutralRaw(missing));
        assert(!usePairedDngHueSatForNeutralRaw(missing));
    }
    // Matrix-only characterization is allowed if genuinely paired/validated.
    // HueSatMap must not be fabricated when the profile has no real table.
    in.genuineHueSatMapAvailable = false;
    assert(useCalibratedDngMatrixForNeutralRaw(in));
    assert(!usePairedDngHueSatForNeutralRaw(in));
    std::cout << "NeutralRawHueSatAuthorityPolicyTest PASS\n";
}
