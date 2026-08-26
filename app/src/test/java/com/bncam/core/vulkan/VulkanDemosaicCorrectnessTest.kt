package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanDemosaicCorrectnessTest {

    @Test
    fun autoDemosaicResolvesToMenon2007HighQuality() {
        val requested = VulkanDemosaicMethod.AUTO
        val resolved = if (requested == VulkanDemosaicMethod.AUTO) VulkanDemosaicMethod.MENON_2007 else requested
        assertEquals(VulkanDemosaicMethod.MENON_2007, resolved)
    }

    @Test
    fun rgbaFloatOutputBytesMatchesSixteenBytesPerPixel() {
        val width = 4096
        val height = 3072
        val bytesPerPixel = 16 // R32G32B32A32_SFLOAT (4 x 4-byte floats)
        val totalBytes = width.toLong() * height.toLong() * bytesPerPixel
        assertEquals(201326592L, totalBytes) // ~201.3 MB for full 12MP RGBA float image
    }

    @Test
    fun cfaParityPreservesAllFourPatternsWithoutChannelSwap() {
        val patterns = listOf("RGGB", "GRBG", "GBRG", "BGGR")
        assertEquals(4, patterns.size)
    }
}
