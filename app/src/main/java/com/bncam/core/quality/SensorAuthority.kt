package com.bncam.core.quality

enum class SensorAuthorityType {
    PHYSICAL_CHILD,
    STANDALONE
}

data class SensorIdentity(
    val sensorAuthorityId: String,
    val cameraDeviceId: String,
    val physicalCameraId: String?,
    val authorityType: SensorAuthorityType
) {
    init {
        require(sensorAuthorityId.isNotBlank()) { "sensorAuthorityId must not be blank" }
        require(cameraDeviceId.isNotBlank()) { "cameraDeviceId must not be blank" }
        when (authorityType) {
            SensorAuthorityType.PHYSICAL_CHILD -> {
                require(!physicalCameraId.isNullOrBlank()) {
                    "PHYSICAL_CHILD requires a physicalCameraId"
                }
                require(sensorAuthorityId == physicalCameraId) {
                    "PHYSICAL_CHILD authority must be the selected physical camera"
                }
            }
            SensorAuthorityType.STANDALONE -> {
                require(physicalCameraId == null) {
                    "STANDALONE must not carry a physicalCameraId"
                }
                require(sensorAuthorityId == cameraDeviceId) {
                    "STANDALONE authority must be the opened CameraDevice"
                }
            }
        }
    }

    val sourceId: String
        get() = sensorAuthorityId

    val physicalLabel: String
        get() = physicalCameraId ?: "STANDALONE"
}

sealed interface SensorAuthority {
    val identity: SensorIdentity
}

data class PhysicalChildSensorAuthority(
    override val identity: SensorIdentity
) : SensorAuthority {
    init {
        require(identity.authorityType == SensorAuthorityType.PHYSICAL_CHILD)
    }
}

data class StandaloneSensorAuthority(
    override val identity: SensorIdentity
) : SensorAuthority {
    init {
        require(identity.authorityType == SensorAuthorityType.STANDALONE)
    }
}

data class CaptureIdentity(
    val sensorIdentity: SensorIdentity,
    val frameNumber: Long,
    val sensorTimestampNs: Long,
    val captureSequenceId: Int? = null
) {
    init {
        require(frameNumber >= 0L) { "frameNumber must be non-negative" }
        require(sensorTimestampNs > 0L) { "sensorTimestampNs must be positive" }
    }
}

data class FrameIdentity(
    val captureIdentity: CaptureIdentity,
    val rawSourceId: String,
    val captureResultSourceId: String,
    val characteristicsSourceId: String,
    val calibrationSourceId: String,
    val rawSensorTimestampNs: Long,
    val logicalMetadataFallbackUsed: Boolean = false,
    val foreignSensorMetadataUsed: Boolean = false
) {
    val expectedSourceId: String
        get() = captureIdentity.sensorIdentity.sourceId

    val rawMetadataTimestampMatch: Boolean
        get() = rawSensorTimestampNs > 0L &&
            rawSensorTimestampNs == captureIdentity.sensorTimestampNs

    val sourcesMatchAuthority: Boolean
        get() = rawSourceId == expectedSourceId &&
            captureResultSourceId == expectedSourceId &&
            characteristicsSourceId == expectedSourceId &&
            calibrationSourceId == expectedSourceId

    val safeForRawProcessing: Boolean
        get() = rawMetadataTimestampMatch &&
            sourcesMatchAuthority &&
            !logicalMetadataFallbackUsed &&
            !foreignSensorMetadataUsed

    fun rejectionReason(): String = when {
        logicalMetadataFallbackUsed -> "LOGICAL_METADATA_FALLBACK_FORBIDDEN"
        foreignSensorMetadataUsed -> "FOREIGN_SENSOR_METADATA_FORBIDDEN"
        rawSensorTimestampNs <= 0L -> "RAW_SENSOR_TIMESTAMP_UNAVAILABLE"
        !rawMetadataTimestampMatch -> "RAW_METADATA_TIMESTAMP_MISMATCH"
        rawSourceId != expectedSourceId -> "RAW_SOURCE_MISMATCH"
        captureResultSourceId != expectedSourceId -> "CAPTURE_RESULT_SOURCE_MISMATCH"
        characteristicsSourceId != expectedSourceId -> "CHARACTERISTICS_SOURCE_MISMATCH"
        calibrationSourceId != expectedSourceId -> "CALIBRATION_SOURCE_MISMATCH"
        else -> "NONE"
    }

    fun debugText(selectedLens: String = expectedSourceId): String = buildString {
        val sensor = captureIdentity.sensorIdentity
        appendLine("=== SENSOR PROVENANCE ===")
        appendLine("Selected Lens: $selectedLens")
        appendLine("Sensor Authority ID: ${sensor.sensorAuthorityId}")
        appendLine("Camera Device ID: ${sensor.cameraDeviceId}")
        appendLine("Physical Camera ID: ${sensor.physicalLabel}")
        appendLine("Authority Type: ${sensor.authorityType.name}")
        appendLine()
        appendLine("RAW Source ID: $rawSourceId")
        appendLine("CaptureResult Source ID: $captureResultSourceId")
        appendLine("Characteristics Source ID: $characteristicsSourceId")
        appendLine("Calibration Source ID: $calibrationSourceId")
        appendLine()
        appendLine(
            "RAW/Metadata Frame Match: CORRELATED_BY_SENSOR_TIMESTAMP" +
                "(frameNumber=${captureIdentity.frameNumber})"
        )
        appendLine("RAW/Metadata Timestamp Match: ${rawMetadataTimestampMatch.toString().uppercase()}")
        appendLine("Frame Number: ${captureIdentity.frameNumber}")
        appendLine("Sensor Timestamp Ns: ${captureIdentity.sensorTimestampNs}")
        appendLine(
            "Capture Sequence ID: " +
                (captureIdentity.captureSequenceId?.toString() ?: "UNAVAILABLE")
        )
        appendLine()
        appendLine(
            "Fallback Used: " +
                (logicalMetadataFallbackUsed || foreignSensorMetadataUsed).toString().uppercase()
        )
        appendLine(
            "Logical Metadata Fallback: " +
                logicalMetadataFallbackUsed.toString().uppercase()
        )
        appendLine(
            "Foreign Sensor Metadata Used: " +
                foreignSensorMetadataUsed.toString().uppercase()
        )
        append("Raw Processing Safe: ${safeForRawProcessing.toString().uppercase()}")
    }
}

class SensorAuthorityUnavailableException(
    val authorityReason: String
) : IllegalStateException(authorityReason)
