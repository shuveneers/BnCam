package com.bncam.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorMetadataTest {
    private fun metadata(rawSizeAvailable: Boolean = true): SensorMetadata {
        val sensor = SensorIdentity("4", "4", null, SensorAuthorityType.STANDALONE)
        val capture = CaptureIdentity(sensor, 44L, 9001L, 8)
        fun <T> v(value: T, source: String) = SensorMetadataValue.valid(value, source)
        fun <T> u(source: String, reason: String = "VALUE_UNAVAILABLE") = SensorMetadataValue.unavailable<T>(source, reason)
        val black = v(listOf(64f, 64f, 64f, 64f), "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL")
        val white = v(4095, "CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL")
        return SensorMetadata(
            route = SensorRouteKey("4", null),
            staticFingerprint = "fingerprint",
            metadataSource = "STANDALONE_CAPTURE_RESULT",
            sensorIdentity = sensor,
            captureIdentity = capture,
            cfa = v(0, "CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT"),
            staticBlackLevel = black,
            dynamicBlackLevel = black,
            effectiveBlackLevel = black,
            staticWhiteLevelField = white,
            dynamicWhiteLevelField = white,
            effectiveWhiteLevelField = white,
            sensitivityIsoField = v(200, "CaptureResult.SENSOR_SENSITIVITY"),
            exposureTimeNsField = v(20_000_000L, "CaptureResult.SENSOR_EXPOSURE_TIME"),
            frameDurationNsField = v(33_333_333L, "CaptureResult.SENSOR_FRAME_DURATION"),
            maxAnalogSensitivityIso = v(800, "CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY"),
            analogSensitivityIso = v(200, "DERIVED"),
            analogGainRelativeToMinimum = v(2.0, "DERIVED"),
            sensorDigitalGainRatio = v(1.0, "DERIVED"),
            postRawSensitivityBoostField = v(100, "CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST"),
            postRawDigitalGainRatio = v(1.0, "DERIVED"),
            combinedDigitalGainRatio = v(1.0, "DERIVED"),
            noiseProfileSoField = v(listOf(0.01, 0.001, 0.011, 0.001), "CaptureResult.SENSOR_NOISE_PROFILE"),
            colorCorrectionGainsField = v(listOf(2f, 1f, 1f, 1.5f), "CaptureResult.COLOR_CORRECTION_GAINS"),
            colorCorrectionTransformField = u("CaptureResult.COLOR_CORRECTION_TRANSFORM"),
            neutralColorPointField = v(listOf(0.5f, 1f, 0.67f), "CaptureResult.SENSOR_NEUTRAL_COLOR_POINT"),
            colorTransform1 = u("CameraCharacteristics.SENSOR_COLOR_TRANSFORM1"),
            colorTransform2 = u("CameraCharacteristics.SENSOR_COLOR_TRANSFORM2"),
            forwardMatrix1 = u("CameraCharacteristics.SENSOR_FORWARD_MATRIX1"),
            forwardMatrix2 = u("CameraCharacteristics.SENSOR_FORWARD_MATRIX2"),
            cameraCalibration1 = u("CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1"),
            cameraCalibration2 = u("CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2"),
            activeArrayField = v(RectSnapshot(0, 0, 4096, 3072), "CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE"),
            rawSizeField = if (rawSizeAvailable) v(SizeSnapshot(4096, 3072), "PipelineIdentity.ImageReader") else u("PipelineIdentity.ImageReader", "RAW_FRAME_SIZE_UNAVAILABLE"),
            orientationField = v(90, "CameraCharacteristics.SENSOR_ORIENTATION"),
            timestampField = v(9001L, "CaptureResult.SENSOR_TIMESTAMP"),
            frameNumberField = v(44L, "CaptureResult.frameNumber"),
            rollingShutterSkewNsField = u("CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW"),
            lensShadingMapMode = null,
            lensShadingRows = 0,
            lensShadingColumns = 0,
            lensShadingGainFactors = null,
            aeState = null,
            awbState = null,
            afState = null,
            sceneFlicker = null,
            lensState = null,
            oisMode = null,
            focusDistanceDiopters = null,
            focalLengthMm = null,
            aperture = null,
            rawSourceId = "4",
            captureResultSourceId = "4",
            characteristicsSourceId = "4",
            calibrationSourceId = "4"
        )
    }

    @Test
    fun coreRawMetadataIsValidOnlyWhenRequiredFieldsAreValid() {
        assertTrue(metadata().coreRawMetadataValid)
        assertFalse(metadata(rawSizeAvailable = false).coreRawMetadataValid)
        assertTrue(metadata(rawSizeAvailable = false).coreRawMetadataStatus.startsWith("RAW_SIZE_"))
    }

    @Test
    fun debugAuditAlwaysCarriesValueSourceAndValidity() {
        val lines = metadata().debugAuditLines()
        listOf("CFA", "Black Effective", "White Effective", "ISO", "Exposure Ns", "NoiseProfile S/O", "WB Gains", "ForwardMatrix1", "CameraCalibration1", "RAW Size", "Timestamp Ns", "Frame Number").forEach { label ->
            assertTrue(lines.any { it.startsWith("$label |") && "value=" in it && "source=" in it && "validity=" in it })
        }
    }

    @Test
    fun frameIdentityKeepsUniformAuthoritySources() {
        val frame = metadata().frameIdentityForRaw(9001L)
        assertTrue(frame.safeForRawProcessing)
        assertTrue(frame.sourcesMatchAuthority)
    }
}
