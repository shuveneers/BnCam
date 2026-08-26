package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.data.settings.SettingsRepository
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.launch

@Composable
fun ColorMatrixSettingsScreen(lensId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val mode by repo.getColorMatrixModeFlow(lensId).collectAsStateWithLifecycle(initialValue = "System")
    val encoded by repo.getColorMatrixManualFlow(lensId).collectAsStateWithLifecycle(initialValue = "")
    val values = remember(encoded) { parseMatrix(encoded) }

    SettingsTopicScaffold("Color Matrix", onNavigateBack) {
        SettingsCard(
            title = "Color transform",
            description = "System uses the sensor-specific Camera2 calibration. Manual overrides only this Lens ID."
        ) {
            ChoiceSettingRow(
                title = "Color transform profile",
                description = "Choose sensor calibration or a manual 3 × 3 matrix.",
                value = if (mode.equals("Manual", ignoreCase = true)) "Manual" else "System",
                options = listOf("System", "Manual")
            ) { selected ->
                scope.launch { repo.setColorMatrixMode(lensId, selected) }
            }
            SettingValueRow(
                title = "Reset to System",
                description = "Return this lens to the calibrated Camera2 color transform.",
                value = "Reset",
                onClick = { scope.launch { repo.setColorMatrixMode(lensId, "System") } }
            )
        }

        if (mode.equals("Manual", ignoreCase = true)) {
            SettingsCard(
                title = "Manual 3 × 3 matrix",
                description = "Rows are output R, G and B; columns are sensor R, G and B. Values are applied only to developed RAW output."
            ) {
                val labels = listOf("RR", "RG", "RB", "GR", "GG", "GB", "BR", "BG", "BB")
                repeat(3) { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        repeat(3) { column ->
                            val index = row * 3 + column
                            PersistedDecimalField(
                                stableKey = "$lensId:color_matrix:$index",
                                label = labels[index],
                                authoritativeValue = values[index],
                                minimum = -8.0,
                                maximum = 8.0,
                                onValidValue = { next ->
                                    val updated = values.toMutableList().also { it[index] = next }
                                    scope.launch { repo.setColorMatrixManual(lensId, updated.map { it.toString() }) }
                                },
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun parseMatrix(raw: String): List<Double> {
    val identity = listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    val parsed = raw.split(',', ';', '|', ' ', '\n', '\t')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { it.toDoubleOrNull() }
    return if (parsed.size >= 9) parsed.take(9) else identity
}
