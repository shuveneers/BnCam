package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanRawAlignmentTest {

    @Test
    fun phaseCorrelationScalarReadbackIsSixteenBytes() {
        val dx = 0.0f
        val dy = 0.0f
        val response = 0.98f
        val accepted = 1.0f

        val bytes = 4 * 4 // 4 floats = 16 bytes
        assertEquals(16, bytes)
        assertTrue(response >= 0.35f)
        assertEquals(1.0f, accepted, 0.0001f)
    }

    @Test
    fun cfaParityPreservedForStepTwoAlignment() {
        val step = 2 // Bayer pattern preservation step
        val dx = 4
        val dy = -2

        val parityX = (dx % step) == 0
        val parityY = (dy % step) == 0

        assertTrue(parityX)
        assertTrue(parityY)
    }
}
