#include "SingleFrameRawDenoisePolicy.h"
#include <cassert>
#include <cmath>
#include <iostream>

using bncam::singleframe::resolveRawDenoisePlan;

int main() {
    {
        const auto p = resolveRawDenoisePlan(0.0f, 1.0f, 1.0f);
        assert(!p.active);
        assert(p.lumaAuthority == 0.0f);
        assert(p.chromaAuthority == 0.0f);
        assert(p.lowFrequencyChromaAuthority == 0.0f);
    }
    {
        const auto p = resolveRawDenoisePlan(1.0e-4f, 1.0f, 0.20f);
        assert(!p.active);  // weak physical authority never invents a fallback.
    }
    {
        const auto low = resolveRawDenoisePlan(4.0e-6f, 1.0f, 1.0f);
        const auto high = resolveRawDenoisePlan(2.0e-4f, 1.0f, 1.0f);
        assert(low.active && high.active);
        assert(high.physicalNoisePressure >= low.physicalNoisePressure);
        assert(high.lumaAuthority >= low.lumaAuthority);
        assert(high.maxLinearShift >= low.maxLinearShift);
        assert(low.chromaAuthority == 0.0f && high.chromaAuthority == 0.0f);
        assert(low.lowFrequencyChromaAuthority == 0.0f &&
               high.lowFrequencyChromaAuthority == 0.0f);
        assert(high.detailRetentionFloor >= 0.90f);
        assert(high.maxLinearShift <= 0.0135f + 1e-7f);
    }
    {
        const auto normal = resolveRawDenoisePlan(2.0e-5f, 1.0f, 0.9f);
        const auto calibrated = resolveRawDenoisePlan(2.0e-5f, 4.0f, 0.9f);
        assert(normal.active && calibrated.active);
        assert(calibrated.physicalNoisePressure >= normal.physicalNoisePressure);
    }
    std::cout << "SingleFrameRawDenoisePolicy Phase-6 ownership tests: PASS\n";
    return 0;
}
