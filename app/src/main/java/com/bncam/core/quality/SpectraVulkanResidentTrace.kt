package com.bncam.core.quality

/** Milestone-8H production Vulkan resident post-demosaic chain trace. */
data class SpectraVulkanResidentTrace(
    val kernelConnected: Boolean = false,
    val attempted: Boolean = false,
    val executionSucceeded: Boolean = false,
    val usedForOutput: Boolean = false,
    val timestampQueryUsed: Boolean = false,
    val cpuFallbackUsed: Boolean = false,
    val persistentReuseObserved: Boolean = false,
    val persistentReallocated: Boolean = false,
    val stripCount: Int = 0,
    val successfulStripCount: Int = 0,
    val persistentReuseHitCount: Int = 0,
    val persistentReallocationCount: Int = 0,
    val inputPackingMs: Double = 0.0,
    val spatialKernelMs: Double = 0.0,
    val visibleKernelMs: Double = 0.0,
    val gpuKernelMs: Double = 0.0,
    val synchronizationMs: Double = 0.0,
    val readbackMs: Double = 0.0,
    val transferAndSyncMs: Double = 0.0,
    val totalMs: Double = 0.0,
    val inputBytes: Long = 0L,
    val intermediateBytes: Long = 0L,
    val outputBytes: Long = 0L,
    val spatialMapBytes: Long = 0L,
    val persistentResidentBytes: Long = 0L,
    val allocationGeneration: Long = 0L,
    val executionStatus: String = "NOT_RUN",
    val failureReason: String = "none",
    val statisticsMethod: String = "NOT_RUN",
    val authority: String = "GPU_FINAL_DECISION_PRIMARY_CPU_FALLBACK_ONLY"
) {
    fun toTraceMap(): Map<String, Any> = linkedMapOf(
        "architecture" to "MILESTONE_8H_VULKAN_RESIDENT_POST_DEMOSAIC_CHAIN",
        "kernelConnected" to kernelConnected,
        "attempted" to attempted,
        "executionSucceeded" to executionSucceeded,
        "usedForOutput" to usedForOutput,
        "cpuFallbackUsed" to cpuFallbackUsed,
        "authority" to authority,
        "execution" to linkedMapOf(
            "status" to executionStatus,
            "failureReason" to failureReason,
            "timestampQueryUsed" to timestampQueryUsed,
            "stripCount" to stripCount.coerceAtLeast(0),
            "successfulStripCount" to successfulStripCount.coerceAtLeast(0),
            "statisticsMethod" to statisticsMethod
        ),
        "timing" to linkedMapOf(
            "inputPackingMs" to inputPackingMs.m8hPositive(),
            "spatialKernelMs" to spatialKernelMs.m8hPositive(),
            "visibleKernelMs" to visibleKernelMs.m8hPositive(),
            "gpuKernelMs" to gpuKernelMs.m8hPositive(),
            "synchronizationMs" to synchronizationMs.m8hPositive(),
            "readbackMs" to readbackMs.m8hPositive(),
            "transferAndSyncMs" to transferAndSyncMs.m8hPositive(),
            "totalMs" to totalMs.m8hPositive()
        ),
        "persistentResources" to linkedMapOf(
            "reuseObserved" to persistentReuseObserved,
            "reallocated" to persistentReallocated,
            "reuseHitCount" to persistentReuseHitCount.coerceAtLeast(0),
            "reallocationCount" to persistentReallocationCount.coerceAtLeast(0),
            "residentBytes" to persistentResidentBytes.coerceAtLeast(0L),
            "allocationGeneration" to allocationGeneration.coerceAtLeast(0L)
        ),
        "transfer" to linkedMapOf(
            "inputBytes" to inputBytes.coerceAtLeast(0L),
            "deviceLocalIntermediateBytes" to intermediateBytes.coerceAtLeast(0L),
            "outputBytes" to outputBytes.coerceAtLeast(0L),
            "spatialMapBytes" to spatialMapBytes.coerceAtLeast(0L)
        ),
        "scopeStatus" to if (usedForOutput) {
            "GPU_SPATIAL_NR_VISIBLE_CHROMA_NO_REGRET_AND_RGB_RECONSTRUCTION_CPU_NEIGHBOUR_LOOPS_SKIPPED"
        } else {
            "CPU_REFERENCE_FALLBACK_OR_RESIDENT_KERNEL_UNAVAILABLE"
        }
    )

    companion object {
        fun fromNativeStats(stats: Map<String, String>): SpectraVulkanResidentTrace =
            SpectraVulkanResidentTrace(
                kernelConnected = stats.m8hBool("spectraVisibleChromaVulkanResidentKernelConnected"),
                attempted = stats.m8hBool("spectraVisibleChromaVulkanResidentAttempted"),
                executionSucceeded = stats.m8hBool(
                    "spectraVisibleChromaVulkanResidentExecutionSucceeded"
                ),
                usedForOutput = stats.m8hBool("spectraVisibleChromaVulkanResidentUsedForOutput"),
                timestampQueryUsed = stats.m8hBool(
                    "spectraVisibleChromaVulkanResidentTimestampQueryUsed"
                ),
                cpuFallbackUsed = stats.m8hBool(
                    "spectraVisibleChromaVulkanResidentCpuFallbackUsed"
                ),
                persistentReuseObserved = stats.m8hBool(
                    "spectraVisibleChromaVulkanResidentPersistentReuseObserved"
                ),
                persistentReallocated = stats.m8hBool(
                    "spectraVisibleChromaVulkanResidentPersistentReallocated"
                ),
                stripCount = stats.m8hInt("spectraVisibleChromaVulkanResidentStripCount"),
                successfulStripCount = stats.m8hInt(
                    "spectraVisibleChromaVulkanResidentSuccessfulStripCount"
                ),
                persistentReuseHitCount = stats.m8hInt(
                    "spectraVisibleChromaVulkanResidentPersistentReuseHitCount"
                ),
                persistentReallocationCount = stats.m8hInt(
                    "spectraVisibleChromaVulkanResidentPersistentReallocationCount"
                ),
                inputPackingMs = stats.m8hDouble(
                    "spectraVisibleChromaVulkanResidentInputPackingMs"
                ),
                spatialKernelMs = stats.m8hDouble(
                    "spectraVisibleChromaVulkanResidentSpatialKernelMs"
                ),
                visibleKernelMs = stats.m8hDouble(
                    "spectraVisibleChromaVulkanResidentVisibleKernelMs"
                ),
                gpuKernelMs = stats.m8hDouble(
                    "spectraVisibleChromaVulkanResidentGpuKernelMs"
                ),
                synchronizationMs = stats.m8hDouble(
                    "spectraVisibleChromaVulkanResidentSynchronizationMs"
                ),
                readbackMs = stats.m8hDouble(
                    "spectraVisibleChromaVulkanResidentReadbackMs"
                ),
                transferAndSyncMs = stats.m8hDouble(
                    "spectraVisibleChromaVulkanResidentTransferAndSyncMs"
                ),
                totalMs = stats.m8hDouble("spectraVisibleChromaVulkanResidentTotalMs"),
                inputBytes = stats.m8hLong("spectraVisibleChromaVulkanResidentInputBytes"),
                intermediateBytes = stats.m8hLong(
                    "spectraVisibleChromaVulkanResidentIntermediateBytes"
                ),
                outputBytes = stats.m8hLong("spectraVisibleChromaVulkanResidentOutputBytes"),
                spatialMapBytes = stats.m8hLong(
                    "spectraVisibleChromaVulkanResidentSpatialMapBytes"
                ),
                persistentResidentBytes = stats.m8hLong(
                    "spectraVisibleChromaVulkanResidentPersistentResidentBytes"
                ),
                allocationGeneration = stats.m8hLong(
                    "spectraVisibleChromaVulkanResidentAllocationGeneration"
                ),
                executionStatus = stats[
                    "spectraVisibleChromaVulkanResidentExecutionStatus"
                ] ?: "NOT_RUN",
                failureReason = stats[
                    "spectraVisibleChromaVulkanResidentFailureReason"
                ] ?: "none",
                statisticsMethod = stats[
                    "spectraVisibleChromaVulkanResidentStatisticsMethod"
                ] ?: "NOT_RUN",
                authority = stats[
                    "spectraVisibleChromaVulkanResidentAuthority"
                ] ?: "GPU_FINAL_DECISION_PRIMARY_CPU_FALLBACK_ONLY"
            )
    }
}

private fun Map<String, String>.m8hBool(key: String): Boolean =
    this[key]?.equals("true", ignoreCase = true) == true

private fun Map<String, String>.m8hDouble(key: String): Double =
    this[key]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0

private fun Map<String, String>.m8hInt(key: String): Int =
    this[key]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

private fun Map<String, String>.m8hLong(key: String): Long =
    this[key]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

private fun Double.m8hPositive(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0
