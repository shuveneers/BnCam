package com.bncam.core.alignment

import kotlin.math.abs
import kotlin.math.max

class TilePyramidAlignmentBackend : AlignmentBackend {
    override val id: String = "Tile Pyramid"

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
                transformType = "Local Tile Field",
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

        val gridCols = 4
        val gridRows = 4
        val numTiles = gridCols * gridRows

        val tileW = guideWidth / gridCols
        val tileH = guideHeight / gridRows

        val tileVectors = FloatArray(numTiles * 2)
        val tileConfidences = FloatArray(numTiles)

        var validTileCount = 0
        var totalDx = 0.0f
        var totalDy = 0.0f
        var totalConf = 0.0f

        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                val tileIdx = row * gridCols + col
                val tileX = col * tileW
                val tileY = row * tileH

                // Texture variance check
                val variance = calculateTileVariance(refL0, guideWidth, tileX, tileY, tileW, tileH)
                if (variance < 40.0f) {
                    // Low texture tile -> reject
                    tileConfidences[tileIdx] = 0.0f
                    tileVectors[tileIdx * 2] = 0.0f
                    tileVectors[tileIdx * 2 + 1] = 0.0f
                    continue
                }

                val shift = alignTile(refL0, candL0, guideWidth, guideHeight, tileX, tileY, tileW, tileH, radius = 4)
                val fullDx = shift.first * 4.0f
                val fullDy = shift.second * 4.0f
                val conf = shift.third

                if (conf >= 0.35f && abs(fullDx) <= context.maxShiftPixels && abs(fullDy) <= context.maxShiftPixels) {
                    tileVectors[tileIdx * 2] = fullDx
                    tileVectors[tileIdx * 2 + 1] = fullDy
                    tileConfidences[tileIdx] = conf

                    totalDx += fullDx
                    totalDy += fullDy
                    totalConf += conf
                    validTileCount++
                } else {
                    tileConfidences[tileIdx] = 0.0f
                }
            }
        }

        val globalDx = if (validTileCount > 0) totalDx / validTileCount else 0.0f
        val globalDy = if (validTileCount > 0) totalDy / validTileCount else 0.0f
        val meanConf = if (validTileCount > 0) totalConf / validTileCount else 0.0f
        val coveragePct = (validTileCount.toFloat() / numTiles.toFloat()) * 100.0f
        val durationMs = System.currentTimeMillis() - startMs

        val rejection = when {
            validTileCount < 4 -> "Insufficient valid tiles ($validTileCount / $numTiles)"
            meanConf < 0.35f -> "Low tile field confidence ($meanConf)"
            else -> "none"
        }

        return AlignmentResult(
            backendId = id,
            success = rejection == "none",
            transformType = "Local Tile Field",
            horizontalDisplacement = globalDx,
            verticalDisplacement = globalDy,
            rotationDegrees = 0.0f,
            globalConfidence = meanConf,
            validOverlapPercentage = coveragePct,
            residualAlignmentError = (1.0f - meanConf).coerceIn(0.0f, 1.0f),
            localMotionField = tileVectors,
            tileConfidenceValues = tileConfidences,
            rejectionReason = rejection,
            processingTimeMs = durationMs
        )
    }

    private fun calculateTileVariance(data: ByteArray, stride: Int, tx: Int, ty: Int, tw: Int, th: Int): Float {
        var sum = 0L
        var count = 0L
        for (y in ty until (ty + th) step 2) {
            val row = y * stride
            for (x in tx until (tx + tw) step 2) {
                sum += data[row + x].toInt() and 0xFF
                count++
            }
        }
        if (count <= 0) return 0.0f
        val mean = sum.toFloat() / count.toFloat()

        var sumSqDiff = 0.0f
        for (y in ty until (ty + th) step 2) {
            val row = y * stride
            for (x in tx until (tx + tw) step 2) {
                val v = (data[row + x].toInt() and 0xFF) - mean
                sumSqDiff += v * v
            }
        }
        return sumSqDiff / count.toFloat()
    }

    private fun alignTile(
        ref: ByteArray,
        cand: ByteArray,
        w: Int,
        h: Int,
        tx: Int,
        ty: Int,
        tw: Int,
        th: Int,
        radius: Int
    ): Triple<Float, Float, Float> {
        var bestDx = 0
        var bestDy = 0
        var minDiff = Long.MAX_VALUE
        var count = 0L

        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                var diff = 0L
                var samples = 0L
                val startY = max(ty, ty - dy)
                val endY = minOf(ty + th, ty + th - dy)
                val startX = max(tx, tx - dx)
                val endX = minOf(tx + tw, tx + tw - dx)

                for (y in startY until endY step 2) {
                    val rRow = y * w
                    val cRow = (y + dy) * w
                    for (x in startX until endX step 2) {
                        val rVal = ref[rRow + x].toInt() and 0xFF
                        val cVal = cand[cRow + (x + dx)].toInt() and 0xFF
                        diff += abs(rVal - cVal)
                        samples++
                    }
                }

                if (samples > 0 && diff < minDiff) {
                    minDiff = diff
                    bestDx = dx
                    bestDy = dy
                    count = samples
                }
            }
        }

        val avgDiff = if (count > 0) minDiff.toFloat() / count.toFloat() else 255.0f
        val conf = (1.0f - (avgDiff / 50.0f)).coerceIn(0.10f, 0.98f)
        return Triple(bestDx.toFloat(), bestDy.toFloat(), conf)
    }
}
