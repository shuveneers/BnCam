package com.bncam.core.capture

/** Legacy persisted priority enum retained only for decoding older profiles. */
enum class CaptureExposurePriorityMode(val persistedValue: String) {
    BALANCED("Balanced"),
    SHUTTER_PRIORITY("Shutter Priority"),
    ISO_PRIORITY("ISO Priority");

    companion object {
        fun fromPersisted(value: String?): CaptureExposurePriorityMode =
            entries.firstOrNull { it.persistedValue.equals(value?.trim(), ignoreCase = true) } ?: BALANCED
    }
}

enum class ShotBiasExposureChoice(
    val persistedValue: String,
    val fixedIso: Int? = null,
    val fixedExposureNs: Long? = null,
    val useSensorMaxIso: Boolean = false,
    val useEffectiveMaxTime: Boolean = false
) {
    AUTO("Auto"),
    MAX_ISO("Max. ISO", useSensorMaxIso = true),
    ISO_1600("ISO 1600", fixedIso = 1600),
    ISO_1250("ISO 1250", fixedIso = 1250),
    ISO_1000("ISO 1000", fixedIso = 1000),
    ISO_800("ISO 800", fixedIso = 800),
    ISO_640("ISO 640", fixedIso = 640),
    ISO_500("ISO 500", fixedIso = 500),
    ISO_400("ISO 400", fixedIso = 400),
    ISO_320("ISO 320", fixedIso = 320),
    ISO_250("ISO 250", fixedIso = 250),
    ISO_200("ISO 200", fixedIso = 200),
    ISO_160("ISO 160", fixedIso = 160),
    ISO_125("ISO 125", fixedIso = 125),
    ISO_100("ISO 100", fixedIso = 100),
    MAX_TIME("Max. Time", useEffectiveMaxTime = true),
    TIME_2S("Time 2.0 s", fixedExposureNs = 2_000_000_000L),
    TIME_1S("Time 1.0 s", fixedExposureNs = 1_000_000_000L),
    TIME_HALF("Time 1/2 s", fixedExposureNs = 500_000_000L),
    TIME_QUARTER("Time 1/4 s", fixedExposureNs = 250_000_000L),
    TIME_EIGHTH("Time 1/8 s", fixedExposureNs = 125_000_000L),
    TIME_15("Time 1/15 s", fixedExposureNs = 66_666_667L),
    TIME_30("Time 1/30 s", fixedExposureNs = 33_333_333L),
    TIME_60("Time 1/60 s", fixedExposureNs = 16_666_667L),
    TIME_125("Time 1/125 s", fixedExposureNs = 8_000_000L),
    TIME_250("Time 1/250 s", fixedExposureNs = 4_000_000L),
    TIME_500("Time 1/500 s", fixedExposureNs = 2_000_000L),
    TIME_1000("Time 1/1000 s", fixedExposureNs = 1_000_000L);

    companion object {
        val uiValues: List<String> = entries.map { it.persistedValue }
        fun fromPersisted(value: String?): ShotBiasExposureChoice =
            entries.firstOrNull { it.persistedValue.equals(value?.trim(), ignoreCase = true) } ?: AUTO
    }
}

enum class MaxFrameExposureChoice(val persistedValue: String) {
    SEC_0_3("0.3 sec"),
    SEC_0_5("0.5 sec"),
    SEC_1("1 sec"),
    SEC_3("3 sec"),
    SIXTY_PERCENT_SENSOR_MAX("0.6 × max exposure time"),
    SENSOR_MAX("Max exposure time");

    fun resolveNs(sensorMaxNs: Long): Long {
        val sensorMax = sensorMaxNs.coerceAtLeast(1L)
        val requested = when (this) {
            SEC_0_3 -> 300_000_000L
            SEC_0_5 -> 500_000_000L
            SEC_1 -> 1_000_000_000L
            SEC_3 -> 3_000_000_000L
            SIXTY_PERCENT_SENSOR_MAX -> (sensorMax.toDouble() * 0.6).toLong()
            SENSOR_MAX -> sensorMax
        }
        return requested.coerceIn(1L, sensorMax)
    }

    companion object {
        val uiValues: List<String> = entries.map { it.persistedValue }
        fun fromPersisted(value: String?): MaxFrameExposureChoice =
            entries.firstOrNull { it.persistedValue.equals(value?.trim(), ignoreCase = true) } ?: SENSOR_MAX
    }
}

/**
 * Profile-owned acquisition bias. The old multiplier priority fields are decoded for backwards
 * compatibility but are no longer user-facing or authoritative in Profile V3.
 */
data class CaptureExposurePreferences(
    val priorityMode: CaptureExposurePriorityMode = CaptureExposurePriorityMode.BALANCED,
    val shutterMultiplier: Float = 1.0f,
    val isoMultiplier: Float = 1.0f,
    val captureEvBias: Float = 0.0f,
    val shotBiasExposure: ShotBiasExposureChoice = ShotBiasExposureChoice.AUTO,
    val maxFrameExposure: MaxFrameExposureChoice = MaxFrameExposureChoice.SENSOR_MAX
) {
    fun sanitized(): CaptureExposurePreferences = copy(
        priorityMode = CaptureExposurePriorityMode.BALANCED,
        shutterMultiplier = 1.0f,
        isoMultiplier = 1.0f,
        captureEvBias = captureEvBias.finiteOr(0.0f).coerceIn(MIN_CAPTURE_EV, MAX_CAPTURE_EV)
    )

    /**
     * A fresh measured AE baseline is only required when BnCam must take direct sensor-control
     * authority (fixed ISO/time or a per-frame shutter ceiling). Capture EV by itself stays on
     * Camera2 AE compensation so Auto remains genuinely Auto.
     */
    fun requiresAeBaseline(): Boolean =
        shotBiasExposure != ShotBiasExposureChoice.AUTO ||
            maxFrameExposure != MaxFrameExposureChoice.SENSOR_MAX

    fun effectivePriorityMode(): CaptureExposurePriorityMode = when {
        shotBiasExposure.useSensorMaxIso || shotBiasExposure.fixedIso != null -> CaptureExposurePriorityMode.ISO_PRIORITY
        shotBiasExposure.useEffectiveMaxTime || shotBiasExposure.fixedExposureNs != null -> CaptureExposurePriorityMode.SHUTTER_PRIORITY
        else -> CaptureExposurePriorityMode.BALANCED
    }

    fun debugMap(): Map<String, Any> = linkedMapOf(
        "shotBiasExposure" to shotBiasExposure.persistedValue,
        "maxFrameExposure" to maxFrameExposure.persistedValue,
        "captureEvBias" to captureEvBias,
        "legacyPriorityIgnored" to true
    )

    companion object {
        const val MIN_MULTIPLIER = 0.25f
        const val MAX_MULTIPLIER = 4.0f
        const val MIN_CAPTURE_EV = -2.0f
        const val MAX_CAPTURE_EV = 2.0f

        fun fromPersisted(
            priorityMode: String?,
            shutterMultiplier: Float,
            isoMultiplier: Float,
            captureEvBias: Float,
            shotBiasExposure: String? = null,
            maxFrameExposure: String? = null
        ): CaptureExposurePreferences = CaptureExposurePreferences(
            // Decode legacy values only to keep old files readable; Profile V3 does not execute them.
            priorityMode = CaptureExposurePriorityMode.fromPersisted(priorityMode),
            shutterMultiplier = shutterMultiplier,
            isoMultiplier = isoMultiplier,
            captureEvBias = captureEvBias,
            shotBiasExposure = ShotBiasExposureChoice.fromPersisted(shotBiasExposure),
            maxFrameExposure = MaxFrameExposureChoice.fromPersisted(maxFrameExposure)
        ).sanitized()
    }
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
