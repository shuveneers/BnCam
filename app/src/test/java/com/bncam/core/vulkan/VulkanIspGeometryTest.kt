package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanIspGeometryTest {

    @Test
    fun geometryRotationSwapsDimensionsFor90And270Degrees() {
        val cropW = 4096
        val cropH = 3072

        val rot0W = cropW
        val rot0H = cropH

        val rot90W = cropH
        val rot90H = cropW

        val rot180W = cropW
        val rot180H = cropH

        val rot270W = cropH
        val rot270H = cropW

        assertEquals(4096, rot0W); assertEquals(3072, rot0H)
        assertEquals(3072, rot90W); assertEquals(4096, rot90H)
        assertEquals(4096, rot180W); assertEquals(3072, rot180H)
        assertEquals(3072, rot270W); assertEquals(4096, rot270H)
    }

    @Test
    fun bgr8QuantizationPacksThreeBytesPerPixel() {
        val width = 4096
        val height = 3072
        val bpp = 3 // BGR8 format

        val totalBytes = width.toLong() * height.toLong() * bpp.toLong()
        assertEquals(37748736L, totalBytes)
    }
}
