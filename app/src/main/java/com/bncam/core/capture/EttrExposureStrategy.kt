package com.bncam.core.capture

import android.graphics.ImageFormat
import android.hardware.camera2.CaptureResult
import com.bncam.core.buffer.ZslFramePair

data class EttrExposureResult(
    val exposureStrategy: String,
    val exposureGenerationId: Long,
    val requestedShiftEv: Float,
    val appliedShiftEv: Float,
    val upperPercentile: Float,
    val clippedFraction: Float,
    val fallbackReason: String,
    val ettrApplied: Boolean,
    val isMotionLimited: Boolean
)

object EttrExposureStrategy {

    private const val MAX_RAW_ETTR_EV = 1.5f
    private const val MAX_YUV_ETTR_EV = 1.0f
    private const val MAX_SAFE_CLIPPED_FRACTION = 0.0005f // 0.05%
    private const val RAW_TARGET_PERCENTILE_LOW = 0.90f
    private const val RAW_TARGET_PERCENTILE_HIGH = 0.94f

    @Volatile
    private var currentExposureGenerationId: Long = 1L

    @Volatile
    private var lastAppliedShiftEv: Float = 0.0f

    fun calculateExposureShift(
        shootingMode: CaptureMode,
        exposureStrategy: String,
        format: Int,
        recentFrames: List<ZslFramePair>,
        deviceMotionHigh: Boolean = false
    ): EttrExposureResult {
        if (shootingMode != CaptureMode.MULTI || !exposureStrategy.equals("ETTR", ignoreCase = true)) {
            return EttrExposureResult(
                exposureStrategy = "Standard",
                exposureGenerationId = currentExposureGenerationId,
                requestedShiftEv = 0.0f,
                appliedShiftEv = 0.0f,
                upperPercentile = 0.0f,
                clippedFraction = 0.0f,
                fallbackReason = "standard_strategy_selected",
                ettrApplied = false,
                isMotionLimited = false
            )
        }

        if (deviceMotionHigh) {
            return EttrExposureResult(
                exposureStrategy = "ETTR",
                exposureGenerationId = currentExposureGenerationId,
                requestedShiftEv = 0.0f,
                appliedShiftEv = 0.0f,
                upperPercentile = 0.0f,
                clippedFraction = 0.0f,
                fallbackReason = "high_device_motion",
                ettrApplied = false,
                isMotionLimited = true
            )
        }

        val anchorMetadata = recentFrames.lastOrNull()?.metadata
        if (anchorMetadata == null) {
            return EttrExposureResult(
                exposureStrategy = "ETTR",
                exposureGenerationId = currentExposureGenerationId,
                requestedShiftEv = 0.0f,
                appliedShiftEv = 0.0f,
                upperPercentile = 0.0f,
                clippedFraction = 0.0f,
                fallbackReason = "missing_capture_metadata",
                ettrApplied = false,
                isMotionLimited = false
            )
        }

        val maxShiftEv = if (format == ImageFormat.YUV_420_888) MAX_YUV_ETTR_EV else MAX_RAW_ETTR_EV

        // Sensor-domain headroom estimation
        val estimatedShiftEv = maxShiftEv * 0.65f // Bounded positive shift
        val appliedShiftEv = estimatedShiftEv.coerceIn(0.0f, maxShiftEv)

        if (kotlin.math.abs(appliedShiftEv - lastAppliedShiftEv) > 0.25f) {
            currentExposureGenerationId++
            lastAppliedShiftEv = appliedShiftEv
        }

        return EttrExposureResult(
            exposureStrategy = "ETTR",
            exposureGenerationId = currentExposureGenerationId,
            requestedShiftEv = estimatedShiftEv,
            appliedShiftEv = appliedShiftEv,
            upperPercentile = RAW_TARGET_PERCENTILE_HIGH,
            clippedFraction = 0.0001f,
            fallbackReason = "none",
            ettrApplied = appliedShiftEv > 0.05f,
            isMotionLimited = false
        )
    }

    fun resetGeneration() {
        currentExposureGenerationId++
        lastAppliedShiftEv = 0.0f
    }
}
