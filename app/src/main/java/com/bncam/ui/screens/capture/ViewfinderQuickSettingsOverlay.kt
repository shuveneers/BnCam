package com.bncam.ui.screens.capture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bncam.core.capture.MeteringMode
import com.bncam.core.capture.OutputPolicy

/**
 * Canonical quick-settings surface for the viewfinder.
 *
 * This composable owns no persisted state. CameraScreen supplies values from SettingsRepository
 * and all mutations are routed back through the same repository setters used by the full settings
 * screens. Keep the tile set deliberately small and viewfinder-focused.
 */
@Composable
fun ViewfinderQuickSettingsOverlay(
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
    onDismiss: () -> Unit,
    uiRotationDegrees: Float = 0f,
    modifier: Modifier = Modifier
) {
    val meteringValues = listOf(
        MeteringMode.AUTO_DEFAULT_AE,
        MeteringMode.CENTER_WEIGHTED,
        MeteringMode.FRAME_AVERAGE,
        MeteringMode.SPOT
    )
    val tiles = listOf(
        QuickTileSpec(
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
        ),
        QuickTileSpec(
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
        ),
        QuickTileSpec("Watermark", if (watermarkEnabled) "On" else "Off", watermarkEnabled) {
            onWatermarkEnabledChange(!watermarkEnabled)
        },
        QuickTileSpec(
            label = "Output",
            value = outputPolicy.displayName,
            active = outputPolicy != OutputPolicy.JPEG,
            onClick = {
                val entries = OutputPolicy.entries
                val next = entries[(entries.indexOf(outputPolicy).coerceAtLeast(0) + 1) % entries.size]
                onOutputPolicyChange(next)
            }
        ),
        QuickTileSpec(
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
        ),
        QuickTileSpec("Geotag", if (geotagEnabled) "On" else "Off", geotagEnabled) {
            onGeotagEnabledChange(!geotagEnabled)
        },
        QuickTileSpec("Focus peaking", if (focusPeakingEnabled) "On" else "Off", focusPeakingEnabled) {
            onFocusPeakingEnabledChange(!focusPeakingEnabled)
        },
        QuickTileSpec(
            label = "Metering",
            value = when (meteringMode) {
                MeteringMode.AUTO_DEFAULT_AE -> "Auto"
                MeteringMode.CENTER_WEIGHTED -> "Center"
                MeteringMode.FRAME_AVERAGE -> "Average"
                MeteringMode.SPOT -> "Spot"
            },
            active = meteringMode != MeteringMode.AUTO_DEFAULT_AE,
            onClick = {
                val next = meteringValues[(meteringValues.indexOf(meteringMode).coerceAtLeast(0) + 1) % meteringValues.size]
                onMeteringModeChange(next)
            }
        ),
        QuickTileSpec("Histogram", if (histogramEnabled) "On" else "Off", histogramEnabled) {
            onHistogramEnabledChange(!histogramEnabled)
        },
        QuickTileSpec("Focus track", if (focusTrackingEnabled) "On" else "Off", focusTrackingEnabled) {
            onFocusTrackingEnabledChange(!focusTrackingEnabled)
        },
        QuickTileSpec("Horizon leveler", if (horizonLevelerEnabled) "On" else "Off", horizonLevelerEnabled) {
            onHorizonLevelerEnabledChange(!horizonLevelerEnabled)
        },
        QuickTileSpec("Face detection", if (faceDetectionEnabled) "On" else "Off", faceDetectionEnabled) {
            onFaceDetectionEnabledChange(!faceDetectionEnabled)
        }
    )

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
        color = Color(0xF21A1A1A),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Quick settings",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    modifier = Modifier.rotate(uiRotationDegrees)
                )
                Text(
                    "Close",
                    color = AccentPistachio,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .rotate(uiRotationDegrees)
                )
            }

            tiles.chunked(3).forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowTiles.forEach { tile ->
                        QuickTile(tile = tile, uiRotationDegrees = uiRotationDegrees, modifier = Modifier.weight(1f))
                    }
                    repeat(3 - rowTiles.size) {
                        Box(modifier = Modifier.weight(1f).height(70.dp))
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
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        !tile.enabled -> Color.White.copy(alpha = 0.08f)
        tile.active -> AccentPistachio.copy(alpha = 0.72f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    val background = when {
        !tile.enabled -> Color.White.copy(alpha = 0.025f)
        tile.active -> AccentPistachio.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.055f)
    }
    Surface(
        modifier = modifier.height(70.dp).clickable(enabled = tile.enabled, onClick = tile.onClick),
        shape = RoundedCornerShape(16.dp),
        color = background,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 6.dp, vertical = 8.dp)
                .rotate(uiRotationDegrees),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                tile.label,
                color = Color.White.copy(alpha = if (tile.enabled) 0.88f else 0.32f),
                fontSize = 11.sp,
                maxLines = 1
            )
            Text(
                tile.value,
                color = when {
                    !tile.enabled -> Color.White.copy(alpha = 0.22f)
                    tile.active -> AccentPistachio
                    else -> Color.White.copy(alpha = 0.68f)
                },
                fontSize = 11.sp,
                fontWeight = if (tile.active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
