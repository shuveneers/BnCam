package com.bncam.core.alignment

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class EccPyramidAlignmentBackend : AlignmentBackend {
    override val id: String = "ECC Pyramid"

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
                transformType = "Euclidean (Rigid)",
                horizontalDisplacement = 0.0f,
                verticalDisplacement = 0.0f,
                rotationDegrees = 0.0f,
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
                transformType = "Euclidean (Rigid)",
                horizontalDisplacement = 0.0f,
                verticalDisplacement = 0.0f,
                rotationDegrees = 0.0f,
                globalConfidence = 0.0f,
                validOverlapPercentage = 0.0f,
                residualAlignmentError = 1.0f,
                rejectionReason = "Low texture image (variance < 10)"
            )
        }

        // Initialize from phase correlation estimate
        val basePhaseCorrelation = PhaseCorrelationAlignmentBackend().align(
            referenceGuidePyramid, candidateGuidePyramid, guideWidth, guideHeight, context
        )

        val initDx = basePhaseCorrelation.horizontalDisplacement
        val initDy = basePhaseCorrelation.verticalDisplacement

        // Pyramidal Euclidean refinement (estimate small rotation angle -2.0 to +2.0 degrees around center)
        val eccResult = refineEuclidean(
            ref = refL0,
            cand = candL0,
            w = guideWidth,
            h = guideHeight,
            initDx = initDx / 4.0f,
            initDy = initDy / 4.0f
        )

        val finalDx = eccResult.first * 4.0f
        val finalDy = eccResult.second * 4.0f
        val finalRot = eccResult.third
        val confidence = (basePhaseCorrelation.globalConfidence * 0.60f + eccResult.fourth * 0.40f).coerceIn(0.10f, 0.99f)
        val durationMs = System.currentTimeMillis() - startMs

        val rejection = when {
            !basePhaseCorrelation.success -> "Base phase correlation failed: ${basePhaseCorrelation.rejectionReason}"
            abs(finalRot) > 5.0f -> "Implausible rotation angle (${finalRot} deg)"
            confidence < 0.35f -> "ECC non-convergence confidence ($confidence)"
            abs(finalDx) > context.maxShiftPixels || abs(finalDy) > context.maxShiftPixels ->
                "ECC displacement exceeds max shift (${finalDx}, ${finalDy})"
            else -> "none"
        }

        val overlapPct = max(0.0f, 100.0f * (1.0f - (abs(finalDx) + abs(finalDy)) / (context.width + context.height)))

        return AlignmentResult(
            backendId = id,
            success = rejection == "none",
            transformType = "Euclidean (Rigid)",
            horizontalDisplacement = finalDx,
            verticalDisplacement = finalDy,
            rotationDegrees = finalRot,
            globalConfidence = confidence,
            validOverlapPercentage = overlapPct,
            residualAlignmentError = (1.0f - confidence).coerceIn(0.0f, 1.0f),
            rejectionReason = rejection,
            processingTimeMs = durationMs
        )
    }

    private fun calculateVariance(data: ByteArray): Float {
        if (data.isEmpty()) return 0.0f
        var sum = 0L
        for (b in data) sum += b.toInt() and 0xFF
        val mean = sum.toFloat() / data.size.toFloat()
        var sumSqDiff = 0.0f
        for (b in data) {
            val diff = (b.toInt() and 0xFF) - mean
            sumSqDiff += diff * diff
        }
        return sumSqDiff / data.size.toFloat()
    }

    private fun refineEuclidean(
        ref: ByteArray,
        cand: ByteArray,
        w: Int,
        h: Int,
        initDx: Float,
        initDy: Float
    ): Quadruple<Float, Float, Float, Float> {
        var bestDx = initDx
        var bestDy = initDy
        var bestRot = 0.0f
        var minDiff = Long.MAX_VALUE
        var count = 0L

        val rotAngles = floatArrayOf(-1.5f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f)
        val cx = w / 2.0f
        val cy = h / 2.0f

        for (rot in rotAngles) {
            val rad = Math.toRadians(rot.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            for (dy in (initDy.toInt() - 2)..(initDy.toInt() + 2)) {
                for (dx in (initDx.toInt() - 2)..(initDx.toInt() + 2)) {
                    var diff = 0L
                    var samples = 0L

                    for (y in 0 until h step 3) {
                        val dyCenter = y - cy
                        for (x in 0 until w step 3) {
                            val dxCenter = x - cx
                            val rx = (dxCenter * cosA - dyCenter * sinA + cx + dx).toInt()
                            val ry = (dxCenter * sinA + dyCenter * cosA + cy + dy).toInt()

                            if (rx in 0 until w && ry in 0 until h) {
                                val rVal = ref[y * w + x].toInt() and 0xFF
                                val cVal = cand[ry * w + rx].toInt() and 0xFF
                                diff += abs(rVal - cVal)
                                samples++
                            }
                        }
                    }

                    if (samples > 0 && diff < minDiff) {
                        minDiff = diff
                        bestDx = dx.toFloat()
                        bestDy = dy.toFloat()
                        bestRot = rot
                        count = samples
                    }
                }
            }
        }

        val avgDiff = if (count > 0) minDiff.toFloat() / count.toFloat() else 255.0f
        val conf = (1.0f - (avgDiff / 45.0f)).coerceIn(0.10f, 0.98f)
        return Quadruple(bestDx, bestDy, bestRot, conf)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
