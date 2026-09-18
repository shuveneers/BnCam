package com.bncam.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bncam.core.quality.NoiseModelSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore persistence for the physical noise model. This remains separate from the legacy
 * LensNoiseModelSettings API; Phase 5 now consumes this store as shutter-time physical authority.
 * Legacy calibration payloads remain retained for migration/forensics. The one intentional write
 * to legacy state is hardware_lens_*_dynamic_iso = 0: that old coefficient was also consumed by
 * native SPECTRA authority shaping, so leaving it live would apply the new physical coefficient a
 * second time outside the A/B/C/D resolver. The new physical_noise_dynamic_iso_coefficient remains
 * the sole runtime owner.
 */
class PhysicalNoiseModelSettingsStore(private val context: Context) {

    fun settingsFlow(lensId: String): Flow<LensPhysicalNoiseModelSettings> {
        val keys = keys(lensId)
        return context.dataStore.data.map { preferences ->
            readCurrent(preferences, keys) ?: PhysicalNoiseModelMigration.migrate(
                readLegacy(preferences, keys)
            )
        }
    }

    /** Materializes the deterministic legacy migration exactly once and returns the persisted v2 state. */
    suspend fun ensureMigrated(lensId: String): LensPhysicalNoiseModelSettings {
        val keys = keys(lensId)
        var resolved: LensPhysicalNoiseModelSettings? = null
        context.dataStore.edit { preferences ->
            val current = readCurrent(preferences, keys)
            if (current != null) {
                resolved = current
            } else {
                val migrated = PhysicalNoiseModelMigration.migrate(readLegacy(preferences, keys))
                writeCurrent(preferences, keys, migrated)
                resolved = migrated
            }
            retireLegacyDynamicIsoRuntimeCarrier(preferences, keys)
        }
        return resolved ?: settingsFlow(lensId).first()
    }

    suspend fun get(lensId: String): LensPhysicalNoiseModelSettings = ensureMigrated(lensId)

    suspend fun set(lensId: String, settings: LensPhysicalNoiseModelSettings) {
        val keys = keys(lensId)
        context.dataStore.edit { preferences ->
            writeCurrent(preferences, keys, settings.sanitized())
        }
        PhysicalNoiseModelRuntimeRegistry.invalidate(lensId)
    }

    suspend fun update(
        lensId: String,
        transform: (LensPhysicalNoiseModelSettings) -> LensPhysicalNoiseModelSettings
    ): LensPhysicalNoiseModelSettings {
        val keys = keys(lensId)
        var result: LensPhysicalNoiseModelSettings? = null
        context.dataStore.edit { preferences ->
            val current = readCurrent(preferences, keys)
                ?: PhysicalNoiseModelMigration.migrate(readLegacy(preferences, keys))
            val updated = transform(current).sanitized()
            writeCurrent(preferences, keys, updated)
            result = updated
        }
        PhysicalNoiseModelRuntimeRegistry.invalidate(lensId)
        return requireNotNull(result)
    }

    suspend fun setSource(lensId: String, source: NoiseModelSource) =
        update(lensId) { it.copy(source = source) }

    suspend fun setDynamicIso(
        lensId: String,
        enabled: Boolean,
        coefficient: Double
    ) = update(lensId) {
        it.copy(
            dynamicIsoEnabled = enabled,
            dynamicIsoCoefficient = coefficient
        )
    }

    suspend fun setSystemModel(
        lensId: String,
        model: PersistedParametricNoiseModel?,
        origin: String?
    ) = update(lensId) {
        it.copy(systemModel = model, systemModelOrigin = origin)
    }

    suspend fun setManualModel(
        lensId: String,
        model: PersistedParametricNoiseModel?
    ) = update(lensId) {
        it.copy(manualModel = model)
    }

    suspend fun setSelectedPresetId(lensId: String, presetId: String?) =
        update(lensId) { it.copy(selectedPresetId = presetId) }

    private fun readCurrent(
        preferences: Preferences,
        keys: NoiseKeys
    ): LensPhysicalNoiseModelSettings? {
        val schemaVersion = preferences[keys.schemaVersion] ?: return null
        if (schemaVersion < PHYSICAL_NOISE_MODEL_SCHEMA_VERSION) return null

        val systemModel = PhysicalNoiseModelPersistenceCodec.decodeModel(
            a = preferences[keys.systemA],
            b = preferences[keys.systemB],
            c = preferences[keys.systemC],
            d = preferences[keys.systemD],
            isoStep = preferences[keys.systemIsoStep]
        )
        val manualModel = PhysicalNoiseModelPersistenceCodec.decodeModel(
            a = preferences[keys.manualA],
            b = preferences[keys.manualB],
            c = preferences[keys.manualC],
            d = preferences[keys.manualD],
            isoStep = preferences[keys.manualIsoStep]
        )

        return LensPhysicalNoiseModelSettings(
            schemaVersion = schemaVersion,
            source = parsePersistedNoiseModelSource(preferences[keys.source]),
            dynamicIsoEnabled = preferences[keys.dynamicIsoEnabled] ?: false,
            dynamicIsoCoefficient = sanitizePhysicalDynamicIsoCoefficient(
                (preferences[keys.dynamicIsoCoefficient] ?: 1.0f).toDouble()
            ),
            systemModel = systemModel,
            systemModelOrigin = preferences[keys.systemModelOrigin],
            manualModel = manualModel,
            selectedPresetId = preferences[keys.selectedPresetId],
            migration = PhysicalNoiseModelMigrationMetadata(
                migratedFromLegacy = preferences[keys.migratedFromLegacy] ?: false,
                legacyMode = preferences[keys.legacyMode],
                legacyManualSoRetained = preferences[keys.legacyManualSoRetained] ?: false,
                legacyParametricFieldsRetained = preferences[keys.legacyParametricFieldsRetained] ?: false,
                legacyDynamicIsoReset = preferences[keys.legacyDynamicIsoReset] ?: false
            )
        ).sanitized()
    }

    private fun readLegacy(
        preferences: Preferences,
        keys: NoiseKeys
    ): LegacyPhysicalNoiseModelState = LegacyPhysicalNoiseModelState(
        mode = preferences[keys.legacyNoiseType],
        noiseA = preferences[keys.legacyNoiseA],
        noiseB = preferences[keys.legacyNoiseB],
        noiseC = preferences[keys.legacyNoiseC],
        noiseD = preferences[keys.legacyNoiseD],
        isoStep = preferences[keys.legacyIsoStep],
        dynamicIsoCoefficient = preferences[keys.legacyDynamicIsoCoefficient]?.toDouble(),
        manualNoiseSo = preferences[keys.legacyManualNoiseSo]
    )

    private fun writeCurrent(
        preferences: MutablePreferences,
        keys: NoiseKeys,
        settings: LensPhysicalNoiseModelSettings
    ) {
        val safe = settings.sanitized()
        preferences[keys.schemaVersion] = PHYSICAL_NOISE_MODEL_SCHEMA_VERSION
        preferences[keys.source] = safe.source.name
        preferences[keys.dynamicIsoEnabled] = safe.dynamicIsoEnabled
        preferences[keys.dynamicIsoCoefficient] = safe.dynamicIsoCoefficient.toFloat()
        writeModel(preferences, safe.systemModel, keys.systemA, keys.systemB, keys.systemC, keys.systemD, keys.systemIsoStep)
        writeOptional(preferences, keys.systemModelOrigin, safe.systemModelOrigin)
        writeModel(preferences, safe.manualModel, keys.manualA, keys.manualB, keys.manualC, keys.manualD, keys.manualIsoStep)
        writeOptional(preferences, keys.selectedPresetId, safe.selectedPresetId)

        preferences[keys.migratedFromLegacy] = safe.migration.migratedFromLegacy
        writeOptional(preferences, keys.legacyMode, safe.migration.legacyMode)
        preferences[keys.legacyManualSoRetained] = safe.migration.legacyManualSoRetained
        preferences[keys.legacyParametricFieldsRetained] = safe.migration.legacyParametricFieldsRetained
        preferences[keys.legacyDynamicIsoReset] = safe.migration.legacyDynamicIsoReset
        retireLegacyDynamicIsoRuntimeCarrier(preferences, keys)
    }

    /**
     * The legacy coefficient fed SensorNoiseCalibrationMapper and native IspCore's
     * `dynamicUserGain`. New Dynamic ISO is already consumed when resolving physical A/B/C/D.
     * Force the retired carrier to neutral zero so 0.30 in the new UI means exactly one Dynamic ISO
     * transform, not the BnCam transform plus a second SPECTRA authority boost.
     */
    private fun retireLegacyDynamicIsoRuntimeCarrier(
        preferences: MutablePreferences,
        keys: NoiseKeys
    ) {
        preferences[keys.legacyDynamicIsoCoefficient] = 0.0f
    }

    private fun writeModel(
        preferences: MutablePreferences,
        model: PersistedParametricNoiseModel?,
        aKey: Preferences.Key<String>,
        bKey: Preferences.Key<String>,
        cKey: Preferences.Key<String>,
        dKey: Preferences.Key<String>,
        isoStepKey: Preferences.Key<String>
    ) {
        if (model == null) {
            preferences.remove(aKey)
            preferences.remove(bKey)
            preferences.remove(cKey)
            preferences.remove(dKey)
            preferences.remove(isoStepKey)
            return
        }
        val encoded = PhysicalNoiseModelPersistenceCodec.encodeModel(model)
        preferences[aKey] = encoded.a
        preferences[bKey] = encoded.b
        preferences[cKey] = encoded.c
        preferences[dKey] = encoded.d
        preferences[isoStepKey] = encoded.isoStep
    }

    private fun writeOptional(
        preferences: MutablePreferences,
        key: Preferences.Key<String>,
        value: String?
    ) {
        val clean = value?.trim()?.takeIf(String::isNotEmpty)
        if (clean == null) preferences.remove(key) else preferences[key] = clean
    }

    private fun keys(lensId: String): NoiseKeys {
        val lensPart = SettingsRepository.safeLensKeyPart(lensId)
        val base = "hardware_lens_${lensPart}_"
        val physical = "${base}physical_noise_"
        return NoiseKeys(
            schemaVersion = intPreferencesKey("${physical}schema_version"),
            source = stringPreferencesKey("${physical}source"),
            dynamicIsoEnabled = booleanPreferencesKey("${physical}dynamic_iso_enabled"),
            dynamicIsoCoefficient = floatPreferencesKey("${physical}dynamic_iso_coefficient"),
            systemA = stringPreferencesKey("${physical}system_a"),
            systemB = stringPreferencesKey("${physical}system_b"),
            systemC = stringPreferencesKey("${physical}system_c"),
            systemD = stringPreferencesKey("${physical}system_d"),
            systemIsoStep = stringPreferencesKey("${physical}system_iso_step"),
            systemModelOrigin = stringPreferencesKey("${physical}system_origin"),
            manualA = stringPreferencesKey("${physical}manual_a"),
            manualB = stringPreferencesKey("${physical}manual_b"),
            manualC = stringPreferencesKey("${physical}manual_c"),
            manualD = stringPreferencesKey("${physical}manual_d"),
            manualIsoStep = stringPreferencesKey("${physical}manual_iso_step"),
            selectedPresetId = stringPreferencesKey("${physical}selected_preset_id"),
            migratedFromLegacy = booleanPreferencesKey("${physical}migrated_from_legacy"),
            legacyMode = stringPreferencesKey("${physical}legacy_mode"),
            legacyManualSoRetained = booleanPreferencesKey("${physical}legacy_manual_so_retained"),
            legacyParametricFieldsRetained = booleanPreferencesKey("${physical}legacy_parametric_fields_retained"),
            legacyDynamicIsoReset = booleanPreferencesKey("${physical}legacy_dynamic_iso_reset"),
            legacyNoiseType = stringPreferencesKey("${base}noise_type"),
            legacyNoiseA = stringPreferencesKey("${base}noise_a"),
            legacyNoiseB = stringPreferencesKey("${base}noise_b"),
            legacyNoiseC = stringPreferencesKey("${base}noise_c"),
            legacyNoiseD = stringPreferencesKey("${base}noise_d"),
            legacyIsoStep = stringPreferencesKey("${base}iso_step"),
            legacyDynamicIsoCoefficient = floatPreferencesKey("${base}dynamic_iso"),
            legacyManualNoiseSo = stringPreferencesKey("${base}noise_so")
        )
    }

    private data class NoiseKeys(
        val schemaVersion: Preferences.Key<Int>,
        val source: Preferences.Key<String>,
        val dynamicIsoEnabled: Preferences.Key<Boolean>,
        val dynamicIsoCoefficient: Preferences.Key<Float>,
        val systemA: Preferences.Key<String>,
        val systemB: Preferences.Key<String>,
        val systemC: Preferences.Key<String>,
        val systemD: Preferences.Key<String>,
        val systemIsoStep: Preferences.Key<String>,
        val systemModelOrigin: Preferences.Key<String>,
        val manualA: Preferences.Key<String>,
        val manualB: Preferences.Key<String>,
        val manualC: Preferences.Key<String>,
        val manualD: Preferences.Key<String>,
        val manualIsoStep: Preferences.Key<String>,
        val selectedPresetId: Preferences.Key<String>,
        val migratedFromLegacy: Preferences.Key<Boolean>,
        val legacyMode: Preferences.Key<String>,
        val legacyManualSoRetained: Preferences.Key<Boolean>,
        val legacyParametricFieldsRetained: Preferences.Key<Boolean>,
        val legacyDynamicIsoReset: Preferences.Key<Boolean>,
        val legacyNoiseType: Preferences.Key<String>,
        val legacyNoiseA: Preferences.Key<String>,
        val legacyNoiseB: Preferences.Key<String>,
        val legacyNoiseC: Preferences.Key<String>,
        val legacyNoiseD: Preferences.Key<String>,
        val legacyIsoStep: Preferences.Key<String>,
        val legacyDynamicIsoCoefficient: Preferences.Key<Float>,
        val legacyManualNoiseSo: Preferences.Key<String>
    )
}
