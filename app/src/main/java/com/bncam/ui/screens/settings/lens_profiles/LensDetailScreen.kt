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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.data.settings.BlackLevelSettingsStore
import com.bncam.data.settings.LensBlackLevelControlSettings
import com.bncam.data.settings.LensPhysicalNoiseModelSettings
import com.bncam.data.settings.PhysicalNoiseModelSettingsStore
import com.bncam.data.settings.PhysicalNoiseModelUiPolicy
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.LensWhiteLevelSettings
import com.bncam.data.settings.LensAwbCalibrationSettings
import com.bncam.data.settings.LensAwbCalibrationSettingsStore
import com.bncam.data.settings.WhiteLevelModes
import com.bncam.data.settings.WhiteLevelPresets
import com.bncam.data.settings.WhiteLevelSettingsStore
import com.bncam.ui.components.AccentPistachio
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
    onNavigateToAwbCalibration: () -> Unit = {},
    onNavigateToColorMatrix: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onNavigateToRawStreamBinding: () -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val settingsRepo = remember(context) { SettingsRepository(context) }
    val physicalNoiseStore = remember(context) { PhysicalNoiseModelSettingsStore(context) }
    val blackLevelStore = remember(context) { BlackLevelSettingsStore(context) }
    val whiteLevelStore = remember(context) { WhiteLevelSettingsStore(context) }
    val awbCalibrationStore = remember(context) { LensAwbCalibrationSettingsStore(context) }
    val coroutineScope = rememberCoroutineScope()
    val hardwareSummary by produceState(
        initialValue = unavailableLensHardwareSummary(),
        context,
        lensId
    ) {
        value = withContext(Dispatchers.IO) { resolveLensHardwareSummary(context, lensId) }
    }

    var showAmountDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showWhiteLevelDialog by remember { mutableStateOf(false) }

    // Preview orientation is no longer a user calibration. Retire any previously persisted
    // per-lens rotation as soon as this hardware page is opened. At the same time materialize
    // the current v2 Noise Model / Black Level state so this overview never reports legacy UI state.
    LaunchedEffect(lensId, physicalNoiseStore, blackLevelStore, whiteLevelStore, awbCalibrationStore) {
        settingsRepo.setLensPreviewOrientationCorrection(lensId, "Auto")
        physicalNoiseStore.ensureMigrated(lensId)
        blackLevelStore.ensureMigrated(lensId)
        whiteLevelStore.ensureInitialized(lensId)
        awbCalibrationStore.ensureInitialized(lensId)
    }

    // Use the same authoritative profile-count value as the viewfinder and ProfileManager.
    val profileAmountValue by settingsRepo.getProfileCountFlow(lensId).collectAsStateWithLifecycle(
        initialValue = initialProfileAmount.toIntOrNull()?.coerceIn(1, 12) ?: 6
    )
    val profileAmount = profileAmountValue.toString()
    var profileAmountDraft by remember(profileAmount) { mutableStateOf(profileAmount) }

    val noiseModelSettings by physicalNoiseStore.settingsFlow(lensId)
        .collectAsStateWithLifecycle(initialValue = LensPhysicalNoiseModelSettings())
    val blackLevelSettings by blackLevelStore.settingsFlow(lensId)
        .collectAsStateWithLifecycle(initialValue = LensBlackLevelControlSettings())
    val whiteLevelSettings by whiteLevelStore.settingsFlow(lensId)
        .collectAsStateWithLifecycle(initialValue = LensWhiteLevelSettings())
    val awbCalibrationSettings by awbCalibrationStore.settingsFlow(lensId)
        .collectAsStateWithLifecycle(initialValue = LensAwbCalibrationSettings())

    val colorMatrixMode by settingsRepo.getColorMatrixModeFlow(lensId)
        .collectAsStateWithLifecycle(initialValue = "System")

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

        SettingsCard(
            title = "Lens ID specifications",
            description = "Camera2-reported hardware details for this lens."
        ) {
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
            description = "Per-lens sensor calibration and color transform settings."
        ) {
            SettingValueRow(
                title = "Noise model",
                description = "Per-lens physical sensor noise model, presets and manual S/O values.",
                value = PhysicalNoiseModelUiPolicy.summary(noiseModelSettings),
                onClick = onNavigateToNoiseModel
            )

            SettingValueRow(
                title = "Black level",
                description = "Floating-point developed-RAW black offsets. DNG metadata remains sensor truthful.",
                value = blackLevelSettings.summary(),
                onClick = onNavigateToBlackLevel
            )

            SettingValueRow(
                title = "White level",
                description = "RAW sensor saturation authority for developed RAW processing. Auto uses Camera2 metadata.",
                value = whiteLevelSettings.summary(),
                onClick = { showWhiteLevelDialog = true }
            )

            SettingValueRow(
                title = "AWB",
                description = "Per-lens GCam-style sensor white-balance calibration and green-split authority.",
                value = awbCalibrationSettings.summary(),
                onClick = onNavigateToAwbCalibration
            )

            SettingValueRow(
                title = "Color matrix",
                description = "Sensor-to-RGB color transformation for this Lens ID.",
                value = colorMatrixMode,
                onClick = onNavigateToColorMatrix
            )
        }

        Spacer(Modifier.height(12.dp))
        LensHardwareResetButton(
            text = "Reset lens hardware settings",
            onClick = { showResetConfirm = true }
        )
        Spacer(Modifier.height(20.dp))
    }

    if (showWhiteLevelDialog) {
        WhiteLevelSelectionDialog(
            settings = whiteLevelSettings,
            onDismiss = { showWhiteLevelDialog = false },
            onSelectAuto = {
                coroutineScope.launch {
                    whiteLevelStore.set(
                        lensId,
                        whiteLevelSettings.copy(mode = WhiteLevelModes.AUTO)
                    )
                }
                showWhiteLevelDialog = false
            },
            onSelectManual = { whiteLevel ->
                coroutineScope.launch {
                    whiteLevelStore.set(
                        lensId,
                        LensWhiteLevelSettings(
                            mode = WhiteLevelModes.MANUAL,
                            manualWhiteLevel = whiteLevel
                        )
                    )
                }
                showWhiteLevelDialog = false
            }
        )
    }

    if (showAmountDialog) {
        ProfileAmountDialog(
            value = profileAmountDraft,
            onValueChange = { profileAmountDraft = it },
            onDismiss = {
                profileAmountDraft = profileAmount
                showAmountDialog = false
            },
            onConfirm = {
                val sanitized = profileAmountDraft.trim().toIntOrNull()
                    ?.coerceIn(1, 12)
                    ?.toString()
                    ?: profileAmount
                onProfileAmountChanged(sanitized)
                showAmountDialog = false
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Reset lens hardware settings?", color = Color.White) },
            text = {
                Text(
                    text = "Noise model, Black Level, White Level and other per-lens hardware calibration settings will return to their defaults.",
                    color = Color.Gray,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            settingsRepo.resetLensHardwareSettings(lensId)
                            settingsRepo.setLensPreviewOrientationCorrection(lensId, "Auto")
                        }
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

@Composable
private fun WhiteLevelSelectionDialog(
    settings: LensWhiteLevelSettings,
    onDismiss: () -> Unit,
    onSelectAuto: () -> Unit,
    onSelectManual: (Int) -> Unit
) {
    val safe = settings.sanitized()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("White Level", color = Color.White) },
        text = {
            Column {
                Text(
                    text = "Auto uses valid same-frame Camera2 dynamic white metadata when available, otherwise static sensor white metadata. Manual presets affect developed RAW processing only.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                WhiteLevelOptionRow(
                    label = "Auto",
                    selected = safe.mode == WhiteLevelModes.AUTO,
                    onClick = onSelectAuto
                )
                WhiteLevelPresets.values.forEach { preset ->
                    WhiteLevelOptionRow(
                        label = preset.label,
                        selected = safe.mode == WhiteLevelModes.MANUAL &&
                            safe.manualWhiteLevel == preset.value,
                        onClick = { onSelectManual(preset.value) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}

@Composable
private fun WhiteLevelOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentPistachio,
                unselectedColor = Color(0xFF777777)
            )
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun ProfileAmountDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Amount of profiles", color = Color.White) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = AccentPistachio,
                    focusedBorderColor = AccentPistachio,
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Save", color = AccentPistachio, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        }
    )
}

@Composable
private fun LensHardwareResetButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(54.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B1A1A))
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

private data class LensHardwareSummary(
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

private fun unavailableLensHardwareSummary() = LensHardwareSummary(
    logicalParentId = null,
    focalLengths = "Unavailable",
    sensorSize = "Unavailable",
    activeArray = "Unavailable",
    maxOutput = "Unavailable"
)

private fun resolveLensHardwareSummary(context: Context, lensId: String): LensHardwareSummary {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val resolved = resolveLensCharacteristics(cameraManager, lensId)
        ?: return unavailableLensHardwareSummary()

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
            .flatMap { format ->
                runCatching { map.getOutputSizes(format)?.asSequence() ?: emptySequence() }
                    .getOrDefault(emptySequence())
            }
            .filter { it.width > 0 && it.height > 0 }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?.let { "${it.width} × ${it.height}" }
    } ?: "Unavailable"

    return LensHardwareSummary(
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
            color = Color(0xFF777777),
            fontSize = 14.sp
        )
        Text(
            text = value,
            color = Color(0xFFD8D8D8),
            fontSize = 14.sp,
            textAlign = TextAlign.End
        )
    }
}
