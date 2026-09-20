package com.bncam.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Per-lens persistence for runtime-effective Photo stream controls only.
 *
 * Keys remain below the hardware_lens_<stable-key> namespace so the existing Reset lens hardware
 * settings action remains the single reset authority. Legacy stream_v1 mode/candidate keys are not
 * read or migrated because they no longer have a runtime meaning.
 */
class PhotoStreamSettingsStore(private val context: Context) {

    fun settingsFlow(lensId: String): Flow<PhotoStreamSettings> {
        val keys = keys(lensId)
        return context.dataStore.data.map { preferences -> read(preferences, keys) }
    }

    suspend fun get(lensId: String): PhotoStreamSettings = settingsFlow(lensId).first()

    suspend fun setSpecificRawSizeIndex(lensId: String, index: Int?) {
        update(lensId) { it.copy(specificRawSizeIndex = index) }
    }

    suspend fun setResolutionFixReferenceFormatCode(lensId: String, formatCode: Int?) {
        update(lensId) { it.copy(resolutionFixReferenceFormatCode = formatCode) }
    }

    suspend fun reset(lensId: String) {
        val keys = keys(lensId)
        context.dataStore.edit { preferences ->
            listOf(
                keys.schemaVersion,
                keys.specificRawSizeIndex,
                keys.resolutionFixReferenceFormatCode
            ).forEach { key ->
                @Suppress("UNCHECKED_CAST")
                preferences.remove(key as Preferences.Key<Any>)
            }
        }
    }

    private suspend fun update(
        lensId: String,
        transform: (PhotoStreamSettings) -> PhotoStreamSettings
    ) {
        val keys = keys(lensId)
        context.dataStore.edit { preferences ->
            write(preferences, keys, transform(read(preferences, keys)).sanitized())
        }
    }

    private fun read(preferences: Preferences, keys: PhotoStreamKeys): PhotoStreamSettings =
        PhotoStreamSettings(
            specificRawSizeIndex = preferences[keys.specificRawSizeIndex],
            resolutionFixReferenceFormatCode = preferences[keys.resolutionFixReferenceFormatCode]
        ).sanitized()

    private fun write(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        keys: PhotoStreamKeys,
        settings: PhotoStreamSettings
    ) {
        val safe = settings.sanitized()
        preferences[keys.schemaVersion] = PHOTO_STREAM_SETTINGS_SCHEMA_VERSION
        safe.specificRawSizeIndex?.let { preferences[keys.specificRawSizeIndex] = it }
            ?: preferences.remove(keys.specificRawSizeIndex)
        safe.resolutionFixReferenceFormatCode?.let {
            preferences[keys.resolutionFixReferenceFormatCode] = it
        } ?: preferences.remove(keys.resolutionFixReferenceFormatCode)
    }

    private fun keys(lensId: String): PhotoStreamKeys {
        val lensPart = SettingsRepository.safeLensKeyPart(lensId)
        val base = "hardware_lens_${lensPart}_stream_v2_"
        return PhotoStreamKeys(
            schemaVersion = intPreferencesKey("${base}schema_version"),
            specificRawSizeIndex = intPreferencesKey("${base}photo_specific_raw_size_index"),
            resolutionFixReferenceFormatCode =
                intPreferencesKey("${base}photo_resolution_fix_reference_format")
        )
    }

    private data class PhotoStreamKeys(
        val schemaVersion: Preferences.Key<Int>,
        val specificRawSizeIndex: Preferences.Key<Int>,
        val resolutionFixReferenceFormatCode: Preferences.Key<Int>
    )
}
