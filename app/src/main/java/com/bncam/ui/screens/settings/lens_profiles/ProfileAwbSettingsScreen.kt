package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.data.settings.ProfileAwbModes
import com.bncam.data.settings.ProfileAwbSettings
import com.bncam.data.settings.SettingsRepository
import com.bncam.ui.components.SettingSliderRow
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlin.math.roundToInt

@Composable
fun ProfileAwbSettingsScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val profileId = "${lensId}_profile_$profileIndex"
    val authoritative by repo.getProfileAwbSettingsFlow(profileId)
        .collectAsStateWithLifecycle(initialValue = ProfileAwbSettings())
    val binding = rememberOptimisticPersistedBinding("$profileId:awb", authoritative) {
        repo.setProfileAwbSettings(profileId, it)
    }
    val settings = binding.value
    val topMode = if (settings.mode == ProfileAwbModes.SYSTEM_AUTO) "Auto" else "Manual"
    val manualSource = if (settings.mode == ProfileAwbModes.BRAND_REFERENCE) {
        "Brand preset"
    } else {
        "Kelvin"
    }

    SettingsTopicScaffold("Auto White Balance", onNavigateBack) {
        SettingsCard("Mode", "Choose automatic or profile-controlled white balance.") {
            ChoiceSettingRow(
                title = "White balance",
                description = "Auto follows per-frame Camera2 AWB. Manual reveals the profile controls below.",
                value = topMode,
                options = listOf("Auto", "Manual"),
                onSelected = { selected ->
                    binding.update(
                        settings.copy(
                            mode = if (selected == "Auto") {
                                ProfileAwbModes.SYSTEM_AUTO
                            } else if (settings.mode == ProfileAwbModes.SYSTEM_AUTO) {
                                ProfileAwbModes.MANUAL_KELVIN
                            } else {
                                settings.mode
                            }
                        )
                    )
                }
            )
            SettingValueRow(
                title = "Resolved selection",
                description = "Used for developed RAW JPEG output.",
                value = settings.summary(),
                onClick = {}
            )
        }

        SettingsCard("Intensity", "Reduce or strengthen the resolved white-balance correction without changing its target.") {
            SettingSliderRow(
                title = "AWB intensity",
                description = "0% removes the correction, 100% is the normal resolved AWB, and up to 150% strengthens the same correction in bounded log-gain space.",
                value = settings.referenceIntensity,
                valueRange = 0f..1.5f,
                onValueChange = { binding.update(settings.copy(referenceIntensity = it)) },
                valueFormatter = { String.format(java.util.Locale.US, "%.0f%%", it * 100f) }
            )
        }

        if (topMode == "Manual") {
            SettingsCard("Manual source", "Select a reference preset or an exact Kelvin target.") {
                ChoiceSettingRow(
                    title = "Source",
                    description = "Both routes remain profile-specific.",
                    value = manualSource,
                    options = listOf("Brand preset", "Kelvin"),
                    onSelected = { selected ->
                        binding.update(
                            settings.copy(
                                mode = if (selected == "Brand preset") {
                                    ProfileAwbModes.BRAND_REFERENCE
                                } else {
                                    ProfileAwbModes.MANUAL_KELVIN
                                }
                            )
                        )
                    }
                )
            }

            if (manualSource == "Brand preset") {
                SettingsCard("Reference preset", "Brand labels select reference Kelvin targets.") {
                    ChoiceSettingRow(
                        "Brand",
                        "Reference label.",
                        settings.brand,
                        ProfileAwbSettings.BRANDS
                    ) {
                        binding.update(settings.copy(brand = it, kelvin = referenceKelvin(it, settings.preset)))
                    }
                    ChoiceSettingRow(
                        "Preset",
                        "Reference illuminant.",
                        settings.preset,
                        ProfileAwbSettings.PRESETS
                    ) {
                        binding.update(settings.copy(preset = it, kelvin = referenceKelvin(settings.brand, it)))
                    }
                }
            } else {
                SettingsCard("Manual Kelvin", "Exact target from 2000 K through 10000 K.") {
                    PersistedDecimalField(
                        stableKey = "$profileId:awb_kelvin",
                        label = "Kelvin",
                        authoritativeValue = settings.kelvin.toDouble(),
                        minimum = 2000.0,
                        maximum = 10000.0,
                        onValidValue = { binding.update(settings.copy(kelvin = it.roundToInt())) }
                    )
                    SettingSliderRow(
                        title = "Kelvin",
                        description = "Reference colour temperature.",
                        value = settings.kelvin.toFloat(),
                        valueRange = 2000f..10000f,
                        onValueChange = { binding.update(settings.copy(kelvin = it.roundToInt())) },
                        valueFormatter = { "${it.roundToInt()} K" }
                    )
                    ChoiceSettingRow(
                        "Illuminant model",
                        "Planckian or CIE daylight locus.",
                        settings.illuminantModel,
                        ProfileAwbSettings.MODELS
                    ) {
                        binding.update(settings.copy(illuminantModel = it))
                    }
                }
            }

            SettingsCard("Tint", "Green-to-magenta profile correction.") {
                PersistedDecimalField(
                    stableKey = "$profileId:awb_tint",
                    label = "Tint",
                    authoritativeValue = settings.tint.toDouble(),
                    minimum = -1.0,
                    maximum = 1.0,
                    onValidValue = { binding.update(settings.copy(tint = it.toFloat())) }
                )
                SettingSliderRow(
                    title = "Tint",
                    description = "Exact profile tint.",
                    value = settings.tint,
                    valueRange = -1f..1f,
                    onValueChange = { binding.update(settings.copy(tint = it)) },
                    valueFormatter = { String.format(java.util.Locale.US, "%+.3f", it) }
                )
            }
        }
        ResetPageToDefaultValuesButton {
            binding.update(ProfileAwbSettings())
        }
    }
}

private fun referenceKelvin(brand: String, preset: String): Int {
    val base = when (preset) {
        "Cloudy" -> 6000
        "Shade" -> 7500
        "Tungsten / Incandescent" -> 3200
        "Fluorescent" -> 4000
        "Flash" -> 6000
        else -> 5200
    }
    val brandAdjustment = when (brand) {
        "Leica" -> if (preset == "Daylight") 300 else 0
        "Fujifilm" -> if (preset == "Shade") 500 else 0
        "Nikon" -> if (preset == "Shade") 500 else 0
        else -> 0
    }
    return (base + brandAdjustment).coerceIn(2000, 10000)
}
