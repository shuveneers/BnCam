#pragma once
#include <string>

struct GpuCandidateTransferCost {
    std::string candidateName;
    uint64_t uploadBytes = 0;
    uint64_t downloadBytes = 0;
    float importOrUploadMs = 0.0f;
    float commandSetupMs = 0.0f;
    float kernelExecutionMs = 0.0f;
    float synchronizationMs = 0.0f;
    float downloadOrExportMs = 0.0f;
    float totalGpuCostMs = 0.0f;
    float equivalentCpuStageMs = 0.0f;
    float netSpeedupPercent = 0.0f;
    bool isAdvantageous = false;
    std::string rejectionReason;
};

class GpuCandidateAnalyzer {
public:
    static GpuCandidateTransferCost evaluateCandidateC_WienerPyramid(
        int width, int height, int frameCount, float cpuTimeMs
    );

    static GpuCandidateTransferCost evaluateCandidateD_RawRendering(
        int width, int height, float cpuTimeMs
    );
};
