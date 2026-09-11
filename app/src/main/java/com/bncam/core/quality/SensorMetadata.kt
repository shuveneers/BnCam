package com.bncam.core.quality

import java.util.Locale

/** Exact camera-device/physical-sensor route. */
data class SensorRouteKey(val logicalCameraId: String, val physicalCameraId: String?) {
    val effectiveCameraId: String get() = physicalCameraId?.takeIf { it.isNotBlank() } ?: logicalCameraId
    override fun toString(): String = physicalCameraId?.takeIf { it.isNotBlank() }
        ?.let { "$logicalCameraId/$it" } ?: logicalCameraId
}

data class RectSnapshot(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

data class SizeSnapshot(val width: Int, val height: Int) {
    init {
        require(width >= 0) { "width must be non-negative" }
        require(height >= 0) { "height must be non-negative" }
    }
}

enum class SensorMetadataValidity {
    VALID,
    UNAVAILABLE,
    INVALID
}

/**
 * One field of the uniform sensor metadata contract. Missing and invalid values remain explicit;
 * callers never need to infer whether a numeric zero, identity matrix, or format constant was a
 * real Camera2 value or a fallback.
 */
data class SensorMetadataValue<T>(
    val value: T?,
    val source: String,
    val validity: SensorMetadataValidity,
    val reason: String = "none"
) {
    init {
        require(source.isNotBlank()) { "Sensor metadata source must not be blank" }
        if (validity == SensorMetadataValidity.VALID) {
            require(value != null) { "VALID sensor metadata must carry a value" }
        }
    }

    val isValid: Boolean get() = validity == SensorMetadataValidity.VALID && value != null

    companion object {
        fun <T> valid(value: T, source: String): SensorMetadataValue<T> =
            SensorMetadataValue(value = value, source = source, validity = SensorMetadataValidity.VALID)

        fun <T> unavailable(source: String, reason: String): SensorMetadataValue<T> =
            SensorMetadataValue(value = null, source = source, validity = SensorMetadataValidity.UNAVAILABLE, reason = reason)

        fun <T> invalid(value: T?, source: String, reason: String): SensorMetadataValue<T> =
            SensorMetadataValue(value = value, source = source, validity = SensorMetadataValidity.INVALID, reason = reason)
    }
}

/**
 * Uniform immutable sensor truth for one exact capture. This object is sensor-role agnostic:
 * main, tele, ultra-wide and front use the same fields and the same validity rules.
 */
data class SensorMetadata(
    val route: SensorRouteKey,
    val staticFingerprint: String?,
    val metadataSource: String,
    val sensorIdentity: SensorIdentity,
    val captureIdentity: CaptureIdentity,

    val cfa: SensorMetadataValue<Int>,
    val staticBlackLevel: SensorMetadataValue<List<Float>>,
    val dynamicBlackLevel: SensorMetadataValue<List<Float>>,
    val effectiveBlackLevel: SensorMetadataValue<List<Float>>,
    val staticWhiteLevelField: SensorMetadataValue<Int>,
    val dynamicWhiteLevelField: SensorMetadataValue<Int>,
    val effectiveWhiteLevelField: SensorMetadataValue<Int>,

    val sensitivityIsoField: SensorMetadataValue<Int>,
    val exposureTimeNsField: SensorMetadataValue<Long>,
    val frameDurationNsField: SensorMetadataValue<Long>,
    val maxAnalogSensitivityIso: SensorMetadataValue<Int>,
    val analogSensitivityIso: SensorMetadataValue<Int>,
    val analogGainRelativeToMinimum: SensorMetadataValue<Double>,
    val sensorDigitalGainRatio: SensorMetadataValue<Double>,
    val postRawSensitivityBoostField: SensorMetadataValue<Int>,
    val postRawDigitalGainRatio: SensorMetadataValue<Double>,
    val combinedDigitalGainRatio: SensorMetadataValue<Double>,

    val noiseProfileSoField: SensorMetadataValue<List<Double>>,
    val colorCorrectionGainsField: SensorMetadataValue<List<Float>>,
    val colorCorrectionTransformField: SensorMetadataValue<List<Float>>,
    val neutralColorPointField: SensorMetadataValue<List<Float>>,
    val colorTransform1: SensorMetadataValue<List<Float>>,
    val colorTransform2: SensorMetadataValue<List<Float>>,
    val forwardMatrix1: SensorMetadataValue<List<Float>>,
    val forwardMatrix2: SensorMetadataValue<List<Float>>,
    val cameraCalibration1: SensorMetadataValue<List<Float>>,
    val cameraCalibration2: SensorMetadataValue<List<Float>>,

    val activeArrayField: SensorMetadataValue<RectSnapshot>,
    val rawSizeField: SensorMetadataValue<SizeSnapshot>,
    val orientationField: SensorMetadataValue<Int>,
    val timestampField: SensorMetadataValue<Long>,
    val frameNumberField: SensorMetadataValue<Long>,

    val rollingShutterSkewNsField: SensorMetadataValue<Long>,
    val lensShadingMapMode: Int?,
    val lensShadingRows: Int,
    val lensShadingColumns: Int,
    val lensShadingGainFactors: List<Float>?,
    val aeState: Int?,
    val awbState: Int?,
    val afState: Int?,
    val sceneFlicker: Int?,
    val lensState: Int?,
    val oisMode: Int?,
    val focusDistanceDiopters: Float?,
    val focalLengthMm: Float?,
    val aperture: Float?,

    val rawSourceId: String,
    val captureResultSourceId: String,
    val characteristicsSourceId: String,
    val calibrationSourceId: String,
    val logicalMetadataFallbackUsed: Boolean = false,
    val foreignSensorMetadataUsed: Boolean = false
) {
    // Compatibility accessors for existing ring/runners. The uniform fields above remain the only
    // stored values; these accessors never add defaults.
    val sensorTimestampNs: Long? get() = timestampField.value
    val frameNumber: Long get() = frameNumberField.value ?: captureIdentity.frameNumber
    val sensitivityIso: Int? get() = sensitivityIsoField.value
    val exposureTimeNs: Long? get() = exposureTimeNsField.value
    val frameDurationNs: Long? get() = frameDurationNsField.value
    val postRawSensitivityBoost: Int? get() = postRawSensitivityBoostField.value
    val rollingShutterSkewNs: Long? get() = rollingShutterSkewNsField.value
    val dynamicBlackLevels: List<Float>? get() = dynamicBlackLevel.value
    val dynamicWhiteLevel: Int? get() = dynamicWhiteLevelField.value
    val noiseProfileSo: List<Double>? get() = noiseProfileSoField.value
    val colorCorrectionGains: List<Float>? get() = colorCorrectionGainsField.value
    val colorCorrectionTransform: List<Float>? get() = colorCorrectionTransformField.value
    val neutralColorPoint: List<Float>? get() = neutralColorPointField.value

    val hasPhysicalNoiseModel: Boolean
        get() = noiseProfileSoField.isValid &&
            !noiseProfileSo.isNullOrEmpty() && noiseProfileSo!!.size % 2 == 0

    val hasDynamicLensShadingMap: Boolean
        get() = lensShadingRows > 0 && lensShadingColumns > 0 &&
            lensShadingGainFactors?.size == lensShadingRows * lensShadingColumns * 4

    val coreRawMetadataValid: Boolean
        get() = cfa.isValid && effectiveBlackLevel.isValid && effectiveWhiteLevelField.isValid &&
            sensitivityIsoField.isValid && exposureTimeNsField.isValid && rawSizeField.isValid &&
            orientationField.isValid && timestampField.isValid && frameNumberField.isValid

    val coreRawMetadataStatus: String
        get() = firstCoreRawMetadataFailure() ?: "VALID"

    fun frameIdentityForRaw(rawSensorTimestampNs: Long): FrameIdentity = FrameIdentity(
        captureIdentity = captureIdentity,
        rawSourceId = rawSourceId,
        captureResultSourceId = captureResultSourceId,
        characteristicsSourceId = characteristicsSourceId,
        calibrationSourceId = calibrationSourceId,
        rawSensorTimestampNs = rawSensorTimestampNs,
        logicalMetadataFallbackUsed = logicalMetadataFallbackUsed,
        foreignSensorMetadataUsed = foreignSensorMetadataUsed
    )

    fun debugAuditLines(): List<String> = listOf(
        audit("CFA", cfa, { it.toString() }),
        audit("Black Static", staticBlackLevel, ::formatFloatList),
        audit("Black Dynamic", dynamicBlackLevel, ::formatFloatList),
        audit("Black Effective", effectiveBlackLevel, ::formatFloatList),
        audit("White Static", staticWhiteLevelField, { it.toString() }),
        audit("White Dynamic", dynamicWhiteLevelField, { it.toString() }),
        audit("White Effective", effectiveWhiteLevelField, { it.toString() }),
        audit("ISO", sensitivityIsoField, { it.toString() }),
        audit("Exposure Ns", exposureTimeNsField, { it.toString() }),
        audit("Analog Sensitivity ISO", analogSensitivityIso, { it.toString() }),
        audit("Analog Gain Relative To Minimum", analogGainRelativeToMinimum, { formatDouble(it) }),
        audit("Sensor Digital Gain Ratio", sensorDigitalGainRatio, { formatDouble(it) }),
        audit("Post-RAW Sensitivity Boost", postRawSensitivityBoostField, { it.toString() }),
        audit("Post-RAW Digital Gain Ratio", postRawDigitalGainRatio, { formatDouble(it) }),
        audit("Combined Digital Gain Ratio", combinedDigitalGainRatio, { formatDouble(it) }),
        audit("NoiseProfile S/O", noiseProfileSoField, ::formatDoubleList),
        audit("WB Gains", colorCorrectionGainsField, ::formatFloatList),
        audit("Neutral Color Point", neutralColorPointField, ::formatFloatList),
        audit("Color Correction Transform", colorCorrectionTransformField, ::formatFloatList),
        audit("ColorTransform1", colorTransform1, ::formatFloatList),
        audit("ColorTransform2", colorTransform2, ::formatFloatList),
        audit("ForwardMatrix1", forwardMatrix1, ::formatFloatList),
        audit("ForwardMatrix2", forwardMatrix2, ::formatFloatList),
        audit("CameraCalibration1", cameraCalibration1, ::formatFloatList),
        audit("CameraCalibration2", cameraCalibration2, ::formatFloatList),
        audit("Active Array", activeArrayField, { "${it.left},${it.top},${it.right},${it.bottom}" }),
        audit("RAW Size", rawSizeField, { "${it.width}x${it.height}" }),
        audit("Orientation", orientationField, { it.toString() }),
        audit("Timestamp Ns", timestampField, { it.toString() }),
        audit("Frame Number", frameNumberField, { it.toString() }),
        "Core RAW Metadata | value=$coreRawMetadataStatus; source=UNIFORM_SENSOR_METADATA; validity=" +
            if (coreRawMetadataValid) "VALID; reason=none" else "INVALID; reason=$coreRawMetadataStatus"
    )

    private fun firstCoreRawMetadataFailure(): String? = when {
        !cfa.isValid -> "CFA_${cfa.validity.name}:${cfa.reason}"
        !effectiveBlackLevel.isValid -> "BLACK_LEVEL_${effectiveBlackLevel.validity.name}:${effectiveBlackLevel.reason}"
        !effectiveWhiteLevelField.isValid -> "WHITE_LEVEL_${effectiveWhiteLevelField.validity.name}:${effectiveWhiteLevelField.reason}"
        !sensitivityIsoField.isValid -> "ISO_${sensitivityIsoField.validity.name}:${sensitivityIsoField.reason}"
        !exposureTimeNsField.isValid -> "EXPOSURE_${exposureTimeNsField.validity.name}:${exposureTimeNsField.reason}"
        !rawSizeField.isValid -> "RAW_SIZE_${rawSizeField.validity.name}:${rawSizeField.reason}"
        !orientationField.isValid -> "ORIENTATION_${orientationField.validity.name}:${orientationField.reason}"
        !timestampField.isValid -> "TIMESTAMP_${timestampField.validity.name}:${timestampField.reason}"
        !frameNumberField.isValid -> "FRAME_NUMBER_${frameNumberField.validity.name}:${frameNumberField.reason}"
        else -> null
    }

    private fun <T> audit(
        label: String,
        field: SensorMetadataValue<T>,
        formatter: (T) -> String
    ): String {
        val valueText = field.value?.let(formatter) ?: "UNAVAILABLE"
        return "$label | value=$valueText; source=${field.source}; validity=${field.validity.name}; reason=${field.reason}"
    }

    private fun formatDouble(value: Double): String = String.format(Locale.US, "%.6f", value)
    private fun formatFloatList(values: List<Float>): String =
        values.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.6f", it) }
    private fun formatDoubleList(values: List<Double>): String =
        values.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.9f", it) }
}

/** Source-compatible name retained while the codebase migrates to SensorMetadata terminology. */
typealias FrameSensorMetadataSnapshot = SensorMetadata
