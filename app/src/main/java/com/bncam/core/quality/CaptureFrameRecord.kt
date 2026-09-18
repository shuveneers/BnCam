package com.bncam.core.quality

import android.media.Image

data class ChannelNoiseModel(
    val cfaChannelIndex: Int, // 0..3 for Bayer, 0 for MONO
    val channelName: String,  // "R", "Gr", "Gb", "B" or "MONO"
    val slopeS: Double,       // S >= 0.0
    val offsetO: Double       // O >= 0.0
)

data class NoiseModelRecord(
    val channels: List<ChannelNoiseModel>,
    val source: String,
    val isAuto: Boolean
)

data class CaptureFrameRecord(
    val frameTimestampNs: Long,
    val logicalCameraId: String,
    val physicalCameraId: String?, // Nullable for single-camera or non-physical streams
    val resolvedLensId: String,
    val stableLensKey: String,
    val sensorSensitivityIso: Int,
    val exposureTimeNs: Long,
    val cfaArrangement: CfaArrangementDescriptor,
    val noiseModel: NoiseModelRecord,
    val frameBlackLevels: FloatArray, // Preserved as floating-point channel values
    val frameBlackLevelSource: String,
    /** Same-frame/static Camera2 sensor saturation authority; null when the HAL reports none. */
    val frameWhiteLevel: Int?,
    val frameWhiteLevelSource: String,
    val colorCorrectionGains: FloatArray?,     // From CaptureResult.COLOR_CORRECTION_GAINS
    val colorCorrectionTransform: FloatArray?, // 3x3 array from CaptureResult.COLOR_CORRECTION_TRANSFORM
    val neutralColorPoint: DoubleArray?,       // 3 Double values derived losslessly from Rational
    val image: Image? = null
)
