package com.bncam.core.capture

import org.junit.Assert.*
import org.junit.Test

class BnAutoRecoveryTest {
    private val limits = BnAutoSensorLimits(ExposureBounds(100, 6400, 100_000L, 2_000_000_000L),
        2_000_000_000L, 33_333_333L, 5_000_000L, 800)
    private fun frame(d: BnAutoExposureDecision, t: Long, radiance: Double): BnAutoObservation {
        val warm = d.warmAllocation(limits, RawFlickerConstraint())
        val p = (radiance * warm.realizedExposureProduct / 1e9).coerceIn(0.000001, 0.85)
        return BnAutoObservation(t, warm.exposureTimeNs, warm.sensitivityIso, p / 4, p,
            (p * 1.1).coerceAtMost(0.99), List(4) { 0.0 }, 3072, 0.0)
    }

    @Test fun physicalFeedbackConvergesAfterTwoFrameHalDelayWithoutTargetStacking() {
        val e = BnAutoExposureEngine()
        val g = BnAutoFeedbackGate()
        var physical = e.current("main", limits)
        var target = physical
        var epoch = 1L
        var inFlightAt = -1
        var submissions = 0
        var firstProduct = 0.0
        val products = mutableListOf<Double>()
        repeat(36) { i ->
            val host = 1_000_000_000L + i * 33_333_333L
            if (inFlightAt >= 0 && i - inFlightAt >= 2) { physical = target; inFlightAt = -1 }
            val o = frame(physical, host + 80_000_000_000L, 0.002)
            if (g.admit("main", o.timestampNs, if (inFlightAt >= 0) epoch - 1 else epoch,
                    o.exposureNs, o.iso, host, 33_333_333L)) {
                val next = e.observe("main", limits, o, committedDecision = target)
                if (kotlin.math.abs(kotlin.math.log2(next.exposureProduct / target.exposureProduct)) > 0.08) {
                    assertTrue(g.reserve("main", host))
                    target = next; epoch++; submissions++; inFlightAt = i
                    val w = target.warmAllocation(limits, RawFlickerConstraint())
                    g.submitted("main", epoch, host, w.exposureTimeNs, w.sensitivityIso)
                }
            }
            if (i == 0) firstProduct = physical.exposureProduct
            products.add(physical.exposureProduct)
        }
        assertTrue("Large scene step must gain several EV", products.last() > firstProduct * 8)
        assertTrue("Convergence must finish within one second", products.takeLast(6).max() / products.takeLast(6).min() < 1.15)
        assertTrue("One in-flight request limits submissions", submissions <= 18)
    }

    @Test fun validatedPeriodSurvivesCandidateScoreWithoutLongExposureSnrPermission() {
        val e = BnAutoExposureEngine()
        var d = e.current("main", limits)
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_50)
        repeat(16) { i ->
            val w = d.warmAllocation(limits, flicker)
            val p = 0.1 * w.realizedExposureProduct / 1e9
            val samples = DoubleArray(3072) { p * (0.6 + (it % 23) / 30.0) }
            val o = frame(d, 1_000_000_000L + i * 66_666_666L, 0.1).copy(
                exposureNs = w.exposureTimeNs, iso = w.sensitivityIso,
                noiseSlope = 1e-6, noiseOffset = 1e-9, sensorLumaSamples = samples)
            d = e.observe("main", limits, o, flicker, committedDecision = d)
        }
        assertEquals(d.summary(), 10_000_000L, d.targetExposureNs)
        assertEquals(33_333_333L, d.frameDurationNs)
    }

    @Test fun alternatingLedPhasesDoNotFlipTheMedianExposureTarget() {
        val e = BnAutoExposureEngine()
        var d = e.current("main", limits)
        val products = mutableListOf<Double>()
        repeat(40) { i ->
            val radiance = if (i < 12) 0.1 else if (i % 2 == 0) 0.07 else 0.13
            d = e.observe("main", limits, frame(d, 1_000_000_000L + i * 66_666_666L, radiance),
                committedDecision = d)
            products.add(d.exposureProduct)
        }
        assertTrue("A five-sample majority median used to alternate with LED phases",
            products.takeLast(12).max() / products.takeLast(12).min() < 1.10)
        val before = d.exposureProduct
        repeat(2) { i ->
            d = e.observe("main", limits, frame(d, 4_000_000_000L + i * 66_666_666L, 0.001),
                committedDecision = d)
        }
        assertTrue("A real scene step must bypass pulse history after two agreeing frames",
            d.exposureProduct > before * 1.5)
    }

    @Test fun broadClippedWindowCannotAlternateBetweenPointSourceAndClippingAuthority() {
        val e = BnAutoExposureEngine()
        var d = e.current("main", limits)
        val products = mutableListOf<Double>()
        repeat(24) { i ->
            val w = d.warmAllocation(limits, RawFlickerConstraint())
            val p = w.realizedExposureProduct / 1e9
            val clipped = p >= 0.98
            val o = BnAutoObservation(1_000_000_000L + i * 66_666_666L,
                w.exposureTimeNs, w.sensitivityIso, 0.025 * p,
                0.20 * p, p.coerceAtMost(1.0), List(4) { if (clipped) 0.04 else 0.0 },
                3072, 0.25, diffuseP50 = 0.1 * p, diffuseP80 = 0.2 * p,
                // Reproduces Delta04-07's exposure-dependent exclusion of a bright window.
                diffuseP98Maximum = if (clipped) 1.0 else 0.3 * p,
                channelP98Maximum = p.coerceAtMost(1.0),
                pointSourceFraction = if (clipped) 0.0 else 0.045)
            d = e.observe("main", limits, o, committedDecision = d)
            products.add(d.exposureProduct)
        }
        assertTrue("A stationary clipped window must not pump ISO", products.takeLast(12).max() / products.takeLast(12).min() < 1.15)
        assertTrue("Physical highlight protection survives point-source exclusion", products.last() < 0.90e9)
        val dark = frame(d, 4_000_000_000L, 0.001)
        val recovery = e.observe("main", limits, dark, committedDecision = d)
        assertTrue("A real dark scene retires the old highlight bound", recovery.exposureProduct > d.exposureProduct)
    }
}
