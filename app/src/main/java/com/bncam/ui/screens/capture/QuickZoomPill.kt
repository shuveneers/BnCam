package com.bncam.ui.screens.capture

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

internal data class ZoomSensorStop(
    val lensId: String,
    val zoomRatio: Float,
    val active: Boolean
)

@Composable
internal fun QuickZoomPill(
    currentZoom: () -> Float,
    minZoom: Float,
    maxZoom: Float,
    sensorStops: List<ZoomSensorStop>,
    uiRotationDegrees: Float,
    expanded: Boolean,
    onZoomSelected: (Float) -> Unit,
    onZoomScrubbed: (Float) -> Unit,
    onScrubActiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeMinZoom = minZoom.coerceAtLeast(0.1f)
    val safeMaxZoom = maxZoom.coerceAtLeast(safeMinZoom)
    val currentZoomValue = currentZoom().coerceIn(safeMinZoom, safeMaxZoom)
    val oneXAvailable = 1f in safeMinZoom..safeMaxZoom
    val twoXAvailable = 2f in safeMinZoom..safeMaxZoom
    val oneXSelected = oneXAvailable && abs(currentZoomValue - 1f) <= 0.08f
    val twoXSelected = twoXAvailable && abs(currentZoomValue - 2f) <= 0.12f
    val animatedWidth = animateDpAsState(
        targetValue = if (expanded) 324.dp else 90.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "quick_zoom_pill_width"
    )

    Surface(
        modifier = modifier.width(animatedWidth.value),
        color = Color(0xC91C1C1C),
        shape = RoundedCornerShape(50),
        border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.14f)),
        shadowElevation = 4.dp
    ) {
        Crossfade(
            targetState = expanded,
            animationSpec = tween(durationMillis = 150),
            label = "quick_zoom_pill_content"
        ) { showSlider ->
            if (showSlider) {
                QuickZoomSlider(
                    zoom = currentZoomValue,
                    minZoom = safeMinZoom,
                    maxZoom = safeMaxZoom,
                    sensorStops = sensorStops,
                    rotationDegrees = uiRotationDegrees,
                    onZoomScrubbed = onZoomScrubbed,
                    onScrubActiveChange = onScrubActiveChange
                )
            } else {
                Row(modifier = Modifier.padding(3.dp)) {
                    QuickZoomSegment(
                        label = "1x",
                        selected = oneXSelected,
                        enabled = oneXAvailable,
                        rotationDegrees = uiRotationDegrees,
                        onClick = { onZoomSelected(1f) }
                    )
                    QuickZoomSegment(
                        label = "2x",
                        selected = twoXSelected,
                        enabled = twoXAvailable,
                        rotationDegrees = uiRotationDegrees,
                        onClick = { onZoomSelected(2f) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickZoomSlider(
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    sensorStops: List<ZoomSensorStop>,
    rotationDegrees: Float,
    onZoomScrubbed: (Float) -> Unit,
    onScrubActiveChange: (Boolean) -> Unit
) {
    val safeZoom = zoom.coerceIn(minZoom, maxZoom)
    val fraction = zoomToFraction(safeZoom, minZoom, maxZoom)
    val visibleStops = sensorStops
        .asSequence()
        .filter { it.zoomRatio.isFinite() && it.zoomRatio in minZoom..maxZoom }
        .groupBy { (it.zoomRatio * 100f).toInt() }
        .values
        .mapNotNull { groupedStops ->
            groupedStops.firstOrNull { it.active } ?: groupedStops.firstOrNull()
        }
        .sortedBy { it.zoomRatio }

    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(54.dp)
                .height(38.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = String.format(Locale.US, "%.2f×", safeZoom),
                color = AccentPistachio,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.rotate(rotationDegrees)
            )
        }

        Box(
            modifier = Modifier
                .width(250.dp)
                .height(38.dp)
                .pointerInput(minZoom, maxZoom) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onScrubActiveChange(true)
                        val insetPx = 9.dp.toPx()
                        val usableWidth = (size.width.toFloat() - insetPx * 2f).coerceAtLeast(1f)
                        fun emitZoom(x: Float) {
                            val sliderFraction = ((x - insetPx) / usableWidth).coerceIn(0f, 1f)
                            onZoomScrubbed(
                                fractionToZoom(
                                    fraction = sliderFraction,
                                    minZoom = minZoom,
                                    maxZoom = maxZoom
                                )
                            )
                        }
                        try {
                            emitZoom(down.position.x)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                emitZoom(change.position.x)
                                if (!change.pressed) break
                                change.consume()
                            }
                        } finally {
                            onScrubActiveChange(false)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val inset = 9.dp.toPx()
                val y = 25.dp.toPx().coerceAtMost(size.height - 5.dp.toPx())
                val startX = inset
                val endX = (size.width - inset).coerceAtLeast(startX + 1f)
                val thumbX = startX + (endX - startX) * fraction
                val baseColor = Color.White.copy(alpha = 0.22f)
                val activeColor = AccentPistachio.copy(alpha = 0.95f)

                drawLine(
                    color = baseColor,
                    start = Offset(startX, y),
                    end = Offset(endX, y),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = activeColor,
                    start = Offset(startX, y),
                    end = Offset(thumbX, y),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )

                visibleStops.forEach { stop ->
                    val markerFraction = zoomToFraction(stop.zoomRatio, minZoom, maxZoom)
                    val x = startX + (endX - startX) * markerFraction
                    val markerColor = if (stop.active) {
                        AccentPistachio.copy(alpha = 1f)
                    } else {
                        Color.White.copy(alpha = 0.58f)
                    }
                    drawLine(
                        color = markerColor,
                        start = Offset(x, y - 7.dp.toPx()),
                        end = Offset(x, y + 7.dp.toPx()),
                        strokeWidth = if (stop.active) 2.dp.toPx() else 1.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = markerColor,
                        radius = if (stop.active) 3.2.dp.toPx() else 2.4.dp.toPx(),
                        center = Offset(x, y)
                    )

                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(
                            if (stop.active) 255 else 185,
                            if (stop.active) 178 else 255,
                            if (stop.active) 211 else 255,
                            if (stop.active) 168 else 255
                        )
                        textSize = 9.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = if (stop.active) {
                            android.graphics.Typeface.create(
                                android.graphics.Typeface.DEFAULT,
                                android.graphics.Typeface.BOLD
                            )
                        } else {
                            android.graphics.Typeface.DEFAULT
                        }
                    }
                    val labelY = 10.dp.toPx()
                    drawContext.canvas.nativeCanvas.apply {
                        save()
                        rotate(rotationDegrees, x, labelY)
                        drawText(formatMarkerZoom(stop.zoomRatio), x, labelY, paint)
                        restore()
                    }
                }

                drawCircle(
                    color = activeColor,
                    radius = 6.dp.toPx(),
                    center = Offset(thumbX, y)
                )
                drawCircle(
                    color = Color.Black.copy(alpha = 0.28f),
                    radius = 2.dp.toPx(),
                    center = Offset(thumbX, y)
                )
            }
        }
    }
}

private fun zoomToFraction(zoom: Float, minZoom: Float, maxZoom: Float): Float {
    if (maxZoom <= minZoom * 1.001f) return 0f
    val safeMin = minZoom.coerceAtLeast(0.1f)
    val safeMax = maxZoom.coerceAtLeast(safeMin * 1.001f)
    val safe = zoom.coerceIn(safeMin, safeMax)
    val logMin = ln(safeMin.toDouble())
    val logSpan = (ln(safeMax.toDouble()) - logMin).coerceAtLeast(0.000001)
    return ((ln(safe.toDouble()) - logMin) / logSpan).toFloat().coerceIn(0f, 1f)
}

private fun fractionToZoom(fraction: Float, minZoom: Float, maxZoom: Float): Float {
    if (maxZoom <= minZoom * 1.001f) return minZoom
    val safeMin = minZoom.coerceAtLeast(0.1f)
    val safeMax = maxZoom.coerceAtLeast(safeMin * 1.001f)
    val logMin = ln(safeMin.toDouble())
    val logSpan = ln(safeMax.toDouble()) - logMin
    return exp(logMin + fraction.coerceIn(0f, 1f).toDouble() * logSpan)
        .toFloat()
        .coerceIn(safeMin, safeMax)
}

private fun formatMarkerZoom(zoom: Float): String {
    val rounded = kotlin.math.round(zoom)
    return if (abs(zoom - rounded) <= 0.04f) {
        String.format(Locale.US, "%.0f×", rounded)
    } else {
        String.format(Locale.US, "%.1f×", zoom)
    }
}

@Composable
private fun QuickZoomSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    rotationDegrees: Float,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) AccentPistachio.copy(alpha = 0.92f) else Color.Transparent,
        contentColor = when {
            !enabled -> Color.White.copy(alpha = 0.28f)
            selected -> Color.Black.copy(alpha = 0.88f)
            else -> Color.White.copy(alpha = 0.82f)
        },
        shape = RoundedCornerShape(50),
        modifier = Modifier.clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 42.dp, minHeight = 32.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.rotate(rotationDegrees)
            )
        }
    }
}
