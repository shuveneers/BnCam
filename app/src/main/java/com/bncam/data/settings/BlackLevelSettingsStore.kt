package com.bncam.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Dedicated Black Level v2 persistence.
 *
 * The historical `bl_dynamic` value represented an incorrect base×percentage path. It is read only
 * as migration evidence and is never reinterpreted as the new 0..1 Dynamic strength. A migrated
 * Dynamic mode therefore starts at strength 1.00 (full same-frame dynamic metadata).
 */
class BlackLevelSettingsStore(private val context: Context) {

    fun settingsFlow(lensId: String): Flow<LensBlackLevelControlSettings> {
        val keys = keys(lensId)
        return context.dataStore.data.map { preferences ->
            readCurrent(preferences, keys) ?: migrateLegacy(preferences, keys)
        }
    }

    suspend fun ensureMigrated(lensId: String): LensBlackLevelControlSettings {
        val keys = keys(lensId)
        var resolved: LensBlackLevelControlSettings? = null
        context.dataStore.edit { preferences ->
            val current = readCurrent(preferences, keys)
            if (current != null) {
                resolved = current
            } else {
                val migrated = migrateLegacy(preferences, keys)
                writeCurrent(preferences, keys, migrated)
                resolved = migrated
            }
        }
        return resolved ?: settingsFlow(lensId).first()
    }

    suspend fun get(lensId: String): LensBlackLevelControlSettings = ensureMigrated(lensId)

    suspend fun set(lensId: String, settings: LensBlackLevelControlSettings) {
        val safe = settings.sanitized()
        val keys = keys(lensId)
        context.dataStore.edit { preferences ->
            writeCurrent(preferences, keys, safe)
            // Compatibility mirror only. Runtime authority is v2 and never reads legacy bl_dynamic.
            preferences[keys.legacyMode] = when (safe.type) {
                BlackLevelTypes.DYNAMIC -> "Dynamic"
                BlackLevelTypes.MANUAL -> "Manual"
                else -> "Auto"
            }
            preferences[keys.legacyManual] = safe.manualValues.joinToString(",") { it.toString() }
        }
        BlackLevelRuntimeRegistry.invalidate(lensId)
    }

    suspend fun update(
        lensId: String,
        transform: (LensBlackLevelControlSettings) -> LensBlackLevelControlSettings
    ): LensBlackLevelControlSettings {
        val keys = keys(lensId)
        var result: LensBlackLevelControlSettings? = null
        context.dataStore.edit { preferences ->
            val current = readCurrent(preferences, keys) ?: migrateLegacy(preferences, keys)
            val next = transform(current).sanitized()
            writeCurrent(preferences, keys, next)
            preferences[keys.legacyMode] = when (next.type) {
                BlackLevelTypes.DYNAMIC -> "Dynamic"
                BlackLevelTypes.MANUAL -> "Manual"
                else -> "Auto"
            }
            preferences[keys.legacyManual] = next.manualValues.joinToString(",") { it.toString() }
            result = next
        }
        BlackLevelRuntimeRegistry.invalidate(lensId)
        return requireNotNull(result)
    }

    suspend fun reset(lensId: String) = set(lensId, LensBlackLevelControlSettings())

    private fun readCurrent(
        preferences: Preferences,
        keys: BlackLevelKeys
    ): LensBlackLevelControlSettings? {
        val schema = preferences[keys.schemaVersion] ?: return null
        if (schema < BLACK_LEVEL_SCHEMA_VERSION) return null
        return LensBlackLevelControlSettings(
            schemaVersion = schema,
            type = BlackLevelTypes.sanitize(preferences[keys.type]),
            dynamicStrength = sanitizeBlackLevelDynamicStrength(preferences[keys.dynamicStrength] ?: 1.0f),
            manualValues = parseValues(preferences[keys.manualValues], default = 64.0),
            manualInitialized = preferences[keys.manualInitialized] ?: false,
            migratedFromLegacy = preferences[keys.migratedFromLegacy] ?: false
        ).sanitized()
    }

    private fun migrateLegacy(
        preferences: Preferences,
        keys: BlackLevelKeys
    ): LensBlackLevelControlSettings {
        val legacyType = when {
            preferences[keys.legacyMode].equals("Dynamic", ignoreCase = true) -> BlackLevelTypes.DYNAMIC
            preferences[keys.legacyMode].equals("Manual", ignoreCase = true) -> BlackLevelTypes.MANUAL
            else -> BlackLevelTypes.SYSTEM
        }
        val legacyManual = parseValues(preferences[keys.legacyManual], default = 64.0)
        val hasLegacyManual = !preferences[keys.legacyManual].isNullOrBlank()
        return LensBlackLevelControlSettings(
            type = legacyType,
            // Do NOT map the retired legacy base×percentage carrier onto the new blend strength.
            dynamicStrength = 1.0f,
            manualValues = legacyManual,
            manualInitialized = hasLegacyManual,
            migratedFromLegacy = preferences[keys.legacyMode] != null ||
                preferences[keys.legacyDynamicPercent] != null || hasLegacyManual
        ).sanitized()
    }

    private fun writeCurrent(
        preferences: MutablePreferences,
        keys: BlackLevelKeys,
        settings: LensBlackLevelControlSettings
    ) {
        val safe = settings.sanitized()
        preferences[keys.schemaVersion] = BLACK_LEVEL_SCHEMA_VERSION
        preferences[keys.type] = safe.type
        preferences[keys.dynamicStrength] = safe.dynamicStrength
        preferences[keys.manualValues] = safe.manualValues.joinToString(",") { it.toString() }
        preferences[keys.manualInitialized] = safe.manualInitialized
        preferences[keys.migratedFromLegacy] = safe.migratedFromLegacy
    }

    private fun parseValues(raw: String?, default: Double): List<Double> {
        val fields = raw.orEmpty().split(',')
        return List(4) { index ->
            fields.getOrNull(index)?.trim()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.coerceAtMost(65534.0)
                ?: default
        }
    }

    private fun keys(lensId: String): BlackLevelKeys {
        val lensPart = SettingsRepository.safeLensKeyPart(lensId)
        val base = "hardware_lens_${lensPart}_"
        val v2 = "${base}black_v2_"
        return BlackLevelKeys(
            schemaVersion = intPreferencesKey("${v2}schema_version"),
            type = stringPreferencesKey("${v2}type"),
            dynamicStrength = floatPreferencesKey("${v2}dynamic_strength"),
            manualValues = stringPreferencesKey("${v2}manual_values"),
            manualInitialized = booleanPreferencesKey("${v2}manual_initialized"),
            migratedFromLegacy = booleanPreferencesKey("${v2}migrated_from_legacy"),
            legacyMode = stringPreferencesKey("${base}bl_mode"),
            legacyDynamicPercent = floatPreferencesKey("${base}bl_dynamic"),
            legacyManual = stringPreferencesKey("${base}bl_manual")
        )
    }

    private data class BlackLevelKeys(
        val schemaVersion: Preferences.Key<Int>,
        val type: Preferences.Key<String>,
        val dynamicStrength: Preferences.Key<Float>,
        val manualValues: Preferences.Key<String>,
        val manualInitialized: Preferences.Key<Boolean>,
        val migratedFromLegacy: Preferences.Key<Boolean>,
        val legacyMode: Preferences.Key<String>,
        val legacyDynamicPercent: Preferences.Key<Float>,
        val legacyManual: Preferences.Key<String>
    )
}
