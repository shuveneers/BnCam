package com.bncam.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import com.bncam.ui.components.SettingToggleRow
import com.bncam.ui.components.SettingValueRow
import kotlinx.coroutines.launch

@Composable
fun WatermarkScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    // EXIF State
    val signature by repository.watermarkSignatureFlow.collectAsState(initial = "")
    val exifSaveSignature by repository.exifSaveSignatureFlow.collectAsState(initial = false)
    val exifExtraData by repository.exifExtraDataFlow.collectAsState(initial = false)

    // Watermark State
    val wmEnabled by repository.watermarkEnabledFlow.collectAsState(initial = false)
    val wmStyle by repository.watermarkStyleFlow.collectAsState(initial = "Off")
    val wmAddAuthorTopRight by repository.watermarkAddAuthorTopRightFlow.collectAsState(initial = false)

    // Dialog State
    var showSignatureDialog by remember { mutableStateOf(false) }
    var tempSignature by remember { mutableStateOf("") }

    val accentPistachio = Color(0xFF93C572)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // TOP BAR
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White,
            modifier = Modifier
                .padding(16.dp)
                .clickable { onNavigateBack() }
        )

        Text(
            text = "Watermark & EXIF",
            color = Color.White,
            fontWeight = FontWeight.Light,
            fontSize = 48.sp,
            lineHeight = 48.sp,
            letterSpacing = (-1).sp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
        )

        // SCROLLABLE LIST
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // KAART 1: EXIF METADATA
            SettingsCard(title = "EXIF Metadata") {
                SettingValueRow(
                    title = "Author signature",
                    description = "Used for EXIF and specific visual stamps.",
                    value = signature.ifEmpty { "None" },
                    onClick = {
                        tempSignature = signature
                        showSignatureDialog = true
                    }
                )

                SettingToggleRow(
                    title = "Place signature to EXIF",
                    description = "Write author name invisibly into photo details.",
                    checked = exifSaveSignature,
                    onCheckedChange = { scope.launch { repository.setExifSaveSignature(it) } }
                )

                SettingToggleRow(
                    title = "Enable extra EXIF data",
                    description = "Include Camera ID, Framecount, and processing info.",
                    checked = exifExtraData,
                    onCheckedChange = { scope.launch { repository.setExifExtraData(it) } }
                )
            }

            // KAART 2: WATERMARK
            SettingsCard(title = "Watermark") {
                SettingToggleRow(
                    title = "Enable visual stamp",
                    description = "Draw text or graphics over or under the photo.",
                    checked = wmEnabled,
                    onCheckedChange = { scope.launch { repository.setWatermarkEnabled(it) } }
                )

                AnimatedVisibility(
                    visible = wmEnabled,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = Color.DarkGray.copy(alpha = 0.5f)
                        )

                        SettingValueRow(
                            title = "Template style",
                            description = "Choose the overall layout.",
                            value = wmStyle,
                            onClick = {
                                val next = cycle(wmStyle, listOf(
                                    "Off",
                                    "BnCam original",
                                    "Device + EXIF",
                                    "80s Film Date",
                                    "Creative frame",
                                    "Signature stamp"
                                ))
                                scope.launch { repository.setWatermarkStyle(next) }
                            }
                        )

                        SettingToggleRow(
                            title = "Add Author signature to photo",
                            description = "Place name in the top right corner, regardless of template.",
                            checked = wmAddAuthorTopRight,
                            onCheckedChange = { scope.launch { repository.setWatermarkAddAuthorTopRight(it) } }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // SIGNATURE DIALOG
    if (showSignatureDialog) {
        AlertDialog(
            onDismissRequest = { showSignatureDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Author Signature", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = tempSignature,
                    onValueChange = { tempSignature = it },
                    placeholder = { Text("e.g. Shot by John Doe", color = Color.Gray) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = accentPistachio,
                        cursorColor = accentPistachio
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.setWatermarkSignature(tempSignature.trim()) }
                    showSignatureDialog = false
                }) {
                    Text("Save", color = accentPistachio, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignatureDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}