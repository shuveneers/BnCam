package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.core.engine.CameraStreamCandidate
import com.bncam.core.engine.CameraStreamCapabilityCatalog
import com.bncam.core.engine.CameraStreamCapabilityScanner
import com.bncam.core.engine.CameraStreamFormatCapability
import com.bncam.core.engine.StreamCandidateValidationStatus
import com.bncam.core.engine.cameraHardwareLevelName
import com.bncam.data.settings.LensStreamConfigurationSettings
import com.bncam.data.settings.RawPreviewFormatCompatibility
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.StreamClassConfiguration
import com.bncam.data.settings.StreamConfigurationClass
import com.bncam.data.settings.StreamConfigurationMode
import com.bncam.data.settings.StreamConfigurationSettingsStore
import com.bncam.data.settings.parseCameraFormatCode
import com.bncam.data.settings.rawPreviewFormatCompatibility
import com.bncam.ui.components.AccentPistachio
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compatibility route name retained so existing navigation/deep links keep working.
 *
 * This screen is now the per-lens Stream Configuration authority. The old experimental RAW format
 * code override survives only inside Manual as an advanced control. Photo Auto/Validated runtime
 * resolution is now owned by StreamConfigResolver; wider Manual/Video session controls remain staged.
 */
@Composable
fun RawStreamBindingSettingsScreen(lensId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember(context) { StreamConfigurationSettingsStore(context) }
    val legacySettingsRepo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val settings by settingsStore.settingsFlow(lensId).collectAsStateWithLifecycle(
        initialValue = LensStreamConfigurationSettings()
    )
    val catalog by produceState<CameraStreamCapabilityCatalog?>(null, context, lensId) {
        value = withContext(Dispatchers.IO) {
            CameraStreamCapabilityScanner.scan(context, lensId)
        }
    }
    val raw10Binding by legacySettingsRepo.getRawPreviewFormatCodeFlow(lensId, "RAW10")
        .collectAsStateWithLifecycle(initialValue = "AUTO")
    val rawSensorBinding by legacySettingsRepo.getRawPreviewFormatCodeFlow(lensId, "RAW_SENSOR")
        .collectAsStateWithLifecycle(initialValue = "AUTO")

    var editingClass by remember { mutableStateOf<StreamConfigurationClass?>(null) }
    var editingRawBinding by remember { mutableStateOf<String?>(null) }

    SettingsTopicScaffold("Stream Configuration", onNavigateBack) {
        SettingsCard(
            title = "Lens stream policy",
            description = "Stream choices are stored for Lens ID $lensId. Auto is the safe default; Validated exposes sensor/HAL-derived candidates; Manual is for controlled overrides."
        ) {
            StreamClassRow(
                streamClass = StreamConfigurationClass.PHOTO,
                configuration = settings.photo,
                catalog = catalog,
                onClick = { editingClass = StreamConfigurationClass.PHOTO }
            )
            StreamClassRow(
                streamClass = StreamConfigurationClass.VIDEO,
                configuration = settings.video,
                catalog = catalog,
                onClick = { editingClass = StreamConfigurationClass.VIDEO }
            )
        }

        catalog?.let { currentCatalog ->
            SettingsCard(
                title = "Camera2 capability source",
                description = "Read-only facts for this Lens ID. These are not copied between sensors."
            ) {
                StreamInfoRow("Requested Lens ID", currentCatalog.requestedLensId)
                StreamInfoRow("Logical camera", currentCatalog.logicalCameraId ?: "Unavailable")
                currentCatalog.physicalCameraId?.let { StreamInfoRow("Physical camera", it) }
                StreamInfoRow("Source", currentCatalog.source.name.replace('_', ' '))
                StreamInfoRow("Hardware level", cameraHardwareLevelName(currentCatalog.hardwareLevel))
                StreamInfoRow("Formats", currentCatalog.formats.size.toString())
                StreamInfoRow("AE FPS ranges", currentCatalog.aeFpsRanges.joinToString().ifBlank { "Unavailable" })
                StreamInfoRow(
                    "Session preflight",
                    if (currentCatalog.sessionQuerySupported) "CameraDeviceSetup available" else "Reported capabilities only"
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

            ValidatedCandidateSection(
                streamClass = StreamConfigurationClass.PHOTO,
                configuration = settings.photo,
                catalog = currentCatalog,
                onSelected = { candidate ->
                    scope.launch {
                        settingsStore.setValidatedCandidate(lensId, StreamConfigurationClass.PHOTO, candidate.id)
                    }
                }
            )
            ValidatedCandidateSection(
                streamClass = StreamConfigurationClass.VIDEO,
                configuration = settings.video,
                catalog = currentCatalog,
                onSelected = { candidate ->
                    scope.launch {
                        settingsStore.setValidatedCandidate(lensId, StreamConfigurationClass.VIDEO, candidate.id)
                    }
                }
            )

            if (settings.photo.mode == StreamConfigurationMode.MANUAL ||
                settings.video.mode == StreamConfigurationMode.MANUAL
            ) {
                ManualStreamSection(
                    catalog = currentCatalog,
                    raw10Binding = raw10Binding,
                    rawSensorBinding = rawSensorBinding,
                    onEditRaw10 = { editingRawBinding = "RAW10" },
                    onEditRawSensor = { editingRawBinding = "RAW_SENSOR" }
                )
            }

            Camera2ReportedFormatsSection(currentCatalog)
        } ?: SettingsCard(
            title = "Reading camera capabilities",
            description = "The Camera2 capability catalog for Lens ID $lensId is being built."
        ) {
            Text("Scanning…", color = Color.Gray)
        }

        SettingsCard(
            title = "Runtime status",
            description = "Phase 0003 live-apply boundary."
        ) {
            Text(
                text = "Photo Stream Configuration is live-connected to the active Lens ID. Runtime-relevant Photo changes are reconciled through BnCam's existing serialized soft-reset owner: unchanged plans remain warm, validated size changes rebuild the producer atomically, and Manual custom RAW bindings refresh only the session outputs they actually require. Video remains catalog-only in this phase.",
                color = Color.Gray
            )
        }
    }

    editingClass?.let { streamClass ->
        StreamModeDialog(
            streamClass = streamClass,
            current = settings.forClass(streamClass).mode,
            onDismiss = { editingClass = null },
            onSelected = { mode ->
                scope.launch { settingsStore.setMode(lensId, streamClass, mode) }
                editingClass = null
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
                scope.launch { legacySettingsRepo.setRawPreviewFormatCode(lensId, source, encoded) }
                editingRawBinding = null
            }
        )
    }
}

@Composable
private fun StreamClassRow(
    streamClass: StreamConfigurationClass,
    configuration: StreamClassConfiguration,
    catalog: CameraStreamCapabilityCatalog?,
    onClick: () -> Unit
) {
    val selectedCandidate = configuration.validatedCandidateId?.let { id ->
        catalog?.candidates?.firstOrNull { it.id == id }
    }
    val value = when (configuration.mode) {
        StreamConfigurationMode.AUTO -> "Auto"
        StreamConfigurationMode.MANUAL -> "Manual"
        StreamConfigurationMode.VALIDATED -> selectedCandidate?.let {
            "Validated · ${it.captureFormatName} ${it.captureSize.width}×${it.captureSize.height}"
        } ?: "Validated · Auto candidate"
    }
    SettingValueRow(
        title = streamClass.displayName,
        description = when (streamClass) {
            StreamConfigurationClass.PHOTO -> "Still capture, warm buffer and viewfinder session policy."
            StreamConfigurationClass.VIDEO -> "Video-oriented stream policy kept separate from Photo."
        },
        value = value,
        onClick = onClick
    )
}

@Composable
private fun StreamModeDialog(
    streamClass: StreamConfigurationClass,
    current: StreamConfigurationMode,
    onDismiss: () -> Unit,
    onSelected: (StreamConfigurationMode) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("${streamClass.displayName} stream mode", color = Color.White) },
        text = {
            Column {
                StreamConfigurationMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(mode) }
                            .padding(vertical = 6.dp)
                    ) {
                        RadioButton(
                            selected = current == mode,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = AccentPistachio)
                        )
                        Column(modifier = Modifier.padding(start = 8.dp, top = 8.dp)) {
                            Text(mode.displayName, color = Color.White, fontWeight = FontWeight.Medium)
                            Text(modeDescription(mode), color = Color.Gray)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } }
    )
}

private fun modeDescription(mode: StreamConfigurationMode): String = when (mode) {
    StreamConfigurationMode.AUTO -> "BnCam resolves a safe sensor-specific plan automatically."
    StreamConfigurationMode.VALIDATED -> "Choose a HAL-preflighted candidate. Runtime revalidates it against the exact active viewfinder before applying it."
    StreamConfigurationMode.MANUAL -> "Expose controlled format overrides. Runtime validation remains mandatory."
}

@Composable
private fun ValidatedCandidateSection(
    streamClass: StreamConfigurationClass,
    configuration: StreamClassConfiguration,
    catalog: CameraStreamCapabilityCatalog,
    onSelected: (CameraStreamCandidate) -> Unit
) {
    if (configuration.mode != StreamConfigurationMode.VALIDATED) return
    val candidates = catalog.candidatesFor(streamClass)
        .sortedWith(
            compareBy<CameraStreamCandidate> {
                when (it.validationStatus) {
                    StreamCandidateValidationStatus.SESSION_VALIDATED -> 0
                    StreamCandidateValidationStatus.CAMERA2_REPORTED -> 1
                    StreamCandidateValidationStatus.REJECTED -> 2
                }
            }.thenByDescending { it.captureSize.width.toLong() * it.captureSize.height.toLong() }
        )

    SettingsCard(
        title = "${streamClass.displayName} validated configurations",
        description = if (catalog.sessionQuerySupported && streamClass == StreamConfigurationClass.PHOTO) {
            "Candidates are generated from this Lens ID and preflighted as complete preview + capture sessions when Android exposes CameraDeviceSetup."
        } else {
            "Candidates are generated only from this Lens ID's Camera2 stream properties. Items not fully preflightable are explicitly marked Reported."
        }
    ) {
        if (candidates.isEmpty()) {
            Text("No usable candidates were reported for this class.", color = Color.Gray)
        } else {
            candidates.forEach { candidate ->
                CandidateRow(
                    candidate = candidate,
                    selected = configuration.validatedCandidateId == candidate.id,
                    enabled = candidate.validationStatus == StreamCandidateValidationStatus.SESSION_VALIDATED,
                    onClick = { onSelected(candidate) }
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: CameraStreamCandidate,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(
                if (selected) Color(0xFF263126) else Color(0xFF222222),
                RoundedCornerShape(14.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = AccentPistachio)
            )
            Column(modifier = Modifier.padding(start = 8.dp, top = 6.dp)) {
                Text(
                    "${candidate.captureFormatName} · ${candidate.captureSize.width}×${candidate.captureSize.height}",
                    color = if (enabled) Color.White else Color.Gray,
                    fontWeight = FontWeight.Medium
                )
                candidate.previewSize?.let {
                    Text("Preview ${it.width}×${it.height}", color = Color.Gray)
                }
                candidate.fpsRange?.let {
                    Text("FPS ${it.lower}–${it.upper}", color = Color.Gray)
                }
            }
        }
        val status = when (candidate.validationStatus) {
            StreamCandidateValidationStatus.SESSION_VALIDATED -> "HAL session validated"
            StreamCandidateValidationStatus.CAMERA2_REPORTED -> "Camera2 reported"
            StreamCandidateValidationStatus.REJECTED -> "Rejected"
        }
        Text(
            text = "$status · ${candidate.validationReason}",
            color = if (enabled) AccentPistachio else Color.Gray,
            modifier = Modifier.padding(start = 52.dp, top = 4.dp)
        )
    }
}

@Composable
private fun ManualStreamSection(
    catalog: CameraStreamCapabilityCatalog,
    raw10Binding: String,
    rawSensorBinding: String,
    onEditRaw10: () -> Unit,
    onEditRawSensor: () -> Unit
) {
    SettingsCard(
        title = "Manual / advanced",
        description = "Existing experimental RAW preview format binding is retained here temporarily. Standard incompatible Android layouts are blocked; vendor codes still require runtime validation."
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
        Text(
            text = "Manual resolution/FPS/session overrides are intentionally not exposed yet. The existing custom RAW preview-code experiment is the only Manual runtime override in phase 0002, and it retains its configure-failure fallback to the canonical RAW path.",
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun Camera2ReportedFormatsSection(catalog: CameraStreamCapabilityCatalog) {
    SettingsCard(
        title = "Reported output formats",
        description = "Raw StreamConfigurationMap inventory for this Lens ID. A reported format/size is not automatically a valid multi-output session."
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
