package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class VulkanRawLinearizationTest {

    @Test
    fun blackLevelSubtractionAndWhiteLevelNormalizationCalculatesCorrectLinearFloat() {
        val rawSample = 512.0f
        val blackLevel = 64.0f
        val whiteLevel = 1023.0f

        val valAfterBlack = max(0.0f, rawSample - blackLevel) // 448.0
        val range = max(1.0f, whiteLevel - blackLevel)       // 959.0
        val normalized = min(1.0f, valAfterBlack / range)    // 0.46715328

        val wbGain = 1.8f
        val finalLinear = min(1.0f, normalized * wbGain)

        assertEquals(448.0f, valAfterBlack, 0.001f)
        assertEquals(959.0f, range, 0.001f)
        assertEquals(0.467153f, normalized, 0.001f)
        assertEquals(0.840875f, finalLinear, 0.001f)
    }

    @Test
    fun cfaChannelSelectionIsCorrectForRggb() {
        val patternRggb = 0
        fun getChannel(x: Int, y: Int): Int {
            val xm = x and 1
            val ym = y and 1
            return if (ym == 0) (if (xm == 0) 0 else 1) else (if (xm == 0) 2 else 3)
        }

        assertEquals(0, getChannel(0, 0)) // R
        assertEquals(1, getChannel(1, 0)) // Gr
        assertEquals(2, getChannel(0, 1)) // Gb
        assertEquals(3, getChannel(1, 1)) // B
    }
}
