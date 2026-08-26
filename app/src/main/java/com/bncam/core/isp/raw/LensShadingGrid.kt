package com.bncam.core.isp.raw

import kotlin.math.floor

object LensShadingGrid {
    fun sanitize(gains: FloatArray, columns: Int, rows: Int): FloatArray? {
        if (columns <= 0 || rows <= 0 || gains.size < columns * rows * 4) return null
        return FloatArray(columns * rows * 4) { index ->
            gains[index].takeIf { it.isFinite() }?.coerceIn(0.25f, 3.5f) ?: 1f
        }
    }

    fun gainAt(
        gains: FloatArray,
        columns: Int,
        rows: Int,
        channel: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ): Float {
        val safe = sanitize(gains, columns, rows) ?: return 1f
        val colF = if (width > 1) x.coerceIn(0, width - 1) * (columns - 1f) / (width - 1f) else 0f
        val rowF = if (height > 1) y.coerceIn(0, height - 1) * (rows - 1f) / (height - 1f) else 0f
        val c0 = floor(colF).toInt().coerceIn(0, columns - 1)
        val r0 = floor(rowF).toInt().coerceIn(0, rows - 1)
        val c1 = (c0 + 1).coerceAtMost(columns - 1)
        val r1 = (r0 + 1).coerceAtMost(rows - 1)
        val tx = (colF - c0).coerceIn(0f, 1f)
        val ty = (rowF - r0).coerceIn(0f, 1f)
        val plane = channel.coerceIn(0, 3)
        fun gain(column: Int, row: Int) = safe[(row * columns + column) * 4 + plane]
        val top = gain(c0, r0) + (gain(c1, r0) - gain(c0, r0)) * tx
        val bottom = gain(c0, r1) + (gain(c1, r1) - gain(c0, r1)) * tx
        return (top + (bottom - top) * ty).coerceIn(0.25f, 3.5f)
    }
}
