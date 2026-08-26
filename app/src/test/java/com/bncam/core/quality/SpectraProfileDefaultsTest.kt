package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraProfileDefaultsTest {
    @Test
    fun `default toggle stays off while latent values equal Natural`() {
        assertFalse(SpectraProfileDefaults.ENABLED)
        assertEquals(SpectraProfileDefaults.values(), SpectraProfileCharacters.natural.values)
        assertEquals("Natural", SpectraProfileCharacters.infer(SpectraProfileDefaults.values()))
    }

    @Test
    fun `clean and night are stronger than natural without reducing detail protection to negative`() {
        val natural = SpectraProfileCharacters.natural.values
        val clean = SpectraProfileCharacters.clean.values
        val night = SpectraProfileCharacters.night.values
        assertTrue(clean.dynamicIso >= natural.dynamicIso)
        assertTrue(clean.chroma > natural.chroma)
        assertTrue(clean.lowFrequency > natural.lowFrequency)
        assertTrue(night.dynamicIso >= clean.dynamicIso)
        assertTrue(night.chroma > clean.chroma)
        assertTrue(night.lowFrequency > clean.lowFrequency)
        assertTrue(natural.detailProtection > 0f)
        assertTrue(clean.detailProtection >= 0f)
        assertTrue(night.detailProtection >= 0f)
    }
}
