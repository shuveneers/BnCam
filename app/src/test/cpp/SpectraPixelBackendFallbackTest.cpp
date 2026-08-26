#include <cassert>
#include <iostream>
#include "SpectraPixelBackend.h"

using namespace bncam::spectra2;

int main() {
    const PixelKernelSelection selection = selectPixelKernelBackend(640, 480, true, 64);
    assert(selection.neonCompiled);
    assert(selection.selfTestPerformed);
    assert(selection.selfTestPassed); // numerical equivalence remains valid
    assert(selection.latencyBenchmarkPerformed);
    assert(!selection.latencyBenchmarkPassed);
    assert(selection.selected == PixelKernelBackendKind::TiledCpu);
    assert(selection.fallbackReason == "PIXEL_NEON_LATENCY_NOT_BETTER_SCALAR_FALLBACK");
    std::cout << "SPECTRA_PIXEL_BACKEND_LATENCY_FALLBACK_OK\n";
    return 0;
}
