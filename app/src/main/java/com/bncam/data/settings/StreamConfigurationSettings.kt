package com.bncam.data.settings

const val STREAM_CONFIGURATION_SCHEMA_VERSION = 1

enum class StreamConfigurationMode(val displayName: String) {
    AUTO("Auto"),
    VALIDATED("Validated"),
    MANUAL("Manual");

    companion object {
        fun parse(value: String?): StreamConfigurationMode =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

enum class StreamConfigurationClass(val displayName: String) {
    PHOTO("Photo"),
    VIDEO("Video")
}

data class StreamClassConfiguration(
    val mode: StreamConfigurationMode = StreamConfigurationMode.AUTO,
    val validatedCandidateId: String? = null
) {
    fun sanitized(): StreamClassConfiguration = copy(
        mode = StreamConfigurationMode.parse(mode.name),
        validatedCandidateId = validatedCandidateId?.trim()?.takeIf { it.isNotEmpty() }
    )

    fun summary(): String = when (mode) {
        StreamConfigurationMode.AUTO -> "Auto"
        StreamConfigurationMode.VALIDATED ->
            if (validatedCandidateId.isNullOrBlank()) "Validated · Auto candidate" else "Validated"
        StreamConfigurationMode.MANUAL -> "Manual"
    }
}


/**
 * Runtime-relevant fingerprint for the Photo stream authority.
 *
 * The fingerprint intentionally ignores Video and ignores dormant settings that cannot affect the
 * active Photo pipeline. This lets BnCameraManager observe DataStore without rebuilding a Camera2
 * session for irrelevant preference writes.
 */
object StreamConfigurationRuntimeFingerprint {
    fun photo(
        configuration: StreamClassConfiguration,
        raw10Binding: String = "AUTO",
        rawSensorBinding: String = "AUTO"
    ): String = when (configuration.mode) {
        StreamConfigurationMode.AUTO -> "AUTO"
        StreamConfigurationMode.VALIDATED ->
            "VALIDATED:${configuration.validatedCandidateId?.trim()?.takeIf { it.isNotEmpty() } ?: "AUTO_CANDIDATE"}"
        StreamConfigurationMode.MANUAL ->
            "MANUAL:RAW10=${normalizeBinding(raw10Binding)}:RAW_SENSOR=${normalizeBinding(rawSensorBinding)}"
    }

    private fun normalizeBinding(value: String): String =
        value.trim().uppercase().ifBlank { "AUTO" }
}

data class LensStreamConfigurationSettings(
    val schemaVersion: Int = STREAM_CONFIGURATION_SCHEMA_VERSION,
    val photo: StreamClassConfiguration = StreamClassConfiguration(),
    val video: StreamClassConfiguration = StreamClassConfiguration()
) {
    fun sanitized(): LensStreamConfigurationSettings = copy(
        schemaVersion = STREAM_CONFIGURATION_SCHEMA_VERSION,
        photo = photo.sanitized(),
        video = video.sanitized()
    )

    fun forClass(streamClass: StreamConfigurationClass): StreamClassConfiguration = when (streamClass) {
        StreamConfigurationClass.PHOTO -> photo
        StreamConfigurationClass.VIDEO -> video
    }

    fun withClass(
        streamClass: StreamConfigurationClass,
        value: StreamClassConfiguration
    ): LensStreamConfigurationSettings = when (streamClass) {
        StreamConfigurationClass.PHOTO -> copy(photo = value.sanitized())
        StreamConfigurationClass.VIDEO -> copy(video = value.sanitized())
    }.sanitized()
}
