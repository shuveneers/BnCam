package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorStreamCadencePolicyTest {
    private val ranges = listOf(
        FlickerFpsRange(15, 30),
        FlickerFpsRange(30, 30),
        FlickerFpsRange(30, 60),
        FlickerFpsRange(60, 60)
    )

    @Test
    fun `slowest active output owns sustainable cadence`() {
        val plan = SensorStreamCadencePolicy.resolve(
            availableRanges = ranges,
            outputs = listOf(
                StreamCadenceOutput("raw", 16_666_667L),
                StreamCadenceOutput("display", 8_333_333L)
            )
        )
        assertEquals(60, plan.sustainableUpperFps)
        assertEquals(60, plan.effectiveUpperFps)
        assertEquals(FlickerFpsRange(30, 60), plan.selected)
        assertEquals("raw", plan.limitingOutput)
    }

    @Test
    fun `thirty fps output chooses fixed thirty when it is equally effective`() {
        val plan = SensorStreamCadencePolicy.resolve(
            availableRanges = ranges,
            outputs = listOf(
                StreamCadenceOutput("raw", 16_666_667L),
                StreamCadenceOutput("preview", 33_333_333L)
            )
        )
        assertEquals(30, plan.sustainableUpperFps)
        assertEquals(FlickerFpsRange(30, 30), plan.selected)
    }

    @Test
    fun `intersecting sixty range preserves a physical forty five fps stream ceiling`() {
        val plan = SensorStreamCadencePolicy.resolveFromSustainableUpperFps(ranges, 45)
        assertEquals(FlickerFpsRange(30, 60), plan.selected)
        assertEquals(45, plan.effectiveUpperFps)
    }

    @Test
    fun `intersecting sixty range preserves a physical fifty nine fps stream ceiling`() {
        val plan = SensorStreamCadencePolicy.resolveFromSustainableUpperFps(ranges, 59)
        assertEquals(FlickerFpsRange(30, 60), plan.selected)
        assertEquals(59, plan.effectiveUpperFps)
    }

    @Test
    fun `adaptive high range wins over fixed high range at equal upper bound`() {
        val plan = SensorStreamCadencePolicy.resolveFromSustainableUpperFps(ranges, 60)
        assertEquals(FlickerFpsRange(30, 60), plan.selected)
    }

    @Test
    fun `unknown stream duration does not invent a cadence contract`() {
        val plan = SensorStreamCadencePolicy.resolve(
            availableRanges = ranges,
            outputs = listOf(StreamCadenceOutput("raw", 0L))
        )
        assertNull(plan.selected)
        assertNull(plan.sustainableUpperFps)
    }
}
