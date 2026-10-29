package com.bncam.core.capture

import kotlin.math.abs
import kotlin.math.log2

/**
 * BnC Auto only: separates RAW sampling cadence from sensor request submission cadence.
 * It never changes the Camera2 stream FPS, AF request policy, or Standard Auto owner.
 * Inputs use the monotonic SENSOR_TIMESTAMP domain and are generation/lens scoped by key.
 */
internal class BnAutoControlPacing(
    private val meteringIntervalNs: Long = 150_000_000L,
    private val normalUpdateIntervalNs: Long = 320_000_000L,
    private val safetyUpdateIntervalNs: Long = 100_000_000L,
    private val rapidConvergenceIntervalNs: Long = 150_000_000L
) {
    private var activeKey: String? = null
    private var lastMeteredTimestampNs = 0L
    private var lastSubmittedTimestampNs = 0L
    private var lastRawFrameTimestampNs = 0L
    private var measuredFramePeriodNs: Long? = null

    /**
     * Observed physical RAW cadence is the authority for controller scheduling. The requested
     * FPS range is NOT a guarantee when CONTROL_AE_MODE is OFF. Both the exact frame timestamp
     * delta and SENSOR_FRAME_DURATION are checked; only plausible sensor periods are admitted.
     * A single stalled/dropped frame must not suddenly make the controller sluggish.
     */
    @Synchronized
    fun observeFrame(key: String, sensorTimestampNs: Long, sensorFrameDurationNs: Long?) {
        switchKey(key)
        if (sensorTimestampNs <= lastRawFrameTimestampNs || sensorTimestampNs <= 0L) return
        val deltaNs = if (lastRawFrameTimestampNs > 0L)
            sensorTimestampNs - lastRawFrameTimestampNs else 0L
        lastRawFrameTimestampNs = sensorTimestampNs
        val metadataNs = sensorFrameDurationNs?.takeIf { it in 8_000_000L..200_000_000L }
        val measuredNs = deltaNs.takeIf { it in 8_000_000L..200_000_000L }
        // If a frame was skipped the duration metadata is the better estimate, if supplied.
        val selected = when {
            measuredNs != null && metadataNs != null &&
                measuredNs > metadataNs * 3L / 2L -> metadataNs
            measuredNs != null -> measuredNs
            else -> metadataNs
        } ?: return
        val previous = measuredFramePeriodNs
        measuredFramePeriodNs = if (previous == null) selected
            else ((previous * 3L + selected) / 4L).coerceIn(8_000_000L, 200_000_000L)
    }

    @Synchronized
    fun measuredFramePeriodNs(key: String): Long? {
        switchKey(key)
        return measuredFramePeriodNs
    }

    private fun meteringPeriodNs(): Long = measuredFramePeriodNs?.let {
        (it * 2L).coerceIn(32_000_000L, meteringIntervalNs)
    } ?: meteringIntervalNs

    private fun submissionPeriodNs(urgent: Boolean, largeSceneStep: Boolean): Long {
        val frame = measuredFramePeriodNs
        if (frame == null) return when {
            urgent -> safetyUpdateIntervalNs
            largeSceneStep -> rapidConvergenceIntervalNs
            else -> normalUpdateIntervalNs
        }
        // ~2 physical frames for motion/clipping, ~3 for a major brightness step.
        // Minor updates are intentionally slower so Camera2 and AF are not flooded.
        return when {
            urgent -> (frame * 2L).coerceIn(48_000_000L, safetyUpdateIntervalNs)
            largeSceneStep -> (frame * 3L).coerceIn(64_000_000L, rapidConvergenceIntervalNs)
            else -> (frame * 6L).coerceIn(180_000_000L, normalUpdateIntervalNs)
        }
    }

    private fun switchKey(key: String) {
        if (activeKey == key) return
        activeKey = key
        lastMeteredTimestampNs = 0L
        lastSubmittedTimestampNs = 0L
        lastRawFrameTimestampNs = 0L
        measuredFramePeriodNs = null
    }

    /** Skip heavy RAW metering on intermediate frames, never frame acquisition itself. */
    @Synchronized
    fun shouldMeter(key: String, timestampNs: Long): Boolean {
        switchKey(key)
        if (timestampNs <= 0L || timestampNs <= lastMeteredTimestampNs) return false
        if (lastMeteredTimestampNs > 0L &&
            timestampNs - lastMeteredTimestampNs < meteringPeriodNs()) return false
        lastMeteredTimestampNs = timestampNs
        return true
    }

    /**
     * Small changes are insufficient reason to rebuild repeating Camera2 controls.
     * Fast shortening remains possible for an actual motion or clipping safety constraint.
     * Compare against the LAST PUBLISHED decision (caller retains it until this returns true).
     */
    @Synchronized
    fun shouldSubmit(
        key: String,
        timestampNs: Long,
        previousExposureNs: Long,
        previousIso: Int,
        proposedExposureNs: Long,
        proposedIso: Int,
        limitingConstraint: String
    ): Boolean {
        switchKey(key)
        if (timestampNs <= 0L || previousExposureNs <= 0L || proposedExposureNs <= 0L ||
            previousIso <= 0 || proposedIso <= 0) return false
        if (lastSubmittedTimestampNs > timestampNs) return false
        val exposureEv = log2(proposedExposureNs.toDouble() / previousExposureNs)
        val productEv = exposureEv + log2(proposedIso.toDouble() / previousIso)
        if (!exposureEv.isFinite() || !productEv.isFinite()) return false
        val shorteningForSafety = proposedExposureNs.toDouble() / previousExposureNs < 0.82 &&
            (limitingConstraint == "SUBJECT_MOTION" ||
                limitingConstraint == "CAMERA_MOTION" ||
                limitingConstraint == "RAW_DETAIL_MOTION_BOUND")
        val urgent = shorteningForSafety ||
            (limitingConstraint == "SENSOR_CLIPPING" && productEv < -0.20)
        // A definite scene step must not take seconds to reach the sensor. Ordinary small
        // fluctuations still use the slower hold; urgent motion/clipping retains priority.
        val largeSceneStep = abs(productEv) >= 0.35
        val intervalNs = submissionPeriodNs(urgent, largeSceneStep)
        if (lastSubmittedTimestampNs != 0L && timestampNs - lastSubmittedTimestampNs < intervalNs) return false
        if (!urgent && abs(productEv) < 0.20 && abs(exposureEv) < 0.30) return false
        if (abs(productEv) < 0.0005 && abs(exposureEv) < 0.0005) return false
        lastSubmittedTimestampNs = timestampNs
        return true
    }
}
