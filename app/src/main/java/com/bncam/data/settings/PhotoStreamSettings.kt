package com.bncam.data.settings

const val PHOTO_STREAM_SETTINGS_SCHEMA_VERSION = 2

/**
 * Persisted per-lens controls that have a direct runtime effect on the Photo stream pipeline.
 *
 * The old Auto / Validated / Manual candidate selector is intentionally gone. Format selection is
 * capability-driven and profile-constrained. The only persisted stream controls here are the two
 * independent resolution mechanisms recovered from GCam and already implemented by
 * CameraPhotoResolutionPolicy.
 */
data class PhotoStreamSettings(
    val specificRawSizeIndex: Int? = null,
    val resolutionFixReferenceFormatCode: Int? = null
) {
    fun sanitized(): PhotoStreamSettings = copy(
        specificRawSizeIndex = specificRawSizeIndex?.takeIf { it >= 0 },
        resolutionFixReferenceFormatCode = resolutionFixReferenceFormatCode?.takeIf { it >= 0 }
    )

    val hasExplicitResolutionOverride: Boolean
        get() = specificRawSizeIndex != null || resolutionFixReferenceFormatCode != null
}

/**
 * Runtime-relevant fingerprint for settings that can change Camera2 session outputs/geometry.
 *
 * RAW preview format bindings are stored by SettingsRepository for compatibility with the existing
 * advanced RAW preview control, but they participate in this same fingerprint because changing one
 * may add/remove the optional RAW_PREVIEW_SUPPORT session output.
 */
object PhotoStreamRuntimeFingerprint {
    fun create(
        settings: PhotoStreamSettings,
        raw10Binding: String = "AUTO",
        rawSensorBinding: String = "AUTO"
    ): String {
        val safe = settings.sanitized()
        return buildString {
            append("RAW_INDEX=").append(safe.specificRawSizeIndex?.toString() ?: "AUTO")
            append(":RES_FIX=").append(safe.resolutionFixReferenceFormatCode?.toString() ?: "OFF")
            append(":RAW10_BINDING=").append(normalizeBinding(raw10Binding))
            append(":RAW_SENSOR_BINDING=").append(normalizeBinding(rawSensorBinding))
        }
    }

    private fun normalizeBinding(value: String): String =
        value.trim().uppercase().ifBlank { "AUTO" }
}
