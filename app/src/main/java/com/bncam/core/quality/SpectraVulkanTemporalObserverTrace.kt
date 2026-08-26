package com.bncam.core.quality

/** Milestone-8H-D GPU-primary temporal observer trace. */
data class SpectraVulkanTemporalObserverTrace(
    val attemptedPairs: Int = 0,
    val succeededPairs: Int = 0,
    val cpuFallbackPairs: Int = 0,
    val persistentReusePairs: Int = 0,
    val reallocatedPairs: Int = 0,
    val status: String = "NOT_ATTEMPTED",
    val failureReason: String = "none",
    val inputPackingMs: Double = 0.0,
    val coarseKernelMs: Double = 0.0,
    val coarseReductionMs: Double = 0.0,
    val observerKernelMs: Double = 0.0,
    val staticFieldKernelMs: Double = 0.0,
    val compactReductionAndFitMs: Double = 0.0,
    val synchronizationMs: Double = 0.0,
    val readbackMs: Double = 0.0,
    val totalMs: Double = 0.0,
    val inputBytes: Long = 0L,
    val compactReadbackBytes: Long = 0L,
    val persistentResidentBytes: Long = 0L
) {
    fun toTraceMap(): Map<String, Any> = linkedMapOf(
        "architecture" to "MILESTONE_8H_D_GPU_PRIMARY_TEMPORAL_OBSERVER",
        "attemptedPairs" to attemptedPairs.coerceAtLeast(0),
        "succeededPairs" to succeededPairs.coerceAtLeast(0),
        "cpuFallbackPairs" to cpuFallbackPairs.coerceAtLeast(0),
        "gpuPrimaryUsed" to (succeededPairs > 0),
        "cpuFullFrameObserverAvoided" to (succeededPairs > 0 && cpuFallbackPairs == 0),
        "status" to status,
        "failureReason" to failureReason,
        "authority" to "GPU_PIXEL_SCALE_OBSERVER_CPU_COMPACT_REDUCTION_ROBUST_FIT_AND_TYPED_FALLBACK_ONLY",
        "timing" to linkedMapOf(
            "inputPackingMs" to inputPackingMs.m8hdPositive(),
            "coarseKernelMs" to coarseKernelMs.m8hdPositive(),
            "coarseCpuReductionMs" to coarseReductionMs.m8hdPositive(),
            "observerKernelMs" to observerKernelMs.m8hdPositive(),
            "staticFieldKernelMs" to staticFieldKernelMs.m8hdPositive(),
            "compactReductionAndFitMs" to compactReductionAndFitMs.m8hdPositive(),
            "synchronizationMs" to synchronizationMs.m8hdPositive(),
            "readbackMs" to readbackMs.m8hdPositive(),
            "totalMs" to totalMs.m8hdPositive()
        ),
        "memory" to linkedMapOf(
            "inputBytes" to inputBytes.coerceAtLeast(0L),
            "compactReadbackBytes" to compactReadbackBytes.coerceAtLeast(0L),
            "persistentResidentBytes" to persistentResidentBytes.coerceAtLeast(0L),
            "persistentReusePairs" to persistentReusePairs.coerceAtLeast(0),
            "reallocatedPairs" to reallocatedPairs.coerceAtLeast(0)
        ),
        "normalizedInnovationP90Method" to "GPU_UINT_HISTOGRAM_1024_BINS_0_TO_8_SIGMA"
    )

    companion object {
        fun fromNativeStats(stats: Map<String, String>) = SpectraVulkanTemporalObserverTrace(
            attemptedPairs = stats.m8hdInt("spectraTemporalVulkanAttemptedPairs"),
            succeededPairs = stats.m8hdInt("spectraTemporalVulkanSucceededPairs"),
            cpuFallbackPairs = stats.m8hdInt("spectraTemporalVulkanCpuFallbackPairs"),
            persistentReusePairs = stats.m8hdInt("spectraTemporalVulkanPersistentReusePairs"),
            reallocatedPairs = stats.m8hdInt("spectraTemporalVulkanReallocatedPairs"),
            status = stats["spectraTemporalVulkanStatus"] ?: "NOT_ATTEMPTED",
            failureReason = stats["spectraTemporalVulkanFailureReason"] ?: "none",
            inputPackingMs = stats.m8hdDouble("spectraTemporalVulkanInputPackingMs"),
            coarseKernelMs = stats.m8hdDouble("spectraTemporalVulkanCoarseKernelMs"),
            coarseReductionMs = stats.m8hdDouble("spectraTemporalVulkanCoarseReductionMs"),
            observerKernelMs = stats.m8hdDouble("spectraTemporalVulkanObserverKernelMs"),
            staticFieldKernelMs = stats.m8hdDouble("spectraTemporalVulkanStaticFieldKernelMs"),
            compactReductionAndFitMs = stats.m8hdDouble("spectraTemporalVulkanCompactReductionAndFitMs"),
            synchronizationMs = stats.m8hdDouble("spectraTemporalVulkanSynchronizationMs"),
            readbackMs = stats.m8hdDouble("spectraTemporalVulkanReadbackMs"),
            totalMs = stats.m8hdDouble("spectraTemporalVulkanTotalMs"),
            inputBytes = stats.m8hdLong("spectraTemporalVulkanInputBytes"),
            compactReadbackBytes = stats.m8hdLong("spectraTemporalVulkanCompactReadbackBytes"),
            persistentResidentBytes = stats.m8hdLong("spectraTemporalVulkanPersistentResidentBytes")
        )
    }
}

private fun Map<String, String>.m8hdInt(key: String): Int =
    this[key]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

private fun Map<String, String>.m8hdDouble(key: String): Double =
    this[key]?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0

private fun Map<String, String>.m8hdLong(key: String): Long =
    this[key]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

private fun Double.m8hdPositive(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0
