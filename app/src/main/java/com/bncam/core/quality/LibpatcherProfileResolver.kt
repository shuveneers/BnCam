package com.bncam.core.quality

import android.graphics.ImageFormat
import com.bncam.core.engine.CaptureStrategy
import com.bncam.data.settings.CaptureSettingKeys
import com.bncam.data.settings.ProfileSettingSpec
import com.bncam.data.settings.ProfileSettingValueType
import com.bncam.data.settings.ProfileIspKeys
import com.bncam.data.settings.ProfileDetailDefaults
import com.bncam.data.settings.ProfileNoiseReductionDefaults
import com.bncam.data.settings.ProfilePlannedDefaults
import com.bncam.data.settings.ProfileSharpnessMethods
import com.bncam.data.settings.ProfileAwbModes
import com.bncam.data.settings.ProfileAwbModels
import com.bncam.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.Locale

sealed class ResolvedLibpatcherValue {
    data class FloatValue(val value: Float) : ResolvedLibpatcherValue()
    data class BooleanValue(val value: Boolean) : ResolvedLibpatcherValue()
    data class StringValue(val value: String) : ResolvedLibpatcherValue()

    fun asDebugString(): String = when (this) {
        is FloatValue -> if (value > 0f) String.format(Locale.US, "+%.2f", value) else String.format(Locale.US, "%.2f", value)
        is BooleanValue -> value.toString()
        is StringValue -> value
    }

    fun isNeutral(): Boolean = when (this) {
        is FloatValue -> value == 0f
        is BooleanValue -> !value
        is StringValue -> value.equals("Auto", ignoreCase = true) || value.equals("Default", ignoreCase = true)
    }
}

data class ResolvedLibpatcherSetting(
    val key: String,
    val title: String,
    val sectionTitle: String,
    val topicTitle: String,
    val groupTitle: String,
    val kind: LibpatcherControlKind,
    val runtimeScope: LibpatcherRuntimeScope,
    val value: ResolvedLibpatcherValue,
    val active: Boolean,
    val visible: Boolean = true,
    val source: String = "default",
    val actualTechnicalValue: String = value.asDebugString(),
    val mappedRuntimeValue: String = value.asDebugString(),
    val appliedStage: String = runtimeScope.name
) {
    fun debugLine(): String {
        val state = if (active) "active" else "neutral/default"
        return "$sectionTitle > $topicTitle > $groupTitle > $title | key=$key | visible=$visible | active=$active | source=$source | actual=$actualTechnicalValue | mapped=$mappedRuntimeValue | stage=$appliedStage | state=$state"
    }
}

data class HiddenLibpatcherSetting(
    val key: String,
    val title: String,
    val sectionTitle: String,
    val topicTitle: String,
    val reason: String
) {
    fun debugLine(): String = "$sectionTitle > $topicTitle > $title -> $reason"
}

data class ResolvedIspSettings(
    val profileId: String,
    val captureMode: CaptureStrategy,
    val frameSource: String,
    val routeName: String,
    val activeSettings: List<ResolvedLibpatcherSetting>,
    val hiddenSettings: List<HiddenLibpatcherSetting>
) {
    val commonRenderSettings: List<ResolvedLibpatcherSetting> = activeSettings.filter { it.runtimeScope == LibpatcherRuntimeScope.COMMON_RENDER }
    val rawDemosaicSettings: List<ResolvedLibpatcherSetting> = activeSettings.filter { it.runtimeScope == LibpatcherRuntimeScope.RAW_DEMOSAIC }
    val outputEncodeSettings: List<ResolvedLibpatcherSetting> = activeSettings.filter { it.runtimeScope == LibpatcherRuntimeScope.OUTPUT_ENCODE }

    val activeCommonRenderCount: Int = commonRenderSettings.count { it.active }
    val activeOutputEncodeCount: Int = outputEncodeSettings.count { it.active }

    fun debugPairs(maxActiveLines: Int = 80, maxHiddenLines: Int = 40): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        pairs += "Profile ID" to profileId
        pairs += "Capture Mode" to captureMode.name
        pairs += "Frame Source" to frameSource
        pairs += "Resolved Route" to routeName
        pairs += "Default ISP Version" to DefaultIspProfile.VERSION
        pairs += "Disabled Profile" to DefaultIspProfile.isDisabledProfileId(profileId).toString()
        pairs += "Pixel Effects Applied In This Phase" to "Profile resolver exposes only JPEG output encode controls. Curve settings are resolved by RenderQualityConfig and logged separately."
        pairs += "Runtime Scope" to "Legacy libpatcher common-render keys are no longer visible, resolved, or exported as active settings."
        pairs += "Common Render Visible" to commonRenderSettings.size.toString()
        pairs += "Common Render Active" to activeCommonRenderCount.toString()
        pairs += "Output Encode Visible" to outputEncodeSettings.size.toString()
        pairs += "Output Encode Active" to activeOutputEncodeCount.toString()
        pairs += "Hidden / Locked Preserved Settings" to hiddenSettings.size.toString()

        activeSettings
            .take(maxActiveLines)
            .forEachIndexed { index, setting -> pairs += "Visible Setting ${index + 1}" to setting.debugLine() }

        val neutralCount = activeSettings.count { !it.active }
        pairs += "Neutral Visible Settings" to neutralCount.toString()

        hiddenSettings
            .take(maxHiddenLines)
            .forEachIndexed { index, hidden -> pairs += "Hidden Setting ${index + 1}" to hidden.debugLine() }

        if (activeSettings.size > maxActiveLines) {
            pairs += "Visible Settings Truncated" to "${activeSettings.size - maxActiveLines} more visible settings not listed"
        }
        if (hiddenSettings.size > maxHiddenLines) {
            pairs += "Hidden Settings Truncated" to "${hiddenSettings.size - maxHiddenLines} more hidden settings not listed"
        }
        return pairs
    }
}

object LibpatcherProfileResolver {
    fun runtimeProfileSettingSpecs(): List<ProfileSettingSpec> {
        fun f(key: String, default: Float = 0f) =
            ProfileSettingSpec(key, ProfileSettingValueType.FLOAT, default.toString())
        fun i(key: String, default: Int) =
            ProfileSettingSpec(key, ProfileSettingValueType.INT, default.toString())
        fun s(key: String, default: String) =
            ProfileSettingSpec(key, ProfileSettingValueType.STRING, default)

        return buildList {
            add(i("post_jpeg_quality", 98))
            add(s(DemosaicMode.PROFILE_KEY, DemosaicMode.DEFAULT.displayName))
            // Legacy native tone-shape keys are intentionally not part of the portable/runtime
            // profile contract anymore. GTM/LTM + Lightroom-style tone controls own tone shape.

            // Lightroom-style tone controls. 0.00 is the neutral BnCam automatic baseline.
            add(f(ProfileIspKeys.TONE_EXPOSURE))
            add(f(ProfileIspKeys.TONE_HIGHLIGHTS))
            add(f(ProfileIspKeys.TONE_SHADOWS))
            add(f(ProfileIspKeys.TONE_WHITES))
            add(f(ProfileIspKeys.TONE_BLACKS))
            add(f(ProfileIspKeys.TONE_CONTRAST))
            // Profile V3 removed the old Local Tone Bias control. Keep the persisted key
            // readable for backwards compatibility, but do not export or execute it.

            // SPECTRA profile module. Physical per-lens sensor calibration remains outside profiles.
            add(i(ProfileIspKeys.SPECTRA_ENABLED, 0))
            add(f(ProfileIspKeys.SPECTRA_DYNAMIC_ISO, SpectraProfileDefaults.DYNAMIC_ISO))
            add(f(ProfileIspKeys.SPECTRA_LUMA, SpectraProfileDefaults.LUMA))
            add(f(ProfileIspKeys.SPECTRA_CHROMA, SpectraProfileDefaults.CHROMA))
            add(f(ProfileIspKeys.SPECTRA_DETAIL, SpectraProfileDefaults.DETAIL_PROTECTION))
            add(f(ProfileIspKeys.SPECTRA_LOW_FREQUENCY, SpectraProfileDefaults.LOW_FREQUENCY))
            add(f(ProfileIspKeys.DETAIL_NR_LUMINANCE, ProfileNoiseReductionDefaults.LUMINANCE))
            add(f(ProfileIspKeys.DETAIL_NR_LUMINANCE_DETAIL, ProfileNoiseReductionDefaults.LUMINANCE_DETAIL))
            add(f(ProfileIspKeys.DETAIL_NR_LUMINANCE_CONTRAST, ProfileNoiseReductionDefaults.LUMINANCE_CONTRAST))
            add(f(ProfileIspKeys.DETAIL_NR_COLOR, ProfileNoiseReductionDefaults.COLOR))
            add(f(ProfileIspKeys.DETAIL_NR_COLOR_DETAIL, ProfileNoiseReductionDefaults.COLOR_DETAIL))
            add(f(ProfileIspKeys.DETAIL_NR_COLOR_SMOOTHNESS, ProfileNoiseReductionDefaults.COLOR_SMOOTHNESS))

            add(f(ProfileIspKeys.PRESENCE_VIBRANCE))
            add(f(ProfileIspKeys.PRESENCE_SATURATION))
            add(f(ProfileIspKeys.PRESENCE_POP))
            add(f(ProfileIspKeys.PRESENCE_COLOR_RECOVERY))

            // Normal-vs-Polysharp ownership is runtime-relevant even while Polysharp itself is not
            // connected: selecting Polysharp must disable the Normal backend rather than stacking both.
            add(s(ProfileIspKeys.DETAIL_SHARPENING_METHOD, ProfileSharpnessMethods.NORMAL))
            add(f(ProfileIspKeys.DETAIL_SHARPENING_AMOUNT, ProfileDetailDefaults.AMOUNT))
            add(f(ProfileIspKeys.DETAIL_SHARPENING_RADIUS, ProfileDetailDefaults.RADIUS))
            add(f(ProfileIspKeys.DETAIL_SHARPENING_DETAIL, ProfileDetailDefaults.DETAIL))
            add(f(ProfileIspKeys.DETAIL_SHARPENING_LEGIBILITY, ProfilePlannedDefaults.LEGIBILITY))
            add(f(ProfileIspKeys.DETAIL_SHARPENING_ANTI_ZIPPER, ProfilePlannedDefaults.ANTI_ZIPPER))
            add(f(ProfileIspKeys.DETAIL_SHARPENING_MASKING, ProfileDetailDefaults.MASKING))

            // Complete profile AWB tuple.
            add(s("awb_mode", ProfileAwbModes.SYSTEM_AUTO))
            add(s("awb_brand", "Canon"))
            add(s("awb_preset", "Daylight"))
            add(i("awb_kelvin", 5200))
            add(s("awb_model", ProfileAwbModels.CIE_DAYLIGHT))
            add(f("awb_tint"))
            add(f("awb_reference_intensity", 1f))

            // Capture-route settings owned by the profile.
            add(s(CaptureSettingKeys.ALIGNMENT_METHOD, "Auto"))
            add(s(CaptureSettingKeys.FUSION_METHOD, "Auto"))
            add(i(CaptureSettingKeys.FUSION_FRAMES_YUV, 8))
            add(i(CaptureSettingKeys.FUSION_FRAMES_RAW10, 8))
            add(i(CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR, 5))
            add(s(CaptureSettingKeys.EXPOSURE_STRATEGY, "ETTR"))

            listOf(
                ProfileCurveDefaults.TYPE_TONE,
                ProfileCurveDefaults.TYPE_GAMMA,
                ProfileCurveDefaults.TYPE_SECT
            ).forEach { type ->
                add(s(ProfileCurveDefaults.presetKey(type), ProfileCurveDefaults.PRESET_DEFAULT))
                val defaults = DefaultIspProfile.curveNodes(type)
                repeat(ProfileCurveDefaults.nodeCount(type)) { index ->
                    add(f(ProfileCurveDefaults.pointKey(type, index), defaults[index]))
                }
            }
        }
    }

    private fun plannedProfileSettingSpecs(): List<ProfileSettingSpec> = listOf(
        // Visible V3 placeholders. These values round-trip through .bnc but are intentionally
        // excluded from runtimeProfileSettingSpecs until their dedicated processing backend exists.
        ProfileSettingSpec(ProfileIspKeys.TONE_GAMMA_CONTRAST, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.GAMMA_CONTRAST.toString()),
        ProfileSettingSpec(ProfileIspKeys.TONE_DEHAZE, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.DEHAZE.toString()),
        ProfileSettingSpec(ProfileIspKeys.TONE_CLARITY, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.CLARITY.toString()),
        ProfileSettingSpec(ProfileIspKeys.PRESENCE_COLOR_FRINGE_SUPPRESSION, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.COLOR_FRINGE_SUPPRESSION.toString()),
        ProfileSettingSpec(ProfileIspKeys.DETAIL_SHARPENING_EDGE, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.EDGE_SHARPNESS.toString()),
        ProfileSettingSpec(ProfileIspKeys.DETAIL_SHARPENING_ANTI_ZIPPER, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.ANTI_ZIPPER.toString()),
        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_GAIN, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.POLYSHARP_GAIN.toString()),
        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_MACRO_GAIN, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.POLYSHARP_MACRO_GAIN.toString()),
        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_MICRO_GAIN, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.POLYSHARP_MICRO_GAIN.toString()),
        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_MAX_DETAIL, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.POLYSHARP_MAX_DETAIL.toString()),
        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_RADIUS_SMALL, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.POLYSHARP_RADIUS_SMALL.toString()),
        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_RADIUS_MEDIUM, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.POLYSHARP_RADIUS_MEDIUM.toString()),
        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_RADIUS_LARGE, ProfileSettingValueType.FLOAT, ProfilePlannedDefaults.POLYSHARP_RADIUS_LARGE.toString()),
        ProfileSettingSpec(CaptureSettingKeys.SELECTION_ACCEPT_ALL, ProfileSettingValueType.BOOLEAN, false.toString())
    )

    fun allProfileSettingSpecs(): List<ProfileSettingSpec> {
        fun f(key: String, default: Float) =
            ProfileSettingSpec(key, ProfileSettingValueType.FLOAT, default.toString())
        fun i(key: String, default: Int) =
            ProfileSettingSpec(key, ProfileSettingValueType.INT, default.toString())
        fun b(key: String, default: Boolean) =
            ProfileSettingSpec(key, ProfileSettingValueType.BOOLEAN, default.toString())
        fun s(key: String, default: String) =
            ProfileSettingSpec(key, ProfileSettingValueType.STRING, default)

        // This list is the complete portable profile contract, not only the currently visible ISP UI.
        // Capture selection/merge values are profile-owned and therefore must round-trip too, including
        // values that are temporarily hidden by capability/mode gating. Physical lens calibration, global
        // app/output settings and legacy duplicate keys remain outside the portable profile boundary.
        val captureProfileSpecs = listOf(
            s(CaptureSettingKeys.SHOT_BIAS_EXPOSURE, "Auto"),
            s(CaptureSettingKeys.SHOT_BIAS_MAX_FRAME_EXPOSURE, "Max exposure time"),
            f(CaptureSettingKeys.CAPTURE_EV_BIAS, 0.0f)
        )

        return (runtimeProfileSettingSpecs() + captureProfileSpecs + plannedProfileSettingSpecs())
            .distinctBy { "${it.type}:${it.key}" }
    }

    suspend fun resolve(
        repo: SettingsRepository,
        profileId: String,
        captureMode: CaptureStrategy,
        frameSourceFormat: Int
    ): ResolvedIspSettings {
        return resolve(
            repo = repo,
            profileId = profileId,
            captureMode = captureMode,
            frameSource = frameSourceForSettings(frameSourceFormat)
        )
    }

    suspend fun resolve(
        repo: SettingsRepository,
        profileId: String,
        captureMode: CaptureStrategy,
        frameSource: String
    ): ResolvedIspSettings {
        val normalizedFrameSource = normalizeFrameSource(frameSource)
        val visibleRefs = LibpatcherSettingsCatalog.visibleRuntimeIspControlRefs(captureMode, normalizedFrameSource)
        val activeSettings = visibleRefs.mapNotNull { ref -> resolveRef(repo, profileId, ref, normalizedFrameSource) }
        val hiddenSettings = LibpatcherSettingsCatalog.hiddenRuntimeIspControlRefs(captureMode, normalizedFrameSource)
            .map { (ref, reason) ->
                HiddenLibpatcherSetting(
                    key = ref.key,
                    title = ref.control.title,
                    sectionTitle = ref.section.title,
                    topicTitle = ref.topic.title,
                    reason = reason
                )
            }

        return ResolvedIspSettings(
            profileId = profileId,
            captureMode = captureMode,
            frameSource = normalizedFrameSource,
            routeName = LibpatcherSettingsCatalog.routeName(captureMode, normalizedFrameSource),
            activeSettings = activeSettings,
            hiddenSettings = hiddenSettings
        )
    }

    private suspend fun resolveRef(
        repo: SettingsRepository,
        profileId: String,
        ref: LibpatcherControlRef,
        frameSource: String
    ): ResolvedLibpatcherSetting? {
        if (!ref.isPersistent) return null
        val disabled = DefaultIspProfile.isDisabledProfileId(profileId)
        val isJpegQuality = ref.control.title == "JPEG Quality"
        val key = if (isJpegQuality) "post_jpeg_quality" else ref.key
        val source = if (!disabled && repo.hasProfileOverride(profileId, key)) "profile override" else "default"

        val value: ResolvedLibpatcherValue = when (ref.control.kind) {
            LibpatcherControlKind.BASELINE_SLIDER -> {
                when {
                    isJpegQuality -> {
                        val q = if (source == "profile override") {
                            repo.getProfileInt(profileId, "post_jpeg_quality", 98).first().coerceIn(80, 100)
                        } else {
                            98
                        }
                        ResolvedLibpatcherValue.FloatValue(q.toFloat())
                    }
                    else -> {
                        val mapped = if (source == "profile override") {
                            repo.getProfileFloat(profileId, ref.key, 0f).first().coerceIn(-1f, 1f)
                        } else {
                            0f
                        }
                        ResolvedLibpatcherValue.FloatValue(mapped)
                    }
                }
            }
            LibpatcherControlKind.OPTIONAL_SLIDER -> {
                val mapped = if (source == "profile override") repo.getProfileFloat(profileId, ref.key, 0f).first().coerceIn(0f, 1f) else 0f
                ResolvedLibpatcherValue.FloatValue(mapped)
            }
            LibpatcherControlKind.TOGGLE -> ResolvedLibpatcherValue.BooleanValue(
                source == "profile override" && repo.getProfileBoolean(profileId, ref.key, false).first()
            )
            LibpatcherControlKind.ENUM -> {
                val defaultValue = ref.control.defaultValue.ifBlank { "Default" }
                val storedValue = if (source == "profile override") {
                    repo.getProfileString(profileId, ref.key, defaultValue).first()
                } else {
                    defaultValue
                }
                val resolvedValue = if (ref.key == DemosaicMode.PROFILE_KEY) {
                    DemosaicMode.resolveForPhase4(storedValue).requestedMode.displayName
                } else {
                    storedValue
                }
                ResolvedLibpatcherValue.StringValue(resolvedValue)
            }
            LibpatcherControlKind.READ_ONLY,
            LibpatcherControlKind.ACTION -> return null
        }

        val uiMapped = when {
            isJpegQuality -> String.format(Locale.US, "jpegQuality=%d", (value as ResolvedLibpatcherValue.FloatValue).value.toInt())
            else -> "value=${value.asDebugString()}"
        }
        val active = when {
            isJpegQuality -> true
            ref.key == DemosaicMode.PROFILE_KEY -> true
            else -> source == "profile override" && !value.isNeutral()
        }
        val stage = when (ref.runtimeScope) {
            LibpatcherRuntimeScope.COMMON_RENDER -> "common post-render / native ISP"
            LibpatcherRuntimeScope.RAW_DEMOSAIC -> "normalized Bayer to scene-linear RGB"
            LibpatcherRuntimeScope.OUTPUT_ENCODE -> "JPEG encode"
            LibpatcherRuntimeScope.NOT_RUNTIME_ISP -> "not applied"
        }
        return ResolvedLibpatcherSetting(
            key = key,
            title = ref.control.title,
            sectionTitle = ref.section.title,
            topicTitle = ref.topic.title,
            groupTitle = ref.group.title,
            kind = ref.control.kind,
            runtimeScope = ref.runtimeScope,
            value = value,
            active = active,
            visible = true,
            source = source,
            actualTechnicalValue = value.asDebugString(),
            mappedRuntimeValue = uiMapped,
            appliedStage = stage
        )
    }

    private fun frameSourceForSettings(frameSourceFormat: Int): String {
        return when (frameSourceFormat) {
            ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
            ImageFormat.RAW10 -> "RAW10"
            ImageFormat.YUV_420_888 -> "YUV"
            else -> "UNKNOWN($frameSourceFormat)"
        }
    }

    private fun normalizeFrameSource(frameSource: String): String {
        val normalized = frameSource.trim().uppercase(Locale.US)
        return when (normalized) {
            "YUV", "YUV_420_888" -> "YUV"
            "RAW10" -> "RAW10"
            "RAW_SENSOR", "RAWSENSOR" -> "RAW_SENSOR"
            else -> normalized.ifBlank { "YUV" }
        }
    }
}
