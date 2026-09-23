package com.bncam.core.quality

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RawColorTransformEngineExactFrameTest {
    @Test
    fun `ordinary exact frame Camera2 transform remains accepted`() {
        val matrix = floatArrayOf(
            1.70f, -0.50f, -0.20f,
            -0.30f, 1.40f, -0.10f,
            0.00f, -0.40f, 1.40f
        )

        val result = RawColorTransformEngine.validateExactFrameCamera2ColorTransform(matrix)

        assertTrue(result.valid, result.reason)
    }

    @Test
    fun `neutral axis outlier is rejected before direct Camera2 matrix reaches RAW ISP`() {
        val matrix = floatArrayOf(
            2.00f, 0.00f, 0.00f,
            0.00f, 1.00f, 0.00f,
            0.00f, 0.00f, 0.10f
        )

        val result = RawColorTransformEngine.validateExactFrameCamera2ColorTransform(matrix)

        assertFalse(result.valid)
        assertTrue(result.reason.contains("neutral_axis_outlier"))
    }

    @Test
    fun `extreme direct coefficient is rejected`() {
        val matrix = floatArrayOf(
            9.00f, -8.00f, 0.00f,
            0.00f, 1.00f, 0.00f,
            0.00f, 0.00f, 1.00f
        )

        val result = RawColorTransformEngine.validateExactFrameCamera2ColorTransform(matrix)

        assertFalse(result.valid)
        assertTrue(result.reason.contains("coefficient_outlier"))
    }

    @Test
    fun `extreme row cancellation is rejected even when coefficients stay below hard limit`() {
        val matrix = floatArrayOf(
            7.00f, -6.00f, 0.00f,
            0.00f, 1.00f, 0.00f,
            0.00f, 0.00f, 1.00f
        )

        val result = RawColorTransformEngine.validateExactFrameCamera2ColorTransform(matrix)

        assertFalse(result.valid)
        assertTrue(result.reason.contains("row_cancellation"))
    }
}
