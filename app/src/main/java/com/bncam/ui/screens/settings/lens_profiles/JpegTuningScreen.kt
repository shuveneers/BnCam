package com.bncam.ui.screens.settings.lens_profiles

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard

@Composable
fun JpegTuningScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    SettingsTopicScaffold("JPEG Tuning", onNavigateBack) {
        SettingsCard(
            title = "JPEG Tuning",
            description = "This processing stage is reserved for future profile-specific JPEG controls."
        ) {
            SettingValueRow(
                title = "JPEG tuning",
                description = "The production JPEG renderer currently uses its validated defaults.",
                value = "Coming later",
                onClick = {}
            )
        }
        ResetPageToDefaultValuesButton {
            Toast.makeText(context, "JPEG tuning already uses default values", Toast.LENGTH_SHORT).show()
        }
    }
}
