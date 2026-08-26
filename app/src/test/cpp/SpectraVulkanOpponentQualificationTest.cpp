#include "SpectraPixelBackend.h"
#include "SpectraVulkanOpponentQualification.h"
#include "vulkan/VulkanShaderBytecode.h"

#include <cassert>
#include <cmath>
#include <iostream>

int main() {
    using namespace bncam::spectra2;
    float rgb[] = {0.20f, 0.10f, 0.30f, 0.40f, 0.50f, 0.20f};
    const RgbFloatFrameView view{rgb, 2, 1, 6};
    const auto scalar = buildOpponentFeatureFrameScalar(view);
    assert(scalar.frame.valid());
    const auto comparison = compareOpponentFeatureFrameToScalar(view, scalar.frame);
    assert(comparison.performed);
    assert(comparison.maximumAbsoluteDelta == 0.0f);

    VulkanOpponentQualifier qualifier{};
    const VulkanOpponentBenchmarkSample sample{
            "2x1|RAW10|MALVAR_2004", true, true, 0.0f,
            2.0f, 0.25f, 1.0f, 0.20f, 32u, 32u
    };
    qualifier.record(sample); // Pipeline/device warm-up is excluded.
    for (int i = 0; i < 5; ++i) qualifier.record(sample);
    auto snapshot = qualifier.snapshot(sample.routeKey);
    assert(snapshot.benchmarkPerformed);
    assert(snapshot.numericalEquivalencePassed);
    assert(std::abs(snapshot.measuredSpeedup - 2.0f) < 1.0e-6f);
    assert(std::abs(snapshot.transferFraction - 0.20f) < 1.0e-6f);
    assert(!snapshot.deviceQualificationSelected);
    qualifier.setDeviceQualificationSelected(sample.routeKey, true);
    assert(qualifier.snapshot(sample.routeKey).deviceQualificationSelected);


    VulkanOpponentQualifier failingQualifier{};
    VulkanOpponentBenchmarkSample failed = sample;
    failed.success = false;
    failed.numericalComparisonPerformed = false;
    for (int i = 0; i < 3; ++i) failingQualifier.record(failed);
    const auto failedSnapshot = failingQualifier.snapshot(sample.routeKey);
    assert(failedSnapshot.benchmarkAborted);
    assert(!failingQualifier.needsBenchmark(sample.routeKey));
    assert(failedSnapshot.status ==
            "VULKAN_OPPONENT_BENCHMARK_ABORTED_AFTER_REPEATED_FAILURES");

    const auto& base = bncam::vulkan::getRawColorTransformSpirv();
    const auto& patched = bncam::vulkan::getSpectraOpponentFeaturesSpirv();
    assert(!patched.empty());
    assert(base.size() == patched.size());
    int changedWords = 0;
    for (std::size_t i = 0; i < base.size(); ++i) {
        if (base[i] != patched[i]) changedWords++;
    }
    assert(changedWords == 1);
    int matrixTimesVectorInstructions = 0;
    int vectorTimesMatrixInstructions = 0;
    for (std::size_t cursor = 5u; cursor < base.size();) {
        const std::uint32_t wordCount = base[cursor] >> 16u;
        const std::uint32_t opcode = base[cursor] & 0xffffu;
        assert(wordCount > 0u && cursor + wordCount <= base.size());
        if (opcode == 145u) matrixTimesVectorInstructions++;
        if (opcode == 144u) vectorTimesMatrixInstructions++;
        cursor += wordCount;
    }
    assert(matrixTimesVectorInstructions == 1);
    assert(vectorTimesMatrixInstructions == 0);
    std::cout << "SPECTRA_VULKAN_OPPONENT_QUALIFICATION_TESTS_OK\n";
}
