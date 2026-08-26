package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.core.quality.CfaArrangementDescriptor
import com.bncam.core.quality.CfaState
import com.bncam.core.quality.LensCalibrationTelemetry
import com.bncam.core.quality.SensorNoiseCalibrationMapper
import com.bncam.data.settings.LensHardwareTuningModes
import com.bncam.data.settings.LensNoiseModelSettings
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.StableLensKey
import com.bncam.data.settings.sanitizeDynamicIsoCoefficient
import com.bncam.ui.components.SettingSliderRow
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun NoiseModelSettingsScreen(
    lensId: String,
    onNavigateBack: () -> Unit,
    onNavigateToManualNoiseModel: () -> Unit
) {
    val context = LocalContext.current
    val stableKey = remember(lensId) { StableLensKey.fromString(lensId) }
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val cfaState by produceState<CfaState>(initialValue = CfaState.Loading, context, lensId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val info = readLensStaticSensorInfo(context, lensId)
                when (val desc = info.cfa) {
                    is CfaArrangementDescriptor.Bayer -> CfaState.Available(desc)
                    is CfaArrangementDescriptor.Monochrome -> CfaState.Unsupported("MONO")
                    is CfaArrangementDescriptor.Unsupported -> CfaState.Unsupported(desc.rejectionReason)
                }
            }.getOrElse { CfaState.Unavailable("metadata missing") }
        }
    }
    val cfaName = when (val s = cfaState) {
        is CfaState.Available -> s.descriptor.cfaName
        is CfaState.Loading -> "Loading…"
        is CfaState.Unsupported -> s.reason
        is CfaState.Unavailable -> s.reason
    }

    val authoritative by repo.getLensNoiseModelSettingsFlow(stableKey)
        .collectAsStateWithLifecycle(initialValue = LensNoiseModelSettings())
    val telemetry by LensCalibrationTelemetry.flow(stableKey.value).collectAsStateWithLifecycle()
    val autoSource = telemetry?.noiseSource
    val binding = rememberOptimisticPersistedBinding("${stableKey.value}:noise_model", authoritative) {
        repo.setLensNoiseModelSettings(stableKey, it)
    }
    val settings = binding.value
    val dynamicIsoCoeff by repo.getDynamicIsoCoeffFlow(stableKey.value)
        .collectAsStateWithLifecycle(initialValue = 0.0f)
    val dynamicIsoBinding = rememberOptimisticPersistedBinding(
        stableKey = "${stableKey.value}:dynamic_iso_coeff",
        authoritativeValue = dynamicIsoCoeff.coerceIn(0f, 1f),
        persist = { repo.setDynamicIsoCoeff(stableKey.value, it.coerceIn(0f, 1f)) }
    )
    SettingsTopicScaffold("SPECTRA Noise model", onNavigateBack) {
        SettingsCard("SPECTRA Noise model", "BnCam's noise model adaptation") {
            ChoiceSettingRow(
                title = "Noise model",
                description = "BnCam's noise model adaptation",
                value = if (settings.mode == LensHardwareTuningModes.AUTO) "On" else settings.mode,
                options = listOf(LensHardwareTuningModes.OFF, "On", LensHardwareTuningModes.MANUAL),
                onSelected = { selected ->
                    val storedMode = if (selected == "On") LensHardwareTuningModes.AUTO else selected
                    binding.update(settings.copy(mode = storedMode))
                }
            )

            if (settings.mode == LensHardwareTuningModes.MANUAL) {
                SettingValueRow(
                    title = "Manual noise model",
                    description = "Edit the sensor-specific S/O input values.",
                    value = settings.summary(cfaName, autoSource),
                    onClick = onNavigateToManualNoiseModel
                )
            }

            SettingSliderRow(
                title = "Dynamic ISO",
                description = "0.00 is neutral. Higher values increase SPECTRA authority as ISO rises.",
                value = dynamicIsoBinding.value,
                valueRange = 0f..1f,
                onValueChange = { dynamicIsoBinding.update(sanitizeDynamicIsoCoefficient(it)) },
                valueFormatter = { String.format(Locale.US, "%.2f", it) }
            )
        }
        ResetPageToDefaultValuesButton {
            binding.update(LensNoiseModelSettings())
            dynamicIsoBinding.update(0.0f)
        }
    }
}

@Composable
fun AdvancedSensorNoiseCalibrationScreen(lensId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stableKey = remember(lensId) { StableLensKey.fromString(lensId) }
    val repo = remember(context) { SettingsRepository(context) }

    val cfaStateDisplay by produceState<String>(initialValue = "Channel propagation — Loading…", context, lensId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val cfa = readLensStaticSensorInfo(context, lensId).cfa
                when (cfa) {
                    is CfaArrangementDescriptor.Bayer -> "Automatic · ${cfa.cfaName} · R/Gr/Gb/B"
                    is CfaArrangementDescriptor.Monochrome -> "Unsupported · MONO"
                    is CfaArrangementDescriptor.Unsupported -> "Unsupported · ${cfa.rejectionReason}"
                }
            }.getOrElse { "Unavailable · metadata missing" }
        }
    }

    val calibAdj by repo.getNoiseModelCalibrationAdjustmentFlow(stableKey).collectAsStateWithLifecycle(initialValue = 0.0f)
    val chromaAdj by repo.getDynamicChromaAuthorityAdjustmentFlow(stableKey).collectAsStateWithLifecycle(initialValue = 0.0f)
    val lumaAdj by repo.getDynamicLumaAuthorityAdjustmentFlow(stableKey).collectAsStateWithLifecycle(initialValue = 0.0f)

    val calibBinding = rememberOptimisticPersistedBinding(
        stableKey = "${stableKey.value}:noise_calibration_adj",
        authoritativeValue = calibAdj,
        persist = { repo.setNoiseModelCalibrationAdjustment(stableKey, it) }
    )
    val chromaBinding = rememberOptimisticPersistedBinding(
        stableKey = "${stableKey.value}:chroma_authority_adj",
        authoritativeValue = chromaAdj,
        persist = { repo.setDynamicChromaAuthorityAdjustment(stableKey, it) }
    )
    val lumaBinding = rememberOptimisticPersistedBinding(
        stableKey = "${stableKey.value}:luma_authority_adj",
        authoritativeValue = lumaAdj,
        persist = { repo.setDynamicLumaAuthorityAdjustment(stableKey, it) }
    )

    val effectiveFactor = SensorNoiseCalibrationMapper.noiseModelCalibrationFactor(calibBinding.value.toDouble())
    val effectiveChromaStops = SensorNoiseCalibrationMapper.effectiveChromaAuthorityStops(chromaBinding.value.toDouble())
    val effectiveLumaStops = SensorNoiseCalibrationMapper.effectiveLumaAuthorityStops(lumaBinding.value.toDouble())

    SettingsTopicScaffold("Advanced Sensor Noise Calibration", onNavigateBack) {
        SettingsCard("Noise model calibration", "Per-sensor gain correcting systematic physical noise under/overestimation.") {
            SettingSliderRow(
                title = "Noise model calibration",
                description = String.format(Locale.US, "Effective factor: %.2fx", effectiveFactor),
                value = calibBinding.value,
                valueRange = -1f..1f,
                onValueChange = { calibBinding.update(it.coerceIn(-1f, 1f)) },
                valueFormatter = { String.format(Locale.US, "%.2f", it) }
            )
        }

        SettingsCard("Dynamic chroma authority", "Maximum Dynamic ISO chroma noise expansion stops for this physical sensor.") {
            SettingSliderRow(
                title = "Dynamic chroma authority",
                description = String.format(Locale.US, "Effective maximum: %.2f stops", effectiveChromaStops),
                value = chromaBinding.value,
                valueRange = -1f..1f,
                onValueChange = { chromaBinding.update(it.coerceIn(-1f, 1f)) },
                valueFormatter = { String.format(Locale.US, "%.2f", it) }
            )
        }

        SettingsCard("Dynamic luma authority", "Maximum Dynamic ISO luma noise expansion stops for this physical sensor.") {
            SettingSliderRow(
                title = "Dynamic luma authority",
                description = String.format(Locale.US, "Effective maximum: %.2f stops", effectiveLumaStops),
                value = lumaBinding.value,
                valueRange = -1f..1f,
                onValueChange = { lumaBinding.update(it.coerceIn(-1f, 1f)) },
                valueFormatter = { String.format(Locale.US, "%.2f", it) }
            )
        }

        SettingsCard("Channel propagation", "Read-only CFA color channel resolution.") {
            SettingValueRow(
                title = "Channel propagation",
                description = "Derived from static sensor info.",
                value = cfaStateDisplay,
                onClick = {}
            )
        }

        ResetPageToDefaultValuesButton {
            scope.launch {
                repo.resetAdvancedSensorNoiseCalibration(stableKey)
                calibBinding.update(0.0f)
                chromaBinding.update(0.0f)
                lumaBinding.update(0.0f)
            }
        }
    }
}

@Composable
fun ManualNoiseModelSettingsScreen(
    lensId: String,
    onNavigateBack: () -> Unit,
    onNavigateToAdvancedCalibration: () -> Unit = {}
) {
    val context = LocalContext.current
    val stableKey = remember(lensId) { StableLensKey.fromString(lensId) }
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val cfaState by produceState<CfaState>(initialValue = CfaState.Loading, context, lensId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val info = readLensStaticSensorInfo(context, lensId)
                when (val desc = info.cfa) {
                    is CfaArrangementDescriptor.Bayer -> CfaState.Available(desc)
                    is CfaArrangementDescriptor.Monochrome -> CfaState.Unsupported("MONO")
                    is CfaArrangementDescriptor.Unsupported -> CfaState.Unsupported(desc.rejectionReason)
                }
            }.getOrElse { CfaState.Unavailable("metadata missing") }
        }
    }
    val cfa = (cfaState as? CfaState.Available)?.descriptor
    val labels = cfa?.inputLabels().orEmpty()
    val authoritative by repo.getLensNoiseModelSettingsFlow(stableKey)
        .collectAsStateWithLifecycle(initialValue = LensNoiseModelSettings())
    val telemetry by LensCalibrationTelemetry.flow(stableKey.value).collectAsStateWithLifecycle()
    val binding = rememberOptimisticPersistedBinding("${stableKey.value}:manual_noise_model", authoritative) {
        repo.setLensNoiseModelSettings(stableKey, it)
    }
    val settings = binding.value

    SettingsTopicScaffold("Manual Noise Model", onNavigateBack) {
        SettingsCard("Resolved information", "Latest production calibration for this lens.") {
            SettingValueRow("CFA arrangement", "Actual sensor mosaic order.", cfa?.cfaName ?: "Loading…") {}
            SettingValueRow("Source", "Effective production S/O source.", telemetry?.noiseSource ?: "Awaiting capture") {}
            NoiseTelemetryValues(
                telemetry?.noiseValues?.chunked(2)?.joinToString("\n") { pair ->
                    pair.joinToString(prefix = "[", postfix = "]") { formatExact(it) }
                } ?: "Awaiting capture"
            )
            telemetry?.noiseFallbackReason?.let { NoiseTelemetryReason(it) }
        }

        SettingsCard("Manual S/O values", "Finite non-negative decimal or scientific notation, persisted with Double precision.") {
            if (cfa is CfaArrangementDescriptor.Bayer) {
                labels.forEachIndexed { channel, label ->
                    listOf("S" to channel * 2, "O" to channel * 2 + 1).forEach { (coefficient, index) ->
                        PersistedDecimalField(
                            stableKey = "${stableKey.value}:noise_${channel}_${coefficient.lowercase()}",
                            label = "$label $coefficient",
                            authoritativeValue = settings.values[index],
                            minimum = 0.0,
                            maximum = Double.MAX_VALUE,
                            onValidValue = { value ->
                                val next = settings.values.toMutableList().also { it[index] = value }
                                binding.update(settings.copy(values = next))
                            }
                        )
                    }
                }
            } else if (cfa is CfaArrangementDescriptor.Monochrome) {
                listOf("S" to 0, "O" to 1).forEach { (coefficient, index) ->
                    PersistedDecimalField(
                        stableKey = "${stableKey.value}:noise_mono_${coefficient.lowercase()}",
                        label = "Mono $coefficient",
                        authoritativeValue = settings.values[index],
                        minimum = 0.0,
                        maximum = Double.MAX_VALUE,
                        onValidValue = { value ->
                            val next = settings.values.toMutableList().also { it[index] = value }
                            binding.update(settings.copy(values = next))
                        }
                    )
                }
            } else if (cfa is CfaArrangementDescriptor.Unsupported) {
                val unsupported = cfa as CfaArrangementDescriptor.Unsupported
                Text(
                    unsupported.rejectionReason + ". Values are not silently mapped to RGGB.",
                    color = Color(0xFFFFA0A0),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            } else {
                Text(
                    "Channel propagation loading or unavailable…",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }

        SettingsCard("Advanced calibration", "Sensor-level SPECTRA authority calibration.") {
            SettingValueRow(
                title = "Advanced sensor noise calibration",
                description = "Calibrate physical S/O gain and dynamic chroma/luma authority.",
                value = "Open",
                onClick = onNavigateToAdvancedCalibration
            )
        }

        ResetPageToDefaultValuesButton(
            enabled = cfa is CfaArrangementDescriptor.Bayer || cfa is CfaArrangementDescriptor.Monochrome,
            onReset = {
                scope.launch {
                    binding.update(
                        LensNoiseModelSettings(
                            mode = LensHardwareTuningModes.MANUAL,
                            values = List(8) { 0.0 }
                        )
                    )
                }
            }
        )
    }
}

@Composable
private fun NoiseTelemetryValues(values: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .background(Color(0xFF242424), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text("Latest S/O", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("variance = S * x + O", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Text(values, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun NoiseTelemetryReason(reason: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .background(Color(0xFF242424), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text("Fallback reason", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text("Why an automatic source needed a fallback.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Text(reason, color = Color.White, fontSize = 13.sp)
    }
}
