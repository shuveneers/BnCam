package com.bncam.ui.screens.settings

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bncam.BuildConfig
import com.bncam.CameraEventBus
import com.bncam.core.engine.ImageUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * A deliberately debug-only, session-local control surface.
 * Fresh process startup always keeps experimental luma reconstruction OFF.
 * The production camera's processing and phase-7 kernels are not modified.
 */
private data class Sf2Mode(val stage: Int, val title: String, val description: String, val fileName: String)

private val sf2Modes = listOf(
    Sf2Mode(0, "OFF · chroma baseline", "Original single-frame product path.", "off.jpg"),
    Sf2Mode(1, "Legacy luminance", "Previous luminance reference.", "on.jpg"),
    Sf2Mode(2, "Guided Fine", "Fine-scale guided luminance reconstruction.", "guided.jpg"),
    Sf2Mode(3, "Guided Fine Balanced", "Adaptive flat-noise gain and texture protection (experimental).", "balanced.jpg"),
    Sf2Mode(4, "Guided Fine + Coarse", "Adds low-frequency luminance smoothing.", "combined.jpg"),
    Sf2Mode(5, "Fine + Coarse + Confidence", "Protects supported directional details.", "confidence.jpg")
)
private val sf2GalleryFiles = sf2Modes.map { it.fileName } + "off_repeat.jpg"
private val sf2Accent = Color(0xFFB2D3A8)
private val sf2Card = Color(0xFF1E1E1E)

/** Keeps the chosen stage while navigating back to the live camera; never persists it to disk. */
private object Sf2Session {
    var enabled by mutableStateOf(false)
    var selectedStage by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    var qualificationReport by mutableStateOf<String?>(null)
    var captureFolder by mutableStateOf<File?>(null)
    var error by mutableStateOf<String?>(null)

    fun apply() {
        if (!BuildConfig.DEBUG) return
        val active = if (enabled) selectedStage else 0
        ImageUtils.setPhysicalLumaDebugEnabled(active != 0)
        ImageUtils.setPhysicalLumaStageDebug(active)
    }

    fun reset() {
        enabled = false
        selectedStage = 0
        apply()
    }
}

private fun isQualified(report: String): Boolean =
    report.lineSequence().count { it.startsWith("stage=") } == 7 &&
        report.contains("testedStages=7;") && report.contains("allPassed=true") &&
        !report.contains("allPassed=false")

private fun latestCompletedSf2Folder(parent: File, priorName: String? = null): File? =
    parent.listFiles()?.filter { it.isDirectory && it.name.all(Char::isDigit) &&
        it.name != priorName &&
        File(it, "qualification.txt").readTextIfExists()?.contains("selectedStage.offRepeat=") == true
    }?.maxByOrNull { it.name.toLongOrNull() ?: 0L }

private fun File.readTextIfExists(): String? = runCatching {
    if (isFile) readText() else null
}.getOrNull()

/** Decode a bounded preview only; a seven-image 12MP grid must not exhaust heap memory. */
@Composable
private fun Sf2Photo(file: File, modifier: Modifier = Modifier, maxDimension: Int = 900) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null, file.absolutePath, file.lastModified(), maxDimension
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > maxDimension ||
                    bounds.outHeight / sampleSize > maxDimension) sampleSize *= 2
                BitmapFactory.decodeFile(file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!, contentDescription = file.name,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text("Image unavailable", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

private fun exportSf2ToPictures(context: android.content.Context, dir: File): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        throw IllegalStateException("Export requires Android 10 or newer.")
    }
    var count = 0
    val resolver = context.contentResolver
    for (name in sf2GalleryFiles) {
        val file = File(dir, name)
        if (!file.isFile) continue
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "BnCam_SF2_${dir.name}_$name")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BnCam/SF2 comparisons")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not open gallery for $name.")
        try {
            resolver.openOutputStream(uri)?.use { file.inputStream().use { input -> input.copyTo(it) } }
                ?: throw IllegalStateException("Could not write $name.")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            count++
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
    return count
}

@Composable
fun DeveloperSettingsScreen(
    activeFrameSource: String,
    singleFrame: Boolean,
    onNavigateBack: () -> Unit,
    onOpenCamera: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enlarged by remember { mutableStateOf<File?>(null) }
    var selectedLeft by remember { mutableStateOf("off.jpg") }
    var selectedRight by remember { mutableStateOf("confidence.jpg") }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val eligible = (activeFrameSource == "RAW10" || activeFrameSource == "RAW_SENSOR") && singleFrame

    // Never expose working controls in an accidentally shipped non-debug APK.
    if (!BuildConfig.DEBUG) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Text("Developer settings are only available in debug builds.",
                color = Color.White, modifier = Modifier.padding(24.dp))
        }
        return
    }

    if (enlarged != null) {
        Dialog(onDismissRequest = { enlarged = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(sf2Card).padding(12.dp)) {
                Text(enlarged!!.name, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Sf2Photo(enlarged!!, Modifier.fillMaxWidth().height(440.dp), maxDimension = 2048)
                TextButton(onClick = { enlarged = null }) { Text("Close", color = sf2Accent) }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text("Developer settings", color = Color.White,
                fontWeight = FontWeight.Light, fontSize = 32.sp)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(sf2Card).padding(16.dp)) {
                Text("SINGLE-FRAME RECONSTRUCTION", color = sf2Accent,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Experimental luminance modes are session-only. OFF remains the default.",
                    color = Color.LightGray, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Enable experimental luminance", color = Color.White,
                        modifier = Modifier.weight(1f), fontSize = 15.sp)
                    Switch(checked = Sf2Session.enabled, enabled = !Sf2Session.running,
                        onCheckedChange = { state ->
                            Sf2Session.enabled = state
                            Sf2Session.apply()
                        })
                }
                sf2Modes.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !Sf2Session.running) {
                                Sf2Session.selectedStage = mode.stage
                                Sf2Session.apply()
                            }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = Sf2Session.selectedStage == mode.stage,
                            enabled = !Sf2Session.running,
                            onClick = {
                                Sf2Session.selectedStage = mode.stage
                                Sf2Session.apply()
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = sf2Accent))
                        Column(Modifier.weight(1f)) {
                            Text(mode.title, color = Color.White, fontSize = 15.sp)
                            Text(mode.description, color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
                val active = if (Sf2Session.enabled) Sf2Session.selectedStage else 0
                Text("Active stage: $active ${if (active == 0) "(baseline)" else "(experimental)"}",
                    color = sf2Accent, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Sf2Session.reset() },
                        enabled = !Sf2Session.running) { Text("Reset to OFF", color = sf2Accent) }
                    Button(onClick = onOpenCamera, enabled = !Sf2Session.running,
                        colors = ButtonDefaults.buttonColors(containerColor = sf2Accent)) {
                        Text("Open camera", color = Color.Black)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(sf2Card).padding(16.dp)) {
                Text("COMPARE ALL · SAME RAW", color = sf2Accent,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Generates OFF, Legacy, Fine, Balanced, Fine + Coarse, Confidence and OFF repeat " +
                    "from one immutable RAW master. No seven separate camera exposures.",
                    color = Color.LightGray, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp))
                Text("Current input: $activeFrameSource · " +
                    if (singleFrame) "single-frame" else "multi-frame (not supported)",
                    color = if (eligible) sf2Accent else Color(0xFFFFB6A8), fontSize = 13.sp)
                if (!eligible) {
                    Text("Select RAW10 or RAW_SENSOR and Single frame in your profile first. " +
                        "The mode must output JPEG.",
                        color = Color(0xFFFFB6A8), fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = eligible && !Sf2Session.running,
                    colors = ButtonDefaults.buttonColors(containerColor = sf2Accent),
                    onClick = {
                        scope.launch {
                            Sf2Session.error = null
                            exportResult = null
                            Sf2Session.running = true
                            val parent = File(context.filesDir, "physical_luma_ab")
                            val before = latestCompletedSf2Folder(parent)?.name
                            try {
                                if (CameraEventBus.captureRequests.subscriptionCount.value == 0) {
                                    error("Camera preview is not ready; open the camera first.")
                                }
                                parent.mkdirs()
                                ImageUtils.physicalLumaAbDirectory = parent.absolutePath
                                // The seven-render code selects every stage itself.
                                ImageUtils.setPhysicalLumaStageDebug(0)
                                ImageUtils.setPhysicalLumaDebugEnabled(false)
                                CameraEventBus.requestCapture("sf2_developer_compare")
                                val folder = withContext(Dispatchers.IO) {
                                    var found: File? = null
                                    repeat(360) {
                                        found = latestCompletedSf2Folder(parent, before)
                                        if (found != null) return@withContext found
                                        delay(500)
                                    }
                                    found
                                } ?: error("Timed out waiting for the seven-render set.")
                                val missing = sf2GalleryFiles.filter { !File(folder, it).isFile }
                                if (missing.isNotEmpty()) error("Missing images: " + missing.joinToString())
                                Sf2Session.captureFolder = folder
                            } catch (error: Exception) {
                                Sf2Session.error = error.message ?: error.javaClass.simpleName
                            } finally {
                                ImageUtils.physicalLumaAbDirectory = null
                                Sf2Session.apply()
                                Sf2Session.running = false
                            }
                        }
                    }
                ) { Text("Capture & compare all seven", color = Color.Black) }
                if (Sf2Session.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = sf2Accent, modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Processing on device…", color = Color.White)
                    }
                }
            }

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(sf2Card).padding(16.dp)) {
                Text("GPU / CPU QUALIFICATION", color = sf2Accent,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Runs the seven synthetic GPU cases and validates numerical parity. " +
                    "This does not prove photographic quality.",
                    color = Color.LightGray, fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 10.dp))
                OutlinedButton(enabled = !Sf2Session.running,
                    onClick = {
                        scope.launch {
                            Sf2Session.error = null
                            Sf2Session.running = true
                            try {
                                val report = withContext(Dispatchers.Default) {
                                    ImageUtils.validateSf2IntegrationNative()
                                }
                                Sf2Session.qualificationReport = report
                                File(context.filesDir, "sf2_qualification").apply { mkdirs() }
                                    .resolve("report.txt").writeText(
                                        "buildSha=" + BuildConfig.GIT_REVISION + "\n" + report)
                            } catch (error: Exception) {
                                Sf2Session.qualificationReport = "allPassed=false;reason=" +
                                    (error.message ?: error.javaClass.simpleName)
                            } finally {
                                Sf2Session.apply()
                                Sf2Session.running = false
                            }
                        }
                    }) { Text("Run GPU/CPU validation", color = sf2Accent) }
                Sf2Session.qualificationReport?.let { report ->
                    val pass = isQualified(report)
                    Text(if (pass) "PASS · 7 GPU cases" else "FAIL · inspect report",
                        color = if (pass) sf2Accent else Color(0xFFFFB6A8),
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(report, color = Color.LightGray, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }

            Sf2Session.error?.let { error ->
                Text(error, color = Color(0xFFFFB6A8), fontSize = 13.sp)
            }

            val folder = Sf2Session.captureFolder
            if (folder != null && folder.isDirectory) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(sf2Card).padding(16.dp)) {
                    Text("LAST COMPARISON", color = sf2Accent,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Tap any thumbnail for a larger preview. " +
                        "The seven JPEG files are private until exported.",
                        color = Color.Gray, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 10.dp))
                    sf2GalleryFiles.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { filename ->
                                val file = File(folder, filename)
                                Column(Modifier.weight(1f).padding(bottom = 10.dp)
                                    .clickable { enlarged = file }) {
                                    Text(filename, color = Color.White, fontSize = 11.sp)
                                    Sf2Photo(file, Modifier.fillMaxWidth().height(138.dp))
                                }
                            }
                        }
                    }
                    Text("SIDE-BY-SIDE", color = sf2Accent, fontWeight = FontWeight.Bold,
                        fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            sf2GalleryFiles.forEach { name ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { selectedLeft = name }) {
                                    RadioButton(selectedLeft == name,
                                        onClick = { selectedLeft = name },
                                        colors = RadioButtonDefaults.colors(selectedColor = sf2Accent),
                                        modifier = Modifier.size(34.dp))
                                    Text(name.removeSuffix(".jpg"), color = Color.White,
                                        fontSize = 10.sp)
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            sf2GalleryFiles.forEach { name ->
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { selectedRight = name }) {
                                    RadioButton(selectedRight == name,
                                        onClick = { selectedRight = name },
                                        colors = RadioButtonDefaults.colors(selectedColor = sf2Accent),
                                        modifier = Modifier.size(34.dp))
                                    Text(name.removeSuffix(".jpg"), color = Color.White,
                                        fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Sf2Photo(File(folder, selectedLeft), Modifier.weight(1f).height(240.dp))
                        Sf2Photo(File(folder, selectedRight), Modifier.weight(1f).height(240.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !Sf2Session.running, onClick = {
                        scope.launch {
                            exportResult = try {
                                val count = withContext(Dispatchers.IO) {
                                    exportSf2ToPictures(context, folder)
                                }
                                "Exported $count JPEGs to Pictures/BnCam/SF2 comparisons."
                            } catch (e: Exception) {
                                "Export failed: " + (e.message ?: "unknown")
                            }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = sf2Accent)) {
                        Text("Export seven JPEGs to gallery", color = Color.Black)
                    }
                    exportResult?.let { Text(it, color = Color.LightGray, fontSize = 12.sp) }
                    val qualification = File(folder, "qualification.txt").readTextIfExists()
                    if (qualification != null) {
                        Text("Capture diagnostics", color = sf2Accent,
                            modifier = Modifier.padding(top = 12.dp))
                        Text(qualification, color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
            Text("Debug build only · default processing unchanged · " +
                "experimental stages are not production-qualified.",
                color = Color.Gray, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}
