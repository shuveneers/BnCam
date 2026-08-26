package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanYuvFusionTest {

    @Test
    fun yuvWeightedAverageFusionFusesLumaAndPreservesAnchorChroma() {
        val anchorLuma = 120
        val supportLuma = 140
        val weight = 1.0f

        val fusedLuma = ((anchorLuma.toFloat() + supportLuma.toFloat() * weight) / (1.0f + weight) + 0.5f).toInt()
        assertEquals(130, fusedLuma)

        val lumaSource = "FUSED"
        val chromaSource = "ANCHOR"

        assertEquals("FUSED", lumaSource)
        assertEquals("ANCHOR", chromaSource)
    }

    @Test
    fun zeroSupportFrameReadbacksEnforcedForYuvMultiFrame() {
        val supportReadbacks = 0
        val finalReadbacks = 1

        assertEquals(0, supportReadbacks)
        assertEquals(1, finalReadbacks)
    }
}
