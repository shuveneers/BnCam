package com.bncam.core.fusion

import com.bncam.core.alignment.AlignmentResult
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

class ReferenceDominantFusionBackend : FusionBackend {
    override val id: String = "Reference Dominant"

    override fun fuse(
        referencePayload: ByteArray,
        secondaryPayloads: List<ByteArray>,
        alignmentResults: List<AlignmentResult>,
        context: FusionContext
    ): FusionResult {
        val startMs = System.currentTimeMillis()
        val totalInput = secondaryPayloads.size + 1

        if (secondaryPayloads.isEmpty() || alignmentResults.isEmpty()) {
            return FusionResult(
                requestedBackendId = id,
                actualBackendId = id,
                success = true,
                fusionApplied = false,
                inputFrameCount = totalInput,
                acceptedFrameCount = 1,
                rejectedFrameCount = secondaryPayloads.size,
                referenceFrameId = "frame_0",
                effectiveFrameContribution = 1.0f,
                perFrameWeights = floatArrayOf(1.0f),
                motionRejectedPixelPercentage = 0.0f,
                confidenceRejectedPixelPercentage = 0.0f,
                validOverlapPercentage = 100.0f,
                fusedPayload = referencePayload,
                fallbackReason = "No secondary frames or alignment results provided",
                processingTimeMs = System.currentTimeMillis() - startMs,
                peakMemoryUsageBytes = referencePayload.size.toLong()
            )
        }

        val acceptedSecondaries = mutableListOf<ByteArray>()
        val acceptedAlignments = mutableListOf<AlignmentResult>()

        for (i in secondaryPayloads.indices) {
            val align = alignmentResults.getOrNull(i)
            if (align != null && align.success && align.globalConfidence >= 0.40f) {
                acceptedSecondaries.add(secondaryPayloads[i])
                acceptedAlignments.add(align)
            }
        }

        if (acceptedSecondaries.isEmpty()) {
            return FusionResult(
                requestedBackendId = id,
                actualBackendId = id,
                success = true,
                fusionApplied = false,
                inputFrameCount = totalInput,
                acceptedFrameCount = 1,
                rejectedFrameCount = secondaryPayloads.size,
                referenceFrameId = "frame_0",
                effectiveFrameContribution = 1.0f,
                perFrameWeights = floatArrayOf(1.0f),
                motionRejectedPixelPercentage = 0.0f,
                confidenceRejectedPixelPercentage = 100.0f,
                validOverlapPercentage = 100.0f,
                fusedPayload = referencePayload,
                fallbackReason = "All secondary frames rejected by strict reference-dominant confidence threshold",
                processingTimeMs = System.currentTimeMillis() - startMs,
                peakMemoryUsageBytes = referencePayload.size.toLong()
            )
        }

        val fusedPayload = referencePayload.clone()
        val numAccepted = acceptedSecondaries.size + 1
        val perFrameWeights = FloatArray(numAccepted)

        val refBaseWeight = 0.70f
        perFrameWeights[0] = refBaseWeight

        var motionRejectedCount = 0L
        var totalPixelsCount = 0L

        val len = referencePayload.size
        for (i in 0 until len step 2) {
            val rVal = (referencePayload[i].toInt() and 0xFF) or ((referencePayload.getOrElse(i + 1) { 0 }.toInt() and 0xFF) shl 8)

            var accum = rVal.toFloat() * refBaseWeight
            var weightSum = refBaseWeight

            for (sIdx in acceptedSecondaries.indices) {
                val sec = acceptedSecondaries[sIdx]
                val sVal = (sec.getOrElse(i) { 0 }.toInt() and 0xFF) or ((sec.getOrElse(i + 1) { 0 }.toInt() and 0xFF) shl 8)
                val align = acceptedAlignments[sIdx]

                val diff = abs(rVal - sVal)
                val decay = exp(-diff.toFloat() / 30.0f)
                val w = (1.0f - refBaseWeight) / acceptedSecondaries.size.toFloat() * decay * align.globalConfidence

                if (diff > 80) {
                    motionRejectedCount++
                }
                totalPixelsCount++

                accum += sVal.toFloat() * w
                weightSum += w
            }

            val finalVal = (accum / max(0.001f, weightSum)).toInt().coerceIn(0, 65535)
            fusedPayload[i] = (finalVal and 0xFF).toByte()
            if (i + 1 < len) {
                fusedPayload[i + 1] = ((finalVal shr 8) and 0xFF).toByte()
            }
        }

        val secShare = (1.0f - refBaseWeight) / acceptedSecondaries.size.toFloat()
        for (k in 1 until numAccepted) {
            perFrameWeights[k] = secShare
        }

        val motionPct = if (totalPixelsCount > 0) (motionRejectedCount.toFloat() / totalPixelsCount.toFloat()) * 100.0f else 0.0f
        val durationMs = System.currentTimeMillis() - startMs

        return FusionResult(
            requestedBackendId = id,
            actualBackendId = id,
            success = true,
            fusionApplied = true,
            inputFrameCount = totalInput,
            acceptedFrameCount = numAccepted,
            rejectedFrameCount = secondaryPayloads.size - acceptedSecondaries.size,
            referenceFrameId = "frame_0",
            effectiveFrameContribution = 1.0f + (numAccepted - 1) * 0.30f,
            perFrameWeights = perFrameWeights,
            motionRejectedPixelPercentage = motionPct,
            confidenceRejectedPixelPercentage = 0.0f,
            validOverlapPercentage = 95.0f,
            fusedPayload = fusedPayload,
            fallbackReason = "none",
            processingTimeMs = durationMs,
            peakMemoryUsageBytes = (len * numAccepted).toLong()
        )
    }
}
