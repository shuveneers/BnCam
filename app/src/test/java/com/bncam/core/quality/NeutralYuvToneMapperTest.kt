package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeutralYuvToneMapperTest {
    @Test
    fun neutralToneReducesBlackClipping() {
        assertTrue(NeutralYuvToneMapper.map(0f) > 0.005f)
        assertTrue(NeutralYuvToneMapper.map(0.01f) > 0.01f)
    }

    @Test
    fun neutralToneKeepsHighlightClippingControlled() {
        assertTrue(NeutralYuvToneMapper.map(1f) < 0.995f)
        assertTrue(NeutralYuvToneMapper.map(0.99f) < 0.99f)
    }

    @Test
    fun defaultContrastIsLowerAndCurveIsMonotonic() {
        val lut = NeutralYuvToneMapper.buildLut()
        assertTrue((1 until lut.size).all { index -> lut[index] >= lut[index - 1] })
        assertTrue((lut.last() - lut.first()) < 255)
    }

    @Test
    fun yuvContractDoesNotUseRawToneDefaults() {
        assertEquals("NEUTRAL_YUV_LUMA", NeutralYuvToneMapper.TONE_MODE)
        assertTrue(NeutralYuvToneMapper.toneModeDescription().contains("rawToneDefaultsUsed=false"))
    }
}
