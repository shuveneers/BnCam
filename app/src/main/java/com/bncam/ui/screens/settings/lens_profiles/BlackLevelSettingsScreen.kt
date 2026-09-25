package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bncam.core.quality.CfaArrangementDescriptor
import com.bncam.data.settings.BlackLevelSettingsStore
import com.bncam.data.settings.BlackLevelTypes
import com.bncam.data.settings.LensBlackLevelControlSettings
import com.bncam.data.settings.sanitizeBlackLevelDynamicStrength
import com.bncam.ui.components.SettingSliderRow
import com.bncam.ui.screens.settings.SettingsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun BlackLevelSettingsScreen(lensId: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { BlackLevelSettingsStore(context) }
    val sensorInfo by produceState(
        initialValue = LensStaticSensorInfo(CfaArrangementDescriptor.from(-1), null, emptyList()),
        context,
        lensId
    ) {
        value = withContext(Dispatchers.IO) { readLensStaticSensorInfo(context, lensId) }
    }
    val authoritative by store.settingsFlow(lensId)
        .collectAsStateWithLifecycle(initialValue = LensBlackLevelControlSettings())
    val binding = rememberOptimisticPersistedBinding("$lensId:black_level_v2", authoritative) {
        store.set(lensId, it)
    }
    val settings = binding.value
    val cfa = sensorInfo.cfa
    val labels = cfa.inputLabels()
    val slotPositions = listOf("[00]", "[10]", "[01]", "[11]")

    SettingsTopicScaffold("Black Level", onNavigateBack) {
        SettingsCard(
            title = "Black level",
            description = "Developed RAW black-level authority for this Lens ID."
        ) {
            ChoiceSettingRow(
                title = "Black level type",
                description = "System uses exact-frame Camera2 black metadata when available, with the static sensor pattern only as fallback. Dynamic lets you deliberately blend static to same-frame black. Manual uses four CFA values.",
                value = settings.type,
                options = BlackLevelTypes.values,
                onSelected = { selected ->
                    if (selected == BlackLevelTypes.MANUAL && !settings.manualInitialized) {
                        val staticSeed = sensorInfo.staticBlackLevels
                            .takeIf { it.size >= 4 }
                            ?.take(4)
                            ?.map { it.toDouble() }
                            ?.takeIf { values -> values.all { it.isFinite() && it >= 0.0 } }
                        binding.update(
                            settings.copy(
                                type = BlackLevelTypes.MANUAL,
                                manualValues = staticSeed ?: settings.manualValues,
                                manualInitialized = true
                            )
                        )
                    } else {
                        binding.update(settings.copy(type = selected))
                    }
                }
            )

            if (settings.type == BlackLevelTypes.DYNAMIC) {
                SettingSliderRow(
                    title = "Strength",
                    description = "0.00 = static sensor pattern. 1.00 = full same-frame dynamic black. Intermediate values blend per CFA site.",
                    value = settings.dynamicStrength,
                    valueRange = 0f..1f,
                    onValueChange = {
                        binding.update(
                            settings.copy(
                                dynamicStrength = sanitizeBlackLevelDynamicStrength(it)
                            )
                        )
                    },
                    valueFormatter = { String.format(Locale.US, "%.2f", it) }
                )
            }
        }

        if (settings.type == BlackLevelTypes.MANUAL) {
            SettingsCard(
                title = "Manual black levels",
                description = "Four-channel input in physical sensor mosaic order [00, 10, 01, 11]. Values remain floating point."
            ) {
                when (cfa) {
                    is CfaArrangementDescriptor.Bayer -> labels.forEachIndexed { index, label ->
                        PersistedDecimalField(
                            stableKey = "$lensId:black_v2_$index",
                            label = "Channel $index · $label ${slotPositions[index]}",
                            authoritativeValue = settings.manualValues[index],
                            minimum = 0.0,
                            maximum = 65534.0,
                            onValidValue = { value ->
                                val next = settings.manualValues.toMutableList().also { it[index] = value }
                                binding.update(
                                    settings.copy(
                                        manualValues = next,
                                        manualInitialized = true
                                    )
                                )
                            }
                        )
                    }
                    is CfaArrangementDescriptor.Monochrome -> PersistedDecimalField(
                        stableKey = "$lensId:black_v2_mono",
                        label = "Mono black",
                        authoritativeValue = settings.manualValues[0],
                        minimum = 0.0,
                        maximum = 65534.0,
                        onValidValue = { value ->
                            binding.update(
                                settings.copy(
                                    manualValues = List(4) { value },
                                    manualInitialized = true
                                )
                            )
                        }
                    )
                    is CfaArrangementDescriptor.Unsupported -> Text(
                        text = cfa.rejectionReason + ". Manual values are not silently remapped to RGGB.",
                        color = Color(0xFFFFA0A0),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                }
            }
        }

        ResetPageToDefaultValuesButton {
            binding.update(LensBlackLevelControlSettings())
        }
    }
}
