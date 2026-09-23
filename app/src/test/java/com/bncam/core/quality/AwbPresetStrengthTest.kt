package com.bncam.core.quality

import android.hardware.camera2.CameraCharacteristics
import com.bncam.data.settings.LensAwbCalibrationModes
import com.bncam.data.settings.LensAwbCalibrationSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class AwbPresetStrengthTest {
    private val calibration = ResolvedAwbCalibration(
        valid = true,
        source = "test",
        points = listOf(
            com.bncam.data.settings.AwbCalibrationPoint(0.35f, 0.70f),
            com.bncam.data.settings.AwbCalibrationPoint(0.70f, 0.35f)
        ),
        grGbRatio = null,
        fingerprint = "test"
    )

    @Test
    fun `zero preset strength returns exact camera2 baseline`() {
        val base = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)
        val result = AwbCalibrationEngine.applyToCamera2Prior(
            camera2Gains = base,
            calibration = calibration,
            settings = LensAwbCalibrationSettings(
                mode = LensAwbCalibrationModes.BNCAM_PRESET,
                presetId = 6,
                presetStrength = 0.0f
            ),
            cfaPattern = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        ) ?: error("Expected applied AWB")

        assertEquals(base[0], result.gains[0], 1.0e-6f)
        assertEquals(base[1], result.gains[1], 1.0e-6f)
        assertEquals(base[2], result.gains[2], 1.0e-6f)
        assertEquals(base[3], result.gains[3], 1.0e-6f)
        assertEquals(0.0f, result.calibrationAuthority, 1.0e-6f)
    }

    @Test
    fun `full warm led strength applies catalog endpoint`() {
        val base = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)
        val result = AwbCalibrationEngine.applyToCamera2Prior(
            camera2Gains = base,
            calibration = calibration,
            settings = LensAwbCalibrationSettings(
                mode = LensAwbCalibrationModes.BNCAM_PRESET,
                presetId = 6,
                presetStrength = 1.0f
            ),
            cfaPattern = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        ) ?: error("Expected applied AWB")

        assertEquals(2.0f * 1.060f, result.gains[0], 1.0e-6f)
        assertEquals(1.5f * 0.945f, result.gains[3], 1.0e-6f)
        assertEquals(1.0f, result.calibrationAuthority, 1.0e-6f)
    }

    @Test
    fun `half strength applies half of preset multiplier deviation`() {
        val base = floatArrayOf(2.0f, 1.0f, 1.0f, 1.5f)
        val result = AwbCalibrationEngine.applyToCamera2Prior(
            camera2Gains = base,
            calibration = calibration,
            settings = LensAwbCalibrationSettings(
                mode = LensAwbCalibrationModes.BNCAM_PRESET,
                presetId = 14,
                presetStrength = 0.5f
            ),
            cfaPattern = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        ) ?: error("Expected applied AWB")

        // Snow / Blue Hour full endpoint is 0.920 / 1.100, so half strength is 0.960 / 1.050.
        assertEquals(2.0f * 0.960f, result.gains[0], 1.0e-6f)
        assertEquals(1.5f * 1.050f, result.gains[3], 1.0e-6f)
    }
    @Test
    fun `default auto preserves exact camera2 gains before physical scene refinement`() {
        val base = floatArrayOf(1.0f, 1.291016f, 1.291016f, 2.823242f)
        val result = AwbCalibrationEngine.applyToCamera2Prior(
            camera2Gains = base,
            calibration = calibration,
            settings = LensAwbCalibrationSettings(
                mode = LensAwbCalibrationModes.AUTO
            ),
            cfaPattern = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG
        ) ?: error("Expected applied AWB")

        assertEquals(base[0], result.gains[0], 1.0e-6f)
        assertEquals(base[1], result.gains[1], 1.0e-6f)
        assertEquals(base[2], result.gains[2], 1.0e-6f)
        assertEquals(base[3], result.gains[3], 1.0e-6f)
        assertEquals(0.0f, result.calibrationAuthority, 1.0e-6f)
    }

}
