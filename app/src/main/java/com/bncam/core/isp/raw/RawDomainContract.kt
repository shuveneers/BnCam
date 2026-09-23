package com.bncam.core.isp.raw

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import com.bncam.core.quality.FinalSensorCalibration
import com.bncam.core.quality.RenderQualityConfig
import kotlin.math.roundToInt

enum class RawInputSource {
    RAW10,
    RAW_SENSOR,
    RAW16_MASTER
}

enum class RawStorageAlignment {
    RIGHT_JUSTIFIED,
    LEFT_JUSTIFIED,
    PACKED
}

enum class RawSampleTransform {
    IDENTITY_NATIVE_TO_PAYLOAD,
    RAW10_PACKED_TO_BLACK_ANCHORED_PAYLOAD,
    RAW_SENSOR_RIGHT_JUSTIFIED_TO_PAYLOAD,
    RAW16_MASTER_IDENTITY
}

enum class RawLensShadingState {
    NOT_REQUESTED,
    REQUESTED_MAP_AVAILABLE,
    REQUESTED_MAP_MISSING,
    UNKNOWN
}

data class RawContractRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun toIntArray(): IntArray = intArrayOf(left, top, right, bottom)

    override fun toString(): String = "[$left,$top,$right,$bottom]"
}

/**
 * Immutable RAW-domain contract shared by RAW10, RAW_SENSOR and the virtual Master RAW16.
 *
 * Compatibility properties keep the existing native bridge stable while the pipeline is moved
 * to this single source of truth. New code should prefer the explicit native and payload fields.
 */
data class RawDomainContract(
    val sourceFormat: RawInputSource,
    val width: Int,
    val height: Int,
    val nativeBitDepth: Int,
    val sourceStorageAlignment: RawStorageAlignment,
    val masterStorageAlignment: RawStorageAlignment,
    /** Sensor-origin positional [00,10,01,11] black levels in native source units. */
    val nativeBlackLevels: List<Int>,
    val nativeWhiteLevel: Int,
    /** Sensor-origin positional [00,10,01,11] black levels in DNG/master payload units. */
    val payloadBlackLevels: List<Int>,
    val payloadWhiteLevel: Int,
    /** White level used only by developed RAW JPEG/noise processing, in payload/master units. */
    val developedRawWhiteLevel: Int = payloadWhiteLevel,
    val developedRawWhiteLevelSource: String = "physical payload white",
    val developedWhiteMetadataAuthoritative: Boolean = false,
    val developedWhiteFallbackReason: String = "not_recorded",
    val developedWhiteManualOverrideUsed: Boolean = false,
    val developedWhiteScaleFactor: Float = 1f,
    /** Canonical [R, Gr, Gb, B] black levels used by developed RAW JPEG/noise processing. */
    val developedRawBlackLevels: List<Float> = List(4) { 0f },
    val developedRawBlackLevelSource: String = "sensor metadata",
    val developedBlackMetadataAuthoritative: Boolean = false,
    val developedBlackFallbackReason: String = "not_recorded",
    val cfaPattern: Int,
    val cfaName: String,
    val cfaOriginX: Int,
    val cfaOriginY: Int,
    val activeArray: RawContractRect,
    val cropRegion: RawContractRect,
    val preCorrectionArray: RawContractRect,
    val sourceRowStrideBytes: Int,
    val sourcePixelStrideBytes: Int,
    val masterRowStrideBytes: Int,
    val masterPixelStrideBytes: Int,
    val dynamicBlackLevelUsed: Boolean,
    val dynamicWhiteLevelUsed: Boolean,
    val staticBlackLevelUsed: Boolean,
    val staticWhiteLevelUsed: Boolean,
    val manualOverrideUsed: Boolean,
    val physicalCameraId: String?,
    val lensId: String,
    val lensShadingState: RawLensShadingState,
    val sampleTransform: RawSampleTransform,
    val sensorInfoWhiteLevel: Int?,
    val sensorDynamicWhiteLevel: Int?,
    val sensorBlackLevelPatternValues: List<Float>,
    val sensorDynamicBlackLevelValues: List<Float>?,
    val chosenBlackLevelSource: String,
    val chosenWhiteLevelSource: String,
    val masterStorageScale: Float,
    val masterStorageLeftShift: Int,
    val masterStorageContract: String,
    val validationWarnings: List<String>
) {
    init {
        require(nativeBlackLevels.size == 4) { "nativeBlackLevels must contain 4 positional mosaic values" }
        require(payloadBlackLevels.size == 4) { "payloadBlackLevels must contain 4 positional mosaic values" }
        require(developedRawBlackLevels.size == 4) { "developedRawBlackLevels must contain canonical R/Gr/Gb/B values" }
        require(sensorBlackLevelPatternValues.size == 4) { "sensorBlackLevelPatternValues must contain 4 values" }
        require(nativeBitDepth in 1..16) { "nativeBitDepth must be in 1..16" }
        require(nativeWhiteLevel in 1..65535) { "nativeWhiteLevel must be in 1..65535" }
        require(payloadWhiteLevel in 1..65535) { "payloadWhiteLevel must be in 1..65535" }
        require(developedRawWhiteLevel in 1..65535) { "developedRawWhiteLevel must be in 1..65535" }
    }

    val bufferOriginCfaOffsetX: Int get() = cfaOriginX
    val bufferOriginCfaOffsetY: Int get() = cfaOriginY
    val sensorCfaPattern: Int get() = cfaPattern
    val sensorCfaName: String get() = cfaName
    val sourceBitDepth: Int get() = nativeBitDepth
    val effectiveSourceRange: Int get() = nativeWhiteLevel
    val effectiveWhiteLevelInMasterUnits: Float get() = developedRawWhiteLevel.toFloat() * masterStorageScale
    val developedRawBlackLevelsCanonicalInMasterUnits: List<Float>
        get() = developedRawBlackLevels.map { it * masterStorageScale }
    val effectiveBlackLevelPatternInMasterUnits: List<Float>
        get() = RawLevelOrder.canonicalToMosaic(
            developedRawBlackLevelsCanonicalInMasterUnits,
            cfaPattern
        )
    val sensorBlackLevelPattern: FloatArray get() = sensorBlackLevelPatternValues.toFloatArray()
    val sensorDynamicBlackLevel: FloatArray? get() = sensorDynamicBlackLevelValues?.toFloatArray()

    fun payloadBlackLevelsIntArray(): IntArray = IntArray(4) { payloadBlackLevels[it] }

    /**
     * Compact provenance block for shot diagnostics. Physical/DNG white and developed/JPEG white
     * are intentionally reported side-by-side so a manual developed override can never be
     * mistaken for a mutation of the RAW16/DNG payload contract.
     */
    fun whiteAuthorityDebugPairs(): List<Pair<String, String>> = listOf(
        "Lens ID" to lensId,
        "Physical Camera ID" to (physicalCameraId ?: "not_reported"),
        "Source Format" to sourceFormat.name,
        "Sensor Dynamic White" to (sensorDynamicWhiteLevel?.toString() ?: "unavailable"),
        "Sensor Static White" to (sensorInfoWhiteLevel?.toString() ?: "unavailable"),
        "Native White" to nativeWhiteLevel.toString(),
        "DNG/Payload White" to payloadWhiteLevel.toString(),
        "DNG/Payload White Source" to chosenWhiteLevelSource,
        "Developed White" to developedRawWhiteLevel.toString(),
        "Developed White Source" to developedRawWhiteLevelSource,
        "Developed Metadata Authoritative" to developedWhiteMetadataAuthoritative.toString(),
        "Developed Manual Override" to developedWhiteManualOverrideUsed.toString(),
        "Developed Fallback" to developedWhiteFallbackReason,
        "Developed Scale" to developedWhiteScaleFactor.toString(),
        "Developed/Payload White Differ" to
            (payloadWhiteLevel != developedRawWhiteLevel).toString(),
        "DNG White Contract" to "physical payload white only"
    )

    fun dump(): String {
        return buildString {
            append("RawDomainContract(")
            append("sourceFormat=$sourceFormat")
            append(";nativeBitDepth=$nativeBitDepth")
            append(";sourceStorageAlignment=$sourceStorageAlignment")
            append(";masterStorageAlignment=$masterStorageAlignment")
            append(";nativeBlackLevels=${nativeBlackLevels.joinToString(prefix = "[", postfix = "]")}")
            append(";nativeWhiteLevel=$nativeWhiteLevel")
            append(";payloadBlackLevels=${payloadBlackLevels.joinToString(prefix = "[", postfix = "]")}")
            append(";developedRawBlackLevels=${developedRawBlackLevels.joinToString(prefix = "[", postfix = "]")}")
            append(";developedRawBlackLevelSource=$developedRawBlackLevelSource")
            append(";developedBlackMetadataAuthoritative=$developedBlackMetadataAuthoritative")
            append(";developedBlackFallbackReason=$developedBlackFallbackReason")
            append(";payloadWhiteLevel=$payloadWhiteLevel")
            append(";developedRawWhiteLevel=$developedRawWhiteLevel")
            append(";developedRawWhiteLevelSource=$developedRawWhiteLevelSource")
            append(";developedWhiteMetadataAuthoritative=$developedWhiteMetadataAuthoritative")
            append(";developedWhiteFallbackReason=$developedWhiteFallbackReason")
            append(";developedWhiteManualOverrideUsed=$developedWhiteManualOverrideUsed")
            append(";developedWhiteScaleFactor=$developedWhiteScaleFactor")
            append(";cfa=$cfaName/$cfaPattern")
            append(";cfaOrigin=$cfaOriginX,$cfaOriginY")
            append(";activeArray=$activeArray")
            append(";cropRegion=$cropRegion")
            append(";rowStride=$sourceRowStrideBytes")
            append(";pixelStride=$sourcePixelStrideBytes")
            append(";dynamicBlackLevelUsed=$dynamicBlackLevelUsed")
            append(";dynamicWhiteLevelUsed=$dynamicWhiteLevelUsed")
            append(";physicalCameraId=${physicalCameraId ?: "not_reported"}")
            append(";lensId=$lensId")
            append(";lensShadingState=$lensShadingState")
            append(";sampleTransform=$sampleTransform")
            append(";warnings=${validationWarnings.joinToString(prefix = "[", postfix = "]")}")
            append(")")
        }
    }
}

typealias RawFrameInfo = RawDomainContract

object RawDomainContractResolver {
    fun resolve(
        lensId: String,
        sourceFormat: Int,
        width: Int,
        height: Int,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult?,
        qualityConfig: RenderQualityConfig,
        dngMergeStats: String = ""
    ): RawDomainContract {
        val finalCal = qualityConfig.finalCalibration
        val source = when (sourceFormat) {
            ImageFormat.RAW10 -> RawInputSource.RAW10
            ImageFormat.RAW_SENSOR -> RawInputSource.RAW_SENSOR
            else -> RawInputSource.RAW16_MASTER
        }
        val warnings = mutableListOf<String>()
        val dynamicWhite = runCatching { captureResult?.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) }.getOrNull()
        val staticWhite = runCatching { characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) }.getOrNull()
        val staticBlackPattern = runCatching { characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN) }.getOrNull()
        val dynamicBlackPattern = runCatching {
            captureResult?.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.takeIf { it.size >= 4 }
        }.getOrNull()
        val staticBlack = if (staticBlackPattern != null) {
            listOf(
                staticBlackPattern.getOffsetForIndex(0, 0).toFloat(),
                staticBlackPattern.getOffsetForIndex(1, 0).toFloat(),
                staticBlackPattern.getOffsetForIndex(0, 1).toFloat(),
                staticBlackPattern.getOffsetForIndex(1, 1).toFloat()
            )
        } else {
            warnings.add("SENSOR_BLACK_LEVEL_PATTERN missing; metadata fallback black is 0 in native domain")
            listOf(0f, 0f, 0f, 0f)
        }
        val dynamicBlack = dynamicBlackPattern?.let { List(4) { index -> it[index] } }
        val rawMetadataWhite = (finalCal?.base?.baseWhiteLevelRawMetadata ?: dynamicWhite ?: staticWhite)
            ?.takeIf { it > 0 }
            ?: throw com.bncam.core.quality.SensorAuthorityUnavailableException(
                "UNSAFE_TO_PROCESS:WHITE_LEVEL_METADATA_UNAVAILABLE:${source.name}"
            )
        val payloadWhite = rawMetadataWhite.coerceIn(1, 65535)
        val nativeWhite = when (source) {
            RawInputSource.RAW10 -> 1023
            RawInputSource.RAW_SENSOR -> payloadWhite
            RawInputSource.RAW16_MASTER -> payloadWhite
        }
        val payloadWhiteSource = when {
            finalCal?.base?.baseWhiteLevelRawMetadata != null -> "RawDomainContract payload white from ${finalCal.base.baseWhiteLevelSource}"
            dynamicWhite != null -> "RawDomainContract payload white from CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL"
            staticWhite != null -> "RawDomainContract payload white from CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL"
            else -> "RawDomainContract metadata authority"
        }

        val cfa = finalCal?.base?.cfaPattern
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
            ?: CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB
        val cfaName = finalCal?.base?.cfaName ?: RenderQualityConfig.cfaName(cfa)

        val rawMetadataBlack = finalCal?.base?.baseBlackLevelRawMetadataValues?.takeIf { it.size >= 4 }
        val payloadBlack = when {
            rawMetadataBlack != null -> List(4) { index ->
                rawMetadataBlack[index].roundToInt().coerceIn(0, payloadWhite.coerceAtLeast(2) - 1)
            }
            qualityConfig.whiteLevel > 0 && qualityConfig.whiteLevel != payloadWhite -> {
                val scale = payloadWhite.toFloat() / qualityConfig.whiteLevel.toFloat()
                warnings.add("Black levels transformed from canonical RenderQualityConfig white=${qualityConfig.whiteLevel} to positional payload white=$payloadWhite by explicit contract scale")
                val canonical = List(4) { index ->
                    ((qualityConfig.blackLevels.getOrNull(index) ?: 0f) * scale).roundToInt()
                        .coerceIn(0, payloadWhite.coerceAtLeast(2) - 1)
                }
                RawLevelOrder.canonicalToMosaic(canonical, cfa)
            }
            else -> {
                val canonical = List(4) { index ->
                    (qualityConfig.blackLevels.getOrNull(index) ?: 0f).roundToInt()
                        .coerceIn(0, payloadWhite.coerceAtLeast(2) - 1)
                }
                RawLevelOrder.canonicalToMosaic(canonical, cfa)
            }
        }
        val payloadBlackSource = when {
            rawMetadataBlack != null -> "RawDomainContract payload black from ${finalCal?.base?.baseBlackLevelSource ?: "metadata"}"
            qualityConfig.whiteLevel > 0 && qualityConfig.whiteLevel != payloadWhite -> "RawDomainContract payload black transformed from ${qualityConfig.blackLevelSource}"
            else -> "RawDomainContract payload black from ${qualityConfig.blackLevelSource}"
        }
        val nativeBlack = if (source == RawInputSource.RAW10 && payloadWhite != nativeWhite) {
            payloadBlack.map { black ->
                ((black.toFloat() * nativeWhite.toFloat()) / payloadWhite.toFloat())
                    .roundToInt()
                    .coerceIn(0, nativeWhite.coerceAtLeast(2) - 1)
            }
        } else {
            payloadBlack.map { it.coerceIn(0, nativeWhite.coerceAtLeast(2) - 1) }
        }

        val fallbackDevelopedSourceLevels =
            finalCal?.effectiveBlackLevels?.toList() ?: qualityConfig.blackLevels
        val fallbackDevelopedScale =
            if (qualityConfig.whiteLevel > 0 && qualityConfig.whiteLevel != payloadWhite) {
                payloadWhite.toFloat() / qualityConfig.whiteLevel.toFloat()
            } else {
                1f
            }
        val fallbackDevelopedRawBlack = List(4) { index ->
            ((fallbackDevelopedSourceLevels.getOrNull(index) ?: 0f) * fallbackDevelopedScale)
                .coerceIn(0f, payloadWhite.coerceAtLeast(2) - 1f)
        }

        // P0: one developed-black owner. Prefer the exact frame's dynamic metadata, then the
        // static Camera2 pattern already selected by the central resolver. Lens/manual/scaled
        // values are only a controlled fallback when Camera2 black metadata is unavailable or invalid.
        val metadataMosaicBlack = when {
            rawMetadataBlack != null -> rawMetadataBlack.take(4)
            dynamicBlack != null -> dynamicBlack.take(4)
            staticBlackPattern != null -> staticBlack.take(4)
            else -> null
        }
        val metadataBlackSource = when {
            rawMetadataBlack != null -> finalCal?.base?.baseBlackLevelSource ?: "SensorCalibrationResolver metadata"
            dynamicBlack != null -> "CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL"
            staticBlackPattern != null -> "CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN"
            else -> "unavailable"
        }
        val blackAuthority = RawBlackAuthorityPolicy.resolve(
            metadataMosaicLevels = metadataMosaicBlack,
            metadataSource = metadataBlackSource,
            cfaPattern = cfa,
            fallbackCanonicalLevels = fallbackDevelopedRawBlack,
            fallbackSource = finalCal?.effectiveBlackLevelSource ?: qualityConfig.blackLevelSource
        )
        val developedRawBlack = blackAuthority.canonicalLevels.map {
            it.coerceIn(0f, payloadWhite.coerceAtLeast(2) - 1f)
        }
        val developedRawBlackSource = blackAuthority.source

        val blackOverrideRequested = finalCal?.override?.blackLevelMode
            ?.equals("System", ignoreCase = true) == false
        if (blackAuthority.metadataAuthoritative && blackOverrideRequested) {
            warnings.add(
                "P0 metadata-first black authority suppressed lens black override mode=" +
                    "${finalCal?.override?.blackLevelMode}; Camera2 metadata remains the developed RAW black owner"
            )
        }

        val sourceBitDepth = when (source) {
            RawInputSource.RAW10 -> 10
            RawInputSource.RAW_SENSOR -> bitDepthForWhite(nativeWhite, 1)
            RawInputSource.RAW16_MASTER -> bitDepthForWhite(nativeWhite, 1)
        }
        val sourceAlignment = when (source) {
            RawInputSource.RAW10 -> RawStorageAlignment.PACKED
            RawInputSource.RAW_SENSOR,
            RawInputSource.RAW16_MASTER -> RawStorageAlignment.RIGHT_JUSTIFIED
        }
        val sampleTransform = when (source) {
            RawInputSource.RAW10 -> if (payloadWhite == nativeWhite && payloadBlack == nativeBlack) {
                RawSampleTransform.IDENTITY_NATIVE_TO_PAYLOAD
            } else {
                RawSampleTransform.RAW10_PACKED_TO_BLACK_ANCHORED_PAYLOAD
            }
            RawInputSource.RAW_SENSOR -> RawSampleTransform.RAW_SENSOR_RIGHT_JUSTIFIED_TO_PAYLOAD
            RawInputSource.RAW16_MASTER -> RawSampleTransform.RAW16_MASTER_IDENTITY
        }
        val transform = com.bncam.core.runtime.SensorToRawBufferTransform.create(characteristics, width, height)
        val activeArray = RawContractRect(
            transform.bufferActiveRect.left,
            transform.bufferActiveRect.top,
            transform.bufferActiveRect.right,
            transform.bufferActiveRect.bottom
        )
        val crop = captureResult?.get(CaptureResult.SCALER_CROP_REGION)?.let {
            RawContractRect(it.left, it.top, it.right, it.bottom)
        } ?: RawContractRect(0, 0, width, height)
        val preCorrection = characteristics
            .get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?.let {
                RawContractRect(it.left, it.top, it.right, it.bottom)
            } ?: activeArray
        // RenderQualityConfig already froze the exact physical sensor authority for this frame.
        // The RAW materialization path intentionally passes the physical child CaptureResult, which
        // is not a TotalCaptureResult; re-discovering a child id from result keys therefore loses
        // truthful provenance on UW/tele routes. Keep the resolver's exact authority instead.
        val physicalCameraId = finalCal?.base?.physicalCameraId
        val lensShadingState = resolveLensShadingState(captureResult)
        val sourceRowStride = dngMergeStats.intValue("rowStrideBytes")
            ?: if (source == RawInputSource.RAW10) ((width + 3) / 4) * 5 else width * 2
        val sourcePixelStride = dngMergeStats.intValue("pixelStrideBytes")
            ?: if (source == RawInputSource.RAW10) 0 else 2

        val manualBlackFallbackUsed =
            !blackAuthority.metadataAuthoritative &&
                finalCal?.override?.manualBlackLevels != null
        val manualColorOverrideUsed = finalCal?.override?.manualColorMatrix != null

        return RawDomainContract(
            sourceFormat = source,
            width = width,
            height = height,
            nativeBitDepth = sourceBitDepth,
            sourceStorageAlignment = sourceAlignment,
            masterStorageAlignment = RawStorageAlignment.RIGHT_JUSTIFIED,
            nativeBlackLevels = nativeBlack,
            nativeWhiteLevel = nativeWhite,
            payloadBlackLevels = payloadBlack,
            payloadWhiteLevel = payloadWhite,
            developedRawBlackLevels = developedRawBlack,
            developedRawBlackLevelSource = developedRawBlackSource,
            developedBlackMetadataAuthoritative = blackAuthority.metadataAuthoritative,
            developedBlackFallbackReason = blackAuthority.fallbackReason,
            cfaPattern = cfa,
            cfaName = cfaName,
            cfaOriginX = transform.cfaOffsetX,
            cfaOriginY = transform.cfaOffsetY,
            activeArray = activeArray,
            cropRegion = crop,
            preCorrectionArray = preCorrection,
            sourceRowStrideBytes = sourceRowStride,
            sourcePixelStrideBytes = sourcePixelStride,
            masterRowStrideBytes = width * 2,
            masterPixelStrideBytes = 2,
            dynamicBlackLevelUsed = dynamicBlack != null,
            dynamicWhiteLevelUsed = dynamicWhite != null,
            staticBlackLevelUsed = dynamicBlack == null && staticBlackPattern != null,
            staticWhiteLevelUsed = dynamicWhite == null && staticWhite != null,
            manualOverrideUsed = manualBlackFallbackUsed || manualColorOverrideUsed,
            physicalCameraId = physicalCameraId,
            lensId = lensId,
            lensShadingState = lensShadingState,
            sampleTransform = sampleTransform,
            sensorInfoWhiteLevel = staticWhite,
            sensorDynamicWhiteLevel = dynamicWhite,
            sensorBlackLevelPatternValues = staticBlack,
            sensorDynamicBlackLevelValues = dynamicBlack,
            chosenBlackLevelSource = payloadBlackSource,
            chosenWhiteLevelSource = payloadWhiteSource,
            masterStorageScale = 1.0f,
            masterStorageLeftShift = 0,
            masterStorageContract = "RIGHT_JUSTIFIED_PAYLOAD_CODE_VALUES_IN_UINT16",
            validationWarnings = warnings
        )
    }

    private fun resolveLensShadingState(captureResult: CaptureResult?): RawLensShadingState {
        if (captureResult == null) return RawLensShadingState.UNKNOWN
        val mode = runCatching {
            captureResult.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE)
        }.getOrNull()
        val map = runCatching {
            captureResult.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        }.getOrNull()
        return when {
            mode == CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE_OFF ->
                RawLensShadingState.NOT_REQUESTED
            mode == CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE_ON && map != null ->
                RawLensShadingState.REQUESTED_MAP_AVAILABLE
            mode == CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE_ON ->
                RawLensShadingState.REQUESTED_MAP_MISSING
            map != null -> RawLensShadingState.REQUESTED_MAP_AVAILABLE
            else -> RawLensShadingState.UNKNOWN
        }
    }

    private fun String.intValue(key: String): Int? {
        return substringAfter(";$key=", missingDelimiterValue = "")
            .substringBefore(';')
            .takeIf { it.isNotEmpty() }
            ?.toIntOrNull()
    }

    private fun bitDepthForWhite(whiteLevel: Int, minimum: Int): Int {
        var bits = minimum.coerceAtLeast(1)
        val safeWhite = whiteLevel.coerceAtLeast(1).toLong()
        while (bits < 16 && ((1L shl bits) - 1L) < safeWhite) bits++
        return bits
    }
}
