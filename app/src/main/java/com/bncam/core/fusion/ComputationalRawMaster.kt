package com.bncam.core.fusion

import java.util.UUID

/**
 * Immutable single computational RAW master produced per multi-frame capture.
 * Served as the single source of truth for both JPEG rendering and optional DNG writing.
 */
data class ComputationalRawMaster(
    val masterId: String = UUID.randomUUID().toString(),
    val sourceFormat: Int,
    val width: Int,
    val height: Int,
    val cfaPattern: Int = 0, // e.g. RGGB
    val fusedPayload: ByteArray,
    val blackLevel: Float = 64.0f,
    val whiteLevel: Float = 1023.0f,
    val referenceFrameId: String,
    val contributingFrameIds: List<String>,
    val requestedFusionFrameCount: Int,
    val actualFusedFrameCount: Int,
    val exposureTime: Long = 0L,
    val sensitivity: Int = 100,
    val colorMatrix1: FloatArray? = null,
    val colorMatrix2: FloatArray? = null,
    val asShotNeutral: FloatArray? = null,
    val alignmentMethod: String,
    val fusionMethod: String,
    val fusionApplied: Boolean,
    val fallbackReason: String = "none",
    val fusionExecutionCount: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ComputationalRawMaster

        if (masterId != other.masterId) return false
        if (sourceFormat != other.sourceFormat) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (!fusedPayload.contentEquals(other.fusedPayload)) return false
        if (referenceFrameId != other.referenceFrameId) return false
        if (contributingFrameIds != other.contributingFrameIds) return false

        return true
    }

    override fun hashCode(): Int {
        var result = masterId.hashCode()
        result = 31 * result + sourceFormat
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + fusedPayload.contentHashCode()
        result = 31 * result + referenceFrameId.hashCode()
        result = 31 * result + contributingFrameIds.hashCode()
        return result
    }
}
