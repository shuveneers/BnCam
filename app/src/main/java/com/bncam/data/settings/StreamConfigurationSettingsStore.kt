package com.bncam.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Per-lens Stream Configuration persistence.
 *
 * Keys deliberately live below the existing hardware_lens_<stable-key> prefix so the established
 * "Reset lens hardware settings" action remains the single reset authority for Noise Model,
 * Black Level, White Level, Color Matrix and Stream Configuration.
 */
class StreamConfigurationSettingsStore(private val context: Context) {

    fun settingsFlow(lensId: String): Flow<LensStreamConfigurationSettings> {
        val keys = keys(lensId)
        return context.dataStore.data.map { preferences -> read(preferences, keys) }
    }

    suspend fun get(lensId: String): LensStreamConfigurationSettings = settingsFlow(lensId).first()

    suspend fun setMode(
        lensId: String,
        streamClass: StreamConfigurationClass,
        mode: StreamConfigurationMode
    ) {
        updateClass(lensId, streamClass) { current -> current.copy(mode = mode) }
    }

    suspend fun setValidatedCandidate(
        lensId: String,
        streamClass: StreamConfigurationClass,
        candidateId: String?
    ) {
        updateClass(lensId, streamClass) { current ->
            current.copy(validatedCandidateId = candidateId?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    suspend fun updateClass(
        lensId: String,
        streamClass: StreamConfigurationClass,
        transform: (StreamClassConfiguration) -> StreamClassConfiguration
    ): LensStreamConfigurationSettings {
        val keys = keys(lensId)
        var resolved: LensStreamConfigurationSettings? = null
        context.dataStore.edit { preferences ->
            val current = read(preferences, keys)
            val next = current.withClass(streamClass, transform(current.forClass(streamClass))).sanitized()
            write(preferences, keys, next)
            resolved = next
        }
        return requireNotNull(resolved)
    }

    suspend fun reset(lensId: String) {
        val keys = keys(lensId)
        context.dataStore.edit { preferences ->
            listOf(
                keys.schemaVersion,
                keys.photoMode,
                keys.photoValidatedCandidate,
                keys.videoMode,
                keys.videoValidatedCandidate
            ).forEach { key ->
                @Suppress("UNCHECKED_CAST")
                preferences.remove(key as Preferences.Key<Any>)
            }
        }
    }

    private fun read(
        preferences: Preferences,
        keys: StreamConfigurationKeys
    ): LensStreamConfigurationSettings {
        return LensStreamConfigurationSettings(
            schemaVersion = preferences[keys.schemaVersion] ?: STREAM_CONFIGURATION_SCHEMA_VERSION,
            photo = StreamClassConfiguration(
                mode = StreamConfigurationMode.parse(preferences[keys.photoMode]),
                validatedCandidateId = preferences[keys.photoValidatedCandidate]
            ),
            video = StreamClassConfiguration(
                mode = StreamConfigurationMode.parse(preferences[keys.videoMode]),
                validatedCandidateId = preferences[keys.videoValidatedCandidate]
            )
        ).sanitized()
    }

    private fun write(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        keys: StreamConfigurationKeys,
        settings: LensStreamConfigurationSettings
    ) {
        val safe = settings.sanitized()
        preferences[keys.schemaVersion] = STREAM_CONFIGURATION_SCHEMA_VERSION
        preferences[keys.photoMode] = safe.photo.mode.name
        preferences[keys.videoMode] = safe.video.mode.name
        safe.photo.validatedCandidateId?.let { preferences[keys.photoValidatedCandidate] = it }
            ?: preferences.remove(keys.photoValidatedCandidate)
        safe.video.validatedCandidateId?.let { preferences[keys.videoValidatedCandidate] = it }
            ?: preferences.remove(keys.videoValidatedCandidate)
    }

    private fun keys(lensId: String): StreamConfigurationKeys {
        val lensPart = SettingsRepository.safeLensKeyPart(lensId)
        val base = "hardware_lens_${lensPart}_stream_v1_"
        return StreamConfigurationKeys(
            schemaVersion = intPreferencesKey("${base}schema_version"),
            photoMode = stringPreferencesKey("${base}photo_mode"),
            photoValidatedCandidate = stringPreferencesKey("${base}photo_validated_candidate"),
            videoMode = stringPreferencesKey("${base}video_mode"),
            videoValidatedCandidate = stringPreferencesKey("${base}video_validated_candidate")
        )
    }

    private data class StreamConfigurationKeys(
        val schemaVersion: Preferences.Key<Int>,
        val photoMode: Preferences.Key<String>,
        val photoValidatedCandidate: Preferences.Key<String>,
        val videoMode: Preferences.Key<String>,
        val videoValidatedCandidate: Preferences.Key<String>
    )
}
