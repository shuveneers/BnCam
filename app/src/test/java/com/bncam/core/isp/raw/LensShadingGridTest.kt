package com.bncam.core.isp.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensShadingGridTest {
    @Test
    fun bilinearGainUsesCorrectCfaChannel() {
        val gains = FloatArray(2 * 2 * 4) { 1f }
        gains[(0 * 2 + 0) * 4] = 1f
        gains[(0 * 2 + 1) * 4] = 2f
        gains[(1 * 2 + 0) * 4] = 3f
        gains[(1 * 2 + 1) * 4] = 4f

        val center = LensShadingGrid.gainAt(gains, 2, 2, channel = 0, x = 50, y = 50, width = 101, height = 101)
        val untouchedGreen = LensShadingGrid.gainAt(gains, 2, 2, channel = 1, x = 50, y = 50, width = 101, height = 101)

        assertEquals(2.375f, center, 0.0001f)
        assertEquals(1f, untouchedGreen, 0.0001f)
    }

    @Test
    fun invalidGridIsRejectedAndNonFiniteGainBecomesIdentity() {
        assertNull(LensShadingGrid.sanitize(FloatArray(3), 2, 2))
        val sanitized = LensShadingGrid.sanitize(floatArrayOf(Float.NaN, 9f, -2f, 2f), 1, 1)!!
        assertEquals(1f, sanitized[0], 0f)
        assertEquals(3.5f, sanitized[1], 0f)
        assertEquals(0.25f, sanitized[2], 0f)
    }
}
