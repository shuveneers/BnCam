package com.bncam.core.capture

import android.hardware.camera2.CameraCharacteristics

/**
 * Dynamic sensor profiling for adaptive computational exposure allocation.
 *
 * Derives 35mm-equivalent focal length, OIS efficiency, and dynamic handshake
 * ceilings at runtime directly from CameraCharacteristics without hardcoded sensor parameters.
 */
data class DynamicSensorProfile(
    val focalLength35mmEq: Float,
    val hasOis: Boolean,
    val maxHandheldShutterNs: Long,
    val minShutterNs: Long,
    val maxShutterNs: Long,
    val minIso: Int,
    val maxIso: Int,
    val analogGainLimitIso: Int
) {
    companion object {
        private const val STANDARD_35MM_DIAGONAL_MM = 43.27f
        private const val DEFAULT_SENSOR_DIAGONAL_MM = 6.0f
        private const val DEFAULT_FOCAL_LENGTH_MM = 4.5f
        private const val MIN_FOCAL_35MM_EQ = 14.0f
        private const val OIS_DYNAMIC_EFFICIENCY_STOPS = 2.0f // 2-stop dynamic bonus for OIS = 4.0x
        private const val MIN_HANDHELD_SHUTTER_NS = 10_000_000L // 1/100s floor
        private const val MAX_HANDHELD_SHUTTER_NS = 66_666_667L // 1/15s ceiling
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        fun compute(
            sensorWidthMm: Float?,
            sensorHeightMm: Float?,
            nativeFocalLengthMm: Float?,
            hasOis: Boolean,
            minIso: Int = 100,
            maxIso: Int = 6400,
            minShutterNs: Long = 100_000L,
            maxShutterNs: Long = 1_000_000_000L
        ): DynamicSensorProfile {
            val diagMm = if (sensorWidthMm != null && sensorHeightMm != null && sensorWidthMm > 0f && sensorHeightMm > 0f) {
                Math.hypot(sensorWidthMm.toDouble(), sensorHeightMm.toDouble()).toFloat()
            } else {
                DEFAULT_SENSOR_DIAGONAL_MM
            }

            val cropFactor = STANDARD_35MM_DIAGONAL_MM / diagMm.coerceAtLeast(1.0f)
            val focalMm = nativeFocalLengthMm ?: DEFAULT_FOCAL_LENGTH_MM
            val focal35mmEq = focalMm * cropFactor

            // Handshake safety ceiling based on 1/focal rule + OIS bonus
            val ruleOfThumbSec = 1.0 / focal35mmEq.coerceAtLeast(MIN_FOCAL_35MM_EQ).toDouble()
            val oisMultiplier = if (hasOis) Math.pow(2.0, OIS_DYNAMIC_EFFICIENCY_STOPS.toDouble()) else 1.0
            val safeSec = ruleOfThumbSec * oisMultiplier
            val rawNs = (safeSec * NANOS_PER_SECOND).toLong()
            val maxHandheldShutterNs = rawNs.coerceIn(MIN_HANDHELD_SHUTTER_NS, MAX_HANDHELD_SHUTTER_NS)

            val analogLimit = minOf(maxIso, 1600)

            return DynamicSensorProfile(
                focalLength35mmEq = focal35mmEq,
                hasOis = hasOis,
                maxHandheldShutterNs = maxHandheldShutterNs,
                minShutterNs = minShutterNs,
                maxShutterNs = maxShutterNs,
                minIso = minIso,
                maxIso = maxIso,
                analogGainLimitIso = analogLimit
            )
        }

        fun fromCharacteristics(characteristics: CameraCharacteristics): DynamicSensorProfile {
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            val hasOis = oisModes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

            val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

            return compute(
                sensorWidthMm = sensorSize?.width,
                sensorHeightMm = sensorSize?.height,
                nativeFocalLengthMm = focalLengths?.firstOrNull(),
                hasOis = hasOis,
                minIso = isoRange?.lower ?: 100,
                maxIso = isoRange?.upper ?: 6400,
                minShutterNs = exposureRange?.lower ?: 100_000L,
                maxShutterNs = exposureRange?.upper ?: 1_000_000_000L
            )
        }
    }
}
