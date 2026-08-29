package com.bncam.core.isp.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawHotPixelMapMapperTest {
    @Test
    fun `full pixel array maps through source crop into master coordinates`() {
        val result = RawHotPixelMapMapper.mapCoordinates(
            points = listOf(
                RawSensorDefectPoint(12, 22),
                RawSensorDefectPoint(19, 29),
                RawSensorDefectPoint(1, 1)
            ),
            geometry = RawHotPixelMapGeometry(
                pixelArrayWidth = 32,
                pixelArrayHeight = 32,
                preCorrectionLeft = 10,
                preCorrectionTop = 20,
                preCorrectionWidth = 10,
                preCorrectionHeight = 10,
                sourceWidth = 32,
                sourceHeight = 32,
                sourceCropLeft = 10,
                sourceCropTop = 20,
                outputWidth = 10,
                outputHeight = 10
            )
        )
        assertTrue(result.isUsable)
        assertEquals("MAPPED_FULL_PIXEL_ARRAY_TO_CROPPED_RAW16", result.mappingStatus)
        assertEquals(2, result.mappedPointCount)
        assertEquals(1, result.rejectedOutsideOutputCount)
        assertArrayEquals(intArrayOf(2, 2, 9, 9), result.packedXy)
    }

    @Test
    fun `precorrection-cropped RAW subtracts sensor active origin before output crop`() {
        val result = RawHotPixelMapMapper.mapCoordinates(
            points = listOf(RawSensorDefectPoint(105, 207)),
            geometry = RawHotPixelMapGeometry(
                pixelArrayWidth = 500,
                pixelArrayHeight = 400,
                preCorrectionLeft = 100,
                preCorrectionTop = 200,
                preCorrectionWidth = 200,
                preCorrectionHeight = 100,
                sourceWidth = 200,
                sourceHeight = 100,
                sourceCropLeft = 0,
                sourceCropTop = 0,
                outputWidth = 200,
                outputHeight = 100
            )
        )
        assertArrayEquals(intArrayOf(5, 7), result.packedXy)
        assertEquals("MAPPED_PRECORRECTION_ARRAY_TO_CROPPED_RAW16", result.mappingStatus)
    }

    @Test
    fun `sensor points outside a precorrection source are rejected`() {
        val result = RawHotPixelMapMapper.mapCoordinates(
            points = listOf(
                RawSensorDefectPoint(99, 200),
                RawSensorDefectPoint(100, 200)
            ),
            geometry = RawHotPixelMapGeometry(
                pixelArrayWidth = 500,
                pixelArrayHeight = 400,
                preCorrectionLeft = 100,
                preCorrectionTop = 200,
                preCorrectionWidth = 200,
                preCorrectionHeight = 100,
                sourceWidth = 200,
                sourceHeight = 100,
                sourceCropLeft = 0,
                sourceCropTop = 0,
                outputWidth = 200,
                outputHeight = 100
            )
        )
        assertEquals(1, result.rejectedOutsideSourceCount)
        assertEquals(1, result.mappedPointCount)
        assertArrayEquals(intArrayOf(0, 0), result.packedXy)
    }

    @Test
    fun `custom sized RAW geometry is never guessed`() {
        val result = RawHotPixelMapMapper.mapCoordinates(
            points = listOf(RawSensorDefectPoint(100, 100)),
            geometry = RawHotPixelMapGeometry(
                pixelArrayWidth = 8000,
                pixelArrayHeight = 6000,
                preCorrectionLeft = 16,
                preCorrectionTop = 12,
                preCorrectionWidth = 7968,
                preCorrectionHeight = 5976,
                sourceWidth = 4000,
                sourceHeight = 3000,
                sourceCropLeft = 0,
                sourceCropTop = 0,
                outputWidth = 4000,
                outputHeight = 3000
            )
        )
        assertFalse(result.isUsable)
        assertEquals("UNRESOLVED_CUSTOM_RAW_BUFFER_GEOMETRY", result.mappingStatus)
        assertEquals(0, result.mappedPointCount)
    }

    @Test
    fun `duplicates collapse to one deterministic master coordinate`() {
        val result = RawHotPixelMapMapper.mapCoordinates(
            points = listOf(
                RawSensorDefectPoint(3, 4),
                RawSensorDefectPoint(3, 4)
            ),
            geometry = RawHotPixelMapGeometry(
                pixelArrayWidth = 10,
                pixelArrayHeight = 10,
                preCorrectionLeft = 0,
                preCorrectionTop = 0,
                preCorrectionWidth = 10,
                preCorrectionHeight = 10,
                sourceWidth = 10,
                sourceHeight = 10,
                sourceCropLeft = 0,
                sourceCropTop = 0,
                outputWidth = 10,
                outputHeight = 10
            )
        )
        assertEquals(1, result.mappedPointCount)
        assertArrayEquals(intArrayOf(3, 4), result.packedXy)
    }
}
