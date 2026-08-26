package com.bncam.core.runtime

enum class TimestampPairingMode {
    EXACT_MATCH,
    DYNAMIC_OFFSET,
    UNASSOCIATED
}

data class TimestampPairingCalibration(
    val pairingMode: TimestampPairingMode = TimestampPairingMode.EXACT_MATCH,
    val pairingConfidence: Double = 1.0,
    val learnedOffsetNs: Long = 0L,
    val toleranceNs: Long = 5_000_000L,
    val ambiguousMatchCount: Long = 0L,
    val pairingLatencyP50Ms: Double = 0.0,
    val pairingLatencyP95Ms: Double = 0.0,
    val pairingLatencyP99Ms: Double = 0.0,
    val isSelfHealingActive: Boolean = false,
    val brokenCount: Long = 0L
)

data class BufferHealthState(
    val logicalHistoryTarget: Int = 35,
    val physicalResidentLimit: Int = 12,
    val minimumCaptureReadyFrames: Int = 1,
    val maximumCaptureLeaseFrames: Int = 20,
    val producerReserve: Int = 2,
    val completeFramesAvailable: Int = 0,
    val isSingleFrameCaptureReady: Boolean = false,
    val isMultiFrameCaptureReady: Boolean = false,
    val oldestFrameAgeMs: Double? = null,
    val newestFrameAgeMs: Double? = null,
    val leakedLeaseCount: Int = 0,
    val leasedFrameMutations: Int = 0,
    val closedBorrowedFrames: Int = 0,
    val deferredCloseCount: Int = 0,
    val completedDeferredCloseCount: Int = 0
)

enum class PreviewQualityTier {
    HIGH,
    BALANCED,
    PERFORMANCE
}

data class MeasuredCadenceState(
    val sensorTimestampFps: Double = 0.0,
    val processingFps: Double = 0.0,
    val presentationFps: Double = 0.0,
    val activePreviewTier: PreviewQualityTier = PreviewQualityTier.BALANCED,
    val activeAeTargetFps: Pair<Int, Int> = Pair(30, 60)
)

data class ActiveFallbacksState(
    val activeFallbacks: List<String> = emptyList()
)

/**
 * Mutable live state holder for the active session.
 * Updated dynamically per-frame/interval without recreating the immutable profile.
 */
data class RawPipelineRuntimeState(
    val sessionGeneration: Int = 0,
    val timestampPairing: TimestampPairingCalibration = TimestampPairingCalibration(),
    val bufferHealth: BufferHealthState = BufferHealthState(),
    val cadence: MeasuredCadenceState = MeasuredCadenceState(),
    val fallbacks: ActiveFallbacksState = ActiveFallbacksState(),
    val updatedAtElapsedNs: Long = System.nanoTime()
)
