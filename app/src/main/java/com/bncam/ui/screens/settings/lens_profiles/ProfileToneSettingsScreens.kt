package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.bncam.data.settings.ProfileIspKeys
import com.bncam.data.settings.ProfilePlannedDefaults
import com.bncam.data.settings.ProfileSettingSpec
import com.bncam.data.settings.ProfileSettingValueType
import com.bncam.data.settings.SettingsRepository
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Profile V3 tonal owner. All direct user-facing tonal range controls live here.
 * Planned controls are intentionally visible and persisted with a clear "(not connected)"
 * label, but are excluded from RenderQualityConfig until their dedicated Vulkan contracts exist.
 */
@Composable
fun ProfileLightShadowSettingsScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val profileId = "${lensId}_profile_$profileIndex"
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val percentFormatter: (Float) -> String = { value -> "%+d".format((value * 100f).roundToInt()) }

    SettingsTopicScaffold("Light & Shadow", onNavigateBack) {
        SettingsCard(
            title = "Light & Shadow",
            description = "One tonal workspace for render exposure, global contrast and endpoint/range placement. These controls do not change Camera2 shutter or ISO."
        ) {
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.TONE_EXPOSURE,
                title = "Exposure",
                description = "Adjusts scene-linear render exposure without changing physical sensor exposure.",
                formatter = { value -> String.format(Locale.US, "%+.1f EV", value * 2f) }
            )
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.TONE_CONTRAST,
                title = "Contrast",
                description = "Biases global tonal separation around BnCam's automatic scene baseline.",
                formatter = percentFormatter
            )
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.TONE_GAMMA_CONTRAST,
                title = "Gamma Contrast (not connected)",
                description = "Planned reciprocal-gamma contrast control (factor 1/x). Stored in the profile but not yet applied by the renderer.",
                defaultValue = ProfilePlannedDefaults.GAMMA_CONTRAST,
                formatter = percentFormatter
            )
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.TONE_DEHAZE,
                title = "Dehaze (not connected)",
                description = "Planned haze/veil removal control. Stored in the profile but not yet applied by the renderer.",
                defaultValue = ProfilePlannedDefaults.DEHAZE,
                formatter = percentFormatter
            )
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.TONE_CLARITY,
                title = "Clarity (not connected)",
                description = "Planned noise-aware mid-frequency local contrast control. Stored in the profile but not yet applied by the renderer.",
                defaultValue = ProfilePlannedDefaults.CLARITY,
                formatter = percentFormatter
            )
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.TONE_SHADOWS,
                title = "Shadow Lift",
                description = "Darkens or lifts shadow-range luminance without moving the endpoints.",
                formatter = percentFormatter
            )
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.TONE_HIGHLIGHTS,
                title = "Highlight Reduction",
                description = "Reduces or opens the bright tonal range while preserving the highlight roll-off.",
                formatter = percentFormatter
            )
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.TONE_WHITES,
                title = "Whites",
                description = "Moves upper tonal placement toward or away from the final display-white endpoint.",
                formatter = percentFormatter
            )
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.TONE_BLACKS,
                title = "Blacks",
                description = "Moves deep-black placement while preserving a bounded black endpoint.",
                formatter = percentFormatter
            )
        }
        ResetPageToDefaultValuesButton {
            scope.launch {
                repo.clearProfileOverrideValues(
                    profileId,
                    listOf(
                        ProfileSettingSpec(ProfileIspKeys.TONE_EXPOSURE, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.TONE_CONTRAST, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.TONE_GAMMA_CONTRAST, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.TONE_DEHAZE, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.TONE_CLARITY, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.TONE_HIGHLIGHTS, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.TONE_SHADOWS, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.TONE_WHITES, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.TONE_BLACKS, ProfileSettingValueType.FLOAT)
                    )
                )
            }
        }
    }
}

// Legacy deep-link compatibility. Old routes remain valid but land on the single V3 owner.
@Composable
fun ProfileExposureSettingsScreen(lensId: String, profileIndex: Int, onNavigateBack: () -> Unit) =
    ProfileLightShadowSettingsScreen(lensId, profileIndex, onNavigateBack)

@Composable
fun ProfileTonalRangeSettingsScreen(lensId: String, profileIndex: Int, onNavigateBack: () -> Unit) =
    ProfileLightShadowSettingsScreen(lensId, profileIndex, onNavigateBack)

@Composable
fun ProfileContrastLocalToneSettingsScreen(lensId: String, profileIndex: Int, onNavigateBack: () -> Unit) =
    ProfileLightShadowSettingsScreen(lensId, profileIndex, onNavigateBack)
