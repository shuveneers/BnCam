package com.bncam.core.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmRawMotionSamplerTest {
    private fun rawSensor(width: Int, height: Int, shiftX: Int): ByteBuffer {
        val rowStride = width * 2
        val buffer = ByteBuffer.allocate(rowStride * height).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = (x - shiftX).coerceIn(0, width - 1)
                val checker = ((sx / 8) + (y / 10)) and 1
                val value = if (checker == 0) 900 + ((sx * 7 + y * 3) and 255) else 3000 - ((sx * 5 + y * 2) and 255)
                buffer.putShort(y * rowStride + x * 2, value.toShort())
            }
        }
        return buffer
    }

    @Test
    fun `raw sensor sampler stays compact and feeds temporal motion`() {
        val width = 640
        val height = 480
        val first = WarmRawMotionSampler.sampleRawSensor(
            rawSensor(width, height, 0), width * 2, 2, width, height, 1_000_000_000L
        )
        val second = WarmRawMotionSampler.sampleRawSensor(
            rawSensor(width, height, 2), width * 2, 2, width, height, 1_033_333_333L
        )
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first!!.width <= 160)
        assertTrue(first.height <= 160)
        assertTrue(first.bytes.size < width * height / 8)

        val meter = RawPreviewMotionMeter()
        val warm = meter.observeLuma8(first.bytes, first.width, first.height, width, height, first.timestampNs)
        assertFalse(warm.ready)
        val moved = meter.observeLuma8(second!!.bytes, second.width, second.height, width, height, second.timestampNs)
        assertTrue(moved.cameraConfidence > 0f || moved.sceneConfidence > 0f)
    }

    @Test
    fun `raw10 msb packing yields valid compact guide`() {
        val width = 320
        val height = 240
        val groups = (width + 3) / 4
        val rowStride = groups * 5
        val buffer = ByteBuffer.allocate(rowStride * height)
        for (y in 0 until height) {
            for (g in 0 until groups) {
                val base = y * rowStride + g * 5
                for (lane in 0..3) {
                    buffer.put(base + lane, (40 + ((g * 3 + y * 5 + lane * 17) and 180)).toByte())
                }
                buffer.put(base + 4, 0)
            }
        }
        val sampled = WarmRawMotionSampler.sampleRaw10(buffer, rowStride, width, height, 1_000_000_000L)
        assertNotNull(sampled)
        assertTrue(sampled!!.bytes.any { (it.toInt() and 0xFF) > 0 })
    }
}
