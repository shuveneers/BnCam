package com.bncam.ui.screens.settings.lens_profiles

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.core.quality.AwbCalibrationImportParser
import com.bncam.data.settings.BnCamAwbPreset
import com.bncam.data.settings.BnCamAwbPresetCatalog
import com.bncam.data.settings.BnCamAwbPresetGroup
import com.bncam.data.settings.LensAwbCalibrationModes
import com.bncam.data.settings.LensAwbCalibrationSettings
import com.bncam.data.settings.LensAwbCalibrationSettingsStore
import com.bncam.data.settings.LensAwbGreenSplitModes
import com.bncam.ui.components.AccentPistachio
import com.bncam.ui.components.SettingSliderRow
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun AwbCalibrationSettingsScreen(
    lensId: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { LensAwbCalibrationSettingsStore(context) }

    LaunchedEffect(lensId, store) { store.ensureInitialized(lensId) }

    val authoritative by store.settingsFlow(lensId).collectAsStateWithLifecycle(
        initialValue = LensAwbCalibrationSettings()
    )
    val binding = rememberOptimisticPersistedBinding(
        stableKey = "$lensId:awb_calibration_v2",
        authoritativeValue = authoritative,
        persist = { store.set(lensId, it) }
    )
    val settings = binding.value.sanitized()
    var showPresetPicker by remember(lensId) { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        }
                        ?.takeIf { it.isNotBlank() }
                        ?: "Imported AWB"
                    val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalArgumentException("Could not read selected AWB file.")
                    val parsed = AwbCalibrationImportParser.parse(text, displayName)
                    if (parsed.points.size < 2) {
                        throw IllegalArgumentException(parsed.warnings.joinToString().ifBlank { "No valid RG/BG calibration points." })
                    }
                    settings.copy(
                        mode = LensAwbCalibrationModes.CUSTOM_IMPORT,
                        importedName = displayName.substringBeforeLast('.').take(120),
                        importedFormat = parsed.format,
                        customPoints = parsed.points,
                        importedGrGbRatio = parsed.grGbRatio
                    ).sanitized()
                }
            }.onSuccess { imported ->
                binding.update(imported)
                Toast.makeText(context, "Imported ${imported.importedName}", Toast.LENGTH_SHORT).show()
            }.onFailure { failure ->
                Toast.makeText(
                    context,
                    "AWB import failed: ${failure.message ?: failure.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    SettingsTopicScaffold("AWB Calibration", onNavigateBack) {
        SettingsCard(
            title = "AWB authority",
            description = "Per-lens white-balance response. Auto is the default and remains scene-adaptive. Presets are deliberate developed-JPEG responses, not color-matrix looks."
        ) {
            ChoiceSettingRow(
                title = "Calibration source",
                description = "Auto follows the active lens and scene. BnCam Preset changes the illuminant response. Custom Import uses a user-supplied RG/BG calibration.",
                value = settings.mode,
                options = listOf(
                    LensAwbCalibrationModes.AUTO,
                    LensAwbCalibrationModes.BNCAM_PRESET,
                    LensAwbCalibrationModes.CUSTOM_IMPORT
                ),
                onSelected = { binding.update(settings.copy(mode = it)) }
            )

            SettingSliderRow(
                title = "R/G calibration trim",
                description = "Advanced sensor-neutral trim. 1.000 leaves the selected response unchanged.",
                value = settings.rgCoefficient,
                valueRange = 0.25f..5.00f,
                valueFormatter = { String.format(Locale.US, "%.3f", it) },
                onValueChange = { binding.update(settings.copy(rgCoefficient = it)) }
            )
            SettingSliderRow(
                title = "B/G calibration trim",
                description = "Advanced sensor-neutral trim. 1.000 leaves the selected response unchanged.",
                value = settings.bgCoefficient,
                valueRange = 0.25f..5.00f,
                valueFormatter = { String.format(Locale.US, "%.3f", it) },
                onValueChange = { binding.update(settings.copy(bgCoefficient = it)) }
            )
        }

        if (settings.mode == LensAwbCalibrationModes.BNCAM_PRESET) {
            val selected = BnCamAwbPresetCatalog.byId(settings.presetId) ?: BnCamAwbPresetCatalog.all.first()
            SettingsCard(
                title = "BnCam AWB preset",
                description = "Fifteen lighting-response presets grouped by the kind of illumination they are designed to handle."
            ) {
                SettingValueRow(
                    title = "AWB preset",
                    description = selected.description,
                    value = selected.name,
                    onClick = { showPresetPicker = true }
                )
            }
        }

        if (settings.mode == LensAwbCalibrationModes.CUSTOM_IMPORT) {
            SettingsCard(
                title = "Custom calibration",
                description = "Import a text calibration containing paired RG/BG points and, optionally, a Gr/Gb value."
            ) {
                SettingValueRow(
                    title = "Imported calibration",
                    description = "At least two paired RG/BG points are required.",
                    value = if (settings.customPoints.size >= 2) {
                        "${settings.importedName.ifBlank { "Imported" }} · ${settings.customPoints.size} pts"
                    } else "Not configured",
                    onClick = { importLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }
                )
                Button(
                    onClick = { importLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C))
                ) {
                    Text("Import AWB calibration", color = AccentPistachio, fontWeight = FontWeight.Medium)
                }
            }
        }

        SettingsCard(
            title = "G1/G2 sensor calibration",
            description = "Technical Bayer green-site calibration. This is not a green/magenta creative tint control."
        ) {
            ChoiceSettingRow(
                title = "G1/G2 authority",
                description = "Auto preserves exact-frame green balance. Manual applies the requested sensor-site ratio before demosaic.",
                value = settings.greenSplitMode,
                options = listOf(LensAwbGreenSplitModes.AUTO, LensAwbGreenSplitModes.MANUAL),
                onSelected = { binding.update(settings.copy(greenSplitMode = it)) }
            )
            if (settings.greenSplitMode == LensAwbGreenSplitModes.MANUAL) {
                SettingSliderRow(
                    title = "Gr / Gb ratio",
                    description = "Sensor calibration ratio. 1.0000 is neutral; CFA mapping happens at runtime.",
                    value = settings.manualGrGbRatio,
                    valueRange = 0.80f..1.20f,
                    valueFormatter = { String.format(Locale.US, "%.4f", it) },
                    onValueChange = { binding.update(settings.copy(manualGrGbRatio = it)) }
                )
            } else {
                SettingValueRow(
                    title = "Green-site source",
                    description = "Auto uses exact-frame Camera2 green gains unless an imported calibration provides a valid ratio.",
                    value = settings.importedGrGbRatio?.let { String.format(Locale.US, "%.4f", it) } ?: "Frame metadata",
                    onClick = {}
                )
            }
        }

        SettingsCard(
            title = "Authority status",
            description = "The fingerprint identifies the exact per-lens AWB state used by the runtime."
        ) {
            SettingValueRow("Calibration", "Current per-lens AWB state.", settings.summary(), onClick = {})
            SettingValueRow("Fingerprint", "Short hash of the persisted calibration values.", settings.fingerprint(), onClick = {})
        }

        Button(
            onClick = { binding.update(LensAwbCalibrationSettings()) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B1A1A))
        ) {
            Text("Reset AWB calibration", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }

    if (showPresetPicker) {
        AwbPresetPickerDialog(
            selectedPresetId = settings.presetId,
            onDismiss = { showPresetPicker = false },
            onSelected = { preset ->
                binding.update(settings.copy(presetId = preset.id))
                showPresetPicker = false
            }
        )
    }
}

@Composable
private fun AwbPresetPickerDialog(
    selectedPresetId: Int,
    onDismiss: () -> Unit,
    onSelected: (BnCamAwbPreset) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("AWB preset", color = Color.White) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                BnCamAwbPresetGroup.entries.forEach { group ->
                    AwbPresetSectionHeader(group.sectionTitle)
                    BnCamAwbPresetCatalog.inGroup(group).forEach { preset ->
                        AwbPresetPickerRow(preset, preset.id == selectedPresetId, onSelected)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        }
    )
}

@Composable
private fun AwbPresetSectionHeader(title: String) {
    Text(
        text = title,
        color = AccentPistachio,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun AwbPresetPickerRow(
    preset: BnCamAwbPreset,
    selected: Boolean,
    onSelected: (BnCamAwbPreset) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(preset) }
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(preset.name, color = Color.White, fontSize = 14.sp)
            Text(preset.description, color = Color.Gray, fontSize = 11.sp)
        }
        if (selected) Text("Selected", color = AccentPistachio, fontSize = 12.sp)
    }
}
