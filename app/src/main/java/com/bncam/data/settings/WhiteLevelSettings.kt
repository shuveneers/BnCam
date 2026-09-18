package com.bncam.data.settings

const val WHITE_LEVEL_SCHEMA_VERSION = 1

object WhiteLevelModes {
    const val AUTO = "Auto"
    const val MANUAL = "Manual"

    fun sanitize(value: String?): String =
        if (value.equals(MANUAL, ignoreCase = true)) MANUAL else AUTO
}

data class WhiteLevelPreset(
    val value: Int,
    val label: String
)

object WhiteLevelPresets {
    val values: List<WhiteLevelPreset> = listOf(
        WhiteLevelPreset(1023, "1023 · 10-bit full scale"),
        WhiteLevelPreset(4095, "4095 · 12-bit full scale"),
        WhiteLevelPreset(16383, "16383 · 14-bit full scale"),
        WhiteLevelPreset(65535, "65535 · 16-bit full scale")
    )

    fun isSupported(value: Int): Boolean = values.any { it.value == value }

    fun labelFor(value: Int): String =
        values.firstOrNull { it.value == value }?.label ?: "Auto"
}

/**
 * Per-lens developed-RAW White Level selection.
 *
 * AUTO is intentionally metadata-driven. The eventual RAW authority resolver must prefer valid
 * same-frame SENSOR_DYNAMIC_WHITE_LEVEL, then SENSOR_INFO_WHITE_LEVEL, and only then apply the
 * active RAW-domain conversion. A manual preset is a developed-RAW override only; it must not
 * rewrite physical DNG metadata.
 */
data class LensWhiteLevelSettings(
    val schemaVersion: Int = WHITE_LEVEL_SCHEMA_VERSION,
    val mode: String = WhiteLevelModes.AUTO,
    val manualWhiteLevel: Int = 1023
) {
    fun sanitized(): LensWhiteLevelSettings {
        val safeMode = WhiteLevelModes.sanitize(mode)
        val validManual = WhiteLevelPresets.isSupported(manualWhiteLevel)
        return if (safeMode == WhiteLevelModes.MANUAL && !validManual) {
            copy(
                schemaVersion = WHITE_LEVEL_SCHEMA_VERSION,
                mode = WhiteLevelModes.AUTO,
                manualWhiteLevel = 1023
            )
        } else {
            copy(
                schemaVersion = WHITE_LEVEL_SCHEMA_VERSION,
                mode = safeMode,
                manualWhiteLevel = if (validManual) manualWhiteLevel else 1023
            )
        }
    }

    fun manualOverrideOrNull(): Int? {
        val safe = sanitized()
        return safe.manualWhiteLevel.takeIf { safe.mode == WhiteLevelModes.MANUAL }
    }

    fun summary(): String {
        val safe = sanitized()
        return if (safe.mode == WhiteLevelModes.MANUAL) {
            WhiteLevelPresets.labelFor(safe.manualWhiteLevel)
        } else {
            WhiteLevelModes.AUTO
        }
    }
}
