package com.bncam.core.quality

/** Milestone-8G Vulkan visible-chroma candidate/filter qualification trace. */
data class SpectraVulkanVisibleCandidateTrace(
    val kernelConnected: Boolean = false,
    val attempted: Boolean = false,
    val executionSucceeded: Boolean = false,
    val usedForOutput: Boolean = false,
    val timestampQueryUsed: Boolean = false,
    val benchmarkPerformed: Boolean = false,
    val shaderEquivalencePassed: Boolean = false,
    val finalDecisionCompatibilityPassed: Boolean = false,
    val deviceQualificationSelected: Boolean = false,
    val benchmarkAborted: Boolean = false,
    val memoryGatePassed: Boolean = false,
    val benchmarkSampleCount: Int = 0,
    val failedSampleCount: Int = 0,
    val stripCount: Int = 0,
    val successfulStripCount: Int = 0,
    val finalDecisionComparedPixelCount: Long = 0L,
    val strength: Double = 0.0,
    val currentShaderMaximumAbsoluteDelta: Double = 0.0,
    val shaderMaximumAbsoluteDelta: Double = 0.0,
    val currentFinalDecisionMaximumAbsoluteDelta: Double = 0.0,
    val finalDecisionMaximumAbsoluteDelta: Double = 0.0,
    val currentCpuReferenceMs: Double = 0.0,
    val currentGpuKernelMs: Double = 0.0,
    val currentGpuTotalMs: Double = 0.0,
    val currentTransferAndSyncMs: Double = 0.0,
    val cpuMedianMs: Double = 0.0,
    val gpuKernelMedianMs: Double = 0.0,
    val gpuTotalMedianMs: Double = 0.0,
    val transferAndSyncMedianMs: Double = 0.0,
    val measuredSpeedup: Double = 0.0,
    val transferFraction: Double = 1.0,
    val qualificationOverheadMs: Double = 0.0,
    val inputBytes: Long = 0L,
    val outputBytes: Long = 0L,
    val allocatedBytes: Long = 0L,
    val estimatedTransientBytes: Long = 0L,
    val maximumTransientBytes: Long = 0L,
    val routeKey: String = "UNSET",
    val executionStatus: String = "NOT_RUN",
    val qualificationStatus: String = "NO_SAMPLES",
    val failureReason: String = "none",
    val noRegretExecution: String = "CPU_AUTHORITATIVE_NO_REGRET_AFTER_VULKAN_CANDIDATE"
) {
    fun toTraceMap(): Map<String, Any?> = linkedMapOf(
        "architecture" to "MILESTONE_8G_VULKAN_CANDIDATE_CPU_AUTHORITATIVE_NO_REGRET",
        "kernelConnected" to kernelConnected,
        "attempted" to attempted,
        "executionSucceeded" to executionSucceeded,
        "usedForOutput" to usedForOutput,
        "timestampQueryUsed" to timestampQueryUsed,
        "benchmark" to linkedMapOf(
            "performed" to benchmarkPerformed,
            "sampleCount" to benchmarkSampleCount.coerceAtLeast(0),
            "failedSampleCount" to failedSampleCount.coerceAtLeast(0),
            "aborted" to benchmarkAborted,
            "shaderEquivalencePassed" to shaderEquivalencePassed,
            "finalDecisionCompatibilityPassed" to finalDecisionCompatibilityPassed,
            "currentShaderMaximumAbsoluteDelta" to currentShaderMaximumAbsoluteDelta.finitePositive(),
            "shaderMaximumAbsoluteDelta" to shaderMaximumAbsoluteDelta.finitePositive(),
            "currentFinalDecisionMaximumAbsoluteDelta" to
                currentFinalDecisionMaximumAbsoluteDelta.finitePositive(),
            "finalDecisionMaximumAbsoluteDelta" to
                finalDecisionMaximumAbsoluteDelta.finitePositive(),
            "currentCpuReferenceMs" to currentCpuReferenceMs.finitePositive(),
            "currentGpuKernelMs" to currentGpuKernelMs.finitePositive(),
            "currentGpuTotalMs" to currentGpuTotalMs.finitePositive(),
            "currentTransferAndSyncMs" to currentTransferAndSyncMs.finitePositive(),
            "cpuMedianMs" to cpuMedianMs.finitePositive(),
            "gpuKernelMedianMs" to gpuKernelMedianMs.finitePositive(),
            "gpuTotalMedianMs" to gpuTotalMedianMs.finitePositive(),
            "transferAndSyncMedianMs" to transferAndSyncMedianMs.finitePositive(),
            "measuredSpeedup" to measuredSpeedup.finitePositive(),
            "minimumRequiredSpeedup" to 1.15,
            "transferFraction" to transferFraction.finiteUnit(),
            "maximumTransferFraction" to 0.35,
            "qualificationOverheadMs" to qualificationOverheadMs.finitePositive()
        ),
        "selection" to linkedMapOf(
            "deviceQualificationSelected" to deviceQualificationSelected,
            "memoryGatePassed" to memoryGatePassed,
            "routeKey" to routeKey,
            "executionStatus" to executionStatus,
            "qualificationStatus" to qualificationStatus,
            "failureReason" to failureReason
        ),
        "stripExecution" to linkedMapOf(
            "stripCount" to stripCount.coerceAtLeast(0),
            "successfulStripCount" to successfulStripCount.coerceAtLeast(0),
            "finalDecisionComparedPixelCount" to finalDecisionComparedPixelCount.coerceAtLeast(0L),
            "strength" to strength.coerceIn(0.0, 1.0),
            "inputBytes" to inputBytes.coerceAtLeast(0L),
            "outputBytes" to outputBytes.coerceAtLeast(0L),
            "allocatedBytes" to allocatedBytes.coerceAtLeast(0L),
            "estimatedTransientBytes" to estimatedTransientBytes.coerceAtLeast(0L),
            "maximumTransientBytes" to maximumTransientBytes.coerceAtLeast(0L)
        ),
        "noRegretExecution" to noRegretExecution,
        "scopeStatus" to "GPU_FILTER_CANDIDATE_ONLY_CPU_NO_REGRET_REMAINS_AUTHORITATIVE"
    )

    companion object {
        fun fromNativeStats(stats: Map<String, String>): SpectraVulkanVisibleCandidateTrace =
            SpectraVulkanVisibleCandidateTrace(
                kernelConnected = stats.boolM8G("spectraVisibleChromaVulkanCandidateKernelConnected"),
                attempted = stats.boolM8G("spectraVisibleChromaVulkanCandidateAttempted"),
                executionSucceeded = stats.boolM8G("spectraVisibleChromaVulkanCandidateExecutionSucceeded"),
                usedForOutput = stats.boolM8G("spectraVisibleChromaVulkanCandidateUsedForOutput"),
                timestampQueryUsed = stats.boolM8G("spectraVisibleChromaVulkanCandidateTimestampQueryUsed"),
                benchmarkPerformed = stats.boolM8G("spectraVisibleChromaVulkanCandidateBenchmarkPerformed"),
                shaderEquivalencePassed = stats.boolM8G("spectraVisibleChromaVulkanCandidateShaderEquivalencePassed"),
                finalDecisionCompatibilityPassed = stats.boolM8G(
                    "spectraVisibleChromaVulkanCandidateFinalDecisionCompatibilityPassed"
                ),
                deviceQualificationSelected = stats.boolM8G(
                    "spectraVisibleChromaVulkanCandidateDeviceQualificationSelected"
                ),
                benchmarkAborted = stats.boolM8G("spectraVisibleChromaVulkanCandidateBenchmarkAborted"),
                memoryGatePassed = stats.boolM8G("spectraVisibleChromaVulkanCandidateMemoryGatePassed"),
                benchmarkSampleCount = stats.intM8G("spectraVisibleChromaVulkanCandidateBenchmarkSampleCount"),
                failedSampleCount = stats.intM8G("spectraVisibleChromaVulkanCandidateFailedSampleCount"),
                stripCount = stats.intM8G("spectraVisibleChromaVulkanCandidateStripCount"),
                successfulStripCount = stats.intM8G("spectraVisibleChromaVulkanCandidateSuccessfulStripCount"),
                finalDecisionComparedPixelCount = stats.longM8G(
                    "spectraVisibleChromaVulkanCandidateFinalDecisionComparedPixelCount"
                ),
                strength = stats.doubleM8G("spectraVisibleChromaVulkanCandidateStrength"),
                currentShaderMaximumAbsoluteDelta = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateCurrentShaderMaximumAbsoluteDelta"
                ),
                shaderMaximumAbsoluteDelta = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateShaderMaximumAbsoluteDelta"
                ),
                currentFinalDecisionMaximumAbsoluteDelta = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateCurrentFinalDecisionMaximumAbsoluteDelta"
                ),
                finalDecisionMaximumAbsoluteDelta = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateFinalDecisionMaximumAbsoluteDelta"
                ),
                currentCpuReferenceMs = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateCurrentCpuReferenceMs"
                ),
                currentGpuKernelMs = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateCurrentGpuKernelMs"
                ),
                currentGpuTotalMs = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateCurrentGpuTotalMs"
                ),
                currentTransferAndSyncMs = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateCurrentTransferAndSyncMs"
                ),
                cpuMedianMs = stats.doubleM8G("spectraVisibleChromaVulkanCandidateCpuMedianMs"),
                gpuKernelMedianMs = stats.doubleM8G("spectraVisibleChromaVulkanCandidateGpuKernelMedianMs"),
                gpuTotalMedianMs = stats.doubleM8G("spectraVisibleChromaVulkanCandidateGpuTotalMedianMs"),
                transferAndSyncMedianMs = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateTransferAndSyncMedianMs"
                ),
                measuredSpeedup = stats.doubleM8G("spectraVisibleChromaVulkanCandidateMeasuredSpeedup"),
                transferFraction = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateTransferFraction",
                    1.0
                ),
                qualificationOverheadMs = stats.doubleM8G(
                    "spectraVisibleChromaVulkanCandidateQualificationOverheadMs"
                ),
                inputBytes = stats.longM8G("spectraVisibleChromaVulkanCandidateInputBytes"),
                outputBytes = stats.longM8G("spectraVisibleChromaVulkanCandidateOutputBytes"),
                allocatedBytes = stats.longM8G("spectraVisibleChromaVulkanCandidateAllocatedBytes"),
                estimatedTransientBytes = stats.longM8G(
                    "spectraVisibleChromaVulkanCandidateEstimatedTransientBytes"
                ),
                maximumTransientBytes = stats.longM8G(
                    "spectraVisibleChromaVulkanCandidateMaximumTransientBytes"
                ),
                routeKey = stats["spectraVisibleChromaVulkanCandidateRouteKey"] ?: "UNSET",
                executionStatus = stats["spectraVisibleChromaVulkanCandidateExecutionStatus"] ?: "NOT_RUN",
                qualificationStatus = stats[
                    "spectraVisibleChromaVulkanCandidateQualificationStatus"
                ] ?: "NO_SAMPLES",
                failureReason = stats["spectraVisibleChromaVulkanCandidateFailureReason"] ?: "none",
                noRegretExecution = stats[
                    "spectraVisibleChromaVulkanCandidateNoRegretExecution"
                ] ?: "CPU_AUTHORITATIVE_NO_REGRET_AFTER_VULKAN_CANDIDATE"
            )
    }
}

private fun Map<String, String>.boolM8G(key: String): Boolean =
    this[key]?.equals("true", ignoreCase = true) == true

private fun Map<String, String>.doubleM8G(key: String, default: Double = 0.0): Double =
    this[key]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: default

private fun Map<String, String>.intM8G(key: String): Int =
    this[key]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

private fun Map<String, String>.longM8G(key: String): Long =
    this[key]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

private fun Double.finitePositive(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0
private fun Double.finiteUnit(): Double = if (isFinite()) coerceIn(0.0, 1.0) else 1.0
