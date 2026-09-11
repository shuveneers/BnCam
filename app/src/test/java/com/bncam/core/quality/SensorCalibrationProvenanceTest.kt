package com.bncam.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCalibrationProvenanceTest {
    private fun metadata(
        authority: String = "2",
        cameraDevice: String = authority,
        physical: String? = null,
        forwardOffset: Float = 0f
    ): SensorMetadata {
        val sensor = SensorIdentity(
            sensorAuthorityId = authority,
            cameraDeviceId = cameraDevice,
            physicalCameraId = physical,
            authorityType = if (physical == null) SensorAuthorityType.STANDALONE else SensorAuthorityType.PHYSICAL_CHILD
        )
        val capture = CaptureIdentity(sensor, frameNumber = 7L, sensorTimestampNs = 123456L, captureSequenceId = 3)
        fun <T> v(value: T, source: String) = SensorMetadataValue.valid(value, source)
        fun <T> u(source: String) = SensorMetadataValue.unavailable<T>(source, "UNAVAILABLE")
        val identity = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val forward = identity.toMutableList().also { it[0] += forwardOffset }
        return SensorMetadata(
            route = SensorRouteKey(cameraDevice, physical),
            staticFingerprint = "legacy-not-authoritative",
            metadataSource = if (physical == null) "STANDALONE_CAPTURE_RESULT" else "PHYSICAL_CAPTURE_RESULT",
            sensorIdentity = sensor,
            captureIdentity = capture,
            cfa = v(3, "CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT"),
            staticBlackLevel = v(listOf(64f, 64f, 64f, 64f), "CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN"),
            dynamicBlackLevel = v(listOf(64f, 64f, 64f, 64f), "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL"),
            effectiveBlackLevel = v(listOf(64f, 64f, 64f, 64f), "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL"),
            staticWhiteLevelField = v(1023, "CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL"),
            dynamicWhiteLevelField = v(1023, "CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL"),
            effectiveWhiteLevelField = v(1023, "CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL"),
            sensitivityIsoField = v(400, "CaptureResult.SENSOR_SENSITIVITY"),
            exposureTimeNsField = v(30_000_000L, "CaptureResult.SENSOR_EXPOSURE_TIME"),
            frameDurationNsField = v(33_333_333L, "CaptureResult.SENSOR_FRAME_DURATION"),
            maxAnalogSensitivityIso = v(1600, "CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY"),
            analogSensitivityIso = v(400, "DERIVED"),
            analogGainRelativeToMinimum = v(4.0, "DERIVED"),
            sensorDigitalGainRatio = v(1.0, "DERIVED"),
            postRawSensitivityBoostField = v(100, "CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST"),
            postRawDigitalGainRatio = v(1.0, "DERIVED"),
            combinedDigitalGainRatio = v(1.0, "DERIVED"),
            noiseProfileSoField = v(listOf(0.01, 0.001, 0.01, 0.001, 0.01, 0.001, 0.01, 0.001), "CaptureResult.SENSOR_NOISE_PROFILE"),
            colorCorrectionGainsField = v(listOf(2f, 1f, 1f, 1.5f), "CaptureResult.COLOR_CORRECTION_GAINS"),
            colorCorrectionTransformField = v(identity, "CaptureResult.COLOR_CORRECTION_TRANSFORM"),
            neutralColorPointField = v(listOf(0.5f, 1f, 0.66f), "CaptureResult.SENSOR_NEUTRAL_COLOR_POINT"),
            referenceIlluminant1Field = v(21, "CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1"),
            referenceIlluminant2Field = v(17, "CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2"),
            colorTransform1 = v(identity, "CameraCharacteristics.SENSOR_COLOR_TRANSFORM1"),
            colorTransform2 = v(identity, "CameraCharacteristics.SENSOR_COLOR_TRANSFORM2"),
            forwardMatrix1 = v(forward, "CameraCharacteristics.SENSOR_FORWARD_MATRIX1"),
            forwardMatrix2 = v(identity, "CameraCharacteristics.SENSOR_FORWARD_MATRIX2"),
            cameraCalibration1 = v(identity, "CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1"),
            cameraCalibration2 = v(identity, "CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2"),
            activeArrayField = v(RectSnapshot(0, 0, 4096, 3072), "CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE"),
            rawSizeField = v(SizeSnapshot(4096, 3072), "PipelineIdentity.ImageReader"),
            orientationField = v(90, "CameraCharacteristics.SENSOR_ORIENTATION"),
            timestampField = v(123456L, "CaptureResult.SENSOR_TIMESTAMP"),
            frameNumberField = v(7L, "CaptureResult.frameNumber"),
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
            rawSourceId = authority,
            captureResultSourceId = authority,
            characteristicsSourceId = authority,
            calibrationSourceId = authority
        )
    }

    @Test
    fun profileIdIsExactSensorAuthorityForStandaloneAndPhysicalRoutes() {
        val standalone = CalibrationProfileBinding.from(metadata(authority = "2"))
        assertTrue(standalone.provenance.safeForCalibration)
        assertTrue(standalone.calibrationProfileId == "2")

        val physical = CalibrationProfileBinding.from(
            metadata(authority = "5", cameraDevice = "0", physical = "5")
        )
        assertTrue(physical.provenance.safeForCalibration)
        assertTrue(physical.calibrationProfileId == "5")
    }

    @Test
    fun staticCalibrationFingerprintIsStableAndChangesWithCalibration() {
        val a = CalibrationProfileBinding.from(metadata())
        val b = CalibrationProfileBinding.from(metadata())
        val changed = CalibrationProfileBinding.from(metadata(forwardOffset = 0.01f))
        assertTrue(a.staticCalibrationFingerprint == b.staticCalibrationFingerprint)
        assertNotEquals(a.staticCalibrationFingerprint, changed.staticCalibrationFingerprint)
        assertTrue(a.staticCalibrationFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun persistedBindingRejectsWrongAuthorityOrFingerprint() {
        val binding = CalibrationProfileBinding.from(metadata())
        assertTrue(binding.matchesPersistedBinding("2", "2", binding.staticCalibrationFingerprint))
        assertFalse(binding.matchesPersistedBinding("4", "4", binding.staticCalibrationFingerprint))
        assertFalse(binding.matchesPersistedBinding("2", "2", "0".repeat(64)))
    }

    @Test
    fun dngCharacterizationMustMatchStaticSensorCalibration() {
        val binding = CalibrationProfileBinding.from(metadata())
        fun f(values: List<Float>?) = values?.toFloatArray()
        assertTrue(
            binding.matchesDngCharacterization(
                illuminant1 = 21,
                illuminant2 = 17,
                dngColor1 = f(binding.colorTransform1),
                dngColor2 = f(binding.colorTransform2),
                dngCalibration1 = f(binding.cameraCalibration1),
                dngCalibration2 = f(binding.cameraCalibration2),
                dngForward1 = f(binding.forwardMatrix1),
                dngForward2 = f(binding.forwardMatrix2)
            )
        )
        val wrong = f(binding.forwardMatrix1)!!
        wrong[0] += 0.02f
        assertFalse(
            binding.matchesDngCharacterization(
                illuminant1 = 21,
                illuminant2 = 17,
                dngColor1 = f(binding.colorTransform1),
                dngColor2 = f(binding.colorTransform2),
                dngCalibration1 = f(binding.cameraCalibration1),
                dngCalibration2 = f(binding.cameraCalibration2),
                dngForward1 = wrong,
                dngForward2 = f(binding.forwardMatrix2)
            )
        )
    }

    @Test
    fun invalidStaticMatrixCannotBecomeProfileAuthority() {
        val valid = metadata()
        val invalid = valid.copy(
            forwardMatrix1 = SensorMetadataValue.invalid(
                valid.forwardMatrix1.value,
                valid.forwardMatrix1.source,
                "MATRIX_INVALID"
            )
        )
        val binding = CalibrationProfileBinding.from(invalid)
        assertFalse(binding.safeForProfileBinding)
        assertTrue(binding.rejectionReason == "PRIMARY_STATIC_CHARACTERIZATION_INCOMPLETE")
    }

}
