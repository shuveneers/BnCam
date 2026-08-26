package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureStatisticsTest {
    @Test
    fun histogramPercentilesAreMonotonic() {
        val stats = testStats()
        assertTrue(stats.lumaPercentile(0.25f) <= stats.lumaPercentile(0.50f))
        assertTrue(stats.lumaPercentile(0.50f) <= stats.lumaPercentile(0.95f))
    }

    @Test
    fun clippingDiagnosticsRemainAvailableWithoutOwningAe() {
        val stats = testStats(rawClip = 0.01f, displayClip = 0.03f)

        assertEquals(0.01f, stats.rawNearClipFraction ?: -1f, 0.0001f)
        assertEquals(0.03f, stats.maximumDisplayClipFraction, 0.0001f)
        assertTrue(stats.highDynamicRangeRisk)
    }

    @Test
    fun exposureControllerPrefersSceneLinearHistogramWhenAvailable() {
        val linear = IntArray(256).also { it[64] = 1000 }
        val base = testStats()
        val stats = base.copy(linearLumaHistogram256 = linear)
        val expected = (64.5f / 256f)
        assertEquals(expected, stats.exposureControllerLuma(), 0.0001f)
    }

    @Test
    fun exposureControllerFallsBackToLinearizedDisplayLumaForYuv() {
        val stats = testStats(shadowBin = 32, highlightCount = 0)
        val displayP50 = stats.lumaPercentile(0.50f)
        val controller = stats.exposureControllerLuma()
        assertTrue(controller in 0f..1f)
        assertTrue(controller <= displayP50)
    }

    @Test
    fun normalizedHistogramStaysBounded() {
        val normalized = testStats().normalizedHistogram()
        assertTrue(normalized.luma.all { it in 0f..1f })
        assertTrue(normalized.red.all { it in 0f..1f })
        assertTrue(normalized.green.all { it in 0f..1f })
        assertTrue(normalized.blue.all { it in 0f..1f })
    }

    private fun testStats(
        shadowBin: Int = 18,
        highlightBin: Int = 60,
        highlightCount: Int = 80,
        rawClip: Float = 0.0001f,
        displayClip: Float = 0.005f
    ): ExposureStatistics {
        val luma = IntArray(64)
        luma[shadowBin] = 700
        luma[32] = 400
        luma[highlightBin] = highlightCount
        val rgb = IntArray(64).also {
            it[20] = 500
            it[40] = 500
            it[63] = (displayClip * 1200).toInt()
        }
        return ExposureStatistics(
            source = "test",
            lumaHistogram64 = luma,
            redHistogram64 = rgb.copyOf(),
            greenHistogram64 = rgb.copyOf(),
            blueHistogram64 = rgb.copyOf(),
            sampleCount = luma.sum(),
            shadowFraction = 0.20f,
            highlightFraction = 0.08f,
            redClipFraction = displayClip,
            greenClipFraction = displayClip,
            blueClipFraction = displayClip,
            rawNearClipFraction = rawClip
        )
    }
}
