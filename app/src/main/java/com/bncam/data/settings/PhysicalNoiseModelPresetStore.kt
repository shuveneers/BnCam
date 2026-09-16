package com.bncam.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Global user-imported preset catalog. Built-in AGC presets are code-backed and never persisted. */
class PhysicalNoiseModelPresetStore(private val context: Context) {
    private val userPresetsKey = stringSetPreferencesKey("physical_noise_user_presets_v1")

    val userPresetsFlow: Flow<List<NoiseModelPreset>> = context.dataStore.data.map { preferences ->
        preferences[userPresetsKey]
            .orEmpty()
            .mapNotNull(NoiseModelUserPresetCodec::decode)
            .sortedWith(
                compareByDescending<NoiseModelPreset> { it.importedAtEpochMs ?: Long.MIN_VALUE }
                    .thenBy { it.displayName.lowercase() }
            )
    }

    suspend fun importPreset(preset: NoiseModelPreset) {
        require(preset.origin == NoiseModelPresetOrigin.USER)
        context.dataStore.edit { preferences ->
            val current = preferences[userPresetsKey].orEmpty().toMutableSet()
            current.removeAll { raw -> NoiseModelUserPresetCodec.decode(raw)?.id == preset.id }
            current += NoiseModelUserPresetCodec.encode(preset)
            preferences[userPresetsKey] = current
        }
        PhysicalNoiseModelRuntimeRegistry.invalidateAll()
    }

    suspend fun deleteUserPreset(presetId: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[userPresetsKey].orEmpty().toMutableSet()
            current.removeAll { raw -> NoiseModelUserPresetCodec.decode(raw)?.id == presetId }
            preferences[userPresetsKey] = current
        }
        PhysicalNoiseModelRuntimeRegistry.invalidateAll()
    }
}
