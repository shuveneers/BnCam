package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Test

class VulkanCameraColorTransformTest {

    @Test
    fun identityColorMatrixPreservesInputRgbValues() {
        val identity = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )
        val r = 0.5f; val g = 0.2f; val b = 0.8f

        val outR = identity[0] * r + identity[1] * g + identity[2] * b
        val outG = identity[3] * r + identity[4] * g + identity[5] * b
        val outB = identity[6] * r + identity[7] * g + identity[8] * b

        assertEquals(0.5f, outR, 0.0001f)
        assertEquals(0.2f, outG, 0.0001f)
        assertEquals(0.8f, outB, 0.0001f)
    }

    @Test
    fun colorTransformPreservesHighlightHeadroomAboveUnity() {
        val matrix = floatArrayOf(
            1.2f, -0.1f, 0.0f,
            -0.1f, 1.1f, 0.0f,
            0.0f, -0.1f, 1.3f
        )
        val r = 1.5f; val g = 1.2f; val b = 1.0f // Highlight values

        val outR = matrix[0] * r + matrix[1] * g + matrix[2] * b // 1.68
        val outG = matrix[3] * r + matrix[4] * g + matrix[5] * b // 1.17
        val outB = matrix[6] * r + matrix[7] * g + matrix[8] * b // 1.18

        assertEquals(1.68f, outR, 0.001f)
        assertEquals(1.17f, outG, 0.001f)
        assertEquals(1.18f, outB, 0.001f)
    }
}
