package com.bncam.ui.screens.settings

import com.bncam.core.capture.MeteringMode
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.content.Context
import android.os.Build
import com.bncam.core.engine.OisResolver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.ViewfinderSliderAssignment
import com.bncam.ui.components.SettingToggleRow
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.capture.ViewfinderStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Kleine helper om makkelijk door lijstjes te 'cyclen' als je erop tapt
fun <T> cycle(current: T, options: List<T>): T {
    val index = options.indexOf(current)
    return if (index == -1 || index == options.size - 1) options[0] else options[index + 1]
}

@Composable
fun ViewfinderScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val activeLensId by repository.activeLensIdFlow.collectAsState(initial = null)
    val isOisSupported by produceState(initialValue = true, activeLensId, context) {
        val lensId = activeLensId
        value = if (lensId == null) {
            true
        } else {
            withContext(Dispatchers.IO) {
                val systemCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                OisResolver.isOisOrStabilizationSupported(systemCameraManager, lensId)
            }
        }
    }

    // --- State Ophalen ---
    val viewfinderStream by repository.viewfinderStreamFlow.collectAsState(
        initial = ViewfinderStream.YUV
    )

    // Kaart 1: Composition
    val gridLines by repository.gridLinesFlow.collectAsState(initial = "Off")
    val horizonLeveler by repository.horizonLevelerFlow.collectAsState(initial = false)
    val centerCrosshair by repository.centerCrosshairFlow.collectAsState(initial = false)

    // Kaart 2: Focus
    val focusData by repository.focusDataFlow.collectAsState(initial = false)
    val leftSliderAssignment by repository.leftSliderAssignmentFlow.collectAsState(initial = ViewfinderSliderAssignment.OFF)
    val rightSliderAssignment by repository.rightSliderAssignmentFlow.collectAsState(initial = ViewfinderSliderAssignment.OFF)
    val shutterSlider by repository.shutterSpeedSliderFlow.collectAsState(initial = false)
    val isoSlider by repository.isoSliderFlow.collectAsState(initial = false)


    // NIEUW: Focus Mode & Tracking
    val focusMode by repository.focusModeFlow.collectAsState(initial = "Continuous")
    val facePriorityFocus by repository.facePriorityFocusFlow.collectAsState(initial = false)

    val nearZslFocusSelection by repository.nearZslFocusSelectionFlow.collectAsState(initial = true)

    val focusLock by repository.focusLockFlow.collectAsState(initial = "3s")
    val focusTracking by repository.focusTrackingFlow.collectAsState(initial = false)
    val focusRing by repository.focusRingFlow.collectAsState(initial = true)
    val focusPeak by repository.focusPeakFlow.collectAsState(initial = false)
    val focusPeakColor by repository.focusPeakColorFlow.collectAsState(initial = "Red")
    val resetFocusCapture by repository.resetFocusCaptureFlow.collectAsState(initial = false)

    // Kaart 3: Exposure
    val meteringStyle by repository.meteringStyleFlow.collectAsState(
        initial = MeteringMode.AUTO_DEFAULT_AE.settingValue
    )
    val histogram by repository.histogramFlow.collectAsState(initial = false)

    // Kaart 4: Stabilization & Processing
    val opticalStabilization by repository.opticalStabilizationFlow.collectAsState(initial = true)
    val stabilizationStatus by repository.stabilizationStatusFlow.collectAsState(initial = "Stabilization probing failed")
    val hotPixelMode by repository.hotPixelModeFlow.collectAsState(initial = "Off")
    val noiseReductionHint by repository.noiseReductionHintFlow.collectAsState(initial = "Off")
    val edgeModeHint by repository.edgeModeHintFlow.collectAsState(initial = "Off")
    val tonemapHint by repository.tonemapHintFlow.collectAsState(initial = "Off")
    val antiBanding by repository.antiBandingFlow.collectAsState(initial = "Auto")

    // Kaart 5: Detection
    val faceDetection by repository.faceDetectionFlow.collectAsState(initial = false)
    val qrDetection by repository.qrDetectionFlow.collectAsState(initial = false)

    // Kaart 6: Others
    val doubleTapAction by repository.doubleTapActionFlow.collectAsState(initial = "2x Zoom")
    val mirrorFrontPreview by repository.mirrorFrontPreviewFlow.collectAsState(initial = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // --- TOP BAR ---
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier
                .padding(16.dp)
                .clickable { onNavigateBack() }
        )

        Text(
            text = "Viewfinder",
            color = Color.White,
            fontWeight = FontWeight.Light,
            fontSize = 48.sp,
            letterSpacing = (-1).sp,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)
        )

        // --- SCROLLABLE SETTINGS LIST ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            SettingsCard(title = "Preview") {
                SettingValueRow(
                    "Viewfinder stream",
                    "Choose the source used for the live viewfinder.",
                    viewfinderStream.displayName
                ) {
                    val next = if (viewfinderStream == ViewfinderStream.YUV) {
                        ViewfinderStream.SELECTED_BUFFER
                    } else {
                        ViewfinderStream.YUV
                    }
                    scope.launch { repository.setViewfinderStream(next) }
                }
            }

            // KAART 1: COMPOSITION & GUIDES
            SettingsCard(title = "Composition & Guides") {
                SettingValueRow("Grid lines", "Assist with composition.", gridLines) {
                    val next = cycle(gridLines, listOf("Off", "3x3", "4x4", "Golden Ratio"))
                    scope.launch { repository.setGridLines(next) }
                }
                SettingToggleRow("Horizon leveler", "Virtual spirit level for straight shots.", horizonLeveler) {
                    scope.launch { repository.setHorizonLeveler(it) }
                }
                SettingToggleRow("Center crosshair", "Show a small '+' in the center.", centerCrosshair) {
                    scope.launch { repository.setCenterCrosshair(it) }
                }
            }

            // KAART 2: FOCUS
            SettingsCard(title = "Focus") {
                SettingToggleRow("Near-ZSL focus selection", "Prefer sharpest frame around shutter time.", nearZslFocusSelection) {
                    scope.launch { repository.setNearZslFocusSelection(it) }
                }

                SettingValueRow("Focus mode", "Default behavior when not interacting.", focusMode) {
                    scope.launch { repository.setFocusMode(cycle(focusMode, listOf("Continuous", "Tap-to-Focus"))) }
                }
                SettingToggleRow("Focus data", "Show real-time AF state, lens state, focus confidence and focus distance.", focusData) { scope.launch { repository.setFocusData(it) } }

                ViewfinderChoiceSettingRow(
                    title = "Focus lock",
                    description = "Duration to hold focus and a normal Focus Track target after a tap.",
                    value = focusLock,
                    options = listOf("3s", "5s", "8s", "12s", "20s"),
                    onSelected = { scope.launch { repository.setFocusLock(it) } }
                )
                SettingToggleRow(
                    "Focus Track",
                    "Track the subject selected by touch. Tap tracks for Focus lock time; long-press keeps tracking until the next tap.",
                    focusTracking
                ) { scope.launch { repository.setFocusTracking(it) } }
                SettingToggleRow("Focus ring", "Show halo animation on focus.", focusRing) { scope.launch { repository.setFocusRing(it) } }
                SettingToggleRow("Focus peaking", "Highlight likely in-focus detail using focus confidence and the AF target when available.", focusPeak) { scope.launch { repository.setFocusPeak(it) } }

                if (focusPeak) {
                    SettingValueRow("Peaking color", "Highlight color for peaking.", focusPeakColor) {
                        scope.launch { repository.setFocusPeakColor(cycle(focusPeakColor, listOf("Red", "Green", "Blue", "Yellow"))) }
                    }
                }
                SettingToggleRow("Reset focus after capture", "Return to the configured autofocus mode.", resetFocusCapture) { scope.launch { repository.setResetFocusCapture(it) } }
            }

            // KAART 3: EXPOSURE
            SettingsCard(title = "Exposure") {
                SettingValueRow("Metering style", "Standard Camera2 AE metering: Auto, center-weighted, full-frame average or spot.", meteringStyle) {
                    scope.launch {
                        repository.setMeteringStyle(
                            cycle(meteringStyle, listOf("Auto", "Center Weighted", "Frame Average", "Spot"))
                        )
                    }
                }
                SettingToggleRow("Live histogram", "Show real-time graph.", histogram) { scope.launch { repository.setHistogram(it) } }
            }

            // KAART 4: STABILIZATION & PROCESSING
            SettingsCard(title = "Stabilization & Processing") {
                SettingToggleRow("Optical stabilization (OIS)", stabilizationStatus, opticalStabilization) {
                    scope.launch { repository.setOpticalStabilization(it) }
                }
                SettingValueRow("Hot pixel mode", "Sensor defect correction.", hotPixelMode) {
                    scope.launch { repository.setHotPixelMode(cycle(hotPixelMode, listOf("Off", "Fast", "High Quality"))) }
                }
                ViewfinderChoiceSettingRow(
                    title = "Noise reduction hint",
                    description = "Preview noise processing.",
                    value = noiseReductionHint,
                    options = listOf("Off", "Fast", "High Quality", "Minimal", "ZSL"),
                    onSelected = { scope.launch { repository.setNoiseReductionHint(it) } }
                )
                SettingValueRow("Edge mode hint", "Preview sharpening.", edgeModeHint) {
                    scope.launch { repository.setEdgeModeHint(cycle(edgeModeHint, listOf("Off", "Fast", "High Quality", "ZSL"))) }
                }
                SettingValueRow("Tonemap hint", "Off means no custom curve; Camera2 uses FAST as its required API carrier.", tonemapHint) {
                     scope.launch { repository.setTonemapHint(cycle(tonemapHint, listOf("Off", "Fast", "High Quality", "Contrast Curve"))) }
                }
                SettingValueRow("Anti-banding mode", "Prevent flicker under artificial light.", antiBanding) {
                    scope.launch { repository.setAntiBanding(cycle(antiBanding, listOf("Auto", "50Hz", "60Hz", "Off"))) }
                }
            }

            // --- FLEXIBLE LEFT / RIGHT SLIDERS ---
            SettingsCard(title = "Sliders") {
                SliderAssignmentRow(
                    title = "Left slider",
                    description = "Choose which live control is placed on the left side.",
                    value = leftSliderAssignment,
                    onSelected = { scope.launch { repository.setLeftSliderAssignment(it) } }
                )
                SliderAssignmentRow(
                    title = "Right slider",
                    description = "Choose which live control is placed on the right side.",
                    value = rightSliderAssignment,
                    onSelected = { scope.launch { repository.setRightSliderAssignment(it) } }
                )
                SettingToggleRow(
                    title = "Shutter dial",
                    description = "Show the dedicated shutter arc dial next to the mode selector.",
                    checked = shutterSlider,
                    onCheckedChange = { scope.launch { repository.setShutterSpeedSlider(it) } }
                )
                SettingToggleRow(
                    title = "ISO dial",
                    description = "Show the dedicated ISO arc dial next to the mode selector.",
                    checked = isoSlider,
                    onCheckedChange = { scope.launch { repository.setIsoSlider(it) } }
                )
            }

            // KAART 5: DETECTION
            SettingsCard(title = "Detection") {
                SettingToggleRow("Face detection", "Show face halos and enable face-aware focus features.", faceDetection) { scope.launch { repository.setFaceDetection(it) } }
                if (faceDetection) {
                    SettingToggleRow(
                        "Nearest face priority focus",
                        "Focus the apparently nearest detected face while showing only the face halo, not a second AF ring.",
                        facePriorityFocus
                    ) {
                        scope.launch { repository.setFacePriorityFocus(it) }
                    }
                }
                SettingToggleRow("QR Code detection", "Scan QR codes in preview.", qrDetection) { scope.launch { repository.setQrDetection(it) } }
            }

            // KAART 6: OTHERS
            SettingsCard(title = "Others") {
                SettingValueRow("Double tap action", "Action when tapping screen twice.", doubleTapAction) {
                    scope.launch { repository.setDoubleTapAction(cycle(doubleTapAction, listOf("2x Zoom", "Reset Zoom"))) }
                }
                SettingToggleRow("Mirror front preview", "Match preview with saved photo.", mirrorFrontPreview) { scope.launch { repository.setMirrorFrontPreview(it) } }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}


@Composable
private fun ViewfinderChoiceSettingRow(
    title: String,
    description: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    require(options.isNotEmpty())
    var open by remember(title) { mutableStateOf(false) }
    SettingValueRow(title, description, value) {
        if (options.size <= 4) {
            val index = options.indexOf(value).takeIf { it >= 0 } ?: -1
            onSelected(options[(index + 1).mod(options.size)])
        } else {
            open = true
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(title, color = Color.White) },
            text = {
                Column {
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelected(option)
                                    open = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            RadioButton(selected = option == value, onClick = null)
                            Text(option, color = Color.White, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Cancel", color = Color.Gray) }
            }
        )
    }
}

@Composable
private fun SliderAssignmentRow(
    title: String,
    description: String,
    value: ViewfinderSliderAssignment,
    onSelected: (ViewfinderSliderAssignment) -> Unit
) {
    var open by remember(title) { mutableStateOf(false) }
    SettingValueRow(title, description, value.displayName) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text(title, color = Color.White) },
            text = {
                Column {
                    ViewfinderSliderAssignment.selectable.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelected(option)
                                    open = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            val selected = option == value
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentPistachio,
                                    unselectedColor = Color(0xFF5A5A5A)
                                )
                            )
                            Text(
                                option.displayName,
                                color = if (selected) AccentPistachio else Color.White,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Cancel", color = Color.Gray) }
            }
        )
    }
}
