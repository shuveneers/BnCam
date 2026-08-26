package com.bncam.ui.screens.capture

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class RawPreviewCalibrationTransformTest {
    @Test
    fun bggrCanonicalBlackLevelsReturnToSensorTileOrder() {
        assertArrayEquals(
            floatArrayOf(40f, 30f, 20f, 10f),
            RawPreviewCalibrationTransform.canonicalBlackLevelsToMosaic(
                canonical = floatArrayOf(10f, 20f, 30f, 40f),
                cfaPattern = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR
            ),
            0f
        )
    }

    @Test
    fun rggbCanonicalBlackLevelsAlreadyMatchTileOrder() {
        assertArrayEquals(
            floatArrayOf(10f, 20f, 30f, 40f),
            RawPreviewCalibrationTransform.canonicalBlackLevelsToMosaic(
                canonical = floatArrayOf(10f, 20f, 30f, 40f),
                cfaPattern = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
            ),
            0f
        )
    }
}
