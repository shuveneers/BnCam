package com.bncam.core.capture

import org.junit.Assert.*
import org.junit.Test

/** Controlled observations of existing policies. Does not change Camera2 AE. */
class EveningMeteringSequenceTest {
    @Test fun `standard auto retains HAL ownership as lamps enter move and leave`() {
        val sequence = listOf(null, NormalizedPoint(.1f, .1f), NormalizedPoint(.5f, .5f),
            NormalizedPoint(.9f, .1f), null)
        sequence.forEach { lamp ->
            val statistics = scene(if (lamp == null) 0 else 100, lamp)
            val exposure = PhysicalSensorExposureAuthorityPolicy.resolve(false, false)
            val metering = CameraMeteringPolicy.plan(MeteringMode.AUTO_DEFAULT_AE, 1)
            assertTrue(exposure.camera2OwnsCompleteExposure)
            assertTrue(metering.restoreInitialAeRegions)
            assertTrue(metering.regions.isEmpty())
            println("ACTIVE_AUTO_SEQUENCE lamp=$lamp source=${statistics.source} owner=${exposure.owner} regions=${metering.source}")
        }
    }

    @Test fun `touch temporarily selects local metering and auto restores HAL regions`() {
        val auto = CameraMeteringPolicy.plan(MeteringMode.AUTO_DEFAULT_AE, 1)
        val touch = CameraMeteringPolicy.plan(MeteringMode.AUTO_DEFAULT_AE, 1, NormalizedPoint(.8f, .15f))
        val restored = CameraMeteringPolicy.plan(MeteringMode.AUTO_DEFAULT_AE, 1)
        assertTrue(auto.restoreInitialAeRegions)
        assertFalse(touch.restoreInitialAeRegions)
        assertEquals(1, touch.regions.size)
        assertTrue(restored.restoreInitialAeRegions)
        assertTrue(PhysicalSensorExposureAuthorityPolicy.resolve(false, false).camera2OwnsCompleteExposure)
    }

    private fun scene(lampPixels: Int, lampPoint: NormalizedPoint? = null): ExposureStatistics {
        val linear = IntArray(256)
        linear[8] = 10_000 - lampPixels
        linear[255] = lampPixels
        val display = IntArray(64)
        display[12] = 10_000 - lampPixels
        display[63] = lampPixels
        return ExposureStatistics("CONTROLLED_RAW", display, display.copyOf(), display.copyOf(),
            display.copyOf(), 10_000, .8f, lampPixels / 10_000f,
            lampPixels / 10_000f, lampPixels / 10_000f, lampPixels / 10_000f,
            lampPixels / 10_000f, linear, lampPoint)
    }

    @Test fun `legacy observer median and clipping constraint respond separately to small lamps`() {
        val tracker = DefaultRawMeteringTracker()
        val targetLuma = scene(0).exposureControllerLuma()
        val samples = listOf(0, 10, 49, 50, 100, 500, 2500, 0)
        samples.forEachIndexed { i, lamps ->
            val statistics = scene(lamps)
            assertEquals(targetLuma, statistics.exposureControllerLuma(), 0f)
            val metering = tracker.observe(1, statistics.source, statistics.exposureControllerLuma(),
                statistics.rawNearClipFraction, statistics.sampleCount, 1_000_000_000L + i * 50_000_000L)
            val target = requireNotNull(DefaultRawExposureTargetModel.resolve(targetLuma, metering, 1e11))
            assertEquals(0f, target.ideal.exposureErrorEv, 0f)
            if (lamps < 50) assertEquals(0f, target.highlightProtectionEv, 0f)
            else assertTrue(target.finalExposureProduct < target.ideal.idealExposureProduct)
            println("EVENING_SEQUENCE lamps=$lamps median=$targetLuma penalty=${target.highlightProtectionEv} product=${target.finalExposureProduct}")
        }
    }

    @Test fun `metering gate rejects old convergence and requires fresh scene evidence`() {
        val tracker = DefaultRawMeteringTracker()
        val gate = DefaultRawAeReferenceGate()
        val old = tracker.observe(3, "CONTROLLED_RAW", .03f, 0f, 10_000, 1_000_000_000L)
        gate.observeAeState(3, true, 1_100_000_000L)
        gate.observeAeState(3, true, 1_130_000_000L)
        assertFalse(gate.canAccept(3, old))
        val fresh = tracker.observe(3, "CONTROLLED_RAW", .03f, .01f, 10_000, 1_140_000_000L)
        assertTrue(gate.canAccept(3, fresh))
        assertFalse(gate.canAccept(3, tracker.resolve(3, 1_500_000_000L)))
        assertFalse(gate.canAccept(4, fresh))
        assertEquals(DefaultRawMeteringFreshness.BOOTSTRAP_REQUIRED,
            tracker.resolve(4, 1_510_000_000L).freshness)
    }

    @Test fun `route fallback retains reference instead of treating darker observation as intent`() {
        val observed = listOf(.03f, .008f, .012f, .03f)
        observed.forEach { assertEquals(.03f,
            requireNotNull(DefaultRawTargetContinuity.resolveFallbackTarget(null, .03f, it)), 0f) }
    }
}
