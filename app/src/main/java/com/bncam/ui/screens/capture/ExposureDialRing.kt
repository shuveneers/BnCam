package com.bncam.ui.screens.capture

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal enum class ExposureDialSide {
    SHUTTER_LEFT,
    ISO_RIGHT
}

/**
 * Compact manual-exposure control with an expanding camera-style dial ring.
 *
 * Vertical drag changes values continuously while the gesture is active. An inward horizontal
 * swipe resets the control to Auto. The callback is fired on every crossed detent; it is never
 * deferred until pointer-up.
 */
@Composable
internal fun ExposureDialRing(
    currentValue: () -> String,
    values: List<String>,
    side: ExposureDialSide,
    uiRotationDegrees: Float,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (values.isEmpty()) return

    val currentValueResolved = currentValue()
    var dragging by remember { mutableStateOf(false) }
    var verticalRemainderPx by remember { mutableFloatStateOf(0f) }
    var horizontalTravelPx by remember { mutableFloatStateOf(0f) }
    var selectedIndex by remember { mutableIntStateOf(values.indexOf(currentValueResolved).coerceAtLeast(0)) }
    var resetTriggered by remember { mutableStateOf(false) }

    val latestValue by rememberUpdatedState(currentValueResolved)
    val latestValues by rememberUpdatedState(values)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val pixelsPerDetent = with(density) { 14.dp.toPx() }
    val resetThresholdPx = with(density) { 58.dp.toPx() }

    Box(
        modifier = modifier
            .pointerInput(values, side) {
                var localIndex = 0
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        verticalRemainderPx = 0f
                        horizontalTravelPx = 0f
                        resetTriggered = false
                        localIndex = latestValues.indexOf(latestValue).coerceAtLeast(0)
                        selectedIndex = localIndex
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        horizontalTravelPx += dragAmount.x
                        val inwardDistance = when (side) {
                            ExposureDialSide.SHUTTER_LEFT -> horizontalTravelPx
                            ExposureDialSide.ISO_RIGHT -> -horizontalTravelPx
                        }
                        if (!resetTriggered && inwardDistance >= resetThresholdPx) {
                            resetTriggered = true
                            onReset()
                            localIndex = latestValues.indexOf("AUTO").coerceAtLeast(0)
                            selectedIndex = localIndex
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }

                        if (!resetTriggered) {
                            verticalRemainderPx -= dragAmount.y
                            val rawSteps = (verticalRemainderPx / pixelsPerDetent).toInt()
                            if (rawSteps != 0) {
                                val oldIndex = localIndex
                                val newIndex = (oldIndex + rawSteps).coerceIn(0, latestValues.lastIndex)
                                val movedSteps = newIndex - oldIndex
                                if (movedSteps != 0) {
                                    localIndex = newIndex
                                    selectedIndex = newIndex
                                    onValueChange(latestValues[newIndex])
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    verticalRemainderPx -= movedSteps * pixelsPerDetent
                                } else {
                                    verticalRemainderPx = verticalRemainderPx.coerceIn(
                                        -pixelsPerDetent,
                                        pixelsPerDetent
                                    )
                                }
                            }
                        }
                    },
                    onDragEnd = {
                        dragging = false
                        verticalRemainderPx = 0f
                        horizontalTravelPx = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        verticalRemainderPx = 0f
                        horizontalTravelPx = 0f
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.requiredSize(0.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = dragging,
                enter = fadeIn() + scaleIn(initialScale = 0.94f),
                exit = fadeOut() + scaleOut(targetScale = 0.96f)
            ) {
                ExposureDialArc(
                    values = values,
                    selectedIndex = selectedIndex,
                    fractionalDetent = (verticalRemainderPx / pixelsPerDetent).coerceIn(-1f, 1f),
                    side = side,
                    uiRotationDegrees = uiRotationDegrees
                )
            }
        }

        val activeColor = if (dragging) AccentPistachio else Color.White
        Surface(
            color = Color(0xB31D1D1D),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(
                0.75.dp,
                if (dragging) AccentPistachio.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.16f)
            )
        ) {
            Row(
                modifier = Modifier
                    .defaultMinSize(minWidth = 76.dp, minHeight = 42.dp)
                    .padding(horizontal = 11.dp, vertical = 8.dp)
                    .rotate(uiRotationDegrees),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (side == ExposureDialSide.ISO_RIGHT) {
                    Text(
                        text = "ISO",
                        color = AccentPistachio.copy(alpha = 0.78f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                }
                Text(
                    text = compactExposureValue(currentValueResolved, side),
                    color = activeColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                if (side == ExposureDialSide.SHUTTER_LEFT) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "S",
                        color = AccentPistachio.copy(alpha = 0.78f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (dragging) {
            val inwardDistance = when (side) {
                ExposureDialSide.SHUTTER_LEFT -> horizontalTravelPx
                ExposureDialSide.ISO_RIGHT -> -horizontalTravelPx
            }
            val progress = (inwardDistance / resetThresholdPx).coerceIn(0f, 1f)
            if (progress > 0.10f) {
                Text(
                    text = "AUTO",
                    color = AccentPistachio.copy(alpha = 0.45f + 0.55f * progress),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .offset(
                            x = if (side == ExposureDialSide.SHUTTER_LEFT) 70.dp else (-70).dp,
                            y = (-2).dp
                        )
                        .rotate(uiRotationDegrees)
                )
            }
        }
    }
}

@Composable
private fun ExposureDialArc(
    values: List<String>,
    selectedIndex: Int,
    fractionalDetent: Float,
    side: ExposureDialSide,
    uiRotationDegrees: Float
) {
    val ringWidth = 228.dp
    val ringHeight = 132.dp
    val centerX = 114.dp
    val centerY = 118.dp
    val labelRadius = 82.dp
    val ringOffsetX = if (side == ExposureDialSide.SHUTTER_LEFT) 38.dp else (-38).dp

    Box(
        modifier = Modifier
            .requiredSize(ringWidth, ringHeight)
            .offset(x = ringOffsetX, y = (-94).dp)
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = size.width / 2f
            val cy = size.height * 0.89f
            val radius = 92.dp.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.18f),
                startAngle = 205f,
                sweepAngle = 130f,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = Stroke(width = 1.dp.toPx())
            )

            val phaseDegrees = fractionalDetent * 5.5f
            for (i in -10..10) {
                val angle = 270f + i * 6.2f + phaseDegrees
                if (angle !in 205f..335f) continue
                val radians = angle * PI.toFloat() / 180f
                val major = i == 0 || i % 5 == 0
                val innerRadius = radius - if (major) 13.dp.toPx() else 7.dp.toPx()
                val outerRadius = radius
                val x1 = cx + cos(radians) * innerRadius
                val y1 = cy + sin(radians) * innerRadius
                val x2 = cx + cos(radians) * outerRadius
                val y2 = cy + sin(radians) * outerRadius
                drawLine(
                    color = if (i == 0) AccentPistachio else Color.White.copy(alpha = if (major) 0.58f else 0.30f),
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = if (i == 0) 2.dp.toPx() else 1.dp.toPx()
                )
            }
        }

        val labelAngles = listOf(232f, 251f, 270f, 289f, 308f)
        (-2..2).forEachIndexed { position, relativeIndex ->
            val index = (selectedIndex + relativeIndex).coerceIn(0, values.lastIndex)
            val angle = labelAngles[position]
            val radians = angle * PI.toFloat() / 180f
            val x = centerX + labelRadius * cos(radians) - 29.dp
            val y = centerY + labelRadius * sin(radians) - 8.dp
            val center = relativeIndex == 0
            Text(
                text = compactExposureValue(values[index], side),
                color = if (center) AccentPistachio else Color.White.copy(alpha = 0.58f),
                fontSize = if (center) 10.sp else 9.sp,
                fontWeight = if (center) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .offset(x = x, y = y)
                    .width(58.dp)
                    .rotate(uiRotationDegrees)
            )
        }
    }
}

private fun compactExposureValue(value: String, side: ExposureDialSide): String = when (side) {
    ExposureDialSide.ISO_RIGHT -> value.removePrefix("ISO ")
    ExposureDialSide.SHUTTER_LEFT -> value
}
