package com.bncam.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewMotionMeterTest {
    private fun texturedNv21(width: Int, height: Int, shiftX: Int = 0): ByteArray {
        val bytes = ByteArray(width * height * 3 / 2) { 128.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = (x - shiftX).coerceIn(0, width - 1)
                val checker = ((sx / 5) + (y / 7)) and 1
                val ramp = (sx * 3 + y * 2) and 63
                bytes[y * width + x] = (if (checker == 0) 48 + ramp else 176 - ramp).toByte()
            }
        }
        return bytes
    }

    @Test
    fun `first frame warms temporal baseline`() {
        val meter = RawPreviewMotionMeter()
        val first = meter.observeNv21(
            texturedNv21(160, 120), 160, 120, 4080, 3060, 1_000_000_000L
        )
        assertFalse(first.ready)
    }

    @Test
    fun `translated textured frame produces strong motion evidence`() {
        val meter = RawPreviewMotionMeter()
        meter.observeNv21(
            texturedNv21(160, 120), 160, 120, 4080, 3060, 1_000_000_000L
        )
        val moved = meter.observeNv21(
            texturedNv21(160, 120, shiftX = 1),
            160, 120, 4080, 3060, 1_033_333_333L
        )
        assertTrue(moved.cameraConfidence > 0f || moved.sceneConfidence > 0f)
        assertTrue(moved.cameraMotionPxPerSecond >= 0f)
        assertTrue(moved.ready)
        assertNotNull(moved.cameraExposureCeilingNs ?: moved.sceneExposureCeilingNs)
    }

    @Test
    fun `camera shake budget is more tolerant than legacy universal two pixel budget`() {
        val strict = RawPreviewMotionMeter(
            allowedBlurPixels = 2.0f,
            cameraBlurBudgetPixels = 2.0f,
            sceneBlurBudgetPixels = 2.0f
        )
        val photonFirst = RawPreviewMotionMeter()
        val first = texturedNv21(160, 120)
        val moved = texturedNv21(160, 120, shiftX = 1)

        strict.observeNv21(first, 160, 120, 4080, 3060, 1_000_000_000L)
        photonFirst.observeNv21(first, 160, 120, 4080, 3060, 1_000_000_000L)
        val strictResult = strict.observeNv21(moved, 160, 120, 4080, 3060, 1_033_333_333L)
        val photonResult = photonFirst.observeNv21(moved, 160, 120, 4080, 3060, 1_033_333_333L)

        val strictCeiling = strictResult.cameraExposureCeilingNs
        val photonCeiling = photonResult.cameraExposureCeilingNs
        assertNotNull(strictCeiling)
        assertNotNull(photonCeiling)
        assertTrue(photonCeiling!! >= strictCeiling!!)
    }

    @Test
    fun `flow may be diagnostic without becoming exposure authority`() {
        val meter = RawPreviewMotionMeter(exposureAuthorityMinConfidence = 0.99f)
        meter.observeNv21(
            texturedNv21(160, 120), 160, 120, 4080, 3060, 1_000_000_000L
        )
        val result = meter.observeNv21(
            texturedNv21(160, 120, shiftX = 1),
            160, 120, 4080, 3060, 1_033_333_333L
        )

        assertTrue(result.cameraConfidence > 0f || result.sceneConfidence > 0f)
        assertFalse(result.ready)
        assertTrue(result.cameraExposureCeilingNs == null && result.sceneExposureCeilingNs == null)
    }

    @Test
    fun `reset requires a new temporal pair`() {
        val meter = RawPreviewMotionMeter()
        meter.observeNv21(
            texturedNv21(160, 120), 160, 120, 4080, 3060, 1_000_000_000L
        )
        meter.observeNv21(
            texturedNv21(160, 120, shiftX = 1),
            160, 120, 4080, 3060, 1_033_333_333L
        )
        meter.reset()
        val afterReset = meter.observeNv21(
            texturedNv21(160, 120), 160, 120, 4080, 3060, 2_000_000_000L
        )
        assertFalse(afterReset.ready)
    }

    @Test
    fun `global brightness change is not treated as strong motion`() {
        val meter = RawPreviewMotionMeter()
        val first = texturedNv21(160, 120)
        val brighter = first.copyOf().also { bytes ->
            for (i in 0 until 160 * 120) {
                val value = bytes[i].toInt() and 0xFF
                bytes[i] = (value * 1.15f).toInt().coerceIn(0, 255).toByte()
            }
        }
        meter.observeNv21(first, 160, 120, 4080, 3060, 1_000_000_000L)
        val result = meter.observeNv21(
            brighter, 160, 120, 4080, 3060, 1_033_333_333L
        )
        assertTrue(result.cameraMotionPxPerSecond < 250f)
    }
}
