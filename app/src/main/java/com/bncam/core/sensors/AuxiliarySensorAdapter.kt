package com.bncam.core.sensors

import android.hardware.Sensor
import android.hardware.camera2.CaptureResult
import java.util.Locale
import kotlin.math.max

interface AuxiliarySensorAdapter {
    fun canAdapt(sensor: Sensor?): Boolean
    fun classifyCapability(sensor: Sensor?): AuxiliarySensorCapability
    fun adaptValues(sensorName: String, values: FloatArray, timestampNs: Long): AuxiliaryIlluminantReading
}

/**
 * Standard CCT adapter for sensors explicitly reporting color temperature / CCT in Kelvin.
 */
class StandardCctAdapter : AuxiliarySensorAdapter {
    override fun canAdapt(sensor: Sensor?): Boolean {
        if (sensor == null) return false
        val name = sensor.name.lowercase(Locale.US)
        val stringType = sensor.stringType?.lowercase(Locale.US).orEmpty()
        return stringType.contains("color_temperature") ||
                stringType.contains("cct") ||
                (name.contains("cct") && !name.contains("tcs"))
    }

    override fun classifyCapability(sensor: Sensor?): AuxiliarySensorCapability =
        AuxiliarySensorCapability.CCT

    override fun adaptValues(sensorName: String, values: FloatArray, timestampNs: Long): AuxiliaryIlluminantReading {
        if (values.isEmpty()) return AuxiliaryIlluminantReading(sourceId = sensorName, capabilityType = AuxiliarySensorCapability.UNSUPPORTED)
        val cct = values.firstOrNull { it >= 1500.0f && it <= 12000.0f } ?: return AuxiliaryIlluminantReading(
            sourceId = sensorName,
            capabilityType = AuxiliarySensorCapability.CCT,
            timestampNs = timestampNs,
            confidence = 0.0f
        )
        return AuxiliaryIlluminantReading(
            sourceId = sensorName,
            capabilityType = AuxiliarySensorCapability.CCT,
            timestampNs = timestampNs,
            cctKelvin = cct,
            confidence = 0.90f,
            stable = true,
            obstructed = false,
            rawValues = values.copyOf()
        )
    }
}

/**
 * Adapter for TCS3411-compatible RGBC+CCT sensors.
 * Validates vendor/stringType/name, event size (>= 5), value bounds, and extracts CCT.
 */
class Tcs3411Adapter : AuxiliarySensorAdapter {
    override fun canAdapt(sensor: Sensor?): Boolean {
        if (sensor == null) return false
        val name = sensor.name.lowercase(Locale.US)
        val stringType = sensor.stringType?.lowercase(Locale.US).orEmpty()
        val vendor = sensor.vendor?.lowercase(Locale.US).orEmpty()
        return name.contains("tcs3411") || name.contains("tcs") ||
                stringType.contains("tcs") || vendor.contains("ams") || vendor.contains("osram")
    }

    override fun classifyCapability(sensor: Sensor?): AuxiliarySensorCapability =
        AuxiliarySensorCapability.RGBC

    override fun adaptValues(sensorName: String, values: FloatArray, timestampNs: Long): AuxiliaryIlluminantReading {
        if (values.size < 5) {
            return AuxiliaryIlluminantReading(
                sourceId = sensorName,
                capabilityType = AuxiliarySensorCapability.UNKNOWN_LAYOUT,
                timestampNs = timestampNs,
                confidence = 0.0f
            )
        }
        val r = values[0]
        val g = values[1]
        val b = values[2]
        val clear = values[3]
        val cctCandidate = values[4]

        val cct = if (cctCandidate >= 1500.0f && cctCandidate <= 12000.0f) {
            cctCandidate
        } else {
            estimateCctFromRgb(r, g, b)
        }

        val isValidRange = cct != null && (r > 0.0f || g > 0.0f || b > 0.0f || clear > 0.0f)
        return AuxiliaryIlluminantReading(
            sourceId = sensorName,
            capabilityType = AuxiliarySensorCapability.RGBC,
            timestampNs = timestampNs,
            cctKelvin = if (isValidRange) cct else null,
            illuminanceLux = if (clear > 0.0f) clear else null,
            confidence = if (isValidRange) 0.95f else 0.0f,
            stable = true,
            obstructed = false,
            rawValues = values.copyOf()
        )
    }

    private fun estimateCctFromRgb(r: Float, g: Float, b: Float): Float? {
        val sum = r + g + b
        if (sum <= 1.0e-5f) return null
        val x = r / sum
        val y = g / sum
        val n = (x - 0.3320f) / max(1.0e-5f, 0.1858f - y)
        val cct = 449.0f * n * n * n + 3525.0f * n * n + 6823.3f * n + 5520.33f
        return cct.coerceIn(1500.0f, 12000.0f)
    }
}

/**
 * Generic RGB/RGBC/RGBW adapter for tri-stimulus/color channels without direct CCT.
 * Converts normalized RGB ratios to estimated CCT via McCamy's formula.
 */
class GenericRgbcAdapter : AuxiliarySensorAdapter {
    override fun canAdapt(sensor: Sensor?): Boolean {
        if (sensor == null) return false
        val name = sensor.name.lowercase(Locale.US)
        val stringType = sensor.stringType?.lowercase(Locale.US).orEmpty()
        return (name.contains("rgb") || name.contains("color") || stringType.contains("rgb") || stringType.contains("color")) &&
                !name.contains("tcs") && !stringType.contains("color_temperature")
    }

    override fun classifyCapability(sensor: Sensor?): AuxiliarySensorCapability =
        AuxiliarySensorCapability.RGB

    override fun adaptValues(sensorName: String, values: FloatArray, timestampNs: Long): AuxiliaryIlluminantReading {
        if (values.size < 3) {
            return AuxiliaryIlluminantReading(
                sourceId = sensorName,
                capabilityType = AuxiliarySensorCapability.UNKNOWN_LAYOUT,
                timestampNs = timestampNs,
                confidence = 0.0f
            )
        }
        val r = values[0]
        val g = values[1]
        val b = values[2]
        val sum = r + g + b
        if (sum <= 1.0e-5f) {
            return AuxiliaryIlluminantReading(
                sourceId = sensorName,
                capabilityType = AuxiliarySensorCapability.RGB,
                timestampNs = timestampNs,
                confidence = 0.0f
            )
        }
        val x = r / sum
        val y = g / sum
        val n = (x - 0.3320f) / max(1.0e-5f, 0.1858f - y)
        val cct = (449.0f * n * n * n + 3525.0f * n * n + 6823.3f * n + 5520.33f).coerceIn(1500.0f, 12000.0f)
        return AuxiliaryIlluminantReading(
            sourceId = sensorName,
            capabilityType = AuxiliarySensorCapability.RGB,
            timestampNs = timestampNs,
            cctKelvin = cct,
            confidence = 0.75f,
            stable = true,
            obstructed = false,
            rawValues = values.copyOf()
        )
    }
}

/**
 * Adapter for standard scalar light (illuminance lux) sensors.
 * Classified as ILLUMINANCE_ONLY with zero confidence/ISP contribution.
 */
class ScalarLightAdapter : AuxiliarySensorAdapter {
    override fun canAdapt(sensor: Sensor?): Boolean {
        if (sensor == null) return false
        return sensor.type == Sensor.TYPE_LIGHT || sensor.stringType?.contains("light") == true
    }

    override fun classifyCapability(sensor: Sensor?): AuxiliarySensorCapability =
        AuxiliarySensorCapability.ILLUMINANCE_ONLY

    override fun adaptValues(sensorName: String, values: FloatArray, timestampNs: Long): AuxiliaryIlluminantReading =
        AuxiliaryIlluminantReading(
            sourceId = sensorName,
            capabilityType = AuxiliarySensorCapability.ILLUMINANCE_ONLY,
            timestampNs = timestampNs,
            illuminanceLux = values.getOrNull(0),
            confidence = 0.0f,
            rawValues = values.copyOf()
        )
}

/**
 * Fallback adapter for unrecognized vendor sensors with unknown value layouts.
 * Classified as UNKNOWN_LAYOUT with zero confidence/ISP contribution.
 */
class UnknownVendorAdapter : AuxiliarySensorAdapter {
    override fun canAdapt(sensor: Sensor?): Boolean = true

    override fun classifyCapability(sensor: Sensor?): AuxiliarySensorCapability =
        AuxiliarySensorCapability.UNKNOWN_LAYOUT

    override fun adaptValues(sensorName: String, values: FloatArray, timestampNs: Long): AuxiliaryIlluminantReading =
        AuxiliaryIlluminantReading(
            sourceId = sensorName,
            capabilityType = AuxiliarySensorCapability.UNKNOWN_LAYOUT,
            timestampNs = timestampNs,
            confidence = 0.0f,
            rawValues = values.copyOf()
        )
}

/**
 * Adapter for Camera2 vendor illuminant metadata (if provided in CaptureResult).
 */
object Camera2VendorIlluminantAdapter {
    /**
     * Extracts only high-confidence illuminant metadata from the exact Camera2 result. Vendor
     * keys are discovered by semantic key name; no OEM/model/key hardcoding is used. This lets
     * a HAL surface a dedicated colour/ambient camera or sensor without BnCam opening a second
     * CameraDevice. Unknown vendor layouts are ignored rather than guessed.
     */
    fun extract(captureResult: CaptureResult?): AuxiliaryIlluminantReading? {
        if (captureResult == null) return null
        val timestampNs = captureResult.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L

        captureResult.keys.forEach { key ->
            val normalized = key.name.lowercase(Locale.US).replace('-', '_')
            if (!isExplicitCctKey(normalized)) return@forEach
            val cct = extractCctKelvin(readValue(captureResult, key)) ?: return@forEach
            return AuxiliaryIlluminantReading(
                sourceId = "camera2:${key.name}",
                capabilityType = AuxiliarySensorCapability.CAMERA2_VENDOR_ILLUMINANT,
                timestampNs = timestampNs,
                cctKelvin = cct,
                confidence = 0.95f,
                stable = true,
                obstructed = false
            )
        }

        captureResult.keys.forEach { key ->
            val normalized = key.name.lowercase(Locale.US).replace('-', '_')
            if (!isExplicitRgbIlluminantKey(normalized)) return@forEach
            val rgb = extractRgb(readValue(captureResult, key)) ?: return@forEach
            val cct = estimateCctFromRgb(rgb[0], rgb[1], rgb[2]) ?: return@forEach
            return AuxiliaryIlluminantReading(
                sourceId = "camera2:${key.name}",
                capabilityType = AuxiliarySensorCapability.CAMERA2_VENDOR_ILLUMINANT,
                timestampNs = timestampNs,
                cctKelvin = cct,
                confidence = 0.75f,
                stable = true,
                obstructed = false,
                rawValues = rgb
            )
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun readValue(result: CaptureResult, key: CaptureResult.Key<*>): Any? =
        runCatching { result.get(key as CaptureResult.Key<Any>) }.getOrNull()

    private fun isExplicitCctKey(name: String): Boolean =
        name.contains("cct") ||
            name.contains("color_temperature") ||
            name.contains("colour_temperature") ||
            (name.contains("illuminant") && name.contains("temperature")) ||
            (name.contains("ambient") && name.contains("temperature") &&
                (name.contains("color") || name.contains("colour")))

    private fun isExplicitRgbIlluminantKey(name: String): Boolean {
        val hasRgb = name.contains("rgb") || name.contains("rgbc") || name.contains("rgbw")
        val identifiesIlluminantSensor =
            name.contains("ambient") || name.contains("illuminant") ||
                name.contains("color_sensor") || name.contains("colour_sensor")
        return hasRgb && identifiesIlluminantSensor
    }

    private fun extractCctKelvin(value: Any?): Float? {
        val candidates: List<Float> = when (value) {
            is Number -> listOf(value.toFloat())
            is FloatArray -> value.toList()
            is DoubleArray -> value.map { it.toFloat() }
            is IntArray -> value.map { it.toFloat() }
            is LongArray -> value.map { it.toFloat() }
            is Array<*> -> value.mapNotNull { (it as? Number)?.toFloat() }
            else -> emptyList()
        }
        return candidates.firstOrNull { it.isFinite() && it in 1500.0f..12000.0f }
    }

    private fun extractRgb(value: Any?): FloatArray? {
        val values: FloatArray = when (value) {
            is FloatArray -> value
            is DoubleArray -> FloatArray(value.size) { value[it].toFloat() }
            is IntArray -> FloatArray(value.size) { value[it].toFloat() }
            is LongArray -> FloatArray(value.size) { value[it].toFloat() }
            is Array<*> -> value.mapNotNull { (it as? Number)?.toFloat() }.toFloatArray()
            else -> return null
        }
        if (values.size < 3) return null
        val rgb = floatArrayOf(values[0], values[1], values[2])
        if (rgb.any { !it.isFinite() || it < 0.0f } || rgb.sum() <= 1.0e-5f) return null
        return rgb
    }

    private fun estimateCctFromRgb(r: Float, g: Float, b: Float): Float? {
        val sum = r + g + b
        if (!sum.isFinite() || sum <= 1.0e-5f) return null
        val x = r / sum
        val y = g / sum
        val denominator = 0.1858f - y
        if (!denominator.isFinite() || kotlin.math.abs(denominator) <= 1.0e-5f) return null
        val n = (x - 0.3320f) / denominator
        val cct = 449.0f * n * n * n + 3525.0f * n * n + 6823.3f * n + 5520.33f
        return cct.takeIf { it.isFinite() }?.coerceIn(1500.0f, 12000.0f)
    }
}
