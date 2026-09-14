package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

class DefaultRawShutterManualFallbackPolicyTest {
    private val bounds = ExposureBounds(50, 12_800, 100_000L, 250_000_000L)

    @Test
    fun `initial plan preserves ae sensitivity with longest safe shutter`() {
        val plan = DefaultRawShutterManualFallbackPolicy.initial(
            1_600.0 * 40_000_000.0, 0.18f, 80_000_000L, bounds
        )!!
        assertEquals(80_000_000L, plan.exposureTimeNs)
        assertEquals(800, plan.sensitivityIso)
    }

    @Test
    fun `three stop dark step receives fast attack instead of quarter stop crawl`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            100.0 * 10_000_000.0, 0.18f, 10_000_000L, bounds
        )!!
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, 0.0225f, 0f, 10_000_000L, bounds
        )
        assertTrue(next.lastCorrectionEv >= 2.0f)
        assertTrue(next.exposureProduct > initial.exposureProduct * 4.0)
    }

    @Test
    fun `three stop bright step receives symmetric fast negative attack`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            800.0 * 10_000_000.0, 0.10f, 10_000_000L, bounds
        )!!
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, 0.80f, 0f, 10_000_000L, bounds
        )
        assertTrue(next.lastCorrectionEv <= -2.0f)
        assertTrue(next.exposureProduct < initial.exposureProduct / 4.0)
    }

    @Test
    fun `50hz fast attack holds aligned shutter and moves iso`() {
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            100.0 * 10_000_000.0, 0.18f, 10_000_000L, bounds, flicker
        )!!
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, 0.0225f, 0f, 40_000_000L, bounds, flicker
        )
        assertEquals(10_000_000L, next.exposureTimeNs)
        assertTrue(flicker.isExposureAligned(next.exposureTimeNs))
        assertTrue(next.sensitivityIso >= 500)
    }

    @Test
    fun `60hz fast attack holds aligned shutter and moves iso`() {
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_60, "TEST")
        val aligned = flicker.periodNs!!
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            100.0 * aligned.toDouble(), 0.18f, aligned, bounds, flicker
        )!!
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, 0.0225f, 0f, 50_000_000L, bounds, flicker
        )
        assertEquals(aligned, next.exposureTimeNs)
        assertTrue(flicker.isExposureAligned(next.exposureTimeNs))
        assertTrue(next.sensitivityIso >= 500)
    }

    @Test
    fun `large error cadence is no longer flicker throttled to 500ms`() {
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            100.0 * 10_000_000.0, 0.18f, 10_000_000L, bounds, flicker
        )!!
        val interval = DefaultRawShutterManualFallbackPolicy.recommendedUpdateIntervalNs(
            initial, 0.0225f, 0f
        )
        assertTrue(interval <= 50_000_000L)
    }

    @Test
    fun `three stop step reaches half stop residual within two fresh feedback updates`() {
        var observed = 0.0225f
        var plan = DefaultRawShutterManualFallbackPolicy.initial(
            100.0 * 10_000_000.0, 0.18f, 40_000_000L, bounds
        )!!
        repeat(2) {
            val next = DefaultRawShutterManualFallbackPolicy.adapt(
                plan, observed, 0f, 40_000_000L, bounds
            )
            observed = (observed * 2.0.pow(next.lastCorrectionEv.toDouble())).toFloat()
            plan = next
        }
        val residual = abs(log2(0.18 / observed.toDouble()))
        assertTrue("residual=$residual", residual <= 0.5)
    }


    @Test
    fun `measured 3 point 459 ev dark case reaches half stop residual in two updates`() {
        var observed = 0.001953125f
        val target = 0.021484375f
        var plan = DefaultRawShutterManualFallbackPolicy.initial(
            100.0 * 10_000_000.0, target, 40_000_000L, bounds
        )!!
        repeat(2) {
            val next = DefaultRawShutterManualFallbackPolicy.adapt(
                plan, observed, 0f, 40_000_000L, bounds
            )
            observed = (observed * 2.0.pow(next.lastCorrectionEv.toDouble())).toFloat()
            plan = next
        }
        val residual = abs(log2(target.toDouble() / observed.toDouble()))
        assertTrue("residual=$residual", residual <= 0.5)
    }

    @Test
    fun `small stable error settles inside deadband without pumping`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            400.0 * 10_000_000.0, 0.18f, 40_000_000L, bounds
        )!!
        val observed = (0.18 / 2.0.pow(0.03)).toFloat()
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, observed, 0f, 40_000_000L, bounds
        )
        assertEquals(0f, next.lastCorrectionEv, 0.0001f)
        assertEquals(initial.exposureProduct, next.exposureProduct, 0.001)
    }

    @Test
    fun `severe raw clipping causes immediate negative pressure`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            800.0 * 10_000_000.0, 0.18f, 40_000_000L, bounds
        )!!
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, 0.18f, 0.05f, 40_000_000L, bounds
        )
        assertTrue(next.lastCorrectionEv <= -1.0f)
    }

    @Test
    fun `moderate specular clipping does not reverse a strongly underexposed scene`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            100.0 * 10_000_000.0, 0.18f, 40_000_000L, bounds
        )!!
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, 0.045f, 0.01f, 40_000_000L, bounds
        )
        assertTrue(next.lastCorrectionEv > 0f)
    }

    @Test
    fun `unreachable dark demand is anti windup bounded to max iso times safe shutter`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            12_800.0 * 40_000_000.0, 0.18f, 40_000_000L, bounds
        )!!
        val next = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, 0.001f, 0f, 40_000_000L, bounds
        )
        assertEquals(12_800.0 * 40_000_000.0, next.exposureProduct, 1.0)
        assertEquals(12_800, next.sensitivityIso)
        assertTrue(next.reason.contains("product_bounded_to_sensor_authority"))
    }

    @Test
    fun `motion reallocates same product between shutter and iso`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            1_600.0 * 40_000_000.0, 0.18f, 80_000_000L, bounds
        )!!
        val moved = DefaultRawShutterManualFallbackPolicy.reallocateForMotion(
            initial, 20_000_000L, bounds
        )
        assertEquals(initial.exposureProduct, moved.exposureProduct, 0.001)
        assertEquals(20_000_000L, moved.exposureTimeNs)
        assertEquals(3_200, moved.sensitivityIso)
    }

    @Test
    fun `50hz manual fallback does not impose a 40ms frame cadence`() {
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        val plan = DefaultRawShutterManualFallbackPolicy.initial(
            referenceExposureProduct = 800.0 * 10_000_000.0,
            targetLuma = 0.18f,
            safeExposureCeilingNs = 10_000_000L,
            bounds = bounds,
            flickerConstraint = flicker
        )!!

        assertEquals(10_000_000L, plan.exposureTimeNs)
        assertEquals(10_000_000L, plan.frameDurationNs)
    }
    @Test
    fun `motion reallocation preserves last feedback correction and reports shutter allocation delta`() {
        val initial = DefaultRawShutterManualFallbackPolicy.initial(
            400.0 * 40_000_000.0, 0.18f, 80_000_000L, bounds
        )!!
        val adapted = DefaultRawShutterManualFallbackPolicy.adapt(
            initial, 0.09f, 0f, 80_000_000L, bounds
        )
        assertTrue(adapted.feedbackCorrectionEv > 0f)

        val moved = DefaultRawShutterManualFallbackPolicy.reallocateForMotion(
            adapted, 20_000_000L, bounds
        )
        assertEquals(adapted.feedbackCorrectionEv, moved.feedbackCorrectionEv, 0.0001f)
        assertEquals(adapted.feedbackCorrectionEv, moved.lastCorrectionEv, 0.0001f)
        assertTrue(moved.motionReallocationEv < 0f)
        assertTrue(moved.summary().contains("feedbackCorrectionEv="))
        assertTrue(moved.summary().contains("motionReallocationEv="))
    }

}
