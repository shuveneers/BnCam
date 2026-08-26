package com.bncam.ui.screens.settings

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.data.settings.SettingsRepository
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.capture.FrameCapacityPolicy
import com.bncam.core.capture.FrameOrigin
import com.bncam.data.settings.DngSourcePolicy
import com.bncam.ui.components.SettingSliderRow
import com.bncam.ui.components.SettingToggleRow
import com.bncam.ui.components.SettingValueRow
import kotlinx.coroutines.launch

val AccentPistachio = Color(0xFFB2D3A8)
val CardBackground = Color(0xFF1E1E1E)

@Composable
fun AppSettingsScreen(
    activeProfileId: String, // <-- NIEUW: Om de buffer state van de actieve lens te checken
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    // --- File & Storage States ---
    val saveLocation by repository.saveLocationFlow.collectAsState(initial = "DCIM/BnCam")
    val outputPolicy by repository.outputPolicyFlow.collectAsState(initial = OutputPolicy.JPEG)
    val outputModeSettings by repository.getOutputModeSettingsFlow().collectAsState(
        initial = com.bncam.data.settings.OutputModeSettings()
    )
    val computationalHdrEnabled by repository.computationalHdrEnabledFlow.collectAsState(initial = false)
    val hdrEnhancedFrameSetting by repository.hdrEnhancedFrameSettingFlow.collectAsState(initial = "Auto")
    val ultraHdrGainmapEnabled by repository.ultraHdrGainmapEnabledFlow.collectAsState(initial = false)
    val portraitEffectEnabled by repository.portraitEffectEnabledFlow.collectAsState(initial = false)
    val photoPrefix by repository.photoPrefixFlow.collectAsState(initial = "IMG_BNC")
    val forceGooglePhotos by repository.forceGooglePhotosFlow.collectAsState(initial = false)

    val preferredFrame by repository.getProfileFrameSourceFlow(activeProfileId).collectAsState(initial = "YUV")
    val isRawDngCapableBuffer = preferredFrame == "RAW10" || preferredFrame == "RAW_SENSOR"
    val dngConfig = outputModeSettings.rawPlusJpeg
    val dngFrameOrigin = if (preferredFrame == "RAW_SENSOR") FrameOrigin.RAW_SENSOR else FrameOrigin.RAW10
    val maxDngMasterFrames = FrameCapacityPolicy.maximumDngMasterFrames(dngFrameOrigin)

    // --- Device & Interaction States ---
    val hapticFeedback by repository.hapticFeedbackFlow.collectAsState(initial = true)
    val cameraSounds by repository.cameraSoundsFlow.collectAsState(initial = true)
    val volumeButtonAction by repository.volumeButtonActionFlow.collectAsState(initial = "Take Photo")
    val forceMaxBrightness by repository.forceMaxBrightnessFlow.collectAsState(initial = false)

    // --- Privacy & Advanced States ---
    val saveLocationData by repository.saveLocationDataFlow.collectAsState(initial = false)
    val enableShotLogger by repository.enableShotLoggerFlow.collectAsState(initial = false)
    val phoneAssistanceSensors by repository.phoneAssistanceSensorsFlow.collectAsState(initial = false)

    // --- GPS Permissie Launcher ---
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            scope.launch { repository.setSaveLocationData(granted) }
            if (!granted) {
                Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    )

    // --- Dialog Triggers ---
    var showShotLoggerConfig by remember { mutableStateOf(false) }
    var showPhotoPrefixDialog by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var tempInput by remember { mutableStateOf("") }

    // --- ShotLogger Config States ---
    val logSummary by repository.logSummaryFlow.collectAsState(initial = true)
    val logActiveMode by repository.logActiveModeFlow.collectAsState(initial = true)
    val logProfileSettings by repository.logProfileSettingsFlow.collectAsState(initial = true)
    val logFrameAnalysis by repository.logFrameAnalysisFlow.collectAsState(initial = true)
    val logWarnings by repository.logWarningsFlow.collectAsState(initial = true)
    val logPipelineDebug by repository.logPipelineDebugFlow.collectAsState(initial = true)
    val logVendorInjection by repository.logVendorInjectionFlow.collectAsState(initial = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier
                .padding(16.dp)
                .clickable { onNavigateBack() }
        )

        Text(
            text = "App settings",
            color = Color.White,
            fontWeight = FontWeight.Light,
            fontSize = 48.sp,
            letterSpacing = (-1).sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // KAART 1: FILE & STORAGE
            SettingsCard(title = "File & Storage") {
                SettingValueRow(
                    title = "Save location",
                    description = "Path: $saveLocation",
                    value = "Edit",
                    onClick = {
                        tempInput = saveLocation
                        showLocationDialog = true
                    }
                )

                // DE GEÜPDATE SAVE FORMAT KNOP
                SettingValueRow(
                    title = "Save format",
                    description = if (isRawDngCapableBuffer) {
                        "Exact capture output for the active $preferredFrame pipeline."
                    } else {
                        if (outputPolicy == OutputPolicy.JPEG) {
                            "YUV supports JPEG only; RAW outputs are not applicable."
                        } else {
                            "${outputPolicy.displayName} is not applicable to YUV. Tap to explicitly select JPEG."
                        }
                    },
                    value = if (isRawDngCapableBuffer || outputPolicy == OutputPolicy.JPEG) {
                        outputPolicy.displayName
                    } else {
                        "${outputPolicy.displayName} (Not applicable)"
                    },
                    onClick = {
                        if (!isRawDngCapableBuffer) {
                            if (outputPolicy != OutputPolicy.JPEG) {
                                scope.launch { repository.setOutputPolicy(OutputPolicy.JPEG) }
                            } else {
                                Toast.makeText(context, "YUV supports JPEG output only.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            scope.launch {
                                val nextPolicy = when (outputPolicy) {
                                    OutputPolicy.JPEG -> OutputPolicy.JPEG_PLUS_RAW
                                    OutputPolicy.JPEG_PLUS_RAW -> OutputPolicy.RAW_ONLY
                                    OutputPolicy.RAW_ONLY -> OutputPolicy.JPEG
                                }
                                repository.setOutputPolicy(nextPolicy)
                            }
                        }
                    }
                )

                if (isRawDngCapableBuffer && outputPolicy.producesRaw) {
                    SettingValueRow(
                        title = "DNG source",
                        description = "Anchor RAW exports one pristine source frame. Fused RAW creates the exported DNG master from multiple aligned RAW frames.",
                        value = if (dngConfig.dngSourcePolicy == DngSourcePolicy.ANCHOR_RAW) "Anchor RAW" else "Fused RAW",
                        onClick = {
                            val next = if (dngConfig.dngSourcePolicy == DngSourcePolicy.ANCHOR_RAW) {
                                DngSourcePolicy.FUSED_RAW
                            } else {
                                DngSourcePolicy.ANCHOR_RAW
                            }
                            scope.launch {
                                val updated = dngConfig.copy(
                                    dngSourcePolicy = next,
                                    dngMasterFrameCount = if (next == DngSourcePolicy.FUSED_RAW) {
                                        dngConfig.dngMasterFrameCount.coerceIn(2, maxDngMasterFrames)
                                    } else {
                                        dngConfig.dngMasterFrameCount
                                    }
                                )
                                repository.setOutputModeSettings(
                                    outputModeSettings.copy(rawPlusJpeg = updated, rawOnly = updated)
                                )
                            }
                        }
                    )
                    if (dngConfig.dngSourcePolicy == DngSourcePolicy.FUSED_RAW) {
                        SettingSliderRow(
                            title = "DNG master frame count",
                            description = "Frames merged into the computational RAW master published as DNG. This is independent from the profile's JPEG Fusion Frame Count.",
                            value = dngConfig.dngMasterFrameCount.coerceIn(2, maxDngMasterFrames).toFloat(),
                            valueRange = 2f..maxDngMasterFrames.toFloat(),
                            onValueChange = { raw ->
                                val count = raw.toInt().coerceIn(2, maxDngMasterFrames)
                                scope.launch {
                                    val updated = dngConfig.copy(dngMasterFrameCount = count)
                                    repository.setOutputModeSettings(
                                        outputModeSettings.copy(rawPlusJpeg = updated, rawOnly = updated)
                                    )
                                }
                            },
                            valueFormatter = { it.toInt().toString() }
                        )
                    }
                }

                SettingValueRow(
                    title = "Photo prefix",
                    description = "Currently: $photoPrefix",
                    value = "Edit",
                    onClick = {
                        tempInput = photoPrefix
                        showPhotoPrefixDialog = true
                    }
                )
                SettingToggleRow(
                    title = "Force Google Photos",
                    description = "Always open Google Photos.",
                    checked = forceGooglePhotos,
                    onCheckedChange = { scope.launch { repository.setForceGooglePhotos(it) } }
                )
            }

            // KAART 2: DEVICE & INTERACTION
            SettingsCard(title = "Device & Interaction") {
                SettingToggleRow(
                    title = "Haptic feedback",
                    description = "UI vibration.",
                    checked = hapticFeedback,
                    onCheckedChange = { scope.launch { repository.setHapticFeedback(it) } }
                )
                SettingToggleRow(
                    title = "Camera sounds",
                    description = "Shutter sounds.",
                    checked = cameraSounds,
                    onCheckedChange = { scope.launch { repository.setCameraSounds(it) } }
                )
                SettingValueRow(
                    title = "Volume button",
                    description = "Current action: $volumeButtonAction",
                    value = "Change",
                    onClick = {
                        scope.launch {
                            val next = when(volumeButtonAction) {
                                "Take Photo" -> "Zoom"
                                "Zoom" -> "Device Volume"
                                "Device Volume" -> "Do Nothing"
                                else -> "Take Photo"
                            }
                            repository.setVolumeButtonAction(next)
                        }
                    }
                )
                SettingToggleRow(
                    title = "Force max brightness",
                    description = "Keep screen at 100%.",
                    checked = forceMaxBrightness,
                    onCheckedChange = { scope.launch { repository.setForceMaxBrightness(it) } }
                )
            }

            // KAART 3: PRIVACY & ADVANCED
            SettingsCard(title = "Privacy & Advanced") {
                SettingToggleRow(
                    title = "Computational HDR",
                    description = when (outputPolicy) {
                        OutputPolicy.RAW_ONLY ->
                            "Preference retained, but RAW-only has no computational JPEG target. DNG remains the pristine anchor frame."
                        OutputPolicy.JPEG_PLUS_RAW ->
                            "Deliberate multi-frame RAW HDR for JPEG with a user frame ceiling; RAW publication remains separate from JPEG rendering."
                        OutputPolicy.JPEG ->
                            "Deliberate same/similar-exposure RAW stacking with Vulkan alignment, temporal denoise, highlight protection, and shadow recovery."
                    },
                    checked = computationalHdrEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { repository.setComputationalHdrEnabled(enabled) }
                    }
                )
                if (computationalHdrEnabled) {
                    SettingValueRow(
                        title = "HDR Enhanced frames",
                        description = "Target frame count ceiling for HDR Enhanced capture bursts.",
                        value = hdrEnhancedFrameSetting,
                        onClick = {
                            scope.launch {
                                val options = listOf("Auto", "4", "6", "8", "10", "12", "15")
                                val nextIndex = (options.indexOf(hdrEnhancedFrameSetting) + 1) % options.size
                                repository.setHdrEnhancedFrameSetting(options[nextIndex])
                            }
                        }
                    )
                }
                SettingToggleRow(
                    title = "Ultra HDR gainmap",
                    description = "Store real HDR luminance headroom in a backward-compatible JPEG gainmap. Gainmap image processing is Vulkan/GPU based.",
                    checked = ultraHdrGainmapEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { repository.setUltraHdrGainmapEnabled(enabled) }
                    }
                )
                SettingToggleRow(
                    title = "Portrait effect",
                    description = "Keep the selected foreground subject sharp and apply GPU bokeh to the background. Works with people, pets and prominent objects.",
                    checked = portraitEffectEnabled,
                    onCheckedChange = { enabled ->
                        scope.launch { repository.setPortraitEffectEnabled(enabled) }
                    }
                )
                SettingToggleRow(
                    title = "Phone assistance sensors",
                    description = "Use supported auxiliary color/CCT sensors for RAW color assistance. Camera2/HAL remains authoritative for laser/ToF autofocus ranging.",
                    checked = phoneAssistanceSensors,
                    onCheckedChange = { scope.launch { repository.setPhoneAssistanceSensors(it) } }
                )
                SettingToggleRow(
                    title = "Save location data",
                    description = "GPS in EXIF.",
                    checked = saveLocationData,
                    onCheckedChange = { state ->
                        if (state) {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        } else {
                            scope.launch { repository.setSaveLocationData(false) }
                        }
                    }
                )
                SettingToggleRow(
                    title = "Enable diagnostics",
                    description = "Create a per-shot diagnostics folder in Documents/BnCamDebug.",
                    checked = enableShotLogger,
                    onCheckedChange = { scope.launch { repository.setEnableShotLogger(it) } }
                )

                if (enableShotLogger) {
                    SettingValueRow(
                        title = "Configure diagnostics",
                        description = "Choose which of the seven physical per-shot files are created.",
                        value = "Edit",
                        onClick = { showShotLoggerConfig = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // ==========================================
    // DIALOGS
    // ==========================================

    if (showPhotoPrefixDialog) {
        TextInputDialog(
            title = "Edit Photo Prefix",
            initialValue = tempInput,
            onDismiss = { showPhotoPrefixDialog = false },
            onSave = {
                scope.launch { repository.setPhotoPrefix(it) }
                showPhotoPrefixDialog = false
            }
        )
    }

    if (showLocationDialog) {
        LocationInputDialog(
            initialValue = tempInput,
            onDismiss = { showLocationDialog = false },
            onSave = {
                scope.launch { repository.setSaveLocation(it) }
                showLocationDialog = false
            }
        )
    }

    if (showShotLoggerConfig) {
        AlertDialog(
            onDismissRequest = { showShotLoggerConfig = false },
            containerColor = CardBackground,
            titleContentColor = Color.White,
            textContentColor = Color.LightGray,
            title = { Text("Configure diagnostics", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "Capture diagnostic detail",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        text = "Every debug-enabled shot creates its own folder in Documents/BnCamDebug with seven stable, phone-readable diagnostics files. These switches control which physical files are created for each shot.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    DialogCheckboxRow("01 · Summary", logSummary) { scope.launch { repository.setLogSummary(it) } }
                    DialogCheckboxRow("02 · Capture", logActiveMode) { scope.launch { repository.setLogActiveMode(it) } }
                    DialogCheckboxRow("03 · Profile settings", logProfileSettings) { scope.launch { repository.setLogProfileSettings(it) } }
                    DialogCheckboxRow("04 · ISP", logPipelineDebug) { scope.launch { repository.setLogPipelineDebug(it) } }
                    DialogCheckboxRow("05 · Warnings & errors", logWarnings) { scope.launch { repository.setLogWarnings(it) } }
                    DialogCheckboxRow("06 · Frame analysis", logFrameAnalysis) { scope.launch { repository.setLogFrameAnalysis(it) } }
                    DialogCheckboxRow("07 · Vendor tag injection", logVendorInjection) { scope.launch { repository.setLogVendorInjection(it) } }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShotLoggerConfig = false }) {
                    Text("DONE", color = AccentPistachio)
                }
            }
        )
    }
}

// ==========================================
// HELPER COMPONENTS
// ==========================================

@Composable
fun TextInputDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var textValue by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBackground,
        title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPistachio,
                    unfocusedBorderColor = Color.DarkGray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = AccentPistachio
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(textValue) }) {
                Text("SAVE", color = AccentPistachio)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color.Gray)
            }
        }
    )
}

@Composable
fun LocationInputDialog(
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var textValue by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBackground,
        title = { Text("Edit Save Location", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentPistachio,
                        unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = AccentPistachio
                    )
                )
                Text(
                    text = "Must start with a public directory like DCIM/ or Pictures/",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(textValue) }) {
                Text("SAVE", color = AccentPistachio)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color.Gray)
            }
        }
    )
}

@Composable
fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title.uppercase(),
            color = AccentPistachio,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        content()
    }
}

@Composable
fun DialogCheckboxRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = AccentPistachio, checkmarkColor = Color.Black)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = text, color = Color.White, fontSize = 14.sp)
    }
}
