package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Compact motion metering for RAW acquisition control.
 *
 * The estimator consumes only the small preview-analysis luma plane (never the full RAW/RGBA
 * frame). A pyramidal/CNN optical-flow stack would be excessive for a repeating control loop;
 * this implementation uses Lucas-Kanade normal equations globally and in a small block grid.
 * Direction is discarded after solving because acquisition only needs predicted blur magnitude.
 *
 * Exposure authority deliberately requires stronger evidence than flow estimation itself. In low
 * light, sensor noise can still produce a mathematically solvable but weak optical-flow result. Such
 * weak flow is useful diagnostically, but it must not force the shutter down to 1/100 s and make the
 * sensor pay the brightness difference with high gain.
 */
data class RawMotionMeasurement(
    val ready: Boolean,
    val cameraMotionPxPerSecond: Float,
    val sceneMotionPxPerSecond: Float,
    val cameraConfidence: Float,
    val sceneConfidence: Float,
    val cameraExposureCeilingNs: Long?,
    val sceneExposureCeilingNs: Long?,
    val sampleIntervalNs: Long,
    val reason: String
) {
    fun summary(): String =
        "ready=$ready;cameraPxPerSec=$cameraMotionPxPerSecond;scenePxPerSec=$sceneMotionPxPerSecond;" +
            "cameraConfidence=$cameraConfidence;sceneConfidence=$sceneConfidence;" +
            "cameraCeilingNs=${cameraExposureCeilingNs ?: "unavailable"};" +
            "sceneCeilingNs=${sceneExposureCeilingNs ?: "unavailable"};dtNs=$sampleIntervalNs;reason=$reason"
}

class RawPreviewMotionMeter(
    private val maxAnalysisDimension: Int = 160,
    /** Legacy/default subject blur budget retained for source compatibility. */
    private val allowedBlurPixels: Float = 2.0f,
    // Keep the original positional constructor order intact. New policy knobs are appended below.
    private val blockColumns: Int = 4,
    private val blockRows: Int = 3,
    /**
     * Camera shake gets a computational-photography blur budget. By default it is twice the subject
     * budget (4 full-resolution px): in low light, trading a small amount of global camera-motion
     * blur for 1-2 EV more photons is preferable to extreme ISO gain.
     */
    private val cameraBlurBudgetPixels: Float = allowedBlurPixels * 2.0f,
    /** Real subject motion remains stricter because it cannot be removed by OIS or global alignment. */
    private val sceneBlurBudgetPixels: Float = allowedBlurPixels,
    /** Flow may be estimated below this, but only stronger evidence may constrain exposure. */
    private val exposureAuthorityMinConfidence: Float = 0.18f
) {
    companion object {
        private const val FLOW_MIN_CONFIDENCE = 0.08f
    }

    private data class Sample(
        val luma: FloatArray,
        val width: Int,
        val height: Int,
        val fullWidth: Int,
        val fullHeight: Int,
        val timestampNs: Long
    )

    private data class Flow(val u: Float, val v: Float, val confidence: Float) {
        val magnitude: Float get() = sqrt(u * u + v * v)
    }

    private var previous: Sample? = null
    private var filteredCameraSpeed = 0f
    private var filteredSceneSpeed = 0f

    @Synchronized
    fun reset() {
        previous = null
        filteredCameraSpeed = 0f
        filteredSceneSpeed = 0f
    }

    /**
     * Observe one compact NV21 frame. Only the Y plane is read.
     * First frame intentionally returns not-ready because temporal motion has no baseline yet.
     */
    @Synchronized
    fun observeNv21(
        nv21: ByteArray,
        width: Int,
        height: Int,
        fullWidth: Int,
        fullHeight: Int,
        timestampNs: Long
    ): RawMotionMeasurement {
        val yBytes = width.toLong() * height.toLong()
        if (yBytes <= 0L || yBytes > nv21.size.toLong()) {
            return unavailable("invalid_nv21_luma_plane")
        }
        return observeLuma8(nv21, width, height, fullWidth, fullHeight, timestampNs)
    }

    /**
     * Observe one compact 8-bit luma plane. This is also the production entry point for the
     * warm-RAW CFA sampler, so motion truth is available even when the UI viewfinder remains YUV.
     * The array may contain trailing bytes; only width*height luma bytes are consumed.
     */
    @Synchronized
    fun observeLuma8(
        luma: ByteArray,
        width: Int,
        height: Int,
        fullWidth: Int,
        fullHeight: Int,
        timestampNs: Long
    ): RawMotionMeasurement {
        if (width < 16 || height < 12 || fullWidth <= 0 || fullHeight <= 0 || timestampNs <= 0L) {
            return unavailable("invalid_frame_geometry_or_timestamp")
        }
        val yBytes = width.toLong() * height.toLong()
        if (yBytes <= 0L || yBytes > luma.size.toLong()) {
            return unavailable("invalid_luma_plane")
        }

        val current = makeSample(luma, width, height, fullWidth, fullHeight, timestampNs)
        val prior = previous
        previous = current
        if (prior == null || prior.width != current.width || prior.height != current.height ||
            prior.fullWidth != current.fullWidth || prior.fullHeight != current.fullHeight
        ) {
            filteredCameraSpeed = 0f
            filteredSceneSpeed = 0f
            return unavailable("temporal_baseline_warming")
        }

        val dtNs = current.timestampNs - prior.timestampNs
        if (dtNs !in 5_000_000L..500_000_000L) {
            filteredCameraSpeed = 0f
            filteredSceneSpeed = 0f
            return unavailable("invalid_temporal_interval", dtNs)
        }

        val global = solveFlow(
            prior.luma, current.luma, current.width, current.height,
            1, 1, current.width - 1, current.height - 1
        )
        val blockFlows = ArrayList<Flow>(blockColumns * blockRows)
        val blockWidth = max(6, current.width / blockColumns)
        val blockHeight = max(6, current.height / blockRows)
        for (by in 0 until blockRows) {
            for (bx in 0 until blockColumns) {
                val left = max(1, bx * current.width / blockColumns)
                val top = max(1, by * current.height / blockRows)
                val right = min(current.width - 1, max(left + 2, (bx + 1) * current.width / blockColumns))
                val bottom = min(current.height - 1, max(top + 2, (by + 1) * current.height / blockRows))
                if (right - left >= blockWidth / 2 && bottom - top >= blockHeight / 2) {
                    val flow = solveFlow(
                        prior.luma, current.luma, current.width, current.height,
                        left, top, right, bottom
                    )
                    if (flow.confidence >= FLOW_MIN_CONFIDENCE) blockFlows += flow
                }
            }
        }

        if (global.confidence < FLOW_MIN_CONFIDENCE && blockFlows.size < 3) {
            return RawMotionMeasurement(
                ready = false,
                cameraMotionPxPerSecond = 0f,
                sceneMotionPxPerSecond = 0f,
                cameraConfidence = global.confidence,
                sceneConfidence = 0f,
                cameraExposureCeilingNs = null,
                sceneExposureCeilingNs = null,
                sampleIntervalNs = dtNs,
                reason = "insufficient_texture_for_motion_truth"
            )
        }

        val dtSeconds = dtNs.toDouble() / 1_000_000_000.0
        val scaleX = current.fullWidth.toFloat() / current.width.toFloat()
        val scaleY = current.fullHeight.toFloat() / current.height.toFloat()
        val cameraDxFull = global.u * scaleX
        val cameraDyFull = global.v * scaleY
        val cameraSpeed = if (global.confidence >= FLOW_MIN_CONFIDENCE) {
            (sqrt(cameraDxFull * cameraDxFull + cameraDyFull * cameraDyFull) / dtSeconds).toFloat()
        } else {
            0f
        }

        val residualSpeeds = blockFlows.map { block ->
            val du = (block.u - global.u) * scaleX
            val dv = (block.v - global.v) * scaleY
            (sqrt(du * du + dv * dv) / dtSeconds).toFloat()
        }.filter { it.isFinite() && it >= 0f }.sorted()
        val sceneSpeed = if (residualSpeeds.isNotEmpty()) {
            // Upper percentile reacts to a moving subject without letting one unstable block own the result.
            val index = ((residualSpeeds.size - 1) * 0.80f).toInt().coerceIn(0, residualSpeeds.lastIndex)
            residualSpeeds[index]
        } else {
            0f
        }
        val sceneConfidence = if (blockFlows.isEmpty()) {
            0f
        } else {
            (blockFlows.map { it.confidence }.average().toFloat() *
                (blockFlows.size.toFloat() / (blockColumns * blockRows).toFloat())).coerceIn(0f, 1f)
        }

        filteredCameraSpeed = asymmetricMotionFilter(filteredCameraSpeed, cameraSpeed)
        filteredSceneSpeed = asymmetricMotionFilter(filteredSceneSpeed, sceneSpeed)

        val cameraCeiling = exposureCeilingNs(
            filteredCameraSpeed,
            global.confidence,
            cameraBlurBudgetPixels
        )
        val sceneCeiling = exposureCeilingNs(
            filteredSceneSpeed,
            sceneConfidence,
            sceneBlurBudgetPixels
        )
        val ready = cameraCeiling != null || sceneCeiling != null
        val reason = when {
            ready -> "compact_luma_motion_meter_photon_first"
            global.confidence >= FLOW_MIN_CONFIDENCE || sceneConfidence >= FLOW_MIN_CONFIDENCE ->
                "flow_detected_below_exposure_authority_confidence"
            else -> "motion_confidence_below_threshold"
        }
        return RawMotionMeasurement(
            ready = ready,
            cameraMotionPxPerSecond = filteredCameraSpeed,
            sceneMotionPxPerSecond = filteredSceneSpeed,
            cameraConfidence = global.confidence,
            sceneConfidence = sceneConfidence,
            cameraExposureCeilingNs = cameraCeiling,
            sceneExposureCeilingNs = sceneCeiling,
            sampleIntervalNs = dtNs,
            reason = reason
        )
    }

    private fun makeSample(
        lumaBytes: ByteArray,
        width: Int,
        height: Int,
        fullWidth: Int,
        fullHeight: Int,
        timestampNs: Long
    ): Sample {
        val scale = max(1, max(width, height) / maxAnalysisDimension)
        val outWidth = max(16, width / scale)
        val outHeight = max(12, height / scale)
        val luma = FloatArray(outWidth * outHeight)
        var sum = 0.0
        var sumSq = 0.0
        for (y in 0 until outHeight) {
            val sourceY = min(height - 1, y * scale)
            val sourceRow = sourceY * width
            val outputRow = y * outWidth
            for (x in 0 until outWidth) {
                val sourceX = min(width - 1, x * scale)
                val value = (lumaBytes[sourceRow + sourceX].toInt() and 0xFF) / 255f
                luma[outputRow + x] = value
                sum += value
                sumSq += value * value
            }
        }
        // Global AE/WB/tone changes between analysis frames are not motion. Normalize mean and
        // contrast before temporal derivatives so a brightness step cannot masquerade as optical
        // flow. Low-contrast scenes still fail closed through the structure-confidence gate.
        val count = luma.size.coerceAtLeast(1)
        val mean = (sum / count).toFloat()
        val variance = (sumSq / count - mean.toDouble() * mean.toDouble()).coerceAtLeast(0.0)
        val sigma = sqrt(variance).toFloat().coerceAtLeast(0.025f)
        for (i in luma.indices) {
            luma[i] = ((luma[i] - mean) / sigma).coerceIn(-4f, 4f)
        }
        return Sample(luma, outWidth, outHeight, fullWidth, fullHeight, timestampNs)
    }

    /** Lucas-Kanade translation solve with compact structure confidence. */
    private fun solveFlow(
        previous: FloatArray,
        current: FloatArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Flow {
        var a11 = 0.0
        var a12 = 0.0
        var a22 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var gradientSamples = 0
        var totalSamples = 0

        val x0 = left.coerceIn(1, width - 2)
        val y0 = top.coerceIn(1, height - 2)
        val x1 = right.coerceIn(x0 + 1, width - 1)
        val y1 = bottom.coerceIn(y0 + 1, height - 1)
        for (y in y0 until y1 step 2) {
            for (x in x0 until x1 step 2) {
                val i = y * width + x
                val ix = ((current[i + 1] - current[i - 1]) +
                    (previous[i + 1] - previous[i - 1])) * 0.25f
                val iy = ((current[i + width] - current[i - width]) +
                    (previous[i + width] - previous[i - width])) * 0.25f
                val it = current[i] - previous[i]
                val gradientEnergy = ix * ix + iy * iy
                totalSamples++
                if (gradientEnergy < 0.000025f || abs(it) > 0.65f) continue
                gradientSamples++
                val weight = min(1f, sqrt(gradientEnergy) * 10f).toDouble()
                a11 += weight * ix * ix
                a12 += weight * ix * iy
                a22 += weight * iy * iy
                b1 -= weight * ix * it
                b2 -= weight * iy * it
            }
        }
        if (gradientSamples < 12 || totalSamples <= 0) return Flow(0f, 0f, 0f)

        val det = a11 * a22 - a12 * a12
        val trace = a11 + a22
        if (!det.isFinite() || det <= 1e-10 || trace <= 1e-8) return Flow(0f, 0f, 0f)
        val u = ((a22 * b1 - a12 * b2) / det).toFloat()
        val v = ((a11 * b2 - a12 * b1) / det).toFloat()
        if (!u.isFinite() || !v.isFinite() || abs(u) > 12f || abs(v) > 12f) return Flow(0f, 0f, 0f)

        val isotropy = (4.0 * det / (trace * trace + 1e-12)).toFloat().coerceIn(0f, 1f)
        val texturedFraction = gradientSamples.toFloat() / totalSamples.toFloat()
        val confidence = (sqrt(isotropy) * min(1f, texturedFraction * 4f)).coerceIn(0f, 1f)
        return Flow(u, v, confidence)
    }

    /** Fast attack, slow release: sudden motion shortens shutter immediately. */
    private fun asymmetricMotionFilter(previous: Float, current: Float): Float {
        if (!current.isFinite() || current < 0f) return previous.coerceAtLeast(0f)
        if (current >= previous) return current
        return (0.80f * previous + 0.20f * current).coerceAtLeast(0f)
    }

    private fun exposureCeilingNs(
        speedPxPerSecond: Float,
        confidence: Float,
        blurBudgetPixels: Float
    ): Long? {
        if (!speedPxPerSecond.isFinite() || speedPxPerSecond < 0f ||
            !confidence.isFinite() || confidence < exposureAuthorityMinConfidence
        ) {
            return null
        }
        if (speedPxPerSecond < 0.25f) return 1_000_000_000L
        val seconds = blurBudgetPixels.coerceIn(0.5f, 8f) / speedPxPerSecond
        return (seconds * 1_000_000_000.0).toLong().coerceIn(1_000_000L, 1_000_000_000L)
    }

    private fun unavailable(reason: String, dtNs: Long = 0L) = RawMotionMeasurement(
        ready = false,
        cameraMotionPxPerSecond = 0f,
        sceneMotionPxPerSecond = 0f,
        cameraConfidence = 0f,
        sceneConfidence = 0f,
        cameraExposureCeilingNs = null,
        sceneExposureCeilingNs = null,
        sampleIntervalNs = dtNs,
        reason = reason
    )
}
