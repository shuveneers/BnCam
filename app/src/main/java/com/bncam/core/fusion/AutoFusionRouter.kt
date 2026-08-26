package com.bncam.core.fusion

import com.bncam.core.alignment.AlignmentResult

data class AutoFusionRoutingResult(
    val initialSelectedBackend: String,
    val initialReason: String,
    val finalBackend: String,
    val fallbackUsed: Boolean,
    val fallbackReason: String,
    val fusionResult: FusionResult
)

class AutoFusionRouter : FusionBackend {
    override val id: String = "Auto"

    private val robustWeightedBackend = RobustWeightedAverageFusionBackend()
    private val referenceDominantBackend = ReferenceDominantFusionBackend()
    private val wienerPyramidBackend = WienerPyramidFusionBackend()

    override fun fuse(
        referencePayload: ByteArray,
        secondaryPayloads: List<ByteArray>,
        alignmentResults: List<AlignmentResult>,
        context: FusionContext
    ): FusionResult {
        return routeAndFuse(referencePayload, secondaryPayloads, alignmentResults, context).fusionResult
    }

    fun routeAndFuse(
        referencePayload: ByteArray,
        secondaryPayloads: List<ByteArray>,
        alignmentResults: List<AlignmentResult>,
        context: FusionContext
    ): AutoFusionRoutingResult {
        val startMs = System.currentTimeMillis()

        val avgConfidence = if (alignmentResults.isNotEmpty()) {
            alignmentResults.map { it.globalConfidence }.average().toFloat()
        } else 0.0f

        val lowConfidenceOrMotion = avgConfidence < 0.60f || alignmentResults.any { it.backendId == "Tile Pyramid" && it.validOverlapPercentage < 85.0f }
        val isHighNoise = context.iso >= 400

        val initialSelection: Pair<String, String> = when {
            lowConfidenceOrMotion ->
                "Reference Dominant" to "Scene motion or lower tile alignment confidence detected; prioritizing reference preservation"
            isHighNoise ->
                "Wiener Pyramid" to "High ISO noise detected (ISO=${context.iso}); selecting multi-scale Wiener noise filtering"
            else ->
                "Robust Weighted Average" to "Static scene with high alignment confidence; selecting robust rejection-weighted averaging"
        }

        val initialBackendId = initialSelection.first
        val initialReason = initialSelection.second

        val selectedBackend: FusionBackend = when (initialBackendId) {
            "Reference Dominant" -> referenceDominantBackend
            "Wiener Pyramid" -> wienerPyramidBackend
            else -> robustWeightedBackend
        }

        var result = selectedBackend.fuse(referencePayload, secondaryPayloads, alignmentResults, context)

        var fallbackUsed = false
        var fallbackReason = "none"
        var finalBackendId = initialBackendId

        if (!result.success || !result.fusionApplied) {
            fallbackUsed = true
            fallbackReason = "Preferred fusion backend '$initialBackendId' yielded fallback: ${result.fallbackReason}"

            val fallbackBackend = if (initialBackendId != "Robust Weighted Average") robustWeightedBackend else referenceDominantBackend
            finalBackendId = fallbackBackend.id

            result = fallbackBackend.fuse(referencePayload, secondaryPayloads, alignmentResults, context)
        }

        val totalTime = System.currentTimeMillis() - startMs
        val finalResult = result.copy(processingTimeMs = totalTime)

        return AutoFusionRoutingResult(
            initialSelectedBackend = initialBackendId,
            initialReason = initialReason,
            finalBackend = finalBackendId,
            fallbackUsed = fallbackUsed,
            fallbackReason = fallbackReason,
            fusionResult = finalResult
        )
    }
}
