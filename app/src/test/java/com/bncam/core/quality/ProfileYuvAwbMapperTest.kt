package com.bncam.core.quality

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileYuvAwbMapperTest {
    @Test
    fun systemAutoEquivalentGainsResolveToIdentity() {
        val gains = floatArrayOf(2.0f, 1.0f, 1.0f, 1.6f)
        assertArrayEquals(
            floatArrayOf(1f, 1f, 1f),
            ProfileYuvAwbMapper.resolve(gains, gains),
            1.0e-6f
        )
    }

    @Test
    fun profileTargetIsMappedRelativeToCameraAwb() {
        val result = ProfileYuvAwbMapper.resolve(
            baseCameraGains = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f),
            effectiveProfileGains = floatArrayOf(1.5f, 1.0f, 1.0f, 2.0f)
        )
        assertEquals(0.75f, result[0], 1.0e-6f)
        assertEquals(1.00f, result[1], 1.0e-6f)
        assertEquals(4.0f / 3.0f, result[2], 1.0e-6f)
    }

    @Test
    fun invalidOrExtremeInputsFailClosedAndClamp() {
        assertArrayEquals(
            floatArrayOf(1f, 1f, 1f),
            ProfileYuvAwbMapper.resolve(null, null),
            1.0e-6f
        )
        val clamped = ProfileYuvAwbMapper.resolve(
            floatArrayOf(4f, 1f, 1f, 0.25f),
            floatArrayOf(0.25f, 1f, 1f, 4f)
        )
        assertEquals(0.50f, clamped[0], 1.0e-6f)
        assertEquals(2.00f, clamped[2], 1.0e-6f)
    }
}
