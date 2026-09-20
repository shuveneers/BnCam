package com.bncam.ui.screens.capture

import com.bncam.core.runtime.RawPreviewProducerFrameIdentity
import com.bncam.core.runtime.RawPreviewProducerKind
import kotlin.math.max

enum class RawPreviewHealthStage {
    INACTIVE,
    STARTING,
    HEALTHY,
    CAMERA_CAPTURE_RESULT,
    RAW_IMAGE_READER,
    RENDERER_OFFER,
    RENDERER_PUBLICATION,
    GL_ACCEPT,
    GL_DRAW,
    EGL_PRESENTATION,
    RGB_OUTPUT
}

data class RawPreviewHealthSnapshot(
    val source: String,
    val pipelineGeneration: Int,
    val eglGeneration: Int,
    val stage: RawPreviewHealthStage,
    val expectedIntervalNs: Long,
    val stallThresholdNs: Long,
    val routeStartedElapsedNs: Long,
    val lastCaptureResultElapsedNs: Long,
    val lastImageReaderElapsedNs: Long,
    val lastRendererOfferElapsedNs: Long,
    val lastRendererPublicationElapsedNs: Long,
    val lastGlAcceptedElapsedNs: Long,
    val firstGlDrawElapsedNs: Long,
    val lastGlDrawElapsedNs: Long,
    val lastDisplayPresentElapsedNs: Long,
    val latestSensorTimestampNs: Long,
    val latestSensorFrameDurationNs: Long,
    val latestExposureTimeNs: Long,
    val advertisedMinFrameDurationNs: Long,
    val lastImageReaderFrame: RawPreviewProducerFrameIdentity?,
    val lastRendererOfferFrame: RawPreviewProducerFrameIdentity?,
    val lastRendererPublicationFrame: RawPreviewProducerFrameIdentity?,
    val lastGlAcceptedFrame: RawPreviewProducerFrameIdentity?,
    val lastGlDrawFrame: RawPreviewProducerFrameIdentity?,
    val lastDisplayPresentedFrame: RawPreviewProducerFrameIdentity?,
    val stageFrameHint: RawPreviewProducerFrameIdentity?,
    val exactDownstreamChainCoherent: Boolean,
    val outputRgbMin: Float,
    val outputRgbMax: Float,
    val outputRgbMean: Float,
    val inputRawMax: Float,
    val inputSceneP50: Float,
    val consecutiveImpossibleBlackFrames: Int,
    val impossibleBlackConfirmed: Boolean,
    val outputSlotHealth: String,
    val ringPressure: String,
    val actualPresentationObserved: Boolean,
    val lastRecoveryReason: String,
    val lastRecoveryElapsedNs: Long
) {
    fun report(nowElapsedNs: Long): String = buildString {
        fun age(last: Long): String =
            if (last <= 0L) "none" else "${((nowElapsedNs - last).coerceAtLeast(0L)) / 1_000_000.0}"
        fun frame(value: RawPreviewProducerFrameIdentity?): String = value?.summary() ?: "none"

        appendLine("RAW_PREVIEW_HEALTH source=$source generation=$pipelineGeneration eglGeneration=$eglGeneration stage=$stage")
        appendLine("expectedIntervalMs=${expectedIntervalNs / 1_000_000.0} stallThresholdMs=${stallThresholdNs / 1_000_000.0}")
        appendLine("ageCaptureResultMs=${age(lastCaptureResultElapsedNs)} ageImageReaderMs=${age(lastImageReaderElapsedNs)} ageRendererOfferMs=${age(lastRendererOfferElapsedNs)}")
        appendLine("ageRendererPublicationMs=${age(lastRendererPublicationElapsedNs)} ageGlAcceptedMs=${age(lastGlAcceptedElapsedNs)} firstGlDrawAgeMs=${age(firstGlDrawElapsedNs)} ageGlDrawMs=${age(lastGlDrawElapsedNs)} ageDisplayPresentMs=${age(lastDisplayPresentElapsedNs)}")
        appendLine("latestSensorTimestampNs=$latestSensorTimestampNs frameDurationNs=$latestSensorFrameDurationNs exposureTimeNs=$latestExposureTimeNs advertisedMinFrameDurationNs=$advertisedMinFrameDurationNs")
        appendLine("imageReaderFrame={${frame(lastImageReaderFrame)}}")
        appendLine("rendererOfferFrame={${frame(lastRendererOfferFrame)}}")
        appendLine("rendererPublicationFrame={${frame(lastRendererPublicationFrame)}}")
        appendLine("glAcceptedFrame={${frame(lastGlAcceptedFrame)}}")
        appendLine("glDrawFrame={${frame(lastGlDrawFrame)}}")
        appendLine("displayPresentedFrame={${frame(lastDisplayPresentedFrame)}}")
        appendLine("stageFrameHint={${frame(stageFrameHint)}} exactDownstreamChainCoherent=$exactDownstreamChainCoherent")
        appendLine(
            "rgbMin=$outputRgbMin rgbMax=$outputRgbMax rgbMean=$outputRgbMean " +
                "inputRawMax=$inputRawMax inputSceneP50=$inputSceneP50 " +
                "consecutiveImpossibleBlackFrames=$consecutiveImpossibleBlackFrames " +
                "impossibleBlackConfirmed=$impossibleBlackConfirmed " +
                "actualPresentationObserved=$actualPresentationObserved"
        )
        appendLine("outputSlotHealth={$outputSlotHealth}")
        appendLine("ringPressure={$ringPressure}")
        appendLine("lastRecoveryReason=$lastRecoveryReason lastRecoveryAgeMs=${age(lastRecoveryElapsedNs)}")
    }
}

/**
 * Low-overhead, always-on RAW viewfinder liveness owner.
 *
 * This intentionally does not recover anything itself. It records monotonic progress at each
 * stage and classifies the first stale stage. Every RAW frame-bearing stage also records exact
 * producer provenance so renderer/GL/EGL failures can be attributed without guessing whether the
 * frame came from PRIMARY_BUFFER or RAW_PREVIEW_SUPPORT.
 *
 * RAW_IMAGE_READER remains an aggregate transport stage by design. PRIMARY_BUFFER liveness is
 * owned by the canonical warm-buffer watchdog and optional support liveness by
 * RawViewfinderProducerHealthPolicy; this monitor must not infer a failed producer from aggregate
 * ImageReader freshness alone.
 */
object RawPreviewHealthMonitor {
    private var source: String = "YUV"
    private var pipelineGeneration: Int = -1
    private var eglGeneration: Int = -1
    private var routeStartedElapsedNs: Long = 0L
    private var advertisedMinFrameDurationNs: Long = 0L
    private var latestSensorFrameDurationNs: Long = 0L
    private var latestExposureTimeNs: Long = 0L
    private var latestSensorTimestampNs: Long = 0L

    private var lastCaptureResultElapsedNs: Long = 0L
    private var lastImageReaderElapsedNs: Long = 0L
    private var lastRendererOfferElapsedNs: Long = 0L
    private var lastRendererPublicationElapsedNs: Long = 0L
    private var lastGlAcceptedElapsedNs: Long = 0L
    private var firstGlDrawElapsedNs: Long = 0L
    private var lastGlDrawElapsedNs: Long = 0L
    private var lastDisplayPresentElapsedNs: Long = 0L
    private var actualPresentationObserved: Boolean = false

    private var lastImageReaderFrame: RawPreviewProducerFrameIdentity? = null
    private var lastRendererOfferFrame: RawPreviewProducerFrameIdentity? = null
    private var lastRendererPublicationFrame: RawPreviewProducerFrameIdentity? = null
    private var lastGlAcceptedFrame: RawPreviewProducerFrameIdentity? = null
    private var lastGlDrawFrame: RawPreviewProducerFrameIdentity? = null
    private var lastDisplayPresentedFrame: RawPreviewProducerFrameIdentity? = null

    private var outputRgbMin: Float = Float.NaN
    private var outputRgbMax: Float = Float.NaN
    private var outputRgbMean: Float = Float.NaN
    private var inputRawMax: Float = Float.NaN
    private var inputSceneP50: Float = Float.NaN
    private var consecutiveImpossibleBlackFrames: Int = 0
    private var impossibleBlackConfirmed: Boolean = false
    private var lastBlackEvidenceSensorTimestampNs: Long = Long.MIN_VALUE
    private var outputSlotHealth: String = "unavailable"
    private var ringPressure: String = "unavailable"
    private var lastRecoveryReason: String = "none"
    private var lastRecoveryElapsedNs: Long = 0L

    @Synchronized
    fun route(
        sourceName: String,
        generation: Int,
        minFrameDurationNs: Long,
        nowElapsedNs: Long
    ) {
        val changed = source != sourceName || pipelineGeneration != generation
        source = sourceName
        pipelineGeneration = generation
        if (!changed) {
            if (minFrameDurationNs > 0L) advertisedMinFrameDurationNs = minFrameDurationNs
            return
        }
        advertisedMinFrameDurationNs = minFrameDurationNs.coerceAtLeast(0L)

        routeStartedElapsedNs = nowElapsedNs
        latestSensorFrameDurationNs = 0L
        latestExposureTimeNs = 0L
        latestSensorTimestampNs = 0L
        lastCaptureResultElapsedNs = 0L
        lastImageReaderElapsedNs = 0L
        lastRendererOfferElapsedNs = 0L
        lastRendererPublicationElapsedNs = 0L
        lastGlAcceptedElapsedNs = 0L
        firstGlDrawElapsedNs = 0L
        lastGlDrawElapsedNs = 0L
        lastDisplayPresentElapsedNs = 0L
        actualPresentationObserved = false
        lastImageReaderFrame = null
        lastRendererOfferFrame = null
        lastRendererPublicationFrame = null
        lastGlAcceptedFrame = null
        lastGlDrawFrame = null
        lastDisplayPresentedFrame = null
        outputRgbMin = Float.NaN
        outputRgbMax = Float.NaN
        outputRgbMean = Float.NaN
        inputRawMax = Float.NaN
        inputSceneP50 = Float.NaN
        consecutiveImpossibleBlackFrames = 0
        impossibleBlackConfirmed = false
        lastBlackEvidenceSensorTimestampNs = Long.MIN_VALUE
        outputSlotHealth = "unavailable"
        ringPressure = "unavailable"
        lastRecoveryReason = "none"
        lastRecoveryElapsedNs = 0L
    }

    @Synchronized
    fun captureResultProgress(
        generation: Int,
        sensorTimestampNs: Long,
        sensorFrameDurationNs: Long,
        exposureTimeNs: Long,
        nowElapsedNs: Long
    ) {
        if (!matches(generation)) return
        lastCaptureResultElapsedNs = nowElapsedNs
        if (sensorTimestampNs > 0L) latestSensorTimestampNs = max(latestSensorTimestampNs, sensorTimestampNs)
        if (sensorFrameDurationNs > 0L) latestSensorFrameDurationNs = sensorFrameDurationNs
        if (exposureTimeNs > 0L) latestExposureTimeNs = exposureTimeNs
    }

    @Synchronized
    fun imageReaderProgress(
        generation: Int,
        sensorTimestampNs: Long,
        producerKind: RawPreviewProducerKind,
        nowElapsedNs: Long
    ) {
        if (!matches(generation)) return
        lastImageReaderElapsedNs = nowElapsedNs
        frameIdentity(generation, sensorTimestampNs, producerKind)?.let { lastImageReaderFrame = it }
        if (sensorTimestampNs > 0L) latestSensorTimestampNs = max(latestSensorTimestampNs, sensorTimestampNs)
    }

    @Synchronized
    fun rendererOffer(
        generation: Int,
        sensorTimestampNs: Long,
        producerKind: RawPreviewProducerKind,
        nowElapsedNs: Long
    ) {
        if (!matches(generation)) return
        lastRendererOfferElapsedNs = nowElapsedNs
        frameIdentity(generation, sensorTimestampNs, producerKind)?.let { lastRendererOfferFrame = it }
    }

    @Synchronized
    fun rendererPublication(
        generation: Int,
        sensorTimestampNs: Long,
        producerKind: RawPreviewProducerKind,
        frameEglGeneration: Int,
        rgbMin: Float,
        rgbMax: Float,
        rgbMean: Float,
        slotHealth: String,
        nowElapsedNs: Long,
        normalizedRawMax: Float = Float.NaN,
        sceneP50: Float = Float.NaN
    ) {
        if (!matches(generation)) return
        lastRendererPublicationElapsedNs = nowElapsedNs
        frameIdentity(generation, sensorTimestampNs, producerKind)?.let { lastRendererPublicationFrame = it }
        if (frameEglGeneration > 0) eglGeneration = frameEglGeneration
        outputRgbMin = rgbMin
        outputRgbMax = rgbMax
        outputRgbMean = rgbMean
        inputRawMax = normalizedRawMax
        inputSceneP50 = sceneP50

        val outputExactlyBlack =
            rgbMax.isFinite() && rgbMean.isFinite() &&
                rgbMax <= BROKEN_RGB_MAX_EPSILON &&
                rgbMean <= BROKEN_RGB_MEAN_EPSILON
        val rawSignalProven =
            normalizedRawMax.isFinite() && sceneP50.isFinite() &&
                normalizedRawMax >= MIN_RAW_SIGNAL_FOR_BLACK_FAULT &&
                sceneP50 >= MIN_SCENE_P50_FOR_BLACK_FAULT
        if (outputExactlyBlack && rawSignalProven && sensorTimestampNs > 0L) {
            // Canonical + support may render the same sensor frame. Count that physical frame once
            // so two Camera2 outputs cannot satisfy the multi-frame black-fault quorum early.
            if (sensorTimestampNs != lastBlackEvidenceSensorTimestampNs) {
                lastBlackEvidenceSensorTimestampNs = sensorTimestampNs
                consecutiveImpossibleBlackFrames =
                    (consecutiveImpossibleBlackFrames + 1)
                        .coerceAtMost(BLACK_FAULT_CONFIRMATION_FRAMES)
            }
        } else {
            consecutiveImpossibleBlackFrames = 0
            lastBlackEvidenceSensorTimestampNs = Long.MIN_VALUE
        }
        impossibleBlackConfirmed =
            consecutiveImpossibleBlackFrames >= BLACK_FAULT_CONFIRMATION_FRAMES

        if (slotHealth.isNotBlank()) outputSlotHealth = slotHealth
    }

    @Synchronized
    fun glAccepted(
        generation: Int,
        sensorTimestampNs: Long,
        producerKind: RawPreviewProducerKind,
        currentEglGeneration: Int,
        nowElapsedNs: Long
    ) {
        if (!matches(generation)) return
        lastGlAcceptedElapsedNs = nowElapsedNs
        frameIdentity(generation, sensorTimestampNs, producerKind)?.let { lastGlAcceptedFrame = it }
        if (currentEglGeneration > 0) eglGeneration = currentEglGeneration
    }

    @Synchronized
    fun glDraw(
        generation: Int,
        sensorTimestampNs: Long,
        producerKind: RawPreviewProducerKind,
        currentEglGeneration: Int,
        nowElapsedNs: Long
    ) {
        if (!matches(generation)) return
        if (firstGlDrawElapsedNs == 0L) firstGlDrawElapsedNs = nowElapsedNs
        lastGlDrawElapsedNs = nowElapsedNs
        frameIdentity(generation, sensorTimestampNs, producerKind)?.let { lastGlDrawFrame = it }
        if (currentEglGeneration > 0) eglGeneration = currentEglGeneration
    }

    @Synchronized
    fun displayPresented(
        generation: Int,
        sensorTimestampNs: Long,
        producerKind: RawPreviewProducerKind,
        nowElapsedNs: Long
    ) {
        if (!matches(generation)) return
        lastDisplayPresentElapsedNs = nowElapsedNs
        actualPresentationObserved = true
        frameIdentity(generation, sensorTimestampNs, producerKind)?.let { lastDisplayPresentedFrame = it }
    }

    @Synchronized
    fun eglContext(generation: Int, currentEglGeneration: Int) {
        if (!matches(generation) && pipelineGeneration >= 0) return
        if (currentEglGeneration > 0) eglGeneration = currentEglGeneration
    }

    @Synchronized
    fun updateOutputSlotHealth(generation: Int, value: String) {
        if (!matches(generation)) return
        if (value.isNotBlank()) outputSlotHealth = value
    }

    @Synchronized
    fun updateRingPressure(generation: Int, value: String) {
        if (!matches(generation)) return
        if (value.isNotBlank()) ringPressure = value
    }

    @Synchronized
    fun recoveryAttempt(generation: Int, reason: String, nowElapsedNs: Long) {
        if (!matches(generation)) return
        lastRecoveryReason = reason
        lastRecoveryElapsedNs = nowElapsedNs
    }

    @Synchronized
    fun snapshot(nowElapsedNs: Long): RawPreviewHealthSnapshot {
        val expected = maxOf(
            advertisedMinFrameDurationNs,
            latestSensorFrameDurationNs,
            latestExposureTimeNs,
            DEFAULT_EXPECTED_INTERVAL_NS
        )
        val threshold = max(MIN_STALL_THRESHOLD_NS, expected * STALL_INTERVAL_MULTIPLIER)
        val stage = classify(nowElapsedNs, threshold)
        val stageFrame = stageFrameHint(stage)
        return RawPreviewHealthSnapshot(
            source = source,
            pipelineGeneration = pipelineGeneration,
            eglGeneration = eglGeneration,
            stage = stage,
            expectedIntervalNs = expected,
            stallThresholdNs = threshold,
            routeStartedElapsedNs = routeStartedElapsedNs,
            lastCaptureResultElapsedNs = lastCaptureResultElapsedNs,
            lastImageReaderElapsedNs = lastImageReaderElapsedNs,
            lastRendererOfferElapsedNs = lastRendererOfferElapsedNs,
            lastRendererPublicationElapsedNs = lastRendererPublicationElapsedNs,
            lastGlAcceptedElapsedNs = lastGlAcceptedElapsedNs,
            firstGlDrawElapsedNs = firstGlDrawElapsedNs,
            lastGlDrawElapsedNs = lastGlDrawElapsedNs,
            lastDisplayPresentElapsedNs = lastDisplayPresentElapsedNs,
            latestSensorTimestampNs = latestSensorTimestampNs,
            latestSensorFrameDurationNs = latestSensorFrameDurationNs,
            latestExposureTimeNs = latestExposureTimeNs,
            advertisedMinFrameDurationNs = advertisedMinFrameDurationNs,
            lastImageReaderFrame = lastImageReaderFrame,
            lastRendererOfferFrame = lastRendererOfferFrame,
            lastRendererPublicationFrame = lastRendererPublicationFrame,
            lastGlAcceptedFrame = lastGlAcceptedFrame,
            lastGlDrawFrame = lastGlDrawFrame,
            lastDisplayPresentedFrame = lastDisplayPresentedFrame,
            stageFrameHint = stageFrame,
            exactDownstreamChainCoherent = exactDownstreamChainCoherent(),
            outputRgbMin = outputRgbMin,
            outputRgbMax = outputRgbMax,
            outputRgbMean = outputRgbMean,
            inputRawMax = inputRawMax,
            inputSceneP50 = inputSceneP50,
            consecutiveImpossibleBlackFrames = consecutiveImpossibleBlackFrames,
            impossibleBlackConfirmed = impossibleBlackConfirmed,
            outputSlotHealth = outputSlotHealth,
            ringPressure = ringPressure,
            actualPresentationObserved = actualPresentationObserved,
            lastRecoveryReason = lastRecoveryReason,
            lastRecoveryElapsedNs = lastRecoveryElapsedNs
        )
    }

    private fun classify(nowElapsedNs: Long, thresholdNs: Long): RawPreviewHealthStage {
        if (source == "YUV" || pipelineGeneration < 0) return RawPreviewHealthStage.INACTIVE
        fun recent(value: Long): Boolean = value > 0L && nowElapsedNs - value <= thresholdNs
        val routeAge = if (routeStartedElapsedNs > 0L) nowElapsedNs - routeStartedElapsedNs else Long.MAX_VALUE
        val anyMissing = lastCaptureResultElapsedNs == 0L || lastImageReaderElapsedNs == 0L ||
            lastRendererOfferElapsedNs == 0L || lastRendererPublicationElapsedNs == 0L ||
            lastGlAcceptedElapsedNs == 0L || lastGlDrawElapsedNs == 0L
        if (routeAge <= thresholdNs && anyMissing) return RawPreviewHealthStage.STARTING

        if (!recent(lastCaptureResultElapsedNs)) return RawPreviewHealthStage.CAMERA_CAPTURE_RESULT
        if (!recent(lastImageReaderElapsedNs)) return RawPreviewHealthStage.RAW_IMAGE_READER
        if (!recent(lastRendererOfferElapsedNs)) return RawPreviewHealthStage.RENDERER_OFFER
        if (!recent(lastRendererPublicationElapsedNs)) return RawPreviewHealthStage.RENDERER_PUBLICATION
        if (!recent(lastGlAcceptedElapsedNs)) return RawPreviewHealthStage.GL_ACCEPT
        if (!recent(lastGlDrawElapsedNs)) return RawPreviewHealthStage.GL_DRAW
        if (!actualPresentationObserved && firstGlDrawElapsedNs > 0L &&
            nowElapsedNs - firstGlDrawElapsedNs > thresholdNs
        ) {
            return RawPreviewHealthStage.EGL_PRESENTATION
        }
        if (actualPresentationObserved && !recent(lastDisplayPresentElapsedNs)) {
            return RawPreviewHealthStage.EGL_PRESENTATION
        }
        if (impossibleBlackConfirmed) {
            return RawPreviewHealthStage.RGB_OUTPUT
        }
        return RawPreviewHealthStage.HEALTHY
    }

    /**
     * Returns exact producer/frame evidence for downstream stages only. RAW_IMAGE_READER is
     * deliberately left unattributed because aggregate arrival freshness cannot decide which
     * configured Camera2 producer is the failed one.
     */
    private fun stageFrameHint(stage: RawPreviewHealthStage): RawPreviewProducerFrameIdentity? = when (stage) {
        RawPreviewHealthStage.RENDERER_OFFER -> lastImageReaderFrame
        RawPreviewHealthStage.RENDERER_PUBLICATION -> lastRendererOfferFrame
        RawPreviewHealthStage.GL_ACCEPT -> lastRendererPublicationFrame
        RawPreviewHealthStage.GL_DRAW -> lastGlAcceptedFrame
        RawPreviewHealthStage.EGL_PRESENTATION -> lastGlDrawFrame
        RawPreviewHealthStage.RGB_OUTPUT -> lastRendererPublicationFrame
        RawPreviewHealthStage.HEALTHY -> lastDisplayPresentedFrame
        else -> null
    }

    private fun exactDownstreamChainCoherent(): Boolean {
        val chain = listOfNotNull(
            lastRendererPublicationFrame,
            lastGlAcceptedFrame,
            lastGlDrawFrame,
            lastDisplayPresentedFrame
        )
        if (chain.size <= 1) return true
        // Only compare stages referring to the same or newer chronology. A latest publication may
        // legitimately be newer than the most recently presented frame, so coherence means no
        // stage reports a *different producer for the exact same SENSOR_TIMESTAMP*.
        return chain.groupBy { it.sensorTimestampNs }.values.all { sameTimestamp ->
            sameTimestamp.map { it.producerKind }.distinct().size <= 1
        }
    }

    private fun frameIdentity(
        generation: Int,
        sensorTimestampNs: Long,
        producerKind: RawPreviewProducerKind
    ): RawPreviewProducerFrameIdentity? =
        if (generation >= 0 && sensorTimestampNs > 0L) {
            RawPreviewProducerFrameIdentity(generation, sensorTimestampNs, producerKind)
        } else {
            null
        }

    private fun matches(generation: Int): Boolean = generation == pipelineGeneration

    internal const val MIN_STALL_THRESHOLD_NS: Long = 1_000_000_000L
    internal const val STALL_INTERVAL_MULTIPLIER: Long = 6L
    internal const val DEFAULT_EXPECTED_INTERVAL_NS: Long = 33_333_333L
    internal const val BLACK_FAULT_CONFIRMATION_FRAMES = 3
    internal const val MIN_RAW_SIGNAL_FOR_BLACK_FAULT = 0.005f
    internal const val MIN_SCENE_P50_FOR_BLACK_FAULT = 0.001f
    private const val BROKEN_RGB_MAX_EPSILON = 1.0e-6f
    private const val BROKEN_RGB_MEAN_EPSILON = 1.0e-7f
}
