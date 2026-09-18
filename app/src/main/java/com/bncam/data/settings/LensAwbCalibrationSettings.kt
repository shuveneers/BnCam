package com.bncam.data.settings

import java.security.MessageDigest
import java.util.Locale

const val LENS_AWB_CALIBRATION_SCHEMA_VERSION = 1

object LensAwbCalibrationModes {
    const val SENSOR_AUTO = "Sensor Auto"
    const val CUSTOM_GCAM = "Custom GCam"

    fun sanitize(value: String?): String = when {
        value.equals(CUSTOM_GCAM, ignoreCase = true) -> CUSTOM_GCAM
        else -> SENSOR_AUTO
    }
}

object LensAwbGreenSplitModes {
    const val AUTO = "Auto"
    const val MANUAL = "Manual"

    fun sanitize(value: String?): String =
        if (value.equals(MANUAL, ignoreCase = true)) MANUAL else AUTO
}

data class GcamAwbCalibrationPoint(
    val rgRatio: Float,
    val bgRatio: Float
) {
    fun sanitizedOrNull(): GcamAwbCalibrationPoint? {
        if (!rgRatio.isFinite() || !bgRatio.isFinite()) return null
        if (rgRatio !in 0.05f..8.0f || bgRatio !in 0.05f..8.0f) return null
        return this
    }
}

data class LensAwbCalibrationSettings(
    val schemaVersion: Int = LENS_AWB_CALIBRATION_SCHEMA_VERSION,
    val mode: String = LensAwbCalibrationModes.SENSOR_AUTO,
    val rgCoefficient: Float = 1.0f,
    val bgCoefficient: Float = 1.0f,
    val greenSplitMode: String = LensAwbGreenSplitModes.AUTO,
    /** GCam QcColorCalibration semantic Gr/Gb calibration ratio; mapped to G_even/G_odd by CFA at runtime. */
    val manualGrGbRatio: Float = 1.0f,
    val importedName: String = "",
    val importedFormat: String = "",
    val customPoints: List<GcamAwbCalibrationPoint> = emptyList(),
    /** Optional AGC BGRG/GRGB calibration scalar. */
    val importedGrGbRatio: Float? = null
) {
    fun sanitized(): LensAwbCalibrationSettings {
        val points = customPoints.mapNotNull { it.sanitizedOrNull() }.take(MAX_POINTS)
        val safeImportedGrGb = importedGrGbRatio
            ?.takeIf { it.isFinite() && it in 0.50f..2.0f }
        return copy(
            schemaVersion = LENS_AWB_CALIBRATION_SCHEMA_VERSION,
            mode = LensAwbCalibrationModes.sanitize(mode),
            rgCoefficient = rgCoefficient.takeIf(Float::isFinite)?.coerceIn(0.50f, 2.0f) ?: 1.0f,
            bgCoefficient = bgCoefficient.takeIf(Float::isFinite)?.coerceIn(0.50f, 2.0f) ?: 1.0f,
            greenSplitMode = LensAwbGreenSplitModes.sanitize(greenSplitMode),
            manualGrGbRatio = manualGrGbRatio.takeIf(Float::isFinite)?.coerceIn(0.50f, 2.0f) ?: 1.0f,
            importedName = importedName.trim().take(120),
            importedFormat = importedFormat.trim().take(32),
            customPoints = points,
            importedGrGbRatio = safeImportedGrGb
        )
    }

    fun effectiveCustomGrGbRatioOrNull(): Float? {
        val safe = sanitized()
        return when (safe.greenSplitMode) {
            LensAwbGreenSplitModes.MANUAL -> safe.manualGrGbRatio
            else -> safe.importedGrGbRatio
        }
    }

    fun customCalibrationReady(): Boolean =
        sanitized().let { it.mode == LensAwbCalibrationModes.CUSTOM_GCAM && it.customPoints.size >= 2 }

    fun summary(): String {
        val safe = sanitized()
        return when (safe.mode) {
            LensAwbCalibrationModes.CUSTOM_GCAM -> if (safe.customPoints.size >= 2) {
                "Custom GCam · ${safe.importedName.ifBlank { "import" }} · ${safe.customPoints.size} pts"
            } else {
                "Custom GCam · no valid calibration"
            }
            else -> LensAwbCalibrationModes.SENSOR_AUTO
        }
    }

    fun fingerprint(): String {
        val safe = sanitized()
        val canonical = buildString {
            append("v=").append(safe.schemaVersion)
            append(";mode=").append(safe.mode)
            append(";rg=").append(String.format(Locale.US, "%.8f", safe.rgCoefficient))
            append(";bg=").append(String.format(Locale.US, "%.8f", safe.bgCoefficient))
            append(";green=").append(safe.greenSplitMode)
            append(";grgb=").append(String.format(Locale.US, "%.8f", safe.manualGrGbRatio))
            append(";igrgb=").append(safe.importedGrGbRatio?.let { String.format(Locale.US, "%.8f", it) } ?: "none")
            safe.customPoints.forEach { point ->
                append(";").append(String.format(Locale.US, "%.8f,%.8f", point.rgRatio, point.bgRatio))
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_POINTS = 128
    }
}
