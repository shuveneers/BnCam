package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RawFlickerCadencePolicyTest {
    private val ranges = listOf(
        FlickerFpsRange(15, 30),
        FlickerFpsRange(25, 25),
        FlickerFpsRange(30, 30),
        FlickerFpsRange(30, 60),
        FlickerFpsRange(60, 60)
    )

    @Test
    fun `50hz does not force twenty five fps when stream can run sixty`() {
        val plan = RawFlickerCadencePolicy.resolve(ranges, 60, RawFlickerFrequency.HZ_50)
        assertEquals(FlickerFpsRange(30, 60), plan.selected)
        assertEquals(60, plan.targetFps)
        assertFalse(plan.exactPhaseCadence)
    }

    @Test
    fun `60hz does not force thirty fps when stream can run sixty`() {
        val plan = RawFlickerCadencePolicy.resolve(ranges, 60, RawFlickerFrequency.HZ_60)
        assertEquals(FlickerFpsRange(30, 60), plan.selected)
        assertEquals(60, plan.targetFps)
    }

    @Test
    fun `fifty nine fps stream keeps intersecting high range`() {
        val plan = RawFlickerCadencePolicy.resolve(ranges, 59, RawFlickerFrequency.HZ_50)
        assertEquals(FlickerFpsRange(30, 60), plan.selected)
        assertEquals(59, plan.targetFps)
    }

    @Test
    fun `thirty fps physical contract still selects thirty`() {
        val plan = RawFlickerCadencePolicy.resolve(ranges, 30, RawFlickerFrequency.HZ_50)
        assertEquals(FlickerFpsRange(30, 30), plan.selected)
        assertEquals(30, plan.targetFps)
    }

    @Test
    fun `missing stream contract leaves manager fallback authoritative`() {
        val plan = RawFlickerCadencePolicy.resolve(ranges, null, RawFlickerFrequency.HZ_50)
        assertNull(plan.selected)
        assertNull(plan.targetFps)
    }

    @Test
    fun `prefer adaptive lower selects fifteen thirty under fifty hz`() {
        val plan = RawFlickerCadencePolicy.resolve(ranges, 30, RawFlickerFrequency.HZ_50, preferAdaptiveLower = true)
        assertEquals(FlickerFpsRange(15, 30), plan.selected)
        assertEquals(30, plan.targetFps)
    }
}
