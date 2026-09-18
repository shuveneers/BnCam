package com.bncam.data.settings

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BnCamAwbPresetCatalogTest {
    @Test
    fun `auto is the default AWB authority`() {
        val settings = LensAwbCalibrationSettings()
        assertEquals(LensAwbCalibrationModes.AUTO, settings.mode)
        assertEquals("Auto", settings.summary())
    }

    @Test
    fun `catalog has fifteen unique lighting response presets`() {
        val all = BnCamAwbPresetCatalog.all
        assertEquals(15, all.size)
        assertEquals(15, all.map { it.id }.toSet().size)
        assertEquals(15, all.map { it.name }.toSet().size)
        BnCamAwbPresetGroup.entries.forEach { group ->
            assertEquals(5, all.count { it.group == group })
        }
    }

    @Test
    fun `presets stay within calibrated response bounds`() {
        assertTrue(BnCamAwbPresetCatalog.all.all { preset ->
            preset.redGainScale in 0.50f..1.50f && preset.blueGainScale in 0.50f..1.50f
        })
    }

    @Test
    fun `built in tone biases stay deliberately restrained`() {
        val nonNeutral = BnCamAwbPresetCatalog.all.filter { it.id != 0 }
        assertTrue(nonNeutral.all { preset ->
            abs(preset.redGainScale - 1.0f) <= 0.1001f &&
                abs(preset.blueGainScale - 1.0f) <= 0.1001f
        })
    }

    @Test
    fun `warm named presets actually render warmer`() {
        val warmNames = listOf("Tungsten", "Warm LED", "Candlelight", "Warm Mixed", "Sunset Preserve")
        warmNames.forEach { name ->
            val preset = BnCamAwbPresetCatalog.all.single { it.name == name }
            assertTrue(preset.redGainScale > preset.blueGainScale, "$name must bias output warm")
        }
    }

    @Test
    fun `cool named presets actually render cooler`() {
        val coolNames = listOf("Cool LED", "Cool Mixed", "Overcast", "Snow / Blue Hour")
        coolNames.forEach { name ->
            val preset = BnCamAwbPresetCatalog.all.single { it.name == name }
            assertTrue(preset.blueGainScale > preset.redGainScale, "$name must bias output cool")
        }
    }

    @Test
    fun `warm led and snow blue hour cannot invert their intended tone`() {
        val warmLed = BnCamAwbPresetCatalog.all.single { it.name == "Warm LED" }
        val snow = BnCamAwbPresetCatalog.all.single { it.name == "Snow / Blue Hour" }

        assertTrue(warmLed.redGainScale > 1.0f)
        assertTrue(warmLed.blueGainScale < 1.0f)
        assertTrue(snow.redGainScale < 1.0f)
        assertTrue(snow.blueGainScale > 1.0f)
    }

    @Test
    fun `preset strength blends from auto baseline to full preset`() {
        BnCamAwbPresetCatalog.all.forEach { preset ->
            assertEquals(1.0f, preset.redScaleAt(0.0f), 1.0e-6f)
            assertEquals(1.0f, preset.blueScaleAt(0.0f), 1.0e-6f)
            assertEquals(preset.redGainScale, preset.redScaleAt(1.0f), 1.0e-6f)
            assertEquals(preset.blueGainScale, preset.blueScaleAt(1.0f), 1.0e-6f)
        }
    }

    @Test
    fun `half strength remains between auto and full tone`() {
        BnCamAwbPresetCatalog.all.filter { it.id != 0 }.forEach { preset ->
            val redHalf = preset.redScaleAt(0.5f)
            val blueHalf = preset.blueScaleAt(0.5f)
            assertTrue(redHalf in minOf(1.0f, preset.redGainScale)..maxOf(1.0f, preset.redGainScale))
            assertTrue(blueHalf in minOf(1.0f, preset.blueGainScale)..maxOf(1.0f, preset.blueGainScale))
        }
    }

}
