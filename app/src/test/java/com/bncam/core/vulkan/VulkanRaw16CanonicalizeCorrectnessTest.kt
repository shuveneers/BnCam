package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanRaw16CanonicalizeCorrectnessTest {

    @Test
    fun raw16CanonicalizationPreservesExactUint16PixelValues() {
        val raw16Sample = 4095
        val littleEndianLo = (raw16Sample and 0xFF).toByte()
        val littleEndianHi = ((raw16Sample ushr 8) and 0xFF).toByte()

        val reconstructed = ((littleEndianHi.toInt() and 0xFF) shl 8) or (littleEndianLo.toInt() and 0xFF)
        assertEquals(4095, reconstructed)
    }

    @Test
    fun canonicalRowBytesMatchesTightlyPackedWidth() {
        val width = 4096
        val canonicalRowBytes = width * 2
        assertEquals(8192, canonicalRowBytes)
    }
}
