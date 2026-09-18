package com.bncam.core.quality

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GcamAwbCalibrationParserTest {
    @Test
    fun parsesAgcGawbSeriesAndGreenRatio() {
        val parsed = GcamAwbCalibrationParser.parse(
            """
            RG=0.52,0.66,0.82
            BG=0.91,0.73,0.55
            BGRG=1.013
            """.trimIndent(),
            "sensor.gawb"
        )
        assertEquals(3, parsed.points.size)
        assertEquals(1.013f, parsed.grGbRatio)
        assertTrue(parsed.format.contains("gawb"))
    }

    @Test
    fun parsesGcamTxtArrays() {
        val parsed = GcamAwbCalibrationParser.parse(
            """
            WB_RG = new float[]{0.55f, 0.70f, 0.90f};
            WB_BG = new float[]{1.05f, 0.75f, 0.50f};
            """.trimIndent(),
            "sensor.txt"
        )
        assertEquals(3, parsed.points.size)
        assertTrue(parsed.warnings.isEmpty())
    }
}
