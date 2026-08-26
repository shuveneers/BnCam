package com.bncam.core.isp.raw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RawSampleReadersTest {
    @Test
    fun raw10RoundTripSupportsPaddingAndOddWidths() {
        val width = 7
        val height = 3
        val rowStride = RawSampleReaders.packedRaw10RowBytes(width) + 3
        val samples = IntArray(width * height) { index -> (index * 73 + 17) % 1024 }

        val packed = RawSampleReaders.packRaw10Rows(samples, width, height, rowStride)
        val unpacked = RawSampleReaders.unpackRaw10Rows(packed, width, height, rowStride)

        assertArrayEquals(samples, unpacked)
    }


    @Test
    fun raw10UsesAndroidMipiLowBitLaneOrder() {
        val packed = byteArrayOf(0xFF.toByte(), 0x00, 0x80.toByte(), 0x00, 0x53)
        val unpacked = RawSampleReaders.unpackRaw10Rows(packed, width = 4, height = 1, rowStrideBytes = 5)

        assertArrayEquals(intArrayOf(1023, 0, 513, 1), unpacked)

        val repacked = RawSampleReaders.packRaw10Rows(unpacked, width = 4, height = 1)
        assertArrayEquals(packed, repacked)
    }

    @Test
    fun raw10RejectsRowsShorterThanPackedPayload() {
        try {
            RawSampleReaders.unpackRaw10Rows(ByteArray(8), width = 5, height = 1, rowStrideBytes = 5)
            fail("Expected rowStride validation failure")
        } catch (error: IllegalArgumentException) {
            assertEquals(true, error.message?.contains("rowStrideBytes") == true)
        }
    }

    @Test
    fun rawSensorReaderHonorsRowStridePixelStrideAndLittleEndian() {
        val width = 4
        val height = 2
        val pixelStride = 4
        val rowStride = 20
        val expected = intArrayOf(0x0011, 0x1022, 0x2033, 0x3044, 0x4055, 0x5066, 0x6077, 0x7088)
        val bytes = ByteArray(rowStride * height)
        expected.forEachIndexed { index, value ->
            val y = index / width
            val x = index % width
            val offset = y * rowStride + x * pixelStride
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        val actual = RawSampleReaders.readRawSensor16LittleEndian(bytes, width, height, rowStride, pixelStride)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun cfaOriginTracksOddCropOffsets() {
        assertEquals(0 to 0, RawSampleReaders.cfaOriginAfterCrop(0, 0, 0, 0))
        assertEquals(1 to 0, RawSampleReaders.cfaOriginAfterCrop(0, 0, 1, 0))
        assertEquals(1 to 1, RawSampleReaders.cfaOriginAfterCrop(1, 0, 2, 3))
    }

    @Test
    fun blackWhiteNormalizationUsesOneExplicitSampleDomain() {
        assertEquals(0f, RawSampleReaders.normalizeSample(64, 64, 1023), 0f)
        assertEquals(1f, RawSampleReaders.normalizeSample(1023, 64, 1023), 0f)
        assertEquals(0.5f, RawSampleReaders.normalizeSample(544, 64, 1024), 0.0001f)
        assertEquals(0f, RawSampleReaders.normalizeSample(0, 64, 1023), 0f)
    }
}
