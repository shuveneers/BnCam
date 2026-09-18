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

class LensAwbCalibrationSettingsStore(private val context: Context) {

    fun settingsFlow(lensId: String): Flow<LensAwbCalibrationSettings> {
        val keys = keys(lensId)
        return context.dataStore.data.map { preferences ->
            read(preferences, keys) ?: LensAwbCalibrationSettings()
        }
    }

    suspend fun ensureInitialized(lensId: String): LensAwbCalibrationSettings {
        val keys = keys(lensId)
        var resolved: LensAwbCalibrationSettings? = null
        context.dataStore.edit { preferences ->
            val current = read(preferences, keys)
            val value = current ?: LensAwbCalibrationSettings()
            if (current == null) write(preferences, keys, value)
            resolved = value
        }
        val value = resolved ?: settingsFlow(lensId).first()
        LensAwbCalibrationRuntimeRegistry.publish(lensId, value)
        return value
    }

    suspend fun get(lensId: String): LensAwbCalibrationSettings = ensureInitialized(lensId)

    suspend fun set(lensId: String, settings: LensAwbCalibrationSettings) {
        val safe = settings.sanitized()
        val keys = keys(lensId)
        context.dataStore.edit { preferences -> write(preferences, keys, safe) }
        // Capture must see the same value the UI just committed, immediately. Do not invalidate
        // after DataStore emits: that can erase the freshly observed cache value until a later read.
        LensAwbCalibrationRuntimeRegistry.publish(lensId, safe)
    }

    suspend fun reset(lensId: String) = set(lensId, LensAwbCalibrationSettings())

    private fun read(preferences: Preferences, keys: Keys): LensAwbCalibrationSettings? {
        val schema = preferences[keys.schema] ?: return null
        if (schema < LENS_AWB_CALIBRATION_SCHEMA_VERSION) return null
        return LensAwbCalibrationSettings(
            schemaVersion = schema,
            mode = preferences[keys.mode] ?: LensAwbCalibrationModes.AUTO,
            rgCoefficient = preferences[keys.rgCoeff] ?: 1.0f,
            bgCoefficient = preferences[keys.bgCoeff] ?: 1.0f,
            greenSplitMode = preferences[keys.greenMode] ?: LensAwbGreenSplitModes.AUTO,
            presetId = preferences[keys.presetId] ?: 0,
            manualGrGbRatio = preferences[keys.manualGrGb] ?: 1.0f,
            importedName = preferences[keys.importedName] ?: "",
            importedFormat = preferences[keys.importedFormat] ?: "",
            customPoints = decodePoints(preferences[keys.points] ?: ""),
            importedGrGbRatio = preferences[keys.importedGrGb]
        ).sanitized()
    }

    private fun write(preferences: MutablePreferences, keys: Keys, settings: LensAwbCalibrationSettings) {
        val safe = settings.sanitized()
        preferences[keys.schema] = LENS_AWB_CALIBRATION_SCHEMA_VERSION
        preferences[keys.mode] = safe.mode
        preferences[keys.rgCoeff] = safe.rgCoefficient
        preferences[keys.bgCoeff] = safe.bgCoefficient
        preferences[keys.greenMode] = safe.greenSplitMode
        preferences[keys.presetId] = safe.presetId
        preferences[keys.manualGrGb] = safe.manualGrGbRatio
        preferences[keys.importedName] = safe.importedName
        preferences[keys.importedFormat] = safe.importedFormat
        preferences[keys.points] = encodePoints(safe.customPoints)
        safe.importedGrGbRatio?.let { preferences[keys.importedGrGb] = it } ?: preferences.remove(keys.importedGrGb)
    }

    private fun keys(lensId: String): Keys {
        val base = "hardware_lens_${SettingsRepository.safeLensKeyPart(lensId)}_awb_cal_v1_"
        return Keys(
            schema = intPreferencesKey("${base}schema"),
            mode = stringPreferencesKey("${base}mode"),
            rgCoeff = floatPreferencesKey("${base}rg_coeff"),
            bgCoeff = floatPreferencesKey("${base}bg_coeff"),
            greenMode = stringPreferencesKey("${base}green_mode"),
            presetId = intPreferencesKey("${base}preset_id"),
            manualGrGb = floatPreferencesKey("${base}manual_grgb"),
            importedName = stringPreferencesKey("${base}import_name"),
            importedFormat = stringPreferencesKey("${base}import_format"),
            points = stringPreferencesKey("${base}points"),
            importedGrGb = floatPreferencesKey("${base}import_grgb")
        )
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
        val schema: Preferences.Key<Int>,
        val mode: Preferences.Key<String>,
        val rgCoeff: Preferences.Key<Float>,
        val bgCoeff: Preferences.Key<Float>,
        val greenMode: Preferences.Key<String>,
        val presetId: Preferences.Key<Int>,
        val manualGrGb: Preferences.Key<Float>,
        val importedName: Preferences.Key<String>,
        val importedFormat: Preferences.Key<String>,
        val points: Preferences.Key<String>,
        val importedGrGb: Preferences.Key<Float>
    )
}
