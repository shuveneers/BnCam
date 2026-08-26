#include "GpuCandidateAnalyzer.h"
#include <algorithm>

GpuCandidateTransferCost GpuCandidateAnalyzer::evaluateCandidateC_WienerPyramid(
    int width, int height, int frameCount, float cpuTimeMs
) {
    GpuCandidateTransferCost cost;
    cost.candidateName = "Candidate C — Wiener Pyramid Fusion";
    cost.uploadBytes = static_cast<uint64_t>(width) * height * 2 * frameCount; // RAW16 bytes
    cost.downloadBytes = static_cast<uint64_t>(width) * height * 2; // Fused RAW16 master

    // Transfer cost estimation based on mobile SoC system memory bus throughput (~8 GB/s)
    float gbUploaded = static_cast<float>(cost.uploadBytes) / 1e9f;
    float gbDownloaded = static_cast<float>(cost.downloadBytes) / 1e9f;

    cost.importOrUploadMs = (gbUploaded / 8.0f) * 1000.0f; // ~3.5 ms for 8 frames
    cost.commandSetupMs = 0.8f;
    cost.kernelExecutionMs = 6.2f; // Fast GPU compute
    cost.synchronizationMs = 1.2f; // vkQueueWaitIdle / glFinish sync wait
    cost.downloadOrExportMs = (gbDownloaded / 8.0f) * 1000.0f; // ~3.0 ms

    cost.totalGpuCostMs = cost.importOrUploadMs + cost.commandSetupMs + cost.kernelExecutionMs + cost.synchronizationMs + cost.downloadOrExportMs;
    cost.equivalentCpuStageMs = cpuTimeMs;

    if (cpuTimeMs > 0.0f) {
        cost.netSpeedupPercent = ((cpuTimeMs - cost.totalGpuCostMs) / cpuTimeMs) * 100.0f;
    }

    // Transfer/sync cost removes kernel advantage when transfer overhead exceeds 40% of CPU time
    if (cost.importOrUploadMs + cost.downloadOrExportMs + cost.synchronizationMs > 0.35f * cpuTimeMs) {
        cost.isAdvantageous = false;
        cost.rejectionReason = "Memory transfer & sync overhead (" + std::to_string(cost.importOrUploadMs + cost.downloadOrExportMs + cost.synchronizationMs) + " ms) eliminates GPU kernel speedup";
    } else {
        cost.isAdvantageous = (cost.netSpeedupPercent >= 25.0f);
    }

    return cost;
}

GpuCandidateTransferCost GpuCandidateAnalyzer::evaluateCandidateD_RawRendering(
    int width, int height, float cpuTimeMs
) {
    GpuCandidateTransferCost cost;
    cost.candidateName = "Candidate D — RAW Rendering (Demosaic + ISP)";
    cost.uploadBytes = static_cast<uint64_t>(width) * height * 2; // Master RAW16
    cost.downloadBytes = static_cast<uint64_t>(width) * height * 4; // RGBA8 rendered

    float gbUploaded = static_cast<float>(cost.uploadBytes) / 1e9f;
    float gbDownloaded = static_cast<float>(cost.downloadBytes) / 1e9f;

    cost.importOrUploadMs = (gbUploaded / 8.0f) * 1000.0f;
    cost.commandSetupMs = 0.5f;
    cost.kernelExecutionMs = 4.5f;
    cost.synchronizationMs = 1.0f;
    cost.downloadOrExportMs = (gbDownloaded / 8.0f) * 1000.0f;

    cost.totalGpuCostMs = cost.importOrUploadMs + cost.commandSetupMs + cost.kernelExecutionMs + cost.synchronizationMs + cost.downloadOrExportMs;
    cost.equivalentCpuStageMs = cpuTimeMs;

    if (cpuTimeMs > 0.0f) {
        cost.netSpeedupPercent = ((cpuTimeMs - cost.totalGpuCostMs) / cpuTimeMs) * 100.0f;
    }

    if (cost.importOrUploadMs + cost.downloadOrExportMs + cost.synchronizationMs > 0.35f * cpuTimeMs) {
        cost.isAdvantageous = false;
        cost.rejectionReason = "Transfer and host readback overhead (" + std::to_string(cost.importOrUploadMs + cost.downloadOrExportMs + cost.synchronizationMs) + " ms) neutralizes kernel speedup";
    } else {
        cost.isAdvantageous = (cost.netSpeedupPercent >= 25.0f);
    }

    return cost;
}
