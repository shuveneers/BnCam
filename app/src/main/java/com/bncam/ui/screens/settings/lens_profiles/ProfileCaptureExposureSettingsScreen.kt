package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.core.capture.MaxFrameExposureChoice
import com.bncam.core.capture.ShotBiasExposureChoice
import com.bncam.data.settings.CaptureSettingKeys
import com.bncam.data.settings.ProfileSettingSpec
import com.bncam.data.settings.ProfileSettingValueType
import com.bncam.data.settings.SettingsRepository
import com.bncam.ui.components.SettingSliderRow
import com.bncam.ui.screens.settings.SettingsCard
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Profile V3 acquisition controls. These affect sensor acquisition and are deliberately separate
 * from ISP Tuning -> Light & Shadow, which is post-capture rendering.
 */
@Composable
fun ProfileCaptureExposureSettingsScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val profileId = "${lensId}_profile_$profileIndex"
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val storedExposureChoice by repo.getProfileString(
        profileId,
        CaptureSettingKeys.SHOT_BIAS_EXPOSURE,
        ShotBiasExposureChoice.AUTO.persistedValue
    ).collectAsStateWithLifecycle(initialValue = ShotBiasExposureChoice.AUTO.persistedValue)
    val exposureChoice = ShotBiasExposureChoice.fromPersisted(storedExposureChoice)

    val storedMaxFrameExposure by repo.getProfileString(
        profileId,
        CaptureSettingKeys.SHOT_BIAS_MAX_FRAME_EXPOSURE,
        MaxFrameExposureChoice.SENSOR_MAX.persistedValue
    ).collectAsStateWithLifecycle(initialValue = MaxFrameExposureChoice.SENSOR_MAX.persistedValue)
    val maxFrameExposure = MaxFrameExposureChoice.fromPersisted(storedMaxFrameExposure)

    val storedCaptureEv by repo.getProfileFloat(
        profileId, CaptureSettingKeys.CAPTURE_EV_BIAS, 0.0f
    ).collectAsStateWithLifecycle(initialValue = 0.0f)
    val captureEvBinding = rememberOptimisticPersistedBinding(
        stableKey = "$profileId:${CaptureSettingKeys.CAPTURE_EV_BIAS}",
        authoritativeValue = storedCaptureEv.coerceIn(-2.0f, 2.0f),
        persist = {
            repo.setProfileFloatOverride(
                profileId,
                CaptureSettingKeys.CAPTURE_EV_BIAS,
                it.coerceIn(-2.0f, 2.0f)
            )
        }
    )

    SettingsTopicScaffold("Shot Bias", onNavigateBack) {
        SettingsCard(
            title = "Exposure",
            description = "Controls how Camera2 distributes physical sensor exposure. Auto keeps normal AE; a fixed ISO or time keeps that variable preferred while the other compensates."
        ) {
            ChoiceSettingRow(
                title = "Exposure",
                description = "Choose Auto, a maximum/fixed ISO, or a maximum/fixed shutter time.",
                value = exposureChoice.persistedValue,
                options = ShotBiasExposureChoice.uiValues,
                onSelected = { selected ->
                    scope.launch {
                        repo.setProfileStringOverride(
                            profileId,
                            CaptureSettingKeys.SHOT_BIAS_EXPOSURE,
                            ShotBiasExposureChoice.fromPersisted(selected).persistedValue
                        )
                    }
                }
            )

            ChoiceSettingRow(
                title = "Max exposure for a frame",
                description = "Hard per-frame shutter ceiling. A requested fixed time is still clamped to this value and to the sensor limit.",
                value = maxFrameExposure.persistedValue,
                options = MaxFrameExposureChoice.uiValues,
                onSelected = { selected ->
                    scope.launch {
                        repo.setProfileStringOverride(
                            profileId,
                            CaptureSettingKeys.SHOT_BIAS_MAX_FRAME_EXPOSURE,
                            MaxFrameExposureChoice.fromPersisted(selected).persistedValue
                        )
                    }
                }
            )
        }

        SettingsCard(
            title = "Capture EV Bias",
            description = "Changes the physical acquisition target. This is separate from post-capture ISP Exposure."
        ) {
            SettingSliderRow(
                title = "Capture EV Bias",
                description = "Biases the physical sensor-exposure target. With Auto exposure this stays on Camera2 AE compensation; fixed Shot Bias choices apply it around a fresh AE baseline.",
                value = captureEvBinding.value,
                valueRange = -2.0f..2.0f,
                onValueChange = captureEvBinding.update,
                valueFormatter = { String.format(Locale.US, "%+.1f EV", it) }
            )
        }

        ResetPageToDefaultValuesButton {
            scope.launch {
                repo.clearProfileOverrideValues(
                    profileId,
                    listOf(
                        ProfileSettingSpec(CaptureSettingKeys.SHOT_BIAS_EXPOSURE, ProfileSettingValueType.STRING),
                        ProfileSettingSpec(CaptureSettingKeys.SHOT_BIAS_MAX_FRAME_EXPOSURE, ProfileSettingValueType.STRING),
                        ProfileSettingSpec(CaptureSettingKeys.CAPTURE_EV_BIAS, ProfileSettingValueType.FLOAT),
                        // Clear superseded V2 values as well so reset cannot revive hidden authority.
                        ProfileSettingSpec(CaptureSettingKeys.EXPOSURE_PRIORITY_MODE, ProfileSettingValueType.STRING),
                        ProfileSettingSpec(CaptureSettingKeys.SHUTTER_PRIORITY_MULTIPLIER, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(CaptureSettingKeys.ISO_PRIORITY_MULTIPLIER, ProfileSettingValueType.FLOAT)
                    )
                )
            }
        }
    }
}
