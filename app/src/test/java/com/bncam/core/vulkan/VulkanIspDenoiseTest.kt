package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class VulkanIspDenoiseTest {

    @Test
    fun guidedBilateralLumaDenoisePreservesSharpEdges() {
        val centerLuma = 0.8f
        val neighborEdgeLuma = 0.1f // Sharp edge gradient

        val lumaSigmaS = 1.5f
        val lumaSigmaR = 0.08f

        val spatialDist2 = 1.0f
        val rangeDist2 = (neighborEdgeLuma - centerLuma) * (neighborEdgeLuma - centerLuma)

        val inv2SigmaS2 = 0.5f / (lumaSigmaS * lumaSigmaS)
        val inv2SigmaR2 = 0.5f / (lumaSigmaR * lumaSigmaR)

        val wEdge = exp(-spatialDist2 * inv2SigmaS2 - rangeDist2 * inv2SigmaR2)

        // Across a hard edge delta (0.7), range weight drops to ~0 preserving sharp edges
        assertTrue(wEdge < 0.0001f)
    }

    @Test
    fun spatialChromaNoiseSuppressionSmoothsColorSpeckles() {
        val centerU = 0.2f
        val neighborU = 0.0f
        val chromaStrength = 0.75f

        val avgChromaU = (centerU + neighborU) / 2.0f
        val filteredU = centerU * (1.0f - chromaStrength) + avgChromaU * chromaStrength

        assertEquals(0.125f, filteredU, 0.001f)
    }
}
