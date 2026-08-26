package com.bncam.core.fusion

data class FusionResult(
    val requestedBackendId: String,
    val actualBackendId: String,
    val success: Boolean,
    val fusionApplied: Boolean,
    val inputFrameCount: Int,
    val acceptedFrameCount: Int,
    val rejectedFrameCount: Int,
    val referenceFrameId: String,
    val effectiveFrameContribution: Float,
    val perFrameWeights: FloatArray,
    val motionRejectedPixelPercentage: Float,
    val confidenceRejectedPixelPercentage: Float,
    val validOverlapPercentage: Float,
    val fusedPayload: ByteArray? = null,
    val fallbackReason: String = "none",
    val processingTimeMs: Long = 0L,
    val peakMemoryUsageBytes: Long = 0L
)
