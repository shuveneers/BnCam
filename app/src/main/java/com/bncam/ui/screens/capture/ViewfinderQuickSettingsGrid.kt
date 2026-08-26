package com.bncam.ui.screens.capture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.core.capture.MeteringMode
import com.bncam.core.capture.OutputPolicy

private enum class QuickSettingsPicker {
    METERING,
    TIMER,
    OUTPUT,
    VIEWFINDER
}

/**
 * Compact 3 x 3 control surface for the viewfinder.
 *
 * The component intentionally owns only transient picker state. Every persisted value remains
 * sourced from SettingsRepository by the caller, so the overlay cannot diverge from Settings.
 */
@Composable
fun ViewfinderQuickSettingsGridOverlay(
    flashMode: String,
    meteringMode: MeteringMode,
    timerDurationSeconds: Int,
    outputPolicy: OutputPolicy,
    watermarkEnabled: Boolean,
    viewfinderStream: ViewfinderStream,
    focusPeakingEnabled: Boolean,
    histogramEnabled: Boolean,
    debugEnabled: Boolean,
    rotationDegrees: Float,
    onFlashModeChange: (String) -> Unit,
    onMeteringModeChange: (MeteringMode) -> Unit,
    onTimerDurationChange: (Int) -> Unit,
    onOutputPolicyChange: (OutputPolicy) -> Unit,
    onWatermarkEnabledChange: (Boolean) -> Unit,
    onViewfinderStreamChange: (ViewfinderStream) -> Unit,
    onFocusPeakingEnabledChange: (Boolean) -> Unit,
    onHistogramEnabledChange: (Boolean) -> Unit,
    onDebugEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var activePicker by remember { mutableStateOf<QuickSettingsPicker?>(null) }

    Box(modifier = modifier) {
        Surface(
            color = Color(0xF21A1A1A),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 10.dp
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                QuickSettingsTileRow(
                    tiles = listOf(
                        QuickGridTileSpec(
                            icon = Icons.Default.FlashOn,
                            title = "Flash",
                            value = flashMode,
                            active = !flashMode.equals("Off", ignoreCase = true),
                            onClick = {
                                onFlashModeChange(
                                    when (flashMode.trim().lowercase()) {
                                        "off" -> "On"
                                        "on" -> "Auto"
                                        else -> "Off"
                                    }
                                )
                            }
                        ),
                        QuickGridTileSpec(
                            icon = Icons.Default.Tune,
                            title = "Metering",
                            value = meteringLabel(meteringMode),
                            active = meteringMode != MeteringMode.AUTO_DEFAULT_AE,
                            onClick = { activePicker = QuickSettingsPicker.METERING }
                        ),
                        QuickGridTileSpec(
                            icon = Icons.Default.Schedule,
                            title = "Timer",
                            value = if (timerDurationSeconds <= 0) "Off" else "${timerDurationSeconds}s",
                            active = timerDurationSeconds > 0,
                            onClick = { activePicker = QuickSettingsPicker.TIMER }
                        )
                    ),
                    rotationDegrees = rotationDegrees,
                    drawBottomDivider = true
                )
                QuickSettingsTileRow(
                    tiles = listOf(
                        QuickGridTileSpec(
                            icon = Icons.Default.PhotoCamera,
                            title = "Output",
                            value = outputPolicy.displayName,
                            active = outputPolicy != OutputPolicy.JPEG,
                            onClick = { activePicker = QuickSettingsPicker.OUTPUT }
                        ),
                        QuickGridTileSpec(
                            icon = Icons.Default.BrandingWatermark,
                            title = "Watermark",
                            value = if (watermarkEnabled) "On" else "Off",
                            active = watermarkEnabled,
                            onClick = { onWatermarkEnabledChange(!watermarkEnabled) }
                        ),
                        QuickGridTileSpec(
                            icon = Icons.Default.GridView,
                            title = "Viewfinder",
                            value = viewfinderStream.displayName,
                            active = viewfinderStream == ViewfinderStream.SELECTED_BUFFER,
                            onClick = { activePicker = QuickSettingsPicker.VIEWFINDER }
                        )
                    ),
                    rotationDegrees = rotationDegrees,
                    drawBottomDivider = true
                )
                QuickSettingsTileRow(
                    tiles = listOf(
                        QuickGridTileSpec(
                            icon = Icons.Default.CenterFocusStrong,
                            title = "Focus peaking",
                            value = if (focusPeakingEnabled) "On" else "Off",
                            active = focusPeakingEnabled,
                            onClick = { onFocusPeakingEnabledChange(!focusPeakingEnabled) }
                        ),
                        QuickGridTileSpec(
                            icon = Icons.Default.BarChart,
                            title = "Histogram",
                            value = if (histogramEnabled) "On" else "Off",
                            active = histogramEnabled,
                            onClick = { onHistogramEnabledChange(!histogramEnabled) }
                        ),
                        QuickGridTileSpec(
                            icon = Icons.Default.BugReport,
                            title = "Debug",
                            value = if (debugEnabled) "On" else "Off",
                            active = debugEnabled,
                            onClick = { onDebugEnabledChange(!debugEnabled) }
                        )
                    ),
                    rotationDegrees = rotationDegrees,
                    drawBottomDivider = false
                )
            }
        }

        AnimatedVisibility(
            visible = activePicker != null,
            enter = fadeIn() + scaleIn(initialScale = 0.96f),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.34f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { activePicker = null },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color(0xFF242424),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    when (activePicker) {
                        QuickSettingsPicker.METERING -> QuickChoicePanel(
                            title = "Metering",
                            rotationDegrees = rotationDegrees,
                            options = listOf(
                                MeteringMode.AUTO_DEFAULT_AE to "Auto",
                                MeteringMode.CENTER_WEIGHTED to "Center",
                                MeteringMode.FRAME_AVERAGE to "Average",
                                MeteringMode.SPOT to "Spot"
                            ),
                            selected = meteringMode,
                            onSelected = {
                                onMeteringModeChange(it)
                                activePicker = null
                            }
                        )
                        QuickSettingsPicker.TIMER -> QuickChoicePanel(
                            title = "Timer",
                            rotationDegrees = rotationDegrees,
                            options = listOf(0 to "Off", 3 to "3s", 10 to "10s"),
                            selected = timerDurationSeconds,
                            onSelected = {
                                onTimerDurationChange(it)
                                activePicker = null
                            }
                        )
                        QuickSettingsPicker.OUTPUT -> QuickChoicePanel(
                            title = "Output",
                            rotationDegrees = rotationDegrees,
                            options = OutputPolicy.entries.map { it to it.displayName },
                            selected = outputPolicy,
                            onSelected = {
                                onOutputPolicyChange(it)
                                activePicker = null
                            }
                        )
                        QuickSettingsPicker.VIEWFINDER -> QuickChoicePanel(
                            title = "Viewfinder",
                            rotationDegrees = rotationDegrees,
                            options = listOf(
                                ViewfinderStream.YUV to "YUV",
                                ViewfinderStream.SELECTED_BUFFER to "Selected buffer"
                            ),
                            selected = viewfinderStream,
                            onSelected = {
                                onViewfinderStreamChange(it)
                                activePicker = null
                            }
                        )
                        null -> Unit
                    }
                }
            }
        }
    }
}

private data class QuickGridTileSpec(
    val icon: ImageVector,
    val title: String,
    val value: String,
    val active: Boolean,
    val onClick: () -> Unit
)

@Composable
private fun QuickSettingsTileRow(
    tiles: List<QuickGridTileSpec>,
    rotationDegrees: Float,
    drawBottomDivider: Boolean
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tileWidth = maxWidth / tiles.size.coerceAtLeast(1).toFloat()
        Row(modifier = Modifier.fillMaxWidth()) {
            tiles.forEachIndexed { index, tile ->
                Box(modifier = Modifier.width(tileWidth)) {
                    QuickSettingsTile(tile = tile, rotationDegrees = rotationDegrees)
                    if (index < tiles.lastIndex) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(vertical = 9.dp)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(Color.White.copy(alpha = 0.08f))
                        )
                    }
                }
            }
        }
    }
    if (drawBottomDivider) {
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f))
        )
    }
}

@Composable
private fun QuickSettingsTile(
    tile: QuickGridTileSpec,
    rotationDegrees: Float
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = tile.onClick
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.rotate(rotationDegrees),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = tile.icon,
                contentDescription = tile.title,
                tint = if (tile.active) AccentPistachio else Color.White.copy(alpha = 0.82f),
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = tile.title,
                color = Color.White.copy(alpha = 0.90f),
                fontSize = 11.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                text = tile.value,
                color = if (tile.active) AccentPistachio else Color.White.copy(alpha = 0.52f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun <T> QuickChoicePanel(
    title: String,
    rotationDegrees: Float,
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit
) {
    Column(
        modifier = Modifier.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .rotate(rotationDegrees)
        )
        options.chunked(2).forEach { rowOptions ->
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val itemWidth = if (rowOptions.size == 1) {
                    maxWidth
                } else {
                    (maxWidth - 8.dp) / 2f
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowOptions.forEach { (value, label) ->
                        val active = value == selected
                        Surface(
                            color = if (active) AccentPistachio.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                            contentColor = if (active) AccentPistachio else Color.White,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                1.dp,
                                if (active) AccentPistachio.copy(alpha = 0.66f) else Color.White.copy(alpha = 0.10f)
                            ),
                            modifier = Modifier
                                .width(itemWidth)
                                .clickable { onSelected(value) }
                        ) {
                            Text(
                                text = label,
                                textAlign = TextAlign.Center,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 9.dp)
                                    .rotate(rotationDegrees)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun meteringLabel(mode: MeteringMode): String = when (mode) {
    MeteringMode.AUTO_DEFAULT_AE -> "Auto"
    MeteringMode.CENTER_WEIGHTED -> "Center"
    MeteringMode.FRAME_AVERAGE -> "Average"
    MeteringMode.SPOT -> "Spot"
}
