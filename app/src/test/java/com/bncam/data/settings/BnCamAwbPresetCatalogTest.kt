package com.bncam.data.settings

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
}
