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

/**
 * User-facing signed AWB trim. The runtime keeps the existing physical coefficient domain:
 * -1.00 -> 0.25, 0.00 -> 1.00, +1.00 -> 5.00.
 *
 * Keeping this mapping outside Compose guarantees preview, import/export and tests use one
 * reversible contract without changing the proven AWB engine semantics.
 */
object AwbCoefficientUiMapping {
    const val MIN_COEFFICIENT = 0.25f
    const val NEUTRAL_COEFFICIENT = 1.00f
    const val MAX_COEFFICIENT = 5.00f

    fun toSigned(coefficient: Float): Float {
        val safe = coefficient.takeIf(Float::isFinite)
            ?.coerceIn(MIN_COEFFICIENT, MAX_COEFFICIENT)
            ?: NEUTRAL_COEFFICIENT
        return if (safe >= NEUTRAL_COEFFICIENT) {
            ((safe - NEUTRAL_COEFFICIENT) / (MAX_COEFFICIENT - NEUTRAL_COEFFICIENT))
                .coerceIn(0.0f, 1.0f)
        } else {
            ((safe - NEUTRAL_COEFFICIENT) / (NEUTRAL_COEFFICIENT - MIN_COEFFICIENT))
                .coerceIn(-1.0f, 0.0f)
        }
    }

    fun fromSigned(value: Float): Float {
        val safe = value.takeIf(Float::isFinite)?.coerceIn(-1.0f, 1.0f) ?: 0.0f
        return if (safe >= 0.0f) {
            NEUTRAL_COEFFICIENT + safe * (MAX_COEFFICIENT - NEUTRAL_COEFFICIENT)
        } else {
            NEUTRAL_COEFFICIENT + safe * (NEUTRAL_COEFFICIENT - MIN_COEFFICIENT)
        }
    }
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
    val importedGrGbRatio: Float? = null,
    /**
     * Blend between exact-frame Camera2 AWB and the selected BnCam preset.
     * Kept as the final constructor field for source compatibility with older positional callers.
     * 0.00 = Auto baseline, 1.00 = full (still restrained) preset tone.
     */
    val presetStrength: Float = 1.0f
) {
    fun sanitized(): LensAwbCalibrationSettings {
        val points = customPoints.mapNotNull { it.sanitizedOrNull() }.take(MAX_POINTS)
        val safeImportedGrGb = importedGrGbRatio
            ?.takeIf { it.isFinite() && it in 0.50f..2.0f }
        return copy(
            schemaVersion = LENS_AWB_CALIBRATION_SCHEMA_VERSION,
            mode = LensAwbCalibrationModes.sanitize(mode),
            rgCoefficient = rgCoefficient.takeIf(Float::isFinite)?.coerceIn(AwbCoefficientUiMapping.MIN_COEFFICIENT, AwbCoefficientUiMapping.MAX_COEFFICIENT) ?: 1.0f,
            bgCoefficient = bgCoefficient.takeIf(Float::isFinite)?.coerceIn(AwbCoefficientUiMapping.MIN_COEFFICIENT, AwbCoefficientUiMapping.MAX_COEFFICIENT) ?: 1.0f,
            greenSplitMode = LensAwbGreenSplitModes.sanitize(greenSplitMode),
            presetId = presetId.coerceIn(0, BnCamAwbPresetCatalog.all.lastIndex),
            presetStrength = presetStrength.takeIf(Float::isFinite)?.coerceIn(0.0f, 1.0f) ?: 1.0f,
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
            append(";preset_strength=").append(String.format(Locale.US, "%.8f", safe.presetStrength))
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
