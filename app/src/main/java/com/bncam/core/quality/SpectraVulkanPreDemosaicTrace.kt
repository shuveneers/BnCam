package com.bncam.core.quality

/** Milestone-8H-B GPU-primary pre-demosaic Pass-1 trace. */
data class SpectraVulkanPreDemosaicTrace(
    val kernelConnected: Boolean = false,
    val attempted: Boolean = false,
    val executionSucceeded: Boolean = false,
    val usedForOutput: Boolean = false,
    val cpuFallbackUsed: Boolean = false,
    val gpuNoRegretBlendUsed: Boolean = false,
    val candidateReadbackAvoided: Boolean = false,
    val persistentReuseHit: Boolean = false,
    val persistentReallocated: Boolean = false,
    val status: String = "NOT_RUN",
    val failureReason: String = "none",
    val inputPackingMs: Double = 0.0,
    val tensorUploadMs: Double = 0.0,
    val pass1KernelMs: Double = 0.0,
    val tileStatisticsKernelMs: Double = 0.0,
    val noRegretDecisionMs: Double = 0.0,
    val noRegretBlendMs: Double = 0.0,
    val gpuKernelMs: Double = 0.0,
    val synchronizationMs: Double = 0.0,
    val readbackMs: Double = 0.0,
    val transferAndSyncMs: Double = 0.0,
    val totalMs: Double = 0.0,
    val residentBytes: Long = 0L,
    val allocationGeneration: Long = 0L
) {
    fun toTraceMap(): Map<String, Any> = linkedMapOf(
        "architecture" to "MILESTONE_8H_B_GPU_PRIMARY_PRE_DEMOSAIC_PASS1",
        "kernelConnected" to kernelConnected,
        "attempted" to attempted,
        "executionSucceeded" to executionSucceeded,
        "usedForOutput" to usedForOutput,
        "cpuFallbackUsed" to cpuFallbackUsed,
        "gpuNoRegretBlendUsed" to gpuNoRegretBlendUsed,
        "candidateFullFrameReadbackAvoided" to candidateReadbackAvoided,
        "authority" to "GPU_PASS1_PRIMARY_CPU_TYPED_FALLBACK_ONLY",
        "status" to status,
        "failureReason" to failureReason,
        "timing" to linkedMapOf(
            "inputPackingMs" to inputPackingMs.m8hbPositive(),
            "tensorUploadMs" to tensorUploadMs.m8hbPositive(),
            "pass1KernelMs" to pass1KernelMs.m8hbPositive(),
            "tileStatisticsKernelMs" to tileStatisticsKernelMs.m8hbPositive(),
            "noRegretCpuDecisionMs" to noRegretDecisionMs.m8hbPositive(),
            "noRegretBlendKernelMs" to noRegretBlendMs.m8hbPositive(),
            "gpuKernelMs" to gpuKernelMs.m8hbPositive(),
            "synchronizationMs" to synchronizationMs.m8hbPositive(),
            "readbackMs" to readbackMs.m8hbPositive(),
            "transferAndSyncMs" to transferAndSyncMs.m8hbPositive(),
            "totalMs" to totalMs.m8hbPositive()
        ),
        "persistentResources" to linkedMapOf(
            "reuseHit" to persistentReuseHit,
            "reallocated" to persistentReallocated,
            "residentBytes" to residentBytes.coerceAtLeast(0L),
            "allocationGeneration" to allocationGeneration.coerceAtLeast(0L)
        ),
        "scopeStatus" to if (usedForOutput) {
            "VST_WIENER_ANISOTROPIC_PASS1_AND_FULL_FRAME_NO_REGRET_BLEND_ON_GPU"
        } else {
            "CPU_REFERENCE_FALLBACK_OR_PRE_DEMOSAIC_KERNEL_UNAVAILABLE"
        }
    )

    companion object {
        fun fromNativeStats(stats: Map<String, String>) = SpectraVulkanPreDemosaicTrace(
            kernelConnected = stats.m8hbBool("spectraPass1VulkanKernelConnected"),
            attempted = stats.m8hbBool("spectraPass1VulkanAttempted"),
            executionSucceeded = stats.m8hbBool("spectraPass1VulkanExecutionSucceeded"),
            usedForOutput = stats.m8hbBool("spectraPass1VulkanUsedForOutput"),
            cpuFallbackUsed = stats.m8hbBool("spectraPass1VulkanCpuFallbackUsed"),
            gpuNoRegretBlendUsed = stats.m8hbBool("spectraPass1VulkanGpuNoRegretBlendUsed"),
            candidateReadbackAvoided = stats.m8hbBool("spectraPass1VulkanCandidateReadbackAvoided"),
            persistentReuseHit = stats.m8hbBool("spectraPass1VulkanPersistentReuseHit"),
            persistentReallocated = stats.m8hbBool("spectraPass1VulkanPersistentReallocated"),
            status = stats["spectraPass1VulkanStatus"] ?: "NOT_RUN",
            failureReason = stats["spectraPass1VulkanFailureReason"] ?: "none",
            inputPackingMs = stats.m8hbDouble("spectraPass1VulkanInputPackingMs"),
            tensorUploadMs = stats.m8hbDouble("spectraPass1VulkanTensorUploadMs"),
            pass1KernelMs = stats.m8hbDouble("spectraPass1VulkanPass1KernelMs"),
            tileStatisticsKernelMs = stats.m8hbDouble("spectraPass1VulkanTileStatisticsKernelMs"),
            noRegretDecisionMs = stats.m8hbDouble("spectraPass1VulkanNoRegretDecisionMs"),
            noRegretBlendMs = stats.m8hbDouble("spectraPass1VulkanNoRegretBlendMs"),
            gpuKernelMs = stats.m8hbDouble("spectraPass1VulkanGpuKernelMs"),
            synchronizationMs = stats.m8hbDouble("spectraPass1VulkanSynchronizationMs"),
            readbackMs = stats.m8hbDouble("spectraPass1VulkanReadbackMs"),
            transferAndSyncMs = stats.m8hbDouble("spectraPass1VulkanTransferAndSyncMs"),
            totalMs = stats.m8hbDouble("spectraPass1VulkanTotalMs"),
            residentBytes = stats.m8hbLong("spectraPass1VulkanResidentBytes"),
            allocationGeneration = stats.m8hbLong("spectraPass1VulkanAllocationGeneration")
        )
    }
}

private fun Map<String, String>.m8hbBool(key: String): Boolean =
    this[key]?.equals("true", ignoreCase = true) == true

private fun Map<String, String>.m8hbDouble(key: String): Double =
    this[key]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0

private fun Map<String, String>.m8hbLong(key: String): Long =
    this[key]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

private fun Double.m8hbPositive(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0
