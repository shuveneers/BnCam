package com.bncam.data.settings

import kotlin.math.roundToInt

const val BLACK_LEVEL_SCHEMA_VERSION = 1

object BlackLevelTypes {
    const val SYSTEM = "System"
    const val DYNAMIC = "Dynamic"
    const val MANUAL = "Manual"
    val values: List<String> = listOf(SYSTEM, DYNAMIC, MANUAL)

    fun sanitize(value: String?): String = when {
        value.equals(DYNAMIC, ignoreCase = true) -> DYNAMIC
        value.equals(MANUAL, ignoreCase = true) -> MANUAL
        else -> SYSTEM
    }
}

internal fun sanitizeBlackLevelDynamicStrength(value: Float): Float {
    if (!value.isFinite()) return 1.0f
    return (value.coerceIn(0.0f, 1.0f) * 100.0f).roundToInt() / 100.0f
}

data class LensBlackLevelControlSettings(
    val schemaVersion: Int = BLACK_LEVEL_SCHEMA_VERSION,
    val type: String = BlackLevelTypes.SYSTEM,
    val dynamicStrength: Float = 1.0f,
    /** Sensor mosaic-position order [00, 10, 01, 11]. */
    val manualValues: List<Double> = List(4) { 64.0 },
    val manualInitialized: Boolean = false,
    val migratedFromLegacy: Boolean = false
) {
    fun sanitized(): LensBlackLevelControlSettings {
        val values = manualValues.take(4).map { value ->
            value.takeIf { it.isFinite() && it >= 0.0 }?.coerceAtMost(65534.0) ?: 64.0
        }.toMutableList()
        while (values.size < 4) values += 64.0
        return copy(
            schemaVersion = BLACK_LEVEL_SCHEMA_VERSION,
            type = BlackLevelTypes.sanitize(type),
            dynamicStrength = sanitizeBlackLevelDynamicStrength(dynamicStrength),
            manualValues = values
        )
    }

    fun summary(): String = when (BlackLevelTypes.sanitize(type)) {
        BlackLevelTypes.DYNAMIC -> "Dynamic · ${java.lang.String.format(java.util.Locale.US, "%.2f", dynamicStrength)}"
        BlackLevelTypes.MANUAL -> "Manual · 4-channel"
        else -> BlackLevelTypes.SYSTEM
    }
}
