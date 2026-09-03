package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFlickerCadencePolicyTest {
    private val ranges = listOf(
        FlickerFpsRange(15, 30),
        FlickerFpsRange(30, 30),
        FlickerFpsRange(25, 25),
        FlickerFpsRange(30, 60),
        FlickerFpsRange(60, 60)
    )

    @Test
    fun `50hz prefers fixed 25fps phase cadence`() {
        val plan = RawFlickerCadencePolicy.resolve(ranges, 60, RawFlickerFrequency.HZ_50)
        assertEquals(FlickerFpsRange(25, 25), plan.selected)
        assertTrue(plan.exactPhaseCadence)
    }

    @Test
    fun `60hz prefers fixed 30fps phase cadence`() {
        val plan = RawFlickerCadencePolicy.resolve(ranges, 60, RawFlickerFrequency.HZ_60)
        assertEquals(FlickerFpsRange(30, 30), plan.selected)
        assertTrue(plan.exactPhaseCadence)
    }

    @Test
    fun `stream sustainability caps cadence before flicker ranking`() {
        val plan = RawFlickerCadencePolicy.resolve(
            listOf(FlickerFpsRange(15, 15), FlickerFpsRange(25, 25), FlickerFpsRange(30, 30)),
            20,
            RawFlickerFrequency.HZ_50
        )
        assertEquals(FlickerFpsRange(15, 15), plan.selected)
    }

    @Test
    fun `unknown flicker leaves existing fps policy authoritative`() {
        val plan = RawFlickerCadencePolicy.resolve(ranges, 60, RawFlickerFrequency.NONE)
        assertNull(plan.selected)
    }
}
