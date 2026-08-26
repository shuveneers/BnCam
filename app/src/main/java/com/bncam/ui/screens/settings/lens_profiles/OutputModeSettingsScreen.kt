package com.bncam.ui.screens.settings.lens_profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.core.capture.FrameCapacityPolicy
import com.bncam.core.capture.FrameOrigin
import com.bncam.data.profile.SettingImpact
import com.bncam.data.settings.DngSourcePolicy
import com.bncam.data.settings.OutputModeDngConfig
import com.bncam.data.settings.OutputModeSettings
import com.bncam.data.settings.SettingsRepository
import kotlinx.coroutines.launch

@Composable
fun OutputModeSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsRepo = remember(context) { SettingsRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val outputModeSettings by settingsRepo.getOutputModeSettingsFlow().collectAsState(initial = OutputModeSettings())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(scrollState)
            .systemBarsPadding()
            .padding(bottom = 48.dp)
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
            text = "App DNG Fusion Settings",
            color = Color.White,
            fontWeight = FontWeight.Light,
            fontSize = 32.sp,
            letterSpacing = (-1).sp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 4.dp)
        )

        Text(
            text = "Application-level DNG fusion settings per output mode. Independent from ISP profiles.",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        )

        // --- MODE 1: JPEG ONLY ---
        OutputModeCard(
            title = "JPEG Output Mode",
            description = "Single or multi-frame JPEG capture. DNG fusion controls do not apply."
        ) {
            Text(
                text = "JPEG processing frame counts and base-frame bias are controlled by the active per-lens ISP profile in Group A.",
                color = Color.LightGray,
                fontSize = 13.sp
            )
        }

        // --- MODE 2: RAW + JPEG ---
        OutputModeCard(
            title = "RAW + JPEG Output Mode",
            description = "Master DNG frame count and RAW fusion policy."
        ) {
            DngModeControlSection(
                config = outputModeSettings.rawPlusJpeg,
                onConfigChanged = { updated ->
                    coroutineScope.launch {
                        settingsRepo.setOutputModeSettings(
                            outputModeSettings.copy(rawPlusJpeg = updated, rawOnly = updated)
                        )
                    }
                }
            )
        }

        // --- MODE 3: RAW ONLY ---
        OutputModeCard(
            title = "RAW-only Output Mode",
            description = "Standalone RAW capture master DNG fusion settings."
        ) {
            DngModeControlSection(
                config = outputModeSettings.rawOnly,
                onConfigChanged = { updated ->
                    coroutineScope.launch {
                        settingsRepo.setOutputModeSettings(
                            outputModeSettings.copy(rawOnly = updated, rawPlusJpeg = updated)
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun DngModeControlSection(
    config: OutputModeDngConfig,
    onConfigChanged: (OutputModeDngConfig) -> Unit
) {
    val maxDngMaster = FrameCapacityPolicy.maximumDngMasterFrames(FrameOrigin.RAW10)
    val effectiveDng = if (config.dngSourcePolicy == DngSourcePolicy.ANCHOR_RAW) 1 else config.dngMasterFrameCount.coerceIn(1, maxDngMaster)
    val reason = if (config.dngMasterFrameCount > maxDngMaster) {
        "Clamped to runtime safe maximum $maxDngMaster"
    } else "Configured count within limit"

    var expandedPolicy by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.65f)) {
                Text("DNG Source Policy", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = if (config.dngSourcePolicy == DngSourcePolicy.ANCHOR_RAW) "ANCHOR_RAW (Single frame RAW)" else "FUSED_RAW (Multi-frame fused RAW)",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                ImpactBadge(if (config.dngSourcePolicy == DngSourcePolicy.FUSED_RAW) SettingImpact.MASTER_DNG_PIXELS else SettingImpact.DNG_METADATA)
            }

            Box {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF222222))
                        .clickable { expandedPolicy = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(config.dngSourcePolicy.name, color = Color.White, fontSize = 13.sp)
                }

                DropdownMenu(
                    expanded = expandedPolicy,
                    onDismissRequest = { expandedPolicy = false },
                    modifier = Modifier.background(Color(0xFF222222))
                ) {
                    DngSourcePolicy.values().forEach { policy ->
                        DropdownMenuItem(
                            text = { Text(policy.name, color = Color.White) },
                            onClick = {
                                onConfigChanged(config.copy(dngSourcePolicy = policy))
                                expandedPolicy = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.fillMaxWidth(0.65f)) {
                Text("Configured DNG Master Frames", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = "Configured: ${config.dngMasterFrameCount}  |  Runtime Max: $maxDngMaster  |  Effective: $effectiveDng",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Text(text = "Resolution Reason: $reason", color = Color.DarkGray, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                ImpactBadge(SettingImpact.MASTER_DNG_FRAME_SELECTION)
            }

            OutlinedTextField(
                value = config.dngMasterFrameCount.toString(),
                onValueChange = { str ->
                    str.toIntOrNull()?.let { count -> onConfigChanged(config.copy(dngMasterFrameCount = count.coerceIn(1, 25))) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(72.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFC5E1A5),
                    unfocusedBorderColor = Color.DarkGray
                )
            )
        }
    }
}

@Composable
private fun OutputModeCard(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.DarkGray.copy(alpha = 0.3f))
            .padding(16.dp)
    ) {
        Text(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = description, color = Color.Gray, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun ImpactBadge(impact: SettingImpact) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2C3E50))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = impact.dutchBadge, color = Color(0xFF81D4FA), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}
