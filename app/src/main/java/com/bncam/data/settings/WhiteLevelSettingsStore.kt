package com.bncam.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Dedicated per-lens persistence for developed-RAW White Level authority. */
class WhiteLevelSettingsStore(private val context: Context) {

    fun settingsFlow(lensId: String): Flow<LensWhiteLevelSettings> {
        val keys = keys(lensId)
        return context.dataStore.data.map { preferences ->
            readCurrent(preferences, keys) ?: LensWhiteLevelSettings()
        }
    }

    suspend fun ensureInitialized(lensId: String): LensWhiteLevelSettings {
        val keys = keys(lensId)
        var resolved: LensWhiteLevelSettings? = null
        context.dataStore.edit { preferences ->
            val current = readCurrent(preferences, keys)
            val value = current ?: LensWhiteLevelSettings()
            if (current == null) writeCurrent(preferences, keys, value)
            resolved = value
        }
        return resolved ?: settingsFlow(lensId).first()
    }

    suspend fun get(lensId: String): LensWhiteLevelSettings = ensureInitialized(lensId)

    suspend fun set(lensId: String, settings: LensWhiteLevelSettings) {
        val safe = settings.sanitized()
        val keys = keys(lensId)
        context.dataStore.edit { preferences ->
            writeCurrent(preferences, keys, safe)
        }
        WhiteLevelRuntimeRegistry.invalidate(lensId)
    }

    suspend fun update(
        lensId: String,
        transform: (LensWhiteLevelSettings) -> LensWhiteLevelSettings
    ): LensWhiteLevelSettings {
        val keys = keys(lensId)
        var result: LensWhiteLevelSettings? = null
        context.dataStore.edit { preferences ->
            val current = readCurrent(preferences, keys) ?: LensWhiteLevelSettings()
            val next = transform(current).sanitized()
            writeCurrent(preferences, keys, next)
            result = next
        }
        WhiteLevelRuntimeRegistry.invalidate(lensId)
        return requireNotNull(result)
    }

    suspend fun reset(lensId: String) = set(lensId, LensWhiteLevelSettings())

    private fun readCurrent(
        preferences: Preferences,
        keys: WhiteLevelKeys
    ): LensWhiteLevelSettings? {
        val schema = preferences[keys.schemaVersion] ?: return null
        if (schema < WHITE_LEVEL_SCHEMA_VERSION) return null
        return LensWhiteLevelSettings(
            schemaVersion = schema,
            mode = WhiteLevelModes.sanitize(preferences[keys.mode]),
            manualWhiteLevel = preferences[keys.manualWhiteLevel] ?: 1023
        ).sanitized()
    }

    private fun writeCurrent(
        preferences: MutablePreferences,
        keys: WhiteLevelKeys,
        settings: LensWhiteLevelSettings
    ) {
        val safe = settings.sanitized()
        preferences[keys.schemaVersion] = WHITE_LEVEL_SCHEMA_VERSION
        preferences[keys.mode] = safe.mode
        preferences[keys.manualWhiteLevel] = safe.manualWhiteLevel
    }

    private fun keys(lensId: String): WhiteLevelKeys {
        val lensPart = SettingsRepository.safeLensKeyPart(lensId)
        val base = "hardware_lens_${lensPart}_white_v1_"
        return WhiteLevelKeys(
            schemaVersion = intPreferencesKey("${base}schema_version"),
            mode = stringPreferencesKey("${base}mode"),
            manualWhiteLevel = intPreferencesKey("${base}manual_white_level")
        )
    }

    private data class WhiteLevelKeys(
        val schemaVersion: Preferences.Key<Int>,
        val mode: Preferences.Key<String>,
        val manualWhiteLevel: Preferences.Key<Int>
    )
}
