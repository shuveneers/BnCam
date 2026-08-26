package com.bncam.core.fusion

import com.bncam.core.alignment.AlignmentResult

data class FusionContext(
    val format: Int,
    val width: Int,
    val height: Int,
    val blackLevel: Float = 64.0f,
    val whiteLevel: Float = 1023.0f,
    val exposureTimeNs: Long = 20_000_000L,
    val iso: Int = 100,
    val maxFramesToUse: Int = 8
)

interface FusionBackend {
    val id: String

    fun fuse(       
        referencePayload: ByteArray,
        secondaryPayloads: List<ByteArray>,
        alignmentResults: List<AlignmentResult>,
        context: FusionContext
    ): FusionResult
}
