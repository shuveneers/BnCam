package com.bncam.core.capture

import android.hardware.camera2.CameraCharacteristics

/**
 * Dynamic sensor profiling for adaptive computational exposure allocation.
 *
 * Derives 35mm-equivalent focal length and a conservative lens/FOV fallback ceiling from
 * CameraCharacteristics. OIS capability is retained as sensor truth, but no unadvertised number of
 * "OIS stops" is fabricated: Camera2 exposes OIS modes, not a standardized stabilization rating.
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
        private const val MIN_HANDHELD_SHUTTER_NS = 10_000_000L // conservative fallback floor: 1/100s
        private const val MAX_HANDHELD_SHUTTER_NS = 66_666_667L // conservative fallback ceiling: 1/15s
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        fun compute(
            sensorWidthMm: Float?,
            sensorHeightMm: Float?,
            nativeFocalLengthMm: Float?,
            hasOis: Boolean,
            minIso: Int = 100,
            maxIso: Int = 6400,
            minShutterNs: Long = 100_000L,
            maxShutterNs: Long = 1_000_000_000L,
            maxAnalogSensitivityIso: Int? = null
        ): DynamicSensorProfile {
            val diagMm = if (
                sensorWidthMm != null && sensorHeightMm != null &&
                sensorWidthMm > 0f && sensorHeightMm > 0f
            ) {
                Math.hypot(sensorWidthMm.toDouble(), sensorHeightMm.toDouble()).toFloat()
            } else {
                DEFAULT_SENSOR_DIAGONAL_MM
            }

            val cropFactor = STANDARD_35MM_DIAGONAL_MM / diagMm.coerceAtLeast(1.0f)
            val focalMm = nativeFocalLengthMm ?: DEFAULT_FOCAL_LENGTH_MM
            val focal35mmEq = focalMm * cropFactor

            // Lens/FOV prior only. Real camera/subject motion, when available, owns the actual
            // shutter ceiling. Do not multiply this by a guessed OIS stop count.
            val ruleOfThumbSec = 1.0 / focal35mmEq.coerceAtLeast(MIN_FOCAL_35MM_EQ).toDouble()
            val rawNs = (ruleOfThumbSec * NANOS_PER_SECOND).toLong()
            val maxHandheldShutterNs = rawNs.coerceIn(
                MIN_HANDHELD_SHUTTER_NS,
                MAX_HANDHELD_SHUTTER_NS
            )

            // If Camera2 does not expose SENSOR_MAX_ANALOG_SENSITIVITY, do not invent an ISO
            // threshold such as 1600. Unknown means only that the analog/digital split is unknown.
            val analogLimit = (maxAnalogSensitivityIso ?: maxIso).coerceIn(minIso, maxIso)

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
            val maxAnalogSensitivity = characteristics.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY)

            return compute(
                sensorWidthMm = sensorSize?.width,
                sensorHeightMm = sensorSize?.height,
                nativeFocalLengthMm = focalLengths?.firstOrNull(),
                hasOis = hasOis,
                minIso = isoRange?.lower ?: 100,
                maxIso = isoRange?.upper ?: 6400,
                minShutterNs = exposureRange?.lower ?: 100_000L,
                maxShutterNs = exposureRange?.upper ?: 1_000_000_000L,
                maxAnalogSensitivityIso = maxAnalogSensitivity
            )
        }
    }
}
