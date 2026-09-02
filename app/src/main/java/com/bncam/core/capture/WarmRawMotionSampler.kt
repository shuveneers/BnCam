package com.bncam.core.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Bounded CFA sampler for Phase-1 motion metering.
 *
 * This is deliberately not a renderer and not a full-frame copy. It reads at most roughly
 * 160x120 Bayer-cell locations from the exact warm-buffer Image plane and publishes one compact
 * luma-like 8-bit grid. Averaging a 2x2 Bayer cell makes the motion guide independent of CFA phase;
 * the downstream motion estimator normalizes global mean/contrast, so AWB/colour processing is
 * neither required nor allowed here.
 */
data class WarmRawMotionLuma(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val fullWidth: Int,
    val fullHeight: Int,
    val timestampNs: Long
)

object WarmRawMotionSampler {
    private const val MIN_WIDTH = 16
    private const val MIN_HEIGHT = 12
    private const val DEFAULT_MAX_DIMENSION = 160

    fun sampleRaw10(
        buffer: ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int,
        timestampNs: Long,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): WarmRawMotionLuma? = sample(
        width = width,
        height = height,
        timestampNs = timestampNs,
        maxDimension = maxDimension,
        read = { x, y -> readRaw10Msb(buffer, rowStride, x, y) }
    )

    fun sampleRawSensor(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        timestampNs: Long,
        maxDimension: Int = DEFAULT_MAX_DIMENSION
    ): WarmRawMotionLuma? {
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        val stride = pixelStride.takeIf { it >= 2 } ?: 2
        return sample(
            width = width,
            height = height,
            timestampNs = timestampNs,
            maxDimension = maxDimension,
            read = { x, y ->
                val offset = y.toLong() * rowStride.toLong() + x.toLong() * stride.toLong()
                if (offset < 0L || offset + 1L >= view.limit().toLong()) 0
                else view.getShort(offset.toInt()).toInt() and 0xFFFF
            }
        )
    }

    private inline fun sample(
        width: Int,
        height: Int,
        timestampNs: Long,
        maxDimension: Int,
        read: (x: Int, y: Int) -> Int
    ): WarmRawMotionLuma? {
        if (width < 8 || height < 8 || timestampNs <= 0L) return null
        val safeMax = maxDimension.coerceIn(64, 256)
        val sourceCellsWide = width / 2
        val sourceCellsHigh = height / 2
        if (sourceCellsWide < MIN_WIDTH || sourceCellsHigh < MIN_HEIGHT) return null

        val scale = max(1, max(sourceCellsWide, sourceCellsHigh) / safeMax)
        val outWidth = max(MIN_WIDTH, sourceCellsWide / scale)
        val outHeight = max(MIN_HEIGHT, sourceCellsHigh / scale)
        val values = IntArray(outWidth * outHeight)
        var peak = 0

        for (oy in 0 until outHeight) {
            val cellY = min(sourceCellsHigh - 1, oy * sourceCellsHigh / outHeight)
            val y = (cellY * 2).coerceIn(0, height - 2)
            val row = oy * outWidth
            for (ox in 0 until outWidth) {
                val cellX = min(sourceCellsWide - 1, ox * sourceCellsWide / outWidth)
                val x = (cellX * 2).coerceIn(0, width - 2)
                val sum = read(x, y) + read(x + 1, y) + read(x, y + 1) + read(x + 1, y + 1)
                val value = (sum + 2) / 4
                values[row + ox] = value
                if (value > peak) peak = value
            }
        }
        if (peak <= 0) return null

        // Absolute code scale differs across RAW10/RAW_SENSOR sensors and white levels. Motion uses
        // normalized temporal structure, so map the observed sampled peak to 8-bit and let the
        // meter remove global mean/contrast. No tone curve or per-channel colour gain is involved.
        val bytes = ByteArray(values.size)
        for (i in values.indices) {
            bytes[i] = ((values[i].toLong() * 255L + peak / 2L) / peak.toLong())
                .toInt().coerceIn(0, 255).toByte()
        }
        return WarmRawMotionLuma(bytes, outWidth, outHeight, width, height, timestampNs)
    }

    private fun readRaw10Msb(buffer: ByteBuffer, rowStride: Int, x: Int, y: Int): Int {
        if (x < 0 || y < 0 || rowStride <= 0) return 0
        // Android RAW10 packs four 8 MSB bytes plus one shared low-bit byte. For motion the 8 MSBs
        // are sufficient and avoid any vendor-specific low-bit ordering ambiguity.
        val base = y.toLong() * rowStride.toLong() + (x / 4).toLong() * 5L + (x and 3).toLong()
        if (base < 0L || base >= buffer.limit().toLong()) return 0
        return buffer.get(base.toInt()).toInt() and 0xFF
    }
}
