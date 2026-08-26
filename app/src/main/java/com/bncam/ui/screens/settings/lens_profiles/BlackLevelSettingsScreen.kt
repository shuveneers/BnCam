package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.core.quality.CfaArrangementDescriptor
import com.bncam.core.quality.LensCalibrationTelemetry
import com.bncam.data.settings.LensBlackLevelSettings
import com.bncam.data.settings.LensHardwareTuningModes
import com.bncam.data.settings.SettingsRepository
import com.bncam.ui.components.SettingValueRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BlackLevelSettingsScreen(lensId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val sensorInfo by produceState(
        initialValue = LensStaticSensorInfo(CfaArrangementDescriptor.from(-1), null, emptyList()),
        context,
        lensId
    ) {
        value = withContext(Dispatchers.IO) { readLensStaticSensorInfo(context, lensId) }
    }
    val authoritative by repo.getLensBlackLevelSettingsFlow(lensId)
        .collectAsStateWithLifecycle(initialValue = LensBlackLevelSettings())
    val telemetry by LensCalibrationTelemetry.flow(lensId).collectAsStateWithLifecycle()
    val binding = rememberOptimisticPersistedBinding("$lensId:black_level", authoritative) {
        repo.setLensBlackLevelSettings(lensId, it)
    }
    val settings = binding.value
    // Snapshot the delegated sensor state once so sealed-type smart casts remain stable
    // for the full composition branch below.
    val cfa = sensorInfo.cfa
    val labels = cfa.inputLabels()
    val autoSource = when {
        telemetry?.blackLevelSource?.contains("DYNAMIC_BLACK_LEVEL") == true -> "Dynamic metadata"
        telemetry?.blackLevelSource?.contains("BLACK_LEVEL_PATTERN") == true -> "Static-pattern fallback"
        telemetry != null -> "Safe fallback warning"
        else -> "Dynamic metadata"
    }

    SettingsTopicScaffold("Black Level", onNavigateBack) {
        SettingsCard("Mode", "Auto follows frame metadata. Manual overrides developed RAW JPEG for this lens.") {
            ChoiceSettingRow("Black level mode", "Per-lens hardware setting.", settings.mode, listOf("Auto", "Manual")) {
                binding.update(settings.copy(mode = it))
            }
            SettingValueRow("Reset to Auto", "Return immediately to automatic resolution.", "Reset") {
                binding.update(settings.copy(mode = LensHardwareTuningModes.AUTO))
            }
        }

        SettingsCard("Automatic resolution", "Priority: same-frame dynamic metadata, validated optical-black provider when available, static pattern, safe warning fallback.") {
            BlackTelemetryValue("Source", "Latest production source.", telemetry?.blackLevelSource ?: "Awaiting a captured frame")
            SettingValueRow("CFA arrangement", "Manual labels match the actual sensor tile.", cfa.cfaName) {}
            BlackTelemetryValue(
                title = "Current values",
                subtitle = "Floating point values before native RAW normalization.",
                value = telemetry?.blackLevels?.joinToString(" / ") { formatExact(it.toDouble()) }
                    ?: sensorInfo.staticBlackLevels.takeIf { it.isNotEmpty() }?.joinToString(" / ") { formatExact(it.toDouble()) }
                    ?: "Awaiting metadata",
                monospaced = true
            )
            BlackTelemetryValue(
                title = "White level",
                subtitle = telemetry?.whiteLevelSource ?: "SENSOR_INFO_WHITE_LEVEL",
                value = (telemetry?.whiteLevel ?: sensorInfo.whiteLevel)?.toString() ?: "Unavailable"
            )
            telemetry?.blackFallbackReason?.let { reason ->
                BlackTelemetryValue("Fallback reason", "Optical-black fallback is used only when a validated provider exists.", reason)
            }
        }

        SettingsCard("Manual values", "Four decimal values in the actual CFA mosaic order; no integer rounding occurs in developed RAW JPEG.") {
            when (cfa) {
                is CfaArrangementDescriptor.Bayer -> labels.forEachIndexed { index, label ->
                    PersistedDecimalField(
                        stableKey = "$lensId:black_$index",
                        label = "$label black",
                        authoritativeValue = settings.values[index],
                        minimum = 0.0,
                        maximum = 65534.0,
                        onValidValue = { value ->
                            val next = settings.values.toMutableList().also { it[index] = value }
                            binding.update(settings.copy(values = next))
                        }
                    )
                }
                is CfaArrangementDescriptor.Monochrome -> PersistedDecimalField(
                    stableKey = "$lensId:black_mono",
                    label = "Mono black",
                    authoritativeValue = settings.values[0],
                    minimum = 0.0,
                    maximum = 65534.0,
                    onValidValue = { value ->
                        binding.update(settings.copy(values = List(4) { value }))
                    }
                )
                is CfaArrangementDescriptor.Unsupported -> Text(
                    cfa.rejectionReason + ". The layout is not mapped to RGGB.",
                    color = Color(0xFFFFA0A0),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }

        Text(
            "Current summary: ${settings.summary(autoSource)}",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun BlackTelemetryValue(
    title: String,
    subtitle: String,
    value: String,
    monospaced: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .background(Color(0xFF242424), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(subtitle, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Text(value, color = Color.White, fontSize = 13.sp, fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.Default)
    }
}
