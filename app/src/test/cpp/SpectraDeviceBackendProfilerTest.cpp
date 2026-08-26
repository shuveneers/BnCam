#include <cassert>
#include <cmath>
#include <iostream>
#include "SpectraDeviceBackendProfiler.h"

using bncam::spectra2::DeviceBackendProfileSample;
using bncam::spectra2::DeviceBackendProfiler;
using bncam::spectra2::VulkanFp32QualificationInput;

static DeviceBackendProfileSample sample(float totalMs, bool spectra = true) {
    return DeviceBackendProfileSample{
            4000,
            3000,
            spectra,
            spectra ? "RAW10|MALVAR_2004" : "RAW10|MALVAR_2004",
            true,
            totalMs,
            totalMs * 0.08f,
            totalMs * 0.52f,
            totalMs * 0.12f,
            totalMs * 0.09f
    };
}

int main() {
    DeviceBackendProfiler stableProfiler(12u);
    for (float value : {110.0f, 100.0f, 101.0f, 99.0f, 100.5f, 101.5f, 102.0f, 101.0f}) {
        stableProfiler.record(sample(value));
    }
    const auto stable = stableProfiler.snapshot();
    assert(stable.width == 4000);
    assert(stable.height == 3000);
    assert(stable.spectraEnabled);
    assert(stable.routeKey == "RAW10|MALVAR_2004");
    assert(stable.sampleCount == 8);
    assert(stable.warmupDiscardedCount == 1);
    assert(stable.profileReady);
    assert(stable.thermalStable);
    assert(!stable.throttlingDetected);
    assert(stable.thermalDriftRatio < 1.08f);
    assert(stable.confidence >= 0.55f);
    auto failed = sample(0.0f);
    failed.captureSucceeded = false;
    failed.routeKey = "RAW_SENSOR|MENON_2007";
    const auto afterFailed = stableProfiler.record(failed);
    assert(afterFailed.sampleCount == stable.sampleCount);
    assert(afterFailed.routeKey == stable.routeKey);

    DeviceBackendProfiler throttledProfiler(12u);
    for (float value : {101.0f, 100.0f, 101.0f, 102.0f, 118.0f, 124.0f, 130.0f, 132.0f}) {
        throttledProfiler.record(sample(value));
    }
    const auto throttled = throttledProfiler.snapshot();
    assert(throttled.profileReady);
    assert(throttled.throttlingDetected);
    assert(!throttled.thermalStable);
    assert(throttled.thermalDriftRatio >= 1.15f);

    // A profile-signature change must reset the bounded evidence instead of mixing modes.
    const auto reset = throttledProfiler.record(sample(90.0f, false));
    assert(reset.sampleCount == 1);
    assert(!reset.spectraEnabled);
    assert(!reset.profileReady);

    VulkanFp32QualificationInput input{};
    auto result = bncam::spectra2::evaluateVulkanFp32Qualification(input);
    assert(!result.selected);
    assert(result.blockedReason == "RUNTIME_NOT_PREPARED");

    input.runtimePrepared = true;
    input.runtimeReady = true;
    input.timestampQueriesSupported = true;
    input.vmaReady = true;
    result = bncam::spectra2::evaluateVulkanFp32Qualification(input);
    assert(!result.selected);
    assert(result.blockedReason == "NO_PRODUCTION_SPECTRA_VULKAN_FP32_KERNEL_CONNECTED");

    input.productionKernelConnected = true;
    input.fp32KernelAvailable = true;
    input.memoryWithinBudget = false;
    result = bncam::spectra2::evaluateVulkanFp32Qualification(input);
    assert(!result.selected);
    assert(result.blockedReason == "VULKAN_MEMORY_BUDGET_GATE_FAILED");

    input.memoryWithinBudget = true;
    input.benchmarkPerformed = true;
    input.benchmarkSampleCount = 7;
    input.numericalEquivalencePassed = true;
    input.maximumAbsoluteDelta = 1.0e-7f;
    input.cpuMedianMs = 23.0f;
    input.gpuKernelMedianMs = 10.0f;
    input.gpuTotalMedianMs = 18.0f;
    input.transferAndSyncMedianMs = 8.0f; // 44%, deliberately too high.
    input.thermalStable = true;
    input.memoryWithinBudget = true;
    result = bncam::spectra2::evaluateVulkanFp32Qualification(input);
    assert(!result.selected);
    assert(result.blockedReason == "TRANSFER_AND_SYNC_DOMINATE_VULKAN_PATH");

    input.gpuTotalMedianMs = 15.0f;
    input.transferAndSyncMedianMs = 3.0f;
    result = bncam::spectra2::evaluateVulkanFp32Qualification(input);
    assert(result.selected);
    assert(result.status == "VULKAN_FP32_DEVICE_QUALIFIED");
    assert(result.measuredSpeedup >= result.minimumRequiredSpeedup);
    assert(result.transferFraction <= result.maximumTransferFraction);

    input.thermalThrottlingDetected = true;
    result = bncam::spectra2::evaluateVulkanFp32Qualification(input);
    assert(!result.selected);
    assert(result.blockedReason == "THERMAL_STABILITY_GATE_FAILED");

    std::cout << "SPECTRA_DEVICE_BACKEND_PROFILER_TESTS_OK\n";
    return 0;
}
