#pragma once
#include <string>
#include <vector>
#include <cstdint>

struct BenchmarkRunResults {
    std::string workloadName;
    int workloadId = 0;
    bool isGpuAvailable = false;
    float initCostMs = 0.0f;
    float coldFirstRunMs = 0.0f;
    float warmMedianGpuKernelMs = 0.0f;
    float warmMedianTotalGpuPathMs = 0.0f;
    float warmMedianCpuStageMs = 0.0f;
    float minTotalGpuMs = 0.0f;
    float maxTotalGpuMs = 0.0f;
    float p95TotalGpuMs = 0.0f;
    float transferAndSyncMs = 0.0f;
    float netSpeedupPercent = 0.0f;
    bool thermalThrottlingDetected = false;
    float tempBeforeC = 0.0f;
    float tempAfterC = 0.0f;
    bool memoryLeakDetected = false;

    // Equivalence metrics
    double mae = 0.0;
    double rmse = 0.0;
    double p99Error = 0.0;
    double maxAbsError = 0.0;
    int nanInfCount = 0;
    int clippedPixelDiffCount = 0;
    bool cfaPhaseCorrect = true;
    std::string summaryJson;
};

class VulkanBenchmarkKernels {
public:
    static BenchmarkRunResults runBenchmark(
        int workloadId,
        int runCount,
        const uint16_t* rawInput,
        int width,
        int height,
        int frameCount
    );
};
