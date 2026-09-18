package com.bncam.data.settings

import java.security.MessageDigest
import java.util.Locale

const val LENS_AWB_CALIBRATION_SCHEMA_VERSION = 2

object LensAwbCalibrationModes {
    const val AUTO = "Auto"
    const val BNCAM_PRESET = "BnCam Preset"
    const val CUSTOM_IMPORT = "Custom Import"

    fun sanitize(value: String?): String = when {
        value.equals(BNCAM_PRESET, ignoreCase = true) -> BNCAM_PRESET
        value.equals(CUSTOM_IMPORT, ignoreCase = true) -> CUSTOM_IMPORT
        else -> AUTO
    }
}

object LensAwbGreenSplitModes {
    const val AUTO = "Auto"
    const val MANUAL = "Manual"

    fun sanitize(value: String?): String =
        if (value.equals(MANUAL, ignoreCase = true)) MANUAL else AUTO
}

data class AwbCalibrationPoint(
    val rgRatio: Float,
    val bgRatio: Float
) {
    fun sanitizedOrNull(): AwbCalibrationPoint? {
        if (!rgRatio.isFinite() || !bgRatio.isFinite()) return null
        if (rgRatio !in 0.05f..8.0f || bgRatio !in 0.05f..8.0f) return null
        return this
    }
}

data class LensAwbCalibrationSettings(
    val schemaVersion: Int = LENS_AWB_CALIBRATION_SCHEMA_VERSION,
    /** Auto is intentionally the default and remains the only scene-refined built-in mode. */
    val mode: String = LensAwbCalibrationModes.AUTO,
    /** Advanced sensor-neutral R/G trim. Values >1 reduce the corresponding developed red gain. */
    val rgCoefficient: Float = 1.0f,
    /** Advanced sensor-neutral B/G trim. Values >1 reduce the corresponding developed blue gain. */
    val bgCoefficient: Float = 1.0f,
    val greenSplitMode: String = LensAwbGreenSplitModes.AUTO,
    val presetId: Int = 0,
    /** Semantic Gr/Gb sensor calibration ratio; mapped to G_even/G_odd by CFA at runtime. */
    val manualGrGbRatio: Float = 1.0f,
    val importedName: String = "",
    val importedFormat: String = "",
    val customPoints: List<AwbCalibrationPoint> = emptyList(),
    val importedGrGbRatio: Float? = null
) {
    fun sanitized(): LensAwbCalibrationSettings {
        val points = customPoints.mapNotNull { it.sanitizedOrNull() }.take(MAX_POINTS)
        val safeImportedGrGb = importedGrGbRatio
            ?.takeIf { it.isFinite() && it in 0.50f..2.0f }
        return copy(
            schemaVersion = LENS_AWB_CALIBRATION_SCHEMA_VERSION,
            mode = LensAwbCalibrationModes.sanitize(mode),
            rgCoefficient = rgCoefficient.takeIf(Float::isFinite)?.coerceIn(0.25f, 5.0f) ?: 1.0f,
            bgCoefficient = bgCoefficient.takeIf(Float::isFinite)?.coerceIn(0.25f, 5.0f) ?: 1.0f,
            greenSplitMode = LensAwbGreenSplitModes.sanitize(greenSplitMode),
            presetId = presetId.coerceIn(0, BnCamAwbPresetCatalog.all.lastIndex),
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
        sanitized().let { it.mode == LensAwbCalibrationModes.CUSTOM_IMPORT && it.customPoints.size >= 2 }

    fun summary(): String {
        val safe = sanitized()
        return when (safe.mode) {
            LensAwbCalibrationModes.BNCAM_PRESET -> {
                val preset = BnCamAwbPresetCatalog.byId(safe.presetId)
                "Preset · ${preset?.name ?: safe.presetId}"
            }
            LensAwbCalibrationModes.CUSTOM_IMPORT -> if (safe.customPoints.size >= 2) {
                "Custom · ${safe.importedName.ifBlank { "import" }} · ${safe.customPoints.size} pts"
            } else {
                "Custom · no valid calibration"
            }
            else -> LensAwbCalibrationModes.AUTO
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
            append(";preset=").append(safe.presetId)
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
