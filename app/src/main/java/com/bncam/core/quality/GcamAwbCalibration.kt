package com.bncam.core.quality

import android.hardware.camera2.CameraCharacteristics
import com.bncam.data.settings.GcamAwbCalibrationPoint
import com.bncam.data.settings.LensAwbCalibrationModes
import com.bncam.data.settings.LensAwbCalibrationSettings
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class ParsedGcamAwbCalibration(
    val format: String,
    val points: List<GcamAwbCalibrationPoint>,
    val grGbRatio: Float?,
    val warnings: List<String> = emptyList()
)

object GcamAwbCalibrationParser {
    private val numberRegex = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    fun parse(text: String, fileName: String = ""): ParsedGcamAwbCalibration {
        val clean = text.replace("\\r", "")
        val rg = firstSeries(clean, listOf("WB_RG", "RG", "rg_ratio"))
        val bg = firstSeries(clean, listOf("WB_BG", "BG", "bg_ratio"))
        val pairCount = minOf(rg.size, bg.size, com.bncam.data.settings.LensAwbCalibrationSettings.MAX_POINTS)
        val points = (0 until pairCount).mapNotNull { index ->
            GcamAwbCalibrationPoint(rg[index], bg[index]).sanitizedOrNull()
        }
        val grgb = firstScalar(clean, listOf("BGRG", "GRGB", "grgb_ratio"))
            ?.takeIf { it.isFinite() && it in 0.50f..2.0f }
        val warnings = buildList {
            if (rg.isEmpty()) add("RG calibration series missing")
            if (bg.isEmpty()) add("BG calibration series missing")
            if (rg.size != bg.size) add("RG/BG series length mismatch: ${rg.size}/${bg.size}; paired prefix used")
            if (points.size < 2) add("At least two valid RG/BG points are required")
        }
        val lower = fileName.lowercase(Locale.US)
        val format = when {
            lower.endsWith(".gawb") || clean.contains("BGRG", ignoreCase = true) -> "AGC .gawb"
            lower.endsWith(".txt") || clean.contains("WB_RG", ignoreCase = true) -> "GCam .txt"
            else -> "GCam AWB text"
        }
        return ParsedGcamAwbCalibration(format, points, grgb, warnings)
    }

    private fun firstSeries(text: String, keys: List<String>): List<Float> {
        for (key in keys) {
            val patterns = listOf(
                Regex("(?is)\\b${Regex.escape(key)}\\b\\s*=\\s*new\\s+float\\s*\\[?\\]?\\s*\\{([^}]*)}"),
                Regex("(?is)\\b${Regex.escape(key)}\\b\\s*=\\s*\\{([^}]*)}"),
                Regex("(?im)^\\s*${Regex.escape(key)}\\s*=\\s*([^\\n]+)$")
            )
            for (pattern in patterns) {
                val match = pattern.find(text) ?: continue
                val values = numberRegex.findAll(match.groupValues[1]).mapNotNull { it.value.toFloatOrNull() }.toList()
                if (values.isNotEmpty()) return values
            }
        }
        return emptyList()
    }

    private fun firstScalar(text: String, keys: List<String>): Float? {
        for (key in keys) {
            val pattern = Regex("(?im)\\b${Regex.escape(key)}\\b\\s*=\\s*(${numberRegex.pattern})")
            pattern.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let { return it }
        }
        return null
    }
}

data class ResolvedGcamAwbCalibration(
    val valid: Boolean,
    val source: String,
    val points: List<GcamAwbCalibrationPoint>,
    val grGbRatio: Float?,
    val fingerprint: String,
    val warning: String = "none"
)

/**
 * BnCam representation of the useful AGC/GCam AWB calibration concept: a per-sensor RG/BG
 * illuminant locus plus an optional G1/G2 calibration ratio. It intentionally does not copy any
 * device table from AGC. Sensor Auto derives its anchors from this lens' own Camera2 matrices.
 */
object GcamAwbCalibrationEngine {
    fun resolve(
        settings: LensAwbCalibrationSettings,
        characteristics: CameraCharacteristics
    ): ResolvedGcamAwbCalibration {
        val safe = settings.sanitized()
        return if (safe.mode == LensAwbCalibrationModes.CUSTOM_GCAM) {
            val points = safe.customPoints.mapNotNull { point ->
                GcamAwbCalibrationPoint(
                    rgRatio = point.rgRatio * safe.rgCoefficient,
                    bgRatio = point.bgRatio * safe.bgCoefficient
                ).sanitizedOrNull()
            }
            if (points.size < 2) invalid("CUSTOM_GCAM", "custom_calibration_has_fewer_than_two_valid_points")
            else result("CUSTOM_GCAM:${safe.importedName.ifBlank { "import" }}", points, safe.effectiveCustomGrGbRatioOrNull())
        } else {
            resolveSensorAuto(safe, characteristics)
        }
    }

    private fun resolveSensorAuto(
        settings: LensAwbCalibrationSettings,
        characteristics: CameraCharacteristics
    ): ResolvedGcamAwbCalibration {
        val i1 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt()
        val i2 = characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt()
        val kelvins = listOfNotNull(
            i1?.let(RawColorTransformEngine::mapExifIlluminantToKelvin),
            i2?.let(RawColorTransformEngine::mapExifIlluminantToKelvin)
        ).distinct().ifEmpty { listOf(2856, 6504) }
        val candidateKelvins = if (kelvins.size >= 2) kelvins else listOf(kelvins.first(), 6504).distinct()
        val points = candidateKelvins.mapNotNull { kelvin ->
            val model = if (kelvin < 4000) "Planckian Blackbody" else "CIE Daylight"
            val solution = runCatching {
                RawColorTransformEngine.computeOptionBMatrices(characteristics, kelvin, model)
            }.getOrNull() ?: return@mapNotNull null
            if (!solution.isValid || solution.bayerWbGains.size < 4) return@mapNotNull null
            val rGain = solution.bayerWbGains[0]
            val bGain = solution.bayerWbGains[3]
            if (!rGain.isFinite() || !bGain.isFinite() || rGain <= 0f || bGain <= 0f) return@mapNotNull null
            // QcColorCalibration stores sensor-neutral ratios. Camera2 WB gains are their inverse.
            GcamAwbCalibrationPoint(
                rgRatio = (1f / rGain) * settings.rgCoefficient,
                bgRatio = (1f / bGain) * settings.bgCoefficient
            ).sanitizedOrNull()
        }
        return if (points.size >= 2) {
            result("SENSOR_AUTO_CAMERA2_REFERENCE_ILLUMINANTS", points, settings.effectiveCustomGrGbRatioOrNull())
        } else {
            invalid("SENSOR_AUTO", "camera2_reference_illuminant_calibration_unavailable")
        }
    }

    /**
     * Projects a physical AWB estimate onto the calibrated sensor locus in log RG/BG space.
     * authority=0 keeps the scene estimate, authority=1 uses the calibrated locus point.
     */
    fun constrainPhysicalGains(
        physicalGains: FloatArray,
        calibration: ResolvedGcamAwbCalibration,
        authority: Float
    ): FloatArray {
        if (!calibration.valid || calibration.points.size < 2 || physicalGains.size < 4) return physicalGains.copyOf()
        val rGain = physicalGains[0]
        val bGain = physicalGains[3]
        if (!rGain.isFinite() || !bGain.isFinite() || rGain <= 0f || bGain <= 0f) return physicalGains.copyOf()
        val observed = GcamAwbCalibrationPoint(1f / rGain, 1f / bGain)
        val projected = nearestPointOnLocus(observed, calibration.points) ?: return physicalGains.copyOf()
        val a = authority.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val rg = logBlend(observed.rgRatio, projected.rgRatio, a)
        val bg = logBlend(observed.bgRatio, projected.bgRatio, a)
        val result = physicalGains.copyOf(4)
        result[0] = (1f / rg).coerceIn(0.25f, 6.0f)
        result[3] = (1f / bg).coerceIn(0.25f, 6.0f)
        return result
    }

    private fun nearestPointOnLocus(
        observed: GcamAwbCalibrationPoint,
        points: List<GcamAwbCalibrationPoint>
    ): GcamAwbCalibrationPoint? {
        if (points.size < 2) return null
        val ox = ln(observed.rgRatio.toDouble())
        val oy = ln(observed.bgRatio.toDouble())
        var bestX = Double.NaN
        var bestY = Double.NaN
        var bestD2 = Double.POSITIVE_INFINITY
        for (i in 0 until points.lastIndex) {
            val ax = ln(points[i].rgRatio.toDouble())
            val ay = ln(points[i].bgRatio.toDouble())
            val bx = ln(points[i + 1].rgRatio.toDouble())
            val by = ln(points[i + 1].bgRatio.toDouble())
            val vx = bx - ax
            val vy = by - ay
            val vv = vx * vx + vy * vy
            val t = if (vv <= 1.0e-12) 0.0 else (((ox - ax) * vx + (oy - ay) * vy) / vv).coerceIn(0.0, 1.0)
            val px = ax + t * vx
            val py = ay + t * vy
            val d2 = (ox - px) * (ox - px) + (oy - py) * (oy - py)
            if (d2 < bestD2) {
                bestD2 = d2
                bestX = px
                bestY = py
            }
        }
        if (!bestX.isFinite() || !bestY.isFinite()) return null
        return GcamAwbCalibrationPoint(exp(bestX).toFloat(), exp(bestY).toFloat()).sanitizedOrNull()
    }

    private fun logBlend(a: Float, b: Float, t: Float): Float =
        exp((ln(a.toDouble()) * (1.0 - t) + ln(b.toDouble()) * t)).toFloat()

    private fun result(source: String, points: List<GcamAwbCalibrationPoint>, grGb: Float?): ResolvedGcamAwbCalibration {
        val canonical = buildString {
            append(source).append(';').append(grGb ?: "none")
            points.forEach { append(';').append(it.rgRatio).append(',').append(it.bgRatio) }
        }
        val fp = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
        return ResolvedGcamAwbCalibration(true, source, points, grGb, fp)
    }

    private fun invalid(source: String, warning: String) =
        ResolvedGcamAwbCalibration(false, source, emptyList(), null, "unavailable", warning)
}
