package com.bncam.core.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded 64x48 CFA-cell sample, before demosaic, AWB and all rendering. */
object BnAutoRawMeter {
    fun sample(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        packedRaw10: Boolean, blackLevels: DoubleArray, whiteLevel: Double,
        timestampNs: Long, exposureNs: Long, iso: Int,
        noiseSlope: Double? = null, noiseOffset: Double? = null,
        motion: RawMotionMeasurement? = null
    ): BnAutoObservation? {
        if (width < 8 || height < 8 || rowStride <= 0 || blackLevels.size != 4 ||
            !whiteLevel.isFinite() || blackLevels.any { !it.isFinite() || it < 0 || it >= whiteLevel }) return null
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val hist = IntArray(4096)
        val highlightHist = IntArray(4096)
        val clipped = IntArray(4)
        val regionCount = IntArray(12)
        val regionBright = IntArray(12)
        var cells = 0
        val signals = DoubleArray(64 * 48)
        val gradientsX = DoubleArray(signals.size)
        val gradientsY = DoubleArray(signals.size)
        val channelSignals = Array(4) { DoubleArray(signals.size) }
        fun read(x: Int, y: Int): Int? {
            val offset = y.toLong() * rowStride + if (packedRaw10) (x / 4L) * 5 else x.toLong() * pixelStride.coerceAtLeast(2)
            if (offset < 0 || offset + (if (packedRaw10) 4 else 1) >= view.limit()) return null
            val at = offset.toInt()
            return if (packedRaw10) {
                ((view.get(at + (x and 3)).toInt() and 255) shl 2) or
                    ((view.get(at + 4).toInt() ushr ((x and 3) * 2)) and 3)
            } else view.getShort(at).toInt() and 65535
        }
        fun cellMean(x: Int, y: Int): Double {
            var mean = 0.0
            for (i in 0..3) {
                val code = read(x + i % 2, y + i / 2) ?: return Double.NaN
                mean += ((code - blackLevels[i]) / (whiteLevel - blackLevels[i])).coerceIn(0.0, 1.0) / 4
            }
            return mean
        }
        for (gy in 0 until 48) for (gx in 0 until 64) {
            val x = 2 + (gx * ((width - 4) / 2) / 64) * 2
            val y = 2 + (gy * ((height - 4) / 2) / 48) * 2
            val codes = intArrayOf(read(x, y) ?: return null, read(x + 1, y) ?: return null,
                read(x, y + 1) ?: return null, read(x + 1, y + 1) ?: return null)
            var mean = 0.0
            var maximum = 0.0
            for (i in 0..3) {
                val signal = ((codes[i] - blackLevels[i]) / (whiteLevel - blackLevels[i])).coerceIn(0.0, 1.0)
                channelSignals[i][cells] = signal
                if (signal >= 0.98) clipped[i]++
                mean += signal / 4
                maximum = maxOf(maximum, signal)
            }
            hist[(mean * 4095).toInt()]++
            highlightHist[(maximum * 4095).toInt()]++
            signals[cells] = mean
            // Symmetric neighbors exclude the center sample: its noise must not correlate with
            // both the temporal difference and the gradient (which would mimic camera drift).
            gradientsX[cells] = (cellMean(x + 2, y) - cellMean(x - 2, y)) / 4
            gradientsY[cells] = (cellMean(x, y + 2) - cellMean(x, y - 2)) / 4
            val region = (gy / 16) * 4 + gx / 16
            regionCount[region]++
            if (maximum >= 0.80) regionBright[region]++
            cells++
        }
        fun percentile(fraction: Double, histogram: IntArray = hist): Double {
            val target = ((cells - 1) * fraction).toInt()
            var count = 0
            for (i in histogram.indices) {
                count += histogram[i]
                if (count > target) return i / 4095.0
            }
            return 1.0
        }
        // Isolated point sources occupy few cells/regions; a broad bright wall/window does not.
        val broadBright = regionBright.indices.count { regionBright[it] >= regionCount[it] / 8.0 } / 12.0
        // Brightness alone does not identify a lamp. Require a compact connected component,
        // strong contrast against the body of the scene and a small total spatial footprint.
        val noiseFloor = kotlin.math.sqrt(((noiseSlope ?: 0.0) * percentile(0.50) +
            (noiseOffset ?: 0.0)).coerceAtLeast(0.0))
        val brightThreshold = maxOf(percentile(0.50) * 8, percentile(0.80) * 4, noiseFloor * 6, 1.0 / 4095)
        val bright = BooleanArray(cells) { i -> channelSignals.maxOf { it[i] } > brightThreshold }
        val visited = BooleanArray(cells)
        val points = BooleanArray(cells)
        for (start in 0 until cells) {
            if (!bright[start] || visited[start]) continue
            val component = ArrayList<Int>()
            val queue = java.util.ArrayDeque<Int>()
            queue.add(start); visited[start] = true
            var minX = 63; var maxX = 0; var minY = 47; var maxY = 0
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst(); component.add(i)
                val x = i % 64; val y = i / 64
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0..63 || ny !in 0..47) continue
                    val next = ny * 64 + nx
                    if (bright[next] && !visited[next]) { visited[next] = true; queue.add(next) }
                }
            }
            if (component.size <= cells * 0.03 &&
                (maxX - minX + 1) * (maxY - minY + 1) <= cells * 0.06) {
                component.forEach { points[it] = true }
            }
        }
        if (points.count { it } > cells * 0.05) points.fill(false)
        fun quantile(values: List<Double>, fraction: Double): Double = values.sorted().let {
            if (it.isEmpty()) Double.NaN else it[((it.size - 1) * fraction).toInt()]
        }
        val diffuse = (0 until cells).filter { !points[it] }
        val diffuse80 = quantile(diffuse.map { signals[it] }, 0.80)
        val diffuse98 = quantile(diffuse.map { i -> channelSignals.maxOf { it[i] } }, 0.98)
        val medians = DoubleArray(12) { region -> quantile(diffuse.filter {
            (it / 64 / 16) * 4 + (it % 64 / 16) == region
        }.map { signals[it] }, 0.50) }
        return BnAutoObservation(timestampNs, exposureNs, iso, percentile(0.20), percentile(0.80),
            percentile(0.98), clipped.map { it.toDouble() / cells }, cells, broadBright,
            noiseSlope, noiseOffset, motion?.cameraExposureCeilingNs, motion?.sceneExposureCeilingNs,
            if (motion?.ready == true) minOf(motion.cameraConfidence, motion.sceneConfidence) else 0f,
            cameraMotionConfidence = motion?.cameraConfidence ?: 0f,
            subjectMotionConfidence = motion?.sceneConfidence ?: 0f,
            sensorLumaSamples = signals, sensorGradientX = gradientsX, sensorGradientY = gradientsY,
            channelP98Maximum = percentile(0.98, highlightHist),
            quantizationVariance = blackLevels.map { 1.0 / (12 * (whiteLevel - it) * (whiteLevel - it)) }.average(),
            diffuseP50 = quantile(diffuse.map { signals[it] }, 0.50), diffuseP80 = diffuse80,
            diffuseP98Maximum = diffuse98, regionMedians = medians,
            pointSourceFraction = points.count { it }.toDouble() / cells,
            shadowFraction = diffuse.count { signals[it] < diffuse80 / 8 }.toDouble() / diffuse.size.coerceAtLeast(1),
            channelSamples = channelSignals, pointSourceMask = points)
    }
}
