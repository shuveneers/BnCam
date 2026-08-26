package com.bncam.core.alignment

import kotlin.math.abs
import kotlin.math.max

class PhaseCorrelationAlignmentBackend : AlignmentBackend {
    override val id: String = "Phase Correlation Pyramid"

    override fun align(
        referenceGuidePyramid: List<ByteArray>,
        candidateGuidePyramid: List<ByteArray>,
        guideWidth: Int,
        guideHeight: Int,
        context: AlignmentContext
    ): AlignmentResult {
        val startMs = System.currentTimeMillis()
        if (referenceGuidePyramid.isEmpty() || candidateGuidePyramid.isEmpty()) {
            return AlignmentResult(
                backendId = id,
                success = false,
                transformType = "Translation",
                horizontalDisplacement = 0.0f,
                verticalDisplacement = 0.0f,
                globalConfidence = 0.0f,
                validOverlapPercentage = 0.0f,
                residualAlignmentError = 1.0f,
                rejectionReason = "Empty guide pyramid"
            )
        }

        val refL0 = referenceGuidePyramid[0]
        val candL0 = candidateGuidePyramid[0]

        // Texture variance check
        val refVar = calculateVariance(refL0)
        val candVar = calculateVariance(candL0)

        if (refVar < 10.0f || candVar < 10.0f) {
            return AlignmentResult(
                backendId = id,
                success = false,
                transformType = "Translation",
                horizontalDisplacement = 0.0f,
                verticalDisplacement = 0.0f,
                globalConfidence = 0.0f,
                validOverlapPercentage = 0.0f,
                residualAlignmentError = 1.0f,
                rejectionReason = "Low texture image (variance < 10)"
            )
        }

        // Coarse level (Level 2) search
        val refL2 = referenceGuidePyramid.getOrNull(2) ?: referenceGuidePyramid.last()
        val candL2 = candidateGuidePyramid.getOrNull(2) ?: candidateGuidePyramid.last()
        val l2W = max(16, guideWidth / 4)
        val l2H = max(12, guideHeight / 4)

        val coarseShift = findTranslation(refL2, candL2, l2W, l2H, searchRadius = 8)

        // Fine level (Level 0) search
        val fineAnchorX = (coarseShift.first * 4).coerceIn(-context.maxShiftPixels, context.maxShiftPixels)
        val fineAnchorY = (coarseShift.second * 4).coerceIn(-context.maxShiftPixels, context.maxShiftPixels)

        val fineShift = findTranslationAround(refL0, candL0, guideWidth, guideHeight, fineAnchorX, fineAnchorY, searchRadius = 4)

        val fullShiftX = (fineShift.first * 4.0f)
        val fullShiftY = (fineShift.second * 4.0f)
        val confidence = fineShift.third

        val overlapPct = max(0.0f, 100.0f * (1.0f - (abs(fullShiftX) + abs(fullShiftY)) / (context.width + context.height)))
        val residualErr = (1.0f - confidence).coerceIn(0.0f, 1.0f)
        val durationMs = System.currentTimeMillis() - startMs

        val rejectedReason = when {
            confidence < 0.35f -> "Low correlation peak confidence ($confidence)"
            abs(fullShiftX) > context.maxShiftPixels || abs(fullShiftY) > context.maxShiftPixels ->
                "Displacement exceeds max shift (${fullShiftX}, ${fullShiftY})"
            else -> "none"
        }

        return AlignmentResult(
            backendId = id,
            success = rejectedReason == "none",
            transformType = "Translation",
            horizontalDisplacement = fullShiftX,
            verticalDisplacement = fullShiftY,
            rotationDegrees = 0.0f,
            globalConfidence = confidence,
            validOverlapPercentage = overlapPct,
            residualAlignmentError = residualErr,
            rejectionReason = rejectedReason,
            processingTimeMs = durationMs
        )
    }

    private fun calculateVariance(data: ByteArray): Float {
        if (data.isEmpty()) return 0.0f
        var sum = 0L
        for (b in data) {
            sum += b.toInt() and 0xFF
        }
        val mean = sum.toFloat() / data.size.toFloat()

        var sumSqDiff = 0.0f
        for (b in data) {
            val diff = (b.toInt() and 0xFF) - mean
            sumSqDiff += diff * diff
        }
        return sumSqDiff / data.size.toFloat()
    }

    private fun findTranslation(ref: ByteArray, cand: ByteArray, w: Int, h: Int, searchRadius: Int): Pair<Int, Int> {
        var bestX = 0
        var bestY = 0
        var minDiff = Long.MAX_VALUE

        for (dy in -searchRadius..searchRadius) {
            for (dx in -searchRadius..searchRadius) {
                var diff = 0L
                val startY = max(0, -dy)
                val endY = minOf(h, h - dy)
                val startX = max(0, -dx)
                val endX = minOf(w, w - dx)

                for (y in startY until endY step 2) {
                    val rRow = y * w
                    val cRow = (y + dy) * w
                    for (x in startX until endX step 2) {
                        val rVal = ref[rRow + x].toInt() and 0xFF
                        val cVal = cand[cRow + (x + dx)].toInt() and 0xFF
                        diff += abs(rVal - cVal)
                    }
                }

                if (diff < minDiff) {
                    minDiff = diff
                    bestX = dx
                    bestY = dy
                }
            }
        }
        return bestX to bestY
    }

    private fun findTranslationAround(
        ref: ByteArray,
        cand: ByteArray,
        w: Int,
        h: Int,
        centerX: Int,
        centerY: Int,
        searchRadius: Int
    ): Triple<Float, Float, Float> {
        var bestX = centerX
        var bestY = centerY
        var minDiff = Long.MAX_VALUE
        var totalSamples = 0L

        for (dy in (centerY - searchRadius)..(centerY + searchRadius)) {
            for (dx in (centerX - searchRadius)..(centerX + searchRadius)) {
                var diff = 0L
                var count = 0L
                val startY = max(0, -dy)
                val endY = minOf(h, h - dy)
                val startX = max(0, -dx)
                val endX = minOf(w, w - dx)

                for (y in startY until endY step 2) {
                    val rRow = y * w
                    val cRow = (y + dy) * w
                    for (x in startX until endX step 2) {
                        val rVal = ref[rRow + x].toInt() and 0xFF
                        val cVal = cand[cRow + (x + dx)].toInt() and 0xFF
                        diff += abs(rVal - cVal)
                        count++
                    }
                }

                if (count > 0 && diff < minDiff) {
                    minDiff = diff
                    bestX = dx
                    bestY = dy
                    totalSamples = count
                }
            }
        }

        val avgDiffPerPixel = if (totalSamples > 0) minDiff.toFloat() / totalSamples else 255.0f
        val confidence = (1.0f - (avgDiffPerPixel / 64.0f)).coerceIn(0.10f, 0.98f)

        return Triple(bestX.toFloat(), bestY.toFloat(), confidence)
    }
}
