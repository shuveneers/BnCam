package com.bncam.core.quality

import java.security.MessageDigest

/**
 * Immutable calibration/profile authority derived only from the exact SensorMetadata for a frame.
 * The profile id is the sensor-authority id; logical lens aliases never become colour-profile keys.
 */
data class SensorCalibrationProvenance(
    val sensorAuthorityId: String,
    val cameraDeviceId: String,
    val physicalCameraId: String?,
    val calibrationProfileId: String,
    val characteristicsSourceId: String,
    val captureResultSourceId: String,
    val staticCalibrationFingerprint: String,
    val frameNumber: Long,
    val sensorTimestampNs: Long
) {
    val authorityMatches: Boolean
        get() = calibrationProfileId == sensorAuthorityId &&
            characteristicsSourceId == sensorAuthorityId &&
            captureResultSourceId == sensorAuthorityId &&
            if (physicalCameraId.isNullOrBlank()) {
                sensorAuthorityId == cameraDeviceId
            } else {
                sensorAuthorityId == physicalCameraId
            }

    val safeForCalibration: Boolean
        get() = sensorAuthorityId.isNotBlank() && cameraDeviceId.isNotBlank() &&
            calibrationProfileId.isNotBlank() && characteristicsSourceId.isNotBlank() &&
            captureResultSourceId.isNotBlank() && frameNumber >= 0L && sensorTimestampNs > 0L &&
            staticCalibrationFingerprint.matches(Regex("[0-9a-f]{64}")) && authorityMatches

    val rejectionReason: String
        get() = when {
            sensorAuthorityId.isBlank() -> "SENSOR_AUTHORITY_ID_UNAVAILABLE"
            cameraDeviceId.isBlank() -> "CAMERA_DEVICE_ID_UNAVAILABLE"
            calibrationProfileId.isBlank() -> "CALIBRATION_PROFILE_ID_UNAVAILABLE"
            characteristicsSourceId.isBlank() -> "CHARACTERISTICS_SOURCE_ID_UNAVAILABLE"
            captureResultSourceId.isBlank() -> "CAPTURE_RESULT_SOURCE_ID_UNAVAILABLE"
            frameNumber < 0L -> "FRAME_NUMBER_INVALID"
            sensorTimestampNs <= 0L -> "SENSOR_TIMESTAMP_INVALID"
            !staticCalibrationFingerprint.matches(Regex("[0-9a-f]{64}")) -> "STATIC_CALIBRATION_FINGERPRINT_INVALID"
            !authorityMatches -> "CALIBRATION_AUTHORITY_MISMATCH"
            else -> "none"
        }
}

/** Static DNG/Camera2 colour characterization bound to one exact sensor authority. */
data class CalibrationProfileBinding(
    val provenance: SensorCalibrationProvenance,
    val referenceIlluminant1: Int?,
    val referenceIlluminant2: Int?,
    val colorTransform1: List<Float>?,
    val colorTransform2: List<Float>?,
    val cameraCalibration1: List<Float>?,
    val cameraCalibration2: List<Float>?,
    val forwardMatrix1: List<Float>?,
    val forwardMatrix2: List<Float>?
) {
    val calibrationProfileId: String get() = provenance.calibrationProfileId
    val sensorAuthorityId: String get() = provenance.sensorAuthorityId
    val staticCalibrationFingerprint: String get() = provenance.staticCalibrationFingerprint

    val primaryCharacterizationComplete: Boolean
        get() = referenceIlluminant1 != null && validMatrix(colorTransform1) &&
            validMatrix(cameraCalibration1) && validMatrix(forwardMatrix1)

    val secondaryCharacterizationComplete: Boolean
        get() = referenceIlluminant2 != null && validMatrix(colorTransform2) &&
            validMatrix(cameraCalibration2) && validMatrix(forwardMatrix2)

    val safeForProfileBinding: Boolean
        get() = provenance.safeForCalibration && primaryCharacterizationComplete

    val rejectionReason: String
        get() = when {
            !provenance.safeForCalibration -> provenance.rejectionReason
            !primaryCharacterizationComplete -> "PRIMARY_STATIC_CHARACTERIZATION_INCOMPLETE"
            else -> "none"
        }

    fun matchesPersistedBinding(
        profileId: String,
        authorityId: String,
        staticFingerprint: String
    ): Boolean = safeForProfileBinding &&
        profileId == calibrationProfileId &&
        authorityId == sensorAuthorityId &&
        staticFingerprint == staticCalibrationFingerprint

    fun matchesDngCharacterization(
        illuminant1: Int,
        illuminant2: Int,
        dngColor1: FloatArray?,
        dngColor2: FloatArray?,
        dngCalibration1: FloatArray?,
        dngCalibration2: FloatArray?,
        dngForward1: FloatArray?,
        dngForward2: FloatArray?,
        tolerance: Float = 0.0005f
    ): Boolean {
        if (!safeForProfileBinding || illuminant1 != referenceIlluminant1) return false
        if (!matrixNear(colorTransform1, dngColor1, tolerance) ||
            !matrixNear(cameraCalibration1, dngCalibration1, tolerance) ||
            !matrixNear(forwardMatrix1, dngForward1, tolerance)
        ) return false

        val expectedSecondary = secondaryCharacterizationComplete
        val dngSecondary = illuminant2 != 0 || dngColor2 != null || dngCalibration2 != null || dngForward2 != null
        if (expectedSecondary != dngSecondary) return false
        if (expectedSecondary) {
            if (illuminant2 != referenceIlluminant2) return false
            if (!matrixNear(colorTransform2, dngColor2, tolerance) ||
                !matrixNear(cameraCalibration2, dngCalibration2, tolerance) ||
                !matrixNear(forwardMatrix2, dngForward2, tolerance)
            ) return false
        }
        return true
    }

    companion object {
        fun from(metadata: SensorMetadata): CalibrationProfileBinding {
            val profileId = metadata.sensorIdentity.sensorAuthorityId
            val fingerprint = StaticCalibrationFingerprint.from(metadata)
            val provenance = SensorCalibrationProvenance(
                sensorAuthorityId = metadata.sensorIdentity.sensorAuthorityId,
                cameraDeviceId = metadata.sensorIdentity.cameraDeviceId,
                physicalCameraId = metadata.sensorIdentity.physicalCameraId,
                calibrationProfileId = profileId,
                characteristicsSourceId = metadata.characteristicsSourceId,
                captureResultSourceId = metadata.captureResultSourceId,
                staticCalibrationFingerprint = fingerprint,
                frameNumber = metadata.captureIdentity.frameNumber,
                sensorTimestampNs = metadata.captureIdentity.sensorTimestampNs
            )
            return CalibrationProfileBinding(
                provenance = provenance,
                referenceIlluminant1 = metadata.referenceIlluminant1Field.takeValidValue(),
                referenceIlluminant2 = metadata.referenceIlluminant2Field.takeValidValue(),
                colorTransform1 = metadata.colorTransform1.takeValidValue()?.toList(),
                colorTransform2 = metadata.colorTransform2.takeValidValue()?.toList(),
                cameraCalibration1 = metadata.cameraCalibration1.takeValidValue()?.toList(),
                cameraCalibration2 = metadata.cameraCalibration2.takeValidValue()?.toList(),
                forwardMatrix1 = metadata.forwardMatrix1.takeValidValue()?.toList(),
                forwardMatrix2 = metadata.forwardMatrix2.takeValidValue()?.toList()
            )
        }

        private fun <T> SensorMetadataValue<T>.takeValidValue(): T? =
            value?.takeIf { isValid }

        private fun validMatrix(values: List<Float>?): Boolean =
            values?.size == 9 && values.all { it.isFinite() }

        private fun matrixNear(expected: List<Float>?, actual: FloatArray?, tolerance: Float): Boolean {
            if (expected == null && actual == null) return true
            if (expected?.size != 9 || actual?.size != 9) return false
            return expected.indices.all { kotlin.math.abs(expected[it] - actual[it]) <= tolerance }
        }
    }
}

/** Strong, deterministic calibration hash. No manufacturer/model/lens-role information is used. */
object StaticCalibrationFingerprint {
    fun from(metadata: SensorMetadata): String {
        val canonical = buildString {
            token("schema", "bncam-static-calibration-v1")
            token("authority", metadata.sensorIdentity.sensorAuthorityId)
            fieldToken("cfa", metadata.cfa)
            floatField("black", metadata.staticBlackLevel)
            fieldToken("white", metadata.staticWhiteLevelField)
            fieldToken("illuminant1", metadata.referenceIlluminant1Field)
            fieldToken("illuminant2", metadata.referenceIlluminant2Field)
            floatField("color1", metadata.colorTransform1)
            floatField("color2", metadata.colorTransform2)
            floatField("calibration1", metadata.cameraCalibration1)
            floatField("calibration2", metadata.cameraCalibration2)
            floatField("forward1", metadata.forwardMatrix1)
            floatField("forward2", metadata.forwardMatrix2)
            fieldToken("orientation", metadata.orientationField)
            metadata.activeArrayField.value?.takeIf { metadata.activeArrayField.isValid }?.let {
                token("activeArray", "${it.left},${it.top},${it.right},${it.bottom}")
            } ?: token("activeArray", null)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }


    private fun <T> StringBuilder.fieldToken(name: String, field: SensorMetadataValue<T>) {
        append(name).append(".validity=").append(field.validity.name).append(';')
        token(name, field.value?.takeIf { field.isValid })
    }

    private fun StringBuilder.floatField(name: String, field: SensorMetadataValue<List<Float>>) {
        append(name).append(".validity=").append(field.validity.name).append(';')
        floatList(name, field.value?.takeIf { field.isValid })
    }

    private fun StringBuilder.token(name: String, value: Any?) {
        append(name).append('=').append(value?.toString() ?: "-").append(';')
    }

    private fun StringBuilder.floatList(name: String, values: List<Float>?) {
        append(name).append('=')
        if (values == null) {
            append('-')
        } else {
            values.forEachIndexed { index, value ->
                if (index > 0) append(',')
                append(java.lang.Float.floatToIntBits(value).toUInt().toString(16).padStart(8, '0'))
            }
        }
        append(';')
    }
}
