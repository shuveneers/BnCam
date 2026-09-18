package com.bncam.ui.screens.settings.lens_profiles

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.core.quality.NoiseModelResolver
import com.bncam.core.quality.NoiseModelSource
import com.bncam.data.settings.LensPhysicalNoiseModelSettings
import com.bncam.data.settings.NoiseModelPreset
import com.bncam.data.settings.NoiseModelPresetCatalog
import com.bncam.data.settings.NoiseModelPresetImportParser
import com.bncam.data.settings.NoiseModelPresetGroup
import com.bncam.data.settings.NoiseModelPresetOrigin
import com.bncam.data.settings.PersistedParametricNoiseModel
import com.bncam.data.settings.PhysicalNoiseModelSettingsStore
import com.bncam.data.settings.PhysicalNoiseModelPresetStore
import com.bncam.data.settings.PhysicalNoiseModelUiPolicy
import com.bncam.data.settings.sanitizePhysicalDynamicIsoCoefficient
import com.bncam.ui.components.AccentPistachio
import com.bncam.ui.components.SettingSliderRow
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val PHYSICAL_NOISE_CHANNEL_LABELS = listOf("R", "Gr", "Gb", "B")

/**
 * Lens-ID editor for the new physical noise-model architecture (Phase 4 preset catalog/import).
 *
 * This screen intentionally writes only [PhysicalNoiseModelSettingsStore]. The production
 * capture/noise authority is switched in a later integration phase so UI work cannot silently
 * change image processing while the refactor is incomplete.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun NoiseModelSettingsScreen(
    lensId: String,
    onNavigateBack: () -> Unit,
    onNavigateToManualNoiseModel: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { PhysicalNoiseModelSettingsStore(context) }

    LaunchedEffect(lensId, store) {
        store.ensureMigrated(lensId)
    }

    val authoritative by store.settingsFlow(lensId).collectAsStateWithLifecycle(
        initialValue = LensPhysicalNoiseModelSettings()
    )
    val binding = rememberOptimisticPersistedBinding(
        stableKey = "$lensId:physical_noise_model_v2",
        authoritativeValue = authoritative,
        persist = { store.set(lensId, it) }
    )
    val settings = binding.value

    SettingsTopicScaffold("Sensor Noise Model", onNavigateBack) {
        SettingsCard(
            title = "Physical noise model",
            description = "Per-lens physical noise evidence. Denoise processing strength remains a separate profile control."
        ) {
            ChoiceSettingRow(
                title = "Noise model source",
                description = "OEM uses Camera2 S/O directly. System, Manual and Preset use a canonical R/Gr/Gb/B A/B/C/D model.",
                value = PhysicalNoiseModelUiPolicy.sourceLabel(settings.source),
                options = PhysicalNoiseModelUiPolicy.selectableSources.map(PhysicalNoiseModelUiPolicy::sourceLabel),
                onSelected = { label ->
                    binding.update(
                        settings.copy(source = PhysicalNoiseModelUiPolicy.sourceFromLabel(label))
                    )
                }
            )
        }

        when (settings.source) {
            NoiseModelSource.OEM -> Unit
            NoiseModelSource.SYSTEM -> DynamicIsoCard(
                settings = settings,
                onSettingsChanged = { next -> binding.update(next) }
            )
            NoiseModelSource.MANUAL -> {
                ManualNoiseModelCard(
                    lensId = lensId,
                    settings = settings,
                    onSave = { model ->
                        binding.update(
                            settings.copy(
                                source = NoiseModelSource.MANUAL,
                                manualModel = model
                            )
                        )
                    }
                )
                DynamicIsoCard(
                    settings = settings,
                    onSettingsChanged = { next -> binding.update(next) }
                )
            }
            NoiseModelSource.PRESET -> {
                PresetNoiseModelCard(
                    lensId = lensId,
                    settings = settings,
                    onPresetSelected = { presetId ->
                        binding.update(
                            settings.copy(
                                source = NoiseModelSource.PRESET,
                                selectedPresetId = presetId
                            )
                        )
                    }
                )
                DynamicIsoCard(
                    settings = settings,
                    onSettingsChanged = { next -> binding.update(next) }
                )
            }
        }

        NoiseModelResetButton {
            binding.update(PhysicalNoiseModelUiPolicy.resetUserControls(settings))
        }
    }
}


@Composable
private fun ManualNoiseModelCard(
    lensId: String,
    settings: LensPhysicalNoiseModelSettings,
    onSave: (PersistedParametricNoiseModel) -> Unit
) {
    SettingsCard(
        title = "Manual model",
        description = "Enter signed A/B/C/D coefficients in canonical R/Gr/Gb/B order. Only final resolved S/O is clamped to zero."
    ) {
        var draft by remember(lensId, settings.manualModel) {
            mutableStateOf(ManualNoiseModelDraft.from(settings.manualModel))
        }

        SettingValueRow(
            title = "Manual model status",
            description = "All 16 coefficients plus ISO step must be valid before the model is persisted.",
            value = if (settings.manualModel == null) "Incomplete" else "Configured",
            onClick = {}
        )

        ManualCoefficientGroup(
            label = "A",
            description = "S slope term",
            values = draft.a,
            onValueChange = { channel, value -> draft = draft.copy(a = draft.a.replaced(channel, value)) }
        )
        ManualCoefficientGroup(
            label = "B",
            description = "S intercept term",
            values = draft.b,
            onValueChange = { channel, value -> draft = draft.copy(b = draft.b.replaced(channel, value)) }
        )
        ManualCoefficientGroup(
            label = "C",
            description = "ISO² offset term",
            values = draft.c,
            onValueChange = { channel, value -> draft = draft.copy(c = draft.c.replaced(channel, value)) }
        )
        ManualCoefficientGroup(
            label = "D",
            description = "Digital-gain² offset term",
            values = draft.d,
            onValueChange = { channel, value -> draft = draft.copy(d = draft.d.replaced(channel, value)) }
        )

        ManualDraftField(
            label = "ISO step",
            value = draft.isoStep,
            requirePositive = true,
            onValueChange = { draft = draft.copy(isoStep = it) }
        )

        val parsedModel = draft.toModelOrNull()
        Button(
            onClick = { parsedModel?.let(onSave) },
            enabled = parsedModel != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2C2C2C),
                disabledContainerColor = Color(0xFF1F1F1F)
            )
        ) {
            Text(
                text = if (settings.manualModel == null) "Save manual model" else "Save manual model changes",
                color = if (parsedModel != null) AccentPistachio else Color.Gray,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PresetNoiseModelCard(
    lensId: String,
    settings: LensPhysicalNoiseModelSettings,
    onPresetSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val presetStore = remember(context) { PhysicalNoiseModelPresetStore(context) }
    val userPresets by presetStore.userPresetsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val allPresets = remember(userPresets) { NoiseModelPresetCatalog.merged(userPresets) }
    val selectedPreset = remember(settings.selectedPresetId, userPresets) {
        NoiseModelPresetCatalog.resolve(settings.selectedPresetId, userPresets)
    }
    var showPresetPicker by remember(lensId) { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val imported = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                        }
                        ?.substringBeforeLast('.')
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?: "Imported noise model"
                    val text = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalArgumentException("Could not read selected file.")
                    NoiseModelPresetImportParser.parse(text = text, displayName = name)
                }
                presetStore.importPreset(imported)
                onPresetSelected(imported.id)
                imported
            }.onSuccess { imported ->
                Toast.makeText(context, "Imported ${imported.displayName}", Toast.LENGTH_SHORT).show()
            }.onFailure { failure ->
                Toast.makeText(
                    context,
                    "Noise model import failed: ${failure.message ?: failure.javaClass.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    SettingsCard(
        title = "Preset model",
        description = "User imports are listed first, followed by 20 BnCam sensor-class presets grouped by lens type."
    ) {
        SettingValueRow(
            title = "Preset",
            description = "Choose the physical A/B/C/D model used by the Preset source.",
            value = selectedPreset?.displayName
                ?: settings.selectedPresetId?.let { "Missing preset" }
                ?: "None selected",
            onClick = { showPresetPicker = true }
        )

        Button(
            onClick = { importLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C2C2C))
        ) {
            Text("Import noise model", color = AccentPistachio, fontWeight = FontWeight.Medium)
        }

        if (selectedPreset != null) {
            PresetModelBlock(selectedPreset)
        } else {
            Text(
                text = "Select a preset to make the Preset source configuration-complete.",
                color = Color(0xFFFFC38A),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }

    if (showPresetPicker) {
        PresetPickerDialog(
            presets = allPresets,
            selectedPresetId = settings.selectedPresetId,
            onDismiss = { showPresetPicker = false },
            onSelected = { preset ->
                onPresetSelected(preset.id)
                showPresetPicker = false
            }
        )
    }
}

@Composable
private fun PresetPickerDialog(
    presets: List<NoiseModelPreset>,
    selectedPresetId: String?,
    onDismiss: () -> Unit,
    onSelected: (NoiseModelPreset) -> Unit
) {
    val userPresets = presets.filter { it.origin == NoiseModelPresetOrigin.USER }
    val builtinPresets = presets.filter { it.origin == NoiseModelPresetOrigin.BNCAM }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Noise model preset", color = Color.White) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (userPresets.isNotEmpty()) {
                    PresetSectionHeader("Imported presets")
                    userPresets.forEach { preset ->
                        PresetPickerRow(preset, preset.id == selectedPresetId, onSelected)
                    }
                }
                NoiseModelPresetGroup.entries.forEach { group ->
                    PresetSectionHeader(group.sectionTitle)
                    builtinPresets.filter { it.group == group }.forEach { preset ->
                        PresetPickerRow(preset, preset.id == selectedPresetId, onSelected)
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
private fun PresetSectionHeader(title: String) {
    Text(
        text = title,
        color = AccentPistachio,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun PresetPickerRow(
    preset: NoiseModelPreset,
    selected: Boolean,
    onSelected: (NoiseModelPreset) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(preset) }
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(preset.displayName, color = Color.White, fontSize = 14.sp)
            Text(
                text = if (preset.origin == NoiseModelPresetOrigin.USER) "Imported" else preset.description,
                color = Color.Gray,
                fontSize = 11.sp
            )
        }
        if (selected) {
            Text("Selected", color = AccentPistachio, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PresetModelBlock(preset: NoiseModelPreset) {
    ReadOnlyValueBlock(
        title = "Preset A/B/C/D",
        subtitle = "Canonical R/Gr/Gb/B · ${if (preset.origin == NoiseModelPresetOrigin.USER) "Imported" else "BnCam built-in"}",
        value = buildString {
            appendCoefficientLine("A", preset.model.a)
            appendCoefficientLine("B", preset.model.b)
            appendCoefficientLine("C", preset.model.c)
            appendCoefficientLine("D", preset.model.d)
            append("ISO step = ").append(formatExact(preset.model.isoStep))
        }
    )
}

@Composable
private fun DynamicIsoCard(
    settings: LensPhysicalNoiseModelSettings,
    onSettingsChanged: (LensPhysicalNoiseModelSettings) -> Unit
) {
    SettingsCard(
        title = "Dynamic ISO",
        description = "Optional physical-model ISO transform for System, Manual and Preset only."
    ) {
        ChoiceSettingRow(
            title = "Dynamic ISO",
            description = "Disabled evaluates the model at capture ISO. Enabled applies BnCam Dynamic ISO: ISO_NM = trunc(50 + k × (ISO_capture - 50)) before A/B/C/D.",
            value = if (settings.dynamicIsoEnabled) "Enabled" else "Disabled",
            options = listOf("Disabled", "Enabled"),
            onSelected = { selected ->
                onSettingsChanged(settings.copy(dynamicIsoEnabled = selected == "Enabled"))
            }
        )

        if (settings.dynamicIsoEnabled) {
            var coefficientDraft by remember(settings.dynamicIsoCoefficient) {
                mutableStateOf(settings.dynamicIsoCoefficient.toFloat())
            }
            SettingSliderRow(
                title = "Dynamic ISO coefficient",
                description = "Dynamic ISO coefficient. 0.00 locks to ISO 50; 1.00 follows capture ISO; 2.00 expands twice as fast. The transformed ISO is truncated to an integer before A/B/C/D.",
                value = coefficientDraft,
                valueRange = NoiseModelResolver.MIN_DYNAMIC_ISO_COEFFICIENT.toFloat()..
                    NoiseModelResolver.MAX_DYNAMIC_ISO_COEFFICIENT.toFloat(),
                onValueChange = { value ->
                    coefficientDraft = sanitizePhysicalDynamicIsoCoefficient(value.toDouble()).toFloat()
                },
                onValueChangeFinished = {
                    onSettingsChanged(
                        settings.copy(dynamicIsoCoefficient = coefficientDraft.toDouble())
                    )
                },
                valueFormatter = { String.format(Locale.US, "%.2f", it) }
            )
        }
    }
}

@Composable
private fun ManualCoefficientGroup(
    label: String,
    description: String,
    values: List<String>,
    onValueChange: (Int, String) -> Unit
) {
    Text(
        text = "$label · $description",
        color = AccentPistachio,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp)
    )
    PHYSICAL_NOISE_CHANNEL_LABELS.forEachIndexed { channel, channelLabel ->
        ManualDraftField(
            label = "$channelLabel $label",
            value = values.getOrElse(channel) { "" },
            requirePositive = false,
            onValueChange = { onValueChange(channel, it) }
        )
    }
}

@Composable
private fun ManualDraftField(
    label: String,
    value: String,
    requirePositive: Boolean,
    onValueChange: (String) -> Unit
) {
    val parsed = value.trim().toDoubleOrNull()
    val invalid = value.isNotBlank() && (
        parsed == null || !parsed.isFinite() || (requirePositive && parsed <= 0.0)
    )
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color.Gray) },
        supportingText = if (invalid) {
            {
                Text(
                    if (requirePositive) "Enter a finite value > 0" else "Enter a finite signed value",
                    color = Color(0xFFFF7777)
                )
            }
        } else null,
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentPistachio,
            unfocusedBorderColor = Color.DarkGray,
            errorBorderColor = Color(0xFFFF7777)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

private fun StringBuilder.appendCoefficientLine(label: String, values: List<Double>) {
    append(label)
        .append(" = [")
        .append(values.joinToString(", ") { formatExact(it) })
        .append("]\n")
}

@Composable
private fun ReadOnlyValueBlock(
    title: String,
    subtitle: String,
    value: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .background(Color(0xFF242424), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(
            subtitle,
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
        )
        Text(value, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}


@Composable
private fun NoiseModelResetButton(onReset: () -> Unit) {
    var showResetConfirm by remember { mutableStateOf(false) }

    Button(
        onClick = { showResetConfirm = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(54.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B1A1A))
    ) {
        Text(
            text = "Reset page to default values",
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Reset Noise Model?", color = Color.White) },
            text = {
                Text(
                    text = "The Noise Model source and its user controls for this lens will return to their defaults.",
                    color = Color.Gray,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onReset()
                        showResetConfirm = false
                    }
                ) {
                    Text("Reset", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}

private data class ManualNoiseModelDraft(
    val a: List<String> = List(4) { "" },
    val b: List<String> = List(4) { "" },
    val c: List<String> = List(4) { "" },
    val d: List<String> = List(4) { "" },
    val isoStep: String = ""
) {
    fun toModelOrNull(): PersistedParametricNoiseModel? {
        fun parse(values: List<String>): List<Double>? {
            if (values.size != 4) return null
            val parsed = values.map { raw -> raw.trim().toDoubleOrNull() ?: return null }
            if (parsed.any { !it.isFinite() }) return null
            return parsed
        }

        val parsedIsoStep = isoStep.trim().toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: return null
        return PersistedParametricNoiseModel(
            a = parse(a) ?: return null,
            b = parse(b) ?: return null,
            c = parse(c) ?: return null,
            d = parse(d) ?: return null,
            isoStep = parsedIsoStep
        )
    }

    companion object {
        fun from(model: PersistedParametricNoiseModel?): ManualNoiseModelDraft {
            if (model == null) return ManualNoiseModelDraft()
            return ManualNoiseModelDraft(
                a = model.a.map(::formatExact),
                b = model.b.map(::formatExact),
                c = model.c.map(::formatExact),
                d = model.d.map(::formatExact),
                isoStep = formatExact(model.isoStep)
            )
        }
    }
}

private fun List<String>.replaced(index: Int, value: String): List<String> =
    toMutableList().also { list ->
        if (index in list.indices) list[index] = value
    }

/**
 * Compatibility destination for old deep links. The legacy direct-S/O editor is intentionally no
 * longer reachable; all routes converge on the single physical noise-model page.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ManualNoiseModelSettingsScreen(
    lensId: String,
    onNavigateBack: () -> Unit,
    onNavigateToAdvancedCalibration: () -> Unit = {}
) {
    NoiseModelSettingsScreen(
        lensId = lensId,
        onNavigateBack = onNavigateBack,
        onNavigateToManualNoiseModel = {}
    )
}

/** Compatibility destination for the retired authority-tuning page. */
@Composable
fun AdvancedSensorNoiseCalibrationScreen(
    lensId: String,
    onNavigateBack: () -> Unit
) {
    NoiseModelSettingsScreen(
        lensId = lensId,
        onNavigateBack = onNavigateBack,
        onNavigateToManualNoiseModel = {}
    )
}
