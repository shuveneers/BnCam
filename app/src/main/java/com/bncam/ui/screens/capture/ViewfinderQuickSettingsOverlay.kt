package com.bncam.ui.screens.capture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.core.capture.MeteringMode
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.capture.ViewfinderMode

internal object ViewfinderQuickSettingIds {
    const val FLASH = "flash"
    const val TIMER = "timer"
    const val WATERMARK = "watermark"
    const val OUTPUT = "output"
    const val VIEWFINDER = "viewfinder"
    const val GEOTAG = "geotag"
    const val FOCUS_PEAKING = "focus_peaking"
    const val METERING = "metering"
    const val HISTOGRAM = "histogram"
    const val FOCUS_TRACK = "focus_track"
    const val HORIZON_LEVELER = "horizon_leveler"
    const val FACE_DETECTION = "face_detection"

    val all: List<String> = listOf(
        FLASH,
        TIMER,
        WATERMARK,
        OUTPUT,
        VIEWFINDER,
        GEOTAG,
        FOCUS_PEAKING,
        METERING,
        HISTOGRAM,
        FOCUS_TRACK,
        HORIZON_LEVELER,
        FACE_DETECTION
    )

    val defaults: List<String> = all.take(9)
}

internal enum class QuickShotMode {
    PORTRAIT,
    ULTRA_HDR,
    NIGHT
}

/**
 * Canonical quick-settings surface for the viewfinder.
 *
 * Nine user-assignable slots are persisted by CameraScreen/SettingsRepository. This composable
 * owns only transient editor state; every camera setting continues to use the same canonical
 * repository setter as the full settings UI.
 */
@Composable
internal fun ViewfinderQuickSettingsOverlay(
    flashMode: String,
    timerDurationSeconds: Int,
    watermarkEnabled: Boolean,
    outputPolicy: OutputPolicy,
    viewfinderStream: ViewfinderStream,
    geotagEnabled: Boolean,
    focusPeakingEnabled: Boolean,
    meteringMode: MeteringMode,
    histogramEnabled: Boolean,
    focusTrackingEnabled: Boolean,
    horizonLevelerEnabled: Boolean,
    faceDetectionEnabled: Boolean,
    quickSettingAssignments: List<String>,
    viewfinderMode: ViewfinderMode,
    ultraHdrEnabled: Boolean,
    onFlashModeChange: (String) -> Unit,
    onTimerDurationChange: (Int) -> Unit,
    onWatermarkEnabledChange: (Boolean) -> Unit,
    onOutputPolicyChange: (OutputPolicy) -> Unit,
    onViewfinderStreamChange: (ViewfinderStream) -> Unit,
    onGeotagEnabledChange: (Boolean) -> Unit,
    onFocusPeakingEnabledChange: (Boolean) -> Unit,
    onMeteringModeChange: (MeteringMode) -> Unit,
    onHistogramEnabledChange: (Boolean) -> Unit,
    onFocusTrackingEnabledChange: (Boolean) -> Unit,
    onHorizonLevelerEnabledChange: (Boolean) -> Unit,
    onFaceDetectionEnabledChange: (Boolean) -> Unit,
    onQuickSettingAssigned: (slotIndex: Int, settingId: String) -> Unit,
    onShotModeSelected: (QuickShotMode) -> Unit,
    onDismiss: () -> Unit,
    uiRotationDegrees: Float = 0f,
    modifier: Modifier = Modifier
) {
    var editMode by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<Int?>(null) }

    val normalizedAssignments = remember(quickSettingAssignments) {
        val result = quickSettingAssignments
            .filter { it in ViewfinderQuickSettingIds.all }
            .distinct()
            .take(9)
            .toMutableList()
        ViewfinderQuickSettingIds.all.forEach { id ->
            if (result.size < 9 && id !in result) result += id
        }
        result
    }

    val meteringValues = listOf(
        MeteringMode.AUTO_DEFAULT_AE,
        MeteringMode.CENTER_WEIGHTED,
        MeteringMode.FRAME_AVERAGE,
        MeteringMode.SPOT
    )

    fun labelForId(id: String): String = when (id) {
        ViewfinderQuickSettingIds.FLASH -> "Flash"
        ViewfinderQuickSettingIds.TIMER -> "Timer"
        ViewfinderQuickSettingIds.WATERMARK -> "Watermark"
        ViewfinderQuickSettingIds.OUTPUT -> "Output"
        ViewfinderQuickSettingIds.VIEWFINDER -> "Viewfinder stream"
        ViewfinderQuickSettingIds.GEOTAG -> "Geotag"
        ViewfinderQuickSettingIds.FOCUS_PEAKING -> "Focus peaking"
        ViewfinderQuickSettingIds.METERING -> "Metering"
        ViewfinderQuickSettingIds.HISTOGRAM -> "Histogram"
        ViewfinderQuickSettingIds.FOCUS_TRACK -> "Focus track"
        ViewfinderQuickSettingIds.HORIZON_LEVELER -> "Horizon leveler"
        ViewfinderQuickSettingIds.FACE_DETECTION -> "Face detection"
        else -> "Setting"
    }

    fun tileForId(id: String): QuickTileSpec = when (id) {
        ViewfinderQuickSettingIds.FLASH -> QuickTileSpec(
            label = "Flash",
            value = flashMode,
            active = !flashMode.equals("Off", ignoreCase = true),
            onClick = {
                onFlashModeChange(
                    when (flashMode.trim().lowercase()) {
                        "off" -> "Auto"
                        "auto" -> "On"
                        else -> "Off"
                    }
                )
            }
        )
        ViewfinderQuickSettingIds.TIMER -> QuickTileSpec(
            label = "Timer",
            value = if (timerDurationSeconds <= 0) "Off" else "${timerDurationSeconds}s",
            active = timerDurationSeconds > 0,
            onClick = {
                onTimerDurationChange(
                    when (timerDurationSeconds) {
                        0 -> 3
                        3 -> 10
                        else -> 0
                    }
                )
            }
        )
        ViewfinderQuickSettingIds.WATERMARK -> QuickTileSpec(
            "Watermark",
            if (watermarkEnabled) "On" else "Off",
            watermarkEnabled
        ) { onWatermarkEnabledChange(!watermarkEnabled) }
        ViewfinderQuickSettingIds.OUTPUT -> QuickTileSpec(
            label = "Output",
            value = outputPolicy.displayName,
            active = outputPolicy != OutputPolicy.JPEG,
            onClick = {
                val entries = OutputPolicy.entries
                val next = entries[(entries.indexOf(outputPolicy).coerceAtLeast(0) + 1) % entries.size]
                onOutputPolicyChange(next)
            }
        )
        ViewfinderQuickSettingIds.VIEWFINDER -> QuickTileSpec(
            label = "Viewfinder stream",
            value = viewfinderStream.displayName,
            active = viewfinderStream == ViewfinderStream.SELECTED_BUFFER,
            onClick = {
                onViewfinderStreamChange(
                    if (viewfinderStream == ViewfinderStream.YUV) {
                        ViewfinderStream.SELECTED_BUFFER
                    } else {
                        ViewfinderStream.YUV
                    }
                )
            }
        )
        ViewfinderQuickSettingIds.GEOTAG -> QuickTileSpec(
            "Geotag",
            if (geotagEnabled) "On" else "Off",
            geotagEnabled
        ) { onGeotagEnabledChange(!geotagEnabled) }
        ViewfinderQuickSettingIds.FOCUS_PEAKING -> QuickTileSpec(
            "Focus peaking",
            if (focusPeakingEnabled) "On" else "Off",
            focusPeakingEnabled
        ) { onFocusPeakingEnabledChange(!focusPeakingEnabled) }
        ViewfinderQuickSettingIds.METERING -> QuickTileSpec(
            label = "Metering",
            value = when (meteringMode) {
                MeteringMode.AUTO_DEFAULT_AE -> "Auto"
                MeteringMode.CENTER_WEIGHTED -> "Center"
                MeteringMode.FRAME_AVERAGE -> "Average"
                MeteringMode.SPOT -> "Spot"
            },
            active = meteringMode != MeteringMode.AUTO_DEFAULT_AE,
            onClick = {
                val next = meteringValues[
                    (meteringValues.indexOf(meteringMode).coerceAtLeast(0) + 1) % meteringValues.size
                ]
                onMeteringModeChange(next)
            }
        )
        ViewfinderQuickSettingIds.HISTOGRAM -> QuickTileSpec(
            "Histogram",
            if (histogramEnabled) "On" else "Off",
            histogramEnabled
        ) { onHistogramEnabledChange(!histogramEnabled) }
        ViewfinderQuickSettingIds.FOCUS_TRACK -> QuickTileSpec(
            "Focus track",
            if (focusTrackingEnabled) "On" else "Off",
            focusTrackingEnabled
        ) { onFocusTrackingEnabledChange(!focusTrackingEnabled) }
        ViewfinderQuickSettingIds.HORIZON_LEVELER -> QuickTileSpec(
            "Horizon leveler",
            if (horizonLevelerEnabled) "On" else "Off",
            horizonLevelerEnabled
        ) { onHorizonLevelerEnabledChange(!horizonLevelerEnabled) }
        ViewfinderQuickSettingIds.FACE_DETECTION -> QuickTileSpec(
            "Face detection",
            if (faceDetectionEnabled) "On" else "Off",
            faceDetectionEnabled
        ) { onFaceDetectionEnabledChange(!faceDetectionEnabled) }
        else -> QuickTileSpec("Setting", "Unavailable", enabled = false, onClick = {})
    }

    val selectedShotMode = when {
        viewfinderMode == ViewfinderMode.PORTRAIT -> QuickShotMode.PORTRAIT
        viewfinderMode == ViewfinderMode.NIGHT -> QuickShotMode.NIGHT
        viewfinderMode == ViewfinderMode.PHOTO && ultraHdrEnabled -> QuickShotMode.ULTRA_HDR
        else -> null
    }

    Surface(
        modifier = modifier.pointerInput(onDismiss) {
            var accumulatedVerticalDragPx = 0f
            val dismissThresholdPx = 48.dp.toPx()
            detectVerticalDragGestures(
                onDragStart = { accumulatedVerticalDragPx = 0f },
                onDragCancel = { accumulatedVerticalDragPx = 0f },
                onDragEnd = {
                    if (accumulatedVerticalDragPx <= -dismissThresholdPx) onDismiss()
                    accumulatedVerticalDragPx = 0f
                }
            ) { change, dragAmount ->
                accumulatedVerticalDragPx += dragAmount
                change.consume()
            }
        },
        color = Color(0xF2181818),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        editingSlot != null -> "Setting ${editingSlot!! + 1}"
                        editMode -> "Quick settings layout"
                        else -> "Quick settings"
                    },
                    color = Color.White.copy(alpha = 0.94f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.rotate(uiRotationDegrees)
                )

                Surface(
                    shape = CircleShape,
                    color = if (editMode) AccentPistachio.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f),
                    border = BorderStroke(
                        0.75.dp,
                        if (editMode) AccentPistachio.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.10f)
                    ),
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (editMode) {
                                editMode = false
                                editingSlot = null
                            } else {
                                editMode = true
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit quick settings",
                            tint = if (editMode) AccentPistachio else Color.White.copy(alpha = 0.90f),
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(uiRotationDegrees)
                        )
                    }
                }
            }

            when {
                editingSlot != null -> {
                    val slot = editingSlot!!
                    Text(
                        text = "Choose a function for this tile",
                        color = Color.White.copy(alpha = 0.48f),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .rotate(uiRotationDegrees)
                    )
                    ViewfinderQuickSettingIds.all.chunked(3).forEach { rowIds ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowIds.forEach { id ->
                                val selected = normalizedAssignments.getOrNull(slot) == id
                                QuickTile(
                                    tile = QuickTileSpec(
                                        label = labelForId(id),
                                        value = if (selected) "Assigned" else "",
                                        active = selected,
                                        onClick = {
                                            onQuickSettingAssigned(slot, id)
                                            editingSlot = null
                                        }
                                    ),
                                    uiRotationDegrees = uiRotationDegrees,
                                    modifier = Modifier.weight(1f),
                                    compact = true
                                )
                            }
                            repeat(3 - rowIds.size) {
                                Box(modifier = Modifier.weight(1f).height(58.dp))
                            }
                        }
                    }
                }

                editMode -> {
                    Text(
                        text = "Tap a slot, then choose its function",
                        color = Color.White.copy(alpha = 0.48f),
                        fontSize = 10.sp,
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .rotate(uiRotationDegrees)
                    )
                    normalizedAssignments.take(9).chunked(3).forEachIndexed { rowIndex, rowIds ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowIds.forEachIndexed { columnIndex, id ->
                                val slot = rowIndex * 3 + columnIndex
                                QuickTile(
                                    tile = QuickTileSpec(
                                        label = "Setting ${slot + 1}",
                                        value = labelForId(id),
                                        active = false,
                                        onClick = { editingSlot = slot }
                                    ),
                                    uiRotationDegrees = uiRotationDegrees,
                                    modifier = Modifier.weight(1f),
                                    compact = true
                                )
                            }
                            repeat(3 - rowIds.size) {
                                Box(modifier = Modifier.weight(1f).height(58.dp))
                            }
                        }
                    }
                }

                else -> {
                    normalizedAssignments.take(9).chunked(3).forEach { rowIds ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowIds.forEach { id ->
                                QuickTile(
                                    tile = tileForId(id),
                                    uiRotationDegrees = uiRotationDegrees,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(3 - rowIds.size) {
                                Box(modifier = Modifier.weight(1f).height(70.dp))
                            }
                        }
                    }

                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                    ) {
                        drawLine(
                            color = Color.White.copy(alpha = 0.10f),
                            start = androidx.compose.ui.geometry.Offset.Zero,
                            end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ShotModeTile(
                            label = "Portrait",
                            selected = selectedShotMode == QuickShotMode.PORTRAIT,
                            uiRotationDegrees = uiRotationDegrees,
                            onClick = { onShotModeSelected(QuickShotMode.PORTRAIT) },
                            modifier = Modifier.weight(1f)
                        )
                        ShotModeTile(
                            label = "UHDR",
                            selected = selectedShotMode == QuickShotMode.ULTRA_HDR,
                            uiRotationDegrees = uiRotationDegrees,
                            onClick = { onShotModeSelected(QuickShotMode.ULTRA_HDR) },
                            modifier = Modifier.weight(1f)
                        )
                        ShotModeTile(
                            label = "Night",
                            selected = selectedShotMode == QuickShotMode.NIGHT,
                            uiRotationDegrees = uiRotationDegrees,
                            onClick = { onShotModeSelected(QuickShotMode.NIGHT) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

private data class QuickTileSpec(
    val label: String,
    val value: String,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
private fun QuickTile(
    tile: QuickTileSpec,
    uiRotationDegrees: Float,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val borderColor = when {
        !tile.enabled -> Color.White.copy(alpha = 0.07f)
        tile.active -> AccentPistachio.copy(alpha = 0.76f)
        else -> Color.White.copy(alpha = 0.13f)
    }
    val background = when {
        !tile.enabled -> Color.White.copy(alpha = 0.02f)
        tile.active -> AccentPistachio.copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.052f)
    }
    Surface(
        modifier = modifier
            .height(if (compact) 58.dp else 70.dp)
            .clickable(enabled = tile.enabled, onClick = tile.onClick),
        shape = RoundedCornerShape(16.dp),
        color = background,
        border = BorderStroke(if (tile.active) 1.25.dp else 0.75.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = if (compact) 6.dp else 8.dp)
                .rotate(uiRotationDegrees),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = tile.label,
                color = when {
                    !tile.enabled -> Color.White.copy(alpha = 0.30f)
                    tile.active -> AccentPistachio
                    else -> Color.White.copy(alpha = 0.88f)
                },
                fontSize = if (compact) 10.sp else 11.sp,
                lineHeight = if (compact) 11.sp else 13.sp,
                textAlign = TextAlign.Center,
                fontWeight = if (tile.active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2
            )
            if (tile.value.isNotBlank()) {
                Text(
                    text = tile.value,
                    color = when {
                        !tile.enabled -> Color.White.copy(alpha = 0.20f)
                        tile.active -> AccentPistachio.copy(alpha = 0.90f)
                        else -> Color.White.copy(alpha = 0.58f)
                    },
                    fontSize = if (compact) 9.sp else 10.sp,
                    lineHeight = if (compact) 10.sp else 12.sp,
                    fontWeight = if (tile.active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = if (compact) 2.dp else 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ShotModeTile(
    label: String,
    selected: Boolean,
    uiRotationDegrees: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) AccentPistachio.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f),
        border = BorderStroke(
            if (selected) 1.25.dp else 0.75.dp,
            if (selected) AccentPistachio.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.12f)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (selected) AccentPistachio else Color.White.copy(alpha = 0.82f),
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.rotate(uiRotationDegrees)
            )
        }
    }
}
