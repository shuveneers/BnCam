package com.bncam.ui.screens.settings.lens_profiles

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.ImageFormat
import android.content.Context
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.bncam.core.engine.CaptureStrategy
import com.bncam.core.quality.ProfileCurveDefaults
import com.bncam.core.quality.SpectraProfileDefaults
import com.bncam.core.quality.DemosaicMode
import com.bncam.core.quality.LibpatcherControlDef
import com.bncam.core.quality.LibpatcherControlKind
import com.bncam.core.quality.LibpatcherSectionDef
import com.bncam.core.quality.LibpatcherSettingsCatalog
import com.bncam.core.quality.LibpatcherTopicDef
import com.bncam.data.settings.SettingsRepository
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.capture.FrameCapacityPolicy
import com.bncam.data.settings.CaptureSettingKeys
import com.bncam.data.settings.ProfileSettingValueType
import com.bncam.data.settings.ProfileAwbSettings
import com.bncam.data.settings.ProfileIspKeys
import com.bncam.data.settings.ProfileSettingSpec
import com.bncam.ui.components.SettingSliderRow
import com.bncam.ui.components.SettingToggleRow
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import androidx.activity.compose.BackHandler
import com.bncam.ui.components.AccentPistachio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt


@Composable
fun ProfileEditScreen(
    lensId: String,
    profileIndex: Int,
    initialName: String,
    initialMode: CaptureStrategy,
    onSaveName: (String) -> Unit,
    onSaveMode: (CaptureStrategy) -> Unit,
    onNavigateToAwb: () -> Unit = {},
    onNavigateToJpegTuning: () -> Unit = {},
    onNavigateToSpectra: () -> Unit = {},
    onNavigateToShotBias: () -> Unit = {},
    onNavigateToDenoise: () -> Unit = {},
    onNavigateToLightShadow: () -> Unit = {},
    onNavigateToCurves: () -> Unit = {},
    onNavigateToColorManager: () -> Unit = {},
    onNavigateToSharpness: () -> Unit = {},
    onNavigateToProfileTransfer: () -> Unit = {},
    onNavigateToOtherSettings: () -> Unit = {},
    onNavigateToMultiFrame: () -> Unit = {},
    onNavigateBack: () -> Unit
) {
    val profileId = "${lensId}_profile_$profileIndex"

    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val frameSourceSupport by produceState<FrameSourceSupport?>(null, context, lensId) {
        value = withContext(Dispatchers.IO) { scanFrameSourceSupport(context, lensId) }
    }

    val captureMode by repo.getProfileCaptureModeFlow(profileId).collectAsStateWithLifecycle(initialValue = initialMode)
    val profileName by repo.getProfileNameFlow(profileId, initialName).collectAsStateWithLifecycle(initialValue = initialName)
    val persistedPreferredFrame by repo.getProfileFrameSourceFlow(profileId).collectAsStateWithLifecycle(initialValue = "YUV")
    val outputPolicy by repo.outputPolicyFlow.collectAsStateWithLifecycle(initialValue = OutputPolicy.JPEG)
    var preferredFrameUi by remember(profileId) { mutableStateOf("YUV") }
    var pendingPreferredFrameUi by remember(profileId) { mutableStateOf<String?>(null) }
    var selectedTopic by remember(profileId) { mutableStateOf<LibpatcherTopicDef?>(null) }
    val overviewScrollState = rememberScrollState()

    val activeFrameSource = preferredFrameUi
    val resolvedFrameSourceSupport = frameSourceSupport ?: FrameSourceSupport(
        yuvSupported = false,
        raw10Supported = false,
        rawSensorSupported = false,
        rawCapability = false,
        yuvBestSize = null,
        raw10BestSize = null,
        rawSensorBestSize = null
    )
    val renderSettingsApplicable = outputPolicy.producesJpeg
    LaunchedEffect(profileId, persistedPreferredFrame) {
        val pending = pendingPreferredFrameUi
        if (pending == null || persistedPreferredFrame == pending) {
            preferredFrameUi = persistedPreferredFrame
            if (persistedPreferredFrame == pending) pendingPreferredFrameUi = null
        }
    }

    BackHandler(enabled = selectedTopic != null) {
        selectedTopic = null
    }

    var showNameDialog by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showFrameSourceDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .systemBarsPadding()
                    .padding(vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier
                        .padding(16.dp)
                        .size(32.dp)
                        .clickable {
                            if (selectedTopic == null) onNavigateBack() else selectedTopic = null
                        }
                )
                Text(
                    text = selectedTopic?.title ?: "Profile Settings",
                    color = Color.White,
                    fontWeight = FontWeight.Light,
                    fontSize = if (selectedTopic == null) 38.sp else 34.sp,
                    letterSpacing = (-1).sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            if (selectedTopic == null) {
                LibpatcherProfileOverview(
                    profileId = profileId,
                    lensId = lensId,
                    profileName = profileName,
                    captureMode = captureMode,
                    activeFrameSource = activeFrameSource,
                    frameSourceSupport = resolvedFrameSourceSupport,
                    renderSettingsApplicable = renderSettingsApplicable,
                    overviewScrollState = overviewScrollState,
                    onEditName = { showNameDialog = true },
                    onEditMode = { showModeDialog = true },
                    onNavigateToShotBias = onNavigateToShotBias,
                    onEditFrameSource = { showFrameSourceDialog = true },
                    onNavigateToAwb = onNavigateToAwb,
                    onNavigateToSpectra = onNavigateToSpectra,
                    onNavigateToDenoise = onNavigateToDenoise,
                    onNavigateToLightShadow = onNavigateToLightShadow,
                    onNavigateToCurves = onNavigateToCurves,
                    onNavigateToColorManager = onNavigateToColorManager,
                    onNavigateToSharpness = onNavigateToSharpness,
                    onNavigateToProfileTransfer = onNavigateToProfileTransfer,
                    onNavigateToOtherSettings = onNavigateToOtherSettings,
                    onNavigateToMultiFrame = onNavigateToMultiFrame,
                    onOpenTopic = { selectedTopic = it }
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 32.dp)
                ) {
                    LibpatcherTopicScreen(
                        profileId = profileId,
                        topic = selectedTopic!!,
                        captureMode = captureMode,
                        activeFrameSource = activeFrameSource
                    )
                }
            }
        }
    }

    if (showNameDialog) {
        var tempName by remember { mutableStateOf(profileName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = Color(0xFF222222),
            title = { Text("Edit Profile Title", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AccentPistachio,
                        unfocusedBorderColor = Color(0xFF555555)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tempName.isNotBlank()) {
                        onSaveName(tempName)
                    }
                    showNameDialog = false
                }) {
                    Text("Save", color = AccentPistachio, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Capture Mode", color = Color.White) },
            text = {
                Column {
                    CaptureStrategy.entries
                        .filter { it != CaptureStrategy.HDR_ENHANCED }
                        .forEach { strategy ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { repo.setProfileCaptureMode(profileId, strategy) }
                                    onSaveMode(strategy)
                                    showModeDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (captureMode == strategy),
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = AccentPistachio)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(strategy.label, color = Color.White, fontSize = 16.sp)
                                Text(strategy.description, color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) { Text("Cancel", color = Color.Gray) }
            }
        )
    }

    if (showFrameSourceDialog) {
        val options = resolvedFrameSourceSupport.options()
        AlertDialog(
            onDismissRequest = { showFrameSourceDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Buffer Type / Pipeline", color = Color.White) },
            text = {
                Column {
                    Text(
                        text = "Only formats advertised by this active sensor are selectable. Unsupported RAW modes are locked instead of falling back to YUV.",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    )
                    options.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = option.supported) {
                                    preferredFrameUi = option.key
                                    pendingPreferredFrameUi = option.key
                                    scope.launch { repo.setProfileFrameSource(profileId, option.key) }
                                    selectedTopic = null
                                    showFrameSourceDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (activeFrameSource == option.key),
                                onClick = null,
                                enabled = option.supported,
                                colors = RadioButtonDefaults.colors(selectedColor = AccentPistachio)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    option.key,
                                    color = if (option.supported) Color.White else Color.Gray,
                                    fontSize = 16.sp
                                )
                                Text(
                                    option.description,
                                    color = if (option.supported) Color.Gray else Color(0xFF777777),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFrameSourceDialog = false }) { Text("Cancel", color = Color.Gray) }
            }
        )
    }
}

@Composable
private fun LibpatcherProfileOverview(
    profileId: String,
    lensId: String,
    profileName: String,
    captureMode: CaptureStrategy,
    activeFrameSource: String,
    frameSourceSupport: FrameSourceSupport,
    renderSettingsApplicable: Boolean,
    overviewScrollState: androidx.compose.foundation.ScrollState,
    onEditName: () -> Unit,
    onEditMode: () -> Unit,
    onNavigateToShotBias: () -> Unit,
    onEditFrameSource: () -> Unit,
    onNavigateToAwb: () -> Unit,
    onNavigateToSpectra: () -> Unit,
    onNavigateToDenoise: () -> Unit,
    onNavigateToLightShadow: () -> Unit,
    onNavigateToCurves: () -> Unit,
    onNavigateToColorManager: () -> Unit,
    onNavigateToSharpness: () -> Unit,
    onNavigateToProfileTransfer: () -> Unit,
    onNavigateToOtherSettings: () -> Unit,
    onNavigateToMultiFrame: () -> Unit,
    onOpenTopic: (LibpatcherTopicDef) -> Unit
) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val isMultiFrameMode = captureMode == CaptureStrategy.MULTI_FRAME_ZSL
    val isRawFrameSource = activeFrameSource == "RAW10" || activeFrameSource == "RAW_SENSOR"

    val profileAwb by repo.getProfileAwbSettingsFlow(profileId)
        .collectAsStateWithLifecycle(initialValue = ProfileAwbSettings())

    val demosaicOverridden by repo.isProfileOverrideFlow(profileId, DemosaicMode.PROFILE_KEY)
        .collectAsStateWithLifecycle(initialValue = false)
    val demosaicStored by repo.getProfileString(
        profileId,
        DemosaicMode.PROFILE_KEY,
        DemosaicMode.DEFAULT.displayName
    ).collectAsStateWithLifecycle(initialValue = DemosaicMode.DEFAULT.displayName)
    val demosaicMode = if (demosaicOverridden) {
        DemosaicMode.resolveForPhase4(demosaicStored).requestedMode
    } else {
        DemosaicMode.DEFAULT
    }

    val spectraEnabledInt by repo.getProfileInt(profileId, ProfileIspKeys.SPECTRA_ENABLED, 0)
        .collectAsStateWithLifecycle(initialValue = 0)
    val spectraEnabled = spectraEnabledInt == 1
    val spectraDynamicIso by repo.getProfileFloat(profileId, ProfileIspKeys.SPECTRA_DYNAMIC_ISO, SpectraProfileDefaults.DYNAMIC_ISO)
        .collectAsStateWithLifecycle(initialValue = SpectraProfileDefaults.DYNAMIC_ISO)
    val spectraLuma by repo.getProfileFloat(profileId, ProfileIspKeys.SPECTRA_LUMA, SpectraProfileDefaults.LUMA)
        .collectAsStateWithLifecycle(initialValue = SpectraProfileDefaults.LUMA)
    val spectraChroma by repo.getProfileFloat(profileId, ProfileIspKeys.SPECTRA_CHROMA, SpectraProfileDefaults.CHROMA)
        .collectAsStateWithLifecycle(initialValue = SpectraProfileDefaults.CHROMA)
    val spectraDetail by repo.getProfileFloat(profileId, ProfileIspKeys.SPECTRA_DETAIL, SpectraProfileDefaults.DETAIL_PROTECTION)
        .collectAsStateWithLifecycle(initialValue = SpectraProfileDefaults.DETAIL_PROTECTION)
    val spectraLowFrequency by repo.getProfileFloat(profileId, ProfileIspKeys.SPECTRA_LOW_FREQUENCY, SpectraProfileDefaults.LOW_FREQUENCY)
        .collectAsStateWithLifecycle(initialValue = SpectraProfileDefaults.LOW_FREQUENCY)
    val spectraCharacter = com.bncam.core.quality.SpectraProfileCharacters.infer(
        com.bncam.core.quality.SpectraProfileCharacterValues(
            dynamicIso = spectraDynamicIso,
            luma = spectraLuma,
            chroma = spectraChroma,
            detailProtection = spectraDetail,
            lowFrequency = spectraLowFrequency
        )
    )
    val spectraSummary = if (spectraEnabled) "On · $spectraCharacter" else "Off · $spectraCharacter latent"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(overviewScrollState)
            .padding(bottom = 32.dp)
    ) {
        SettingsCard(title = profileName, description = "Linked to lens ID: $lensId") {
            SettingValueRow(
                title = "Profile title",
                description = "Rename for the UI.",
                value = "Edit",
                onClick = onEditName
            )
            SettingValueRow(
                title = "Capture mode",
                description = "Select core shooting behavior.",
                value = captureMode.label,
                onClick = onEditMode
            )
            SettingValueRow(
                title = "Buffer Type / Pipeline",
                description = frameSourceSupport.summaryForUi(),
                value = activeFrameSource,
                onClick = onEditFrameSource
            )
            SettingValueRow(
                title = "Shot Bias",
                description = "Physical capture bias: ISO/time preference, per-frame exposure ceiling and Capture EV bias.",
                value = "Open",
                onClick = onNavigateToShotBias
            )
        }

        if (isMultiFrameMode) {
            SettingsCard(
                title = "Multi-frame processing",
                description = "Alignment, fusion, exposure strategy and frame-count controls for this profile."
            ) {
                SettingValueRow(
                    title = "Multi-frame processing",
                    description = "Configure alignment, merge/fusion, ETTR and accepted frame count.",
                    value = activeFrameSource,
                    onClick = onNavigateToMultiFrame
                )
            }
        }

        if (isRawFrameSource) {
            SettingsCard(
                title = "RAW Processing",
                description = "Sensor-adaptive cleanup and Bayer reconstruction for RAW10 / RAW_SENSOR."
            ) {
                SettingValueRow(
                    title = "SPECTRA",
                    description = "Adaptive physical-noise processing. When enabled, the engine uses its full calibrated master authority; the component controls define its character.",
                    value = spectraSummary,
                    onClick = onNavigateToSpectra
                )
                ChoiceSettingRow(
                    title = "Demosaic",
                    description = "Select BnCam's Bayer reconstruction route.",
                    value = demosaicMode.displayName,
                    options = DemosaicMode.USER_ORDER.map { it.displayName },
                    onSelected = { selected ->
                        val selectedMode = DemosaicMode.fromPersisted(selected) ?: DemosaicMode.DEFAULT
                        scope.launch {
                            repo.setProfileStringOverride(profileId, DemosaicMode.PROFILE_KEY, selectedMode.displayName)
                        }
                    }
                )
            }
        }

        SettingsCard(
            title = "ISP Tuning",
            description = "White balance, denoise, light and shadow, curves, colour and sharpening."
        ) {
            SettingValueRow(
                title = "Auto white balance",
                description = "Configure automatic, reference or manual white balance for this profile.",
                value = profileAwb.summary(),
                onClick = onNavigateToAwb
            )
            SettingValueRow(
                title = "Denoise",
                description = "Luminance and colour noise reduction after reconstruction / in the YUV ISP.",
                value = "Open",
                onClick = onNavigateToDenoise
            )
            SettingValueRow(
                title = "Light & Shadow",
                description = "Exposure, contrast, shadows, highlights, whites and blacks in one tonal workspace.",
                value = "Open",
                onClick = onNavigateToLightShadow
            )
            SettingValueRow(
                title = "Curves",
                description = "Tone, Gamma and Sect response curves with presets and manual node control.",
                value = "Tone · Gamma · Sect",
                onClick = onNavigateToCurves
            )
            SettingValueRow(
                title = "Color Manager",
                description = "Vibrance, saturation and future colour-fringe correction.",
                value = "Open",
                onClick = onNavigateToColorManager
            )
            SettingValueRow(
                title = "Sharpness",
                description = "Dedicated sharpening controls, separate from denoise.",
                value = "Open",
                onClick = onNavigateToSharpness
            )
        }

        SettingsCard(title = "Others", description = "Profile transfer and output-specific controls.") {
            SettingValueRow(
                title = "Profile export/import",
                description = "Save or load the complete portable profile as .bnc.",
                value = ".bnc",
                onClick = onNavigateToProfileTransfer
            )
            SettingValueRow(
                title = "Other settings",
                description = if (renderSettingsApplicable) "JPEG quality and miscellaneous profile output controls." else "Profile output controls.",
                value = "Open",
                onClick = onNavigateToOtherSettings
            )
        }
    }
}

@Composable
private fun libpatcherTopicSummary(
    repo: SettingsRepository,
    profileId: String,
    topic: LibpatcherTopicDef
): String = when (topic.id) {
    "raw_demosaic" -> {
        val overridden by repo.isProfileOverrideFlow(profileId, DemosaicMode.PROFILE_KEY)
            .collectAsStateWithLifecycle(initialValue = false)
        val stored by repo.getProfileString(
            profileId,
            DemosaicMode.PROFILE_KEY,
            DemosaicMode.DEFAULT.displayName
        ).collectAsStateWithLifecycle(initialValue = DemosaicMode.DEFAULT.displayName)
        if (overridden) DemosaicMode.resolveForPhase4(stored).requestedMode.displayName
        else DemosaicMode.DEFAULT.displayName
    }
    "tone" -> {
        val type = curveTypeForTopic(topic.id)
        val key = ProfileCurveDefaults.presetKey(type)
        val overridden by repo.isProfileOverrideFlow(profileId, key)
            .collectAsStateWithLifecycle(initialValue = false)
        val stored by repo.getProfileString(profileId, key, ProfileCurveDefaults.PRESET_DEFAULT)
            .collectAsStateWithLifecycle(initialValue = ProfileCurveDefaults.PRESET_DEFAULT)
        if (overridden) ProfileCurveDefaults.sanitizePreset(stored) else ProfileCurveDefaults.PRESET_DEFAULT
    }
    else -> "Open"
}

@Composable
private fun LibpatcherTopicScreen(
    profileId: String,
    topic: LibpatcherTopicDef,
    captureMode: CaptureStrategy,
    activeFrameSource: String
) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    var showResetConfirm by remember(topic.id) { mutableStateOf(false) }

    Text(
        text = topic.description,
        color = Color.Gray,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )

    if (topic.customScreen == LibpatcherSettingsCatalog.CUSTOM_CURVE_MODULATION) {
        ProfileCurveTopicContent(profileId = profileId, topicId = topic.id)
    } else {
        topic.groups.forEach { group ->
            val visibleControls = LibpatcherSettingsCatalog.visibleControls(topic, group, captureMode, activeFrameSource)
            if (visibleControls.isNotEmpty()) {
                SettingsCard(title = group.title, description = libpatcherGroupDescription(group.title)) {
                    visibleControls.forEach { control ->
                        LibpatcherControlRow(profileId, control, activeFrameSource)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
        RedResetButton(text = "Reset page to default values") {
            showResetConfirm = true
        }
    }
    Spacer(Modifier.height(20.dp))

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Reset this page?", color = Color.White) },
            text = {
                Text(
                    text = "This will reset only the settings on this ${topic.title} page back to their defaults.",
                    color = Color.Gray,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        resetTopicSettingsToDefault(repo, profileId, topic, captureMode, activeFrameSource)
                        Toast.makeText(context, "${topic.title} reset", Toast.LENGTH_SHORT).show()
                    }
                    showResetConfirm = false
                }) {
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

private suspend fun resetTopicSettingsToDefault(
    repo: SettingsRepository,
    profileId: String,
    topic: LibpatcherTopicDef,
    captureMode: CaptureStrategy,
    activeFrameSource: String
) {
    val specs = if (topic.customScreen == LibpatcherSettingsCatalog.CUSTOM_CURVE_MODULATION) {
        val type = curveTypeForTopic(topic.id)
        buildList {
            add(ProfileSettingSpec(ProfileCurveDefaults.presetKey(type), ProfileSettingValueType.STRING))
            repeat(ProfileCurveDefaults.nodeCount(type)) { index ->
                add(ProfileSettingSpec(ProfileCurveDefaults.pointKey(type, index), ProfileSettingValueType.FLOAT))
            }
        }
    } else {
        LibpatcherSettingsCatalog.visibleControls(topic, captureMode, activeFrameSource).mapNotNull { control ->
            when (control.kind) {
                LibpatcherControlKind.BASELINE_SLIDER -> ProfileSettingSpec(
                    control.key,
                    if (control.title == "JPEG Quality") ProfileSettingValueType.INT else ProfileSettingValueType.FLOAT
                )
                LibpatcherControlKind.OPTIONAL_SLIDER -> ProfileSettingSpec(control.key, ProfileSettingValueType.FLOAT)
                LibpatcherControlKind.TOGGLE -> ProfileSettingSpec(control.key, ProfileSettingValueType.BOOLEAN)
                LibpatcherControlKind.ENUM -> ProfileSettingSpec(control.key, ProfileSettingValueType.STRING)
                LibpatcherControlKind.ACTION,
                LibpatcherControlKind.READ_ONLY -> null
            }
        }
    }
    repo.clearProfileOverrideValues(profileId, specs)
}

private fun curveTypeForTopic(topicId: String): String = when (topicId.trim().lowercase(Locale.US)) {
    "gamma" -> ProfileCurveDefaults.TYPE_GAMMA
    "sect", "section" -> ProfileCurveDefaults.TYPE_SECT
    else -> ProfileCurveDefaults.TYPE_TONE
}

private data class CurveEditorUiState(
    val loaded: Boolean,
    val preset: String,
    val nodes: List<Float>
)

@Composable
fun ProfileCurveTopicContent(
    profileId: String,
    topicId: String,
    reloadToken: Int = 0
) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val type = curveTypeForTopic(topicId)
    val presetKey = ProfileCurveDefaults.presetKey(type)
    val defaultNodes = remember(type) { ProfileCurveDefaults.linearNodes(type) }
    val specs = remember(type) {
        buildList {
            add(ProfileSettingSpec(presetKey, ProfileSettingValueType.STRING, ProfileCurveDefaults.PRESET_DEFAULT))
            defaultNodes.forEachIndexed { index, value ->
                add(
                    ProfileSettingSpec(
                        ProfileCurveDefaults.pointKey(type, index),
                        ProfileSettingValueType.FLOAT,
                        value.toString()
                    )
                )
            }
        }
    }
    var editor by remember(profileId, type) {
        mutableStateOf(
            CurveEditorUiState(
                loaded = false,
                preset = ProfileCurveDefaults.PRESET_DEFAULT,
                nodes = defaultNodes
            )
        )
    }

    LaunchedEffect(profileId, type, reloadToken) {
        val snapshot = repo.readProfileSettingsSnapshot(
            profileId = profileId,
            specs = specs,
            includePortableDefaults = true
        )
        val overrideKeys = repo.getProfileOverrideKeys(profileId)
        val loadedPreset = if (presetKey in overrideKeys) {
            ProfileCurveDefaults.sanitizePreset(
                snapshot.stringValues[presetKey] ?: ProfileCurveDefaults.PRESET_DEFAULT
            )
        } else {
            ProfileCurveDefaults.PRESET_DEFAULT
        }
        val loadedNodes = defaultNodes.mapIndexed { index, fallback ->
            val key = ProfileCurveDefaults.pointKey(type, index)
            if (key in overrideKeys) snapshot.floatValues[key]?.coerceIn(0f, 1f) ?: fallback else fallback
        }
        editor = CurveEditorUiState(
            loaded = true,
            preset = loadedPreset,
            nodes = loadedNodes
        )
    }

    fun applyPreset(selected: String) {
        val safe = ProfileCurveDefaults.sanitizePreset(selected)
        val nodes = ProfileCurveDefaults.pointsForPreset(type, safe)
        editor = CurveEditorUiState(loaded = true, preset = safe, nodes = nodes)
        scope.launch {
            repo.setProfileOverrideBatch(
                profileId = profileId,
                floatValues = nodes.mapIndexed { index, value ->
                    ProfileCurveDefaults.pointKey(type, index) to value
                }.toMap(),
                stringValues = mapOf(presetKey to safe)
            )
        }
    }

    fun enterManual() {
        val sourcePreset = editor.preset.takeUnless { it == ProfileCurveDefaults.PRESET_MANUAL }
            ?: ProfileCurveDefaults.PRESET_DEFAULT
        val nodes = if (editor.preset == ProfileCurveDefaults.PRESET_MANUAL) {
            editor.nodes
        } else {
            ProfileCurveDefaults.pointsForPreset(type, sourcePreset)
        }
        editor = CurveEditorUiState(
            loaded = true,
            preset = ProfileCurveDefaults.PRESET_MANUAL,
            nodes = nodes
        )
        scope.launch {
            repo.setProfileOverrideBatch(
                profileId = profileId,
                floatValues = nodes.mapIndexed { index, value ->
                    ProfileCurveDefaults.pointKey(type, index) to value
                }.toMap(),
                stringValues = mapOf(presetKey to ProfileCurveDefaults.PRESET_MANUAL)
            )
        }
    }

    fun updateNode(index: Int, value: Float, persist: Boolean) {
        if (index !in editor.nodes.indices) return
        val safe = value.coerceIn(0f, 1f)
        val next = editor.nodes.toMutableList().also { it[index] = safe }
        editor = editor.copy(
            loaded = true,
            preset = ProfileCurveDefaults.PRESET_MANUAL,
            nodes = next
        )
        if (persist) {
            scope.launch {
                repo.setProfileOverrideBatch(
                    profileId = profileId,
                    floatValues = mapOf(ProfileCurveDefaults.pointKey(type, index) to safe),
                    stringValues = mapOf(presetKey to ProfileCurveDefaults.PRESET_MANUAL)
                )
            }
        }
    }

    fun commitNode(index: Int) {
        val value = editor.nodes.getOrNull(index) ?: return
        scope.launch {
            repo.setProfileOverrideBatch(
                profileId = profileId,
                floatValues = mapOf(ProfileCurveDefaults.pointKey(type, index) to value.coerceIn(0f, 1f)),
                stringValues = mapOf(presetKey to ProfileCurveDefaults.PRESET_MANUAL)
            )
        }
    }

    if (!editor.loaded) {
        SettingsCard(
            title = "Loading curve",
            description = "Loading ${type.lowercase(Locale.US)} nodes from one profile snapshot."
        ) {}
        return
    }

    val curveMode = if (editor.preset == ProfileCurveDefaults.PRESET_MANUAL) "Manual" else "Auto"
    SettingsCard(
        title = "Response",
        description = "The selected response is read when capture starts and applied by the native JPEG renderer."
    ) {
        ChoiceSettingRow(
            title = "Mode",
            description = "Auto uses a calibrated preset; Manual reveals every curve node.",
            value = curveMode,
            options = listOf("Auto", "Manual"),
            onSelected = { selected ->
                if (selected == "Manual") enterManual()
                else if (editor.preset == ProfileCurveDefaults.PRESET_MANUAL) applyPreset(ProfileCurveDefaults.PRESET_DEFAULT)
            }
        )
        ChoiceSettingRow(
            title = "Preset",
            description = "A preset can be selected at any time. Selecting one returns to Auto; choose Custom to fine-tune its nodes.",
            value = if (curveMode == "Manual") "Custom" else editor.preset,
            options = listOf("Custom") + ProfileCurveDefaults.presets.filterNot { it == ProfileCurveDefaults.PRESET_MANUAL },
            onSelected = { selected ->
                if (selected == "Custom") enterManual() else applyPreset(selected)
            }
        )
    }

    if (editor.preset == ProfileCurveDefaults.PRESET_MANUAL) {
        SettingsCard(
            title = "Manual nodes",
            description = "Normalized input/output response nodes. The graph, slider and numeric field share one in-memory editor state and persist only the changed node."
        ) {
            ProfileCurveGridPreview(
                type = type,
                values = editor.nodes,
                onValueChange = { index, value -> updateNode(index, value, persist = false) },
                onValueCommit = { index -> commitNode(index) }
            )
            editor.nodes.forEachIndexed { index, value ->
                PersistedDecimalField(
                    stableKey = "$profileId:${ProfileCurveDefaults.pointKey(type, index)}:input",
                    label = "Node ${index + 1}",
                    authoritativeValue = value.toDouble(),
                    minimum = 0.0,
                    maximum = 1.0,
                    onValidValue = { updateNode(index, it.toFloat(), persist = true) }
                )
                SettingSliderRow(
                    title = "Node ${index + 1}",
                    description = "Input ${String.format(Locale.US, "%.3f", index.toFloat() / (editor.nodes.size - 1).coerceAtLeast(1).toFloat())}",
                    value = value,
                    valueRange = 0f..1f,
                    onValueChange = { updateNode(index, it, persist = false) },
                    onValueChangeFinished = { commitNode(index) },
                    valueFormatter = { String.format(Locale.US, "%.3f", it) }
                )
            }
        }
    }
}

@Composable
private fun ProfileCurveGridPreview(
    type: String,
    values: List<Float>,
    onValueChange: (Int, Float) -> Unit,
    onValueCommit: (Int) -> Unit
) {
    var graphSize by remember(type) { mutableStateOf(IntSize.Zero) }
    var dragIndex by remember(type) { mutableStateOf<Int?>(null) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueCommit by rememberUpdatedState(onValueCommit)
    val nodeCount = values.size

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(Color(0xFF181818), RoundedCornerShape(16.dp))
            .onSizeChanged { graphSize = it }
            .pointerInput(type, nodeCount, graphSize) {
                detectDragGestures(
                    onDragStart = { position ->
                        if (graphSize.width <= 0 || graphSize.height <= 0 || nodeCount <= 0) return@detectDragGestures
                        val index = ((position.x / graphSize.width.toFloat()) * (nodeCount - 1))
                            .roundToInt().coerceIn(0, nodeCount - 1)
                        dragIndex = index
                        val value = (1f - position.y / graphSize.height.toFloat()).coerceIn(0f, 1f)
                        currentOnValueChange(index, value)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val index = dragIndex ?: return@detectDragGestures
                        if (graphSize.height <= 0) return@detectDragGestures
                        val value = (1f - change.position.y / graphSize.height.toFloat()).coerceIn(0f, 1f)
                        currentOnValueChange(index, value)
                    },
                    onDragEnd = {
                        dragIndex?.let(currentOnValueCommit)
                        dragIndex = null
                    },
                    onDragCancel = {
                        dragIndex?.let(currentOnValueCommit)
                        dragIndex = null
                    }
                )
            }
            .padding(12.dp)
    ) {
        val gridColor = Color(0xFF363636)
        repeat(5) { index ->
            val fraction = index / 4f
            drawLine(gridColor, Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), 1f)
            drawLine(gridColor, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), 1f)
        }
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = if (values.size <= 1) 0f else size.width * index.toFloat() / (values.size - 1).toFloat()
            val y = size.height * (1f - value.coerceIn(0f, 1f))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(AccentPistachio, radius = 4.dp.toPx(), center = Offset(x, y))
        }
        drawPath(path, AccentPistachio, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun displayTitleFor(control: LibpatcherControlDef): String {
    return if (LibpatcherSettingsCatalog.isTemporaryActiveIspControl(control.title)) {
        "${control.title} (active)"
    } else {
        control.title
    }
}


private fun libpatcherGroupDescription(title: String): String = when (title) {
    "Output Quality" -> "Controls final JPEG encoder quality."
    else -> "Controls for this runtime profile stage."
}

@Composable
private fun LibpatcherControlRow(
    profileId: String,
    control: LibpatcherControlDef,
    activeFrameSource: String
) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val signedFormatter: (Float) -> String = {
        if (it > 0f) String.format(Locale.US, "+%.2f", it) else String.format(Locale.US, "%.2f", it)
    }

    val displayTitle = displayTitleFor(control)

    when (control.kind) {
        LibpatcherControlKind.BASELINE_SLIDER -> {
            if (control.title == "JPEG Quality") {
                val isOverride by repo.isProfileOverrideFlow(profileId, "post_jpeg_quality")
                    .collectAsStateWithLifecycle(initialValue = false)
                val rawValue by repo.getProfileInt(profileId, "post_jpeg_quality", 98)
                    .collectAsStateWithLifecycle(initialValue = 98)
                val value = if (isOverride) rawValue else 98
                val binding = rememberOptimisticPersistedBinding(
                    stableKey = "$profileId:post_jpeg_quality",
                    authoritativeValue = value.coerceIn(80, 100),
                    persist = { repo.setProfileIntOverride(profileId, "post_jpeg_quality", it.coerceIn(80, 100)) }
                )
                SettingSliderRow(
                    title = displayTitle,
                    description = control.description,
                    value = binding.value.toFloat(),
                    valueRange = 80f..100f,
                    onValueChange = { binding.update(it.roundToInt()) },
                    valueFormatter = { it.roundToInt().toString() }
                )
            } else {
                val isOverride by repo.isProfileOverrideFlow(profileId, control.key)
                    .collectAsStateWithLifecycle(initialValue = false)
                val rawValue by repo.getProfileFloat(profileId, control.key, 0f)
                    .collectAsStateWithLifecycle(initialValue = 0f)
                val value = if (isOverride) rawValue.coerceIn(-1f, 1f) else 0f
                val binding = rememberOptimisticPersistedBinding(
                    stableKey = "$profileId:${control.key}",
                    authoritativeValue = value,
                    persist = { repo.setProfileFloatOverride(profileId, control.key, it.coerceIn(-1f, 1f)) }
                )
                SettingSliderRow(
                    title = displayTitle,
                    description = control.description,
                    value = binding.value,
                    valueRange = -1f..1f,
                    onValueChange = binding.update,
                    valueFormatter = signedFormatter
                )
            }
        }
        LibpatcherControlKind.OPTIONAL_SLIDER -> {
            val isOverride by repo.isProfileOverrideFlow(profileId, control.key)
                .collectAsStateWithLifecycle(initialValue = false)
            val rawValue by repo.getProfileFloat(profileId, control.key, 0f)
                .collectAsStateWithLifecycle(initialValue = 0f)
            val value = if (isOverride) rawValue.coerceIn(0f, 1f) else 0f
            val binding = rememberOptimisticPersistedBinding(
                stableKey = "$profileId:${control.key}",
                authoritativeValue = value,
                persist = { repo.setProfileFloatOverride(profileId, control.key, it.coerceIn(0f, 1f)) }
            )
            SettingSliderRow(
                title = displayTitle,
                description = control.description,
                value = binding.value,
                valueRange = 0f..1f,
                onValueChange = binding.update,
                valueFormatter = { String.format(Locale.US, "%.2f", it) }
            )
        }
        LibpatcherControlKind.TOGGLE -> {
            val isOverride by repo.isProfileOverrideFlow(profileId, control.key)
                .collectAsStateWithLifecycle(initialValue = false)
            val rawValue by repo.getProfileBoolean(profileId, control.key, false)
                .collectAsStateWithLifecycle(initialValue = false)
            val value = isOverride && rawValue
            val binding = rememberOptimisticPersistedBinding(
                stableKey = "$profileId:${control.key}",
                authoritativeValue = value,
                persist = { repo.setProfileBooleanOverride(profileId, control.key, it) }
            )
            SettingToggleRow(
                title = displayTitle,
                description = control.description,
                checked = binding.value,
                onCheckedChange = binding.update
            )
        }
        LibpatcherControlKind.ENUM -> {
            val isOverride by repo.isProfileOverrideFlow(profileId, control.key)
                .collectAsStateWithLifecycle(initialValue = false)
            val defaultValue = control.defaultValue.ifBlank { "Default" }
            val rawValue by repo.getProfileString(profileId, control.key, defaultValue)
                .collectAsStateWithLifecycle(initialValue = defaultValue)
            val value = if (isOverride && rawValue in control.enumOptions) rawValue else defaultValue
            val binding = rememberOptimisticPersistedBinding(
                stableKey = "$profileId:${control.key}",
                authoritativeValue = value,
                persist = { repo.setProfileStringOverride(profileId, control.key, it) }
            )
            var showOptionsDialog by remember(profileId, control.key) { mutableStateOf(false) }
            SettingValueRow(
                title = displayTitle,
                description = control.description,
                value = binding.value,
                onClick = { showOptionsDialog = true }
            )
            if (showOptionsDialog) {
                AlertDialog(
                    onDismissRequest = { showOptionsDialog = false },
                    containerColor = Color(0xFF1E1E1E),
                    title = { Text(control.title, color = Color.White) },
                    text = {
                        Column {
                            control.enumOptions.forEach { option ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            binding.update(option)
                                            showOptionsDialog = false
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = binding.value == option,
                                        onClick = null,
                                        colors = RadioButtonDefaults.colors(selectedColor = AccentPistachio)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(option, color = Color.White, fontSize = 16.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showOptionsDialog = false }) {
                            Text("Cancel", color = Color.Gray)
                        }
                    }
                )
            }
        }
        LibpatcherControlKind.ACTION -> {
            SettingValueRow(
                title = displayTitle,
                description = control.description,
                value = "Not wired",
                onClick = {}
            )
        }
        LibpatcherControlKind.READ_ONLY -> {
            SettingValueRow(
                title = displayTitle,
                description = control.description,
                value = "Pending",
                onClick = {}
            )
        }
    }
}

private data class FrameSourceUiOption(
    val key: String,
    val description: String,
    val supported: Boolean
)

private data class FrameSourceSupport(
    val yuvSupported: Boolean,
    val raw10Supported: Boolean,
    val rawSensorSupported: Boolean,
    val rawCapability: Boolean,
    val yuvBestSize: Size?,
    val raw10BestSize: Size?,
    val rawSensorBestSize: Size?
) {
    fun isSupported(source: String): Boolean {
        return when (source.uppercase(Locale.US)) {
            "RAW_SENSOR" -> rawSensorSupported
            "RAW10" -> raw10Supported
            else -> yuvSupported
        }
    }

    fun summaryForUi(): String {
        val available = mutableListOf<String>()
        if (yuvSupported) available += "YUV"
        if (raw10Supported) available += "RAW10"
        if (rawSensorSupported) available += "RAW_SENSOR"
        val availableText = if (available.isEmpty()) "none" else available.joinToString()
        return "Available on this sensor: $availableText. Unsupported RAW modes are locked; no YUV fallback."
    }

    fun options(): List<FrameSourceUiOption> {
        return listOf(
            FrameSourceUiOption(
                key = "YUV",
                description = if (yuvSupported) {
                    "YUV_420_888, warm-buffer target ${FrameCapacityPolicy.YUV_WARM_BUFFER_TARGET} frames, processing max ${FrameCapacityPolicy.YUV_PROCESSING_MAXIMUM}${sizeSuffix(yuvBestSize)}"
                } else {
                    "Unavailable: this sensor does not advertise YUV_420_888 output."
                },
                supported = yuvSupported
            ),
            FrameSourceUiOption(
                key = "RAW10",
                description = if (raw10Supported) {
                    "10-bit packed Bayer, warm-buffer target ${FrameCapacityPolicy.RAW10_WARM_BUFFER_TARGET} frames, processing max ${FrameCapacityPolicy.RAW10_PROCESSING_MAXIMUM}${sizeSuffix(raw10BestSize)}"
                } else if (!rawCapability) {
                    "Unavailable: this sensor does not advertise Android RAW capability."
                } else {
                    "Unavailable: this sensor does not advertise RAW10 output sizes."
                },
                supported = raw10Supported
            ),
            FrameSourceUiOption(
                key = "RAW_SENSOR",
                description = if (rawSensorSupported) {
                    "16-bit RAW sensor, warm-buffer target ${FrameCapacityPolicy.RAW_SENSOR_WARM_BUFFER_TARGET} frames, processing max ${FrameCapacityPolicy.RAW_SENSOR_PROCESSING_MAXIMUM}${sizeSuffix(rawSensorBestSize)}"
                } else if (!rawCapability) {
                    "Unavailable: this sensor does not advertise Android RAW capability."
                } else {
                    "Unavailable: this sensor has RAW capability but no RAW_SENSOR output size."
                },
                supported = rawSensorSupported
            )
        )
    }
}

private fun sizeSuffix(size: Size?): String {
    return size?.let { " (${it.width} x ${it.height})" } ?: ""
}

private fun scanFrameSourceSupport(context: Context, lensId: String): FrameSourceSupport {
    return try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(lensId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val rawCapability = capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
        val raw10Sizes = map?.getOutputSizes(ImageFormat.RAW10).orEmpty()
        val rawSensorSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty()

        FrameSourceSupport(
            yuvSupported = yuvSizes.isNotEmpty(),
            raw10Supported = rawCapability && raw10Sizes.isNotEmpty(),
            rawSensorSupported = rawCapability && rawSensorSizes.isNotEmpty(),
            rawCapability = rawCapability,
            yuvBestSize = yuvSizes.maxByOrNull { it.width * it.height },
            raw10BestSize = raw10Sizes.maxByOrNull { it.width * it.height },
            rawSensorBestSize = rawSensorSizes.maxByOrNull { it.width * it.height }
        )
    } catch (t: Throwable) {
        FrameSourceSupport(
            yuvSupported = false,
            raw10Supported = false,
            rawSensorSupported = false,
            rawCapability = false,
            yuvBestSize = null,
            raw10BestSize = null,
            rawSensorBestSize = null
        )
    }
}


// ====================================================================
// PRESERVED CURVE EDITOR SUPPORT
// ====================================================================

@Composable
private fun RedResetButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B1A1A))
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.Bold)
    }
}
