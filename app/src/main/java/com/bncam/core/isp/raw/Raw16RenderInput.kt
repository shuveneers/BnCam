package com.bncam.core.isp.raw

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import com.bncam.core.engine.ImageUtils
import com.bncam.core.quality.FinalSensorCalibration
import com.bncam.core.quality.FocusConfidenceState
import com.bncam.core.quality.withSpectraMergeStats
import com.bncam.core.quality.RenderQualityConfig

data class DemosaicAfHints(
    val focusScore: Float = 0f,
    val focusConfidence: Float = 0f,
    val confidenceState: FocusConfidenceState = FocusConfidenceState.INDETERMINATE,
    val afState: Int = CaptureResult.CONTROL_AF_STATE_INACTIVE,
    val lensState: Int = CaptureResult.LENS_STATE_STATIONARY,
    val lensFocusDistance: Float = 0f,
    val focusVelocityDioptersPerSec: Float = 0f,
    val predictiveConfidence: Float = 0f
) {
    val predictiveSignalAvailable: Boolean
        get() = predictiveConfidence > 0f
}

/** The immutable RAW16 contract consumed by the BnCam JPEG renderer. */
interface Raw16RenderInput : AutoCloseable {
    val lensId: String
    val source: RawInputSource
    val nativeRaw16Buffer: NativeRaw16Buffer
    val width: Int
    val height: Int
    val frameCount: Int
    val captureResult: CaptureResult?
    val characteristics: CameraCharacteristics
    val cfaPattern: Int
    val orientationDegrees: Int
    val dngMergeStats: String
    val finalCalibration: FinalSensorCalibration?
    val rawFrameInfo: RawFrameInfo
    val demosaicAfHints: DemosaicAfHints get() = DemosaicAfHints()
    val phoneAssistanceSensorsEnabled: Boolean get() = false
    val colorSensorReading: com.bncam.core.model.ColorSensorReading get() = com.bncam.core.model.ColorSensorReading()
    val colorSensorContributionWeight: Float get() = 0.0f
    val raw16ByteCount: Int get() = nativeRaw16Buffer.byteCount
    val managedMaterializationCount: Int get() = nativeRaw16Buffer.managedMaterializationCount
    val managedMaterializationBytes: Long get() = nativeRaw16Buffer.managedMaterializationBytes
    fun materializeRaw16ForDng(): ByteArray = nativeRaw16Buffer.materializeForDng()
    override fun close() = nativeRaw16Buffer.close()
}

/**
 * A single selected RAW frame unpacked into the renderer/DNG RAW16 domain. It deliberately has
 * no alignment, accumulation, support-frame selection, or Master RAW16 merge state.
 */
class SingleRaw16Frame(
    override val lensId: String,
    override val source: RawInputSource,
    override val nativeRaw16Buffer: NativeRaw16Buffer,
    override val width: Int,
    override val height: Int,
    override val captureResult: CaptureResult?,
    override val characteristics: CameraCharacteristics,
    override val cfaPattern: Int,
    override val orientationDegrees: Int,
    override val dngMergeStats: String,
    override val finalCalibration: FinalSensorCalibration?,
    override val rawFrameInfo: RawFrameInfo,
    override val demosaicAfHints: DemosaicAfHints = DemosaicAfHints(),
    override val phoneAssistanceSensorsEnabled: Boolean = false,
    override val colorSensorReading: com.bncam.core.model.ColorSensorReading = com.bncam.core.model.ColorSensorReading(),
    override val colorSensorContributionWeight: Float = 0.0f
) : Raw16RenderInput {
    override val frameCount: Int = 1
}

object SingleRaw16FrameBuilder {
    fun build(
        lensId: String,
        buffer: HardwareBuffer,
        observerBuffer: HardwareBuffer? = null,
        sourceFormat: Int,
        width: Int,
        height: Int,
        characteristics: CameraCharacteristics,
        captureResult: CaptureResult?,
        qualityConfig: RenderQualityConfig,
        orientationDegrees: Int,
        demosaicAfHints: DemosaicAfHints = DemosaicAfHints(),
        phoneAssistanceSensorsEnabled: Boolean = false,
        colorSensorReading: com.bncam.core.model.ColorSensorReading = com.bncam.core.model.ColorSensorReading(),
        colorSensorContributionWeight: Float = 0.0f
    ): SingleRaw16Frame? {
        require(sourceFormat == ImageFormat.RAW10 || sourceFormat == ImageFormat.RAW_SENSOR) {
            "SingleRaw16Frame only accepts RAW10 or RAW_SENSOR."
        }
        val initialContract = RawDomainContractResolver.resolve(
            lensId = lensId,
            sourceFormat = sourceFormat,
            width = width,
            height = height,
            characteristics = characteristics,
            captureResult = captureResult,
            qualityConfig = qualityConfig
        )
        val sourceBuffers = if (observerBuffer != null && observerBuffer !== buffer) {
            arrayOf(observerBuffer, buffer) // anchor must remain last
        } else {
            arrayOf(buffer)
        }
        val nativeRaw16Buffer = when (sourceFormat) {
            ImageFormat.RAW10 -> ImageUtils.mergeRaw10NativeRaw16Safe(
                lensId = lensId,
                buffers = sourceBuffers,
                characteristics = characteristics,
                payloadWhiteLevel = initialContract.payloadWhiteLevel,
                payloadBlackLevels = initialContract.payloadBlackLevelsIntArray(),
                maxFramesCap = sourceBuffers.size,
                maxShiftPixels = if (sourceBuffers.size > 1) 32 else 0,
                alignmentStrictness = 0.9f,
                finalCalibration = qualityConfig.finalCalibration,
                fuseSupportFrames = false
            )
            ImageFormat.RAW_SENSOR -> ImageUtils.mergeRawSensorNativeRaw16Safe(
                lensId = lensId,
                buffers = sourceBuffers,
                characteristics = characteristics,
                payloadWhiteLevel = initialContract.payloadWhiteLevel,
                payloadBlackLevels = initialContract.payloadBlackLevelsIntArray(),
                maxFramesCap = sourceBuffers.size,
                maxShiftPixels = if (sourceBuffers.size > 1) 32 else 0,
                alignmentStrictness = 0.9f,
                finalCalibration = qualityConfig.finalCalibration,
                fuseSupportFrames = false
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
            val stats = ImageUtils.lastDngMergeStats()
            val spectraCalibration = qualityConfig.finalCalibration?.withSpectraMergeStats(stats)
            val finalContract = RawDomainContractResolver.resolve(
                lensId = lensId,
                sourceFormat = sourceFormat,
                width = outputWidth,
                height = outputHeight,
                characteristics = characteristics,
                captureResult = captureResult,
                qualityConfig = qualityConfig,
                dngMergeStats = stats
            )
            SingleRaw16Frame(
                lensId = lensId,
                source = if (sourceFormat == ImageFormat.RAW10) RawInputSource.RAW10 else RawInputSource.RAW_SENSOR,
                nativeRaw16Buffer = nativeRaw16Buffer,
                width = outputWidth,
                height = outputHeight,
                captureResult = captureResult,
                characteristics = characteristics,
                cfaPattern = qualityConfig.cfaPattern,
                orientationDegrees = orientationDegrees,
                dngMergeStats = stats,
                finalCalibration = spectraCalibration,
                rawFrameInfo = finalContract,
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
