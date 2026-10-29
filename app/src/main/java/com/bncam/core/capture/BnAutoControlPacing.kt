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
    private val rapidConvergenceIntervalNs: Long = 150_000_000L,
    private val continuousTrajectory: Boolean = false
) {
    private var activeKey: String? = null
    private var lastMeteredTimestampNs = 0L
    private var lastSubmittedTimestampNs = 0L
    private var lastRawFrameTimestampNs = 0L
    private var measuredFramePeriodNs: Long? = null
    private var awaitingSensorExposureNs: Long? = null
    private var awaitingSensorIso: Int? = null
    private var awaitingSinceNs = 0L
    private var lastRealizationStatus: String = "NONE"

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

    /** A pending Camera2 request is NOT a realized exposure. The matching RAW/result
     * pair acknowledges a new sensor setting, preventing speculative AE feedback.
     */
    @Synchronized
    fun observeRealized(key: String, timestampNs: Long, exposureNs: Long, iso: Int): String {
        switchKey(key)
        val expectedExposure = awaitingSensorExposureNs ?: return "NO_PENDING_REQUEST"
        val expectedIso = awaitingSensorIso ?: return "NO_PENDING_REQUEST"
        if (timestampNs <= 0L || exposureNs <= 0L || iso <= 0) return "INVALID_RESULT"
        val toleranceExposure = maxOf(150_000L, expectedExposure / 50L)
        val toleranceIso = maxOf(2, expectedIso / 50)
        if (abs(exposureNs - expectedExposure) <= toleranceExposure &&
            abs(iso - expectedIso) <= toleranceIso) {
            awaitingSensorExposureNs = null
            awaitingSensorIso = null
            awaitingSinceNs = 0L
            lastRealizationStatus = "ACKNOWLEDGED"
            return lastRealizationStatus
        }
        val deadline = maxOf(350_000_000L, (measuredFramePeriodNs ?: 33_333_333L) * 5L)
        if (timestampNs > awaitingSinceNs && timestampNs - awaitingSinceNs >= deadline) {
            awaitingSensorExposureNs = null
            awaitingSensorIso = null
            awaitingSinceNs = 0L
            lastRealizationStatus = "TIMEOUT_SENSOR_DID_NOT_MATCH"
            return lastRealizationStatus
        }
        lastRealizationStatus = "PENDING"
        return lastRealizationStatus
    }

    @Synchronized
    fun realizationStatus(key: String): String {
        switchKey(key)
        return lastRealizationStatus
    }

    private fun meteringPeriodNs(): Long = measuredFramePeriodNs?.let {
        if (continuousTrajectory) it else (it * 2L).coerceIn(32_000_000L, meteringIntervalNs)
    } ?: meteringIntervalNs

    private fun submissionPeriodNs(urgent: Boolean, largeSceneStep: Boolean): Long {
        val frame = measuredFramePeriodNs
        if (frame == null) return when {
            urgent -> safetyUpdateIntervalNs
            largeSceneStep -> rapidConvergenceIntervalNs
            else -> normalUpdateIntervalNs
        }
        // Realization gating prevents overlap; do not add another multi-frame hold after ACK.
        return when {
            urgent -> frame.coerceIn(8_000_000L, safetyUpdateIntervalNs)
            largeSceneStep -> frame.coerceIn(8_000_000L, rapidConvergenceIntervalNs)
            else -> frame.coerceIn(8_000_000L, normalUpdateIntervalNs)
        }
    }

    private fun switchKey(key: String) {
        if (activeKey == key) return
        activeKey = key
        lastMeteredTimestampNs = 0L
        lastSubmittedTimestampNs = 0L
        lastRawFrameTimestampNs = 0L
        measuredFramePeriodNs = null
        awaitingSensorExposureNs = null
        awaitingSensorIso = null
        awaitingSinceNs = 0L
        lastRealizationStatus = "NONE"
    }

    /** Skip heavy RAW metering on intermediate frames, never frame acquisition itself. */
    @Synchronized
    fun shouldMeter(key: String, timestampNs: Long): Boolean {
        switchKey(key)
        if (timestampNs <= 0L || timestampNs <= lastMeteredTimestampNs) return false
        if (!continuousTrajectory && lastMeteredTimestampNs > 0L &&
            timestampNs - lastMeteredTimestampNs < meteringPeriodNs()) return false
        lastMeteredTimestampNs = timestampNs
        return true
    }

    /**
     * Small changes are insufficient reason to rebuild repeating Camera2 controls.
     * Fast shortening remains possible for an actual motion or clipping safety constraint.
     * Compare against the LAST PUBLISHED decision (caller retains it until this returns true).
     */
    /** Called only after the approved target is chosen; source is the actual warm
     * repeating shutter/ISO, not a longer still-capture exposure target.
     */
    @Synchronized
    fun expectRealization(key: String, timestampNs: Long, exposureNs: Long, iso: Int) {
        switchKey(key)
        if (timestampNs > 0 && exposureNs > 0 && iso > 0) {
            awaitingSensorExposureNs = exposureNs
            awaitingSensorIso = iso
            awaitingSinceNs = timestampNs
            lastRealizationStatus = "PENDING"
        }
    }

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
        // Continuous ticks come from distinct observed physical RAW frames. Comparing their
        // interval to a smoothed nanosecond period can spuriously discard every other frame.
        if (continuousTrajectory && (timestampNs != lastRawFrameTimestampNs ||
            timestampNs <= lastSubmittedTimestampNs)) return false
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
        // Never queue a second ordinary AE target while the first sensor request is still
        // in flight. SAFETY shortening and clipping are allowed to preempt it.
        if (awaitingSensorExposureNs != null && !urgent) return false
        if (!continuousTrajectory && lastSubmittedTimestampNs != 0L &&
            timestampNs - lastSubmittedTimestampNs < intervalNs) return false
        if (!urgent && abs(productEv) < (if (continuousTrajectory) 0.005 else 0.20) &&
            abs(exposureEv) < (if (continuousTrajectory) 0.08 else 0.30)) return false
        if (abs(productEv) < 0.0005 && abs(exposureEv) < 0.0005) return false
        lastSubmittedTimestampNs = timestampNs
        return true
    }
}
