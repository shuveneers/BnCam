package com.bncam.core.runtime

import android.graphics.Rect
import com.bncam.core.isp.raw.RawColumnStatsAuditor

/**
 * Resolves the actually visible RAW payload inside an acquired Camera2 Image buffer.
 *
 * Resolution order is deliberately conservative:
 *  1. A non-full Image.cropRect supplied by the HAL is authoritative.
 *  2. Otherwise, a Stage-A edge audit may remove only strongly evidenced X/Y padding.
 *  3. If neither source proves padding, the existing Camera2-derived geometry is preserved.
 *
 * This class never invents a device-specific crop width, height or offset.
 */
object RawVisiblePayloadResolver {
    enum class Source {
        IMAGE_CROP_RECT,
        STAGE_A_EDGE_PADDING,
        EXISTING_GEOMETRY
    }

    data class Decision(
        val visibleRect: Rect,
        val source: Source,
        val confidence: Double,
        val reason: String
    )

    fun resolve(
        bufferWidth: Int,
        bufferHeight: Int,
        existingVisibleRect: Rect,
        imageCropRect: Rect?,
        stageA: RawColumnStatsAuditor.ColumnStats?
    ): Decision {
        val bounds = Rect(0, 0, bufferWidth.coerceAtLeast(0), bufferHeight.coerceAtLeast(0))
        val existing = sanitize(existingVisibleRect, bounds) ?: bounds
        if (bounds.width() < 2 || bounds.height() < 2) {
            return Decision(existing, Source.EXISTING_GEOMETRY, 0.0, "invalid_buffer_bounds")
        }

        val halCrop = imageCropRect?.let { sanitize(it, bounds) }
        if (halCrop != null && halCrop != bounds) {
            val intersected = intersect(existing, halCrop) ?: halCrop
            if (isUsable(intersected, bounds)) {
                return Decision(
                    visibleRect = intersected,
                    source = Source.IMAGE_CROP_RECT,
                    confidence = 1.0,
                    reason = "camera2_image_crop_rect=${rectString(halCrop)}"
                )
            }
        }

        val stats = stageA
        if (stats != null && stats.width == bufferWidth && stats.height == bufferHeight) {
            val audited = resolveAuditedPadding(bounds, existing, stats)
            if (audited != null) return audited
        }

        return Decision(
            visibleRect = existing,
            source = Source.EXISTING_GEOMETRY,
            confidence = 0.0,
            reason = "no_proven_edge_padding"
        )
    }

    private fun resolveAuditedPadding(
        bounds: Rect,
        existing: Rect,
        stats: RawColumnStatsAuditor.ColumnStats
    ): Decision? {
        val width = bounds.width()
        val height = bounds.height()

        val acceptedLeadingColumns = resolveLeadingColumns(width, stats)
        val acceptedTrailingColumns = resolveTrailingColumns(width, stats)
        val acceptedLeadingRows = resolveLeadingRows(height, stats)
        val acceptedTrailingRows = resolveTrailingRows(height, stats)

        if (acceptedLeadingColumns == 0 && acceptedTrailingColumns == 0 &&
            acceptedLeadingRows == 0 && acceptedTrailingRows == 0
        ) return null

        // A payload resolver is allowed to remove allocation/padding edges, never the majority of
        // a frame. Per-axis caps also prevent a pathological dark scene from collapsing geometry.
        if (acceptedLeadingColumns + acceptedTrailingColumns > (width * 0.45).toInt()) return null
        if (acceptedLeadingRows + acceptedTrailingRows > (height * 0.45).toInt()) return null

        val auditRect = Rect(
            acceptedLeadingColumns,
            acceptedLeadingRows,
            width - acceptedTrailingColumns,
            height - acceptedTrailingRows
        )
        val candidate = intersect(existing, auditRect) ?: return null
        if (!isUsable(candidate, bounds)) return null
        if (candidate.width() < width / 2 || candidate.height() < height / 2) return null

        val xConfidence = if (acceptedLeadingColumns > 0 || acceptedTrailingColumns > 0) {
            stats.edgePaddingConfidence
        } else 1.0
        val yConfidence = if (acceptedLeadingRows > 0 || acceptedTrailingRows > 0) {
            stats.rowEdgePaddingConfidence
        } else 1.0
        val confidence = minOf(xConfidence, yConfidence).coerceIn(0.0, 1.0)

        // Stage-A is heuristic. It may trim only complete 2x2 Bayer cells relative to the
        // already-established RAW geometry. An odd crop origin or odd number of removed rows /
        // columns changes CFA phase and can turn a valid RAW frame into a severe magenta/green
        // channel error downstream. Authoritative HAL cropRect handling remains separate above.
        if (!preservesBayerCellParity(existing, candidate)) {
            return Decision(
                visibleRect = existing,
                source = Source.EXISTING_GEOMETRY,
                confidence = confidence,
                reason = "stageA_rejected_bayer_cell_parity " +
                    "candidate=${rectString(candidate)} existing=${rectString(existing)}"
            )
        }

        return Decision(
            visibleRect = candidate,
            source = Source.STAGE_A_EDGE_PADDING,
            confidence = confidence,
            reason = "stageA_edges=" +
                "left=$acceptedLeadingColumns right=$acceptedTrailingColumns " +
                "top=$acceptedLeadingRows bottom=$acceptedTrailingRows " +
                "firstNonZeroCol=${stats.firstNonZeroColumn} lastNonZeroCol=${stats.lastNonZeroColumn} " +
                "firstNonZeroRow=${stats.firstNonZeroRow} lastNonZeroRow=${stats.lastNonZeroRow} " +
                "columnConfidence=${fmt3(stats.edgePaddingConfidence)} " +
                "rowConfidence=${fmt3(stats.rowEdgePaddingConfidence)} " +
                "rowReference=${fmt2(stats.rowCenterReferenceMean)} " +
                "bottomMean=${fmt2(stats.trailingRowBandMean)}"
        )
    }

    private fun resolveLeadingColumns(
        width: Int,
        stats: RawColumnStatsAuditor.ColumnStats
    ): Int {
        if (stats.edgePaddingConfidence < 0.80) return 0
        val minimumBand = maxOf(16, width / 100)
        val reported = stats.leadingNearBlackColumns.coerceIn(0, width - 1)
        if (reported < minimumBand) return 0
        val zeroBoundaryEvidence =
            stats.firstNonZeroColumn >= 0 &&
                stats.firstNonZeroColumn >= (reported - 4).coerceAtLeast(0)
        val strongHistoricalBandSeparation =
            stats.column384_EndMean > 1.0 &&
                stats.column0_383Mean <= stats.column384_EndMean * 0.20
        val meanEvidence = reported >= maxOf(minimumBand, width / 16) && strongHistoricalBandSeparation
        return if (zeroBoundaryEvidence || meanEvidence) reported else 0
    }

    private fun resolveTrailingColumns(
        width: Int,
        stats: RawColumnStatsAuditor.ColumnStats
    ): Int {
        if (stats.edgePaddingConfidence < 0.80) return 0
        val minimumBand = maxOf(16, width / 100)
        val reported = stats.trailingNearBlackColumns.coerceIn(0, width - 1)
        if (reported < minimumBand) return 0
        val expectedLastPayloadColumn = width - reported - 1
        val boundaryEvidence =
            stats.lastNonZeroColumn >= 0 &&
                stats.lastNonZeroColumn <= expectedLastPayloadColumn + 4
        return if (boundaryEvidence) reported else 0
    }

    private fun resolveLeadingRows(
        height: Int,
        stats: RawColumnStatsAuditor.ColumnStats
    ): Int {
        if (stats.rowEdgePaddingConfidence < 0.80) return 0
        val minimumBand = maxOf(16, height / 100)
        val reported = stats.leadingNearBlackRows.coerceIn(0, height - 1)
        if (reported < minimumBand) return 0
        val zeroBoundaryEvidence =
            stats.firstNonZeroRow >= 0 &&
                stats.firstNonZeroRow >= (reported - 4).coerceAtLeast(0)
        val blackFloorEvidence = rowBandLooksLikePadding(
            bandMean = stats.leadingRowBandMean,
            bandStdDev = stats.leadingRowBandStdDev,
            rowReference = stats.rowCenterReferenceMean,
            threshold = stats.adaptiveRowEdgeThreshold,
            bandSize = reported,
            axisLength = height
        )
        return if (zeroBoundaryEvidence || blackFloorEvidence) reported else 0
    }

    private fun resolveTrailingRows(
        height: Int,
        stats: RawColumnStatsAuditor.ColumnStats
    ): Int {
        if (stats.rowEdgePaddingConfidence < 0.80) return 0
        val minimumBand = maxOf(16, height / 100)
        val reported = stats.trailingNearBlackRows.coerceIn(0, height - 1)
        if (reported < minimumBand) return 0
        val expectedLastPayloadRow = height - reported - 1
        val zeroBoundaryEvidence =
            stats.lastNonZeroRow >= 0 &&
                stats.lastNonZeroRow <= expectedLastPayloadRow + 4
        val blackFloorEvidence = rowBandLooksLikePadding(
            bandMean = stats.trailingRowBandMean,
            bandStdDev = stats.trailingRowBandStdDev,
            rowReference = stats.rowCenterReferenceMean,
            threshold = stats.adaptiveRowEdgeThreshold,
            bandSize = reported,
            axisLength = height
        )
        return if (zeroBoundaryEvidence || blackFloorEvidence) reported else 0
    }

    private fun rowBandLooksLikePadding(
        bandMean: Double,
        bandStdDev: Double,
        rowReference: Double,
        threshold: Double,
        bandSize: Int,
        axisLength: Int
    ): Boolean {
        if (rowReference <= 1.0 || bandSize < maxOf(16, axisLength / 16)) return false
        if (bandMean > threshold * 1.10) return false
        // A flat, darker part of the photographed scene is not proof of hidden RAW padding.
        // Mean-only evidence is accepted only when the candidate band is on a strongly separated
        // black floor (at most half the centre reference). If the scene is too dark to prove that
        // separation, preserving the complete sensor payload is the safe/correct fallback.
        if (bandMean * 2.0 > rowReference) return false
        // Padding/optical-black rows should form a geometrically flat floor. Keep this guard
        // relative so RAW10 and RAW_SENSOR numeric domains both work without device constants.
        val allowedStdDev = maxOf(2.0, (rowReference - bandMean).coerceAtLeast(1.0) * 0.20)
        return bandStdDev <= allowedStdDev
    }


    private fun preservesBayerCellParity(existing: Rect, candidate: Rect): Boolean {
        val removedLeft = candidate.left - existing.left
        val removedTop = candidate.top - existing.top
        val removedRight = existing.right - candidate.right
        val removedBottom = existing.bottom - candidate.bottom
        return removedLeft >= 0 && removedTop >= 0 && removedRight >= 0 && removedBottom >= 0 &&
            (removedLeft and 1) == 0 && (removedTop and 1) == 0 &&
            (removedRight and 1) == 0 && (removedBottom and 1) == 0
    }

    private fun sanitize(rect: Rect, bounds: Rect): Rect? {
        val copy = Rect(rect)
        if (!copy.intersect(bounds)) return null
        return copy.takeIf { it.width() >= 2 && it.height() >= 2 }
    }

    private fun intersect(a: Rect, b: Rect): Rect? {
        val out = Rect(a)
        return if (out.intersect(b) && out.width() >= 2 && out.height() >= 2) out else null
    }

    private fun isUsable(rect: Rect, bounds: Rect): Boolean =
        rect.left >= bounds.left && rect.top >= bounds.top &&
            rect.right <= bounds.right && rect.bottom <= bounds.bottom &&
            rect.width() >= 2 && rect.height() >= 2

    private fun rectString(rect: Rect): String =
        "${rect.left},${rect.top},${rect.width()}x${rect.height()}"

    private fun fmt2(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
    private fun fmt3(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)
}
