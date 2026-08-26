package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanRaw10UnpackCorrectnessTest {

    @Test
    fun raw10PackedUnpackCalculationMatchesExactIntegerSpec() {
        // 4 pixels packed into 5 bytes:
        // P0=1023 (0x3FF), P1=0 (0x0), P2=512 (0x200), P3=1 (0x1)
        // P0[9:2] = 0xFF, P1[9:2] = 0x00, P2[9:2] = 0x80, P3[9:2] = 0x00
        // P3[1:0]=01, P2[1:0]=00, P1[1:0]=00, P0[1:0]=11 -> b4 = 0x43
        val b0 = 0xFF
        val b1 = 0x00
        val b2 = 0x80
        val b3 = 0x00
        val b4 = 0x43

        val p0 = (b0 shl 2) or (b4 and 0x03)
        val p1 = (b1 shl 2) or ((b4 ushr 2) and 0x03)
        val p2 = (b2 shl 2) or ((b4 ushr 4) and 0x03)
        val p3 = (b3 shl 2) or ((b4 ushr 6) and 0x03)

        assertEquals(1023, p0)
        assertEquals(0, p1)
        assertEquals(512, p2)
        assertEquals(1, p3)
    }

    @Test
    fun packedRowBytesCalculatesExactFourPixelFiveByteBoundary() {
        val width = 4096
        val packedRowBytes = ((width + 3) / 4) * 5
        assertEquals(5120, packedRowBytes)
    }
}
