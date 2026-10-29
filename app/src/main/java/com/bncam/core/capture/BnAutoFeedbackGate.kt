package com.bncam.core.capture

import kotlin.math.abs

/** One queued control; continuous mode validates the submitted trajectory's physical results.
 * Host and sensor clocks are never mixed. */
internal class BnAutoFeedbackGate(private val continuousTrajectory: Boolean = false) {
    private data class Submitted(val epoch: Long, val exposure: Long, val iso: Int, val hostNs: Long,
        var realized: Boolean = false)
    private val submitted = java.util.ArrayDeque<Submitted>()
    private var trajectoryFloorEpoch = 0L
    private var lastSubmissionNs = 0L
    private var key = ""
    private var queued = false
    private var exposure = 0L
    private var iso = 0
    private var epoch = 0L
    private var startedNs = 0L
    private var lastSensorNs = 0L
    private var floorEpoch = 0L
    var realizationLatencyNs = 0L
        private set

    private fun select(value: String) {
        if (key == value) return
        key = value
        queued = false; exposure = 0L; epoch = 0L; startedNs = 0L
        lastSensorNs = 0L; floorEpoch = 0L; realizationLatencyNs = 0L
        submitted.clear(); trajectoryFloorEpoch = 0L; lastSubmissionNs = 0L
    }

    @Synchronized fun reserve(value: String, nowNs: Long): Boolean {
        select(value)
        if (queued || (!continuousTrajectory && exposure > 0L)) return false
        if (continuousTrajectory && exposure > 0L &&
            nowNs - lastSubmissionNs < 8_000_000L) return false
        queued = true
        startedNs = nowNs
        return true
    }

    @Synchronized fun cancelQueued(value: String): Boolean {
        // A retiring control callback must never reset the new sensor's gate.
        if (key != value) return false
        val canceled = queued
        queued = false
        return canceled
    }

    /** Called after setRepeatingRequest succeeds, including AF requests carrying this target. */
    @Synchronized fun submitted(value: String, requestEpoch: Long, timeNs: Long,
        exposureNs: Long, sensitivity: Int) {
        select(value)
        if (exposureNs <= 0 || sensitivity <= 0 || requestEpoch <= 0) return
        queued = false
        // AF/region-only rebuilds must neither extend the deadline nor invalidate usable RAW.
        if (exposure == exposureNs && iso == sensitivity && exposure > 0) return
        exposure = exposureNs; iso = sensitivity; epoch = requestEpoch
        floorEpoch = requestEpoch; startedNs = timeNs
        if (continuousTrajectory) {
            if (trajectoryFloorEpoch == 0L) trajectoryFloorEpoch = requestEpoch
            submitted.addLast(Submitted(requestEpoch, exposureNs, sensitivity, timeNs))
            while (submitted.size > 32) submitted.removeFirst()
            lastSubmissionNs = timeNs
        }
    }

    /** Admit only newer exact RAW/result pairs from the submitted request or its successors.
     * A HAL that quantizes/clamps settings is rebased on physical truth after a bounded deadline.
     */
    @Synchronized fun admit(value: String, sensorNs: Long, requestEpoch: Long,
        exposureNs: Long, sensitivity: Int, nowNs: Long, framePeriodNs: Long): Boolean {
        select(value)
        if (continuousTrajectory) return admitTrajectory(sensorNs, requestEpoch, exposureNs, sensitivity, nowNs, framePeriodNs)
        if (sensorNs <= lastSensorNs || requestEpoch < floorEpoch || requestEpoch <= 0) return false
        if (queued) {
            if (nowNs - startedNs < 1_000_000_000L) return false
            queued = false // A discarded lifecycle/control command cannot freeze AE.
        }
        if (exposure > 0L) {
            if (requestEpoch < epoch) return false
            val matches = abs(exposureNs - exposure) <= maxOf(100_000L, exposure / 50) &&
                abs(sensitivity - iso) <= maxOf(2, iso / 50)
            if (!matches && nowNs - startedNs < maxOf(350_000_000L, framePeriodNs * 6)) return false
            realizationLatencyNs = (nowNs - startedNs).coerceAtLeast(0L)
            exposure = 0L
        }
        lastSensorNs = sensorNs
        return exposureNs > 0 && sensitivity > 0
    }
    private fun admitTrajectory(sensorNs: Long, requestEpoch: Long, exposureNs: Long,
        sensitivity: Int, nowNs: Long, framePeriodNs: Long): Boolean {
        if (sensorNs <= lastSensorNs || requestEpoch <= 0 || requestEpoch < trajectoryFloorEpoch ||
            exposureNs <= 0 || sensitivity <= 0) return false
        if (queued && nowNs - startedNs >= 1_000_000_000L) queued = false
        val source = submitted.lastOrNull { it.epoch <= requestEpoch }
        if (source != null) {
            val matches = abs(exposureNs - source.exposure) <= maxOf(100_000L, source.exposure / 50) &&
                abs(sensitivity - source.iso) <= maxOf(2, source.iso / 50)
            if (!matches && nowNs - source.hostNs < maxOf(350_000_000L, framePeriodNs * 6)) return false
            if (matches && !source.realized) {
                realizationLatencyNs = (nowNs - source.hostNs).coerceAtLeast(0L)
                source.realized = true
            }
            // An earlier known request may meter radiance, but cannot acknowledge its successor.
            if (requestEpoch >= epoch) exposure = 0L
        } else if (trajectoryFloorEpoch > 0) return false
        lastSensorNs = sensorNs
        return true
    }

}
