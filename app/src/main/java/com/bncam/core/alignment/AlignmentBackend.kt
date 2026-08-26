package com.bncam.core.alignment

data class AlignmentContext(
    val format: Int,
    val width: Int,
    val height: Int,
    val exposureGenerationId: Long = 1L,
    val maxShiftPixels: Int = 150
)

interface AlignmentBackend {
    val id: String

    fun align(
        referenceGuidePyramid: List<ByteArray>,
        candidateGuidePyramid: List<ByteArray>,
        guideWidth: Int,
        guideHeight: Int,
        context: AlignmentContext
    ): AlignmentResult
}
