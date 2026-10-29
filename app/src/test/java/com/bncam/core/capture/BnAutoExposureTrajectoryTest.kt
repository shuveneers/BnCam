package com.bncam.core.capture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log2

class BnAutoExposureTrajectoryTest {
    private val frame = 33_333_333L
    private val limits = BnAutoSensorLimits(ExposureBounds(100, 6400, 100_000L, 2_000_000_000L),
        2_000_000_000L, frame, 5_000_000L, 800)

    @Test fun trajectoryDoesNotTurnPipelineDelayIntoExposurePlateaus() {
        val engine = BnAutoExposureEngine()
        val gate = BnAutoFeedbackGate(continuousTrajectory = true)
        val pacing = BnAutoControlPacing(continuousTrajectory = true)
        var published = engine.current("main", limits)
        val pipeline = java.util.ArrayDeque<Pair<Long, BnAutoExposureDecision>>()
        repeat(5) { pipeline.add(1L to published) }
        var epoch = 1L
        val changes = mutableListOf<Double>()
        val products = mutableListOf<Double>()
        repeat(100) { i ->
            val now = 1_000_000_000L + i * frame
            val (sourceEpoch, physical) = pipeline.removeFirst()
            val warm = physical.warmAllocation(limits, RawFlickerConstraint())
            val p = (if (i < 50) 0.10 else 0.002) * warm.realizedExposureProduct / 1e9
            val o = BnAutoObservation(now + 50_000_000_000L, warm.exposureTimeNs,
                warm.sensitivityIso, p / 4, p, (p * 1.1).coerceAtMost(0.99), List(4) { 0.0 }, 3072, 0.0)
            pacing.observeFrame("main", o.timestampNs, frame)
            if (gate.admit("main", o.timestampNs, sourceEpoch, o.exposureNs, o.iso, now, frame) &&
                pacing.shouldMeter("main", o.timestampNs)) {
                val next = engine.observe("main", limits, o, committedDecision = published,
                    continuousTransitions = true, observedFramePeriodNs = frame)
                val oldWarm = published.warmAllocation(limits, RawFlickerConstraint())
                val newWarm = next.warmAllocation(limits, RawFlickerConstraint())
                if (pacing.shouldSubmit("main", o.timestampNs, oldWarm.exposureTimeNs, oldWarm.sensitivityIso,
                        newWarm.exposureTimeNs, newWarm.sensitivityIso, next.limitingConstraint) &&
                    gate.reserve("main", now)) {
                    val change = log2(newWarm.realizedExposureProduct / oldWarm.realizedExposureProduct)
                    if (i >= 50) changes.add(change)
                    published = next; epoch++
                    gate.submitted("main", epoch, now, newWarm.exposureTimeNs, newWarm.sensitivityIso)
                }
            }
            pipeline.addLast(epoch to published)
            products.add(warm.realizedExposureProduct)
        }
        assertTrue("Five-frame latency must still allow small consecutive trajectory updates", changes.size > 15)
        assertTrue("Every normal physical change stays below 0.11 EV", changes.all { abs(it) < 0.11 })
        assertTrue("Large change converges within 1.7 seconds including the physical pipeline",
            products.last() / products[49] > 6.0)
        assertTrue("No oscillation around the settled endpoint", products.takeLast(5).max() / products.takeLast(5).min() < 1.10)
    }

    @Test fun stalledAnalysisCannotProduceOneAccumulatedLargeStep() {
        val next = BnAutoExposureTrajectory.nextProduct(1e9, 1e9, 16e9,
            2_000_000_000L, frame, 0.9, false)
        assertTrue(abs(log2(next / 1e9)) <= 3 * frame / 1e9 + 1e-8)
    }

    @Test fun ignoredHalCannotLetTheTrajectoryRunAwayFromActualExposure() {
        assertEquals(8e9, BnAutoExposureTrajectory.nextProduct(8e9, 1e9, 32e9,
            frame, frame, 0.9, false), 0.01)
        assertTrue(BnAutoExposureTrajectory.nextProduct(8e9, 1e9, 0.5e9,
            frame, frame, 0.9, false) < 8e9)
    }

    @Test fun oldExactRequestCanMeterButAMismatchedResultCannot() {
        val gate = BnAutoFeedbackGate(continuousTrajectory = true)
        gate.submitted("main", 10L, 1_000_000_000L, 5_000_000L, 100)
        assertTrue(gate.reserve("main", 1_033_333_333L))
        gate.submitted("main", 11L, 1_033_333_333L, 5_000_000L, 110)
        assertTrue(gate.admit("main", 100L, 10L, 5_000_000L, 100, 1_100_000_000L, frame))
        assertFalse(gate.admit("main", 101L, 11L, 5_000_000L, 100, 1_100_000_001L, frame))
        assertFalse(gate.admit("main", 102L, 9L, 5_000_000L, 100, 1_100_000_002L, frame))
        assertTrue(gate.admit("main", 103L, 11L, 5_000_000L, 110, 1_110_000_000L, frame))
    }

    @Test fun stableFramesDoNotInflateFirstRealizationLatency() {
        val gate = BnAutoFeedbackGate(continuousTrajectory = true)
        gate.submitted("main", 10L, 1_000_000_000L, 5_000_000L, 100)
        assertTrue(gate.admit("main", 100L, 10L, 5_000_000L, 100, 1_150_000_000L, frame))
        assertEquals(150_000_000L, gate.realizationLatencyNs)
        assertTrue(gate.admit("main", 101L, 10L, 5_000_000L, 100, 6_150_000_000L, frame))
        assertEquals(150_000_000L, gate.realizationLatencyNs)
    }

    @Test fun onePercentTailStepsReachSensorInsteadOfAccumulatingIntoLargerSteps() {
        val p = BnAutoControlPacing(continuousTrajectory = true)
        val t = 1_000_000_000L
        p.observeFrame("main", t, frame)
        assertTrue(p.shouldSubmit("main", t, 10_000_000L, 100, 10_000_000L, 101, "NONE"))
        p.observeFrame("main", t + frame, frame)
        assertTrue(p.shouldSubmit("main", t + frame, 10_000_000L, 101, 10_000_000L, 102, "NONE"))
    }

    @Test fun queueRemainsSingleAndFineEvPointsAreNotQuantizedAway() {
        val gate = BnAutoFeedbackGate(continuousTrajectory = true)
        assertTrue(gate.reserve("main", 1_000_000_000L))
        assertFalse(gate.reserve("main", 1_010_000_000L))
        val pacing = BnAutoControlPacing(continuousTrajectory = true)
        pacing.observeFrame("main", 1_000_000_000L, frame)
        assertTrue(pacing.shouldMeter("main", 1_000_000_000L))
        assertTrue(pacing.shouldMeter("main", 1_000_000_000L + frame))
        assertTrue(pacing.shouldSubmit("main", 1_000_000_000L, 10_000_000L, 100, 10_000_000L, 105, "NONE"))
        assertFalse(pacing.shouldSubmit("main", 1_000_000_000L + frame / 2, 10_000_000L, 105, 10_000_000L, 110, "NONE"))
    }

    @Test fun safeKnownLightPeriodAcquiresDuringTheFirstHalfSecond() {
        for (frequency in listOf(RawFlickerFrequency.HZ_50, RawFlickerFrequency.HZ_60)) {
            val e = BnAutoExposureEngine()
            val f = RawFlickerConstraint(frequency)
            var published = e.current("main", limits)
            var firstAlignedFrame: Int? = null
            repeat(16) { i ->
                val w = published.warmAllocation(limits, f)
                val p = 0.1 * w.realizedExposureProduct / 1e9
                val o = BnAutoObservation(1_000_000_000L + i * frame, w.exposureTimeNs,
                    w.sensitivityIso, p / 4, p, p * 1.1, List(4) { 0.0 }, 3072, 0.0,
                    noiseSlope = 1e-6, noiseOffset = 1e-9,
                    sensorLumaSamples = DoubleArray(3072) { p * (0.6 + (it % 23) / 30.0) })
                published = e.observe("main", limits, o, f, committedDecision = published,
                    continuousTransitions = true, observedFramePeriodNs = frame)
                val next = published.warmAllocation(limits, f)
                if (f.isExposureAligned(next.exposureTimeNs) && firstAlignedFrame == null) firstAlignedFrame = i
            }
            assertNotNull("Known $frequency must not require a two-second feedback staircase", firstAlignedFrame)
            assertTrue(firstAlignedFrame!! <= 15)
        }
    }

    @Test fun nanosecondCadenceJitterCannotTurnAFrameRampIntoEveryOtherFrameSteps() {
        val p = BnAutoControlPacing(continuousTrajectory = true)
        val t = 1_000_000_000L
        p.observeFrame("main", t, frame)
        assertTrue(p.shouldMeter("main", t))
        assertTrue(p.shouldSubmit("main", t, 5_000_000L, 100, 5_000_000L, 110, "NONE"))
        p.observeFrame("main", t + frame - 3L, frame)
        assertTrue(p.shouldMeter("main", t + frame - 3L))
        assertTrue(p.shouldSubmit("main", t + frame - 3L, 5_000_000L, 110, 5_000_000L, 120, "NONE"))
    }

    @Test fun motionStillWinsAndShutterReallocationPreservesBrightnessDuringTheRamp() {
        val e = BnAutoExposureEngine()
        val original = BnAutoExposureDecision(10_000_000L, 400, frame, "NONE", 1f, "test")
        val o = BnAutoObservation(1_000_000_000L, original.targetExposureNs, original.targetISO,
            0.05, 0.3, 0.4, List(4) { 0.0 }, 3072, 0.0,
            subjectMotionCeilingNs = 1_000_000L, subjectMotionConfidence = 0.9f)
        val d = e.observe("main", limits, o, RawFlickerConstraint(RawFlickerFrequency.HZ_50),
            committedDecision = original, continuousTransitions = true, observedFramePeriodNs = frame)
        assertTrue(d.targetExposureNs <= 1_000_000L)
        assertTrue("Changing shutter for motion must not create an exposure jump",
            abs(log2(d.exposureProduct / original.exposureProduct)) < 0.15)
    }
}
