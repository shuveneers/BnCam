package com.bncam.ui.screens.settings.lens_profiles

import android.widget.Toast
import com.bncam.BuildConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.core.capture.FrameCapacityPolicy
import com.bncam.core.capture.FrameOrigin
import com.bncam.core.capture.MultiFrameAlignmentRegistry
import com.bncam.core.capture.MultiFrameFusionRegistry
import com.bncam.core.engine.CaptureStrategy
import com.bncam.core.quality.LibpatcherProfileResolver
import com.bncam.core.quality.ProfileCurveDefaults
import com.bncam.core.quality.SpectraProfileCharacterValues
import com.bncam.core.quality.SpectraProfileCharacters
import com.bncam.core.quality.SpectraProfileDefaults
import com.bncam.data.profile.BncProfileCodec
import com.bncam.data.profile.BncProfileDeviceCapabilities
import com.bncam.data.settings.CaptureSettingKeys
import com.bncam.data.settings.ProfileIspKeys
import com.bncam.data.settings.ProfilePlannedDefaults
import com.bncam.data.settings.ProfileSharpnessMethods
import com.bncam.data.settings.ProfileDetailDefaults
import com.bncam.data.settings.ProfileNoiseReductionDefaults
import com.bncam.data.settings.ProfileSettingSpec
import com.bncam.data.settings.ProfileSettingValueType
import com.bncam.data.settings.SettingsRepository
import com.bncam.ui.components.SettingSliderRow
import com.bncam.ui.components.SettingToggleRow
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ProfileSpectraSettingsScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val profileId = "${lensId}_profile_$profileIndex"
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val frameSource by repo.getProfileFrameSourceFlow(profileId)
        .collectAsStateWithLifecycle(initialValue = "YUV")
    val isRawFrameSource = frameSource == "RAW10" || frameSource == "RAW_SENSOR"
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
    val spectraCharacter = SpectraProfileCharacters.infer(
        SpectraProfileCharacterValues(
            dynamicIso = spectraDynamicIso,
            luma = spectraLuma,
            chroma = spectraChroma,
            detailProtection = spectraDetail,
            lowFrequency = spectraLowFrequency
        )
    )

    SettingsTopicScaffold("SPECTRA", onNavigateBack) {
        if (!isRawFrameSource) {
            SettingsCard("Unavailable", "SPECTRA profile steering applies only to RAW10 and RAW_SENSOR profiles.") {
                SettingValueRow("Buffer Type / Pipeline", "Change this from the profile overview.", frameSource) {}
            }
        } else {
            SettingsCard(
                title = "SPECTRA · Context Fusion",
                description = "BnCam sensor intelligence: CFA-domain physical-noise cleanup, multiscale chroma recovery and texture-aware residual control. When On, the adaptive engine runs at full calibrated master authority; the controls below shape the result."
            ) {
                ChoiceSettingRow(
                    title = "SPECTRA",
                    description = "Enable or disable SPECTRA engine processing for this profile.",
                    value = if (spectraEnabled) "On" else "Off",
                    options = listOf("Off", "On"),
                    onSelected = { selected ->
                        scope.launch {
                            repo.setProfileIntOverride(profileId, ProfileIspKeys.SPECTRA_ENABLED, if (selected == "On") 1 else 0)
                        }
                    }
                )
                ChoiceSettingRow(
                    title = "SPECTRA Character",
                    description = "Natural, Clean, Texture and Night are coherent starting points. Changing any SPECTRA slider afterwards becomes Custom; there is no hidden preset state.",
                    value = spectraCharacter,
                    options = SpectraProfileCharacters.names,
                    onSelected = { selected ->
                        SpectraProfileCharacters.byName(selected)?.let { character ->
                            scope.launch {
                                val values = character.values
                                repo.setProfileOverrideBatch(
                                    profileId = profileId,
                                    intValues = mapOf(ProfileIspKeys.SPECTRA_ENABLED to 1),
                                    floatValues = mapOf(
                                        ProfileIspKeys.SPECTRA_DYNAMIC_ISO to values.dynamicIso,
                                        ProfileIspKeys.SPECTRA_LUMA to values.luma,
                                        ProfileIspKeys.SPECTRA_CHROMA to values.chroma,
                                        ProfileIspKeys.SPECTRA_DETAIL to values.detailProtection,
                                        ProfileIspKeys.SPECTRA_LOW_FREQUENCY to values.lowFrequency
                                    )
                                )
                            }
                        }
                    }
                )
                ProfileBoostSlider(
                    repo = repo,
                    profileId = profileId,
                    key = ProfileIspKeys.SPECTRA_DYNAMIC_ISO,
                    title = "Dynamic ISO",
                    description = "Natural starts at 0.45. Higher values add progressively more authority as ISO/noise rises.",
                    enabled = spectraEnabled,
                    defaultValue = SpectraProfileDefaults.DYNAMIC_ISO
                )
                ProfileSignedSlider(
                    repo = repo,
                    profileId = profileId,
                    key = ProfileIspKeys.SPECTRA_LUMA,
                    title = "Luma noise",
                    description = "Changes SPECTRA authority over luminance grain.",
                    enabled = spectraEnabled,
                    defaultValue = SpectraProfileDefaults.LUMA
                )
                ProfileSignedSlider(
                    repo = repo,
                    profileId = profileId,
                    key = ProfileIspKeys.SPECTRA_CHROMA,
                    title = "Chroma noise",
                    description = "Controls CFA and post-demosaic false-colour cleanup. Context Fusion still protects confirmed colour edges.",
                    enabled = spectraEnabled,
                    defaultValue = SpectraProfileDefaults.CHROMA
                )
                ProfileSignedSlider(
                    repo = repo,
                    profileId = profileId,
                    key = ProfileIspKeys.SPECTRA_DETAIL,
                    title = "Detail protection",
                    description = "Positive values retain more fine structure; negative values allow stronger cleanup.",
                    enabled = spectraEnabled,
                    defaultValue = SpectraProfileDefaults.DETAIL_PROTECTION
                )
                ProfileSignedSlider(
                    repo = repo,
                    profileId = profileId,
                    key = ProfileIspKeys.SPECTRA_LOW_FREQUENCY,
                    title = "Low-frequency cleanup",
                    description = "Controls multiscale chroma clouds, blotches and coherent low-frequency sensor noise.",
                    enabled = spectraEnabled,
                    defaultValue = SpectraProfileDefaults.LOW_FREQUENCY
                )
            }
        }

        ResetPageToDefaultValuesButton(
            enabled = isRawFrameSource,
            onReset = {
                scope.launch {
                    repo.clearProfileOverrideValues(
                        profileId,
                        listOf(
                            ProfileSettingSpec(ProfileIspKeys.SPECTRA_ENABLED, ProfileSettingValueType.INT),
                            ProfileSettingSpec(ProfileIspKeys.SPECTRA_DYNAMIC_ISO, ProfileSettingValueType.FLOAT),
                            ProfileSettingSpec(ProfileIspKeys.SPECTRA_LUMA, ProfileSettingValueType.FLOAT),
                            ProfileSettingSpec(ProfileIspKeys.SPECTRA_CHROMA, ProfileSettingValueType.FLOAT),
                            ProfileSettingSpec(ProfileIspKeys.SPECTRA_DETAIL, ProfileSettingValueType.FLOAT),
                            ProfileSettingSpec(ProfileIspKeys.SPECTRA_LOW_FREQUENCY, ProfileSettingValueType.FLOAT)
                        )
                    )
                }
            }
        )
    }
}

@Composable
fun ProfileMultiFrameSettingsScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val profileId = "${lensId}_profile_$profileIndex"
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val captureMode by repo.getProfileCaptureModeFlow(profileId)
        .collectAsStateWithLifecycle(initialValue = CaptureStrategy.SINGLE_FRAME_ZSL)
    val frameSource by repo.getProfileFrameSourceFlow(profileId)
        .collectAsStateWithLifecycle(initialValue = "YUV")
    val alignmentMethod by repo.getProfileMultiFrameAlignmentMethodFlow(profileId)
        .collectAsStateWithLifecycle(initialValue = "Auto")
    val fusionMethod by repo.getProfileMultiFrameFusionMethodFlow(profileId)
        .collectAsStateWithLifecycle(initialValue = "Auto")
    val exposureStrategy by repo.getProfileMultiFrameExposureStrategyFlow(profileId)
        .collectAsStateWithLifecycle(initialValue = "ETTR")
    val jpegFusionFramesYuv by repo.getProfileMultiFrameJpegFusionFramesYuvFlow(profileId)
        .collectAsStateWithLifecycle(initialValue = 8)
    val jpegFusionFramesRaw10 by repo.getProfileMultiFrameJpegFusionFramesRaw10Flow(profileId)
        .collectAsStateWithLifecycle(initialValue = 8)
    val jpegFusionFramesRawSensor by repo.getProfileMultiFrameJpegFusionFramesRawSensorFlow(profileId)
        .collectAsStateWithLifecycle(initialValue = 5)
    val acceptAllFrames by repo.getProfileBoolean(profileId, CaptureSettingKeys.SELECTION_ACCEPT_ALL, false)
        .collectAsStateWithLifecycle(initialValue = false)

    val origin = FrameOrigin.parse(frameSource)
    val alignmentResolution = MultiFrameAlignmentRegistry.resolve(alignmentMethod, origin)
    val fusionResolution = MultiFrameFusionRegistry.resolve(fusionMethod, origin)
    val currentFusionFrames = when (frameSource) {
        "RAW10" -> jpegFusionFramesRaw10
        "RAW_SENSOR" -> jpegFusionFramesRawSensor
        else -> jpegFusionFramesYuv
    }
    val maxFusionFrames = FrameCapacityPolicy.maximumProcessingFrames(origin)
    SettingsTopicScaffold("Multi-frame processing", onNavigateBack) {
        if (captureMode != CaptureStrategy.MULTI_FRAME_ZSL) {
            SettingsCard("Unavailable", "This profile is currently configured for single-frame capture.") {
                SettingValueRow("Capture mode", "Change this from the profile overview.", captureMode.label) {}
            }
        } else {
            SettingsCard("Alignment, merge and fusion", "Profile-specific multi-frame reconstruction controls.") {
                SettingValueRow(
                    title = "Alignment method",
                    description = "Executed route: ${alignmentResolution.resolvedId}.",
                    value = MultiFrameAlignmentRegistry.selectableMethodsPhase5A.single().displayName,
                    onClick = {
                        scope.launch { repo.setProfileMultiFrameAlignmentMethod(profileId, MultiFrameAlignmentRegistry.AUTO.id) }
                    }
                )
                SettingValueRow(
                    title = "Merge / fusion method",
                    description = "Executed route: ${fusionResolution.resolvedId}.",
                    value = MultiFrameFusionRegistry.selectableMethodsPhase5A.single().displayName,
                    onClick = {
                        scope.launch { repo.setProfileMultiFrameFusionMethod(profileId, MultiFrameFusionRegistry.AUTO.id) }
                    }
                )
                ChoiceSettingRow(
                    title = "Exposure strategy",
                    description = "Choose standard exposure or highlight-aware ETTR.",
                    value = exposureStrategy,
                    options = listOf("Standard", "ETTR"),
                    onSelected = { scope.launch { repo.setProfileMultiFrameExposureStrategy(profileId, it) } }
                )
            }

            SettingsCard("Frame counts", "JPEG fusion frame count for this profile. DNG master-frame count is owned by App Settings → Output.") {
                SettingSliderRow(
                    title = "Fusion frame count",
                    description = "$frameSource range: 2–$maxFusionFrames frames. These frames build the computational master used for JPEG rendering.",
                    value = currentFusionFrames.toFloat(),
                    valueRange = 2f..maxFusionFrames.toFloat(),
                    onValueChange = { value ->
                        val next = value.roundToInt().coerceIn(2, maxFusionFrames)
                        scope.launch {
                            when (frameSource) {
                                "RAW10" -> repo.setProfileMultiFrameFusionFramesRaw10(profileId, next)
                                "RAW_SENSOR" -> repo.setProfileMultiFrameFusionFramesRawSensor(profileId, next)
                                else -> repo.setProfileMultiFrameFusionFramesYuv(profileId, next)
                            }
                        }
                    },
                    valueFormatter = { it.roundToInt().toString() }
                )
                SettingToggleRow(
                    title = "Accept all frames (not connected)",
                    description = "Planned frame-selection override. The value is saved in the profile, but MultiFrameRunner does not consume it yet.",
                    checked = acceptAllFrames,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            repo.setProfileBooleanOverride(profileId, CaptureSettingKeys.SELECTION_ACCEPT_ALL, enabled)
                        }
                    }
                )
            }
        }
        ResetPageToDefaultValuesButton(
            enabled = captureMode == CaptureStrategy.MULTI_FRAME_ZSL,
            onReset = {
                scope.launch {
                    repo.setProfileMultiFrameAlignmentMethod(profileId, MultiFrameAlignmentRegistry.AUTO.id)
                    repo.setProfileMultiFrameFusionMethod(profileId, MultiFrameFusionRegistry.AUTO.id)
                    repo.setProfileMultiFrameExposureStrategy(profileId, "ETTR")
                    repo.clearProfileOverrideValue(profileId, CaptureSettingKeys.SELECTION_ACCEPT_ALL, ProfileSettingValueType.BOOLEAN)
                    when (frameSource) {
                        "RAW10" -> repo.setProfileMultiFrameFusionFramesRaw10(profileId, 8)
                        "RAW_SENSOR" -> repo.setProfileMultiFrameFusionFramesRawSensor(profileId, 5)
                        else -> repo.setProfileMultiFrameFusionFramesYuv(profileId, 8)
                    }
                }
            }
        )
    }
}

@Composable
fun ProfileCurveSettingsScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val profileId = "${lensId}_profile_$profileIndex"
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val types = listOf(
        ProfileCurveDefaults.TYPE_TONE to "tone",
        ProfileCurveDefaults.TYPE_GAMMA to "gamma",
        ProfileCurveDefaults.TYPE_SECT to "sect"
    )
    var selectedIndex by remember(profileId) { mutableStateOf(0) }
    var reloadToken by remember(profileId) { mutableStateOf(0) }

    SettingsTopicScaffold("Curves", onNavigateBack) {
        androidx.compose.material3.TabRow(
            selectedTabIndex = selectedIndex,
            containerColor = Color.Transparent,
            contentColor = Color.White
        ) {
            types.forEachIndexed { index, (label, _) ->
                androidx.compose.material3.Tab(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    text = {
                        androidx.compose.material3.Text(
                            text = label,
                            color = if (selectedIndex == index) com.bncam.ui.components.AccentPistachio else Color.Gray
                        )
                    }
                )
            }
        }
        val (_, topicId) = types[selectedIndex]
        ProfileCurveTopicContent(profileId = profileId, topicId = topicId, reloadToken = reloadToken)
        ResetPageToDefaultValuesButton {
            scope.launch {
                val specs = buildList {
                    listOf(
                        ProfileCurveDefaults.TYPE_TONE,
                        ProfileCurveDefaults.TYPE_GAMMA,
                        ProfileCurveDefaults.TYPE_SECT
                    ).forEach { curveType ->
                        add(ProfileSettingSpec(ProfileCurveDefaults.presetKey(curveType), ProfileSettingValueType.STRING))
                        repeat(ProfileCurveDefaults.nodeCount(curveType)) { index ->
                            add(ProfileSettingSpec(ProfileCurveDefaults.pointKey(curveType, index), ProfileSettingValueType.FLOAT))
                        }
                    }
                }
                repo.clearProfileOverrideValues(profileId, specs)
                reloadToken += 1
            }
        }
    }
}

@Composable
fun ProfilePresenceSettingsScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val profileId = "${lensId}_profile_$profileIndex"
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    SettingsTopicScaffold("Color Manager", onNavigateBack) {
        SettingsCard("Color Manager", "Global colour intensity controls. Optical lens calibration remains outside portable profiles.") {
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.PRESENCE_VIBRANCE,
                title = "Vibrance",
                description = "Adjusts colour intensity with stronger protection for already-saturated colours."
            )
            ProfileSignedSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.PRESENCE_SATURATION,
                title = "Saturation",
                description = "Reduces or increases overall colour intensity."
            )
            ProfileRangeSlider(
                repo = repo,
                profileId = profileId,
                key = ProfileIspKeys.PRESENCE_COLOR_FRINGE_SUPPRESSION,
                title = "Color Fringe Suppression (not connected)",
                description = "Planned image-dependent purple/green fringe suppression. True lens-specific chromatic-aberration calibration remains outside portable profiles.",
                defaultValue = ProfilePlannedDefaults.COLOR_FRINGE_SUPPRESSION,
                valueRange = 0f..1f,
                formatter = { String.format(Locale.US, "%.0f", it * 100f) }
            )
        }
        ResetPageToDefaultValuesButton {
            scope.launch {
                repo.clearProfileOverrideValues(
                    profileId,
                    listOf(
                        ProfileSettingSpec(ProfileIspKeys.PRESENCE_VIBRANCE, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.PRESENCE_SATURATION, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.PRESENCE_COLOR_FRINGE_SUPPRESSION, ProfileSettingValueType.FLOAT)
                    )
                )
            }
        }
    }
}

@Composable
fun ProfileDenoiseSettingsScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val profileId = "${lensId}_profile_$profileIndex"
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    SettingsTopicScaffold("Denoise", onNavigateBack) {
        SettingsCard(
            "Denoise",
            "Post-reconstruction luminance and colour noise reduction. SPECTRA remains the independent RAW/CFA physical-noise engine."
        ) {
            ProfileRangeSlider(repo, profileId, ProfileIspKeys.DETAIL_NR_LUMINANCE, "Luminance", "Reduces luminance grain after RAW reconstruction or in the YUV ISP.", ProfileNoiseReductionDefaults.LUMINANCE, 0f..1f, formatter = { String.format(Locale.US, "%.0f", it * 100f) })
            ProfileRangeSlider(repo, profileId, ProfileIspKeys.DETAIL_NR_LUMINANCE_DETAIL, "Luminance Detail", "Balances retained fine texture against luminance smoothing.", ProfileNoiseReductionDefaults.LUMINANCE_DETAIL, 0f..1f, formatter = { String.format(Locale.US, "%.0f", it * 100f) })
            ProfileRangeSlider(repo, profileId, ProfileIspKeys.DETAIL_NR_LUMINANCE_CONTRAST, "Luminance Contrast", "Restores local luminance separation after denoising without acting as global tone contrast.", ProfileNoiseReductionDefaults.LUMINANCE_CONTRAST, 0f..1f, formatter = { String.format(Locale.US, "%.0f", it * 100f) })
            ProfileRangeSlider(repo, profileId, ProfileIspKeys.DETAIL_NR_COLOR, "Color / Chroma", "Reduces chroma speckle independently of SPECTRA's physical chroma model.", ProfileNoiseReductionDefaults.COLOR, 0f..1f, formatter = { String.format(Locale.US, "%.0f", it * 100f) })
            ProfileRangeSlider(repo, profileId, ProfileIspKeys.DETAIL_NR_COLOR_DETAIL, "Color / Chroma Detail", "Controls how strongly fine colour structure is protected from chroma smoothing.", ProfileNoiseReductionDefaults.COLOR_DETAIL, 0f..1f, formatter = { String.format(Locale.US, "%.0f", it * 100f) })
            ProfileRangeSlider(repo, profileId, ProfileIspKeys.DETAIL_NR_COLOR_SMOOTHNESS, "Color / Chroma Smoothness", "Controls spatial smoothness of chroma-noise cleanup in low-detail regions.", ProfileNoiseReductionDefaults.COLOR_SMOOTHNESS, 0f..1f, formatter = { String.format(Locale.US, "%.0f", it * 100f) })
        }
        ResetPageToDefaultValuesButton {
            scope.launch {
                repo.clearProfileOverrideValues(
                    profileId,
                    listOf(
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_NR_LUMINANCE, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_NR_LUMINANCE_DETAIL, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_NR_LUMINANCE_CONTRAST, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_NR_COLOR, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_NR_COLOR_DETAIL, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_NR_COLOR_SMOOTHNESS, ProfileSettingValueType.FLOAT)
                    )
                )
            }
        }
    }
}

@Composable
fun ProfileSharpnessSettingsScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val profileId = "${lensId}_profile_$profileIndex"
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val storedMethod by repo.getProfileString(
        profileId,
        ProfileIspKeys.DETAIL_SHARPENING_METHOD,
        ProfileSharpnessMethods.NORMAL
    ).collectAsStateWithLifecycle(initialValue = ProfileSharpnessMethods.NORMAL)
    val method = ProfileSharpnessMethods.sanitize(storedMethod)

    SettingsTopicScaffold("Sharpness", onNavigateBack) {
        SettingsCard(
            "Sharpening Method",
            "Normal Sharpness and Polysharp are mutually exclusive. Polysharp controls are already part of the profile contract but are marked not connected until the dedicated processing backend is implemented."
        ) {
            ChoiceSettingRow(
                title = "Sharp choice",
                description = "Choose which sharpening family owns the profile. Selecting Polysharp disables the current Normal Sharpness backend.",
                value = method,
                options = ProfileSharpnessMethods.values,
                onSelected = { selected ->
                    scope.launch {
                        repo.setProfileStringOverride(
                            profileId,
                            ProfileIspKeys.DETAIL_SHARPENING_METHOD,
                            ProfileSharpnessMethods.sanitize(selected)
                        )
                    }
                }
            )
        }

        if (method == ProfileSharpnessMethods.NORMAL) {
            SettingsCard(
                "Normal Sharpness",
                "Standalone global output sharpness. Negative values soften the complete image, zero is exact identity, and positive values increase acutance. Other sharpness controls do not scale or gate this slider."
            ) {
                ProfileSignedSlider(
                    repo = repo,
                    profileId = profileId,
                    key = ProfileIspKeys.DETAIL_SHARPENING_AMOUNT,
                    title = "Global Sharpness",
                    description = "Standalone signed global sharpness. -1.00 is deliberately very soft, 0.00 is exact neutral/identity, and +1.00 is deliberately very sharp. The full range is intended to be clearly visible.",
                    defaultValue = ProfileDetailDefaults.AMOUNT,
                    formatter = { value -> String.format(Locale.US, "%+.2f", value) }
                )
                ProfileRangeSlider(
                    repo = repo,
                    profileId = profileId,
                    key = ProfileIspKeys.DETAIL_SHARPENING_EDGE,
                    title = "Edge",
                    description = "Standalone signed structural-edge transition control. -1.00 broadens/smooths qualified contour transitions, 0.00 preserves the natural transition, and +1.00 compresses them into a sharper edge. Neighbour reconstruction suppresses halos/double lines; detected text is excluded.",
                    defaultValue = ProfilePlannedDefaults.EDGE_SHARPNESS,
                    valueRange = -1f..1f,
                    formatter = { value -> String.format(Locale.US, "%+.2f", value) }
                )
                ProfileRangeSlider(
                    repo = repo,
                    profileId = profileId,
                    key = ProfileIspKeys.DETAIL_SHARPENING_DETAIL,
                    title = "Detail",
                    description = "Standalone signed microdetail control. It targets credible fine texture plus narrow natural ridges/creases such as hair, fabric structure, petal veins/seams and fine material grooves. -1.00 reduces them, 0.00 is neutral, and +1.00 enhances them; text and broad structural contours are excluded.",
                    defaultValue = ProfileDetailDefaults.DETAIL,
                    valueRange = -1f..1f,
                    formatter = { value -> String.format(Locale.US, "%+.2f", value) }
                )
                ProfileRangeSlider(
                    repo = repo,
                    profileId = profileId,
                    key = ProfileIspKeys.DETAIL_SHARPENING_LEGIBILITY,
                    title = "Legibility",
                    description = "Text/glyph-only sharpness ownership. -1.00 softens detected letter and symbol strokes, 0.00 preserves their natural transition, and +1.00 sharpens/compresses those strokes. Detected text is excluded from Edge, Detail and Global Sharpness to prevent stacking.",
                    defaultValue = ProfilePlannedDefaults.LEGIBILITY,
                    valueRange = -1f..1f,
                    formatter = { value -> String.format(Locale.US, "%+.2f", value) }
                )
                ProfileRangeSlider(
                    repo = repo,
                    profileId = profileId,
                    key = ProfileIspKeys.DETAIL_SHARPENING_MASKING,
                    title = "Sharp Mask (not connected)",
                    description = "Reserved for a separate masking phase. It does not affect Global Sharpness.",
                    defaultValue = ProfileDetailDefaults.MASKING,
                    valueRange = -1f..1f,
                    formatter = { value -> String.format(Locale.US, "%+.2f", value) }
                )
            }
        } else {
            SettingsCard(
                "Polysharp (not connected)",
                "The complete Polysharp profile surface is reserved and saved now. These controls do not yet alter pixels; Normal Sharpness is disabled while Polysharp is selected."
            ) {
                ProfileRangeSlider(repo, profileId, ProfileIspKeys.POLYSHARP_GAIN, "Sharp Gain (not connected)", "Overall Polysharp authority.", ProfilePlannedDefaults.POLYSHARP_GAIN, 0f..2f)
                ProfileRangeSlider(repo, profileId, ProfileIspKeys.POLYSHARP_MACRO_GAIN, "Sharp Macro Gain (not connected)", "Large-structure sharpening authority.", ProfilePlannedDefaults.POLYSHARP_MACRO_GAIN, 0f..2f)
                ProfileRangeSlider(repo, profileId, ProfileIspKeys.POLYSHARP_MICRO_GAIN, "Sharp Micro Gain (not connected)", "Fine-detail sharpening authority.", ProfilePlannedDefaults.POLYSHARP_MICRO_GAIN, 0f..2f)
                ProfileRangeSlider(repo, profileId, ProfileIspKeys.POLYSHARP_MAX_DETAIL, "Sharpen Max Detail (not connected)", "Planned detail ceiling/smoothing control.", ProfilePlannedDefaults.POLYSHARP_MAX_DETAIL, 0f..1f, formatter = { String.format(Locale.US, "%.0f", it * 100f) })
                ProfileRangeSlider(repo, profileId, ProfileIspKeys.POLYSHARP_RADIUS_SMALL, "Polysharp Radius Small (not connected)", "Small-scale Polysharp radius.", ProfilePlannedDefaults.POLYSHARP_RADIUS_SMALL, 0f..2f)
                ProfileRangeSlider(repo, profileId, ProfileIspKeys.POLYSHARP_RADIUS_MEDIUM, "Polysharp Radius Medium (not connected)", "Medium-scale Polysharp radius.", ProfilePlannedDefaults.POLYSHARP_RADIUS_MEDIUM, 0f..4f)
                ProfileRangeSlider(repo, profileId, ProfileIspKeys.POLYSHARP_RADIUS_LARGE, "Polysharp Radius Large (not connected)", "Large-scale Polysharp radius.", ProfilePlannedDefaults.POLYSHARP_RADIUS_LARGE, 0f..8f)
            }
        }

        ResetPageToDefaultValuesButton {
            scope.launch {
                repo.clearProfileOverrideValues(
                    profileId,
                    listOf(
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_SHARPENING_METHOD, ProfileSettingValueType.STRING),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_SHARPENING_AMOUNT, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_SHARPENING_RADIUS, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_SHARPENING_EDGE, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_SHARPENING_DETAIL, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_SHARPENING_LEGIBILITY, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.DETAIL_SHARPENING_MASKING, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_GAIN, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_MACRO_GAIN, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_MICRO_GAIN, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_MAX_DETAIL, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_RADIUS_SMALL, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_RADIUS_MEDIUM, ProfileSettingValueType.FLOAT),
                        ProfileSettingSpec(ProfileIspKeys.POLYSHARP_RADIUS_LARGE, ProfileSettingValueType.FLOAT)
                    )
                )
            }
        }
    }
}

@Composable
fun ProfileImportExportScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val profileId = "${lensId}_profile_$profileIndex"
    val defaultProfileName = "Profile $profileIndex"
    val profileName by repo.getProfileNameFlow(profileId, defaultProfileName)
        .collectAsStateWithLifecycle(initialValue = defaultProfileName)
    val specs = remember { LibpatcherProfileResolver.allProfileSettingSpecs() }
    var pendingBnc by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BncProfileCodec.MIME_TYPE)
    ) { uri ->
        val contents = pendingBnc
        pendingBnc = null
        if (uri != null && contents != null) {
            scope.launch {
                busy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(contents) }
                            ?: error("Unable to open destination")
                    }
                }.onSuccess {
                    Toast.makeText(context, "Profile exported as .bnc", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(context, "Export failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                runCatching {
                    val raw = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("Unable to read selected file")
                    }
                    val targetCapabilities = withContext(Dispatchers.IO) {
                        BncProfileDeviceCapabilities.inspect(context, lensId)
                    }
                    repo.importProfileBnc(
                        raw = raw,
                        targetProfileId = profileId,
                        specs = specs,
                        targetCapabilities = targetCapabilities
                    )
                }.onSuccess { result ->
                    val fallback = if (result.frameSourceResolution.fallbackOccurred) {
                        " · ${result.frameSourceResolution.requested}→${result.frameSourceResolution.effective}"
                    } else ""
                    val migration = if (result.migrated) " · migrated schema ${result.sourceSchemaVersion}" else ""
                    Toast.makeText(
                        context,
                        "Imported ${result.profileName}: ${result.importedSettings} settings$fallback$migration",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(context, "Import failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
                busy = false
            }
        }
    }

    SettingsTopicScaffold("Profile export/import", onNavigateBack) {
        SettingsCard(
            "Profile export/import",
            "Portable BnCam profile transfer. The profile name and all Profile Settings are preserved; sensor calibration remains owned by the target lens."
        ) {
            SettingValueRow(
                title = "Export profile",
                description = "Save this complete profile as ${BncProfileCodec.FILE_EXTENSION}. Black levels and physical sensor/hardware calibration are never embedded.",
                value = if (busy) "Busy" else "Save .bnc",
                onClick = {
                    if (!busy) {
                        scope.launch {
                            busy = true
                            runCatching {
                                val sourceCapabilities = withContext(Dispatchers.IO) {
                                    BncProfileDeviceCapabilities.inspect(context, lensId)
                                }
                                repo.exportProfileBnc(
                                    profileId = profileId,
                                    sourceStableLensKey = lensId,
                                    defaultProfileName = profileName,
                                    bncamVersion = BuildConfig.VERSION_NAME,
                                    specs = specs,
                                    sourceFrameSources = sourceCapabilities.supportedFrameSources
                                )
                            }.onSuccess { result ->
                                pendingBnc = result.contents
                                exportLauncher.launch(result.suggestedFileName)
                            }.onFailure { error ->
                                Toast.makeText(context, "Export failed: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                            busy = false
                        }
                    }
                }
            )
            SettingValueRow(
                title = "Import profile",
                description = "Load a .bnc into this profile slot. Older BnCam profile JSON remains accepted for backwards compatibility.",
                value = if (busy) "Busy" else "Load .bnc",
                onClick = {
                    if (!busy) {
                        importLauncher.launch(
                            arrayOf(
                                BncProfileCodec.MIME_TYPE,
                                "application/json",
                                "text/json",
                                "text/*",
                                "application/octet-stream"
                            )
                        )
                    }
                }
            )
        }
        ResetPageToDefaultValuesButton(
            enabled = !busy,
            onReset = {
                pendingBnc = null
                busy = false
                Toast.makeText(context, "Page reset", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun ProfileOtherSettingsScreen(
    lensId: String,
    profileIndex: Int,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val profileId = "${lensId}_profile_$profileIndex"
    val scope = rememberCoroutineScope()
    val overridden by repo.isProfileOverrideFlow(profileId, "post_jpeg_quality")
        .collectAsStateWithLifecycle(initialValue = false)
    val stored by repo.getProfileInt(profileId, "post_jpeg_quality", 98)
        .collectAsStateWithLifecycle(initialValue = 98)
    val binding = rememberOptimisticPersistedBinding(
        stableKey = "$profileId:jpeg_quality",
        authoritativeValue = if (overridden) stored.coerceIn(80, 100) else 98,
        persist = { repo.setProfileIntOverride(profileId, "post_jpeg_quality", it.coerceIn(80, 100)) }
    )
    SettingsTopicScaffold("Other settings", onNavigateBack) {
        SettingsCard("JPEG", "Output settings owned by this profile.") {
            SettingSliderRow(
                title = "JPEG quality",
                description = "Final JPEG encoder quality. 98 is the default; 100 is the encoder's maximum quality setting, not a lossless format.",
                value = binding.value.toFloat(),
                valueRange = 80f..100f,
                onValueChange = { binding.update(it.roundToInt().coerceIn(80, 100)) },
                valueFormatter = { String.format(Locale.US, "%.0f", it) }
            )
        }
        ResetPageToDefaultValuesButton {
            scope.launch {
                repo.clearProfileOverrideValue(profileId, "post_jpeg_quality", ProfileSettingValueType.INT)
            }
        }
    }
}



@Composable
internal fun ProfileSignedSlider(
    repo: SettingsRepository,
    profileId: String,
    key: String,
    title: String,
    description: String,
    enabled: Boolean = true,
    defaultValue: Float = 0f,
    formatter: (Float) -> String = { value -> String.format(Locale.US, "%+.2f", value) }
) {
    val overridden by repo.isProfileOverrideFlow(profileId, key)
        .collectAsStateWithLifecycle(initialValue = false)
    val stored by repo.getProfileFloat(profileId, key, defaultValue)
        .collectAsStateWithLifecycle(initialValue = defaultValue)
    val binding = rememberOptimisticPersistedBinding(
        stableKey = "$profileId:$key",
        authoritativeValue = if (overridden) stored.coerceIn(-1f, 1f) else defaultValue.coerceIn(-1f, 1f),
        persist = { repo.setProfileFloatOverride(profileId, key, it.coerceIn(-1f, 1f)) }
    )
    SettingSliderRow(
        title = title,
        description = description,
        value = binding.value,
        valueRange = -1f..1f,
        enabled = enabled,
        onValueChange = binding.update,
        valueFormatter = formatter
    )
}

@Composable
private fun ProfileRangeSlider(
    repo: SettingsRepository,
    profileId: String,
    key: String,
    title: String,
    description: String,
    defaultValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    formatter: (Float) -> String = { value -> String.format(Locale.US, "%.2f", value) }
) {
    val overridden by repo.isProfileOverrideFlow(profileId, key)
        .collectAsStateWithLifecycle(initialValue = false)
    val stored by repo.getProfileFloat(profileId, key, defaultValue)
        .collectAsStateWithLifecycle(initialValue = defaultValue)
    val binding = rememberOptimisticPersistedBinding(
        stableKey = "$profileId:$key",
        authoritativeValue = if (overridden) stored.coerceIn(valueRange) else defaultValue.coerceIn(valueRange),
        persist = { repo.setProfileFloatOverride(profileId, key, it.coerceIn(valueRange)) }
    )
    SettingSliderRow(
        title = title,
        description = description,
        value = binding.value,
        valueRange = valueRange,
        enabled = enabled,
        onValueChange = binding.update,
        valueFormatter = formatter
    )
}

@Composable
private fun ProfileBoostSlider(
    repo: SettingsRepository,
    profileId: String,
    key: String,
    title: String,
    description: String,
    enabled: Boolean = true,
    defaultValue: Float = 0f
) {
    val overridden by repo.isProfileOverrideFlow(profileId, key)
        .collectAsStateWithLifecycle(initialValue = false)
    val stored by repo.getProfileFloat(profileId, key, defaultValue)
        .collectAsStateWithLifecycle(initialValue = defaultValue)
    val binding = rememberOptimisticPersistedBinding(
        stableKey = "$profileId:$key",
        authoritativeValue = if (overridden) stored.coerceIn(0f, 1f) else defaultValue.coerceIn(0f, 1f),
        persist = { repo.setProfileFloatOverride(profileId, key, it.coerceIn(0f, 1f)) }
    )
    SettingSliderRow(
        title = title,
        description = description,
        value = binding.value,
        valueRange = 0f..1f,
        enabled = enabled,
        onValueChange = binding.update,
        valueFormatter = { value -> String.format(Locale.US, "%.2f", value) }
    )
}
