package com.bncam.core.quality

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.LensShadingMap
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
import android.util.Log
import android.util.Rational
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class IntRangeSnapshot(val lower: Int, val upper: Int)
data class LongRangeSnapshot(val lower: Long, val upper: Long)

/** CameraCharacteristics-only truth. Safe to build before the first capture. */
data class PhysicalSensorProfile(
    val route: SensorRouteKey,
    val characteristicsCameraId: String,
    val characteristicsSource: String,
    val rawCapabilityAdvertised: Boolean,
    val hardwareLevel: Int?,
    val cfaArrangement: Int?,
    val sensorOrientationDegrees: Int?,
    val pixelArraySize: SizeSnapshot?,
    val rawSensorOutputSizes: List<SizeSnapshot>,
    val raw10OutputSizes: List<SizeSnapshot>,
    val activeArray: RectSnapshot?,
    val preCorrectionActiveArray: RectSnapshot?,
    val opticalBlackRegions: List<RectSnapshot>,
    val sensitivityRange: IntRangeSnapshot?,
    val exposureTimeRangeNs: LongRangeSnapshot?,
    val maxFrameDurationNs: Long?,
    val maxAnalogSensitivityIso: Int?,
    val staticBlackLevels: List<Int>?,
    val staticWhiteLevel: Int?,
    val rawLensShadingAlreadyApplied: Boolean?,
    val availableLensShadingMapModes: List<Int>,
    val availableShadingModes: List<Int>,
    val availableOisModes: List<Int>,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val minimumFocusDistanceDiopters: Float?,
    val hyperfocalDistanceDiopters: Float?,
    val referenceIlluminant1: Int?,
    val referenceIlluminant2: Int?,
    val colorTransform1: List<Float>?,
    val colorTransform2: List<Float>?,
    val cameraCalibration1: List<Float>?,
    val cameraCalibration2: List<Float>?,
    val forwardMatrix1: List<Float>?,
    val forwardMatrix2: List<Float>?,
    val availableAntibandingModes: List<Int>,
    val staticFingerprint: String
)

data class SensorProfileRegistrySnapshot(
    val prewarmAttempted: Boolean,
    val prewarmCompleted: Boolean,
    val routeCount: Int,
    val physicalRouteCount: Int,
    val failures: List<String>
)

internal data class PhysicalResultRouteDecision(
    val physicalCameraId: String?,
    val authority: String,
    val deterministic: Boolean
)

internal fun resolvePhysicalResultRoute(
    activePhysicalId: String?,
    physicalResultIds: Set<String>
): PhysicalResultRouteDecision {
    val active = activePhysicalId?.trim()?.takeIf { it.isNotEmpty() }
    if (active != null && physicalResultIds.contains(active)) {
        return PhysicalResultRouteDecision(
            physicalCameraId = active,
            authority = "ACTIVE_PHYSICAL_ID_EXACT_RESULT",
            deterministic = true
        )
    }

    return PhysicalResultRouteDecision(
        physicalCameraId = null,
        authority = when {
            active == null && physicalResultIds.isNotEmpty() -> "PHYSICAL_AUTHORITY_UNSPECIFIED"
            else -> "PHYSICAL_METADATA_UNAVAILABLE"
        },
        deterministic = false
    )
}

data class SensorCalibrationInput(
    val physicalCameraId: String?,
    val characteristics: CameraCharacteristics,
    val captureResult: CaptureResult?,
    val sensorMetadata: SensorMetadata,
    val authority: String,
    val deterministic: Boolean,
    val staticFingerprint: String?,
    val sensorIdentity: SensorIdentity
)

/**
 * Single Camera2 static/dynamic sensor-metadata registry. Static data is cached per exact route;
 * dynamic metadata is always resolved from the exact requested physical result when available.
 */
class PhysicalSensorProfileRegistry(private val cameraManager: CameraManager) {
    private val profiles = ConcurrentHashMap<SensorRouteKey, PhysicalSensorProfile>()
    private val characteristicsByCameraId = ConcurrentHashMap<String, CameraCharacteristics>()
    private val recentFrameRouteLock = Any()
    private val recentFrameRoutesByTimestamp = LinkedHashMap<Long, SensorRouteKey>(RECENT_FRAME_ROUTE_CAPACITY)
    private val recentFrameResultsByTimestamp = LinkedHashMap<Long, CaptureResult>(RECENT_FRAME_ROUTE_CAPACITY)
    private val recentFrameIdentitiesByTimestamp = LinkedHashMap<Long, CaptureIdentity>(RECENT_FRAME_ROUTE_CAPACITY)
    private val recentFrameMetadataByTimestamp = LinkedHashMap<Long, SensorMetadata>(RECENT_FRAME_ROUTE_CAPACITY)

    init {
        activeRegistry = this
    }
    private val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val prewarmAttempted = AtomicBoolean(false)
    private val prewarmCompleted = AtomicBoolean(false)

    fun prewarm() {
        if (!prewarmAttempted.compareAndSet(false, true)) return
        try {
            cameraManager.cameraIdList.forEach { logicalId ->
                val logicalChars = runCatching { cameraManager.getCameraCharacteristics(logicalId) }
                    .onFailure { recordFailure("logical=$logicalId characteristics=${it.javaClass.simpleName}") }
                    .getOrNull() ?: return@forEach
                installRoute(logicalId, null, logicalChars, logicalId, "LOGICAL_CAMERA_CHARACTERISTICS")
                logicalChars.physicalCameraIds.sorted().forEach { physicalId ->
                    val physicalChars = runCatching { cameraManager.getCameraCharacteristics(physicalId) }
                        .onFailure { recordFailure("logical=$logicalId physical=$physicalId characteristics=${it.javaClass.simpleName}") }
                        .getOrNull()
                    if (physicalChars != null) {
                        installRoute(logicalId, physicalId, physicalChars, physicalId, "PHYSICAL_CAMERA_CHARACTERISTICS")
                    }
                }
            }
        } finally {
            prewarmCompleted.set(true)
            Log.i(TAG, "prewarm completed routes=${profiles.size} physical=${profiles.keys.count { !it.physicalCameraId.isNullOrBlank() }} failures=${failures.size}")
        }
    }

    fun profileFor(logicalCameraId: String, physicalCameraId: String?): PhysicalSensorProfile? {
        val key = SensorRouteKey(logicalCameraId, physicalCameraId?.takeIf { it.isNotBlank() })
        profiles[key]?.let { return it }
        val targetId = key.effectiveCameraId
        val targetChars = runCatching { cameraManager.getCameraCharacteristics(targetId) }
            .onFailure { recordFailure("route=$key onDemand=${it.javaClass.simpleName}") }
            .getOrNull() ?: return null
        return installRoute(
            logicalCameraId,
            key.physicalCameraId,
            targetChars,
            targetId,
            if (key.physicalCameraId == null) "ON_DEMAND_LOGICAL_CHARACTERISTICS" else "ON_DEMAND_PHYSICAL_CHARACTERISTICS"
        )
    }

    fun snapshotForFrame(
        logicalCameraId: String,
        physicalCameraId: String?,
        result: TotalCaptureResult,
        rawFrameSize: SizeSnapshot? = null
    ): FrameSensorMetadataSnapshot {
        val route = SensorRouteKey(logicalCameraId, physicalCameraId?.takeIf { it.isNotBlank() })
        if (route.physicalCameraId == null && isLogicalMultiCameraCameraId(route.logicalCameraId)) {
            throw SensorAuthorityUnavailableException("LOGICAL_PARENT_WITHOUT_PHYSICAL_AUTHORITY")
        }

        val staticProfile = profileFor(route.logicalCameraId, route.physicalCameraId)
            ?: throw SensorAuthorityUnavailableException("CHARACTERISTICS_UNAVAILABLE")

        val metadata: CaptureResult = if (route.physicalCameraId != null) {
            exactPhysicalResult(result, route.physicalCameraId)
                ?: throw SensorAuthorityUnavailableException("PHYSICAL_METADATA_UNAVAILABLE")
        } else {
            result
        }

        val sensorIdentity = if (route.physicalCameraId != null) {
            SensorIdentity(
                sensorAuthorityId = route.physicalCameraId,
                cameraDeviceId = route.logicalCameraId,
                physicalCameraId = route.physicalCameraId,
                authorityType = SensorAuthorityType.PHYSICAL_CHILD
            )
        } else {
            SensorIdentity(
                sensorAuthorityId = route.logicalCameraId,
                cameraDeviceId = route.logicalCameraId,
                physicalCameraId = null,
                authorityType = SensorAuthorityType.STANDALONE
            )
        }

        if (staticProfile.characteristicsCameraId != sensorIdentity.sourceId) {
            throw SensorAuthorityUnavailableException("FOREIGN_SENSOR_CHARACTERISTICS")
        }

        val captureResultSourceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            metadata.cameraId
        } else {
            sensorIdentity.sourceId
        }
        if (captureResultSourceId != sensorIdentity.sourceId) {
            throw SensorAuthorityUnavailableException("FOREIGN_SENSOR_CAPTURE_RESULT")
        }
        if (metadata.frameNumber != result.frameNumber) {
            throw SensorAuthorityUnavailableException("PHYSICAL_RESULT_FRAME_NUMBER_MISMATCH")
        }
        if (metadata.sequenceId != result.sequenceId) {
            throw SensorAuthorityUnavailableException("PHYSICAL_RESULT_SEQUENCE_ID_MISMATCH")
        }

        val sensorTimestampNs = metadata.get(CaptureResult.SENSOR_TIMESTAMP)
            ?.takeIf { it > 0L }
            ?: throw SensorAuthorityUnavailableException("SENSOR_TIMESTAMP_UNAVAILABLE")
        val captureIdentity = CaptureIdentity(
            sensorIdentity = sensorIdentity,
            frameNumber = metadata.frameNumber,
            sensorTimestampNs = sensorTimestampNs,
            captureSequenceId = metadata.sequenceId
        )

        val shading = metadata.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.copySnapshot()
        val rawNoise = metadata.get(CaptureResult.SENSOR_NOISE_PROFILE)
            ?.flatMap { pair -> listOf(pair.first, pair.second) }
        val rawDynamicBlack = metadata.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.map { it }
        val rawNeutral = metadata.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
            ?.mapNotNull { it.safeFloatOrNull() }
        val rawWb = metadata.get(CaptureResult.COLOR_CORRECTION_GAINS)?.asList()
        val rawColorCorrection = metadata.get(CaptureResult.COLOR_CORRECTION_TRANSFORM).asFloatList()

        val cfa = staticProfile.cfaArrangement.toField(
            source = "CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT",
            valid = { it >= 0 },
            invalidReason = "CFA_VALUE_INVALID"
        )
        val staticBlack = staticProfile.staticBlackLevels
            ?.map { it.toFloat() }
            .toListField(
                source = "CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN",
                expectedSize = 4,
                validItem = { it.isFinite() && it >= 0f },
                invalidReason = "STATIC_BLACK_LEVEL_INVALID"
            )
        val dynamicBlack = rawDynamicBlack.toListField(
            source = "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL",
            expectedSize = 4,
            validItem = { it.isFinite() && it >= 0f },
            invalidReason = "DYNAMIC_BLACK_LEVEL_INVALID"
        )
        val effectiveBlack = preferDynamic(
            dynamic = dynamicBlack,
            static = staticBlack,
            unavailableReason = "BLACK_LEVEL_METADATA_UNAVAILABLE"
        )

        val staticWhite = staticProfile.staticWhiteLevel.toField(
            source = "CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL",
            valid = { it > 0 },
            invalidReason = "STATIC_WHITE_LEVEL_INVALID"
        )
        val dynamicWhite = metadata.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL).toField(
            source = "CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL",
            valid = { it > 0 },
            invalidReason = "DYNAMIC_WHITE_LEVEL_INVALID"
        )
        val effectiveWhite = preferDynamic(
            dynamic = dynamicWhite,
            static = staticWhite,
            unavailableReason = "WHITE_LEVEL_METADATA_UNAVAILABLE"
        )

        val sensitivity = metadata.get(CaptureResult.SENSOR_SENSITIVITY).toField(
            source = "CaptureResult.SENSOR_SENSITIVITY",
            valid = { it > 0 },
            invalidReason = "SENSOR_SENSITIVITY_INVALID"
        )
        val exposure = metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME).toField(
            source = "CaptureResult.SENSOR_EXPOSURE_TIME",
            valid = { it > 0L },
            invalidReason = "SENSOR_EXPOSURE_TIME_INVALID"
        )
        val frameDuration = metadata.get(CaptureResult.SENSOR_FRAME_DURATION).toField(
            source = "CaptureResult.SENSOR_FRAME_DURATION",
            valid = { it > 0L },
            invalidReason = "SENSOR_FRAME_DURATION_INVALID"
        )
        val maxAnalog = staticProfile.maxAnalogSensitivityIso.toField(
            source = "CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY",
            valid = { it > 0 },
            invalidReason = "MAX_ANALOG_SENSITIVITY_INVALID"
        )
        val analogSensitivity = deriveAnalogSensitivity(sensitivity, maxAnalog)
        val analogGainRelativeToMinimum = deriveAnalogGainRelativeToMinimum(
            analogSensitivity = analogSensitivity,
            sensitivityRange = staticProfile.sensitivityRange
        )
        val sensorDigitalGain = deriveSensorDigitalGain(sensitivity, analogSensitivity)
        val postRawBoost = metadata.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST).toField(
            source = "CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST",
            valid = { it > 0 },
            invalidReason = "POST_RAW_SENSITIVITY_BOOST_INVALID"
        )
        val postRawGain = derivePostRawDigitalGain(postRawBoost)
        val combinedDigitalGain = deriveCombinedDigitalGain(sensorDigitalGain, postRawGain)

        val noise = rawNoise.toNoiseProfileField(
            source = "CaptureResult.SENSOR_NOISE_PROFILE"
        )
        val wbGains = rawWb.toListField(
            source = "CaptureResult.COLOR_CORRECTION_GAINS",
            expectedSize = 4,
            validItem = { it.isFinite() && it > 0f },
            invalidReason = "COLOR_CORRECTION_GAINS_INVALID"
        )
        val colorCorrection = rawColorCorrection.toMatrixField(
            "CaptureResult.COLOR_CORRECTION_TRANSFORM"
        )
        val neutral = rawNeutral.toListField(
            source = "CaptureResult.SENSOR_NEUTRAL_COLOR_POINT",
            expectedSize = 3,
            validItem = { it.isFinite() && it > 0f },
            invalidReason = "SENSOR_NEUTRAL_COLOR_POINT_INVALID"
        )

        val activeArray = staticProfile.activeArray.toField(
            source = "CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE",
            valid = { it.width > 0 && it.height > 0 },
            invalidReason = "ACTIVE_ARRAY_INVALID"
        )
        val rawSize = rawFrameSize.toField(
            source = "PipelineIdentity.ImageReader",
            valid = { it.width > 0 && it.height > 0 },
            invalidReason = "RAW_FRAME_SIZE_INVALID"
        )
        val orientation = staticProfile.sensorOrientationDegrees.toField(
            source = "CameraCharacteristics.SENSOR_ORIENTATION",
            valid = { it == 0 || it == 90 || it == 180 || it == 270 },
            invalidReason = "SENSOR_ORIENTATION_INVALID"
        )
        val timestamp = SensorMetadataValue.valid(
            value = sensorTimestampNs,
            source = "CaptureResult.SENSOR_TIMESTAMP"
        )
        val frameNumber = SensorMetadataValue.valid(
            value = metadata.frameNumber,
            source = "CaptureResult.frameNumber"
        )
        val rollingSkew = metadata.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW).toField(
            source = "CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW",
            valid = { it >= 0L },
            invalidReason = "ROLLING_SHUTTER_SKEW_INVALID"
        )

        val snapshot = SensorMetadata(
            route = route,
            staticFingerprint = staticProfile.staticFingerprint,
            metadataSource = if (route.physicalCameraId != null) {
                "PHYSICAL_CAPTURE_RESULT"
            } else {
                "STANDALONE_CAPTURE_RESULT"
            },
            sensorIdentity = sensorIdentity,
            captureIdentity = captureIdentity,
            cfa = cfa,
            staticBlackLevel = staticBlack,
            dynamicBlackLevel = dynamicBlack,
            effectiveBlackLevel = effectiveBlack,
            staticWhiteLevelField = staticWhite,
            dynamicWhiteLevelField = dynamicWhite,
            effectiveWhiteLevelField = effectiveWhite,
            sensitivityIsoField = sensitivity,
            exposureTimeNsField = exposure,
            frameDurationNsField = frameDuration,
            maxAnalogSensitivityIso = maxAnalog,
            analogSensitivityIso = analogSensitivity,
            analogGainRelativeToMinimum = analogGainRelativeToMinimum,
            sensorDigitalGainRatio = sensorDigitalGain,
            postRawSensitivityBoostField = postRawBoost,
            postRawDigitalGainRatio = postRawGain,
            combinedDigitalGainRatio = combinedDigitalGain,
            noiseProfileSoField = noise,
            colorCorrectionGainsField = wbGains,
            colorCorrectionTransformField = colorCorrection,
            neutralColorPointField = neutral,
            referenceIlluminant1Field = staticProfile.referenceIlluminant1.toField(
                source = "CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1",
                valid = { it > 0 },
                invalidReason = "REFERENCE_ILLUMINANT1_INVALID"
            ),
            referenceIlluminant2Field = staticProfile.referenceIlluminant2.toField(
                source = "CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2",
                valid = { it > 0 },
                invalidReason = "REFERENCE_ILLUMINANT2_INVALID"
            ),
            colorTransform1 = staticProfile.colorTransform1.toMatrixField(
                "CameraCharacteristics.SENSOR_COLOR_TRANSFORM1"
            ),
            colorTransform2 = staticProfile.colorTransform2.toMatrixField(
                "CameraCharacteristics.SENSOR_COLOR_TRANSFORM2"
            ),
            forwardMatrix1 = staticProfile.forwardMatrix1.toMatrixField(
                "CameraCharacteristics.SENSOR_FORWARD_MATRIX1"
            ),
            forwardMatrix2 = staticProfile.forwardMatrix2.toMatrixField(
                "CameraCharacteristics.SENSOR_FORWARD_MATRIX2"
            ),
            cameraCalibration1 = staticProfile.cameraCalibration1.toMatrixField(
                "CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1"
            ),
            cameraCalibration2 = staticProfile.cameraCalibration2.toMatrixField(
                "CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2"
            ),
            activeArrayField = activeArray,
            rawSizeField = rawSize,
            orientationField = orientation,
            timestampField = timestamp,
            frameNumberField = frameNumber,
            rollingShutterSkewNsField = rollingSkew,
            lensShadingMapMode = metadata.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE),
            lensShadingRows = shading?.rows ?: 0,
            lensShadingColumns = shading?.columns ?: 0,
            lensShadingGainFactors = shading?.gainFactors,
            aeState = metadata.get(CaptureResult.CONTROL_AE_STATE),
            awbState = metadata.get(CaptureResult.CONTROL_AWB_STATE),
            afState = metadata.get(CaptureResult.CONTROL_AF_STATE),
            sceneFlicker = metadata.get(CaptureResult.STATISTICS_SCENE_FLICKER),
            lensState = metadata.get(CaptureResult.LENS_STATE),
            oisMode = metadata.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE),
            focusDistanceDiopters = metadata.get(CaptureResult.LENS_FOCUS_DISTANCE),
            focalLengthMm = metadata.get(CaptureResult.LENS_FOCAL_LENGTH),
            aperture = metadata.get(CaptureResult.LENS_APERTURE),
            rawSourceId = sensorIdentity.sourceId,
            captureResultSourceId = captureResultSourceId,
            characteristicsSourceId = staticProfile.characteristicsCameraId,
            calibrationSourceId = sensorIdentity.sourceId,
            logicalMetadataFallbackUsed = false,
            foreignSensorMetadataUsed = false
        )
        rememberFrameAuthority(sensorTimestampNs, snapshot.route, metadata, captureIdentity, snapshot)
        return snapshot
    }

    fun calibrationInputFor(
        @Suppress("UNUSED_PARAMETER") fallbackCharacteristics: CameraCharacteristics,
        captureResult: CaptureResult?,
        sensorMetadataHint: SensorMetadata? = null
    ): SensorCalibrationInput {
        val sensorTimestampNs = sensorMetadataHint?.captureIdentity?.sensorTimestampNs
            ?.takeIf { it > 0L }
            ?: captureResult?.get(CaptureResult.SENSOR_TIMESTAMP)?.takeIf { it > 0L }
            ?: throw SensorAuthorityUnavailableException("SENSOR_TIMESTAMP_UNAVAILABLE")

        val rememberedRoute = rememberedFrameRoute(sensorTimestampNs)
            ?: throw SensorAuthorityUnavailableException("SENSOR_AUTHORITY_FRAME_NOT_REGISTERED")
        val rememberedCaptureIdentity = rememberedFrameIdentity(sensorTimestampNs)
            ?: throw SensorAuthorityUnavailableException("CAPTURE_IDENTITY_UNAVAILABLE")
        val sensorIdentity = rememberedCaptureIdentity.sensorIdentity
        val exactCaptureResult = rememberedFrameResult(sensorTimestampNs)
            ?: throw SensorAuthorityUnavailableException("CAPTURE_RESULT_AUTHORITY_UNAVAILABLE")
        val sensorMetadata = rememberedSensorMetadata(sensorTimestampNs)
            ?: throw SensorAuthorityUnavailableException("SENSOR_METADATA_UNAVAILABLE")

        if (captureResult != null && captureResult.frameNumber != rememberedCaptureIdentity.frameNumber) {
            throw SensorAuthorityUnavailableException("CAPTURE_FRAME_NUMBER_MISMATCH")
        }
        if (sensorMetadataHint != null &&
            (sensorMetadataHint.captureIdentity != rememberedCaptureIdentity ||
                sensorMetadataHint.sensorIdentity != rememberedCaptureIdentity.sensorIdentity)
        ) {
            throw SensorAuthorityUnavailableException("SENSOR_METADATA_HINT_IDENTITY_MISMATCH")
        }
        if (rememberedRoute.effectiveCameraId != sensorIdentity.sourceId) {
            throw SensorAuthorityUnavailableException("FRAME_ROUTE_AUTHORITY_MISMATCH")
        }

        val characteristics = characteristicsForCameraId(sensorIdentity.sourceId)
            ?: throw SensorAuthorityUnavailableException("CHARACTERISTICS_UNAVAILABLE")
        val profile = profileFor(
            rememberedRoute.logicalCameraId,
            rememberedRoute.physicalCameraId
        ) ?: throw SensorAuthorityUnavailableException("CALIBRATION_PROFILE_AUTHORITY_UNAVAILABLE")

        if (profile.characteristicsCameraId != sensorIdentity.sourceId) {
            throw SensorAuthorityUnavailableException("FOREIGN_SENSOR_CHARACTERISTICS")
        }
        if (sensorMetadata.captureIdentity != rememberedCaptureIdentity ||
            sensorMetadata.sensorIdentity != sensorIdentity ||
            sensorMetadata.characteristicsSourceId != sensorIdentity.sourceId ||
            sensorMetadata.captureResultSourceId != sensorIdentity.sourceId
        ) {
            throw SensorAuthorityUnavailableException("SENSOR_METADATA_AUTHORITY_MISMATCH")
        }

        return SensorCalibrationInput(
            physicalCameraId = rememberedRoute.physicalCameraId,
            characteristics = characteristics,
            captureResult = exactCaptureResult,
            sensorMetadata = sensorMetadata,
            authority = if (rememberedRoute.physicalCameraId != null) {
                "FRAME_SNAPSHOT_EXACT_PHYSICAL_RESULT"
            } else {
                "FRAME_SNAPSHOT_STANDALONE_RESULT"
            },
            deterministic = true,
            staticFingerprint = profile.staticFingerprint,
            sensorIdentity = sensorIdentity
        )
    }

    fun snapshot(): SensorProfileRegistrySnapshot = SensorProfileRegistrySnapshot(
        prewarmAttempted.get(), prewarmCompleted.get(), profiles.size,
        profiles.keys.count { !it.physicalCameraId.isNullOrBlank() }, failures.toList()
    )

    fun debugSummary(): String {
        val s = snapshot()
        return "prewarmAttempted=${s.prewarmAttempted}; prewarmCompleted=${s.prewarmCompleted}; routes=${s.routeCount}; physicalRoutes=${s.physicalRouteCount}; failures=${s.failures.joinToString("|").ifBlank { "none" }}"
    }

    private fun installRoute(
        logicalId: String,
        physicalId: String?,
        chars: CameraCharacteristics,
        characteristicsCameraId: String,
        source: String
    ): PhysicalSensorProfile {
        val key = SensorRouteKey(logicalId, physicalId?.takeIf { it.isNotBlank() })
        characteristicsByCameraId.putIfAbsent(characteristicsCameraId, chars)
        return profiles.computeIfAbsent(key) { buildStaticProfile(key, chars, characteristicsCameraId, source) }
    }

    private fun buildStaticProfile(
        route: SensorRouteKey,
        chars: CameraCharacteristics,
        characteristicsCameraId: String,
        source: String
    ): PhysicalSensorProfile {
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        return PhysicalSensorProfile(
            route = route,
            characteristicsCameraId = characteristicsCameraId,
            characteristicsSource = source,
            rawCapabilityAdvertised = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            cfaArrangement = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT),
            sensorOrientationDegrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION),
            pixelArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let { SizeSnapshot(it.width, it.height) },
            rawSensorOutputSizes = rawOutputSizes(chars, ImageFormat.RAW_SENSOR),
            raw10OutputSizes = rawOutputSizes(chars, ImageFormat.RAW10),
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toSnapshot(),
            preCorrectionActiveArray = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.toSnapshot(),
            opticalBlackRegions = chars.get(CameraCharacteristics.SENSOR_OPTICAL_BLACK_REGIONS)?.map { it.toSnapshot() }.orEmpty(),
            sensitivityRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { IntRangeSnapshot(it.lower, it.upper) },
            exposureTimeRangeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { LongRangeSnapshot(it.lower, it.upper) },
            maxFrameDurationNs = chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION),
            maxAnalogSensitivityIso = chars.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY),
            staticBlackLevels = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.toList(),
            staticWhiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            rawLensShadingAlreadyApplied = chars.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED),
            availableLensShadingMapModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)?.toList().orEmpty(),
            availableShadingModes = chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)?.toList().orEmpty(),
            availableOisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty(),
            focalLengthsMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
            apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
            minimumFocusDistanceDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            hyperfocalDistanceDiopters = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE),
            referenceIlluminant1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
            referenceIlluminant2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
            colorTransform1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1).asFloatList(),
            colorTransform2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2).asFloatList(),
            cameraCalibration1 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1).asFloatList(),
            cameraCalibration2 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2).asFloatList(),
            forwardMatrix1 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1).asFloatList(),
            forwardMatrix2 = chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2).asFloatList(),
            availableAntibandingModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.toList().orEmpty(),
            staticFingerprint = staticFingerprint(route, chars)
        )
    }

    private fun rememberFrameAuthority(
        timestampNs: Long,
        route: SensorRouteKey,
        captureResult: CaptureResult,
        captureIdentity: CaptureIdentity,
        sensorMetadata: SensorMetadata
    ) {
        if (timestampNs <= 0L) return
        synchronized(recentFrameRouteLock) {
            recentFrameRoutesByTimestamp[timestampNs] = route
            recentFrameResultsByTimestamp[timestampNs] = captureResult
            recentFrameIdentitiesByTimestamp[timestampNs] = captureIdentity
            recentFrameMetadataByTimestamp[timestampNs] = sensorMetadata
            while (recentFrameRoutesByTimestamp.size > RECENT_FRAME_ROUTE_CAPACITY) {
                val eldest = recentFrameRoutesByTimestamp.entries.firstOrNull()?.key ?: break
                recentFrameRoutesByTimestamp.remove(eldest)
                recentFrameResultsByTimestamp.remove(eldest)
                recentFrameIdentitiesByTimestamp.remove(eldest)
                recentFrameMetadataByTimestamp.remove(eldest)
            }
        }
    }

    private fun rememberedFrameRoute(timestampNs: Long): SensorRouteKey? =
        synchronized(recentFrameRouteLock) { recentFrameRoutesByTimestamp[timestampNs] }

    private fun rememberedFrameResult(timestampNs: Long): CaptureResult? =
        synchronized(recentFrameRouteLock) { recentFrameResultsByTimestamp[timestampNs] }

    private fun rememberedFrameIdentity(timestampNs: Long): CaptureIdentity? =
        synchronized(recentFrameRouteLock) { recentFrameIdentitiesByTimestamp[timestampNs] }

    private fun rememberedSensorMetadata(timestampNs: Long): SensorMetadata? =
        synchronized(recentFrameRouteLock) { recentFrameMetadataByTimestamp[timestampNs] }

    private fun isLogicalMultiCameraCameraId(cameraId: String): Boolean {
        val chars = characteristicsForCameraId(cameraId) ?: return false
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toList()
            .orEmpty()
        return capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
        ) && chars.physicalCameraIds.isNotEmpty()
    }

    private fun characteristicsForCameraId(cameraId: String): CameraCharacteristics? {
        characteristicsByCameraId[cameraId]?.let { return it }
        return runCatching { cameraManager.getCameraCharacteristics(cameraId) }
            .onFailure { recordFailure("calibration physical=$cameraId characteristics=${it.javaClass.simpleName}") }
            .getOrNull()
            ?.also { characteristicsByCameraId.putIfAbsent(cameraId, it) }
    }

    private fun physicalResultIds(result: TotalCaptureResult): Set<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result.physicalCameraTotalResults.keys
        } else {
            @Suppress("DEPRECATION") result.physicalCameraResults.keys
        }
    }

    private fun exactPhysicalResult(result: TotalCaptureResult, physicalCameraId: String?): CaptureResult? {
        if (physicalCameraId.isNullOrBlank()) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result.physicalCameraTotalResults[physicalCameraId]
        } else {
            @Suppress("DEPRECATION") result.physicalCameraResults[physicalCameraId]
        }
    }

    private fun rawOutputSizes(chars: CameraCharacteristics, format: Int): List<SizeSnapshot> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        return runCatching { map.getOutputSizes(format)?.toList().orEmpty() }
            .getOrElse { emptyList() }
            .filter { it.width > 0 && it.height > 0 }
            .map { SizeSnapshot(it.width, it.height) }
            .distinct()
            .sortedWith(compareByDescending<SizeSnapshot> { it.width.toLong() * it.height.toLong() }.thenByDescending { it.width })
    }

    private fun <T> T?.toField(
        source: String,
        valid: (T) -> Boolean,
        invalidReason: String
    ): SensorMetadataValue<T> = when {
        this == null -> SensorMetadataValue.unavailable(source, "VALUE_UNAVAILABLE")
        valid(this) -> SensorMetadataValue.valid(this, source)
        else -> SensorMetadataValue.invalid(this, source, invalidReason)
    }

    private fun <T> List<T>?.toListField(
        source: String,
        expectedSize: Int? = null,
        expectedMultiple: Int? = null,
        validItem: (T) -> Boolean,
        invalidReason: String
    ): SensorMetadataValue<List<T>> {
        if (this == null) return SensorMetadataValue.unavailable(source, "VALUE_UNAVAILABLE")
        val copy = this.toList()
        val validShape = copy.isNotEmpty() &&
            (expectedSize == null || copy.size == expectedSize) &&
            (expectedMultiple == null || (copy.size % expectedMultiple == 0))
        return if (validShape && copy.all(validItem)) {
            SensorMetadataValue.valid(copy, source)
        } else {
            SensorMetadataValue.invalid(copy, source, invalidReason)
        }
    }

    private fun List<Float>?.toMatrixField(source: String): SensorMetadataValue<List<Float>> =
        toListField(
            source = source,
            expectedSize = 9,
            validItem = { it.isFinite() },
            invalidReason = "MATRIX_INVALID"
        )

    private fun List<Double>?.toNoiseProfileField(source: String): SensorMetadataValue<List<Double>> {
        val field = toListField(
            source = source,
            expectedMultiple = 2,
            validItem = { it.isFinite() && it >= 0.0 },
            invalidReason = "SENSOR_NOISE_PROFILE_INVALID"
        )
        val values = field.value
        return if (field.isValid && values != null && values.all { kotlin.math.abs(it) < 1.0e-12 }) {
            SensorMetadataValue.invalid(values, source, "SENSOR_NOISE_PROFILE_ALL_ZERO")
        } else {
            field
        }
    }

    private fun <T> preferDynamic(
        dynamic: SensorMetadataValue<T>,
        static: SensorMetadataValue<T>,
        unavailableReason: String
    ): SensorMetadataValue<T> = when {
        dynamic.isValid -> dynamic
        static.isValid -> static
        dynamic.validity == SensorMetadataValidity.INVALID ->
            SensorMetadataValue.invalid(dynamic.value, dynamic.source, dynamic.reason)
        static.validity == SensorMetadataValidity.INVALID ->
            SensorMetadataValue.invalid(static.value, static.source, static.reason)
        else -> SensorMetadataValue.unavailable("NONE", unavailableReason)
    }

    private fun deriveAnalogSensitivity(
        sensitivity: SensorMetadataValue<Int>,
        maxAnalog: SensorMetadataValue<Int>
    ): SensorMetadataValue<Int> {
        val iso = sensitivity.value
        val maxIso = maxAnalog.value
        if (!sensitivity.isValid || iso == null) {
            return SensorMetadataValue.unavailable(
                "DERIVED:SENSOR_SENSITIVITY+SENSOR_MAX_ANALOG_SENSITIVITY",
                "SENSOR_SENSITIVITY_UNAVAILABLE"
            )
        }
        if (!maxAnalog.isValid || maxIso == null) {
            return SensorMetadataValue.unavailable(
                "DERIVED:SENSOR_SENSITIVITY+SENSOR_MAX_ANALOG_SENSITIVITY",
                "MAX_ANALOG_SENSITIVITY_UNAVAILABLE"
            )
        }
        return SensorMetadataValue.valid(
            minOf(iso, maxIso),
            "DERIVED:min(SENSOR_SENSITIVITY,SENSOR_MAX_ANALOG_SENSITIVITY)"
        )
    }

    private fun deriveAnalogGainRelativeToMinimum(
        analogSensitivity: SensorMetadataValue<Int>,
        sensitivityRange: IntRangeSnapshot?
    ): SensorMetadataValue<Double> {
        val analogIso = analogSensitivity.value
        val minimumIso = sensitivityRange?.lower
        if (!analogSensitivity.isValid || analogIso == null) {
            return SensorMetadataValue.unavailable(
                "DERIVED:ANALOG_SENSITIVITY/SENSITIVITY_RANGE.lower",
                "ANALOG_SENSITIVITY_UNAVAILABLE"
            )
        }
        if (minimumIso == null || minimumIso <= 0) {
            return SensorMetadataValue.unavailable(
                "DERIVED:ANALOG_SENSITIVITY/SENSITIVITY_RANGE.lower",
                "MINIMUM_SENSITIVITY_UNAVAILABLE"
            )
        }
        return SensorMetadataValue.valid(
            analogIso.toDouble() / minimumIso.toDouble(),
            "DERIVED:analogSensitivityIso/SENSOR_INFO_SENSITIVITY_RANGE.lower"
        )
    }

    private fun deriveSensorDigitalGain(
        sensitivity: SensorMetadataValue<Int>,
        analogSensitivity: SensorMetadataValue<Int>
    ): SensorMetadataValue<Double> {
        val iso = sensitivity.value
        val analogIso = analogSensitivity.value
        if (!sensitivity.isValid || !analogSensitivity.isValid || iso == null || analogIso == null || analogIso <= 0) {
            return SensorMetadataValue.unavailable(
                "DERIVED:SENSOR_SENSITIVITY/ANALOG_SENSITIVITY",
                "GAIN_INPUT_UNAVAILABLE"
            )
        }
        return SensorMetadataValue.valid(
            iso.toDouble() / analogIso.toDouble(),
            "DERIVED:SENSOR_SENSITIVITY/analogSensitivityIso"
        )
    }

    private fun derivePostRawDigitalGain(
        postRawBoost: SensorMetadataValue<Int>
    ): SensorMetadataValue<Double> {
        val boost = postRawBoost.value
        if (!postRawBoost.isValid || boost == null) {
            return SensorMetadataValue.unavailable(
                "DERIVED:CONTROL_POST_RAW_SENSITIVITY_BOOST/100",
                "POST_RAW_SENSITIVITY_BOOST_UNAVAILABLE"
            )
        }
        return SensorMetadataValue.valid(
            boost.toDouble() / 100.0,
            "DERIVED:CONTROL_POST_RAW_SENSITIVITY_BOOST/100"
        )
    }

    private fun deriveCombinedDigitalGain(
        sensorDigitalGain: SensorMetadataValue<Double>,
        postRawDigitalGain: SensorMetadataValue<Double>
    ): SensorMetadataValue<Double> {
        val sensor = sensorDigitalGain.value
        val postRaw = postRawDigitalGain.value
        if (!sensorDigitalGain.isValid || !postRawDigitalGain.isValid || sensor == null || postRaw == null) {
            return SensorMetadataValue.unavailable(
                "DERIVED:sensorDigitalGain*postRawDigitalGain",
                "DIGITAL_GAIN_COMPONENT_UNAVAILABLE"
            )
        }
        return SensorMetadataValue.valid(
            sensor * postRaw,
            "DERIVED:sensorDigitalGain*postRawDigitalGain"
        )
    }

    private fun recordFailure(message: String) {
        if (!failures.contains(message)) failures.add(message)
        Log.w(TAG, message)
    }

    private fun staticFingerprint(route: SensorRouteKey, chars: CameraCharacteristics): String {
        fun m(key: CameraCharacteristics.Key<ColorSpaceTransform>) = chars.get(key).asFloatList()?.joinToString(",") ?: "-"
        val canonical = listOf(
            route.toString(),
            chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)?.toString() ?: "-",
            chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toString() ?: "-",
            chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.toList()?.joinToString(",") ?: "-",
            chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toString() ?: "-",
            chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toString() ?: "-",
            m(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1), m(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2),
            m(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1), m(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2),
            m(CameraCharacteristics.SENSOR_FORWARD_MATRIX1), m(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)
        ).joinToString("|")
        return canonical.hashCode().toUInt().toString(16).padStart(8, '0')
    }

    private data class LensShadingSnapshot(val rows: Int, val columns: Int, val gainFactors: List<Float>)

    private fun LensShadingMap.copySnapshot(): LensShadingSnapshot? {
        val rows = rowCount
        val columns = columnCount
        val count = gainFactorCount
        if (rows <= 0 || columns <= 0 || count != rows * columns * 4) return null
        val factors = FloatArray(count)
        copyGainFactors(factors, 0)
        if (factors.any { !it.isFinite() || it < 1.0f }) return null
        return LensShadingSnapshot(rows, columns, factors.toList())
    }

    private fun RggbChannelVector.asList() = listOf(red, greenEven, greenOdd, blue)
    private fun BlackLevelPattern.toList() = listOf(
        getOffsetForIndex(0, 0), getOffsetForIndex(1, 0),
        getOffsetForIndex(0, 1), getOffsetForIndex(1, 1)
    )
    private fun android.graphics.Rect.toSnapshot() = RectSnapshot(left, top, right, bottom)
    private fun Rational.safeFloatOrNull(): Float? = if (denominator == 0) null else
        (numerator.toDouble() / denominator.toDouble()).toFloat().takeIf { it.isFinite() }
    private fun ColorSpaceTransform?.asFloatList(): List<Float>? {
        if (this == null) return null
        val out = ArrayList<Float>(9)
        for (row in 0 until 3) for (column in 0 until 3) {
            out += getElement(column, row).safeFloatOrNull() ?: return null
        }
        return out
    }

    companion object {
        private const val TAG = "SensorProfileRegistry"
        private const val RECENT_FRAME_ROUTE_CAPACITY = 128
        @Volatile private var activeRegistry: PhysicalSensorProfileRegistry? = null

        fun resolveCurrentCalibrationInput(
            fallbackCharacteristics: CameraCharacteristics,
            captureResult: CaptureResult?,
            sensorMetadata: SensorMetadata? = null
        ): SensorCalibrationInput {
            val registry = activeRegistry
                ?: throw SensorAuthorityUnavailableException("SENSOR_AUTHORITY_REGISTRY_UNAVAILABLE")
            return registry.calibrationInputFor(fallbackCharacteristics, captureResult, sensorMetadata)
        }
    }
}
