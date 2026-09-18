package com.bncam.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Profile-owned AWB persistence. Keys intentionally use SettingsRepository's regular
 * "${profileId}_${settingKey}" namespace so .bnc import/export round-trips the same state.
 */
object ProfileAwbSettingKeys {
    const val MODE = "awb_mode"
    const val RG_COEFFICIENT = "awb_rg_coefficient"
    const val BG_COEFFICIENT = "awb_bg_coefficient"
    const val GREEN_SPLIT_MODE = "awb_green_split_mode"
    const val PRESET_ID = "awb_preset_id"
    const val PRESET_STRENGTH = "awb_preset_strength"
    const val MANUAL_GRGB_RATIO = "awb_manual_grgb_ratio"
    const val IMPORTED_NAME = "awb_imported_name"
    const val IMPORTED_FORMAT = "awb_imported_format"
    const val CUSTOM_POINTS = "awb_custom_points"
    const val IMPORTED_GRGB_RATIO = "awb_imported_grgb_ratio"

    fun portableSpecs(): List<ProfileSettingSpec> = listOf(
        ProfileSettingSpec(MODE, ProfileSettingValueType.STRING, LensAwbCalibrationModes.AUTO),
        ProfileSettingSpec(RG_COEFFICIENT, ProfileSettingValueType.FLOAT, "1.0"),
        ProfileSettingSpec(BG_COEFFICIENT, ProfileSettingValueType.FLOAT, "1.0"),
        ProfileSettingSpec(GREEN_SPLIT_MODE, ProfileSettingValueType.STRING, LensAwbGreenSplitModes.AUTO),
        ProfileSettingSpec(PRESET_ID, ProfileSettingValueType.INT, "0"),
        ProfileSettingSpec(PRESET_STRENGTH, ProfileSettingValueType.FLOAT, "1.0"),
        ProfileSettingSpec(MANUAL_GRGB_RATIO, ProfileSettingValueType.FLOAT, "1.0"),
        ProfileSettingSpec(IMPORTED_NAME, ProfileSettingValueType.STRING, ""),
        ProfileSettingSpec(IMPORTED_FORMAT, ProfileSettingValueType.STRING, ""),
        ProfileSettingSpec(CUSTOM_POINTS, ProfileSettingValueType.STRING, ""),
        ProfileSettingSpec(IMPORTED_GRGB_RATIO, ProfileSettingValueType.STRING, "")
    )
}

class ProfileAwbCalibrationSettingsStore(private val context: Context) {
    fun settingsFlow(profileId: String): Flow<LensAwbCalibrationSettings> {
        val keys = keys(profileId)
        return context.dataStore.data.map { preferences ->
            read(preferences, keys) ?: LensAwbCalibrationSettings()
        }
    }

    suspend fun ensureInitialized(profileId: String): LensAwbCalibrationSettings {
        val keys = keys(profileId)
        context.dataStore.data.first().let { read(it, keys) }?.let { existing ->
            LensAwbCalibrationRuntimeRegistry.publishProfile(profileId, existing)
            return existing
        }

        // One-time delta 20/21 migration: every profile starts from the previously working
        // per-lens AWB state, then diverges independently after its first profile write.
        val seed = ownerLensId(profileId)
            ?.let { runCatching { LensAwbCalibrationSettingsStore(context).get(it) }.getOrNull() }
            ?: LensAwbCalibrationSettings()

        var resolved = seed.sanitized()
        context.dataStore.edit { preferences ->
            val current = read(preferences, keys)
            if (current != null) {
                resolved = current
            } else {
                write(preferences, keys, resolved)
            }
        }
        LensAwbCalibrationRuntimeRegistry.publishProfile(profileId, resolved)
        return resolved
    }

    suspend fun get(profileId: String): LensAwbCalibrationSettings = ensureInitialized(profileId)

    suspend fun set(profileId: String, settings: LensAwbCalibrationSettings) {
        val safe = settings.sanitized()
        val keys = keys(profileId)
        context.dataStore.edit { preferences -> write(preferences, keys, safe) }
        LensAwbCalibrationRuntimeRegistry.publishProfile(profileId, safe)
    }

    suspend fun reset(profileId: String) = set(profileId, LensAwbCalibrationSettings())

    private fun read(preferences: Preferences, keys: Keys): LensAwbCalibrationSettings? {
        val mode = preferences[keys.mode] ?: return null
        return LensAwbCalibrationSettings(
            mode = mode,
            rgCoefficient = preferences[keys.rgCoefficient] ?: 1.0f,
            bgCoefficient = preferences[keys.bgCoefficient] ?: 1.0f,
            greenSplitMode = preferences[keys.greenSplitMode] ?: LensAwbGreenSplitModes.AUTO,
            presetId = preferences[keys.presetId] ?: 0,
            presetStrength = preferences[keys.presetStrength] ?: 1.0f,
            manualGrGbRatio = preferences[keys.manualGrGbRatio] ?: 1.0f,
            importedName = preferences[keys.importedName] ?: "",
            importedFormat = preferences[keys.importedFormat] ?: "",
            customPoints = decodePoints(preferences[keys.customPoints] ?: ""),
            importedGrGbRatio = preferences[keys.importedGrGbRatio]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toFloatOrNull()
        ).sanitized()
    }

    private fun write(preferences: MutablePreferences, keys: Keys, settings: LensAwbCalibrationSettings) {
        val safe = settings.sanitized()
        preferences[keys.mode] = safe.mode
        preferences[keys.rgCoefficient] = safe.rgCoefficient
        preferences[keys.bgCoefficient] = safe.bgCoefficient
        preferences[keys.greenSplitMode] = safe.greenSplitMode
        preferences[keys.presetId] = safe.presetId
        preferences[keys.presetStrength] = safe.presetStrength
        preferences[keys.manualGrGbRatio] = safe.manualGrGbRatio
        preferences[keys.importedName] = safe.importedName
        preferences[keys.importedFormat] = safe.importedFormat
        preferences[keys.customPoints] = encodePoints(safe.customPoints)
        preferences[keys.importedGrGbRatio] = safe.importedGrGbRatio?.toString() ?: ""
    }

    private fun keys(profileId: String): Keys {
        val prefix = "${profileId}_"
        return Keys(
            mode = stringPreferencesKey(prefix + ProfileAwbSettingKeys.MODE),
            rgCoefficient = floatPreferencesKey(prefix + ProfileAwbSettingKeys.RG_COEFFICIENT),
            bgCoefficient = floatPreferencesKey(prefix + ProfileAwbSettingKeys.BG_COEFFICIENT),
            greenSplitMode = stringPreferencesKey(prefix + ProfileAwbSettingKeys.GREEN_SPLIT_MODE),
            presetId = intPreferencesKey(prefix + ProfileAwbSettingKeys.PRESET_ID),
            presetStrength = floatPreferencesKey(prefix + ProfileAwbSettingKeys.PRESET_STRENGTH),
            manualGrGbRatio = floatPreferencesKey(prefix + ProfileAwbSettingKeys.MANUAL_GRGB_RATIO),
            importedName = stringPreferencesKey(prefix + ProfileAwbSettingKeys.IMPORTED_NAME),
            importedFormat = stringPreferencesKey(prefix + ProfileAwbSettingKeys.IMPORTED_FORMAT),
            customPoints = stringPreferencesKey(prefix + ProfileAwbSettingKeys.CUSTOM_POINTS),
            importedGrGbRatio = stringPreferencesKey(prefix + ProfileAwbSettingKeys.IMPORTED_GRGB_RATIO)
        )
    }

    private fun ownerLensId(profileId: String): String? {
        val marker = "_profile_"
        val index = profileId.lastIndexOf(marker)
        return profileId.takeIf { index > 0 }?.substring(0, index)
    }

    private fun encodePoints(points: List<AwbCalibrationPoint>): String =
        points.joinToString(";") { "${it.rgRatio},${it.bgRatio}" }

    private fun decodePoints(raw: String): List<AwbCalibrationPoint> = raw
        .split(';')
        .mapNotNull { token ->
            val values = token.split(',')
            if (values.size != 2) return@mapNotNull null
            val rg = values[0].trim().toFloatOrNull() ?: return@mapNotNull null
            val bg = values[1].trim().toFloatOrNull() ?: return@mapNotNull null
            AwbCalibrationPoint(rg, bg).sanitizedOrNull()
        }
        .take(LensAwbCalibrationSettings.MAX_POINTS)

    private data class Keys(
        val mode: Preferences.Key<String>,
        val rgCoefficient: Preferences.Key<Float>,
        val bgCoefficient: Preferences.Key<Float>,
        val greenSplitMode: Preferences.Key<String>,
        val presetId: Preferences.Key<Int>,
        val presetStrength: Preferences.Key<Float>,
        val manualGrGbRatio: Preferences.Key<Float>,
        val importedName: Preferences.Key<String>,
        val importedFormat: Preferences.Key<String>,
        val customPoints: Preferences.Key<String>,
        val importedGrGbRatio: Preferences.Key<String>
    )
}
