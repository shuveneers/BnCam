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
    @Test
    fun `same sensor static calibration rejects relatively over amplified exact frame transform`() {
        val exact = floatArrayOf(
            1.96875f, -1.03125f, 0.06250f,
            -0.26563f, 1.68750f, -0.42188f,
            -0.17969f, -0.72656f, 1.91406f
        )
        val staticCalibrated = floatArrayOf(
            1.45f, -0.40f, -0.05f,
            -0.20f, 1.30f, -0.10f,
            -0.05f, -0.25f, 1.30f
        )
        val wb = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)

        // This is the important regression: the matrix is numerically valid under the old
        // standalone gate, so only the same-sensor comparative trust check catches the problem.
        assertTrue(
            RawColorTransformEngine.validateExactFrameCamera2ColorTransform(exact).valid
        )

        val result = RawColorTransformEngine.evaluateExactFrameAgainstStaticCalibration(
            exactFrame = exact,
            wbRggb = wb,
            staticCandidates = listOf(staticCalibrated)
        )

        assertFalse(result.trusted, result.reason)
        assertTrue(result.amplificationRatio > 1.35f)
        assertTrue(result.reason.contains("relative_chroma_amplification"))
    }

    @Test
    fun `coherent exact frame transform remains preferred over close static calibration`() {
        val exact = floatArrayOf(
            1.70f, -0.50f, -0.20f,
            -0.30f, 1.40f, -0.10f,
            0.00f, -0.40f, 1.40f
        )
        val staticCalibrated = floatArrayOf(
            1.62f, -0.44f, -0.18f,
            -0.27f, 1.35f, -0.08f,
            0.01f, -0.36f, 1.35f
        )

        val result = RawColorTransformEngine.evaluateExactFrameAgainstStaticCalibration(
            exactFrame = exact,
            wbRggb = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f),
            staticCandidates = listOf(staticCalibrated)
        )

        assertTrue(result.trusted, result.reason)
        assertTrue(result.amplificationRatio < 1.10f)
    }

    @Test
    fun `exact frame remains usable when no standards complete static fallback exists`() {
        val exact = floatArrayOf(
            1.70f, -0.50f, -0.20f,
            -0.30f, 1.40f, -0.10f,
            0.00f, -0.40f, 1.40f
        )

        val result = RawColorTransformEngine.evaluateExactFrameAgainstStaticCalibration(
            exactFrame = exact,
            wbRggb = null,
            staticCandidates = emptyList()
        )

        assertTrue(result.trusted, result.reason)
        assertTrue(result.reason.contains("no_standards_complete_static_reference"))
    }

}
