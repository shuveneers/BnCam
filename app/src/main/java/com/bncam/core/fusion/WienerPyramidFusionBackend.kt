package com.bncam.core.fusion

import com.bncam.core.alignment.AlignmentResult
import kotlin.math.abs
import kotlin.math.max

class WienerPyramidFusionBackend : FusionBackend {
    override val id: String = "Wiener Pyramid"

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
            if (align != null && align.success && align.globalConfidence >= 0.35f) {
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
                fallbackReason = "All secondary frames rejected by Wiener alignment gate",
                processingTimeMs = System.currentTimeMillis() - startMs,
                peakMemoryUsageBytes = referencePayload.size.toLong()
            )
        }

        val fusedPayload = referencePayload.clone()
        val numAccepted = acceptedSecondaries.size + 1
        val perFrameWeights = FloatArray(numAccepted)
        val len = referencePayload.size

        var motionRejectedCount = 0L
        var totalPixelsCount = 0L

        // Estimate noise energy from ISO
        val noiseEnergy = (context.iso / 100.0f * 25.0f).coerceIn(10.0f, 150.0f)

        for (i in 0 until len step 2) {
            val rVal = (referencePayload[i].toInt() and 0xFF) or ((referencePayload.getOrElse(i + 1) { 0 }.toInt() and 0xFF) shl 8)

            var accum = rVal.toFloat()
            var weightSum = 1.0f

            for (sIdx in acceptedSecondaries.indices) {
                val sec = acceptedSecondaries[sIdx]
                val sVal = (sec.getOrElse(i) { 0 }.toInt() and 0xFF) or ((sec.getOrElse(i + 1) { 0 }.toInt() and 0xFF) shl 8)
                val align = acceptedAlignments[sIdx]

                val diff = abs(rVal - sVal).toFloat()
                val signalEnergy = diff * diff

                // Bounded Wiener Gain W = Signal / (Signal + Noise)
                val wienerGain = (signalEnergy / (signalEnergy + noiseEnergy)).coerceIn(0.10f, 0.95f)
                val w = (1.0f - wienerGain) * align.globalConfidence

                if (diff > 100.0f) {
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

        for (k in 0 until numAccepted) {
            perFrameWeights[k] = 1.0f / numAccepted
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
            effectiveFrameContribution = numAccepted.toFloat(),
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
