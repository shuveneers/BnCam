package com.bncam.core.buffer

import android.graphics.ImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureBufferBudgetTest {
    @Test
    fun frameByteEstimatesMatchStorageContracts() {
        val pixels = 4000L * 3000L
        val totalRam = 8L * 1024L * 1024L * 1024L
        val memoryClass = 512L * 1024L * 1024L

        val raw10 = CaptureBufferBudget.resolve(ImageFormat.RAW10, 4000, 3000, totalRam, memoryClass)
        val rawSensor = CaptureBufferBudget.resolve(ImageFormat.RAW_SENSOR, 4000, 3000, totalRam, memoryClass)
        val yuv = CaptureBufferBudget.resolve(ImageFormat.YUV_420_888, 4000, 3000, totalRam, memoryClass)

        assertEquals(pixels * 5L / 4L, raw10.estimatedFrameBytes)
        assertEquals(pixels * 2L, rawSensor.estimatedFrameBytes)
        assertEquals(pixels * 3L / 2L, yuv.estimatedFrameBytes)
    }

    @Test
    fun routeCapsMatchActualMergeConsumption() {
        val largeBudget = 32L * 1024L * 1024L * 1024L
        val largeClass = 1024L * 1024L * 1024L

        assertEquals(35, CaptureBufferBudget.resolve(ImageFormat.RAW10, 1920, 1080, largeBudget, largeClass).capacity)
        assertEquals(15, CaptureBufferBudget.resolve(ImageFormat.RAW_SENSOR, 1920, 1080, largeBudget, largeClass).capacity)
        assertEquals(35, CaptureBufferBudget.resolve(ImageFormat.YUV_420_888, 1280, 720, largeBudget, largeClass).capacity)
    }

    @Test
    fun highResolutionRawRemainsWarmButBounded() {
        val result = CaptureBufferBudget.resolve(
            ImageFormat.RAW_SENSOR,
            8160,
            6120,
            totalRamBytes = 6L * 1024L * 1024L * 1024L,
            memoryClassBytes = 256L * 1024L * 1024L
        )

        assertEquals(2, result.capacity)
        assertEquals(result.runtimeSafeCapacity, result.capacity)
        assertTrue(result.capacity <= result.routeMaximumFrames)
        assertTrue(result.capacityResolution.wasClamped)
    }
}
