package com.bncam.core.quality

/** Milestone-8H-C GPU-primary pre-demosaic Pass-2/Pass-3 trace. */
data class SpectraVulkanResidentChromaStageTrace(
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
    val auxiliaryUploadMs: Double = 0.0,
    val primaryKernelMs: Double = 0.0,
    val secondaryKernelMs: Double = 0.0,
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
    fun toTraceMap(stage: String): Map<String, Any> = linkedMapOf(
        "stage" to stage,
        "kernelConnected" to kernelConnected,
        "attempted" to attempted,
        "executionSucceeded" to executionSucceeded,
        "usedForOutput" to usedForOutput,
        "cpuFallbackUsed" to cpuFallbackUsed,
        "gpuNoRegretBlendUsed" to gpuNoRegretBlendUsed,
        "candidateFullFrameReadbackAvoided" to candidateReadbackAvoided,
        "authority" to "GPU_FULL_FRAME_PRIMARY_CPU_COMPACT_DECISION_AND_TYPED_FALLBACK_ONLY",
        "status" to status,
        "failureReason" to failureReason,
        "timing" to linkedMapOf(
            "inputPackingMs" to inputPackingMs.m8hcPositive(),
            "auxiliaryUploadMs" to auxiliaryUploadMs.m8hcPositive(),
            "primaryKernelMs" to primaryKernelMs.m8hcPositive(),
            "secondaryKernelMs" to secondaryKernelMs.m8hcPositive(),
            "tileStatisticsKernelMs" to tileStatisticsKernelMs.m8hcPositive(),
            "noRegretCpuDecisionMs" to noRegretDecisionMs.m8hcPositive(),
            "noRegretBlendKernelMs" to noRegretBlendMs.m8hcPositive(),
            "gpuKernelMs" to gpuKernelMs.m8hcPositive(),
            "synchronizationMs" to synchronizationMs.m8hcPositive(),
            "readbackMs" to readbackMs.m8hcPositive(),
            "transferAndSyncMs" to transferAndSyncMs.m8hcPositive(),
            "totalMs" to totalMs.m8hcPositive()
        ),
        "persistentResources" to linkedMapOf(
            "reuseHit" to persistentReuseHit,
            "reallocated" to persistentReallocated,
            "residentBytes" to residentBytes.coerceAtLeast(0L),
            "allocationGeneration" to allocationGeneration.coerceAtLeast(0L)
        )
    )
}

data class SpectraVulkanResidentChromaTrace(
    val pass2: SpectraVulkanResidentChromaStageTrace = SpectraVulkanResidentChromaStageTrace(),
    val pass3: SpectraVulkanResidentChromaStageTrace = SpectraVulkanResidentChromaStageTrace()
) {
    fun toTraceMap(): Map<String, Any> = linkedMapOf(
        "architecture" to "MILESTONE_8H_C_GPU_PRIMARY_PRE_DEMOSAIC_PASS2_PASS3",
        "pass2" to pass2.toTraceMap("PASS2_FINE_MID_CHROMA"),
        "pass3" to pass3.toTraceMap("PASS3_LOW_FREQUENCY_ROW_COLUMN"),
        "scopeStatus" to when {
            pass2.usedForOutput && pass3.usedForOutput ->
                "PASS2_AND_PASS3_FULL_FRAME_CPU_PIXEL_LOOPS_SKIPPED"
            pass2.usedForOutput || pass3.usedForOutput ->
                "PARTIAL_GPU_PRIMARY_TYPED_CPU_FALLBACK_FOR_REMAINING_STAGE"
            else -> "CPU_REFERENCE_FALLBACK_OR_RESIDENT_CHROMA_KERNEL_UNAVAILABLE"
        }
    )

    companion object {
        fun fromNativeStats(stats: Map<String, String>) = SpectraVulkanResidentChromaTrace(
            pass2 = stageFromStats(stats, "spectraPass2Vulkan", pass3 = false),
            pass3 = stageFromStats(stats, "spectraPass3Vulkan", pass3 = true)
        )

        private fun stageFromStats(
            stats: Map<String, String>,
            prefix: String,
            pass3: Boolean
        ) = SpectraVulkanResidentChromaStageTrace(
            kernelConnected = stats.m8hcBool("${prefix}KernelConnected"),
            attempted = stats.m8hcBool("${prefix}Attempted"),
            executionSucceeded = stats.m8hcBool("${prefix}ExecutionSucceeded"),
            usedForOutput = stats.m8hcBool("${prefix}UsedForOutput"),
            cpuFallbackUsed = stats.m8hcBool("${prefix}CpuFallbackUsed"),
            gpuNoRegretBlendUsed = stats.m8hcBool("${prefix}GpuNoRegretBlendUsed"),
            candidateReadbackAvoided = stats.m8hcBool("${prefix}CandidateReadbackAvoided"),
            persistentReuseHit = stats.m8hcBool("${prefix}PersistentReuseHit"),
            persistentReallocated = stats.m8hcBool("${prefix}PersistentReallocated"),
            status = stats["${prefix}Status"] ?: "NOT_RUN",
            failureReason = stats["${prefix}FailureReason"] ?: "none",
            inputPackingMs = stats.m8hcDouble("${prefix}InputPackingMs"),
            auxiliaryUploadMs = stats.m8hcDouble("${prefix}AuxiliaryUploadMs"),
            primaryKernelMs = stats.m8hcDouble(
                if (pass3) "${prefix}Pass3KernelMs" else "${prefix}FineKernelMs"
            ),
            secondaryKernelMs = if (pass3) 0.0 else stats.m8hcDouble("${prefix}MidKernelMs"),
            tileStatisticsKernelMs = stats.m8hcDouble("${prefix}TileStatisticsKernelMs"),
            noRegretDecisionMs = stats.m8hcDouble("${prefix}NoRegretDecisionMs"),
            noRegretBlendMs = stats.m8hcDouble("${prefix}NoRegretBlendMs"),
            gpuKernelMs = stats.m8hcDouble("${prefix}GpuKernelMs"),
            synchronizationMs = stats.m8hcDouble("${prefix}SynchronizationMs"),
            readbackMs = stats.m8hcDouble("${prefix}ReadbackMs"),
            transferAndSyncMs = stats.m8hcDouble("${prefix}TransferAndSyncMs"),
            totalMs = stats.m8hcDouble("${prefix}TotalMs"),
            residentBytes = stats.m8hcLong("${prefix}ResidentBytes"),
            allocationGeneration = stats.m8hcLong("${prefix}AllocationGeneration")
        )
    }
}

private fun Map<String, String>.m8hcBool(key: String): Boolean =
    this[key]?.equals("true", ignoreCase = true) == true

private fun Map<String, String>.m8hcDouble(key: String): Double =
    this[key]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0

private fun Map<String, String>.m8hcLong(key: String): Long =
    this[key]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

private fun Double.m8hcPositive(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0
