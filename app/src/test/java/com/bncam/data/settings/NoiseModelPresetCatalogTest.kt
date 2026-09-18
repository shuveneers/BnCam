package com.bncam.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NoiseModelPresetCatalogTest {
    @Test
    fun `bncam catalog contains twenty presets grouped five per lens class`() {
        val all = BnCamNoiseModelPresets.all
        assertEquals(20, all.size)
        NoiseModelPresetGroup.entries.forEach { group ->
            assertEquals(5, all.count { it.group == group })
        }
        assertTrue(all.all { it.origin == NoiseModelPresetOrigin.BNCAM })
        assertTrue(all.all { it.id.startsWith("bncam:") })
    }

    @Test
    fun `user presets are always before builtins`() {
        val userOld = userPreset("user:old", "Old", 10)
        val userNew = userPreset("user:new", "New", 20)
        val merged = NoiseModelPresetCatalog.merged(listOf(userOld, userNew))
        assertEquals(listOf("user:new", "user:old"), merged.take(2).map { it.id })
        assertTrue(merged.drop(2).all { it.origin == NoiseModelPresetOrigin.BNCAM })
    }

    @Test
    fun `text import parses signed coefficients and iso step`() {
        val text = """
            static double noise_model_A[] = { 1e-6, 2e-6, 3e-6, 4e-6 };
            static double noise_model_B[] = { -1e-5, 2e-5, -3e-5, 4e-5 };
            static double noise_model_C[] = { 1e-10, 2e-10, 3e-10, 4e-10 };
            static double noise_model_D[] = { 1e-7, 2e-7, 3e-7, 4e-7 };
            double digital_gain = (sens / 800.0) < 1.0 ? 1.0 : (sens / 800.0);
        """.trimIndent()
        val preset = NoiseModelPresetImportParser.parse(text, "My sensor", importedAtEpochMs = 1234L)
        assertEquals(NoiseModelPresetOrigin.USER, preset.origin)
        assertEquals(800.0, preset.model.isoStep)
        assertEquals(-1e-5, preset.model.b[0])
        assertTrue(preset.id.startsWith("user:"))
    }

    @Test
    fun `user codec round trips`() {
        val original = userPreset("user:abc", "Imported alpha", 42)
        val decoded = NoiseModelUserPresetCodec.decode(NoiseModelUserPresetCodec.encode(original))
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    private fun userPreset(id: String, name: String, importedAt: Long) = NoiseModelPreset(
        id = id,
        displayName = name,
        origin = NoiseModelPresetOrigin.USER,
        importedAtEpochMs = importedAt,
        model = PersistedParametricNoiseModel(
            a = listOf(1.0, 2.0, 3.0, 4.0),
            b = listOf(-1.0, -2.0, -3.0, -4.0),
            c = listOf(5.0, 6.0, 7.0, 8.0),
            d = listOf(9.0, 10.0, 11.0, 12.0),
            isoStep = 800.0
        )
    )
}
