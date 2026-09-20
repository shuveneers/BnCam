package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.core.engine.CameraCapabilityFormatKind
import com.bncam.core.engine.CameraCapabilityRuntimeAvailability
import com.bncam.core.engine.CameraStreamCapabilityCatalog
import com.bncam.core.engine.CameraStreamCapabilityScanner
import com.bncam.core.engine.CameraStreamFormatCapability
import com.bncam.core.engine.cameraHardwareLevelName
import com.bncam.data.settings.PhotoStreamSettings
import com.bncam.data.settings.PhotoStreamSettingsStore
import com.bncam.data.settings.RawPreviewFormatCompatibility
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.parseCameraFormatCode
import com.bncam.data.settings.rawPreviewFormatCompatibility
import com.bncam.ui.components.AccentPistachio
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-lens stream controls backed only by settings that currently affect runtime behavior.
 *
 * The former Auto / Validated / Manual candidate selector was removed. PRIMARY_BUFFER format is
 * resolved from the profile constraint plus CameraCapabilityInventory. Resolution and optional RAW
 * preview support remain independently configurable here.
 */
@Composable
fun RawStreamBindingSettingsScreen(lensId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val photoSettingsStore = remember(context) { PhotoStreamSettingsStore(context) }
    val settingsRepo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val photoSettings by photoSettingsStore.settingsFlow(lensId).collectAsStateWithLifecycle(
        initialValue = PhotoStreamSettings()
    )
    val catalog by produceState<CameraStreamCapabilityCatalog?>(null, context, lensId) {
        value = withContext(Dispatchers.IO) {
            CameraStreamCapabilityScanner.scan(context, lensId)
        }
    }
    val raw10Binding by settingsRepo.getRawPreviewFormatCodeFlow(lensId, "RAW10")
        .collectAsStateWithLifecycle(initialValue = "AUTO")
    val rawSensorBinding by settingsRepo.getRawPreviewFormatCodeFlow(lensId, "RAW_SENSOR")
        .collectAsStateWithLifecycle(initialValue = "AUTO")

    var editingRawSizeIndex by remember { mutableStateOf(false) }
    var editingResolutionFix by remember { mutableStateOf(false) }
    var editingRawBinding by remember { mutableStateOf<String?>(null) }

    SettingsTopicScaffold("Stream Configuration", onNavigateBack) {
        SettingsCard(
            title = "Photo stream policy",
            description = "PRIMARY_BUFFER format is resolved automatically from the active profile and this sensor's Camera2 capabilities. Only controls with a direct runtime effect are exposed."
        ) {
            StreamInfoRow("Format selection", "Profile constraint + capability policy")
            StreamInfoRow("FPS ownership", "CaptureRequest policy")
            StreamInfoRow("Operation mode", "Session policy")

            SettingValueRow(
                title = "Specific RAW resolution",
                description = "Optional index into the active RAW format's native Camera2 size list. A valid full-FOV selection bypasses Resolution Fix. If the index does not exist for the active RAW format it is ignored.",
                value = specificRawSizeLabel(photoSettings.specificRawSizeIndex, catalog),
                onClick = { editingRawSizeIndex = true }
            )
            SettingValueRow(
                title = "Resolution Fix reference",
                description = "Uses another reported ImageFormat only as a reference size list, then remaps onto a legal native size of the actual PRIMARY_BUFFER format. It never changes the output format.",
                value = resolutionFixLabel(photoSettings.resolutionFixReferenceFormatCode, catalog),
                onClick = { editingResolutionFix = true }
            )
        }

        catalog?.let { currentCatalog ->
            RawPreviewSupportSection(
                catalog = currentCatalog,
                raw10Binding = raw10Binding,
                rawSensorBinding = rawSensorBinding,
                onEditRaw10 = { editingRawBinding = "RAW10" },
                onEditRawSensor = { editingRawBinding = "RAW_SENSOR" }
            )

            SettingsCard(
                title = "Camera2 capability source",
                description = "Read-only facts for this Lens ID. These facts are discovery input, not user-selectable stream candidates."
            ) {
                StreamInfoRow("Requested Lens ID", currentCatalog.requestedLensId)
                StreamInfoRow("Logical camera", currentCatalog.logicalCameraId ?: "Unavailable")
                currentCatalog.physicalCameraId?.let { StreamInfoRow("Physical camera", it) }
                StreamInfoRow("Source", currentCatalog.source.name.replace('_', ' '))
                StreamInfoRow("Hardware level", cameraHardwareLevelName(currentCatalog.hardwareLevel))
                StreamInfoRow("Formats", currentCatalog.formats.size.toString())
                StreamInfoRow("AE FPS ranges", currentCatalog.aeFpsRanges.joinToString().ifBlank { "Unavailable" })
                StreamInfoRow(
                    "Session preflight API",
                    if (currentCatalog.sessionQuerySupported) "Available" else "Unavailable"
                )
            }

            if (currentCatalog.warnings.isNotEmpty()) {
                SettingsCard(
                    title = "Capability notes",
                    description = "Limits detected while reading this sensor/HAL."
                ) {
                    currentCatalog.warnings.forEach { warning ->
                        Text(
                            text = warning,
                            color = Color(0xFFFFCC80),
                            modifier = Modifier.padding(vertical = 5.dp)
                        )
                    }
                }
            }

            Camera2ReportedFormatsSection(currentCatalog)
        } ?: SettingsCard(
            title = "Reading camera capabilities",
            description = "The Camera2 capability inventory for Lens ID $lensId is being built."
        ) {
            Text("Scanning…", color = Color.Gray)
        }

        SettingsCard(
            title = "Runtime behavior",
            description = "Changes are reconciled through BnCam's existing serialized soft-reset owner."
        ) {
            Text(
                text = "Specific RAW resolution and Resolution Fix can change PRIMARY_BUFFER geometry. Custom RAW preview codes can add or remove the optional RAW_PREVIEW_SUPPORT output when Selected buffer viewfinder is active. Runtime recovery is temporary and never rewrites these saved settings.",
                color = Color.Gray
            )
        }
    }

    if (editingRawSizeIndex) {
        SpecificRawSizeDialog(
            current = photoSettings.specificRawSizeIndex,
            catalog = catalog,
            onDismiss = { editingRawSizeIndex = false },
            onSelected = { index ->
                scope.launch { photoSettingsStore.setSpecificRawSizeIndex(lensId, index) }
                editingRawSizeIndex = false
            }
        )
    }

    if (editingResolutionFix) {
        ResolutionFixDialog(
            current = photoSettings.resolutionFixReferenceFormatCode,
            catalog = catalog,
            onDismiss = { editingResolutionFix = false },
            onSelected = { formatCode ->
                scope.launch {
                    photoSettingsStore.setResolutionFixReferenceFormatCode(lensId, formatCode)
                }
                editingResolutionFix = false
            }
        )
    }

    editingRawBinding?.let { source ->
        ManualRawBindingDialog(
            source = source,
            current = if (source == "RAW10") raw10Binding else rawSensorBinding,
            formats = catalog?.formats.orEmpty(),
            onDismiss = { editingRawBinding = null },
            onSelected = { encoded ->
                scope.launch { settingsRepo.setRawPreviewFormatCode(lensId, source, encoded) }
                editingRawBinding = null
            }
        )
    }
}

@Composable
private fun RawPreviewSupportSection(
    catalog: CameraStreamCapabilityCatalog,
    raw10Binding: String,
    rawSensorBinding: String,
    onEditRaw10: () -> Unit,
    onEditRawSensor: () -> Unit
) {
    SettingsCard(
        title = "RAW viewfinder support",
        description = "Advanced optional RAW_PREVIEW_SUPPORT format binding. Auto uses only the canonical RAW producer. A vendor code is runtime-validated and falls back to PRIMARY_BUFFER if support fails."
    ) {
        SettingValueRow(
            title = "RAW10 preview format code",
            description = manualBindingDescription("RAW10", raw10Binding, catalog.formats),
            value = manualBindingLabel("RAW10", raw10Binding),
            onClick = onEditRaw10
        )
        SettingValueRow(
            title = "RAW_SENSOR preview format code",
            description = manualBindingDescription("RAW_SENSOR", rawSensorBinding, catalog.formats),
            value = manualBindingLabel("RAW_SENSOR", rawSensorBinding),
            onClick = onEditRawSensor
        )
    }
}

@Composable
private fun SpecificRawSizeDialog(
    current: Int?,
    catalog: CameraStreamCapabilityCatalog?,
    onDismiss: () -> Unit,
    onSelected: (Int?) -> Unit
) {
    val rawFormats = catalog?.inventory?.formats.orEmpty().filter {
        it.runtimeAvailability == CameraCapabilityRuntimeAvailability.BNCAM_RUNTIME_READY &&
            (it.kind == CameraCapabilityFormatKind.RAW10 || it.kind == CameraCapabilityFormatKind.RAW_SENSOR) &&
            it.sizes.isNotEmpty()
    }
    val maxCount = rawFormats.maxOfOrNull { it.sizes.size } ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Specific RAW resolution", color = Color.White) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                ManualCodeChoice("Auto", current == null) { onSelected(null) }
                repeat(maxCount) { index ->
                    val mappings = rawFormats.mapNotNull { format ->
                        format.sizes.getOrNull(index)?.let { size ->
                            "${format.formatName} ${size.extent.width}×${size.extent.height}"
                        }
                    }
                    ManualCodeChoice(
                        label = "Index $index · ${mappings.joinToString(" · ")}",
                        selected = current == index
                    ) { onSelected(index) }
                }
                if (maxCount == 0) {
                    Text(
                        "No runtime-ready RAW size lists are reported for this Lens ID.",
                        color = Color.Gray,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}

@Composable
private fun ResolutionFixDialog(
    current: Int?,
    catalog: CameraStreamCapabilityCatalog?,
    onDismiss: () -> Unit,
    onSelected: (Int?) -> Unit
) {
    val formats = catalog?.formats.orEmpty().filter { it.sizes.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Resolution Fix reference", color = Color.White) },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                ManualCodeChoice("Off", current == null) { onSelected(null) }
                formats.forEach { capability ->
                    ManualCodeChoice(
                        label = "${capability.formatName} · ${capability.formatCode} · ${capability.sizes.size} sizes",
                        selected = current == capability.formatCode
                    ) { onSelected(capability.formatCode) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}

@Composable
private fun Camera2ReportedFormatsSection(catalog: CameraStreamCapabilityCatalog) {
    SettingsCard(
        title = "Reported output formats",
        description = "Read-only StreamConfigurationMap inventory for this Lens ID. A reported format/size is not automatically a valid multi-output session."
    ) {
        if (catalog.formats.isEmpty()) {
            Text("No output formats reported.", color = Color.Gray)
        } else {
            catalog.formats.forEach { capability ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .background(Color(0xFF222222), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(
                        "${capability.formatName} · ${capability.formatCode} (0x${capability.formatCode.toUInt().toString(16).uppercase()})",
                        color = Color.White
                    )
                    Text(
                        text = capability.sizes.take(6).joinToString(" · ") { item ->
                            buildString {
                                append("${item.size.width}×${item.size.height}")
                                item.maxFpsFromDuration?.let { append(" ≤${it}fps") }
                            }
                        } + if (capability.sizes.size > 6) " · …" else "",
                        color = Color(0xFFB2D3A8),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray)
        Text(value, color = Color.White, textAlign = TextAlign.End)
    }
}

@Composable
private fun ManualRawBindingDialog(
    source: String,
    current: String,
    formats: List<CameraStreamFormatCapability>,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    var manual by remember(source) { mutableStateOf("") }
    val currentCode = parseCameraFormatCode(current)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("$source preview format code", color = Color.White) },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                ManualCodeChoice("Auto (canonical Android format)", currentCode == null) { onSelected("AUTO") }
                formats.forEach { capability ->
                    val compatibility = rawPreviewFormatCompatibility(source, capability.formatCode)
                    val enabled = compatibility != RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD
                    ManualCodeChoice(
                        label = buildString {
                            append("${capability.formatName} · ${capability.formatCode}")
                            if (!enabled) append(" · incompatible RAW layout")
                        },
                        selected = currentCode == capability.formatCode,
                        enabled = enabled
                    ) { onSelected(capability.formatCode.toString()) }
                }
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    label = { Text("Manual decimal or 0xHEX code") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                val parsed = parseCameraFormatCode(manual)
                if (manual.isNotBlank()) {
                    val advertised = parsed != null && formats.any { it.formatCode == parsed }
                    val compatibility = parsed?.let { rawPreviewFormatCompatibility(source, it) }
                    Text(
                        text = when {
                            parsed == null -> "Invalid format code"
                            compatibility == RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD ->
                                "Known Android format, but its layout does not match $source and cannot be bound safely"
                            compatibility == RawPreviewFormatCompatibility.CANONICAL ->
                                "Canonical $source code; equivalent to Auto"
                            advertised -> "Reported by this Lens ID; custom/vendor layout still requires runtime validation"
                            else -> "Not advertised by this Lens ID; runtime validation is mandatory"
                        },
                        color = if (compatibility == RawPreviewFormatCompatibility.CANONICAL || advertised) AccentPistachio else Color.Gray,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            val parsed = parseCameraFormatCode(manual)
            TextButton(
                enabled = parsed != null &&
                    rawPreviewFormatCompatibility(source, parsed) != RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD,
                onClick = { parsed?.let { onSelected(it.toString()) } }
            ) { Text("Use code") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}

@Composable
private fun ManualCodeChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 7.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = AccentPistachio)
        )
        Text(
            label,
            color = if (enabled) Color.White else Color.Gray,
            modifier = Modifier.padding(start = 8.dp, top = 11.dp)
        )
    }
}

private fun specificRawSizeLabel(index: Int?, catalog: CameraStreamCapabilityCatalog?): String {
    if (index == null) return "Auto"
    val mappings = catalog?.inventory?.formats.orEmpty().mapNotNull { format ->
        if (format.runtimeAvailability != CameraCapabilityRuntimeAvailability.BNCAM_RUNTIME_READY ||
            (format.kind != CameraCapabilityFormatKind.RAW10 && format.kind != CameraCapabilityFormatKind.RAW_SENSOR)
        ) return@mapNotNull null
        format.sizes.getOrNull(index)?.let { size ->
            "${format.formatName} ${size.extent.width}×${size.extent.height}"
        }
    }
    return if (mappings.isEmpty()) "Index $index" else "Index $index · ${mappings.joinToString(" / ")}"
}

private fun resolutionFixLabel(formatCode: Int?, catalog: CameraStreamCapabilityCatalog?): String {
    if (formatCode == null) return "Off"
    val capability = catalog?.formats?.firstOrNull { it.formatCode == formatCode }
    return capability?.let { "${it.formatName} · $formatCode" } ?: "Format $formatCode"
}

private fun manualBindingLabel(source: String, encoded: String): String {
    val code = parseCameraFormatCode(encoded) ?: return "Auto"
    return when (rawPreviewFormatCompatibility(source, code)) {
        RawPreviewFormatCompatibility.CANONICAL -> "Auto / $code"
        RawPreviewFormatCompatibility.CUSTOM_VENDOR_UNVERIFIED -> code.toString()
        RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD -> "Rejected $code"
    }
}

private fun manualBindingDescription(
    source: String,
    encoded: String,
    formats: List<CameraStreamFormatCapability>
): String {
    val code = parseCameraFormatCode(encoded) ?: return "Canonical Camera2 $source format selected automatically."
    val option = formats.firstOrNull { it.formatCode == code }
    return option?.let { "${it.formatName} · reported by this Lens ID" }
        ?: "Manual code $code · not advertised by this Lens ID; runtime validation required."
}
