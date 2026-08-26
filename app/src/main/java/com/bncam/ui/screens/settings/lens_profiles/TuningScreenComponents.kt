package com.bncam.ui.screens.settings.lens_profiles

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.core.quality.CfaArrangementDescriptor
import com.bncam.core.quality.ColorPlane
import com.bncam.ui.components.AccentPistachio
import com.bncam.ui.components.SettingValueRow
import java.util.Locale

@Composable
internal fun SettingsTopicScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = Color.Black,
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
                    modifier = Modifier.padding(16.dp).size(32.dp).clickable(onClick = onNavigateBack)
                )
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Light,
                    fontSize = 34.sp,
                    letterSpacing = (-1).sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 32.dp)
        ) { content() }
    }
}


@Composable
internal fun ResetPageToDefaultValuesButton(
    enabled: Boolean = true,
    onReset: () -> Unit
) {
    Button(
        onClick = onReset,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF2C2C2C),
            disabledContainerColor = Color(0xFF1F1F1F)
        )
    ) {
        Text(
            text = "Reset page to default values",
            color = if (enabled) AccentPistachio else Color.Gray,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
internal fun ChoiceSettingRow(
    title: String,
    description: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var open by remember(title) { mutableStateOf(false) }
    SettingValueRow(title, description, value) {
        if (options.size <= 4 && options.isNotEmpty()) {
            val current = options.indexOf(value).takeIf { it >= 0 } ?: 0
            onSelected(options[(current + 1) % options.size])
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    options.forEach { option ->
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSelected(option)
                                open = false
                            }.padding(12.dp)
                        ) {
                            RadioButton(
                                selected = option == value,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = AccentPistachio)
                            )
                            Text(option, color = Color.White, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
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
internal fun PersistedDecimalField(
    stableKey: String,
    label: String,
    authoritativeValue: Double,
    minimum: Double,
    maximum: Double,
    onValidValue: (Double) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
) {
    var draft by remember(stableKey) { mutableStateOf("") }
    var editing by remember(stableKey) { mutableStateOf(false) }
    var invalid by remember(stableKey) { mutableStateOf(false) }
    val formatted = formatExact(authoritativeValue)
    LaunchedEffect(stableKey, authoritativeValue, editing) {
        if (!editing) {
            draft = formatted
            invalid = false
        }
    }
    OutlinedTextField(
        value = if (editing) draft else formatted,
        onValueChange = { next ->
            draft = next
            val parsed = next.trim().toDoubleOrNull()
            invalid = parsed == null || !parsed.isFinite() || parsed < minimum || parsed > maximum
            if (!invalid) onValidValue(parsed!!)
        },
        label = { Text(label, color = Color.Gray) },
        supportingText = if (invalid) {
            { Text("Enter a finite value from ${formatExact(minimum)} to ${formatExact(maximum)}", color = Color(0xFFFF7777)) }
        } else null,
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentPistachio,
            unfocusedBorderColor = Color.DarkGray,
            errorBorderColor = Color(0xFFFF7777)
        ),
        modifier = modifier.onFocusChanged { state ->
            if (state.isFocused && !editing) draft = formatted
            editing = state.isFocused
            if (!state.isFocused && invalid) {
                draft = formatted
                invalid = false
            }
        }
    )
}

internal data class LensStaticSensorInfo(
    val cfa: CfaArrangementDescriptor,
    val whiteLevel: Int?,
    val staticBlackLevels: List<Float>
)

internal fun readLensStaticSensorInfo(context: Context, lensId: String): LensStaticSensorInfo {
    return runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(lensId)
        val cfaValue = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1
        val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        LensStaticSensorInfo(
            cfa = CfaArrangementDescriptor.from(cfaValue),
            whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            staticBlackLevels = if (pattern == null) emptyList() else listOf(
                pattern.getOffsetForIndex(0, 0).toFloat(),
                pattern.getOffsetForIndex(1, 0).toFloat(),
                pattern.getOffsetForIndex(0, 1).toFloat(),
                pattern.getOffsetForIndex(1, 1).toFloat()
            )
        )
    }.getOrElse {
        LensStaticSensorInfo(CfaArrangementDescriptor.from(-1), null, emptyList())
    }
}

internal fun CfaArrangementDescriptor.inputLabels(): List<String> = when (this) {
    is CfaArrangementDescriptor.Bayer -> channels.sortedBy { it.mosaicIndex }.map { channel ->
        when (channel.colorPlane) {
            ColorPlane.RED -> "R"
            ColorPlane.GREEN_RED -> "Gr"
            ColorPlane.GREEN_BLUE -> "Gb"
            ColorPlane.BLUE -> "B"
            ColorPlane.MONO -> "Mono"
        }
    }
    is CfaArrangementDescriptor.Monochrome -> listOf("Mono")
    is CfaArrangementDescriptor.Unsupported -> emptyList()
}

internal fun formatExact(value: Double): String {
    if (value == 0.0) return "0"
    val abs = kotlin.math.abs(value)
    return if (abs in 1.0e-4..1.0e7) {
        String.format(Locale.US, "%.8f", value).trimEnd('0').trimEnd('.')
    } else value.toString()
}

internal fun enumerateRawStreamsForLens(context: Context, lensId: String): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    result.add("AUTO" to "Auto (Default / Recommended)")
    runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(lensId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@runCatching

        val rawFormats = listOf(
            android.graphics.ImageFormat.RAW10 to "RAW10",
            android.graphics.ImageFormat.RAW_SENSOR to "RAW_SENSOR"
        )

        for ((fmt, fmtName) in rawFormats) {
            val sizes = map.getOutputSizes(fmt) ?: continue
            for (sz in sizes) {
                val durationNs = map.getOutputMinFrameDuration(fmt, sz)
                val maxFps = if (durationNs > 0) (1_000_000_000.0 / durationNs).toInt() else 30
                val key = "${fmtName}_${sz.width}x${sz.height}"
                val label = "${sz.width} × ${sz.height} · $fmtName · ≤${maxFps} fps"
                result.add(key to label)
            }
        }
    }
    return result
}



internal data class CameraStreamCodeOption(
    val formatCode: Int,
    val formatName: String,
    val sizes: List<android.util.Size>,
    val maxFps: Int?
) {
    val codeHex: String get() = "0x" + formatCode.toUInt().toString(16).uppercase(Locale.US)
    val largestSize: android.util.Size? get() = sizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
    fun summary(): String {
        val largest = largestSize?.let { "${it.width} × ${it.height}" } ?: "No sizes"
        val fps = maxFps?.let { " · ≤$it fps" } ?: ""
        return "Code $formatCode ($codeHex) · ${sizes.size} size${if (sizes.size == 1) "" else "s"} · $largest$fps"
    }
}

internal fun enumerateCameraOutputFormatCodes(context: Context, lensId: String): List<CameraStreamCodeOption> {
    return runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = manager.getCameraCharacteristics(lensId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return@runCatching emptyList()
        map.outputFormats
            .distinct()
            .map { format ->
                val sizes = runCatching { map.getOutputSizes(format)?.toList().orEmpty() }.getOrDefault(emptyList())
                val fastestNs = sizes.mapNotNull { size ->
                    runCatching { map.getOutputMinFrameDuration(format, size) }.getOrNull()?.takeIf { it > 0L }
                }.minOrNull()
                CameraStreamCodeOption(
                    formatCode = format,
                    formatName = cameraFormatName(format),
                    sizes = sizes.sortedByDescending { it.width.toLong() * it.height.toLong() },
                    maxFps = fastestNs?.let { (1_000_000_000.0 / it.toDouble()).toInt().coerceAtLeast(1) }
                )
            }
            .sortedWith(compareBy<CameraStreamCodeOption> { it.formatName }.thenBy { it.formatCode })
    }.getOrDefault(emptyList())
}

internal fun cameraFormatName(format: Int): String = when (format) {
    android.graphics.ImageFormat.YUV_420_888 -> "YUV_420_888"
    android.graphics.ImageFormat.RAW10 -> "RAW10"
    android.graphics.ImageFormat.RAW12 -> "RAW12"
    android.graphics.ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
    android.graphics.ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
    android.graphics.ImageFormat.JPEG -> "JPEG"
    android.graphics.ImageFormat.DEPTH16 -> "DEPTH16"
    android.graphics.ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
    android.graphics.ImageFormat.PRIVATE -> "PRIVATE"
    else -> "Vendor / format $format"
}
