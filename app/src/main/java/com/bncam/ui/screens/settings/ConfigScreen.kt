package com.bncam.ui.screens.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.bncam.BuildConfig
import com.bncam.core.quality.LibpatcherProfileResolver
import com.bncam.data.profile.BncProfileCodec
import com.bncam.data.profile.BncProfileDeviceCapabilities
import com.bncam.data.settings.SettingsRepository
import com.bncam.ui.components.SettingValueRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConfigScreen(
    activeLensId: String,
    activeProfileId: String,
    activeProfileCount: Int,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val specs = remember { LibpatcherProfileResolver.allProfileSettingSpecs() }
    val activeProfileIndex = remember(activeProfileId) {
        activeProfileId.substringAfterLast("_profile_", "1").toIntOrNull() ?: 1
    }
    val defaultProfileName = "Profile $activeProfileIndex"
    val activeProfileName by repo.getProfileNameFlow(activeProfileId, defaultProfileName)
        .collectAsState(initial = defaultProfileName)
    var pendingExportBnc by remember { mutableStateOf<String?>(null) }
    var showImportConfirm by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BncProfileCodec.MIME_TYPE),
        onResult = { uri ->
            val contents = pendingExportBnc
            pendingExportBnc = null
            if (uri == null || contents == null) return@rememberLauncherForActivityResult
            scope.launch {
                busy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(contents.toByteArray(Charsets.UTF_8))
                        } ?: error("Unable to open output stream")
                    }
                }.onSuccess {
                    Toast.makeText(context, "Profile exported as .bnc", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(context, "Export failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
                busy = false
            }
        }
    )

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) showImportConfirm = uri
        }
    )

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
                .clickable(enabled = !busy) { onNavigateBack() }
        )

        Text(
            text = "Config library",
            color = Color.White,
            fontWeight = FontWeight.Light,
            fontSize = 48.sp,
            letterSpacing = (-1).sp,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsCard(title = "Profile transfer") {
                SettingValueRow(
                    title = "Export active profile",
                    description = "Exports $activeProfileName with every portable Profile Setting. Sensor calibration and black levels remain lens-owned.",
                    value = if (busy) "Busy" else "Save .bnc",
                    onClick = {
                        if (!busy) {
                            scope.launch {
                                busy = true
                                runCatching {
                                    val sourceCapabilities = withContext(Dispatchers.IO) {
                                        BncProfileDeviceCapabilities.inspect(context, activeLensId)
                                    }
                                    repo.exportProfileBnc(
                                        profileId = activeProfileId,
                                        sourceStableLensKey = activeLensId,
                                        defaultProfileName = activeProfileName,
                                        bncamVersion = BuildConfig.VERSION_NAME,
                                        specs = specs,
                                        sourceFrameSources = sourceCapabilities.supportedFrameSources
                                    )
                                }.onSuccess { result ->
                                    pendingExportBnc = result.contents
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
                    title = "Import into active profile",
                    description = "Loads a BnCam .bnc into $activeProfileName. Older BnCam profile JSON is accepted and migrated for backwards compatibility.",
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

            SettingsCard(title = "Scope") {
                SettingValueRow(
                    title = "Active profile",
                    description = activeProfileId,
                    value = activeProfileName,
                    onClick = {}
                )
                SettingValueRow(
                    title = "Saved content",
                    description = "Profile UUID/name, capture mode, preferred frame source, AWB, curves, color, sharpness, Neural Denoise, JPEG, frame-selection and multi-frame settings.",
                    value = ".bnc",
                    onClick = {}
                )
                SettingValueRow(
                    title = "Target ownership",
                    description = "Import changes only the selected profile. The target lens keeps its own black levels, sensor calibration and physical hardware properties.",
                    value = "Portable",
                    onClick = {}
                )
                SettingValueRow(
                    title = "Configured slots",
                    description = "Profile transfer is intentionally one profile per .bnc file; choose another profile slot before importing there.",
                    value = activeProfileCount.coerceIn(1, 12).toString(),
                    onClick = {}
                )
            }
        }
    }

    showImportConfirm?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!busy) showImportConfirm = null },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Import profile?", color = Color.White) },
            text = {
                Text(
                    text = "This replaces the portable settings of the active profile slot. Hardware calibration is not imported. Unsupported frame sources fall back only when the target camera capabilities are known.",
                    color = Color.LightGray
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        runCatching {
                            val raw = withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                                    ?: error("Unable to read selected file")
                            }
                            val targetCapabilities = withContext(Dispatchers.IO) {
                                BncProfileDeviceCapabilities.inspect(context, activeLensId)
                            }
                            repo.importProfileBnc(
                                raw = raw,
                                targetProfileId = activeProfileId,
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
                        showImportConfirm = null
                    }
                }) {
                    Text("Import", color = AccentPistachio, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showImportConfirm = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}
