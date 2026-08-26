package com.bncam.core.alignment

import android.graphics.ImageFormat
import kotlin.math.max

object   AlignmentGuideBuilder {

    /**
     * Builds a 3-level guide pyramid (Level 0: 1/4 size, Level 1: 1/8 size, Level 2: 1/16 size).
     * For RAW10/RAW_SENSOR: CFA-aware luma extraction (averages Bayer green channels Gr/Gb)
     * respecting black & white levels without phase-corrupting RGB interpolation.
     * For YUV: Direct downsampled Y-plane extraction.
     */
    fun buildGuidePyramid(
        payload: ByteArray,
        format: Int,
        width: Int,
        height: Int,
        blackLevel: Float = 64.0f,
        whiteLevel: Float = 1023.0f
    ): Pair<List<ByteArray>, Pair<Int, Int>> {
        val guideW = max(64, width / 4)
        val guideH = max(48, height / 4)
        val level0 = ByteArray(guideW * guideH)

        val stepX = max(1, width / guideW)
        val stepY = max(1, height / guideH)

        if (format == ImageFormat.YUV_420_888 || payload.size <= guideW * guideH * 2) {
            // YUV Y-plane extraction
            for (gy in 0 until guideH) {
                val sy = (gy * stepY).coerceIn(0, height - 1)
                for (gx in 0 until guideW) {
                    val sx = (gx * stepX).coerceIn(0, width - 1)
                    val idx = sy * width + sx
                    level0[gy * guideW + gx] = payload.getOrElse(idx) { 0 }
                }
            }
        } else {
            // CFA-safe RAW Bayer luma extraction (sensor-linear green channel extraction)
            val range = max(1.0f, whiteLevel - blackLevel)
            for (gy in 0 until guideH) {
                val sy = (gy * stepY).coerceIn(0, height - 2) and 0x7FFFFFFE
                for (gx in 0 until guideW) {
                    val sx = (gx * stepX).coerceIn(0, width - 2) and 0x7FFFFFFE
                    val idx0 = sy * width + (sx + 1) // Green 1 (Gr)
                    val idx1 = (sy + 1) * width + sx // Green 2 (Gb)

                    val val0 = (payload.getOrElse(idx0 * 2) { 0 }.toInt() and 0xFF) or
                            ((payload.getOrElse(idx0 * 2 + 1) { 0 }.toInt() and 0xFF) shl 8)
                    val val1 = (payload.getOrElse(idx1 * 2) { 0 }.toInt() and 0xFF) or
                            ((payload.getOrElse(idx1 * 2 + 1) { 0 }.toInt() and 0xFF) shl 8)

                    val rawAvg = (val0 + val1) / 2.0f
                    val norm = ((rawAvg - blackLevel) / range).coerceIn(0.0f, 1.0f)
                    level0[gy * guideW + gx] = (norm * 255.0f).toInt().toByte()
                }
            }
        }

        // Level 1: 1/2 of Level 0
        val l1W = max(32, guideW / 2)
        val l1H = max(24, guideH / 2)
        val level1 = ByteArray(l1W * l1H)
        for (y in 0 until l1H) {
            for (x in 0 until l1W) {
                val srcY = y * 2
                val srcX = x * 2
                val p0 = level0[srcY * guideW + srcX].toInt() and 0xFF
                val p1 = level0[srcY * guideW + (srcX + 1)].toInt() and 0xFF
                val p2 = level0[(srcY + 1) * guideW + srcX].toInt() and 0xFF
                val p3 = level0[(srcY + 1) * guideW + (srcX + 1)].toInt() and 0xFF
                level1[y * l1W + x] = ((p0 + p1 + p2 + p3) / 4).toByte()
            }
        }

        // Level 2: 1/2 of Level 1
        val l2W = max(16, l1W / 2)
        val l2H = max(12, l1H / 2)
        val level2 = ByteArray(l2W * l2H)
        for (y in 0 until l2H) {
            for (x in 0 until l2W) {
                val srcY = y * 2
                val srcX = x * 2
                val p0 = level1[srcY * l1W + srcX].toInt() and 0xFF
                val p1 = level1[srcY * l1W + (srcX + 1)].toInt() and 0xFF
                val p2 = level1[(srcY + 1) * l1W + srcX].toInt() and 0xFF
                val p3 = level1[(srcY + 1) * l1W + (srcX + 1)].toInt() and 0xFF
                level2[y * l2W + x] = ((p0 + p1 + p2 + p3) / 4).toByte()
            }
        }

        return listOf(level0, level1, level2) to (guideW to guideH)
    }
}
