#include "SpectraVulkanVisibleChromaQualification.h"
#include <cassert>
#include <iostream>

using namespace bncam::spectra2;

int main() {
    VulkanVisibleChromaQualifier qualifier{};
    const std::string route = "test_route";
    VulkanVisibleChromaBenchmarkSample sample{};
    sample.routeKey = route;
    sample.success = true;
    sample.candidateComparisonPerformed = true;
    sample.finalDecisionComparisonPerformed = true;
    sample.candidateMaximumAbsoluteDelta = 1.0e-7f;
    sample.finalDecisionMaximumAbsoluteDelta = 2.0e-7f;
    sample.cpuCandidateReferenceMs = 20.0f;
    sample.gpuKernelMs = 5.0f;
    sample.gpuTotalMs = 10.0f;
    sample.transferAndSyncMs = 2.0f;
    qualifier.record(sample); // warmup
    for (int i = 0; i < 5; ++i) qualifier.record(sample);
    auto ready = qualifier.snapshot(route);
    assert(ready.benchmarkPerformed);
    assert(ready.candidateNumericalEquivalencePassed);
    assert(ready.finalDecisionCompatibilityPassed);
    assert(ready.measuredSpeedup >= 1.15f);
    assert(ready.transferFraction <= 0.35f);
    assert(ready.status == "VULKAN_VISIBLE_CHROMA_DEVICE_BENCHMARK_READY");

    VulkanVisibleChromaQualifier incompatible{};
    sample.finalDecisionMaximumAbsoluteDelta = 0.001f;
    incompatible.record(sample);
    for (int i = 0; i < 5; ++i) incompatible.record(sample);
    auto failed = incompatible.snapshot(route);
    assert(failed.benchmarkPerformed);
    assert(!failed.finalDecisionCompatibilityPassed);
    assert(failed.status == "VULKAN_VISIBLE_CHROMA_FINAL_DECISION_COMPATIBILITY_FAILED");
    std::cout << "SPECTRA_VULKAN_VISIBLE_CHROMA_QUALIFICATION_TESTS_OK\n";
}
