package com.bncam.core.quality

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LensCalibrationTelemetrySnapshot(
    val lensId: String,
    val sensorTimestampNs: Long?,
    val cfaName: String,
    val noiseSource: String,
    val noiseValues: List<Double>,
    val noiseFallbackReason: String?,
    val blackLevelSource: String,
    val blackLevels: List<Float>,
    val blackFallbackReason: String?,
    val whiteLevel: Int,
    val whiteLevelSource: String
)

/** In-process read-only telemetry for settings pages; capture ownership and pairing are untouched. */
object LensCalibrationTelemetry {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<LensCalibrationTelemetrySnapshot?>>()

    fun flow(lensId: String): StateFlow<LensCalibrationTelemetrySnapshot?> =
        flows.getOrPut(lensId) { MutableStateFlow(null) }

    fun record(calibration: FinalSensorCalibration) {
        val noiseFallback = calibration.effectiveNoiseProfileFallbackReason
            .takeUnless { it == "None" }
        val blackFallback = calibration.pipelineWarnings.firstOrNull {
            it.contains("black", true) && it.contains("missing", true)
        }
        flows.getOrPut(calibration.base.lensId) { MutableStateFlow(null) }.value =
            LensCalibrationTelemetrySnapshot(
                lensId = calibration.base.lensId,
                sensorTimestampNs = calibration.base.sensorTimestampNs,
                cfaName = calibration.base.cfaName,
                noiseSource = calibration.effectiveNoiseProfileSource,
                noiseValues = calibration.effectiveNoiseProfile?.toList().orEmpty(),
                noiseFallbackReason = noiseFallback,
                blackLevelSource = calibration.effectiveBlackLevelSource,
                blackLevels = calibration.effectiveBlackLevels.toList(),
                blackFallbackReason = blackFallback,
                whiteLevel = calibration.effectiveWhiteLevel,
                whiteLevelSource = calibration.effectiveWhiteLevelSource
            )
    }
}
