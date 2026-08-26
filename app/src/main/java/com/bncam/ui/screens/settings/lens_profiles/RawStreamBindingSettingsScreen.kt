package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.parseCameraFormatCode
import com.bncam.data.settings.rawPreviewFormatCompatibility
import com.bncam.data.settings.RawPreviewFormatCompatibility
import com.bncam.ui.components.AccentPistachio
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RawBindingTarget(val source: String, val title: String) {
    RAW10("RAW10", "RAW10 stream code"),
    RAW_SENSOR("RAW_SENSOR", "RAW_SENSOR stream code")
}

@Composable
fun RawStreamBindingSettingsScreen(lensId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val codes by produceState<List<CameraStreamCodeOption>>(emptyList(), context, lensId) {
        value = withContext(Dispatchers.IO) { enumerateCameraOutputFormatCodes(context, lensId) }
    }
    val raw10 by repo.getRawPreviewFormatCodeFlow(lensId, "RAW10").collectAsStateWithLifecycle(initialValue = "AUTO")
    val rawSensor by repo.getRawPreviewFormatCodeFlow(lensId, "RAW_SENSOR").collectAsStateWithLifecycle(initialValue = "AUTO")
    var editingTarget by remember { mutableStateOf<RawBindingTarget?>(null) }

    SettingsTopicScaffold("RAW Stream Binding", onNavigateBack) {
        SettingsCard(
            title = "Viewfinder bindings",
            description = "Choose the Camera2 output-format code used to feed each RAW live-preview interpretation. Auto uses the canonical Android RAW format."
        ) {
            SettingValueRow(
                title = RawBindingTarget.RAW10.title,
                description = bindingDescription(raw10, codes),
                value = bindingLabel(raw10, RawBindingTarget.RAW10.source),
                onClick = { editingTarget = RawBindingTarget.RAW10 }
            )
            SettingValueRow(
                title = RawBindingTarget.RAW_SENSOR.title,
                description = bindingDescription(rawSensor, codes),
                value = bindingLabel(rawSensor, RawBindingTarget.RAW_SENSOR.source),
                onClick = { editingTarget = RawBindingTarget.RAW_SENSOR }
            )
        }

        SettingsCard(
            title = "Available Camera2 output codes",
            description = "Codes below are reported by this Lens ID's StreamConfigurationMap. Decimal and hexadecimal values refer to the same HAL format."
        ) {
            if (codes.isEmpty()) {
                Text("No output format codes reported.", color = Color.Gray, modifier = Modifier.padding(16.dp))
            } else {
                codes.forEach { option -> StreamCodeInfoRow(option) }
            }
        }
    }

    editingTarget?.let { target ->
        StreamCodeDialog(
            title = target.title,
            source = target.source,
            current = if (target == RawBindingTarget.RAW10) raw10 else rawSensor,
            options = codes,
            onDismiss = { editingTarget = null },
            onSelected = { encoded ->
                scope.launch { repo.setRawPreviewFormatCode(lensId, target.source, encoded) }
                editingTarget = null
            }
        )
    }
}

@Composable
private fun StreamCodeInfoRow(option: CameraStreamCodeOption) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .background(Color(0xFF222222), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(option.formatName, color = Color.White)
        Text(option.summary(), color = Color.Gray, modifier = Modifier.padding(top = 3.dp))
        if (option.sizes.isNotEmpty()) {
            Text(
                option.sizes.take(6).joinToString(" · ") { "${it.width}×${it.height}" } +
                    if (option.sizes.size > 6) " · …" else "",
                color = Color(0xFFB2D3A8),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun StreamCodeDialog(
    title: String,
    source: String,
    current: String,
    options: List<CameraStreamCodeOption>,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    var manual by remember(title) { mutableStateOf("") }
    val currentCode = parseCameraFormatCode(current)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text(title, color = Color.White) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                StreamCodeChoice("Auto (canonical format)", currentCode == null) { onSelected("AUTO") }
                options.forEach { option ->
                    val compatibility = rawPreviewFormatCompatibility(source, option.formatCode)
                    val enabled = compatibility != RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD
                    StreamCodeChoice(
                        label = buildString {
                            append("${option.formatName} · ${option.formatCode} (${option.codeHex})")
                            if (!enabled) append(" · incompatible RAW layout")
                        },
                        selected = currentCode == option.formatCode,
                        enabled = enabled
                    ) { onSelected(option.formatCode.toString()) }
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
                    val advertised = parsed != null && options.any { it.formatCode == parsed }
                    val compatibility = parsed?.let { rawPreviewFormatCompatibility(source, it) }
                    Text(
                        when {
                            parsed == null -> "Invalid format code"
                            compatibility == RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD ->
                                "Known Android format, but its layout does not match $source and cannot be bound safely"
                            compatibility == RawPreviewFormatCompatibility.CANONICAL ->
                                "Canonical $source code; equivalent to Auto"
                            advertised -> "Reported by this Lens ID · custom/vendor layout will be runtime validated"
                            else -> "Not advertised by StreamConfigurationMap; custom/vendor binding will require runtime validation"
                        },
                        color = if (compatibility == RawPreviewFormatCompatibility.CANONICAL || advertised) AccentPistachio else Color.Gray,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            val parsedManual = parseCameraFormatCode(manual)
            TextButton(
                enabled = parsedManual != null &&
                    rawPreviewFormatCompatibility(source, parsedManual) != RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD,
                onClick = { parsedManual?.let { onSelected(it.toString()) } }
            ) { Text("Use code") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StreamCodeChoice(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
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

private fun bindingLabel(encoded: String, source: String): String {
    val code = parseCameraFormatCode(encoded) ?: return "Auto"
    return when (rawPreviewFormatCompatibility(source, code)) {
        RawPreviewFormatCompatibility.CANONICAL -> "Auto / $code"
        RawPreviewFormatCompatibility.CUSTOM_VENDOR_UNVERIFIED -> code.toString()
        RawPreviewFormatCompatibility.INCOMPATIBLE_STANDARD -> "Rejected $code"
    }
}

private fun bindingDescription(encoded: String, options: List<CameraStreamCodeOption>): String {
    val code = parseCameraFormatCode(encoded) ?: return "Canonical Camera2 RAW format selected automatically."
    val option = options.firstOrNull { it.formatCode == code }
    return option?.let { "${it.formatName} · ${it.summary()}" }
        ?: "Manual code $code · not advertised; custom/vendor layout will be runtime validated before use."
}
