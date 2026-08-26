package com.bncam.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * Universal manager for auxiliary phone illuminant sensors.
 * Performs capability-based discovery across all Android sensors without any
 * manufacturer or device-model checks.
 */
class AuxiliarySensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val adapters: List<AuxiliarySensorAdapter> = listOf(
        StandardCctAdapter(),
        Tcs3411Adapter(),
        GenericRgbcAdapter(),
        ScalarLightAdapter(),
        UnknownVendorAdapter()
    )

    val discoveredSensors: List<Pair<Sensor, AuxiliarySensorCapability>> = discoverSensors()
    val activeSensorPair: Pair<Sensor, AuxiliarySensorAdapter>? = selectBestSensor()

    @Volatile
    private var lastReading: AuxiliaryIlluminantReading = AuxiliaryIlluminantReading()
    @Volatile
    private var listening: Boolean = false

    val selectedSensorName: String? get() = activeSensorPair?.first?.name
    val selectedCapability: AuxiliarySensorCapability
        get() = activeSensorPair?.let { (sensor, adapter) -> adapter.classifyCapability(sensor) }
            ?: AuxiliarySensorCapability.UNSUPPORTED
    val isListening: Boolean get() = listening

    private fun discoverSensors(): List<Pair<Sensor, AuxiliarySensorCapability>> {
        val allSensors = sensorManager?.getSensorList(Sensor.TYPE_ALL) ?: return emptyList()
        return allSensors.map { sensor ->
            val adapter = adapters.first { it.canAdapt(sensor) }
            sensor to adapter.classifyCapability(sensor)
        }
    }

    private fun selectBestSensor(): Pair<Sensor, AuxiliarySensorAdapter>? {
        val allSensors = sensorManager?.getSensorList(Sensor.TYPE_ALL) ?: return null
        var bestPair: Pair<Sensor, AuxiliarySensorAdapter>? = null
        var bestCapability: AuxiliarySensorCapability = AuxiliarySensorCapability.UNSUPPORTED

        for (sensor in allSensors) {
            val adapter = adapters.first { it.canAdapt(sensor) }
            val capability = adapter.classifyCapability(sensor)

            if (isBetterCapability(capability, bestCapability)) {
                bestPair = sensor to adapter
                bestCapability = capability
            }
        }

        if (bestCapability == AuxiliarySensorCapability.UNSUPPORTED ||
            bestCapability == AuxiliarySensorCapability.ILLUMINANCE_ONLY ||
            bestCapability == AuxiliarySensorCapability.UNKNOWN_LAYOUT
        ) {
            Log.i(TAG, "No high-confidence color/CCT sensor found; selected capability: $bestCapability")
        } else {
            Log.i(TAG, "Selected primary auxiliary illuminant sensor: ${bestPair?.first?.name} (capability: $bestCapability)")
        }

        return bestPair
    }

    private fun isBetterCapability(newCap: AuxiliarySensorCapability, currentCap: AuxiliarySensorCapability): Boolean {
        fun rank(cap: AuxiliarySensorCapability): Int = when (cap) {
            AuxiliarySensorCapability.CCT, AuxiliarySensorCapability.CAMERA2_VENDOR_ILLUMINANT -> 5
            AuxiliarySensorCapability.RGBC, AuxiliarySensorCapability.RGBW -> 4
            AuxiliarySensorCapability.RGB, AuxiliarySensorCapability.XYZ, AuxiliarySensorCapability.SPECTRAL -> 3
            AuxiliarySensorCapability.ILLUMINANCE_ONLY -> 1
            AuxiliarySensorCapability.UNKNOWN_LAYOUT -> 0
            AuxiliarySensorCapability.UNSUPPORTED -> -1
        }
        return rank(newCap) > rank(currentCap)
    }

    @Synchronized
    fun startListening() {
        if (listening) return
        val pair = activeSensorPair ?: return
        try {
            val registered = sensorManager?.registerListener(this, pair.first, SensorManager.SENSOR_DELAY_NORMAL) == true
            listening = registered
            if (registered) {
                Log.i(TAG, "Registered listener for auxiliary sensor: ${pair.first.name}")
            } else {
                Log.w(TAG, "Auxiliary sensor registration was rejected: ${pair.first.name}")
            }
        } catch (e: Throwable) {
            listening = false
            Log.e(TAG, "Failed to register auxiliary sensor listener", e)
        }
    }

    @Synchronized
    fun stopListening() {
        if (!listening) return
        try {
            sensorManager?.unregisterListener(this)
            Log.i(TAG, "Unregistered auxiliary sensor listener")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to unregister auxiliary sensor listener", e)
        } finally {
            listening = false
            lastReading = AuxiliaryIlluminantReading()
        }
    }

    fun getLatestReading(captureTimestampNs: Long): AuxiliaryIlluminantReading {
        val current = lastReading
        if (!current.isValid) return AuxiliaryIlluminantReading()
        val deltaMs = Math.abs(captureTimestampNs - current.timestampNs) / 1_000_000L
        if (deltaMs > 250L) {
            return AuxiliaryIlluminantReading()
        }
        return current
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.values.isEmpty()) return
        val pair = activeSensorPair ?: return
        lastReading = pair.second.adaptValues(pair.first.name, event.values, event.timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val TAG = "AuxiliarySensorManager"
    }
}
