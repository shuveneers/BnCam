package com.bncam.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSensorMetadataSnapshotTest {
    private fun snapshot(
        noise: List<Double>? = null,
        rows: Int = 0,
        columns: Int = 0,
        shading: List<Float>? = null
    ): FrameSensorMetadataSnapshot {
        val sensor = SensorIdentity(
            sensorAuthorityId = "physical",
            cameraDeviceId = "logical",
            physicalCameraId = "physical",
            authorityType = SensorAuthorityType.PHYSICAL_CHILD
        )
        val capture = CaptureIdentity(sensor, frameNumber = 1L, sensorTimestampNs = 10L, captureSequenceId = 3)
        fun <T> valid(value: T, source: String) = SensorMetadataValue.valid(value, source)
        fun <T> unavailable(source: String) = SensorMetadataValue.unavailable<T>(source, "VALUE_UNAVAILABLE")

        return SensorMetadata(
            route = SensorRouteKey("logical", "physical"),
            staticFingerprint = "abc123",
            metadataSource = "PHYSICAL_CAPTURE_RESULT",
            sensorIdentity = sensor,
            captureIdentity = capture,
            cfa = valid(0, "CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT"),
            staticBlackLevel = valid(listOf(64f, 64f, 64f, 64f), "CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN"),
            dynamicBlackLevel = valid(listOf(64f, 64f, 64f, 64f), "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL"),
            effectiveBlackLevel = valid(listOf(64f, 64f, 64f, 64f), "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL"),
            staticWhiteLevelField = valid(4095, "CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL"),
            dynamicWhiteLevelField = valid(4095, "CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL"),
            effectiveWhiteLevelField = valid(4095, "CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL"),
            sensitivityIsoField = valid(100, "CaptureResult.SENSOR_SENSITIVITY"),
            exposureTimeNsField = valid(10_000_000L, "CaptureResult.SENSOR_EXPOSURE_TIME"),
            frameDurationNsField = valid(33_333_333L, "CaptureResult.SENSOR_FRAME_DURATION"),
            maxAnalogSensitivityIso = valid(800, "CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY"),
            analogSensitivityIso = valid(100, "DERIVED"),
            analogGainRelativeToMinimum = valid(1.0, "DERIVED"),
            sensorDigitalGainRatio = valid(1.0, "DERIVED"),
            postRawSensitivityBoostField = valid(100, "CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST"),
            postRawDigitalGainRatio = valid(1.0, "DERIVED"),
            combinedDigitalGainRatio = valid(1.0, "DERIVED"),
            noiseProfileSoField = noise?.let { valid(it, "CaptureResult.SENSOR_NOISE_PROFILE") }
                ?: unavailable("CaptureResult.SENSOR_NOISE_PROFILE"),
            colorCorrectionGainsField = valid(listOf(2f, 1f, 1f, 1.5f), "CaptureResult.COLOR_CORRECTION_GAINS"),
            colorCorrectionTransformField = unavailable("CaptureResult.COLOR_CORRECTION_TRANSFORM"),
            neutralColorPointField = valid(listOf(0.5f, 1f, 0.67f), "CaptureResult.SENSOR_NEUTRAL_COLOR_POINT"),
            colorTransform1 = unavailable("CameraCharacteristics.SENSOR_COLOR_TRANSFORM1"),
            colorTransform2 = unavailable("CameraCharacteristics.SENSOR_COLOR_TRANSFORM2"),
            forwardMatrix1 = unavailable("CameraCharacteristics.SENSOR_FORWARD_MATRIX1"),
            forwardMatrix2 = unavailable("CameraCharacteristics.SENSOR_FORWARD_MATRIX2"),
            cameraCalibration1 = unavailable("CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1"),
            cameraCalibration2 = unavailable("CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2"),
            activeArrayField = valid(RectSnapshot(0, 0, 4000, 3000), "CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE"),
            rawSizeField = valid(SizeSnapshot(4000, 3000), "PipelineIdentity.ImageReader"),
            orientationField = valid(90, "CameraCharacteristics.SENSOR_ORIENTATION"),
            timestampField = valid(10L, "CaptureResult.SENSOR_TIMESTAMP"),
            frameNumberField = valid(1L, "CaptureResult.frameNumber"),
            rollingShutterSkewNsField = valid(1_000_000L, "CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW"),
            lensShadingMapMode = 1,
            lensShadingRows = rows,
            lensShadingColumns = columns,
            lensShadingGainFactors = shading,
            aeState = 2,
            awbState = 2,
            afState = 2,
            sceneFlicker = 0,
            lensState = 0,
            oisMode = 1,
            focusDistanceDiopters = 0f,
            focalLengthMm = 6f,
            aperture = 1.8f,
            rawSourceId = "physical",
            captureResultSourceId = "physical",
            characteristicsSourceId = "physical",
            calibrationSourceId = "physical"
        )
    }

    @Test
    fun physicalNoiseRequiresCompleteSoPairs() {
        assertTrue(snapshot(noise = listOf(0.01, 0.001, 0.011, 0.001)).hasPhysicalNoiseModel)
        assertFalse(snapshot(noise = listOf(0.01, 0.001, 0.011)).hasPhysicalNoiseModel)
        assertFalse(snapshot(noise = null).hasPhysicalNoiseModel)
    }

    @Test
    fun lensShadingRequiresExactFourChannelMapSize() {
        assertTrue(snapshot(rows = 2, columns = 2, shading = List(16) { 1f }).hasDynamicLensShadingMap)
        assertFalse(snapshot(rows = 2, columns = 2, shading = List(15) { 1f }).hasDynamicLensShadingMap)
    }
}
