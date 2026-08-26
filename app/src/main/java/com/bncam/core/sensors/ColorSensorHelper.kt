package com.bncam.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock

/**
 * Convenience wrapper delegating to the universal AuxiliarySensorManager architecture.
 * Preserves backwards compatibility for existing camera session lifecycles.
 */
private object SharedAuxiliarySensorManager {
    @Volatile private var instance: AuxiliarySensorManager? = null

    fun get(context: Context): AuxiliarySensorManager = instance ?: synchronized(this) {
        instance ?: AuxiliarySensorManager(context.applicationContext).also { instance = it }
    }
}

class ColorSensorHelper(context: Context) : SensorEventListener {

    val auxiliaryManager = SharedAuxiliarySensorManager.get(context)

    fun startListening() {
        auxiliaryManager.startListening()
    }

    fun stopListening() {
        auxiliaryManager.stopListening()
    }


    /**
     * Prefer exact per-frame Camera2 illuminant metadata (including physical-camera results from
     * a logical multi-camera HAL), then fall back to the continuously sampled Android sensor.
     */
    fun getBestReading(
        captureResult: TotalCaptureResult?,
        nowElapsedRealtimeNs: Long = SystemClock.elapsedRealtimeNanos()
    ): com.bncam.core.model.ColorSensorReading {
        if (captureResult != null) {
            val vendorCandidates = buildList {
                Camera2VendorIlluminantAdapter.extract(captureResult)?.let(::add)
                runCatching { captureResult.physicalCameraResults.values }.getOrNull()
                    ?.forEach { physicalResult ->
                        Camera2VendorIlluminantAdapter.extract(physicalResult)?.let(::add)
                    }
            }
            vendorCandidates
                .filter { it.isValid }
                .maxByOrNull { it.confidence }
                ?.let { return it.toColorSensorReading() }
        }
        return getLatestReading(nowElapsedRealtimeNs)
    }

    private fun com.bncam.core.sensors.AuxiliaryIlluminantReading.toColorSensorReading(): com.bncam.core.model.ColorSensorReading =
        com.bncam.core.model.ColorSensorReading(
            sourceId = sourceId,
            capability = capabilityType.name,
            timestampNs = timestampNs,
            cctKelvin = cctKelvin ?: 0.0f,
            r = rawValues.getOrElse(0) { 0.0f },
            g = rawValues.getOrElse(1) { 0.0f },
            b = rawValues.getOrElse(2) { 0.0f },
            isValid = isValid
        )

    fun getLatestReading(captureTimestampNs: Long): com.bncam.core.model.ColorSensorReading {
        val normalized = auxiliaryManager.getLatestReading(captureTimestampNs)
        if (!normalized.isValid) return com.bncam.core.model.ColorSensorReading(isValid = false)
        return com.bncam.core.model.ColorSensorReading(
            sourceId = normalized.sourceId,
            capability = normalized.capabilityType.name,
            timestampNs = normalized.timestampNs,
            cctKelvin = normalized.cctKelvin ?: 0.0f,
            r = normalized.rawValues.getOrElse(0) { 0.0f },
            g = normalized.rawValues.getOrElse(1) { 0.0f },
            b = normalized.rawValues.getOrElse(2) { 0.0f },
            isValid = normalized.isValid
        )
    }

    override fun onSensorChanged(event: SensorEvent?) {
        auxiliaryManager.onSensorChanged(event)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        auxiliaryManager.onAccuracyChanged(sensor, accuracy)
    }
}
