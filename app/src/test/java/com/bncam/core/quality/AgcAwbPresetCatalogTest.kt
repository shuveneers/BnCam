package com.bncam.core.quality

import com.bncam.data.settings.AgcAwbPresetCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgcAwbPresetCatalogTest {
    @Test
    fun `agc v12 catalog contains the complete ui preset set`() {
        assertEquals(56, AgcAwbPresetCatalog.all.size)
        assertEquals((0..55).toList(), AgcAwbPresetCatalog.all.map { it.id })
        assertEquals("Google Pixel 2", AgcAwbPresetCatalog.byId(0)?.name)
        assertEquals("Samsung S21+Snap", AgcAwbPresetCatalog.byId(55)?.name)
        assertTrue(AgcAwbPresetCatalog.all.all { preset ->
            preset.points.size >= 2 && preset.points.all { point ->
                point.rgRatio.isFinite() && point.bgRatio.isFinite() &&
                    point.rgRatio in 0.05f..8.0f && point.bgRatio in 0.05f..8.0f
            }
        })
    }

    @Test
    fun `duplicate sensor display names remain independently selectable by id`() {
        val imx471 = AgcAwbPresetCatalog.all.filter { it.name == "Sony IMX471" }
        assertEquals(2, imx471.size)
        assertNotEquals(imx471[0].selectionLabel, imx471[1].selectionLabel)
        assertEquals(20, imx471[0].id)
        assertEquals(50, imx471[1].id)
    }

    @Test
    fun `known native v12 values are retained`() {
        val imx355Sunny = requireNotNull(AgcAwbPresetCatalog.byId(49))
        assertEquals(0.42347899f, imx355Sunny.points.first().rgRatio, 0.0000001f)
        assertEquals(0.70141399f, imx355Sunny.points.first().bgRatio, 0.0000001f)

        val pixel5Wide = requireNotNull(AgcAwbPresetCatalog.byId(8))
        assertEquals(1.0049068f, requireNotNull(pixel5Wide.grGbRatio), 0.000001f)
    }
}
