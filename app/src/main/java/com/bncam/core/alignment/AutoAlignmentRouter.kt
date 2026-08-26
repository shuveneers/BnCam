package com.bncam.core.alignment

data class AutoRoutingResult(
    val initialSelectedBackend: String,
    val initialReason: String,
    val finalBackend: String,
    val fallbackUsed: Boolean,
    val fallbackReason: String,
    val alignmentResult: AlignmentResult
)

class AutoAlignmentRouter : AlignmentBackend {
    override val id: String = "Auto"

    private val phaseCorrelationBackend = PhaseCorrelationAlignmentBackend()
    private val tilePyramidBackend = TilePyramidAlignmentBackend()
    private val eccPyramidBackend = EccPyramidAlignmentBackend()

    override fun align(
        referenceGuidePyramid: List<ByteArray>,
        candidateGuidePyramid: List<ByteArray>,
        guideWidth: Int,
        guideHeight: Int,
        context: AlignmentContext
    ): AlignmentResult {
        return routeAndAlign(referenceGuidePyramid, candidateGuidePyramid, guideWidth, guideHeight, context).alignmentResult
    }

    fun routeAndAlign(
        referenceGuidePyramid: List<ByteArray>,
        candidateGuidePyramid: List<ByteArray>,
        guideWidth: Int,
        guideHeight: Int,
        context: AlignmentContext
    ): AutoRoutingResult {
        val startMs = System.currentTimeMillis()

        // 1. Initial quick probe via Phase Correlation
        val probe = phaseCorrelationBackend.align(
            referenceGuidePyramid, candidateGuidePyramid, guideWidth, guideHeight, context
        )

        // 2. Evaluate tile residual variance for local motion / parallax
        val tileProbe = tilePyramidBackend.align(
            referenceGuidePyramid, candidateGuidePyramid, guideWidth, guideHeight, context
        )

        val initialSelection: Pair<String, String> = when {
            tileProbe.success && tileProbe.validOverlapPercentage < 80.0f ->
                "Tile Pyramid" to "Significant local residual variance or parallax detected across tile grid"
            probe.success && probe.globalConfidence < 0.60f ->
                "ECC Pyramid" to "Moderate correlation peak confidence with possible scene rotation"
            probe.success ->
                "Phase Correlation Pyramid" to "Strong global translation correlation match (confidence=${probe.globalConfidence})"
            else ->
                "Tile Pyramid" to "Phase correlation peak weak; defaulting to hierarchical local tiles"
        }

        val initialBackendId = initialSelection.first
        val initialReason = initialSelection.second

        // 3. Execute selected backend
        val selectedBackend: AlignmentBackend = when (initialBackendId) {
            "Tile Pyramid" -> tilePyramidBackend
            "ECC Pyramid" -> eccPyramidBackend
            else -> phaseCorrelationBackend
        }

        var result = selectedBackend.align(
            referenceGuidePyramid, candidateGuidePyramid, guideWidth, guideHeight, context
        )

        var fallbackUsed = false
        var fallbackReason = "none"
        var finalBackendId = initialBackendId

        // 4. Deterministic fallback if preferred backend fails
        if (!result.success) {
            fallbackUsed = true
            fallbackReason = "Preferred backend '$initialBackendId' failed: ${result.rejectionReason}"

            val fallbackBackend = if (initialBackendId != "Phase Correlation Pyramid") phaseCorrelationBackend else tilePyramidBackend
            finalBackendId = fallbackBackend.id

            result = fallbackBackend.align(
                referenceGuidePyramid, candidateGuidePyramid, guideWidth, guideHeight, context
            )
        }

        val totalTime = System.currentTimeMillis() - startMs
        val finalResult = result.copy(processingTimeMs = totalTime)

        return AutoRoutingResult(
            initialSelectedBackend = initialBackendId,
            initialReason = initialReason,
            finalBackend = finalBackendId,
            fallbackUsed = fallbackUsed,
            fallbackReason = fallbackReason,
            alignmentResult = finalResult
        )
    }
}
