package com.bncam.core.isp.raw

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import com.bncam.core.engine.ImageUtils
import com.bncam.core.quality.RenderQualityConfig
import com.bncam.core.quality.FinalSensorCalibration
import com.bncam.core.quality.withPhysicalCaptureIdentity
import com.bncam.core.quality.withPhysicalNoiseAuthority
import com.bncam.core.quality.withPhysicalMergeStats
import com.bncam.core.quality.withSpectraNoiseAdapter

class MasterRawFrame(
    override val lensId: String,
    override val source: RawInputSource,
    val rawInputFormat: String,
    override val nativeRaw16Buffer: NativeRaw16Buffer,
    override val width: Int,
    override val height: Int,
    override val frameCount: Int,
    override val captureResult: CaptureResult?,
    override val characteristics: CameraCharacteristics,
    override val cfaPattern: Int,
    val cfaName: String,
    val cfaSource: String,
    val blackLevels: IntArray,
    val blackLevelSource: String,
    val whiteLevel: Int,
    val whiteLevelSource: String,
    val whiteBalanceSource: String,
    val colorMatrixSource: String,
    val colorMatrixApplied: Boolean,
    val colorMatrixRejectReason: String,
    val identityMatrixFallbackUsed: Boolean,
    override val orientationDegrees: Int,
    val dngExportRequested: Boolean,
    override val dngMergeStats: String,
    override val finalCalibration: FinalSensorCalibration? = null,
    override val rawFrameInfo: RawFrameInfo,
    override val knownHotPixelMap: RawMappedHotPixelMap = RawMappedHotPixelMap.EMPTY,
    override val demosaicAfHints: DemosaicAfHints = DemosaicAfHints(),
    override val phoneAssistanceSensorsEnabled: Boolean = false,
    override val colorSensorReading: com.bncam.core.model.ColorSensorReading = com.bncam.core.model.ColorSensorReading(),
    override val colorSensorContributionWeight: Float = 0.0f
) : Raw16RenderInput {
    val sampleScaleContract: String = "ORIGINAL_MASTER_RAW16_SENSOR_CODE_VALUES"

    fun computeSanityStats(): String {
        if (nativeRaw16Buffer.isClosed) return "released_buffer"
        return try {
            nativeRaw16Buffer.withDirectBuffer { directBuffer ->
                val buffer = directBuffer.duplicate()
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                val capacity = buffer.capacity()
                var step = kotlin.math.max(1, capacity / 20000)
                if (step > 1 && step % 2 == 0) step++
                val samples = IntArray(20000)
                var count = 0
                var minVal = 65535
                var maxVal = 0
                var overWhite = 0
                val cfaSamples = IntArray(4)
                val cfaBlackClipped = IntArray(4)
                val cfaWhiteClipped = IntArray(4)
                var i = 0

                while (i < capacity && count < samples.size) {
                    val v = buffer.get(i).toInt() and 0xFFFF
                    val x = i % width
                    val y = i / width
                    val cfaIndex = (((y + rawFrameInfo.cfaOriginY) and 1) shl 1) or
                        ((x + rawFrameInfo.cfaOriginX) and 1)
                    samples[count++] = v
                    cfaSamples[cfaIndex]++
                    if (v <= rawFrameInfo.payloadBlackLevels[cfaIndex]) cfaBlackClipped[cfaIndex]++
                    if (v >= rawFrameInfo.payloadWhiteLevel) cfaWhiteClipped[cfaIndex]++
                    if (v < minVal) minVal = v
                    if (v > maxVal) maxVal = v
                    if (v > whiteLevel) overWhite++
                    i += step
                }

                if (count == 0) return@withDirectBuffer "no_samples"
                val validSamples = samples.copyOf(count).apply { sort() }
                val p1 = validSamples[(count * 0.01).toInt().coerceAtMost(count - 1)]
                val p50 = validSamples[(count * 0.50).toInt().coerceAtMost(count - 1)]
                val p95 = validSamples[(count * 0.95).toInt().coerceAtMost(count - 1)]
                val p99 = validSamples[(count * 0.99).toInt().coerceAtMost(count - 1)]
                val overWhitePct = (overWhite * 100.0) / count
                fun percentages(values: IntArray): String =
                    values.indices.joinToString(prefix = "[", postfix = "]") { index ->
                        val pct = values[index] * 100.0 / cfaSamples[index].coerceAtLeast(1)
                        String.format(java.util.Locale.US, "%.3f", pct)
                    }

                "min=$minVal;p1=$p1;p50=$p50;p95=$p95;p99=$p99;max=$maxVal;" +
                    "overWhitePct=${String.format(java.util.Locale.US, "%.3f", overWhitePct)}%;" +
                    "cfaBlackClippedPct=${percentages(cfaBlackClipped)};" +
                    "cfaWhiteClippedPct=${percentages(cfaWhiteClipped)}"
            }
        } catch (e: Exception) {
            "sanity_check_failed:${e.message}"
        }
    }

    private fun captureExposureDebugPairs(): List<Pair<String, String>> {
        val exposureNs = captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val sensitivity = captureResult?.get(CaptureResult.SENSOR_SENSITIVITY)
        val frameDurationNs = captureResult?.get(CaptureResult.SENSOR_FRAME_DURATION)
        val aeState = captureResult?.get(CaptureResult.CONTROL_AE_STATE)
        val wbState = captureResult?.get(CaptureResult.CONTROL_AWB_STATE)
        return listOf(
            "Capture Exposure Time Ns" to (exposureNs?.toString() ?: "missing"),
            "Capture ISO/Sensitivity" to (sensitivity?.toString() ?: "missing"),
            "Capture Frame Duration Ns" to (frameDurationNs?.toString() ?: "missing"),
            "Capture AE State" to (aeState?.toString() ?: "missing"),
            "Capture AWB State" to (wbState?.toString() ?: "missing")
        )
    }

    fun debugPairs(): List<Pair<String, String>> {
        val pairs = mutableListOf(
            "Sample Scale Contract" to sampleScaleContract,
            "Master Sanity Check" to computeSanityStats(),
            "Raw Input Source" to source.name,
            "Raw Input Frames Accepted" to frameCount.toString(),
            "Raw Input Format" to rawInputFormat,
            "Canonical Master Created" to "true",
            "Master Type" to "OriginalMasterRaw16 / SensorMasterRaw16",
            "Master Width/Height" to "$width x $height",
            "Master CFA Pattern" to "$cfaPattern / $cfaName",
            "Master CFA Source" to cfaSource,
            "Master Black Level Source" to blackLevelSource,
            "Master Black Levels" to blackLevels.joinToString(prefix = "[", postfix = "]"),
            "Master White Level Source" to whiteLevelSource,
            "Master White Level" to whiteLevel.toString(),
            "Master WB Source" to whiteBalanceSource,
            "Master Color Matrix Source" to colorMatrixSource,
            "Master Color Matrix Applied" to colorMatrixApplied.toString(),
            "Master Color Matrix Reject Reason" to colorMatrixRejectReason,
            "Identity Matrix Fallback Used" to identityMatrixFallbackUsed.toString(),
            "DNG Export Requested" to dngExportRequested.toString(),
            "Optional DNG Export Data" to "explicit one-time materialization from native RAW16; never normalized or tone mapped",
            "JPEG Render Source" to "read-only native OriginalMasterRaw16 direct buffer",
            "JPEG Working Layer" to "JpegWorkingLinearRaw / LinearFloatRaw",
            "JPEG Render Path" to "RAW_DOMAIN_NORMALIZE -> DEMOSAIC -> WB -> CCM -> TONE -> sRGB",
            "RawDomainContract Dump" to rawFrameInfo.dump(),
            "RawDomainContract Source Format" to rawFrameInfo.sourceFormat.name,
            "RawDomainContract Native BL/WL" to "${rawFrameInfo.nativeBlackLevels} / ${rawFrameInfo.nativeWhiteLevel}",
            "RawDomainContract Payload BL/WL" to "${rawFrameInfo.payloadBlackLevels} / ${rawFrameInfo.payloadWhiteLevel}",
            "RawDomainContract Storage Alignment" to "${rawFrameInfo.sourceStorageAlignment} -> ${rawFrameInfo.masterStorageAlignment}",
            "RawDomainContract Sample Transform" to rawFrameInfo.sampleTransform.name,
            "RawDomainContract Lens Shading State" to rawFrameInfo.lensShadingState.name,
            "RawDomainContract Metadata Sources" to "dynamicBL=${rawFrameInfo.dynamicBlackLevelUsed};dynamicWL=${rawFrameInfo.dynamicWhiteLevelUsed};staticBL=${rawFrameInfo.staticBlackLevelUsed};staticWL=${rawFrameInfo.staticWhiteLevelUsed};manual=${rawFrameInfo.manualOverrideUsed}",
            "RawDomainContract Physical Camera ID" to (rawFrameInfo.physicalCameraId ?: "not_reported"),
            "RawDomainContract Validation Warnings" to rawFrameInfo.validationWarnings.joinToString(";").ifBlank { "none" },
            "RAW Source Row/Pixel Stride" to "${rawFrameInfo.sourceRowStrideBytes} / ${rawFrameInfo.sourcePixelStrideBytes}",
            "RAW Source Bit Depth/Range" to "${rawFrameInfo.sourceBitDepth} / ${rawFrameInfo.effectiveSourceRange}",
            "Master Storage Scale/Shift" to "${rawFrameInfo.masterStorageScale} / ${rawFrameInfo.masterStorageLeftShift}",
            "Master Storage Contract" to rawFrameInfo.masterStorageContract,
            "JPEG Effective White (Master Units)" to rawFrameInfo.effectiveWhiteLevelInMasterUnits.toString(),
            "JPEG Developed Black Canonical R/Gr/Gb/B (Master Units)" to rawFrameInfo.developedRawBlackLevelsCanonicalInMasterUnits.joinToString(prefix = "[", postfix = "]"),
            "JPEG Effective Black Pattern 00/10/01/11 (Master Units)" to rawFrameInfo.effectiveBlackLevelPatternInMasterUnits.joinToString(prefix = "[", postfix = "]"),
            "DNG Path Receives RawDomainInfo" to "true",
            "Final JPEG Rotation" to orientationDegrees.toString(),
            "Master Raw16 Bytes" to raw16ByteCount.toString(),
            "Known Hot Pixel Map" to knownHotPixelMap.debugSummary(),
            "DNG Merge Stats" to dngMergeStats
        )

        if (finalCalibration != null) {
            pairs.add("--- SENSOR CALIBRATION (V2) ---" to "---------------------------")
            finalCalibration.debugPairs().forEach { (key, value) ->
                pairs.add("[CAL] $key" to value)
            }
            finalCalibration.noiseSnapshot?.physicalNoiseState()?.tracePairs()?.forEach { (key, value) ->
                pairs.add("[PHYSICAL NOISE] $key" to value)
            }

            // Keep a few legacy keys for existing summary readers, but source them from the central resolver.
            pairs.add("[BASE] Hardware White Level" to "${finalCalibration.base.baseWhiteLevel} (${finalCalibration.base.baseWhiteLevelSource})")
            pairs.add("[BASE] Hardware Black Levels" to finalCalibration.base.baseBlackLevels.joinToString(", "))
            pairs.add("[BASE] Raw Input Domain" to finalCalibration.rawInputDomain.name)
            pairs.add("[OVERRIDE] UI Black Level Mode" to finalCalibration.override.blackLevelMode)
            pairs.add("[OVERRIDE] UI Manual Black Levels" to (finalCalibration.override.manualBlackLevels?.joinToString(", ") ?: "none"))
            pairs.add("[OVERRIDE] UI Color Matrix Mode" to finalCalibration.override.colorMode)
            pairs.add("[FINAL] ISP Contract White Level" to finalCalibration.effectiveWhiteLevel.toString())
            pairs.add("[FINAL] ISP Contract Black Levels" to finalCalibration.effectiveBlackLevels.joinToString(", "))
            pairs.add("[FINAL] ISP C++ Working Domain" to finalCalibration.ispWorkingDomain.name)
        } else {
            pairs.add("--- SENSOR CALIBRATION (V2) ---" to "not resolved")
        }

        return pairs + captureExposureDebugPairs()
    }

    fun warnings(): List<String> {
        val result = mutableListOf<String>()
        val sanity = computeSanityStats()

        if (sanity.contains("overWhitePct")) {
            val pctString = sanity.substringAfter("overWhitePct=").substringBefore("%")
            val pct = pctString.toDoubleOrNull() ?: 0.0
            if (pct > 25.0) {
                result.add("SEVERE_MISMATCH: $pct% of pixels exceed WhiteLevel ($whiteLevel). Master RAW scale violates SENSOR_CODE_VALUES contract!")
            }
        }

        if (!colorMatrixApplied) {
            result.add("color_matrix_identity_fallback_used: $colorMatrixRejectReason")
        }
        return result
    }
}

object RawMasterBuilder {
    fun build(
        lensId: String,
        buffers: Array<android.hardware.HardwareBuffer>,
        sourceFormat: Int,
        width: Int,
        height: Int,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult?,
        qualityConfig: RenderQualityConfig,
        dngExportRequested: Boolean,
        orientationDegrees: Int,
        maxFramesCap: Int,
        maxShiftPixels: Int,
        alignmentStrictness: Float,
        exposureScaleToAnchor: FloatArray = FloatArray(buffers.size) { 1f },
        computationalHdr: Boolean = false,
        demosaicAfHints: DemosaicAfHints = DemosaicAfHints(),
        phoneAssistanceSensorsEnabled: Boolean = false,
        colorSensorReading: com.bncam.core.model.ColorSensorReading = com.bncam.core.model.ColorSensorReading(),
        colorSensorContributionWeight: Float = 0.0f
    ): MasterRawFrame? {
        val selectedCap = maxFramesCap.coerceAtLeast(1)
        val shutterCalibration = qualityConfig.finalCalibration?.withPhysicalCaptureIdentity(
            sourceFormat = RenderQualityConfig.formatLabel(sourceFormat),
            captureIso = captureResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
            exposureTimeNs = captureResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
            postRawSensitivityBoost = captureResult?.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST),
            cfaPattern = qualityConfig.cfaPattern
        )?.withPhysicalNoiseAuthority()
            ?.withSpectraNoiseAdapter()
        val initialContract = RawDomainContractResolver.resolve(
            lensId = lensId,
            sourceFormat = sourceFormat,
            width = width,
            height = height,
            characteristics = characteristics,
            captureResult = captureResult,
            qualityConfig = qualityConfig
        )
        val nativeRaw16Buffer = when (sourceFormat) {
            ImageFormat.RAW10 -> ImageUtils.mergeRaw10NativeRaw16Safe(
                lensId = lensId,
                buffers = buffers,
                characteristics = characteristics,
                payloadWhiteLevel = initialContract.payloadWhiteLevel,
                payloadBlackLevels = initialContract.payloadBlackLevelsIntArray(),
                maxFramesCap = selectedCap,
                maxShiftPixels = maxShiftPixels,
                alignmentStrictness = alignmentStrictness,
                finalCalibration = shutterCalibration,
                exposureScaleToAnchor = exposureScaleToAnchor,
                computationalHdr = computationalHdr
            )
            ImageFormat.RAW_SENSOR -> ImageUtils.mergeRawSensorNativeRaw16Safe(
                lensId = lensId,
                buffers = buffers,
                characteristics = characteristics,
                payloadWhiteLevel = initialContract.payloadWhiteLevel,
                payloadBlackLevels = initialContract.payloadBlackLevelsIntArray(),
                maxFramesCap = selectedCap,
                maxShiftPixels = maxShiftPixels,
                alignmentStrictness = alignmentStrictness,
                finalCalibration = shutterCalibration,
                exposureScaleToAnchor = exposureScaleToAnchor,
                computationalHdr = computationalHdr
            )
            else -> null
        } ?: return null
        val outputWidth = nativeRaw16Buffer.width
        val outputHeight = nativeRaw16Buffer.height
        val expectedByteCount = outputWidth.toLong() * outputHeight.toLong() * 2L
        if (expectedByteCount <= 0L || expectedByteCount > Int.MAX_VALUE ||
            nativeRaw16Buffer.byteCount.toLong() != expectedByteCount
        ) {
            nativeRaw16Buffer.close()
            return null
        }

        return try {
            val dngMergeStats = ImageUtils.lastDngMergeStats()
            val physicalNoiseCalibration = shutterCalibration
                ?.withPhysicalMergeStats(dngMergeStats)

            val source = when (sourceFormat) {
                ImageFormat.RAW10 -> RawInputSource.RAW10
                ImageFormat.RAW_SENSOR -> RawInputSource.RAW_SENSOR
                else -> error("RawMasterBuilder only accepts RAW10 or RAW_SENSOR")
            }

            val rawDomainContract = RawDomainContractResolver.resolve(
                lensId = lensId,
                sourceFormat = sourceFormat,
                width = outputWidth,
                height = outputHeight,
                characteristics = characteristics,
                captureResult = captureResult,
                qualityConfig = qualityConfig,
                dngMergeStats = dngMergeStats
            )
            val payloadBlackLevels = rawDomainContract.payloadBlackLevelsIntArray()
            val hotPixelMap = RawHotPixelMapMapper.fromCamera2(
                points = runCatching {
                    captureResult?.get(CaptureResult.STATISTICS_HOT_PIXEL_MAP)
                }.getOrNull(),
                characteristics = characteristics,
                sourceWidth = width,
                sourceHeight = height,
                sourceCropLeft = nativeRaw16Buffer.sourceCropLeft,
                sourceCropTop = nativeRaw16Buffer.sourceCropTop,
                outputWidth = outputWidth,
                outputHeight = outputHeight
            )

            MasterRawFrame(
                lensId = lensId,
                source = source,
                rawInputFormat = RenderQualityConfig.formatLabel(sourceFormat),
                nativeRaw16Buffer = nativeRaw16Buffer,
                width = outputWidth,
                height = outputHeight,
                frameCount = buffers.size.coerceAtMost(selectedCap),
                captureResult = captureResult,
                characteristics = characteristics,
                cfaPattern = qualityConfig.cfaPattern,
                cfaName = qualityConfig.cfaName,
                cfaSource = qualityConfig.cfaSource,
                blackLevels = payloadBlackLevels,
                blackLevelSource = rawDomainContract.chosenBlackLevelSource,
                whiteLevel = rawDomainContract.payloadWhiteLevel,
                whiteLevelSource = rawDomainContract.chosenWhiteLevelSource,
                whiteBalanceSource = qualityConfig.whiteBalanceGains.source,
                colorMatrixSource = qualityConfig.colorCorrectionMatrix.source,
                colorMatrixApplied = qualityConfig.colorCorrectionMatrix.fromMetadata,
                colorMatrixRejectReason = qualityConfig.colorCorrectionMatrix.rejectReason,
                identityMatrixFallbackUsed = qualityConfig.colorCorrectionMatrix.identityFallbackUsed,
                orientationDegrees = orientationDegrees,
                dngExportRequested = dngExportRequested,
                dngMergeStats = dngMergeStats,
                finalCalibration = physicalNoiseCalibration,
                rawFrameInfo = rawDomainContract,
                knownHotPixelMap = hotPixelMap,
                demosaicAfHints = demosaicAfHints,
                phoneAssistanceSensorsEnabled = phoneAssistanceSensorsEnabled,
                colorSensorReading = colorSensorReading,
                colorSensorContributionWeight = colorSensorContributionWeight
            )
        } catch (failure: Throwable) {
            nativeRaw16Buffer.close()
            throw failure
        }
    }
}
