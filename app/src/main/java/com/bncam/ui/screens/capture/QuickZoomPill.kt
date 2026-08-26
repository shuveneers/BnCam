package com.bncam.ui.screens.capture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
internal fun QuickZoomPill(
    currentZoom: () -> Float,
    maxZoom: Float,
    uiRotationDegrees: Float,
    onZoomSelected: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentZoomValue = currentZoom()
    val safeMaxZoom = maxZoom.coerceAtLeast(1f)
    val twoXAvailable = safeMaxZoom >= 1.95f
    val activeTarget = if (
        twoXAvailable && abs(currentZoomValue - 2f) < abs(currentZoomValue - 1f)
    ) 2f else 1f

    Surface(
        modifier = modifier,
        color = Color(0xC91C1C1C),
        shape = RoundedCornerShape(50),
        border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.14f)),
        shadowElevation = 4.dp
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            QuickZoomSegment(
                label = "1x",
                selected = activeTarget == 1f,
                enabled = true,
                rotationDegrees = uiRotationDegrees,
                onClick = { onZoomSelected(1f) }
            )
            QuickZoomSegment(
                label = "2x",
                selected = activeTarget == 2f,
                enabled = twoXAvailable,
                rotationDegrees = uiRotationDegrees,
                onClick = { onZoomSelected(2f.coerceAtMost(safeMaxZoom)) }
            )
        }
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
