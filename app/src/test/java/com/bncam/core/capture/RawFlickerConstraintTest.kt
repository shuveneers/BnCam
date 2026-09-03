package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class RawFlickerConstraintTest {
    @Test
    fun `50hz long shutter quantizes down to ten millisecond multiple`() {
        val c = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        assertEquals(30_000_000L, c.constrainExposureNs(34_000_000L, 100_000L, 250_000_000L))
    }

    @Test
    fun `60hz uses rational light period without accumulated nanosecond drift`() {
        val c = RawFlickerConstraint(RawFlickerFrequency.HZ_60, "TEST")
        assertEquals(25_000_000L, c.constrainExposureNs(30_000_000L, 100_000L, 250_000_000L))
        assertEquals(16_666_667L, c.constrainExposureNs(17_000_000L, 100_000L, 250_000_000L))
    }

    @Test
    fun `shorter than one flicker period remains motion safe`() {
        val c = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        assertEquals(5_000_000L, c.constrainExposureNs(5_000_000L, 100_000L, 250_000_000L))
    }
}
