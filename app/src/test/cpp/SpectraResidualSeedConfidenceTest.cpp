#include "../../main/cpp/SpectraResidualSeedConfidence.h"

#include <cassert>
#include <cmath>
#include <cstring>
#include <iostream>

using bncam::spectra2::resolveResidualSeedConfidence;

static bool near(double a, double b, double eps = 1.0e-9) {
    return std::abs(a - b) <= eps;
}

int main() {
    {
        const auto p = resolveResidualSeedConfidence(
                true, 0, 0.0, true, 1.0, 1.0,
                1.0e-5, 1.0e-5, 1.0e-5);
        assert(p.sufficientChannels);
        assert(p.varianceReady);
        assert(!p.physicalFallbackActive);
        assert(near(p.channelCoverage, 1.0));
        assert(near(p.confidence, 0.90));
        assert(std::strcmp(p.status, "PROPAGATION_SEED_READY") == 0);
    }
    {
        // Exact Sep-1 failure shape: physical S/O is fully confident and all fallback channel
        // variances are valid, but optional diagnostic provenance is zero.
        const auto p = resolveResidualSeedConfidence(
                false, 4, 0.0, true, 1.0, 1.0,
                7.3230e-5, 1.0897e-5, 1.6599e-5);
        assert(p.sufficientChannels);
        assert(p.varianceReady);
        assert(p.physicalFallbackActive);
        assert(near(p.channelCoverage, 1.0));
        assert(near(p.confidence, 0.70));
        assert(std::strcmp(p.status,
                "PROPAGATION_SEED_PHYSICAL_SO_FALLBACK_READY") == 0);
    }
    {
        const auto p = resolveResidualSeedConfidence(
                false, 3, 0.0, true, 0.8, 1.0,
                2.0e-5, 1.0e-5, 2.0e-5);
        assert(p.physicalFallbackActive);
        assert(near(p.channelCoverage, 0.75));
        assert(near(p.confidence, 0.8 * 0.75 * 0.70));
    }
    {
        // A noise profile with insufficient model confidence may not invent authority.
        const auto p = resolveResidualSeedConfidence(
                false, 4, 0.0, true, 1.0, 0.10,
                2.0e-5, 1.0e-5, 2.0e-5);
        assert(!p.physicalFallbackActive);
        assert(near(p.confidence, 0.0));
    }
    {
        // Missing one RGB variance fails closed even with nominal S/O confidence.
        const auto p = resolveResidualSeedConfidence(
                false, 4, 0.0, true, 1.0, 1.0,
                2.0e-5, 0.0, 2.0e-5);
        assert(!p.varianceReady);
        assert(!p.physicalFallbackActive);
        assert(near(p.confidence, 0.0));
    }
    {
        // Too little channel support also fails closed.
        const auto p = resolveResidualSeedConfidence(
                false, 2, 0.9, true, 1.0, 1.0,
                2.0e-5, 1.0e-5, 2.0e-5);
        assert(!p.sufficientChannels);
        assert(near(p.confidence, 0.0));
    }
    std::cout << "SpectraResidualSeedConfidenceTest PASS\n";
    return 0;
}
