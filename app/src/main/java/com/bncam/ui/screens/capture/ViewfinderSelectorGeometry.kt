package com.bncam.ui.screens.capture

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class RadialSelectorPoint(val x: Float, val y: Float)

/**
 * Lens-selector arc in screen coordinates (positive Y down).
 *
 * Index 0 is exactly horizontal-left from the master button (selfie by selector ordering). Every
 * following lens progresses upward through the left quadrant and the final lens is exactly above
 * the master button. The geometry therefore never requires space to the right of the master.
 */
internal fun radialSelectorPoint(index: Int, count: Int, radius: Float): RadialSelectorPoint {
    require(count > 0)
    require(index in 0 until count)
    val degrees = when {
        count == 1 -> 270f
        index == 0 -> 180f
        else -> 180f + (90f * index.toFloat() / (count - 1).toFloat())
    }
    val radians = degrees * PI.toFloat() / 180f
    return RadialSelectorPoint(cos(radians) * radius, sin(radians) * radius)
}

internal fun nearestRadialSelectorIndex(
    dragX: Float,
    dragY: Float,
    count: Int,
    minimumDistance: Float
): Int? {
    if (count <= 0 || hypot(dragX, dragY) < minimumDistance) return null
    val dragAngle = normalizedDegrees(atan2(dragY, dragX) * 180f / PI.toFloat())
    var bestIndex = 0
    var bestDistance = Float.MAX_VALUE
    for (index in 0 until count) {
        val p = radialSelectorPoint(index, count, 1f)
        val candidateAngle = normalizedDegrees(atan2(p.y, p.x) * 180f / PI.toFloat())
        val raw = kotlin.math.abs(dragAngle - candidateAngle)
        val distance = minOf(raw, 360f - raw)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}

internal fun formatLensZoomRatio(ratio: Float): String {
    if (!ratio.isFinite() || ratio <= 0f) return "Lens"
    val oneDecimal = (ratio * 10f).roundToInt() / 10f
    return String.format(java.util.Locale.US, "%.1f×", oneDecimal)
}

private fun normalizedDegrees(value: Float): Float {
    val normalized = value % 360f
    return if (normalized < 0f) normalized + 360f else normalized
}

/**
 * Geometry for the production lens selector.
 *
 * Arc 1 is always the quarter circle around the master button. With three or more rear cameras,
 * rear cameras occupy arc 1 and the selfie camera moves to arc 2 directly above the upper rear
 * stop. With only one or two rear cameras, selfie joins arc 1 instead, so the available buttons
 * fill the quarter circle without leaving a sparse inner arc.
 */
internal fun lensSelectorPoint(
    index: Int,
    rearCount: Int,
    hasSelfie: Boolean,
    rearRadius: Float,
    selfieGap: Float
): RadialSelectorPoint {
    require(rearCount >= 0)
    val totalCount = rearCount + if (hasSelfie) 1 else 0
    require(totalCount > 0)
    require(index in 0 until totalCount)

    val selfieIndex = if (hasSelfie) rearCount else -1
    val selfieUsesOuterArc = hasSelfie && rearCount >= 3
    if (index == selfieIndex && selfieUsesOuterArc) {
        return RadialSelectorPoint(0f, -(rearRadius + selfieGap))
    }

    // If selfie is not on arc 2, every visible selector button shares arc 1. This gives:
    // 2 rear + selfie => left / middle / top; 1 rear + selfie => left / top; selfie-only => top.
    val arcOneCount = if (hasSelfie && !selfieUsesOuterArc) totalCount else rearCount
    return radialSelectorPoint(index = index, count = arcOneCount, radius = rearRadius)
}

internal fun nearestLensSelectorIndex(
    dragX: Float,
    dragY: Float,
    rearCount: Int,
    hasSelfie: Boolean,
    rearRadius: Float,
    selfieGap: Float,
    minimumDistance: Float
): Int? {
    val totalCount = rearCount + if (hasSelfie) 1 else 0
    if (totalCount <= 0 || hypot(dragX, dragY) < minimumDistance) return null

    var bestIndex = 0
    var bestDistance = Float.MAX_VALUE
    for (index in 0 until totalCount) {
        val point = lensSelectorPoint(
            index = index,
            rearCount = rearCount,
            hasSelfie = hasSelfie,
            rearRadius = rearRadius,
            selfieGap = selfieGap
        )
        val distance = hypot(dragX - point.x, dragY - point.y)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return bestIndex
}
