package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DefaultRawExposureAllocatorTest {
    private val bounds = ExposureBounds(100, 12_800, 100_000L, 250_000_000L)

    @Test
    fun `50hz holds ten millisecond shutter and lets iso carry large product change`() {
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        val allocation = DefaultRawExposureAllocator.allocate(
            exposureProduct = 10_000_000.0 * 800.0,
            safeExposureCeilingNs = 30_000_000L,
            bounds = bounds,
            flickerConstraint = flicker,
            previousExposureNs = 10_000_000L
        )!!
        assertEquals(10_000_000L, allocation.exposureTimeNs)
        assertEquals(800, allocation.sensitivityIso)
        assertTrue(allocation.flickerShutterHeld)
    }

    @Test
    fun `product beyond iso authority lengthens shutter within motion ceiling`() {
        val allocation = DefaultRawExposureAllocator.allocate(
            exposureProduct = 20_000_000.0 * 12_800.0,
            safeExposureCeilingNs = 20_000_000L,
            bounds = bounds,
            flickerConstraint = RawFlickerConstraint()
        )!!
        assertEquals(20_000_000L, allocation.exposureTimeNs)
        assertEquals(12_800, allocation.sensitivityIso)
        assertTrue(abs(allocation.residualProductErrorEv) < 0.001f)
    }

    @Test
    fun `requested product beyond physical maximum is bounded once centrally`() {
        val allocation = DefaultRawExposureAllocator.allocate(
            exposureProduct = 1.0e15,
            safeExposureCeilingNs = 10_000_000L,
            bounds = bounds
        )!!
        assertTrue(allocation.productBoundApplied)
        assertEquals(10_000_000L, allocation.exposureTimeNs)
        assertEquals(12_800, allocation.sensitivityIso)
        assertEquals("MAX_ISO", allocation.limitingConstraint)
    }

    @Test
    fun `60hz allocation lands on complete light period when one fits`() {
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_60, "TEST")
        val allocation = DefaultRawExposureAllocator.allocate(
            exposureProduct = 8_333_333.0 * 400.0,
            safeExposureCeilingNs = 20_000_000L,
            bounds = bounds,
            flickerConstraint = flicker
        )!!
        assertTrue(flicker.isExposureAligned(allocation.exposureTimeNs))
    }

    @Test
    fun `invalid product cannot create an allocation`() {
        assertEquals(null, DefaultRawExposureAllocator.allocate(0.0, 10_000_000L, bounds))
    }

    @Test
    fun `same product and constraints produce same allocation independent of caller`() {
        val flicker = RawFlickerConstraint(RawFlickerFrequency.HZ_50, "TEST")
        val a = DefaultRawExposureAllocator.allocate(6.4e9, 20_000_000L, bounds, flicker)
        val b = DefaultRawExposureAllocator.allocate(6.4e9, 20_000_000L, bounds, flicker)
        assertNotNull(a)
        assertEquals(a, b)
    }
}
