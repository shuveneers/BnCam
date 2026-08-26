#include "../../main/cpp/SpectraChromaNoRegretPolicy.h"

#include <cassert>
#include <cmath>

int main() {
    using namespace bncam::spectra2;

    {
        const auto p2 = resolveChromaNoRegretDomain(2, 9.0f, 4.0f, 8.0f, 3.0f);
        assert(std::abs(p2.beforeEnergy - 4.0f) < 1.0e-6f);
        assert(std::abs(p2.targetVariance - 3.0f) < 1.0e-6f);
    }
    {
        const std::array<float, 4> variance{4.0e-6f, 2.0e-6f, 2.0e-6f, 6.0e-6f};
        const float predicted = resolvePredictedChromaResidualVariance(variance);
        assert(std::abs(predicted - 8.25e-6f) < 1.0e-10f);
    }
    {
        const auto carry = resolveChromaProvenanceCarryPlan(4.0e-6f, 3.0e-6f);
        assert(carry.usedGpuPostPassEnergy);
        assert(std::abs(carry.globalEnergyScale - 0.75f) < 1.0e-6f);
        assert(std::abs(carryChromaResidualEnergy(8.0e-6f, carry.globalEnergyScale) - 6.0e-6f) < 1.0e-10f);
    }
    {
        const auto carry = resolveChromaProvenanceCarryPlan(4.0e-6f, NAN);
        assert(!carry.usedGpuPostPassEnergy);
        assert(std::abs(carry.globalEnergyScale - 1.0f) < 1.0e-6f);
        assert(std::abs(carryChromaResidualEnergy(5.0e-6f, carry.globalEnergyScale) - 5.0e-6f) < 1.0e-10f);
    }
    {
        const auto p3 = resolveChromaNoRegretDomain(3, 10.0f, 2.0f, 8.0f, 4.0f);
        assert(std::abs(p3.beforeEnergy - 6.4f) < 1.0e-6f);
        assert(std::abs(p3.targetVariance - 6.2f) < 1.0e-6f);
    }
    {
        const auto safe = resolvePass3ColourDriftDecision(0.0002f, -0.0001f, 4.8e-6f, 0.2f);
        assert(!safe.hardReject);
        assert(safe.acceptanceScale > 0.95f);
    }
    {
        // Green/magenta field: R-G and B-G move together. This must be attenuated.
        const auto tint = resolvePass3ColourDriftDecision(0.0030f, 0.0028f, 4.8e-6f, 0.2f);
        assert(tint.acceptanceScale < 0.35f);
    }
    {
        const auto extreme = resolvePass3ColourDriftDecision(0.0090f, 0.0080f, 4.8e-6f, 0.2f);
        assert(extreme.hardReject);
        assert(extreme.acceptanceScale == 0.0f);
    }
    return 0;
}
