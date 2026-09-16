package com.bncam.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NoiseModelPresetCatalogTest {
    @Test
    fun agcV12CatalogContainsAllExtractedPresets() {
        val all = AgcV12NoiseModelPresets.all
        assertEquals(96, all.size)
        assertEquals("agc_v12:1", all.first().id)
        assertEquals("1. OV48C - Mi10U", all.first().displayName)
        assertEquals(3100.0, all.first().model.isoStep)
        assertEquals(6.83077811393138e-7, all.first().model.a[0])
        assertEquals("agc_v12:96", all.last().id)
        assertEquals("96. op12_ov64b_tele_nm", all.last().displayName)
        assertEquals(1600.0, all.last().model.isoStep)
    }

    @Test
    fun userPresetsAreAlwaysBeforeAgcPresets() {
        val userOld = userPreset("user:old", "Old", 10)
        val userNew = userPreset("user:new", "New", 20)
        val merged = NoiseModelPresetCatalog.merged(listOf(userOld, userNew))
        assertEquals(listOf("user:new", "user:old"), merged.take(2).map { it.id })
        assertTrue(merged.drop(2).all { it.origin == NoiseModelPresetOrigin.AGC_V12 })
    }

    @Test
    fun agcStyleTextImportParsesSignedCoefficientsAndIsoStep() {
        val text = """
            static double noise_model_A[] = { 1e-6, 2e-6, 3e-6, 4e-6 };
            static double noise_model_B[] = { -1e-5, 2e-5, -3e-5, 4e-5 };
            static double noise_model_C[] = { 1e-10, 2e-10, 3e-10, 4e-10 };
            static double noise_model_D[] = { 1e-7, 2e-7, 3e-7, 4e-7 };
            double digital_gain = (sens / 800.0) < 1.0 ? 1.0 : (sens / 800.0);
        """.trimIndent()
        val preset = NoiseModelPresetImportParser.parse(text, "My sensor", importedAtEpochMs = 1234L)
        assertEquals(NoiseModelPresetOrigin.USER, preset.origin)
        assertEquals("My sensor", preset.displayName)
        assertEquals(800.0, preset.model.isoStep)
        assertEquals(-1e-5, preset.model.b[0])
        assertEquals(1234L, preset.importedAtEpochMs)
        assertTrue(preset.id.startsWith("user:"))
    }

    @Test
    fun userCodecRoundTrips() {
        val original = userPreset("user:abc", "Imported α", 42)
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
