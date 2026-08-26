package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpectraProfileCharacterTest {
    @Test
    fun `all named characters round trip from their numeric values`() {
        SpectraProfileCharacters.presets.forEach { preset ->
            assertEquals(preset.name, SpectraProfileCharacters.infer(preset.values))
            assertEquals(preset, SpectraProfileCharacters.byName(preset.name))
        }
    }

    @Test
    fun `changing one slider makes the character custom`() {
        val base = SpectraProfileCharacters.natural.values
        assertEquals(
            SpectraProfileCharacters.CUSTOM,
            SpectraProfileCharacters.infer(base.copy(chroma = base.chroma + 0.10f))
        )
    }

    @Test
    fun `custom is not a persisted preset definition`() {
        assertNull(SpectraProfileCharacters.byName(SpectraProfileCharacters.CUSTOM))
    }
}
