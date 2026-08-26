package com.bncam.ui.screens.settings.lens_profiles

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.data.settings.LensNoiseModelSettings
import com.bncam.data.settings.LensBlackLevelSettings
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.parseCameraFormatCode
import com.bncam.data.settings.StableLensKey
import com.bncam.core.quality.LensCalibrationTelemetry
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LensDetailScreen(
    lensId: String,
    initialProfileAmount: String,
    onProfileAmountChanged: (String) -> Unit,
    onNavigateToProfileList: (String) -> Unit,
    onNavigateToNoiseModel: () -> Unit = {},
    onNavigateToBlackLevel: () -> Unit = {},
    onNavigateToColorMatrix: () -> Unit = {},
    onNavigateToRawStreamBinding: () -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val settingsRepo = remember(context) { SettingsRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    val hardwareSummary by produceState(
        initialValue = unavailableLensHardwareSummary(lensId),
        context,
        lensId
    ) {
        value = withContext(Dispatchers.IO) { resolveLensHardwareSummary(context, lensId) }
    }

    var showAmountDialog by remember { mutableStateOf(false) }
    var showOrientationDialog by remember { mutableStateOf(false) }

    // Use the same authoritative profile-count value as the viewfinder and ProfileManager.
    // The temporary hardware_lens_*_profile_amount key is intentionally no longer used here.
    val profileAmountValue by settingsRepo.getProfileCountFlow(lensId).collectAsStateWithLifecycle(
        initialValue = initialProfileAmount.toIntOrNull()?.coerceIn(1, 12) ?: 6
    )
    val profileAmount = profileAmountValue.toString()

    var profileAmountDraft by remember(profileAmount) { mutableStateOf(profileAmount) }


    val noiseModelSettings by settingsRepo.getLensNoiseModelSettingsFlow(lensId)
        .collectAsStateWithLifecycle(initialValue = LensNoiseModelSettings())
    val blackLevelSettings by settingsRepo.getLensBlackLevelSettingsFlow(lensId)
        .collectAsStateWithLifecycle(initialValue = LensBlackLevelSettings())
    val calibrationTelemetry by LensCalibrationTelemetry.flow(lensId).collectAsStateWithLifecycle()
    val conciseNoiseSource = when {
        calibrationTelemetry?.noiseSource?.contains("SENSOR_NOISE_PROFILE") == true -> "Camera2 metadata"
        calibrationTelemetry?.noiseSource?.contains("manual", ignoreCase = true) == true -> "Lens manual"
        calibrationTelemetry == null -> null
        else -> "Fallback"
    }
    val conciseBlackSource = when {
        calibrationTelemetry?.blackLevelSource?.contains("DYNAMIC_BLACK_LEVEL") == true -> "Dynamic metadata"
        calibrationTelemetry?.blackLevelSource?.contains("BLACK_LEVEL_PATTERN") == true -> "Static pattern"
        calibrationTelemetry?.blackLevelSource?.contains("manual", ignoreCase = true) == true -> "Lens manual"
        calibrationTelemetry == null -> null
        else -> "Fallback"
    }

    val colorMatrixMode by settingsRepo.getColorMatrixModeFlow(lensId).collectAsStateWithLifecycle(initialValue = "System")
    val raw10PreviewBinding by settingsRepo.getRawPreviewFormatCodeFlow(lensId, "RAW10")
        .collectAsStateWithLifecycle(initialValue = "AUTO")
    val rawSensorPreviewBinding by settingsRepo.getRawPreviewFormatCodeFlow(lensId, "RAW_SENSOR")
        .collectAsStateWithLifecycle(initialValue = "AUTO")
    val previewOrientationCorrection by settingsRepo.getLensPreviewOrientationCorrectionFlow(lensId)
        .collectAsStateWithLifecycle(initialValue = "Auto")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(scrollState)
            .systemBarsPadding()
            .padding(bottom = 32.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier
                .padding(16.dp)
                .clickable { onNavigateBack() }
        )

        Text(
            text = "Lens $lensId Calibration",
            color = Color.White,
            fontWeight = FontWeight.Light,
            fontSize = 44.sp,
            letterSpacing = (-1).sp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        )

        SettingsCard(title = "Lens ID specifications", description = "Camera2-reported hardware details for this lens.") {
            SpecRow("Stable lens key", hardwareSummary.stableLensKey)
            SpecRow("Hardware ID", lensId)
            hardwareSummary.logicalParentId?.let { parentId ->
                SpecRow("Logical parent", parentId)
            }
            SpecRow("Focal length", hardwareSummary.focalLengths)
            SpecRow("Sensor size", hardwareSummary.sensorSize)
            SpecRow("Active array", hardwareSummary.activeArray)
            SpecRow("Max output", hardwareSummary.maxOutput)
        }

        SettingsCard(title = "Profile settings", description = "Configure the profile list.") {
            SettingValueRow(
                title = "Amount of profiles",
                description = "Set how many profiles are available (1 - 12).",
                value = profileAmount,
                onClick = { showAmountDialog = true }
            )
            SettingValueRow(
                title = "Enter profile list",
                description = "Manage individual profiles for this lens.",
                value = "Open",
                onClick = { onNavigateToProfileList(lensId) }
            )
        }

        SettingsCard(
            title = "Lens hardware settings",
            description = "Sensor calibration and RAW viewfinder routing for this Lens ID."
        ) {
            SettingValueRow(
                title = "Reset lens hardware settings",
                description = "Return this Lens ID to neutral system/auto behavior.",
                value = "Reset",
                onClick = {
                    coroutineScope.launch { settingsRepo.resetLensHardwareSettings(lensId) }
                }
            )

            SettingValueRow(
                title = "Preview orientation correction",
                description = "Additional per-lens display rotation. Keep Auto unless a vendor/hidden Lens ID is physically shown rotated.",
                value = previewOrientationCorrection,
                onClick = { showOrientationDialog = true }
            )

            CalibrationGroupLabel("Noise Model Calibration")
            SettingValueRow(
                title = "SPECTRA noise model",
                description = "Per-lens sensor noise calibration and manual S/O values.",
                value = noiseModelSettings.summary("Sensor CFA", conciseNoiseSource),
                onClick = onNavigateToNoiseModel
            )

            CalibrationGroupLabel("Black Level Calibration")
            SettingValueRow(
                title = "Black level",
                description = "Floating-point developed-RAW black offsets. DNG metadata remains sensor truthful.",
                value = blackLevelSettings.summary(conciseBlackSource),
                onClick = onNavigateToBlackLevel
            )

            CalibrationGroupLabel("Color Transform Scheme")
            SettingValueRow(
                title = "Color matrix",
                description = "Sensor-to-RGB color transformation for this Lens ID.",
                value = colorMatrixMode,
                onClick = onNavigateToColorMatrix
            )


            CalibrationGroupLabel("RAW Viewfinder Stream Calibration")
            SettingValueRow(
                title = "RAW stream binding",
                description = "Inspect Camera2 stream codes and bind a reported or manual code to RAW10 / RAW_SENSOR live preview.",
                value = rawStreamBindingSummary(raw10PreviewBinding, rawSensorPreviewBinding),
                onClick = onNavigateToRawStreamBinding
            )
        }

    }

    if (showOrientationDialog) {
        AlertDialog(
            onDismissRequest = { showOrientationDialog = false },
            title = { Text("Preview orientation correction") },
            text = {
                Column {
                    listOf("Auto", "+90°", "+180°", "+270°").forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    coroutineScope.launch {
                                        settingsRepo.setLensPreviewOrientationCorrection(lensId, option)
                                    }
                                    showOrientationDialog = false
                                }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(option)
                            if (option == previewOrientationCorrection) {
                                Text("Selected", color = Color(0xFFC5E1A5), fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                OutlinedButton(onClick = { showOrientationDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAmountDialog) {
        SimpleTextDialog(
            title = "Amount of profiles",
            value = profileAmountDraft,
            onValueChange = { profileAmountDraft = it },
            onDismiss = {
                profileAmountDraft = profileAmount
                showAmountDialog = false
            },
            onConfirm = {
                val sanitized = profileAmountDraft.trim().toIntOrNull()?.coerceIn(1, 12)?.toString() ?: profileAmount
                // AppNavigation persists this through SettingsRepository.setProfileCount(...),
                // which is also read by the viewfinder and the restored profile list.
                onProfileAmountChanged(sanitized)
                showAmountDialog = false
            }
        )
    }

}

@Composable
private fun CalibrationGroupLabel(title: String) {
    Text(
        text = title,
        color = Color(0xFFC5E1A5),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp)
    )
}

@Composable
private fun SimpleTextDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("OK") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun rawStreamBindingSummary(raw10: String, rawSensor: String): String {
    fun label(encoded: String): String = parseCameraFormatCode(encoded)?.toString() ?: "Auto"
    return "RAW10 ${label(raw10)}\nRAW_SENSOR ${label(rawSensor)}"
}

private data class LensHardwareSummary(
    val stableLensKey: String,
    val logicalParentId: String?,
    val focalLengths: String,
    val sensorSize: String,
    val activeArray: String,
    val maxOutput: String
)

private data class ResolvedLensCharacteristics(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val characteristics: CameraCharacteristics
)

private fun unavailableLensHardwareSummary(lensId: String) = LensHardwareSummary(
    stableLensKey = StableLensKey.fromString(lensId).value,
    logicalParentId = null,
    focalLengths = "Unavailable",
    sensorSize = "Unavailable",
    activeArray = "Unavailable",
    maxOutput = "Unavailable"
)

private fun resolveLensHardwareSummary(context: Context, lensId: String): LensHardwareSummary {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val resolved = resolveLensCharacteristics(cameraManager, lensId)
    if (resolved == null) {
        return unavailableLensHardwareSummary(lensId)
    }

    val chars = resolved.characteristics
    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.filter { it.isFinite() && it > 0f }
        ?.joinToString(" / ") { value -> String.format(java.util.Locale.US, "%.2f mm", value) }
        ?.takeIf { it.isNotBlank() }
        ?: "Unavailable"

    val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { size ->
        String.format(java.util.Locale.US, "%.2f × %.2f mm", size.width, size.height)
    } ?: "Unavailable"

    val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { rect ->
        "${rect.width()} × ${rect.height()}"
    } ?: "Unavailable"

    val maxOutput = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.let { map ->
        map.outputFormats
            .asSequence()
            .flatMap { format -> runCatching { map.getOutputSizes(format)?.asSequence() ?: emptySequence() }.getOrDefault(emptySequence()) }
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?.let { "${it.width} × ${it.height}" }
    } ?: "Unavailable"

    return LensHardwareSummary(
        stableLensKey = StableLensKey.fromRawPhysicalId(
            resolved.logicalCameraId,
            resolved.physicalCameraId
        ).value,
        logicalParentId = resolved.physicalCameraId?.let { resolved.logicalCameraId },
        focalLengths = focalLengths,
        sensorSize = sensorSize,
        activeArray = activeArray,
        maxOutput = maxOutput
    )
}

private fun resolveLensCharacteristics(
    cameraManager: CameraManager,
    lensId: String
): ResolvedLensCharacteristics? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val parent = runCatching {
            cameraManager.cameraIdList
                .sorted()
                .firstNotNullOfOrNull { logicalId ->
                    val logicalChars = cameraManager.getCameraCharacteristics(logicalId)
                    if (logicalChars.physicalCameraIds.contains(lensId)) {
                        val physicalChars = runCatching {
                            cameraManager.getCameraCharacteristics(lensId)
                        }.getOrElse {
                            // Some HALs expose a physical ID only through its logical parent.
                            // In that case keep the page usable and report the parent characteristics
                            // rather than inventing hardware specifications.
                            logicalChars
                        }
                        ResolvedLensCharacteristics(logicalId, lensId, physicalChars)
                    } else {
                        null
                    }
                }
        }.getOrNull()
        if (parent != null) return parent
    }

    return runCatching {
        ResolvedLensCharacteristics(
            logicalCameraId = lensId,
            physicalCameraId = null,
            characteristics = cameraManager.getCameraCharacteristics(lensId)
        )
    }.getOrNull()
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier
                .weight(0.38f)
                .padding(end = 12.dp)
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.62f)
        )
    }
}
