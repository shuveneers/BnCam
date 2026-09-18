package com.bncam.ui.screens.settings.lens_profiles

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.core.quality.GcamAwbCalibrationParser
import com.bncam.data.settings.AgcAwbPresetCatalog
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
        stableKey = "$lensId:awb_calibration_v1",
        authoritativeValue = authoritative,
        persist = { store.set(lensId, it) }
    )
    val settings = binding.value.sanitized()

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
                    val parsed = GcamAwbCalibrationParser.parse(text, displayName)
                    if (parsed.points.size < 2) {
                        throw IllegalArgumentException(parsed.warnings.joinToString().ifBlank { "No valid RG/BG calibration points." })
                    }
                    settings.copy(
                        mode = LensAwbCalibrationModes.CUSTOM_GCAM,
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
            title = "GCam AWB authority",
            description = "Per-lens sensor white-balance calibration. This is hardware calibration, not a profile look."
        ) {
            ChoiceSettingRow(
                title = "Calibration source",
                description = "Sensor Auto derives a sensor-specific locus from Camera2 calibration. AGC Preset uses an original AGC V12 built-in sensor calibration. Custom GCam imports .gawb/.txt.",
                value = settings.mode,
                options = listOf(
                    LensAwbCalibrationModes.SENSOR_AUTO,
                    LensAwbCalibrationModes.AGC_PRESET,
                    LensAwbCalibrationModes.CUSTOM_GCAM
                ),
                onSelected = { binding.update(settings.copy(mode = it)) }
            )

            SettingSliderRow(
                title = "RG coefficient",
                description = "Multiplicative calibration trim for the sensor RG locus.",
                value = settings.rgCoefficient,
                valueRange = 0.25f..5.00f,
                valueFormatter = { String.format(Locale.US, "%.3f", it) },
                onValueChange = { binding.update(settings.copy(rgCoefficient = it)) }
            )
            SettingSliderRow(
                title = "BG coefficient",
                description = "Multiplicative calibration trim for the sensor BG locus.",
                value = settings.bgCoefficient,
                valueRange = 0.25f..5.00f,
                valueFormatter = { String.format(Locale.US, "%.3f", it) },
                onValueChange = { binding.update(settings.copy(bgCoefficient = it)) }
            )
        }

        if (settings.mode == LensAwbCalibrationModes.AGC_PRESET) {
            val selectedPreset = AgcAwbPresetCatalog.byId(settings.agcPresetId) ?: AgcAwbPresetCatalog.all.first()
            SettingsCard(
                title = "AGC V12 built-in preset",
                description = "Original AGC 8.8.224 V12 PreComputedAWB sensor tables. Presets contain their native RG/BG calibration locus and, where AGC supplies it, GR/GB calibration."
            ) {
                ChoiceSettingRow(
                    title = "AWB preset",
                    description = "Select one of the 56 presets exposed by AGC V12.",
                    value = selectedPreset.selectionLabel,
                    options = AgcAwbPresetCatalog.all.map { it.selectionLabel },
                    onSelected = { label ->
                        AgcAwbPresetCatalog.bySelectionLabel(label)?.let { preset ->
                            binding.update(settings.copy(agcPresetId = preset.id))
                        }
                    }
                )
                SettingValueRow(
                    title = "Calibration points",
                    description = "Native RG/BG points in this AGC preset.",
                    value = selectedPreset.points.size.toString(),
                    onClick = {}
                )
                SettingValueRow(
                    title = "Preset Gr/Gb",
                    description = "Native AGC GR/GB value when the preset contains one.",
                    value = selectedPreset.grGbRatio?.let { String.format(Locale.US, "%.6f", it) } ?: "Not supplied",
                    onClick = {}
                )
            }
        }

        if (settings.mode == LensAwbCalibrationModes.CUSTOM_GCAM) {
            SettingsCard(
                title = "Custom GCam calibration",
                description = "Import `.gawb` or `.txt`. BnCam reads the AGC/GCam RG/BG locus and optional BGRG/GRGB calibration."
            ) {
                SettingValueRow(
                    title = "Imported calibration",
                    description = "The import must contain at least two paired RG/BG points.",
                    value = if (settings.customPoints.size >= 2) {
                        "${settings.importedName.ifBlank { "Imported" }} · ${settings.customPoints.size} pts"
                    } else {
                        "Not configured"
                    },
                    onClick = { importLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) }
                )
                Button(
                    onClick = { importLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C))
                ) {
                    Text("Import .gawb / .txt", color = AccentPistachio, fontWeight = FontWeight.Medium)
                }
                if (settings.customPoints.size < 2) {
                    Text(
                        "Custom GCam remains inactive until a valid calibration is imported.",
                        color = Color(0xFFFFC38A),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }
            }
        }

        SettingsCard(
            title = "Green split calibration",
            description = "Controls GCam semantic Gr/Gb sensor calibration. BnCam maps it to Camera2 G_even/G_odd for the active CFA. Auto uses the selected AGC/imported GR/GB when supplied; otherwise exact-frame Camera2 green gains remain authoritative."
        ) {
            ChoiceSettingRow(
                title = "GR/GB authority",
                description = "Manual is a calibration ratio, not a creative tint control.",
                value = settings.greenSplitMode,
                options = listOf(LensAwbGreenSplitModes.AUTO, LensAwbGreenSplitModes.MANUAL),
                onSelected = { binding.update(settings.copy(greenSplitMode = it)) }
            )
            if (settings.greenSplitMode == LensAwbGreenSplitModes.MANUAL) {
                SettingSliderRow(
                    title = "Gr / Gb ratio",
                    description = "Semantic GCam Gr/Gb calibration ratio. 1.0000 is neutral; CFA mapping happens at runtime.",
                    value = settings.manualGrGbRatio,
                    valueRange = 0.80f..1.20f,
                    valueFormatter = { String.format(Locale.US, "%.4f", it) },
                    onValueChange = { binding.update(settings.copy(manualGrGbRatio = it)) }
                )
            } else {
                SettingValueRow(
                    title = "Imported Gr/Gb",
                    description = "AGC/GCam BGRG/GRGB calibration value when present.",
                    value = when (settings.mode) {
                        LensAwbCalibrationModes.AGC_PRESET ->
                            AgcAwbPresetCatalog.byId(settings.agcPresetId)?.grGbRatio
                                ?.let { String.format(Locale.US, "%.4f", it) } ?: "Frame metadata"
                        LensAwbCalibrationModes.CUSTOM_GCAM ->
                            settings.importedGrGbRatio?.let { String.format(Locale.US, "%.4f", it) } ?: "Frame metadata"
                        else -> "Frame metadata"
                    },
                    onClick = {}
                )
            }
        }

        SettingsCard(
            title = "Authority status",
            description = "The fingerprint identifies the exact per-lens calibration state used by the runtime."
        ) {
            SettingValueRow(
                title = "Calibration",
                description = "Current per-lens AWB calibration state.",
                value = settings.summary(),
                onClick = {}
            )
            SettingValueRow(
                title = "Fingerprint",
                description = "Short hash of the persisted calibration values.",
                value = settings.fingerprint(),
                onClick = {}
            )
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
}
