@file:Suppress("SpellCheckingInspection")

package com.bncam.data.settings

import com.bncam.core.capture.MeteringMode
import android.content.Context
import android.graphics.ImageFormat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bncam.core.engine.CaptureStrategy
import com.bncam.ui.screens.capture.ViewfinderStream
import com.bncam.core.capture.FrameCapacityPolicy
import com.bncam.core.capture.FrameOrigin
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.quality.DefaultIspProfile
import com.bncam.data.profile.BncFrameSourceResolution
import com.bncam.data.profile.BncProfileCodec
import com.bncam.data.profile.BncProfileCompatibility
import com.bncam.data.profile.BncProfileDocument
import com.bncam.data.profile.BncProfileMetadata
import com.bncam.data.profile.BncTargetCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

internal fun sanitizeDynamicIsoCoefficient(value: Float): Float {
    if (!value.isFinite()) return 0f
    return (value.coerceIn(0f, 1f) * 100f).roundToInt() / 100f
}

internal fun parseCameraFormatCode(raw: String): Int? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return runCatching {
        when {
            value.startsWith("0x", ignoreCase = true) -> value.substring(2).toLong(16).toInt()
            else -> value.toLong().toInt()
        }
    }.getOrNull()?.takeIf { it >= 0 }
}

// android.graphics.ImageFormat.Y16 exists in the platform source but is @hide,
// so it is intentionally represented by its stable platform format code here instead
// of referencing a symbol that is absent from the public SDK stubs.
private const val IMAGE_FORMAT_Y16 = 0x20363159

internal enum class RawPreviewFormatCompatibility {
    CANONICAL,
    CUSTOM_VENDOR_UNVERIFIED,
    INCOMPATIBLE_STANDARD
}

internal fun rawPreviewFormatCompatibility(source: String, formatCode: Int): RawPreviewFormatCompatibility {
    val canonical = when (source.trim().uppercase()) {
        "RAW10" -> ImageFormat.RAW10
        "RAW_SENSOR" -> ImageFormat.RAW_SENSOR
        else -> return RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD
    }
    if (formatCode == canonical) return RawPreviewFormatCompatibility.CANONICAL

    // These are public Android image layouts with known semantics that do not match the
    // selected RAW decoder. Unknown/vendor values deliberately remain eligible for runtime
    // validation because that is the purpose of the custom stream-code facility.
    val knownStandardFormats = setOf(
        ImageFormat.YUV_420_888,
        ImageFormat.RAW_SENSOR,
        ImageFormat.RAW10,
        ImageFormat.RAW12,
        ImageFormat.RAW_PRIVATE,
        ImageFormat.JPEG,
        ImageFormat.DEPTH16,
        ImageFormat.DEPTH_POINT_CLOUD,
        ImageFormat.PRIVATE,
        ImageFormat.Y8,
        IMAGE_FORMAT_Y16,
        ImageFormat.NV21,
        ImageFormat.YUY2,
        ImageFormat.RGB_565,
        ImageFormat.FLEX_RGB_888,
        ImageFormat.FLEX_RGBA_8888
    )
    return if (formatCode in knownStandardFormats) {
        RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD
    } else {
        RawPreviewFormatCompatibility.CUSTOM_VENDOR_UNVERIFIED
    }
}

enum class VendorTagTarget {
    SESSION,
    REPEATING_REQUEST,
    STILL_CAPTURE,
    REQUEST_BOTH,
    RESULT_ONLY,
    CHARACTERISTIC_ONLY,
    UNKNOWN
}

enum class VendorTagSource {
    DEVICE_SCAN,
    HIDDEN_SCAN,
    REFLECTION_SCAN,
    ASSET_CATALOG,
    RECIPE,
    MANUAL,
    UNKNOWN
}

enum class VendorValueOrigin {
    AUTO,
    MANUAL,
    RECIPE_DEFAULT,
    EMPTY
}

data class VendorTagConfig(
    val slotName: String,
    val lensId: String,
    val keyName: String,
    val value: String,
    val type: String,
    val enabled: Boolean = true,
    val target: VendorTagTarget = VendorTagTarget.REPEATING_REQUEST,
    val source: VendorTagSource = VendorTagSource.MANUAL,
    val recipeId: String = "",
    val valueOrigin: VendorValueOrigin = VendorValueOrigin.MANUAL,
    val requiresSessionRebuild: Boolean = false,
    val notes: String = ""
) {
    fun serialize(): String {
        return listOf(
            FORMAT_VERSION,
            slotName,
            lensId,
            keyName,
            value,
            type,
            enabled.toString(),
            target.name,
            source.name,
            recipeId,
            valueOrigin.name,
            requiresSessionRebuild.toString(),
            notes
        ).joinToString(SEPARATOR)
    }

    fun identityKey(): String {
        return listOf(lensId, slotName, keyName, target.name, recipeId).joinToString("|")
    }

    companion object {
        private const val FORMAT_VERSION = "v2"
        private const val SEPARATOR = "|#|"

        fun deserialize(data: String): VendorTagConfig? {
            val parts = data.split(SEPARATOR)

            return when {
                parts.size == 5 -> {
                    // Legacy format:
                    // slotName|#|lensId|#|keyName|#|value|#|type
                    VendorTagConfig(
                        slotName = parts[0],
                        lensId = parts[1],
                        keyName = parts[2],
                        value = parts[3],
                        type = parts[4],
                        enabled = true,
                        target = VendorTagTarget.REPEATING_REQUEST,
                        source = VendorTagSource.MANUAL,
                        recipeId = "",
                        valueOrigin = VendorValueOrigin.MANUAL,
                        requiresSessionRebuild = false,
                        notes = "Migrated from legacy vendor tag config."
                    )
                }

                parts.size >= 13 && parts[0] == FORMAT_VERSION -> {
                    VendorTagConfig(
                        slotName = parts[1],
                        lensId = parts[2],
                        keyName = parts[3],
                        value = parts[4],
                        type = parts[5],
                        enabled = parts[6].toBooleanStrictOrNull() ?: true,
                        target = parts[7].toEnumOrDefault(VendorTagTarget.UNKNOWN),
                        source = parts[8].toEnumOrDefault(VendorTagSource.UNKNOWN),
                        recipeId = parts[9],
                        valueOrigin = parts[10].toEnumOrDefault(VendorValueOrigin.EMPTY),
                        requiresSessionRebuild = parts[11].toBooleanStrictOrNull() ?: false,
                        notes = parts.drop(12).joinToString(SEPARATOR)
                    )
                }

                else -> null
            }
        }

        private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T {
            return enumValues<T>().firstOrNull { it.name == this } ?: default
        }
    }
}

enum class ProfileSettingValueType {
    FLOAT,
    INT,
    BOOLEAN,
    STRING
}

data class ProfileSettingSpec(
    val key: String,
    val type: ProfileSettingValueType,
    /** Portable effective default used only when a complete profile snapshot is exported. */
    val portableDefault: String? = null
)

data class ProfileSettingsSnapshot(
    val floatValues: Map<String, Float> = emptyMap(),
    val intValues: Map<String, Int> = emptyMap(),
    val booleanValues: Map<String, Boolean> = emptyMap(),
    val stringValues: Map<String, String> = emptyMap()
) {
    val totalCount: Int get() = floatValues.size + intValues.size + booleanValues.size + stringValues.size
}

data class BncProfileExportResult(
    val contents: String,
    val suggestedFileName: String,
    val profileUuid: String,
    val profileName: String,
    val exportedSettings: Int
)

data class BncProfileImportResult(
    val profileUuid: String,
    val profileName: String,
    val importedSettings: Int,
    val sourceSchemaVersion: Int,
    val migrated: Boolean,
    val frameSourceResolution: BncFrameSourceResolution,
    val ignoredUnknownSettingKeys: Set<String>,
    val fallbackSettingKeys: Set<String>,
    val warnings: List<String>
)

// Dit creëert het fysieke bestandje "bncam_settings.preferences_pb" op de telefoon
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bncam_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        const val LENS_AUTO_ASSIGNMENT_VERSION = 2

        val PRIMARY_SLOTS = listOf("Main", "Ultra wide", "Tele", "Front")
        val EXTRA_SLOTS = listOf("Extra 1", "Extra 2", "Extra 3", "Extra 4", "Extra 5")
        val ALL_SLOTS = PRIMARY_SLOTS + EXTRA_SLOTS

        fun buildStableLensKey(
            logicalCameraId: String,
            physicalCameraId: String? = null
        ): String {
            if (logicalCameraId.startsWith("lens_v2_")) return logicalCameraId
            return StableLensKey.fromRawPhysicalId(logicalCameraId, physicalCameraId).value
        }

        fun safeLensKeyPart(lensId: String): String = StableLensKey.fromString(lensId).value
    }

    // ==========================================
    // 1. GLOBAL APP SETTINGS (FILE, INTERACTION, DEBUG)
    // ==========================================

    // --- KEYS ---
    private val forceGooglePhotosKey = booleanPreferencesKey("force_google_photos")
    private val saveLocationKey = stringPreferencesKey("save_location")
    private val saveFormatKey = stringPreferencesKey("save_format")
    private val computationalHdrEnabledKey = booleanPreferencesKey("computational_hdr_enabled")
    private val hdrEnhancedFrameSettingKey = stringPreferencesKey("hdr_enhanced_frames")
    private val ultraHdrGainmapEnabledKey = booleanPreferencesKey("ultra_hdr_gainmap_enabled")
    private val portraitEffectEnabledKey = booleanPreferencesKey("portrait_effect_enabled")
    private val photoPrefixKey = stringPreferencesKey("photo_prefix")

    private val hapticFeedbackKey = booleanPreferencesKey("haptic_feedback")
    private val cameraSoundsKey = booleanPreferencesKey("camera_sounds")
    private val volumeButtonActionKey = stringPreferencesKey("volume_button_action")
    private val forceMaxBrightnessKey = booleanPreferencesKey("force_max_brightness")

    private val saveLocationDataKey = booleanPreferencesKey("save_location_data")
    private val enableShotLoggerKey = booleanPreferencesKey("enable_shotlogger")
    private val phoneAssistanceSensorsKey = booleanPreferencesKey("phone_assistance_sensors")

    private val facePriorityFocusKey = booleanPreferencesKey("vf_face_priority_focus")
    private val stabilizationStatusKey = stringPreferencesKey("stabilization_status")

    private val activeLensIdKey = stringPreferencesKey("active_lens_id")
    private val activeProfileIdKey = stringPreferencesKey("active_profile_id")
    private val quickSettingsAssignmentsKey = stringPreferencesKey("vf_quick_settings_assignments")
    private val supportedQuickSettingIds = listOf(
        "flash", "timer", "watermark", "output", "viewfinder", "geotag",
        "focus_peaking", "metering", "histogram", "focus_track",
        "horizon_leveler", "face_detection"
    )
    private val defaultQuickSettingAssignments = supportedQuickSettingIds.take(9)

    // ShotLogger Toggles
    private val logSummaryKey = booleanPreferencesKey("log_summary")
    private val logActiveModeKey = booleanPreferencesKey("log_active_mode")
    private val logProfileSettingsKey = booleanPreferencesKey("log_profile_settings")
    private val logFrameAnalysisKey = booleanPreferencesKey("log_frame_analysis")
    private val logWarningsKey = booleanPreferencesKey("log_warnings")
    private val logPipelineDebugKey = booleanPreferencesKey("log_pipeline_debug")
    private val logVendorInjectionKey = booleanPreferencesKey("log_vendor_injection")

    // --- GETTERS (Flows) ---
    val phoneAssistanceSensorsFlow: Flow<Boolean> = context.dataStore.data.map { it[phoneAssistanceSensorsKey] ?: false }
    val forceGooglePhotosFlow: Flow<Boolean> = context.dataStore.data.map { it[forceGooglePhotosKey] ?: false }
    val saveLocationFlow: Flow<String> = context.dataStore.data.map { it[saveLocationKey] ?: "DCIM/BnCam" }
    val outputPolicyFlow: Flow<OutputPolicy> = context.dataStore.data.map {
        OutputPolicy.parse(it[saveFormatKey])
    }
    val computationalHdrEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[computationalHdrEnabledKey] ?: false
    }
    val hdrEnhancedFrameSettingFlow: Flow<String> = context.dataStore.data.map {
        it[hdrEnhancedFrameSettingKey] ?: "Auto"
    }
    val ultraHdrGainmapEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[ultraHdrGainmapEnabledKey] ?: false
    }
    val portraitEffectEnabledFlow: Flow<Boolean> = context.dataStore.data.map {
        it[portraitEffectEnabledKey] ?: false
    }
    val photoPrefixFlow: Flow<String> = context.dataStore.data.map { it[photoPrefixKey] ?: "IMG_BNC_" }

    val hapticFeedbackFlow: Flow<Boolean> = context.dataStore.data.map { it[hapticFeedbackKey] ?: true }
    val cameraSoundsFlow: Flow<Boolean> = context.dataStore.data.map { it[cameraSoundsKey] ?: true }
    val volumeButtonActionFlow: Flow<String> = context.dataStore.data.map { it[volumeButtonActionKey] ?: "Take Photo" }
    val forceMaxBrightnessFlow: Flow<Boolean> = context.dataStore.data.map { it[forceMaxBrightnessKey] ?: false }

    val saveLocationDataFlow: Flow<Boolean> = context.dataStore.data.map { it[saveLocationDataKey] ?: false }
    val enableShotLoggerFlow: Flow<Boolean> = context.dataStore.data.map { it[enableShotLoggerKey] ?: false }

    val logSummaryFlow: Flow<Boolean> = context.dataStore.data.map { it[logSummaryKey] ?: true }
    val logActiveModeFlow: Flow<Boolean> = context.dataStore.data.map { it[logActiveModeKey] ?: true }
    val logProfileSettingsFlow: Flow<Boolean> = context.dataStore.data.map { it[logProfileSettingsKey] ?: true }
    val logFrameAnalysisFlow: Flow<Boolean> = context.dataStore.data.map { it[logFrameAnalysisKey] ?: true }
    val logWarningsFlow: Flow<Boolean> = context.dataStore.data.map { it[logWarningsKey] ?: true }
    val logPipelineDebugFlow: Flow<Boolean> = context.dataStore.data.map { it[logPipelineDebugKey] ?: true }
    val logVendorInjectionFlow: Flow<Boolean> = context.dataStore.data.map { it[logVendorInjectionKey] ?: true }

    val facePriorityFocusFlow: Flow<Boolean> = context.dataStore.data.map { it[facePriorityFocusKey] ?: false }
    val stabilizationStatusFlow: Flow<String> = context.dataStore.data.map { it[stabilizationStatusKey] ?: "Stabilization probing failed" }

    val activeLensIdFlow: Flow<String?> = context.dataStore.data.map { it[activeLensIdKey] }
    val activeProfileIdFlow: Flow<String?> = context.dataStore.data.map { it[activeProfileIdKey] }
    val quickSettingsAssignmentsFlow: Flow<List<String>> = context.dataStore.data.map { preferences ->
        val stored = preferences[quickSettingsAssignmentsKey]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it in supportedQuickSettingIds }
            ?.distinct()
            .orEmpty()
        val resolved = stored.take(9).toMutableList()
        defaultQuickSettingAssignments.forEach { id ->
            if (resolved.size < 9 && id !in resolved) resolved += id
        }
        supportedQuickSettingIds.forEach { id ->
            if (resolved.size < 9 && id !in resolved) resolved += id
        }
        resolved.take(9)
    }

    // --- SETTERS (Suspend functions) ---
    suspend fun setForceGooglePhotos(enabled: Boolean) = context.dataStore.edit { it[forceGooglePhotosKey] = enabled }
    suspend fun setSaveLocation(value: String) = context.dataStore.edit { it[saveLocationKey] = value }
    suspend fun setOutputPolicy(value: OutputPolicy) = context.dataStore.edit {
        it[saveFormatKey] = value.persistedValue
    }
    suspend fun setComputationalHdrEnabled(enabled: Boolean) = context.dataStore.edit {
        it[computationalHdrEnabledKey] = enabled
    }
    suspend fun setHdrEnhancedFrameSetting(value: String) = context.dataStore.edit {
        it[hdrEnhancedFrameSettingKey] = value
    }
    suspend fun setUltraHdrGainmapEnabled(enabled: Boolean) = context.dataStore.edit {
        it[ultraHdrGainmapEnabledKey] = enabled
    }
    suspend fun setPortraitEffectEnabled(enabled: Boolean) = context.dataStore.edit {
        it[portraitEffectEnabledKey] = enabled
    }
    suspend fun setPhotoPrefix(value: String) = context.dataStore.edit { it[photoPrefixKey] = value }

    suspend fun setHapticFeedback(enabled: Boolean) = context.dataStore.edit { it[hapticFeedbackKey] = enabled }
    suspend fun setCameraSounds(enabled: Boolean) = context.dataStore.edit { it[cameraSoundsKey] = enabled }
    suspend fun setVolumeButtonAction(value: String) = context.dataStore.edit { it[volumeButtonActionKey] = value }
    suspend fun setForceMaxBrightness(enabled: Boolean) = context.dataStore.edit { it[forceMaxBrightnessKey] = enabled }

    suspend fun setSaveLocationData(enabled: Boolean) = context.dataStore.edit { it[saveLocationDataKey] = enabled }
    suspend fun setEnableShotLogger(enabled: Boolean) = context.dataStore.edit { it[enableShotLoggerKey] = enabled }

    suspend fun setActiveLensId(lensId: String) = context.dataStore.edit { it[activeLensIdKey] = lensId }
    suspend fun setActiveProfileId(profileId: String) = context.dataStore.edit { it[activeProfileIdKey] = profileId }
    suspend fun setQuickSettingsAssignments(assignments: List<String>) = context.dataStore.edit { preferences ->
        val resolved = assignments
            .filter { it in supportedQuickSettingIds }
            .distinct()
            .take(9)
            .toMutableList()
        defaultQuickSettingAssignments.forEach { id ->
            if (resolved.size < 9 && id !in resolved) resolved += id
        }
        preferences[quickSettingsAssignmentsKey] = resolved.joinToString(",")
    }

    /** Persist the user-visible camera route atomically so collectors never observe a new lens
     * paired with the previous lens' profile (or vice versa). */
    suspend fun setActiveLensAndProfile(lensId: String, profileId: String) = context.dataStore.edit {
        it[activeLensIdKey] = lensId
        it[activeProfileIdKey] = profileId
    }

    suspend fun setLogSummary(enabled: Boolean) = context.dataStore.edit { it[logSummaryKey] = enabled }
    suspend fun setLogActiveMode(enabled: Boolean) = context.dataStore.edit { it[logActiveModeKey] = enabled }
    suspend fun setLogProfileSettings(enabled: Boolean) = context.dataStore.edit { it[logProfileSettingsKey] = enabled }
    suspend fun setLogFrameAnalysis(enabled: Boolean) = context.dataStore.edit { it[logFrameAnalysisKey] = enabled }
    suspend fun setLogWarnings(enabled: Boolean) = context.dataStore.edit { it[logWarningsKey] = enabled }
    suspend fun setLogPipelineDebug(enabled: Boolean) = context.dataStore.edit { it[logPipelineDebugKey] = enabled }
    suspend fun setLogVendorInjection(enabled: Boolean) = context.dataStore.edit { it[logVendorInjectionKey] = enabled }

    suspend fun setFacePriorityFocus(enabled: Boolean) = context.dataStore.edit { it[facePriorityFocusKey] = enabled }
    suspend fun setStabilizationStatus(status: String) = context.dataStore.edit { it[stabilizationStatusKey] = status }

    // ==========================================
    // VIEWFINDER SETTINGS (Kaart 1 t/m 6)
    // ==========================================

    // --- Global preview source ---
    private val viewfinderStreamKey = stringPreferencesKey("vf_stream")

    val viewfinderStreamFlow: Flow<ViewfinderStream> = context.dataStore.data.map {
        ViewfinderStream.parse(it[viewfinderStreamKey])
    }

    suspend fun setViewfinderStream(value: ViewfinderStream) {
        com.bncam.core.debug.RawPreviewFirstActivationTrace.viewfinderSettingRequested(
            selectedBuffer = value == ViewfinderStream.SELECTED_BUFFER
        )
        context.dataStore.edit { it[viewfinderStreamKey] = value.persistedValue }
    }

    // --- Kaart 1: Composition & Guides ---
    private val gridLinesKey = stringPreferencesKey("vf_grid_lines")
    private val horizonLevelerKey = booleanPreferencesKey("vf_horizon_leveler")
    private val centerCrosshairKey = booleanPreferencesKey("vf_center_crosshair")

    val gridLinesFlow: Flow<String> = context.dataStore.data.map { it[gridLinesKey] ?: "Off" }
    val horizonLevelerFlow: Flow<Boolean> = context.dataStore.data.map { it[horizonLevelerKey] ?: false }
    val centerCrosshairFlow: Flow<Boolean> = context.dataStore.data.map { it[centerCrosshairKey] ?: false }

    suspend fun setGridLines(value: String) = context.dataStore.edit { it[gridLinesKey] = value }
    suspend fun setHorizonLeveler(enabled: Boolean) = context.dataStore.edit { it[horizonLevelerKey] = enabled }
    suspend fun setCenterCrosshair(enabled: Boolean) = context.dataStore.edit { it[centerCrosshairKey] = enabled }

    // --- Kaart 2: Focus ---
    private val focusDataKey = booleanPreferencesKey("vf_focus_data")
    private val focusSliderKey = booleanPreferencesKey("vf_focus_slider")
    private val isoSliderKey = booleanPreferencesKey("vf_iso_slider")
    private val shutterSpeedSliderKey = booleanPreferencesKey("vf_shutter_slider")

    private val focusModeKey = stringPreferencesKey("vf_focus_mode")

    private val nearZslFocusSelectionKey = booleanPreferencesKey("vf_near_zsl_focus_selection")

    private val focusLockKey = stringPreferencesKey("vf_focus_lock")
    private val focusTrackingKey = booleanPreferencesKey("vf_focus_tracking")
    private val focusRingKey = booleanPreferencesKey("vf_focus_ring")
    private val focusPeakKey = booleanPreferencesKey("vf_focus_peak")
    private val focusPeakColorKey = stringPreferencesKey("vf_focus_peak_color")
    private val resetFocusCaptureKey = booleanPreferencesKey("vf_reset_focus_capture")

    private val leftSliderAssignmentKey = stringPreferencesKey("vf_left_slider_assignment")
    private val rightSliderAssignmentKey = stringPreferencesKey("vf_right_slider_assignment")

    val focusDataFlow: Flow<Boolean> = context.dataStore.data.map { it[focusDataKey] ?: false }
    val leftSliderAssignmentFlow: Flow<ViewfinderSliderAssignment> = context.dataStore.data.map { prefs ->
        prefs[leftSliderAssignmentKey]?.let(ViewfinderSliderAssignment::fromPersisted) ?: when {
            prefs[focusSliderKey] == true -> ViewfinderSliderAssignment.FOCUS
            else -> ViewfinderSliderAssignment.OFF
        }
    }
    val rightSliderAssignmentFlow: Flow<ViewfinderSliderAssignment> = context.dataStore.data.map { prefs ->
        prefs[rightSliderAssignmentKey]?.let(ViewfinderSliderAssignment::fromPersisted) ?: when {
            prefs[exposureSliderKey] == true -> ViewfinderSliderAssignment.EV
            else -> ViewfinderSliderAssignment.OFF
        }
    }
    // Dedicated exposure halo controls are intentionally independent from the assignable side sliders.
    // Reuse the original persisted keys so existing user preference survives the temporary retirement.
    val isoSliderFlow: Flow<Boolean> = context.dataStore.data.map { it[isoSliderKey] ?: false }
    val shutterSpeedSliderFlow: Flow<Boolean> = context.dataStore.data.map { it[shutterSpeedSliderKey] ?: false }

    val focusModeFlow: Flow<String> = context.dataStore.data.map { it[focusModeKey] ?: "Continuous" }

    val nearZslFocusSelectionFlow: Flow<Boolean> = context.dataStore.data.map { it[nearZslFocusSelectionKey] ?: true }

    val focusLockFlow: Flow<String> = context.dataStore.data.map { it[focusLockKey] ?: "3s" }
    val focusTrackingFlow: Flow<Boolean> = context.dataStore.data.map { it[focusTrackingKey] ?: false }
    val focusRingFlow: Flow<Boolean> = context.dataStore.data.map { it[focusRingKey] ?: true }
    val focusPeakFlow: Flow<Boolean> = context.dataStore.data.map { it[focusPeakKey] ?: false }
    val focusPeakColorFlow: Flow<String> = context.dataStore.data.map { it[focusPeakColorKey] ?: "Red" }
    val resetFocusCaptureFlow: Flow<Boolean> = context.dataStore.data.map { it[resetFocusCaptureKey] ?: false }

    suspend fun setPhoneAssistanceSensors(enabled: Boolean) = context.dataStore.edit { it[phoneAssistanceSensorsKey] = enabled }

    suspend fun setFocusData(enabled: Boolean) = context.dataStore.edit { it[focusDataKey] = enabled }
    suspend fun setLeftSliderAssignment(value: ViewfinderSliderAssignment) = context.dataStore.edit { prefs ->
        prefs[leftSliderAssignmentKey] = value.displayName
        prefs.remove(focusSliderKey)
    }
    suspend fun setRightSliderAssignment(value: ViewfinderSliderAssignment) = context.dataStore.edit { prefs ->
        prefs[rightSliderAssignmentKey] = value.displayName
        prefs.remove(exposureSliderKey)
    }
    suspend fun setIsoSlider(enabled: Boolean) = context.dataStore.edit { it[isoSliderKey] = enabled }
    suspend fun setShutterSpeedSlider(enabled: Boolean) = context.dataStore.edit { it[shutterSpeedSliderKey] = enabled }

    suspend fun setFocusMode(value: String) = context.dataStore.edit { it[focusModeKey] = value }

    suspend fun setNearZslFocusSelection(enabled: Boolean) = context.dataStore.edit { it[nearZslFocusSelectionKey] = enabled }

    suspend fun setFocusLock(value: String) = context.dataStore.edit { it[focusLockKey] = value }
    suspend fun setFocusTracking(enabled: Boolean) = context.dataStore.edit { it[focusTrackingKey] = enabled }
    suspend fun setFocusRing(enabled: Boolean) = context.dataStore.edit { it[focusRingKey] = enabled }
    suspend fun setFocusPeak(enabled: Boolean) = context.dataStore.edit { it[focusPeakKey] = enabled }
    suspend fun setFocusPeakColor(value: String) = context.dataStore.edit { it[focusPeakColorKey] = value }
    suspend fun setResetFocusCapture(enabled: Boolean) = context.dataStore.edit { it[resetFocusCaptureKey] = enabled }

    // --- Kaart 3: Exposure ---
    private val meteringStyleKey = stringPreferencesKey("vf_metering_style")
    private val exposureSliderKey = booleanPreferencesKey("vf_exposure_slider")
    private val histogramKey = booleanPreferencesKey("vf_histogram")

    val meteringStyleFlow: Flow<String> = context.dataStore.data.map {
        com.bncam.core.capture.MeteringMode.fromSetting(
            it[meteringStyleKey] ?: MeteringMode.AUTO_DEFAULT_AE.settingValue
        ).settingValue
    }
    val histogramFlow: Flow<Boolean> = context.dataStore.data.map { it[histogramKey] ?: false }

    suspend fun setMeteringStyle(value: String) = context.dataStore.edit {
        it[meteringStyleKey] = com.bncam.core.capture.MeteringMode.fromSetting(value).settingValue
    }
    suspend fun setHistogram(enabled: Boolean) = context.dataStore.edit { it[histogramKey] = enabled }

    // --- Kaart 4: Stabilization & Processing ---
    private val opticalStabilizationKey = booleanPreferencesKey("vf_ois")
    private val hotPixelModeKey = stringPreferencesKey("vf_hot_pixel")
    private val noiseReductionHintKey = stringPreferencesKey("vf_nr_hint")
    private val edgeModeHintKey = stringPreferencesKey("vf_edge_hint")
    private val tonemapHintKey = stringPreferencesKey("vf_tonemap_hint")
    private val antiBandingKey = stringPreferencesKey("vf_antibanding")

    private fun normalizeCam2ProcessingDefault(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            "off" -> "Off"
            "high quality", "high_quality", "highquality" -> "High Quality"
            "minimal" -> "Minimal"
            "zsl", "zero shutter lag" -> "ZSL"
            "fast" -> "Fast"
            else -> "Off"
        }
    }

    private fun normalizeTonemapHint(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            "high quality", "high_quality", "highquality" -> "High Quality"
            "contrast curve", "contrast_curve", "contrastcurve" -> "Contrast Curve"
            "fast" -> "Fast"
            "off" -> "Off"
            else -> "Off"
        }
    }

    val opticalStabilizationFlow: Flow<Boolean> = context.dataStore.data.map { it[opticalStabilizationKey] ?: true }
    val hotPixelModeFlow: Flow<String> = context.dataStore.data.map { normalizeCam2ProcessingDefault(it[hotPixelModeKey]) }
    val noiseReductionHintFlow: Flow<String> = context.dataStore.data.map { normalizeCam2ProcessingDefault(it[noiseReductionHintKey]) }
    val edgeModeHintFlow: Flow<String> = context.dataStore.data.map { normalizeCam2ProcessingDefault(it[edgeModeHintKey]) }
    val tonemapHintFlow: Flow<String> = context.dataStore.data.map { normalizeTonemapHint(it[tonemapHintKey]) }
    val antiBandingFlow: Flow<String> = context.dataStore.data.map { it[antiBandingKey] ?: "Auto" }

    suspend fun setOpticalStabilization(enabled: Boolean) = context.dataStore.edit { it[opticalStabilizationKey] = enabled }
    suspend fun setHotPixelMode(value: String) = context.dataStore.edit { it[hotPixelModeKey] = normalizeCam2ProcessingDefault(value) }
    suspend fun setNoiseReductionHint(value: String) = context.dataStore.edit { it[noiseReductionHintKey] = normalizeCam2ProcessingDefault(value) }
    suspend fun setEdgeModeHint(value: String) = context.dataStore.edit { it[edgeModeHintKey] = normalizeCam2ProcessingDefault(value) }
    suspend fun setTonemapHint(value: String) = context.dataStore.edit { it[tonemapHintKey] = normalizeTonemapHint(value) }
    suspend fun setAntiBanding(value: String) = context.dataStore.edit { it[antiBandingKey] = value }

    // --- Kaart 5: Detection ---
    private val faceDetectionKey = booleanPreferencesKey("vf_face_detection")
    private val qrDetectionKey = booleanPreferencesKey("vf_qr_detection")

    val faceDetectionFlow: Flow<Boolean> = context.dataStore.data.map { it[faceDetectionKey] ?: false }
    val qrDetectionFlow: Flow<Boolean> = context.dataStore.data.map { it[qrDetectionKey] ?: false }

    suspend fun setFaceDetection(enabled: Boolean) = context.dataStore.edit { it[faceDetectionKey] = enabled }
    suspend fun setQrDetection(enabled: Boolean) = context.dataStore.edit { it[qrDetectionKey] = enabled }

    // --- Kaart 6: Others ---
    private val doubleTapActionKey = stringPreferencesKey("vf_double_tap")
    private val mirrorFrontPreviewKey = booleanPreferencesKey("vf_mirror_front")

    private val timerDurationKey = intPreferencesKey("vf_timer_duration")

    private val flashModeKey = stringPreferencesKey("vf_flash_mode")

    val doubleTapActionFlow: Flow<String> = context.dataStore.data.map {
        when (val stored = it[doubleTapActionKey]) {
            null, "None" -> "2x Zoom"
            else -> stored
        }
    }
    val mirrorFrontPreviewFlow: Flow<Boolean> = context.dataStore.data.map { it[mirrorFrontPreviewKey] ?: true }

    val timerDurationFlow: Flow<Int> = context.dataStore.data.map { it[timerDurationKey] ?: 0 }

    val flashModeFlow: Flow<String> = context.dataStore.data.map { it[flashModeKey] ?: "Off" }

    suspend fun setDoubleTapAction(value: String) = context.dataStore.edit { it[doubleTapActionKey] = value }
    suspend fun setMirrorFrontPreview(enabled: Boolean) = context.dataStore.edit { it[mirrorFrontPreviewKey] = enabled }

    suspend fun setTimerDuration(seconds: Int) = context.dataStore.edit { it[timerDurationKey] = seconds }

    suspend fun setFlashMode(mode: String) = context.dataStore.edit { it[flashModeKey] = mode }

    // ==========================================
    // 2. LENS ZICHTBAARHEID (Bestaande code)
    // ==========================================
    private val visibleLensesKey = stringSetPreferencesKey("visible_lens_ids")

    val visibleLensIdsFlow: Flow<Set<String>?> = context.dataStore.data
        .map { preferences ->
            preferences[visibleLensesKey]
        }

    suspend fun setLensVisibility(lensId: String, isVisible: Boolean) {
        context.dataStore.edit { preferences ->
            val currentSet = preferences[visibleLensesKey] ?: emptySet()
            val mutableSet = currentSet.toMutableSet()
            if (isVisible) mutableSet.add(lensId) else mutableSet.remove(lensId)
            preferences[visibleLensesKey] = mutableSet
        }
    }

    suspend fun saveInitialLenses(lensIds: Set<String>) {
        context.dataStore.edit { preferences ->
            if (!preferences.contains(visibleLensesKey)) {
                preferences[visibleLensesKey] = lensIds
            }
        }
    }

    private val lensAutoAssignmentVersionKey = intPreferencesKey("lens_auto_assignment_version")

    val lensAutoAssignmentVersionFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[lensAutoAssignmentVersionKey] ?: 0
    }

    val slotAssignmentsFlow: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        ALL_SLOTS.mapNotNull { slot ->
            val id = prefs[stringPreferencesKey("slot_${slot}_id")]
            if (id.isNullOrBlank()) null else slot to id
        }.toMap()
    }

    val assignedLensIdsFlow: Flow<Set<String>> = slotAssignmentsFlow.map { assignments ->
        assignments.values.toSet()
    }

    suspend fun applyMissingPrimaryAutoAssignments(assignments: Map<String, String>) {
        context.dataStore.edit { prefs ->
            PRIMARY_SLOTS.forEach { slot ->
                val key = stringPreferencesKey("slot_${slot}_id")
                val currentValue = prefs[key]
                val autoValue = assignments[slot]

                if (currentValue.isNullOrBlank() && !autoValue.isNullOrBlank()) {
                    prefs[key] = autoValue
                }
            }

            prefs[lensAutoAssignmentVersionKey] = LENS_AUTO_ASSIGNMENT_VERSION
        }
    }

    // Speciaal voor de Vendor Tag dropdown: geeft een lijstje van [Lens ID, Slot Naam]
    val activeSlotsFlow: Flow<List<Pair<String, String>>> = context.dataStore.data.map { prefs ->
        ALL_SLOTS.mapNotNull { slot ->
            val id = prefs[stringPreferencesKey("slot_${slot}_id")]
            val customName = prefs[stringPreferencesKey("slot_${slot}_name")] ?: slot
            if (id != null) Pair(id, customName) else null
        }
    }

    // ==========================================
    // 3. PROFIELEN BASIS (Count, Name, Mode)
    // ==========================================

    fun getProfileCountFlow(lensId: String): Flow<Int> {
        return context.dataStore.data.map { preferences ->
            val key = intPreferencesKey("profile_count_$lensId")
            preferences[key] ?: 3
        }
    }

    suspend fun setProfileCount(lensId: String, count: Int) {
        context.dataStore.edit { preferences ->
            val key = intPreferencesKey("profile_count_$lensId")
            preferences[key] = count
        }
    }

    fun getProfileNameFlow(profileId: String, defaultName: String): Flow<String> {
        return context.dataStore.data.map { preferences ->
            val key = stringPreferencesKey("profile_name_$profileId")
            preferences[key] ?: defaultName
        }
    }

    suspend fun setProfileName(profileId: String, name: String) {
        context.dataStore.edit { preferences ->
            val key = stringPreferencesKey("profile_name_$profileId")
            preferences[key] = name
        }
    }

    fun getProfileCaptureModeFlow(profileId: String): Flow<CaptureStrategy> {
        return context.dataStore.data.map { preferences ->
            val key = stringPreferencesKey("profile_mode_$profileId")
            val modeName = preferences[key] ?: CaptureStrategy.SINGLE_FRAME_ZSL.name
            when (runCatching { CaptureStrategy.valueOf(modeName) }.getOrNull()) {
                CaptureStrategy.MULTI_FRAME_ZSL -> CaptureStrategy.MULTI_FRAME_ZSL
                else -> CaptureStrategy.SINGLE_FRAME_ZSL
            }
        }
    }

    suspend fun setProfileCaptureMode(profileId: String, mode: CaptureStrategy) {
        val profileMode = when (mode) {
            CaptureStrategy.MULTI_FRAME_ZSL -> CaptureStrategy.MULTI_FRAME_ZSL
            else -> CaptureStrategy.SINGLE_FRAME_ZSL
        }
        context.dataStore.edit { preferences ->
            val key = stringPreferencesKey("profile_mode_$profileId")
            preferences[key] = profileMode.name
        }
    }
    // --- NIEUW: Hardware Buffer Type (De Pijplijn Selectie) ---
    fun getProfileFrameSourceFlow(profileId: String): Flow<String> {
        return context.dataStore.data.map { preferences ->
            if (DefaultIspProfile.isDisabledProfileId(profileId)) {
                "YUV"
            } else {
                val key = stringPreferencesKey("profile_frame_source_$profileId")
                // Standaard veilig op YUV, maar kan ook RAW10 of RAW_SENSOR zijn
                preferences[key] ?: "YUV"
            }
        }
    }

    suspend fun setProfileFrameSource(profileId: String, source: String) {
        if (DefaultIspProfile.isDisabledProfileId(profileId)) return
        context.dataStore.edit { preferences ->
            val key = stringPreferencesKey("profile_frame_source_$profileId")
            preferences[key] = source
        }
    }

    // ==========================================
    // 4. UNIVERSELE PIPELINE SETTINGS (BnCam pipeline logic)
    // ==========================================

    fun getProfileFloat(profileId: String, settingKey: String, default: Float): Flow<Float> {
        return context.dataStore.data.map { preferences ->
            val key = floatPreferencesKey("${profileId}_${settingKey}")
            preferences[key] ?: default
        }
    }

    suspend fun setProfileFloat(profileId: String, settingKey: String, value: Float) {
        context.dataStore.edit { preferences ->
            val key = floatPreferencesKey("${profileId}_${settingKey}")
            preferences[key] = value
        }
    }

    suspend fun setProfileFloatOverride(profileId: String, settingKey: String, value: Float) {
        context.dataStore.edit { preferences ->
            val key = floatPreferencesKey("${profileId}_${settingKey}")
            preferences[key] = value
            markOverrideLocked(preferences, profileId, settingKey)
        }
    }

    /** Atomically applies a coherent set of profile overrides in one DataStore transaction. */
    suspend fun setProfileOverrideBatch(
        profileId: String,
        floatValues: Map<String, Float> = emptyMap(),
        intValues: Map<String, Int> = emptyMap(),
        booleanValues: Map<String, Boolean> = emptyMap(),
        stringValues: Map<String, String> = emptyMap()
    ) {
        context.dataStore.edit { preferences ->
            floatValues.forEach { (settingKey, value) ->
                preferences[floatPreferencesKey("${profileId}_${settingKey}")] = value
                markOverrideLocked(preferences, profileId, settingKey)
            }
            intValues.forEach { (settingKey, value) ->
                preferences[intPreferencesKey("${profileId}_${settingKey}")] = value
                markOverrideLocked(preferences, profileId, settingKey)
            }
            booleanValues.forEach { (settingKey, value) ->
                preferences[booleanPreferencesKey("${profileId}_${settingKey}")] = value
                markOverrideLocked(preferences, profileId, settingKey)
            }
            stringValues.forEach { (settingKey, value) ->
                preferences[stringPreferencesKey("${profileId}_${settingKey}")] = value
                markOverrideLocked(preferences, profileId, settingKey)
            }
        }
    }

    fun getProfileInt(profileId: String, settingKey: String, default: Int): Flow<Int> {
        return context.dataStore.data.map { preferences ->
            val key = intPreferencesKey("${profileId}_${settingKey}")
            preferences[key] ?: default
        }
    }

    suspend fun setProfileInt(profileId: String, settingKey: String, value: Int) {
        context.dataStore.edit { preferences ->
            val key = intPreferencesKey("${profileId}_${settingKey}")
            preferences[key] = value
        }
    }

    suspend fun setProfileIntOverride(profileId: String, settingKey: String, value: Int) {
        context.dataStore.edit { preferences ->
            val key = intPreferencesKey("${profileId}_${settingKey}")
            preferences[key] = value
            markOverrideLocked(preferences, profileId, settingKey)
        }
    }

    fun getProfileBoolean(profileId: String, settingKey: String, default: Boolean): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            val key = booleanPreferencesKey("${profileId}_${settingKey}")
            preferences[key] ?: default
        }
    }

    fun getRawViewfinderStreamFlow(lensId: String): Flow<String> {
        val key = stringPreferencesKey("raw_viewfinder_stream_$lensId")
        return context.dataStore.data.map { preferences ->
            preferences[key] ?: "AUTO"
        }
    }

    suspend fun setRawViewfinderStream(lensId: String, value: String) {
        val key = stringPreferencesKey("raw_viewfinder_stream_$lensId")
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    private fun rawPreviewFormatCodeKey(lensId: String, source: String) =
        stringPreferencesKey("raw_preview_format_code_${lensId}_${source.trim().uppercase()}")

    fun getRawPreviewFormatCodeFlow(lensId: String, source: String): Flow<String> {
        val key = rawPreviewFormatCodeKey(lensId, source)
        return context.dataStore.data.map { preferences -> preferences[key] ?: "AUTO" }
    }

    suspend fun setRawPreviewFormatCode(lensId: String, source: String, encodedCode: String) {
        val key = rawPreviewFormatCodeKey(lensId, source)
        val normalized = encodedCode.trim().let { raw ->
            if (raw.equals("AUTO", ignoreCase = true)) {
                "AUTO"
            } else {
                val code = parseCameraFormatCode(raw)
                if (code != null && rawPreviewFormatCompatibility(source, code) != RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD) {
                    code.toString()
                } else {
                    "AUTO"
                }
            }
        }
        context.dataStore.edit { preferences -> preferences[key] = normalized }
    }

    suspend fun resetRawPreviewFormatCode(lensId: String, source: String) {
        val key = rawPreviewFormatCodeKey(lensId, source)
        context.dataStore.edit { preferences -> preferences.remove(key) }
    }

    fun getProfileString(profileId: String, settingKey: String, default: String): Flow<String> {
        return context.dataStore.data.map { preferences ->
            val key = stringPreferencesKey("${profileId}_${settingKey}")
            preferences[key] ?: default
        }
    }

    suspend fun setProfileString(profileId: String, settingKey: String, value: String) {
        context.dataStore.edit { preferences ->
            val key = stringPreferencesKey("${profileId}_${settingKey}")
            preferences[key] = value
        }
    }

    suspend fun setProfileStringOverride(profileId: String, settingKey: String, value: String) {
        context.dataStore.edit { preferences ->
            val key = stringPreferencesKey("${profileId}_${settingKey}")
            preferences[key] = value
            markOverrideLocked(preferences, profileId, settingKey)
        }
    }

    suspend fun setProfileBoolean(profileId: String, settingKey: String, value: Boolean) {
        context.dataStore.edit { preferences ->
            val key = booleanPreferencesKey("${profileId}_${settingKey}")
            preferences[key] = value
        }
    }

    suspend fun setProfileBooleanOverride(profileId: String, settingKey: String, value: Boolean) {
        context.dataStore.edit { preferences ->
            val key = booleanPreferencesKey("${profileId}_${settingKey}")
            preferences[key] = value
            markOverrideLocked(preferences, profileId, settingKey)
        }
    }

    fun isProfileOverrideFlow(profileId: String, settingKey: String): Flow<Boolean> {
        return context.dataStore.data.map { preferences -> settingKey in readOverrideSet(preferences, profileId) }
    }

    suspend fun hasProfileOverride(profileId: String, settingKey: String): Boolean {
        if (DefaultIspProfile.isDisabledProfileId(profileId)) return false
        val preferences = context.dataStore.data.first()
        return settingKey in readOverrideSet(preferences, profileId)
    }

    suspend fun getProfileOverrideKeys(profileId: String): Set<String> {
        if (DefaultIspProfile.isDisabledProfileId(profileId)) return emptySet()
        return readOverrideSet(context.dataStore.data.first(), profileId)
    }

    suspend fun clearProfileOverrideValue(profileId: String, settingKey: String, type: ProfileSettingValueType) {
        context.dataStore.edit { preferences ->
            when (type) {
                ProfileSettingValueType.FLOAT -> preferences.remove(floatPreferencesKey("${profileId}_${settingKey}"))
                ProfileSettingValueType.INT -> preferences.remove(intPreferencesKey("${profileId}_${settingKey}"))
                ProfileSettingValueType.BOOLEAN -> preferences.remove(booleanPreferencesKey("${profileId}_${settingKey}"))
                ProfileSettingValueType.STRING -> preferences.remove(stringPreferencesKey("${profileId}_${settingKey}"))
            }
            clearOverrideLocked(preferences, profileId, settingKey)
        }
    }

    /** Atomically clears a group of values and their override flags. */
    suspend fun clearProfileOverrideValues(profileId: String, specs: List<ProfileSettingSpec>) {
        context.dataStore.edit { preferences ->
            specs.distinctBy { "${it.type}:${it.key}" }.forEach { spec ->
                when (spec.type) {
                    ProfileSettingValueType.FLOAT -> preferences.remove(floatPreferencesKey("${profileId}_${spec.key}"))
                    ProfileSettingValueType.INT -> preferences.remove(intPreferencesKey("${profileId}_${spec.key}"))
                    ProfileSettingValueType.BOOLEAN -> preferences.remove(booleanPreferencesKey("${profileId}_${spec.key}"))
                    ProfileSettingValueType.STRING -> preferences.remove(stringPreferencesKey("${profileId}_${spec.key}"))
                }
                clearOverrideLocked(preferences, profileId, spec.key)
            }
        }
    }

    private fun overrideSetKey(profileId: String) = stringSetPreferencesKey("${profileId}_libpatcher_override_keys")

    private fun readOverrideSet(preferences: Preferences, profileId: String): Set<String> {
        return preferences[overrideSetKey(profileId)] ?: emptySet()
    }

    private fun markOverrideLocked(preferences: MutablePreferences, profileId: String, settingKey: String) {
        if (DefaultIspProfile.isDisabledProfileId(profileId)) return
        val key = overrideSetKey(profileId)
        val updated = (preferences[key] ?: emptySet()).toMutableSet()
        updated += settingKey
        preferences[key] = updated
    }

    private fun clearOverrideLocked(preferences: MutablePreferences, profileId: String, settingKey: String) {
        val key = overrideSetKey(profileId)
        val updated = (preferences[key] ?: emptySet()).toMutableSet()
        updated.remove(settingKey)
        if (updated.isEmpty()) preferences.remove(key) else preferences[key] = updated
    }

    suspend fun readProfileSettingsSnapshot(
        profileId: String,
        specs: List<ProfileSettingSpec>,
        onlyKeys: Set<String>? = null,
        includePortableDefaults: Boolean = false
    ): ProfileSettingsSnapshot {
        val preferences = context.dataStore.data.first()
        val floats = linkedMapOf<String, Float>()
        val ints = linkedMapOf<String, Int>()
        val booleans = linkedMapOf<String, Boolean>()
        val strings = linkedMapOf<String, String>()

        specs.distinctBy { it.type.name + ":" + it.key }
            .filter { onlyKeys == null || it.key in onlyKeys }
            .forEach { spec ->
                when (spec.type) {
                    ProfileSettingValueType.FLOAT -> {
                        val key = floatPreferencesKey("${profileId}_${spec.key}")
                        val stored = preferences[key]
                        when {
                            stored != null -> floats[spec.key] = stored
                            includePortableDefaults -> spec.portableDefault?.toFloatOrNull()?.let { floats[spec.key] = it }
                        }
                    }
                    ProfileSettingValueType.INT -> {
                        val key = intPreferencesKey("${profileId}_${spec.key}")
                        val stored = preferences[key]
                        when {
                            stored != null -> ints[spec.key] = stored
                            includePortableDefaults -> spec.portableDefault?.toIntOrNull()?.let { ints[spec.key] = it }
                        }
                    }
                    ProfileSettingValueType.BOOLEAN -> {
                        val key = booleanPreferencesKey("${profileId}_${spec.key}")
                        val stored = preferences[key]
                        when {
                            stored != null -> booleans[spec.key] = stored
                            includePortableDefaults -> spec.portableDefault?.toBooleanStrictOrNull()?.let { booleans[spec.key] = it }
                        }
                    }
                    ProfileSettingValueType.STRING -> {
                        val key = stringPreferencesKey("${profileId}_${spec.key}")
                        val stored = preferences[key]
                        when {
                            stored != null -> strings[spec.key] = stored
                            includePortableDefaults -> spec.portableDefault?.let { strings[spec.key] = it }
                        }
                    }
                }
            }

        return ProfileSettingsSnapshot(
            floatValues = floats,
            intValues = ints,
            booleanValues = booleans,
            stringValues = strings
        )
    }

    suspend fun writeProfileSettingsSnapshot(
        profileId: String,
        snapshot: ProfileSettingsSnapshot
    ) {
        context.dataStore.edit { preferences ->
            snapshot.floatValues.forEach { (settingKey, value) ->
                preferences[floatPreferencesKey("${profileId}_${settingKey}")] = value
                markOverrideLocked(preferences, profileId, settingKey)
            }
            snapshot.intValues.forEach { (settingKey, value) ->
                preferences[intPreferencesKey("${profileId}_${settingKey}")] = value
                markOverrideLocked(preferences, profileId, settingKey)
            }
            snapshot.booleanValues.forEach { (settingKey, value) ->
                preferences[booleanPreferencesKey("${profileId}_${settingKey}")] = value
                markOverrideLocked(preferences, profileId, settingKey)
            }
            snapshot.stringValues.forEach { (settingKey, value) ->
                preferences[stringPreferencesKey("${profileId}_${settingKey}")] = value
                markOverrideLocked(preferences, profileId, settingKey)
            }
        }
    }

    suspend fun clearProfileSettings(
        profileId: String,
        specs: List<ProfileSettingSpec>
    ) {
        context.dataStore.edit { preferences ->
            specs.distinctBy { it.type.name + ":" + it.key }.forEach { spec ->
                when (spec.type) {
                    ProfileSettingValueType.FLOAT -> preferences.remove(floatPreferencesKey("${profileId}_${spec.key}"))
                    ProfileSettingValueType.INT -> preferences.remove(intPreferencesKey("${profileId}_${spec.key}"))
                    ProfileSettingValueType.BOOLEAN -> preferences.remove(booleanPreferencesKey("${profileId}_${spec.key}"))
                    ProfileSettingValueType.STRING -> preferences.remove(stringPreferencesKey("${profileId}_${spec.key}"))
                }
                clearOverrideLocked(preferences, profileId, spec.key)
            }
        }
    }

    private fun portableProfileUuidKey(profileId: String) =
        stringPreferencesKey("profile_portable_uuid_$profileId")

    suspend fun getOrCreatePortableProfileUuid(profileId: String): String {
        val existing = context.dataStore.data.first()[portableProfileUuidKey(profileId)]
        if (!existing.isNullOrBlank()) {
            val valid = runCatching { java.util.UUID.fromString(existing) }.isSuccess
            if (valid) return existing
        }
        val generated = java.util.UUID.randomUUID().toString()
        context.dataStore.edit { preferences ->
            val current = preferences[portableProfileUuidKey(profileId)]
            if (current.isNullOrBlank() || runCatching { java.util.UUID.fromString(current) }.isFailure) {
                preferences[portableProfileUuidKey(profileId)] = generated
            }
        }
        return context.dataStore.data.first()[portableProfileUuidKey(profileId)] ?: generated
    }

    suspend fun exportProfileBnc(
        profileId: String,
        sourceStableLensKey: String,
        defaultProfileName: String,
        bncamVersion: String,
        specs: List<ProfileSettingSpec>,
        sourceFrameSources: Set<String> = emptySet()
    ): BncProfileExportResult {
        val safeSpecs = specs.distinctBy { it.type.name + ":" + it.key }
        val missingPortableDefaults = safeSpecs.filter { it.portableDefault == null }.map { it.key }
        require(missingPortableDefaults.isEmpty()) {
            "Complete .bnc export has no portable default for: ${missingPortableDefaults.joinToString()}"
        }

        val uuid = getOrCreatePortableProfileUuid(profileId)
        val storedName = readProfileName(profileId).trim()
        val effectiveName = storedName.ifBlank { defaultProfileName.trim().ifBlank { "Profile" } }
        val snapshot = readPortableEffectiveProfileSnapshot(
            profileId = profileId,
            specs = safeSpecs
        )
        require(snapshot.totalCount == safeSpecs.size) {
            "Incomplete .bnc profile snapshot: ${snapshot.totalCount}/${safeSpecs.size} settings materialized."
        }

        val document = BncProfileDocument(
            profileUuid = uuid,
            profileName = effectiveName,
            captureMode = readProfileCaptureModeName(profileId),
            preferredFrameSource = readProfileFrameSource(profileId),
            settings = snapshot,
            metadata = BncProfileMetadata(
                bncamVersion = bncamVersion,
                exportedAtEpochMs = System.currentTimeMillis(),
                sourceStableLensKey = sourceStableLensKey,
                defaultIspVersion = DefaultIspProfile.VERSION,
                sourceFrameSources = sourceFrameSources,
                hardwareCalibrationIncluded = false
            )
        )
        val contents = BncProfileCodec.encode(document)
        return BncProfileExportResult(
            contents = contents,
            suggestedFileName = BncProfileCodec.suggestedFileName(effectiveName),
            profileUuid = uuid,
            profileName = effectiveName,
            exportedSettings = snapshot.totalCount
        )
    }

    /**
     * Materializes the self-contained portable profile contract. Legacy native tone-shape keys
     * are no longer in `specs`; they stay in DataStore only for backward storage compatibility
     * and are not silently injected into new .bnc exports.
     */
    private suspend fun readPortableEffectiveProfileSnapshot(
        profileId: String,
        specs: List<ProfileSettingSpec>
    ): ProfileSettingsSnapshot = readProfileSettingsSnapshot(
        profileId = profileId,
        specs = specs,
        includePortableDefaults = true
    )

    suspend fun importProfileBnc(
        raw: String,
        targetProfileId: String,
        specs: List<ProfileSettingSpec>,
        targetCapabilities: BncTargetCapabilities = BncTargetCapabilities.UNKNOWN
    ): BncProfileImportResult {
        val safeSpecs = specs.distinctBy { it.type.name + ":" + it.key }
        val decoded = BncProfileCodec.decode(raw, safeSpecs)
        val sanitizedSnapshot = sanitizeImportedPortableSnapshot(decoded.document.settings)
        val frameResolution = BncProfileCompatibility.resolveFrameSource(
            requested = decoded.document.preferredFrameSource,
            target = targetCapabilities
        )
        val requestedMode = decoded.document.captureMode
        val safeMode = when (requestedMode) {
            CaptureStrategy.MULTI_FRAME_ZSL.name -> CaptureStrategy.MULTI_FRAME_ZSL
            CaptureStrategy.SINGLE_FRAME_ZSL.name -> CaptureStrategy.SINGLE_FRAME_ZSL
            else -> CaptureStrategy.SINGLE_FRAME_ZSL
        }
        val extraWarnings = mutableListOf<String>()
        if (safeMode.name != requestedMode) {
            extraWarnings += "Unsupported profile capture mode '$requestedMode'; fell back to ${safeMode.name}."
        }
        if (frameResolution.fallbackOccurred) {
            extraWarnings += "Frame source ${frameResolution.requested} is unavailable on the target lens; using ${frameResolution.effective}."
        }

        // Decode, migrate and validate completely before entering this single DataStore transaction.
        // This prevents corrupt/unsupported files from leaving a partially imported profile behind.
        context.dataStore.edit { preferences ->
            safeSpecs.forEach { spec ->
                when (spec.type) {
                    ProfileSettingValueType.FLOAT -> preferences.remove(floatPreferencesKey("${targetProfileId}_${spec.key}"))
                    ProfileSettingValueType.INT -> preferences.remove(intPreferencesKey("${targetProfileId}_${spec.key}"))
                    ProfileSettingValueType.BOOLEAN -> preferences.remove(booleanPreferencesKey("${targetProfileId}_${spec.key}"))
                    ProfileSettingValueType.STRING -> preferences.remove(stringPreferencesKey("${targetProfileId}_${spec.key}"))
                }
            }
            preferences.remove(overrideSetKey(targetProfileId))

            preferences[stringPreferencesKey("profile_name_$targetProfileId")] = decoded.document.profileName
            preferences[stringPreferencesKey("profile_mode_$targetProfileId")] = safeMode.name
            preferences[stringPreferencesKey("profile_frame_source_$targetProfileId")] = frameResolution.effective
            preferences[portableProfileUuidKey(targetProfileId)] = decoded.document.profileUuid

            sanitizedSnapshot.floatValues.forEach { (key, value) ->
                preferences[floatPreferencesKey("${targetProfileId}_${key}")] = value
                markOverrideLocked(preferences, targetProfileId, key)
            }
            sanitizedSnapshot.intValues.forEach { (key, value) ->
                preferences[intPreferencesKey("${targetProfileId}_${key}")] = value
                markOverrideLocked(preferences, targetProfileId, key)
            }
            sanitizedSnapshot.booleanValues.forEach { (key, value) ->
                preferences[booleanPreferencesKey("${targetProfileId}_${key}")] = value
                markOverrideLocked(preferences, targetProfileId, key)
            }
            sanitizedSnapshot.stringValues.forEach { (key, value) ->
                preferences[stringPreferencesKey("${targetProfileId}_${key}")] = value
                markOverrideLocked(preferences, targetProfileId, key)
            }
        }

        return BncProfileImportResult(
            profileUuid = decoded.document.profileUuid,
            profileName = decoded.document.profileName,
            importedSettings = sanitizedSnapshot.totalCount,
            sourceSchemaVersion = decoded.sourceSchemaVersion,
            migrated = decoded.migrated,
            frameSourceResolution = frameResolution,
            ignoredUnknownSettingKeys = decoded.ignoredUnknownSettingKeys,
            fallbackSettingKeys = decoded.fallbackSettingKeys,
            warnings = decoded.warnings + extraWarnings
        )
    }

    private fun sanitizeImportedPortableSnapshot(snapshot: ProfileSettingsSnapshot): ProfileSettingsSnapshot {
        val safeFloats = snapshot.floatValues.mapValues { (key, value) ->
            val finite = value.takeIf { it.isFinite() } ?: 0f
            when {
                key.startsWith("curve_") && key.contains("_point_") -> finite.coerceIn(0f, 1f)
                key == ProfileIspKeys.PRESENCE_POP -> finite.coerceIn(0f, 1f)
                key == ProfileIspKeys.SPECTRA_DYNAMIC_ISO -> finite.coerceIn(0f, 1f)
                key == ProfileIspKeys.DETAIL_NR_LUMINANCE ||
                    key == ProfileIspKeys.DETAIL_NR_LUMINANCE_DETAIL ||
                    key == ProfileIspKeys.DETAIL_NR_LUMINANCE_CONTRAST ||
                    key == ProfileIspKeys.DETAIL_NR_COLOR ||
                    key == ProfileIspKeys.DETAIL_NR_COLOR_DETAIL ||
                    key == ProfileIspKeys.DETAIL_NR_COLOR_SMOOTHNESS ||
                    key == ProfileIspKeys.PRESENCE_COLOR_FRINGE_SUPPRESSION ||
                    key == ProfileIspKeys.DETAIL_SHARPENING_EDGE ||
                    key == ProfileIspKeys.POLYSHARP_MAX_DETAIL -> finite.coerceIn(0f, 1f)
                key == ProfileIspKeys.POLYSHARP_GAIN ||
                    key == ProfileIspKeys.POLYSHARP_MACRO_GAIN ||
                    key == ProfileIspKeys.POLYSHARP_MICRO_GAIN -> finite.coerceIn(0f, 2f)
                key == ProfileIspKeys.POLYSHARP_RADIUS_SMALL -> finite.coerceIn(0f, 2f)
                key == ProfileIspKeys.POLYSHARP_RADIUS_MEDIUM -> finite.coerceIn(0f, 4f)
                key == ProfileIspKeys.POLYSHARP_RADIUS_LARGE -> finite.coerceIn(0f, 8f)
                key == CaptureSettingKeys.MERGE_STRICTNESS -> finite.coerceIn(0f, 1f)
                key == CaptureSettingKeys.SHUTTER_PRIORITY_MULTIPLIER ||
                    key == CaptureSettingKeys.ISO_PRIORITY_MULTIPLIER -> finite.coerceIn(0.25f, 4.0f)
                key == CaptureSettingKeys.CAPTURE_EV_BIAS -> finite.coerceIn(-2.0f, 2.0f)
                else -> finite.coerceIn(-1f, 1f)
            }
        }
        val safeInts = snapshot.intValues.mapValues { (key, value) ->
            when (key) {
                CaptureSettingKeys.JPEG_QUALITY, "post_jpeg_quality" -> value.coerceIn(80, 100)
                "awb_kelvin" -> value.coerceIn(2000, 10000)
                ProfileIspKeys.SPECTRA_ENABLED -> value.coerceIn(0, 1)
                CaptureSettingKeys.FUSION_FRAMES_YUV -> value.coerceIn(2, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.YUV))
                CaptureSettingKeys.FUSION_FRAMES_RAW10 -> value.coerceIn(2, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW10))
                CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR -> value.coerceIn(2, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW_SENSOR))
                CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10 -> value.coerceIn(1, FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW10))
                CaptureSettingKeys.DNG_MASTER_FRAMES_RAW_SENSOR -> value.coerceIn(1, FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW_SENSOR))
                CaptureSettingKeys.BASE_CANDIDATES, CaptureSettingKeys.SELECTION_REQUESTED_FRAMES -> value.coerceIn(1, FrameCapacityPolicy.YUV_WARM_BUFFER_TARGET)
                CaptureSettingKeys.MERGE_MAX_SHIFT -> value.coerceIn(1, 4096)
                else -> value
            }
        }
        val safeStrings = snapshot.stringValues.mapValues { (_, value) -> value.take(256) }
        return ProfileSettingsSnapshot(
            floatValues = safeFloats,
            intValues = safeInts,
            booleanValues = snapshot.booleanValues,
            stringValues = safeStrings
        )
    }

    private suspend fun readProfileName(profileId: String): String {
        val preferences = context.dataStore.data.first()
        return preferences[stringPreferencesKey("profile_name_$profileId")] ?: ""
    }

    private suspend fun readProfileCaptureModeName(profileId: String): String {
        val preferences = context.dataStore.data.first()
        return when (preferences[stringPreferencesKey("profile_mode_$profileId")]) {
            CaptureStrategy.MULTI_FRAME_ZSL.name -> CaptureStrategy.MULTI_FRAME_ZSL.name
            else -> CaptureStrategy.SINGLE_FRAME_ZSL.name
        }
    }

    private suspend fun readProfileFrameSource(profileId: String): String {
        val preferences = context.dataStore.data.first()
        return preferences[stringPreferencesKey("profile_frame_source_$profileId")] ?: "YUV"
    }

    // ==========================================
    // 5. LENS SLOTS ASSIGNMENTS & NAMING
    // ==========================================

    fun getSlotLensIdFlow(slotName: String): Flow<String?> {
        return context.dataStore.data.map { it[stringPreferencesKey("slot_${slotName}_id")] }
    }

    suspend fun setSlotLensId(slotName: String, lensId: String?) {
        context.dataStore.edit { preferences ->
            val key = stringPreferencesKey("slot_${slotName}_id")
            if (lensId == null) {
                preferences.remove(key)
            } else {
                preferences[key] = lensId
            }
        }
    }

    fun getSlotCustomNameFlow(slotName: String): Flow<String> {
        return context.dataStore.data.map { it[stringPreferencesKey("slot_${slotName}_name")] ?: slotName }
    }

    suspend fun setSlotCustomName(slotName: String, customName: String) {
        context.dataStore.edit { preferences ->
            val key = stringPreferencesKey("slot_${slotName}_name")
            preferences[key] = customName
        }
    }

    // ==========================================
// 6. VENDOR TAG ENGINE (Dynamische Recepten)
// ==========================================
    private val vendorTagsKey = stringSetPreferencesKey("manual_vendor_tags")

    val vendorTagsFlow: Flow<List<VendorTagConfig>> = context.dataStore.data.map { prefs ->
        prefs[vendorTagsKey]?.mapNotNull { VendorTagConfig.deserialize(it) } ?: emptyList()
    }

    suspend fun addVendorTag(tag: VendorTagConfig) {
        context.dataStore.edit { prefs ->
            val set = prefs[vendorTagsKey]?.toMutableSet() ?: mutableSetOf()

            set.removeAll {
                val existing = VendorTagConfig.deserialize(it)
                existing?.identityKey() == tag.identityKey()
            }

            set.add(tag.serialize())
            prefs[vendorTagsKey] = set
        }
    }

    suspend fun removeVendorTag(
        lensId: String,
        keyName: String,
        target: VendorTagTarget? = null,
        recipeId: String? = null
    ) {
        context.dataStore.edit { prefs ->
            val set = prefs[vendorTagsKey]?.toMutableSet() ?: mutableSetOf()

            set.removeAll {
                val existing = VendorTagConfig.deserialize(it) ?: return@removeAll false

                val lensMatches = existing.lensId == lensId
                val keyMatches = existing.keyName == keyName
                val targetMatches = target == null || existing.target == target
                val recipeMatches = recipeId == null || existing.recipeId == recipeId

                lensMatches && keyMatches && targetMatches && recipeMatches
            }

            prefs[vendorTagsKey] = set
        }
    }

    // Legacy helper. Keep this temporarily until VendorTagsScreen and old callers are migrated.
    suspend fun removeVendorTag(keyName: String) {
        context.dataStore.edit { prefs ->
            val set = prefs[vendorTagsKey]?.toMutableSet() ?: mutableSetOf()
            set.removeAll { VendorTagConfig.deserialize(it)?.keyName == keyName }
            prefs[vendorTagsKey] = set
        }
    }

    suspend fun setVendorTagEnabled(
        lensId: String,
        keyName: String,
        target: VendorTagTarget,
        enabled: Boolean,
        recipeId: String = ""
    ) {
        context.dataStore.edit { prefs ->
            val set = prefs[vendorTagsKey]?.toMutableSet() ?: mutableSetOf()
            var updated = false

            val newSet = set.mapNotNull { raw ->
                val existing = VendorTagConfig.deserialize(raw) ?: return@mapNotNull null

                val matches =
                    existing.lensId == lensId &&
                            existing.keyName == keyName &&
                            existing.target == target &&
                            existing.recipeId == recipeId

                if (matches) {
                    updated = true
                    existing.copy(enabled = enabled).serialize()
                } else {
                    raw
                }
            }.toMutableSet()

            if (updated) {
                prefs[vendorTagsKey] = newSet
            }
        }
    }

    suspend fun getVendorTagsForLens(lensId: String): List<VendorTagConfig> {
        val prefs = context.dataStore.data.first()
        val allTags = prefs[vendorTagsKey]?.mapNotNull { VendorTagConfig.deserialize(it) } ?: emptyList()
        return allTags.filter { it.lensId == lensId }
    }

    suspend fun getActiveVendorTagsForLens(lensId: String): List<VendorTagConfig> {
        return getVendorTagsForLens(lensId).filter { it.enabled }
    }

    suspend fun getActiveVendorTagsForLensAndTarget(
        lensId: String,
        target: VendorTagTarget
    ): List<VendorTagConfig> {
        return getActiveVendorTagsForLens(lensId).filter { tag ->
            tag.target == target ||
                    tag.target == VendorTagTarget.REQUEST_BOTH &&
                    (target == VendorTagTarget.REPEATING_REQUEST || target == VendorTagTarget.STILL_CAPTURE)
        }
    }

    suspend fun replaceVendorRecipeTags(
        lensId: String,
        recipeId: String,
        tags: List<VendorTagConfig>
    ) {
        context.dataStore.edit { prefs ->
            val set = prefs[vendorTagsKey]?.toMutableSet() ?: mutableSetOf()

            set.removeAll {
                val existing = VendorTagConfig.deserialize(it)
                existing?.lensId == lensId && existing.recipeId == recipeId
            }

            tags.forEach { tag ->
                set.add(
                    tag.copy(
                        lensId = lensId,
                        recipeId = recipeId,
                        source = VendorTagSource.RECIPE,
                        enabled = true
                    ).serialize()
                )
            }

            prefs[vendorTagsKey] = set
        }
    }

    suspend fun setVendorRecipeEnabled(
        lensId: String,
        recipeId: String,
        enabled: Boolean
    ) {
        context.dataStore.edit { prefs ->
            val set = prefs[vendorTagsKey]?.toMutableSet() ?: mutableSetOf()

            val newSet = set.mapNotNull { raw ->
                val existing = VendorTagConfig.deserialize(raw) ?: return@mapNotNull null

                if (existing.lensId == lensId && existing.recipeId == recipeId) {
                    existing.copy(enabled = enabled).serialize()
                } else {
                    raw
                }
            }.toMutableSet()

            prefs[vendorTagsKey] = newSet
        }
    }

    suspend fun lensHasEnabledSessionVendorTags(lensId: String): Boolean {
        return getActiveVendorTagsForLens(lensId).any {
            it.target == VendorTagTarget.SESSION || it.requiresSessionRebuild
        }
    }



    // ==========================================
    // 6B. VENDOR OPERATION MODE LEARNING / PROBING
    // ==========================================
    private fun safeVendorProbeKeyPart(raw: String): String {
        return raw.ifBlank { "empty" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(96)
    }

    private fun vendorOpProbeIndexKey(lensId: String, featureSignature: String) =
        intPreferencesKey("vendor_op_probe_index_${safeVendorProbeKeyPart(lensId)}_${safeVendorProbeKeyPart(featureSignature)}")

    private fun vendorOpLearnedSessionTypeKey(lensId: String, featureSignature: String) =
        intPreferencesKey("vendor_op_learned_session_type_${safeVendorProbeKeyPart(lensId)}_${safeVendorProbeKeyPart(featureSignature)}")

    private fun vendorOpLearnedEvidenceKey(lensId: String, featureSignature: String) =
        stringPreferencesKey("vendor_op_learned_evidence_${safeVendorProbeKeyPart(lensId)}_${safeVendorProbeKeyPart(featureSignature)}")

    private fun vendorOpProbeActiveKey(lensId: String) =
        booleanPreferencesKey("vendor_op_probe_active_${safeVendorProbeKeyPart(lensId)}")

    suspend fun setVendorOperationModeProbeActive(lensId: String, active: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[vendorOpProbeActiveKey(lensId)] = active
        }
    }

    suspend fun isVendorOperationModeProbeActive(lensId: String): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[vendorOpProbeActiveKey(lensId)] ?: false
    }

    suspend fun getVendorOperationModeProbeIndex(lensId: String, featureSignature: String): Int {
        val prefs = context.dataStore.data.first()
        return prefs[vendorOpProbeIndexKey(lensId, featureSignature)] ?: 0
    }

    suspend fun setVendorOperationModeProbeIndex(lensId: String, featureSignature: String, index: Int) {
        context.dataStore.edit { prefs ->
            prefs[vendorOpProbeIndexKey(lensId, featureSignature)] = index.coerceAtLeast(0)
        }
    }

    suspend fun getLearnedVendorSessionType(lensId: String, featureSignature: String): Int? {
        val prefs = context.dataStore.data.first()
        return prefs[vendorOpLearnedSessionTypeKey(lensId, featureSignature)]
    }

    suspend fun saveLearnedVendorSessionType(
        lensId: String,
        featureSignature: String,
        sessionType: Int,
        evidence: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[vendorOpLearnedSessionTypeKey(lensId, featureSignature)] = sessionType
            prefs[vendorOpLearnedEvidenceKey(lensId, featureSignature)] = evidence.take(500)
        }
    }


    // ==========================================
    // 7. WATERMARK & EXIF SETTINGS
    // ==========================================
    private val watermarkEnabledKey = booleanPreferencesKey("watermark_enabled")
    private val watermarkStyleKey = stringPreferencesKey("watermark_style")
    private val watermarkSignatureKey = stringPreferencesKey("watermark_signature")
    private val watermarkAddAuthorTopRightKey = booleanPreferencesKey("watermark_add_author_top_right")

    private val exifSaveSignatureKey = booleanPreferencesKey("exif_save_signature")
    private val exifExtraDataKey = booleanPreferencesKey("exif_extra_data")

    // Watermark Flows
    val watermarkEnabledFlow: Flow<Boolean> = context.dataStore.data.map { it[watermarkEnabledKey] ?: false }
    val watermarkStyleFlow: Flow<String> = context.dataStore.data.map { it[watermarkStyleKey] ?: "Off" }
    val watermarkSignatureFlow: Flow<String> = context.dataStore.data.map { it[watermarkSignatureKey] ?: "" }
    val watermarkAddAuthorTopRightFlow: Flow<Boolean> = context.dataStore.data.map { it[watermarkAddAuthorTopRightKey] ?: false }

    // EXIF Flows
    val exifSaveSignatureFlow: Flow<Boolean> = context.dataStore.data.map { it[exifSaveSignatureKey] ?: false }
    val exifExtraDataFlow: Flow<Boolean> = context.dataStore.data.map { it[exifExtraDataKey] ?: false }

    // Setters
    suspend fun setWatermarkEnabled(enabled: Boolean) = context.dataStore.edit { it[watermarkEnabledKey] = enabled }
    suspend fun setWatermarkStyle(style: String) = context.dataStore.edit { it[watermarkStyleKey] = style }
    suspend fun setWatermarkSignature(signature: String) = context.dataStore.edit { it[watermarkSignatureKey] = signature }
    suspend fun setWatermarkAddAuthorTopRight(enabled: Boolean) = context.dataStore.edit { it[watermarkAddAuthorTopRightKey] = enabled }

    suspend fun setExifSaveSignature(enabled: Boolean) = context.dataStore.edit { it[exifSaveSignatureKey] = enabled }
    suspend fun setExifExtraData(enabled: Boolean) = context.dataStore.edit { it[exifExtraDataKey] = enabled }

    // ==========================================
    // 8. GENIUS FEATURES (De Recepten)
    // ==========================================
    private val activeFeaturesKey = stringSetPreferencesKey("active_genius_features")

    val activeFeaturesFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[activeFeaturesKey] ?: emptySet()
    }

    suspend fun getActiveFeaturesSync(): Set<String> {
        val prefs = context.dataStore.data.first()
        return prefs[activeFeaturesKey] ?: emptySet()
    }

    suspend fun toggleFeature(featureId: String, isActive: Boolean) {
        context.dataStore.edit { prefs ->
            val set = prefs[activeFeaturesKey]?.toMutableSet() ?: mutableSetOf()
            if (isActive) set.add(featureId) else set.remove(featureId)
            prefs[activeFeaturesKey] = set
        }
    }

    // ==========================================
    // 9. LENS HARDWARE OVERRIDES (Black Level, Noise Model, Color Matrix, AWB)
    // ==========================================

    private fun lensStringKey(lensId: String, suffix: String) =
        stringPreferencesKey("hardware_lens_${safeLensKeyPart(lensId)}_$suffix")

    private fun lensFloatKey(lensId: String, suffix: String) =
        floatPreferencesKey("hardware_lens_${safeLensKeyPart(lensId)}_$suffix")

    // Backwards-compatible alias for older code paths that already call lensKey().
    private fun lensKey(lensId: String, suffix: String) = lensStringKey(lensId, suffix)

    // --- Noise Model ---
    fun getNoiseModelTypeFlow(lensId: String) = context.dataStore.data.map {
        it[lensStringKey(lensId, "noise_type")] ?: LensHardwareTuningModes.OFF
    }
    fun getNoiseModelAFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "noise_a")] ?: "" }
    fun getNoiseModelBFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "noise_b")] ?: "" }
    fun getNoiseModelCFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "noise_c")] ?: "" }
    fun getNoiseModelDFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "noise_d")] ?: "" }
    fun getIsoStepFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "iso_step")] ?: "" }
    fun getIsoNrStyleFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "iso_nr_style")] ?: "Default" }
    fun getDynamicIsoCoeffFlow(lensId: String) = context.dataStore.data.map {
        sanitizeDynamicIsoCoefficient(it[lensFloatKey(lensId, "dynamic_iso")] ?: 0.0f)
    }
    fun getManualIsoValueFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "manual_iso")] ?: "" }

    suspend fun setNoiseModelType(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "noise_type")] = value }
    suspend fun setNoiseModelA(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "noise_a")] = value }
    suspend fun setNoiseModelB(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "noise_b")] = value }
    suspend fun setNoiseModelC(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "noise_c")] = value }
    suspend fun setNoiseModelD(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "noise_d")] = value }
    suspend fun setIsoStep(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "iso_step")] = value }
    suspend fun setIsoNrStyle(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "iso_nr_style")] = value }
    suspend fun setDynamicIsoCoeff(lensId: String, value: Float) = context.dataStore.edit {
        it[lensFloatKey(lensId, "dynamic_iso")] = sanitizeDynamicIsoCoefficient(value)
    }
    suspend fun setDynamicIsoCoeff(lensKey: StableLensKey, value: Float) = setDynamicIsoCoeff(lensKey.value, value)
    suspend fun setManualIsoValue(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "manual_iso")] = value }

    fun getNoiseModelCalibrationAdjustmentFlow(lensId: String): Flow<Float> = context.dataStore.data.map {
        (it[lensFloatKey(lensId, "noise_calibration_adj")] ?: 0.0f).coerceIn(-1.0f, 1.0f)
    }
    fun getNoiseModelCalibrationAdjustmentFlow(lensKey: StableLensKey): Flow<Float> = getNoiseModelCalibrationAdjustmentFlow(lensKey.value)

    fun getDynamicChromaAuthorityAdjustmentFlow(lensId: String): Flow<Float> = context.dataStore.data.map {
        (it[lensFloatKey(lensId, "chroma_authority_adj")] ?: 0.0f).coerceIn(-1.0f, 1.0f)
    }
    fun getDynamicChromaAuthorityAdjustmentFlow(lensKey: StableLensKey): Flow<Float> = getDynamicChromaAuthorityAdjustmentFlow(lensKey.value)

    fun getDynamicLumaAuthorityAdjustmentFlow(lensId: String): Flow<Float> = context.dataStore.data.map {
        (it[lensFloatKey(lensId, "luma_authority_adj")] ?: 0.0f).coerceIn(-1.0f, 1.0f)
    }
    fun getDynamicLumaAuthorityAdjustmentFlow(lensKey: StableLensKey): Flow<Float> = getDynamicLumaAuthorityAdjustmentFlow(lensKey.value)

    suspend fun setNoiseModelCalibrationAdjustment(lensId: String, value: Float) = context.dataStore.edit {
        it[lensFloatKey(lensId, "noise_calibration_adj")] = value.coerceIn(-1.0f, 1.0f)
    }
    suspend fun setNoiseModelCalibrationAdjustment(lensKey: StableLensKey, value: Float) = setNoiseModelCalibrationAdjustment(lensKey.value, value)

    suspend fun setDynamicChromaAuthorityAdjustment(lensId: String, value: Float) = context.dataStore.edit {
        it[lensFloatKey(lensId, "chroma_authority_adj")] = value.coerceIn(-1.0f, 1.0f)
    }
    suspend fun setDynamicChromaAuthorityAdjustment(lensKey: StableLensKey, value: Float) = setDynamicChromaAuthorityAdjustment(lensKey.value, value)

    suspend fun setDynamicLumaAuthorityAdjustment(lensId: String, value: Float) = context.dataStore.edit {
        it[lensFloatKey(lensId, "luma_authority_adj")] = value.coerceIn(-1.0f, 1.0f)
    }
    suspend fun setDynamicLumaAuthorityAdjustment(lensKey: StableLensKey, value: Float) = setDynamicLumaAuthorityAdjustment(lensKey.value, value)

    suspend fun resetAdvancedSensorNoiseCalibration(lensId: String) = context.dataStore.edit {
        it[lensFloatKey(lensId, "noise_calibration_adj")] = 0.0f
        it[lensFloatKey(lensId, "chroma_authority_adj")] = 0.0f
        it[lensFloatKey(lensId, "luma_authority_adj")] = 0.0f
    }
    suspend fun resetAdvancedSensorNoiseCalibration(lensKey: StableLensKey) = resetAdvancedSensorNoiseCalibration(lensKey.value)

    fun getLensNoiseModelSettingsFlow(lensKey: StableLensKey): Flow<LensNoiseModelSettings> = getLensNoiseModelSettingsFlow(lensKey.value)
    suspend fun setLensNoiseModelSettings(lensKey: StableLensKey, settings: LensNoiseModelSettings) = setLensNoiseModelSettings(lensKey.value, settings)

    fun getLensNoiseModelSettingsFlow(lensId: String): Flow<LensNoiseModelSettings> {
        return context.dataStore.data.map { preferences ->
            LensNoiseModelSettings(
                mode = when {
                    preferences[lensStringKey(lensId, "noise_type")]
                        .equals(LensHardwareTuningModes.AUTO, ignoreCase = true) -> LensHardwareTuningModes.AUTO
                    preferences[lensStringKey(lensId, "noise_type")]
                        .equals(LensHardwareTuningModes.MANUAL, ignoreCase = true) -> LensHardwareTuningModes.MANUAL
                    else -> LensHardwareTuningModes.OFF
                },
                values = parseStoredDoubles(preferences[lensStringKey(lensId, "noise_so")] ?: "", 8)
            ).sanitized()
        }
    }

    /** Mode and every S/O coefficient are committed atomically. */
    suspend fun setLensNoiseModelSettings(lensId: String, settings: LensNoiseModelSettings) {
        val safe = settings.sanitized()
        context.dataStore.edit { preferences ->
            preferences[lensStringKey(lensId, "noise_type")] = safe.mode
            preferences[lensStringKey(lensId, "noise_so")] = safe.encoded()
        }
    }

    suspend fun setNoiseModelArray(lensId: String, channel: String, values: List<String>) {
        val suffix = when (channel.uppercase()) {
            "A" -> "noise_a"
            "B" -> "noise_b"
            "C" -> "noise_c"
            "D" -> "noise_d"
            else -> return
        }
        context.dataStore.edit { prefs -> prefs[lensStringKey(lensId, suffix)] = values.joinToString(",") }
    }

    // --- Black Level ---
    fun getBlackLevelModeFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "bl_mode")] ?: "Auto" }
    fun getDynamicBlackLevelFlow(lensId: String) = context.dataStore.data.map { it[lensFloatKey(lensId, "bl_dynamic")] ?: 50f }
    fun getManualBlackLevelsFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "bl_manual")] ?: "" }

    suspend fun setBlackLevelMode(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "bl_mode")] = value }
    suspend fun setDynamicBlackLevel(lensId: String, value: Float) = context.dataStore.edit { it[lensFloatKey(lensId, "bl_dynamic")] = value.coerceIn(0f, 100f) }
    suspend fun setManualBlackLevels(lensId: String, levels: List<String>) = context.dataStore.edit { prefs ->
        prefs[lensStringKey(lensId, "bl_manual")] = levels.joinToString(",")
    }

    fun getLensBlackLevelSettingsFlow(lensId: String): Flow<LensBlackLevelSettings> {
        return context.dataStore.data.map { preferences ->
            LensBlackLevelSettings(
                mode = if (preferences[lensStringKey(lensId, "bl_mode")]
                        .equals(LensHardwareTuningModes.MANUAL, ignoreCase = true)
                ) LensHardwareTuningModes.MANUAL else LensHardwareTuningModes.AUTO,
                values = parseStoredDoubles(preferences[lensStringKey(lensId, "bl_manual")] ?: "", 4)
            ).sanitized()
        }
    }

    /** Mode and all four decimal black-level values are committed atomically. */
    suspend fun setLensBlackLevelSettings(lensId: String, settings: LensBlackLevelSettings) {
        val safe = settings.sanitized()
        context.dataStore.edit { preferences ->
            preferences[lensStringKey(lensId, "bl_mode")] = safe.mode
            preferences[lensStringKey(lensId, "bl_manual")] = safe.encoded()
        }
    }

    // --- Color Matrix ---
    fun getColorMatrixModeFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "cm_mode")] ?: "System" }
    fun getColorMatrixManualFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "cm_manual")] ?: "" }

    suspend fun setColorMatrixMode(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "cm_mode")] = value }
    suspend fun setColorMatrixManual(lensId: String, values: List<String>) = context.dataStore.edit { prefs ->
        prefs[lensStringKey(lensId, "cm_manual")] = values.joinToString(",")
    }

    // --- AWB ---
    fun getAwbProfileFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "awb_profile")] ?: "System" }
    fun getAwbRatioFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "awb_ratio")] ?: "Auto" }
    fun getAwbTempFlow(lensId: String) = context.dataStore.data.map { it[lensFloatKey(lensId, "awb_temp")] ?: 0.0f }
    fun getAwbIntensityFlow(lensId: String) = context.dataStore.data.map { it[lensFloatKey(lensId, "awb_intensity")] ?: 0.0f }

    suspend fun setAwbProfile(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "awb_profile")] = value }
    suspend fun setAwbRatio(lensId: String, value: String) = context.dataStore.edit { it[lensStringKey(lensId, "awb_ratio")] = value }
    suspend fun setAwbTemp(lensId: String, value: Float) = context.dataStore.edit { it[lensFloatKey(lensId, "awb_temp")] = value.coerceIn(-1f, 1f) }
    suspend fun setAwbIntensity(lensId: String, value: Float) = context.dataStore.edit { it[lensFloatKey(lensId, "awb_intensity")] = value.coerceIn(0f, 1f) }

    // --- Lens Detail UI persistence ---
    // These are LensDetailScreen-level settings. Keeping them under the same per-lens
    // hardware prefix makes resetLensHardwareSettings(lensId) clear the full screen state.
    fun getLensProfileAmountFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "profile_amount")] ?: "6" }
    fun getLensManualUpscaleFlow(lensId: String) = context.dataStore.data.map { it[lensStringKey(lensId, "manual_upscale")] ?: "1.0x" }
    fun getLensPreviewOrientationCorrectionFlow(lensId: String) = context.dataStore.data.map {
        it[lensStringKey(lensId, "preview_orientation_correction")] ?: "Auto"
    }

    suspend fun setLensProfileAmount(lensId: String, value: String) = context.dataStore.edit {
        val sanitized = value.trim().toIntOrNull()?.coerceIn(1, 12)?.toString() ?: "6"
        it[lensStringKey(lensId, "profile_amount")] = sanitized
    }
    suspend fun setLensManualUpscale(lensId: String, value: String) = context.dataStore.edit {
        it[lensStringKey(lensId, "manual_upscale")] = value.trim().ifBlank { "1.0x" }
    }

    suspend fun setLensPreviewOrientationCorrection(lensId: String, value: String) = context.dataStore.edit {
        val sanitized = value.takeIf { it in setOf("Auto", "+90°", "+180°", "+270°") } ?: "Auto"
        it[lensStringKey(lensId, "preview_orientation_correction")] = sanitized
    }

    // ==========================================
    // 10. MULTI-FRAME SETTINGS (Phase 5A)
    // ==========================================
    private val multiFrameAlignmentMethodKey = stringPreferencesKey("multiframe_alignment_method")
    private val multiFrameFusionMethodKey = stringPreferencesKey("multiframe_fusion_method")
    private val multiFrameJpegFusionFramesYuvKey = intPreferencesKey("multiframe_jpeg_fusion_frames_yuv")
    private val multiFrameJpegFusionFramesRaw10Key = intPreferencesKey("multiframe_jpeg_fusion_frames_raw10")
    private val multiFrameJpegFusionFramesRawSensorKey = intPreferencesKey("multiframe_jpeg_fusion_frames_raw_sensor")
    private val multiFrameDngMasterFramesRaw10Key = intPreferencesKey("multiframe_dng_master_frames_raw10")
    private val multiFrameDngMasterFramesRawSensorKey = intPreferencesKey("multiframe_dng_master_frames_raw_sensor")
    private val multiFrameExposureStrategyKey = stringPreferencesKey("multiframe_exposure_strategy")

    val multiFrameAlignmentMethodFlow: Flow<String> = context.dataStore.data.map { it[multiFrameAlignmentMethodKey] ?: "Auto" }
    val multiFrameFusionMethodFlow: Flow<String> = context.dataStore.data.map { it[multiFrameFusionMethodKey] ?: "Auto" }
    val multiFrameJpegFusionFramesYuvFlow: Flow<Int> = context.dataStore.data.map {
        (it[multiFrameJpegFusionFramesYuvKey] ?: 8).coerceIn(
            2,
            FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.YUV)
        )
    }
    val multiFrameJpegFusionFramesRaw10Flow: Flow<Int> = context.dataStore.data.map {
        (it[multiFrameJpegFusionFramesRaw10Key] ?: 8).coerceIn(
            2,
            FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW10)
        )
    }
    val multiFrameJpegFusionFramesRawSensorFlow: Flow<Int> = context.dataStore.data.map {
        (it[multiFrameJpegFusionFramesRawSensorKey] ?: 5).coerceIn(
            2,
            FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW_SENSOR)
        )
    }
    val multiFrameDngMasterFramesRaw10Flow: Flow<Int> = context.dataStore.data.map {
        (it[multiFrameDngMasterFramesRaw10Key] ?: 1).coerceIn(
            1,
            FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW10)
        )
    }
    val multiFrameDngMasterFramesRawSensorFlow: Flow<Int> = context.dataStore.data.map {
        (it[multiFrameDngMasterFramesRawSensorKey] ?: 1).coerceIn(
            1,
            FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW_SENSOR)
        )
    }
    val multiFrameExposureStrategyFlow: Flow<String> = context.dataStore.data.map { it[multiFrameExposureStrategyKey] ?: "ETTR" }

    suspend fun setMultiFrameAlignmentMethod(method: String) = context.dataStore.edit {
        it[multiFrameAlignmentMethodKey] =
            com.bncam.core.capture.MultiFrameAlignmentRegistry.canonicalSelectableId(method)
                ?: com.bncam.core.capture.MultiFrameAlignmentRegistry.AUTO.id
    }
    suspend fun setMultiFrameFusionMethod(method: String) = context.dataStore.edit {
        it[multiFrameFusionMethodKey] =
            com.bncam.core.capture.MultiFrameFusionRegistry.canonicalSelectableId(method)
                ?: com.bncam.core.capture.MultiFrameFusionRegistry.AUTO.id
    }
    suspend fun setMultiFrameJpegFusionFramesYuv(count: Int) = context.dataStore.edit {
        it[multiFrameJpegFusionFramesYuvKey] =
            count.coerceIn(2, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.YUV))
    }
    suspend fun setMultiFrameJpegFusionFramesRaw10(count: Int) = context.dataStore.edit {
        it[multiFrameJpegFusionFramesRaw10Key] =
            count.coerceIn(2, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW10))
    }
    suspend fun setMultiFrameJpegFusionFramesRawSensor(count: Int) = context.dataStore.edit {
        it[multiFrameJpegFusionFramesRawSensorKey] =
            count.coerceIn(2, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW_SENSOR))
    }
    suspend fun setMultiFrameDngMasterFramesRaw10(count: Int) = context.dataStore.edit {
        it[multiFrameDngMasterFramesRaw10Key] =
            count.coerceIn(1, FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW10))
    }
    suspend fun setMultiFrameDngMasterFramesRawSensor(count: Int) = context.dataStore.edit {
        it[multiFrameDngMasterFramesRawSensorKey] =
            count.coerceIn(1, FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW_SENSOR))
    }
    // ==========================================
    // 10. MULTI-FRAME SETTINGS PER PROFILE (Phase 5A.1)
    // ==========================================
    fun getProfileMultiFrameAlignmentMethodFlow(profileId: String): Flow<String> =
        getProfileString(profileId, "multiframe_alignment_method", "Auto")

    suspend fun setProfileMultiFrameAlignmentMethod(profileId: String, method: String) =
        setProfileString(
            profileId,
            CaptureSettingKeys.ALIGNMENT_METHOD,
            com.bncam.core.capture.MultiFrameAlignmentRegistry.canonicalSelectableId(method)
                ?: com.bncam.core.capture.MultiFrameAlignmentRegistry.AUTO.id
        )

    fun getProfileMultiFrameFusionMethodFlow(profileId: String): Flow<String> =
        getProfileString(profileId, "multiframe_fusion_method", "Auto")

    suspend fun setProfileMultiFrameFusionMethod(profileId: String, method: String) =
        setProfileString(
            profileId,
            CaptureSettingKeys.FUSION_METHOD,
            com.bncam.core.capture.MultiFrameFusionRegistry.canonicalSelectableId(method)
                ?: com.bncam.core.capture.MultiFrameFusionRegistry.AUTO.id
        )

    // --- Multi-Frame Unified Fusion Frame Count (Phase 5F) ---
    fun getProfileMultiFrameFusionFramesYuvFlow(profileId: String): Flow<Int> =
        getProfileInt(profileId, CaptureSettingKeys.FUSION_FRAMES_YUV, -1).map { val1 ->
            val maximum = FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.YUV)
            if (val1 > 0) val1.coerceIn(2, maximum)
            else getProfileInt(profileId, CaptureSettingKeys.LEGACY_JPEG_FRAMES_YUV, 8).first().coerceIn(2, maximum)
        }

    suspend fun setProfileMultiFrameFusionFramesYuv(profileId: String, count: Int) =
        setProfileInt(
            profileId,
            CaptureSettingKeys.FUSION_FRAMES_YUV,
            count.coerceIn(2, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.YUV))
        )

    fun getProfileMultiFrameFusionFramesRaw10Flow(profileId: String): Flow<Int> =
        getProfileInt(profileId, CaptureSettingKeys.FUSION_FRAMES_RAW10, -1).map { val1 ->
            val maximum = FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW10)
            if (val1 > 0) val1.coerceIn(2, maximum)
            else getProfileInt(
                profileId,
                CaptureSettingKeys.LEGACY_JPEG_FRAMES_RAW10,
                8
            ).first().coerceIn(2, maximum)
        }

    suspend fun setProfileMultiFrameFusionFramesRaw10(profileId: String, count: Int) =
        setProfileInt(
            profileId,
            CaptureSettingKeys.FUSION_FRAMES_RAW10,
            count.coerceIn(2, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW10))
        )

    fun getProfileMultiFrameFusionFramesRawSensorFlow(profileId: String): Flow<Int> =
        getProfileInt(profileId, CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR, -1).map { val1 ->
            val maximum = FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW_SENSOR)
            if (val1 > 0) val1.coerceIn(2, maximum)
            else getProfileInt(
                profileId,
                CaptureSettingKeys.LEGACY_JPEG_FRAMES_RAW_SENSOR,
                5
            ).first().coerceIn(2, maximum)
        }

    suspend fun setProfileMultiFrameFusionFramesRawSensor(profileId: String, count: Int) =
        setProfileInt(
            profileId,
            CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR,
            count.coerceIn(2, FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW_SENSOR))
        )

    // Backward-compatibility aliases for legacy getters & setters
    fun getProfileMultiFrameJpegFusionFramesYuvFlow(profileId: String): Flow<Int> = getProfileMultiFrameFusionFramesYuvFlow(profileId)
    suspend fun setProfileMultiFrameJpegFusionFramesYuv(profileId: String, count: Int) = setProfileMultiFrameFusionFramesYuv(profileId, count)

    fun getProfileMultiFrameJpegFusionFramesRaw10Flow(profileId: String): Flow<Int> = getProfileMultiFrameFusionFramesRaw10Flow(profileId)
    suspend fun setProfileMultiFrameJpegFusionFramesRaw10(profileId: String, count: Int) = setProfileMultiFrameFusionFramesRaw10(profileId, count)

    fun getProfileMultiFrameJpegFusionFramesRawSensorFlow(profileId: String): Flow<Int> = getProfileMultiFrameFusionFramesRawSensorFlow(profileId)
    suspend fun setProfileMultiFrameJpegFusionFramesRawSensor(profileId: String, count: Int) = setProfileMultiFrameFusionFramesRawSensor(profileId, count)

    fun getProfileMultiFrameDngMasterFramesRaw10Flow(profileId: String): Flow<Int> =
        getProfileInt(profileId, CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10, 1).map {
            it.coerceIn(1, FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW10))
        }

    suspend fun setProfileMultiFrameDngMasterFramesRaw10(profileId: String, count: Int) =
        setProfileInt(
            profileId,
            CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10,
            count.coerceIn(1, FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW10))
        )

    fun getProfileMultiFrameDngMasterFramesRawSensorFlow(profileId: String): Flow<Int> =
        getProfileInt(profileId, CaptureSettingKeys.DNG_MASTER_FRAMES_RAW_SENSOR, 1).map {
            it.coerceIn(1, FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW_SENSOR))
        }

    suspend fun setProfileMultiFrameDngMasterFramesRawSensor(profileId: String, count: Int) =
        setProfileInt(
            profileId,
            CaptureSettingKeys.DNG_MASTER_FRAMES_RAW_SENSOR,
            count.coerceIn(1, FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW_SENSOR))
        )

    fun getProfileMultiFrameExposureStrategyFlow(profileId: String): Flow<String> =
        getProfileString(profileId, "multiframe_exposure_strategy", "ETTR")

    suspend fun setProfileMultiFrameExposureStrategy(profileId: String, strategy: String) =
        setProfileString(profileId, "multiframe_exposure_strategy", if (strategy.equals("ETTR", ignoreCase = true)) "ETTR" else "Standard")

    suspend fun copyProfileMultiFrameSettings(fromProfileId: String, toProfileId: String) {
        val align = getProfileString(fromProfileId, "multiframe_alignment_method", "Auto").first()
        val fusion = getProfileString(fromProfileId, "multiframe_fusion_method", "Auto").first()
        val yuvFusion = getProfileMultiFrameFusionFramesYuvFlow(fromProfileId).first()
        val raw10Fusion = getProfileMultiFrameFusionFramesRaw10Flow(fromProfileId).first()
        val rawSensorFusion = getProfileMultiFrameFusionFramesRawSensorFlow(fromProfileId).first()
        val raw10DngMaster = getProfileMultiFrameDngMasterFramesRaw10Flow(fromProfileId).first()
        val rawSensorDngMaster =
            getProfileMultiFrameDngMasterFramesRawSensorFlow(fromProfileId).first()
        val exposure = getProfileString(fromProfileId, "multiframe_exposure_strategy", "ETTR").first()

        setProfileString(toProfileId, "multiframe_alignment_method", align)
        setProfileString(toProfileId, "multiframe_fusion_method", fusion)
        setProfileMultiFrameFusionFramesYuv(toProfileId, yuvFusion)
        setProfileMultiFrameFusionFramesRaw10(toProfileId, raw10Fusion)
        setProfileMultiFrameFusionFramesRawSensor(toProfileId, rawSensorFusion)
        setProfileMultiFrameDngMasterFramesRaw10(toProfileId, raw10DngMaster)
        setProfileMultiFrameDngMasterFramesRawSensor(toProfileId, rawSensorDngMaster)
        setProfileString(toProfileId, "multiframe_exposure_strategy", exposure)
    }
    suspend fun readLensHardwareSettingsSnapshot(lensId: String): ResolvedLensHardwareSettings {
        val noiseSettings = getLensNoiseModelSettingsFlow(lensId).first()
        val blackSettings = getLensBlackLevelSettingsFlow(lensId).first()
        return LensHardwareSettingsResolver.resolve(
        lensId = lensId,
        noiseModelType = noiseSettings.mode,
        noiseAString = getNoiseModelAFlow(lensId).first(),
        noiseBString = getNoiseModelBFlow(lensId).first(),
        noiseCString = getNoiseModelCFlow(lensId).first(),
        noiseDString = getNoiseModelDFlow(lensId).first(),
        isoStepString = getIsoStepFlow(lensId).first(),
        isoNrStyle = getIsoNrStyleFlow(lensId).first(),
        dynamicIsoCoeff = getDynamicIsoCoeffFlow(lensId).first(),
        manualIsoValueString = getManualIsoValueFlow(lensId).first(),
        blackLevelMode = blackSettings.mode,
        dynamicBlackLevel = getDynamicBlackLevelFlow(lensId).first(),
        manualBlackLevelsString = blackSettings.encoded(),
        colorMatrixMode = getColorMatrixModeFlow(lensId).first(),
        manualColorMatrixString = getColorMatrixManualFlow(lensId).first(),
        awbProfile = getAwbProfileFlow(lensId).first(),
        awbRatio = getAwbRatioFlow(lensId).first(),
        awbTemp = getAwbTempFlow(lensId).first(),
        awbIntensity = getAwbIntensityFlow(lensId).first(),
        manualNoiseSoString = noiseSettings.encoded(),
        noiseCalibrationAdj = getNoiseModelCalibrationAdjustmentFlow(lensId).first(),
        chromaAuthorityAdj = getDynamicChromaAuthorityAdjustmentFlow(lensId).first(),
        lumaAuthorityAdj = getDynamicLumaAuthorityAdjustmentFlow(lensId).first()
        )
    }

    suspend fun resetLensHardwareSettings(lensId: String) {
        context.dataStore.edit { prefs ->
            val prefix = "hardware_lens_${safeLensKeyPart(lensId)}_"
            prefs.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    prefs.remove(key as Preferences.Key<Any>)
                }
        }
    }

    // ==========================================
    // PHASE 4 BLOCK 1: PER-LENS PROFILES & SHUTTER SNAPSHOT
    // ==========================================

    fun getActiveProfileForLensFlow(lensId: String): Flow<String?> {
        val key = stringPreferencesKey("active_profile_for_lens_${safeLensKeyPart(lensId)}")
        return context.dataStore.data.map { it[key] }
    }

    suspend fun setActiveProfileForLens(lensId: String, profileId: String) {
        val key = stringPreferencesKey("active_profile_for_lens_${safeLensKeyPart(lensId)}")
        context.dataStore.edit { it[key] = profileId }
    }

    fun getProfilesForLensFlow(lensId: String): Flow<List<com.bncam.data.profile.IspProfileConfig>> {
        val key = stringSetPreferencesKey("profiles_store_for_lens_${safeLensKeyPart(lensId)}")
        return context.dataStore.data.map { prefs ->
            val set = prefs[key] ?: emptySet()
            if (set.isEmpty()) {
                val def = com.bncam.data.profile.IspProfileConfig.createDefault(lensId)
                listOf(def)
            } else {
                set.mapNotNull { raw ->
                    runCatching {
                        val obj = org.json.JSONObject(raw)
                        com.bncam.data.profile.IspProfileConfig.createDefault(lensId).copy(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            name = obj.optString("name", "Profile"),
                            ownerStableLensKey = obj.optString("ownerStableLensKey", obj.optString("ownerLensId", lensId)),
                            isDefault = obj.optBoolean("isDefault", false),
                            revision = obj.optLong("revision", 1L),
                            lastModifiedMs = obj.optLong("lastModifiedMs", System.currentTimeMillis())
                        )
                    }.getOrNull()
                }.ifEmpty { listOf(com.bncam.data.profile.IspProfileConfig.createDefault(lensId)) }
            }
        }
    }

    fun getProfileByIdFlow(profileId: String): Flow<com.bncam.data.profile.IspProfileConfig?> {
        return context.dataStore.data.map { prefs ->
            prefs.asMap().values.filterIsInstance<Set<String>>()
                .flatMap { it }
                .mapNotNull { raw ->
                    runCatching {
                        val obj = org.json.JSONObject(raw)
                        if (obj.optString("id") == profileId) {
                            val ownerKey = obj.optString("ownerStableLensKey", obj.optString("ownerLensId", "0"))
                            com.bncam.data.profile.IspProfileConfig.createDefault(ownerKey).copy(
                                id = obj.optString("id"),
                                name = obj.optString("name", "Profile"),
                                ownerStableLensKey = ownerKey,
                                isDefault = obj.optBoolean("isDefault", false),
                                revision = obj.optLong("revision", 1L),
                                lastModifiedMs = obj.optLong("lastModifiedMs", System.currentTimeMillis())
                            )
                        } else null
                    }.getOrNull()
                }.firstOrNull() ?: com.bncam.data.profile.IspProfileConfig.createDefault("0").copy(id = profileId)
        }
    }

    suspend fun createProfileForLens(lensId: String, name: String): com.bncam.data.profile.IspProfileConfig {
        val newProfile = com.bncam.data.profile.IspProfileConfig.createDefault(lensId).copy(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            isDefault = false,
            revision = 1L
        )
        updateProfileConfig(newProfile)
        return newProfile
    }

    suspend fun renameProfile(profileId: String, newName: String) {
        val p = getProfileByIdFlow(profileId).first() ?: return
        updateProfileConfig(p.copy(name = newName, revision = p.revision + 1L))
    }

    suspend fun duplicateProfile(profileId: String, newName: String): com.bncam.data.profile.IspProfileConfig {
        val original = getProfileByIdFlow(profileId).first() ?: com.bncam.data.profile.IspProfileConfig.createDefault("0")
        val dup = original.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = newName,
            isDefault = false,
            revision = 1L,
            lastModifiedMs = System.currentTimeMillis()
        )
        updateProfileConfig(dup)
        return dup
    }

    suspend fun copyProfileToLens(profileId: String, destinationLensId: String, newName: String): com.bncam.data.profile.IspProfileConfig {
        val original = getProfileByIdFlow(profileId).first() ?: com.bncam.data.profile.IspProfileConfig.createDefault(destinationLensId)
        val copy = original.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = newName,
            ownerStableLensKey = destinationLensId,
            isDefault = false,
            revision = 1L,
            lastModifiedMs = System.currentTimeMillis()
        )
        updateProfileConfig(copy)
        return copy
    }

    suspend fun deleteProfile(lensId: String, profileId: String) {
        val key = stringSetPreferencesKey("profiles_store_for_lens_${safeLensKeyPart(lensId)}")
        context.dataStore.edit { prefs ->
            val set = prefs[key]?.toMutableSet() ?: return@edit
            if (set.size <= 1) return@edit // Preserve minimum 1 profile
            set.removeAll { raw ->
                runCatching { org.json.JSONObject(raw).optString("id") == profileId }.getOrDefault(false)
            }
            prefs[key] = set

            val activeKey = stringPreferencesKey("active_profile_for_lens_${safeLensKeyPart(lensId)}")
            if (prefs[activeKey] == profileId) {
                val remainingId = set.mapNotNull { raw ->
                    runCatching { org.json.JSONObject(raw).optString("id") }.getOrNull()
                }.firstOrNull() ?: "default_$lensId"
                prefs[activeKey] = remainingId
            }
        }
    }

    suspend fun updateProfileConfig(config: com.bncam.data.profile.IspProfileConfig) {
        val key = stringSetPreferencesKey("profiles_store_for_lens_${safeLensKeyPart(config.ownerLensId)}")
        context.dataStore.edit { prefs ->
            val set = prefs[key]?.toMutableSet() ?: mutableSetOf()
            set.removeAll { raw ->
                runCatching { org.json.JSONObject(raw).optString("id") == config.id }.getOrDefault(false)
            }

            val json = org.json.JSONObject()
            json.put("id", config.id)
            json.put("name", config.name)
            json.put("ownerStableLensKey", config.ownerStableLensKey)
            json.put("ownerLensId", config.ownerStableLensKey)
            json.put("isDefault", config.isDefault)
            json.put("revision", config.revision)
            json.put("lastModifiedMs", System.currentTimeMillis())
            set.add(json.toString())

            prefs[key] = set
        }
    }

    fun getOutputModeSettingsFlow(): Flow<com.bncam.data.settings.OutputModeSettings> {
        return context.dataStore.data.map { prefs ->
            val rawPolicyStr = prefs[stringPreferencesKey("app_dng_source_policy")] ?: "ANCHOR_RAW"
            val policy = try {
                com.bncam.data.settings.DngSourcePolicy.valueOf(rawPolicyStr)
            } catch (_: Throwable) {
                com.bncam.data.settings.DngSourcePolicy.ANCHOR_RAW
            }
            val rawFrames = prefs[intPreferencesKey("app_dng_master_frames_raw")] ?: 1
            val config = com.bncam.data.settings.OutputModeDngConfig(
                dngSourcePolicy = policy,
                dngMasterFrameCount = rawFrames
            )
            com.bncam.data.settings.OutputModeSettings(
                jpegOnly = com.bncam.data.settings.OutputModeDngConfig(dngSourcePolicy = com.bncam.data.settings.DngSourcePolicy.ANCHOR_RAW, dngMasterFrameCount = 1),
                rawPlusJpeg = config,
                rawOnly = config
            )
        }
    }

    suspend fun setOutputModeSettings(config: com.bncam.data.settings.OutputModeSettings) {
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("app_dng_source_policy")] = config.rawPlusJpeg.dngSourcePolicy.name
            prefs[intPreferencesKey("app_dng_master_frames_raw")] = config.rawPlusJpeg.dngMasterFrameCount
        }
    }

    suspend fun createEffectiveShutterSnapshot(
        cameraId: String,
        physicalCameraId: String?,
        lensId: String,
        outputPolicy: com.bncam.core.capture.OutputPolicy,
        activeProfile: com.bncam.data.profile.IspProfileConfig,
        lensCalibration: com.bncam.data.settings.LensCalibrationConfig
    ): com.bncam.core.capture.EffectiveShutterSnapshot {
        val appOutputSettings = getOutputModeSettingsFlow().first().getConfigForOutputPolicy(outputPolicy)

        val frameSource = readProfileFrameSource(activeProfile.id)
        val captureMode = readProfileCaptureModeName(activeProfile.id)
        val frameOrigin = when (frameSource.uppercase()) {
            "RAW10" -> com.bncam.core.capture.FrameOrigin.RAW10
            "RAW_SENSOR" -> com.bncam.core.capture.FrameOrigin.RAW_SENSOR
            else -> com.bncam.core.capture.FrameOrigin.YUV
        }
        val configuredJpegFrames = when {
            outputPolicy == com.bncam.core.capture.OutputPolicy.RAW_ONLY -> 0
            captureMode != CaptureStrategy.MULTI_FRAME_ZSL.name -> 1
            frameOrigin == com.bncam.core.capture.FrameOrigin.RAW10 -> getProfileMultiFrameFusionFramesRaw10Flow(activeProfile.id).first()
            frameOrigin == com.bncam.core.capture.FrameOrigin.RAW_SENSOR -> getProfileMultiFrameFusionFramesRawSensorFlow(activeProfile.id).first()
            else -> getProfileMultiFrameFusionFramesYuvFlow(activeProfile.id).first()
        }
        val maxJpegFrames = com.bncam.core.capture.FrameCapacityPolicy.maximumProcessingFrames(frameOrigin)
        val effectiveJpegFrames = if (outputPolicy == com.bncam.core.capture.OutputPolicy.RAW_ONLY) 0 else configuredJpegFrames.coerceIn(1, maxJpegFrames)
        val jpegReason = if (configuredJpegFrames > maxJpegFrames) {
            "Configured count $configuredJpegFrames exceeded max $maxJpegFrames; clamped to $effectiveJpegFrames"
        } else "Configured count $configuredJpegFrames within limit $maxJpegFrames"

        val dngOrigin = if (frameOrigin == com.bncam.core.capture.FrameOrigin.RAW_SENSOR) {
            com.bncam.core.capture.FrameOrigin.RAW_SENSOR
        } else {
            com.bncam.core.capture.FrameOrigin.RAW10
        }
        val configuredDngFrames = when {
            outputPolicy == com.bncam.core.capture.OutputPolicy.JPEG -> 1
            appOutputSettings.dngSourcePolicy == com.bncam.data.settings.DngSourcePolicy.ANCHOR_RAW -> 1
            else -> appOutputSettings.dngMasterFrameCount
        }
        val maxDngFrames = com.bncam.core.capture.FrameCapacityPolicy.maximumDngMasterFrames(dngOrigin)
        val effectiveDngFrames = if (outputPolicy == com.bncam.core.capture.OutputPolicy.JPEG || appOutputSettings.dngSourcePolicy == com.bncam.data.settings.DngSourcePolicy.ANCHOR_RAW) 1 else configuredDngFrames.coerceIn(1, maxDngFrames)
        val dngReason = if (configuredDngFrames > maxDngFrames) {
            "Configured DNG count $configuredDngFrames exceeded max $maxDngFrames; clamped to $effectiveDngFrames"
        } else "Configured DNG count $configuredDngFrames within limit $maxDngFrames"

        val impactMap = mapOf(
            CaptureSettingKeys.FUSION_FRAMES_YUV to listOf(com.bncam.data.profile.SettingImpact.JPEG_FRAME_SELECTION),
            CaptureSettingKeys.FUSION_FRAMES_RAW10 to listOf(com.bncam.data.profile.SettingImpact.JPEG_FRAME_SELECTION),
            CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR to listOf(com.bncam.data.profile.SettingImpact.JPEG_FRAME_SELECTION),
            CaptureSettingKeys.BASE_BIAS to listOf(com.bncam.data.profile.SettingImpact.JPEG_FRAME_SELECTION),
            "outputDng.dngFusion" to if (appOutputSettings.dngSourcePolicy == com.bncam.data.settings.DngSourcePolicy.FUSED_RAW) {
                listOf(com.bncam.data.profile.SettingImpact.MASTER_DNG_FRAME_SELECTION, com.bncam.data.profile.SettingImpact.MASTER_DNG_PIXELS)
            } else listOf(com.bncam.data.profile.SettingImpact.DNG_METADATA)
        )

        val stableKey = buildStableLensKey(cameraId, physicalCameraId)
        return com.bncam.core.capture.EffectiveShutterSnapshot(
            stableLensKey = stableKey,
            logicalCameraId = cameraId,
            physicalCameraId = physicalCameraId,
            profileId = activeProfile.id,
            profileName = activeProfile.name,
            profileSchemaVersion = activeProfile.schemaVersion,
            profileRevision = activeProfile.revision,
            calibration = lensCalibration,
            profile = activeProfile,
            outputDngSettings = appOutputSettings,
            outputPolicy = outputPolicy,
            dngSourcePolicy = appOutputSettings.dngSourcePolicy.name,
            configuredJpegFrameCount = configuredJpegFrames,
            effectiveJpegFrameCount = effectiveJpegFrames,
            jpegFrameCountResolutionReason = jpegReason,
            configuredDngMasterFrameCount = configuredDngFrames,
            effectiveDngMasterFrameCount = effectiveDngFrames,
            dngFrameCountResolutionReason = dngReason,
            settingImpactMap = impactMap,
            effectiveVulkanParameters = mapOf(
                ProfileIspKeys.TONE_EXPOSURE to getProfileFloat(activeProfile.id, ProfileIspKeys.TONE_EXPOSURE, 0f).first(),
                ProfileIspKeys.TONE_HIGHLIGHTS to getProfileFloat(activeProfile.id, ProfileIspKeys.TONE_HIGHLIGHTS, 0f).first(),
                ProfileIspKeys.TONE_SHADOWS to getProfileFloat(activeProfile.id, ProfileIspKeys.TONE_SHADOWS, 0f).first(),
                ProfileIspKeys.TONE_WHITES to getProfileFloat(activeProfile.id, ProfileIspKeys.TONE_WHITES, 0f).first(),
                ProfileIspKeys.TONE_BLACKS to getProfileFloat(activeProfile.id, ProfileIspKeys.TONE_BLACKS, 0f).first(),
                ProfileIspKeys.TONE_CONTRAST to getProfileFloat(activeProfile.id, ProfileIspKeys.TONE_CONTRAST, 0f).first(),
                ProfileIspKeys.PRESENCE_VIBRANCE to getProfileFloat(activeProfile.id, ProfileIspKeys.PRESENCE_VIBRANCE, 0f).first(),
                ProfileIspKeys.PRESENCE_SATURATION to getProfileFloat(activeProfile.id, ProfileIspKeys.PRESENCE_SATURATION, 0f).first(),
                ProfileIspKeys.PRESENCE_POP to getProfileFloat(activeProfile.id, ProfileIspKeys.PRESENCE_POP, 0f).first(),
                ProfileIspKeys.PRESENCE_COLOR_RECOVERY to getProfileFloat(activeProfile.id, ProfileIspKeys.PRESENCE_COLOR_RECOVERY, 0f).first(),
                CaptureSettingKeys.JPEG_QUALITY to getProfileInt(activeProfile.id, CaptureSettingKeys.JPEG_QUALITY, 98).first().coerceIn(80, 100).toFloat()
            )
        )
    }

}
