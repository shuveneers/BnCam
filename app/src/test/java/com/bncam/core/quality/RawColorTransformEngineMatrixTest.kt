package com.bncam.core.quality

import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawColorTransformEngineMatrixTest {
    private fun assertMatrixNear(expected: FloatArray, actual: FloatArray, epsilon: Float = 1.0e-5f) {
        assertTrue(expected.size == actual.size)
        for (i in expected.indices) {
            assertTrue("matrix[$i] expected=${expected[i]} actual=${actual[i]}", abs(expected[i] - actual[i]) <= epsilon)
        }
    }

    @Test
    fun `identity and asymmetric calibrated matrices are structurally valid`() {
        assertTrue(RawColorTransformEngine.validateSensorToLinearSrgbMatrix(floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )).valid)
        assertTrue(RawColorTransformEngine.validateSensorToLinearSrgbMatrix(floatArrayOf(
            1.73f, -0.42f, -0.18f,
            -0.21f, 1.31f, -0.10f,
            0.03f, -0.37f, 1.29f
        )).valid)
    }

    @Test
    fun `singular or non finite matrices are rejected`() {
        assertFalse(RawColorTransformEngine.validateSensorToLinearSrgbMatrix(floatArrayOf(
            1f, 0f, 0f,
            1f, 0f, 0f,
            0f, 0f, 1f
        )).valid)
        assertFalse(RawColorTransformEngine.validateSensorToLinearSrgbMatrix(floatArrayOf(
            Float.NaN, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )).valid)
    }

    @Test
    fun `forward matrix fallback includes inverse device calibration`() {
        val forward = floatArrayOf(
            0.62f, 0.20f, 0.08f,
            0.28f, 0.67f, 0.05f,
            0.02f, 0.13f, 0.75f
        )
        val calibration = floatArrayOf(
            1.05f, 0.02f, 0.00f,
            0.01f, 0.97f, 0.01f,
            0.00f, 0.03f, 1.02f
        )
        val inverseCalibration = requireNotNull(RawColorTransformEngine.invert3x3(calibration))
        val expectedReferenceToXyz = requireNotNull(RawColorTransformEngine.multiply3x3(forward, inverseCalibration))
        val expected = requireNotNull(RawColorTransformEngine.multiply3x3(
            RawColorTransformEngine.xyzD50ToLinearSrgbMatrix(),
            expectedReferenceToXyz
        ))
        val resolved = RawColorTransformEngine.resolveActualSensorForwardMatrixToLinearSrgb(forward, calibration)
        val actual = requireNotNull(resolved.values)
        assertTrue(resolved.deviceCalibrationApplied)
        assertMatrixNear(expected, actual)
    }

    @Test
    fun `forward matrix without device calibration is not silently assumed valid`() {
        val forward = floatArrayOf(
            0.62f, 0.20f, 0.08f,
            0.28f, 0.67f, 0.05f,
            0.02f, 0.13f, 0.75f
        )
        val resolved = RawColorTransformEngine.resolveActualSensorForwardMatrixToLinearSrgb(forward, null)
        assertTrue(resolved.values == null)
        assertTrue(resolved.calibrationLabel == "calibration_transform_missing")
    }
}
