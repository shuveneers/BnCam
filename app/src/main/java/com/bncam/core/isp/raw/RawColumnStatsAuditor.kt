package com.bncam.core.isp.raw

import android.graphics.ImageFormat
import android.media.Image
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Non-destructive RAW edge/payload statistics auditor.
 *
 * Historical diagnostics compared columns 0..383 against 384..W-1. That remains available for
 * backwards-compatible debug, but payload resolution is now genuinely two-dimensional: columns
 * AND rows are inspected so sensor-orientation cannot turn an undetected RAW row padding band into
 * a visible left/right block after output rotation.
 */
object RawColumnStatsAuditor {
    private const val TAG = "RawColumnStatsAuditor"

    data class ColumnStats(
        val stage: String,
        val width: Int,
        val height: Int,
        val firstNonZeroColumn: Int,
        val lastNonZeroColumn: Int,
        val column0_383Mean: Double,
        val column0_383NonZeroCount: Long,
        val column384_EndMean: Double,
        val column384_EndNonZeroCount: Long,
        val isFullFrame: Boolean = true,
        val leadingNearBlackColumns: Int = 0,
        val trailingNearBlackColumns: Int = 0,
        val estimatedPayloadWidth: Int = width,
        val adaptiveEdgeThreshold: Double = 0.0,
        val edgePaddingConfidence: Double = 0.0,
        val firstNonZeroRow: Int = -1,
        val lastNonZeroRow: Int = -1,
        val leadingNearBlackRows: Int = 0,
        val trailingNearBlackRows: Int = 0,
        val estimatedPayloadHeight: Int = height,
        val adaptiveRowEdgeThreshold: Double = 0.0,
        val rowEdgePaddingConfidence: Double = 0.0,
        val rowCenterReferenceMean: Double = 0.0,
        val leadingRowBandMean: Double = 0.0,
        val trailingRowBandMean: Double = 0.0,
        val leadingRowBandStdDev: Double = 0.0,
        val trailingRowBandStdDev: Double = 0.0
    ) {
        fun compact(): String =
            "STAGE_COLUMN_AUDIT stage=$stage dimensions=${width}x${height} " +
                "evalMode=${if (isFullFrame) "FULL_FRAME_100_PERCENT" else "NON_DESTRUCTIVE_EDGE_SAMPLED"} " +
                "firstNonZeroCol=$firstNonZeroColumn lastNonZeroCol=$lastNonZeroColumn " +
                "col0_383Mean=${fmt(column0_383Mean)} col0_383NonZero=$column0_383NonZeroCount " +
                "col384_EndMean=${fmt(column384_EndMean)} col384_EndNonZero=$column384_EndNonZeroCount " +
                "adaptiveLeadingNearBlackCols=$leadingNearBlackColumns " +
                "adaptiveTrailingNearBlackCols=$trailingNearBlackColumns " +
                "estimatedPayloadWidth=$estimatedPayloadWidth " +
                "adaptiveColumnThreshold=${fmt(adaptiveEdgeThreshold)} " +
                "columnEdgeConfidence=${fmt3(edgePaddingConfidence)} " +
                "firstNonZeroRow=$firstNonZeroRow lastNonZeroRow=$lastNonZeroRow " +
                "adaptiveLeadingNearBlackRows=$leadingNearBlackRows " +
                "adaptiveTrailingNearBlackRows=$trailingNearBlackRows " +
                "estimatedPayloadHeight=$estimatedPayloadHeight " +
                "rowReferenceMean=${fmt(rowCenterReferenceMean)} " +
                "leadingRowBandMean=${fmt(leadingRowBandMean)} trailingRowBandMean=${fmt(trailingRowBandMean)} " +
                "leadingRowBandStdDev=${fmt(leadingRowBandStdDev)} trailingRowBandStdDev=${fmt(trailingRowBandStdDev)} " +
                "adaptiveRowThreshold=${fmt(adaptiveRowEdgeThreshold)} " +
                "rowEdgeConfidence=${fmt3(rowEdgePaddingConfidence)}"
    }

    private data class EdgePaddingEstimate(
        val leading: Int,
        val trailing: Int,
        val threshold: Double,
        val confidence: Double,
        val reference: Double
    )

    private data class BandMoments(val mean: Double, val stdDev: Double)

    private fun estimateEdgePadding(means: DoubleArray): EdgePaddingEstimate {
        if (means.size < 16) return EdgePaddingEstimate(0, 0, 0.0, 0.0, 0.0)
        val length = means.size
        val centerStart = length / 4
        val centerEnd = (length * 3) / 4
        val center = means.copyOfRange(centerStart, centerEnd).sorted()
        val reference = if (center.isNotEmpty()) center[center.size / 2] else 0.0
        if (reference <= 1.0) return EdgePaddingEstimate(0, 0, 0.0, 0.0, reference)

        // True allocation/payload padding can be zero-filled OR sit at the sensor black floor.
        // Estimate that floor from the low tail and place the threshold a small distance above it,
        // rather than assuming that black means numerically close to zero.
        val sortedAll = means.sorted()
        val lowIndex = ((sortedAll.size - 1) * 0.05).toInt().coerceIn(0, sortedAll.lastIndex)
        val floorReference = sortedAll[lowIndex].coerceAtLeast(0.0)
        val dynamicRange = (reference - floorReference).coerceAtLeast(0.0)
        if (dynamicRange <= maxOf(1.0, reference * 0.03)) {
            return EdgePaddingEstimate(0, 0, 0.0, 0.0, reference)
        }
        val threshold = floorReference + maxOf(1.0, dynamicRange * 0.12)
        var leading = 0
        while (leading < length && means[leading] <= threshold) leading++
        var trailing = 0
        while (trailing < length - leading && means[length - 1 - trailing] <= threshold) trailing++

        val minimumBand = maxOf(8, length / 100)
        if (leading < minimumBand) leading = 0
        if (trailing < minimumBand) trailing = 0
        val strongestBand = maxOf(leading, trailing)
        val confidence = if (strongestBand == 0) {
            0.0
        } else {
            val bandFraction = strongestBand.toDouble() / length.toDouble()
            val separation = ((reference - threshold) / dynamicRange.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            (separation * (bandFraction / 0.08).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        }
        return EdgePaddingEstimate(leading, trailing, threshold, confidence, reference)
    }

    private fun bandMoments(values: DoubleArray, start: Int, endExclusive: Int): BandMoments {
        val startSafe = start.coerceIn(0, values.size)
        val endSafe = endExclusive.coerceIn(startSafe, values.size)
        if (endSafe <= startSafe) return BandMoments(0.0, 0.0)
        var sum = 0.0
        for (i in startSafe until endSafe) sum += values[i]
        val mean = sum / (endSafe - startSafe).toDouble()
        var variance = 0.0
        for (i in startSafe until endSafe) {
            val d = values[i] - mean
            variance += d * d
        }
        variance /= (endSafe - startSafe).toDouble()
        return BandMoments(mean, sqrt(variance.coerceAtLeast(0.0)))
    }

    fun auditRaw16ByteArray(stage: String, raw16Bytes: ByteArray, width: Int, height: Int): ColumnStats {
        if (width <= 0 || height <= 0 || raw16Bytes.size.toLong() < width.toLong() * height.toLong() * 2L) {
            val stats = ColumnStats(stage, width, height, -1, -1, 0.0, 0, 0.0, 0)
            Log.w(TAG, "${stats.compact()} (invalid buffer)")
            return stats
        }

        val shortBuffer = ByteBuffer.wrap(raw16Bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val columnSums = LongArray(width)
        val columnCounts = IntArray(width)
        var firstNonZeroColumn = -1
        var lastNonZeroColumn = -1
        var colSum0_383 = 0L
        var nonZeroCount0_383 = 0L
        var colSum384_End = 0L
        var nonZeroCount384_End = 0L
        val checkBoundary = minOf(384, width)

        val sampledRows = minOf(height, 64)
        val stepY = maxOf(1, height / sampledRows)
        var actualRows = 0
        for (y in 0 until height step stepY) {
            actualRows++
            val rowOffset = y * width
            for (x in 0 until width) {
                val value = shortBuffer.get(rowOffset + x).toInt() and 0xFFFF
                columnSums[x] += value.toLong()
                columnCounts[x]++
                if (value > 0) {
                    if (firstNonZeroColumn < 0 || x < firstNonZeroColumn) firstNonZeroColumn = x
                    if (x > lastNonZeroColumn) lastNonZeroColumn = x
                }
                if (x < checkBoundary) {
                    colSum0_383 += value.toLong()
                    if (value > 0) nonZeroCount0_383++
                } else {
                    colSum384_End += value.toLong()
                    if (value > 0) nonZeroCount384_End++
                }
            }
        }

        val columnMeans = DoubleArray(width) { x ->
            if (columnCounts[x] > 0) columnSums[x].toDouble() / columnCounts[x].toDouble() else 0.0
        }
        val columnEdge = estimateEdgePadding(columnMeans)

        // Audit every row using a bounded set of columns. This is the crucial orthogonal audit:
        // after a 90/270 degree output rotation a RAW top/bottom padding band becomes a visible
        // left/right block, which a column-only auditor can never detect.
        val sampledColumns = minOf(width, 64)
        val stepX = maxOf(1, width / sampledColumns)
        val rowMeans = DoubleArray(height)
        var firstNonZeroRow = -1
        var lastNonZeroRow = -1
        for (y in 0 until height) {
            var sum = 0L
            var count = 0
            var rowHasNonZero = false
            val rowOffset = y * width
            for (x in 0 until width step stepX) {
                val value = shortBuffer.get(rowOffset + x).toInt() and 0xFFFF
                sum += value.toLong()
                count++
                if (value > 0) rowHasNonZero = true
            }
            rowMeans[y] = if (count > 0) sum.toDouble() / count.toDouble() else 0.0
            if (rowHasNonZero) {
                if (firstNonZeroRow < 0) firstNonZeroRow = y
                lastNonZeroRow = y
            }
        }
        val rowEdge = estimateEdgePadding(rowMeans)
        val leadingRowMoments = bandMoments(rowMeans, 0, rowEdge.leading)
        val trailingRowMoments = bandMoments(rowMeans, height - rowEdge.trailing, height)

        val count0_383 = (actualRows.toLong() * checkBoundary).coerceAtLeast(1L)
        val count384End = (actualRows.toLong() * (width - checkBoundary).coerceAtLeast(1)).coerceAtLeast(1L)
        val stats = ColumnStats(
            stage = stage,
            width = width,
            height = height,
            firstNonZeroColumn = firstNonZeroColumn,
            lastNonZeroColumn = lastNonZeroColumn,
            column0_383Mean = colSum0_383.toDouble() / count0_383.toDouble(),
            column0_383NonZeroCount = nonZeroCount0_383,
            column384_EndMean = colSum384_End.toDouble() / count384End.toDouble(),
            column384_EndNonZeroCount = nonZeroCount384_End,
            isFullFrame = false,
            leadingNearBlackColumns = columnEdge.leading,
            trailingNearBlackColumns = columnEdge.trailing,
            estimatedPayloadWidth = (width - columnEdge.leading - columnEdge.trailing).coerceAtLeast(0),
            adaptiveEdgeThreshold = columnEdge.threshold,
            edgePaddingConfidence = columnEdge.confidence,
            firstNonZeroRow = firstNonZeroRow,
            lastNonZeroRow = lastNonZeroRow,
            leadingNearBlackRows = rowEdge.leading,
            trailingNearBlackRows = rowEdge.trailing,
            estimatedPayloadHeight = (height - rowEdge.leading - rowEdge.trailing).coerceAtLeast(0),
            adaptiveRowEdgeThreshold = rowEdge.threshold,
            rowEdgePaddingConfidence = rowEdge.confidence,
            rowCenterReferenceMean = rowEdge.reference,
            leadingRowBandMean = leadingRowMoments.mean,
            trailingRowBandMean = trailingRowMoments.mean,
            leadingRowBandStdDev = leadingRowMoments.stdDev,
            trailingRowBandStdDev = trailingRowMoments.stdDev
        )
        Log.i(TAG, stats.compact())
        com.bncam.core.debug.DeviceTelemetryLogger.logEvent("STAGE_COLUMN_AUDIT", stats.compact())
        return stats
    }

    fun auditImagePlaneStageA(stage: String, image: Image, width: Int, height: Int): ColumnStats? {
        return try {
            val planes = image.planes
            if (planes.isEmpty() || width <= 0 || height <= 0) return null
            val plane = planes[0]
            val buffer = plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            // Image.Plane ByteBuffer data begins at its current position. Use that position as the
            // absolute base offset; index zero is not guaranteed to be the first plane byte.
            val baseOffset = buffer.position()
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            fun sampleAt(x: Int, y: Int): Int? {
                return when (image.format) {
                    ImageFormat.RAW10 -> {
                        val groupBase = baseOffset + y * rowStride + (x / 4) * 5
                        val inGroup = x and 3
                        if (groupBase < baseOffset || groupBase + 4 >= buffer.limit()) return null
                        val msb = buffer.get(groupBase + inGroup).toInt() and 0xFF
                        val lsbPack = buffer.get(groupBase + 4).toInt() and 0xFF
                        // Android RAW10 byte 4 stores P0 LSBs in bits 1:0, P1 in 3:2,
                        // P2 in 5:4 and P3 in 7:6.
                        val shift = inGroup * 2
                        (msb shl 2) or ((lsbPack ushr shift) and 0x03)
                    }
                    ImageFormat.RAW_SENSOR -> {
                        val stride = pixelStride.coerceAtLeast(2)
                        val offset = baseOffset + y * rowStride + x * stride
                        if (offset < baseOffset || offset + 1 >= buffer.limit()) return null
                        buffer.getShort(offset).toInt() and 0xFFFF
                    }
                    else -> {
                        val stride = pixelStride.coerceAtLeast(1)
                        val offset = baseOffset + y * rowStride + x * stride
                        if (offset < baseOffset || offset >= buffer.limit()) return null
                        buffer.get(offset).toInt() and 0xFF
                    }
                }
            }

            val columnSums = LongArray(width)
            val columnCounts = IntArray(width)
            var firstNonZeroColumn = -1
            var lastNonZeroColumn = -1
            var colSum0_383 = 0L
            var nonZero0_383 = 0L
            var colSum384End = 0L
            var nonZero384End = 0L
            val checkBoundary = minOf(384, width)

            val sampleRows = minOf(height, 64)
            val stepY = maxOf(1, height / sampleRows)
            var actualRows = 0
            for (y in 0 until height step stepY) {
                actualRows++
                for (x in 0 until width) {
                    val value = sampleAt(x, y) ?: continue
                    columnSums[x] += value.toLong()
                    columnCounts[x]++
                    if (value > 0) {
                        if (firstNonZeroColumn < 0 || x < firstNonZeroColumn) firstNonZeroColumn = x
                        if (x > lastNonZeroColumn) lastNonZeroColumn = x
                    }
                    if (x < checkBoundary) {
                        colSum0_383 += value.toLong()
                        if (value > 0) nonZero0_383++
                    } else {
                        colSum384End += value.toLong()
                        if (value > 0) nonZero384End++
                    }
                }
            }
            val columnMeans = DoubleArray(width) { x ->
                if (columnCounts[x] > 0) columnSums[x].toDouble() / columnCounts[x].toDouble() else 0.0
            }
            val columnEdge = estimateEdgePadding(columnMeans)

            val sampleColumns = minOf(width, 64)
            val stepX = maxOf(1, width / sampleColumns)
            val rowMeans = DoubleArray(height)
            var firstNonZeroRow = -1
            var lastNonZeroRow = -1
            for (y in 0 until height) {
                var sum = 0L
                var count = 0
                var rowHasNonZero = false
                for (x in 0 until width step stepX) {
                    val value = sampleAt(x, y) ?: continue
                    sum += value.toLong()
                    count++
                    if (value > 0) rowHasNonZero = true
                }
                rowMeans[y] = if (count > 0) sum.toDouble() / count.toDouble() else 0.0
                if (rowHasNonZero) {
                    if (firstNonZeroRow < 0) firstNonZeroRow = y
                    lastNonZeroRow = y
                }
            }
            val rowEdge = estimateEdgePadding(rowMeans)
            val leadingRowMoments = bandMoments(rowMeans, 0, rowEdge.leading)
            val trailingRowMoments = bandMoments(rowMeans, height - rowEdge.trailing, height)

            val count0 = (actualRows.toLong() * checkBoundary).coerceAtLeast(1L)
            val countRest = (actualRows.toLong() * (width - checkBoundary).coerceAtLeast(1)).coerceAtLeast(1L)
            val stats = ColumnStats(
                stage = stage,
                width = width,
                height = height,
                firstNonZeroColumn = firstNonZeroColumn,
                lastNonZeroColumn = lastNonZeroColumn,
                column0_383Mean = colSum0_383.toDouble() / count0.toDouble(),
                column0_383NonZeroCount = nonZero0_383,
                column384_EndMean = colSum384End.toDouble() / countRest.toDouble(),
                column384_EndNonZeroCount = nonZero384End,
                isFullFrame = false,
                leadingNearBlackColumns = columnEdge.leading,
                trailingNearBlackColumns = columnEdge.trailing,
                estimatedPayloadWidth = (width - columnEdge.leading - columnEdge.trailing).coerceAtLeast(0),
                adaptiveEdgeThreshold = columnEdge.threshold,
                edgePaddingConfidence = columnEdge.confidence,
                firstNonZeroRow = firstNonZeroRow,
                lastNonZeroRow = lastNonZeroRow,
                leadingNearBlackRows = rowEdge.leading,
                trailingNearBlackRows = rowEdge.trailing,
                estimatedPayloadHeight = (height - rowEdge.leading - rowEdge.trailing).coerceAtLeast(0),
                adaptiveRowEdgeThreshold = rowEdge.threshold,
                rowEdgePaddingConfidence = rowEdge.confidence,
                rowCenterReferenceMean = rowEdge.reference,
                leadingRowBandMean = leadingRowMoments.mean,
                trailingRowBandMean = trailingRowMoments.mean,
                leadingRowBandStdDev = leadingRowMoments.stdDev,
                trailingRowBandStdDev = trailingRowMoments.stdDev
            )
            val detail = "${stats.compact()} sourceFormat=${image.format} rowStride=$rowStride " +
                "pixelStride=$pixelStride planeBaseOffset=$baseOffset bufferLimit=${buffer.limit()}"
            Log.i(TAG, detail)
            com.bncam.core.debug.DeviceTelemetryLogger.logEvent("STAGE_COLUMN_AUDIT", detail)
            stats
        } catch (t: Throwable) {
            Log.w(TAG, "Stage A non-destructive audit skipped: ${t.message}")
            null
        }
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
    private fun fmt3(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)
}
