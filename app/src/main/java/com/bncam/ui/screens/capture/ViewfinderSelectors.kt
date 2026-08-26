package com.bncam.ui.screens.capture

import android.hardware.camera2.CameraCharacteristics
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.bncam.core.engine.LensInfo
import com.bncam.data.profile.CameraProfile

private class AnchorPopupPositionProvider(
    private val placeAbove: Boolean,
    private val gapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val centeredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val x = centeredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val preferredY = if (placeAbove) {
            anchorBounds.top - popupContentSize.height - gapPx
        } else {
            anchorBounds.bottom + gapPx
        }
        val fallbackY = if (placeAbove) anchorBounds.bottom + gapPx else anchorBounds.top - popupContentSize.height - gapPx
        val fitsPreferred = preferredY >= 0 && preferredY + popupContentSize.height <= windowSize.height
        val y = (if (fitsPreferred) preferredY else fallbackY)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

/**
 * Places the lens arc so its internal origin coincides with the centre of the master lens button.
 * The arc grows only left/up from that origin, so a master button near the right screen edge no
 * longer forces Popup clamping to shift the visual selector away from the user's finger.
 */
private class LensArcPopupPositionProvider(
    private val originXPx: Int,
    private val originYPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val anchorCenterX = anchorBounds.left + anchorBounds.width / 2
        val anchorCenterY = anchorBounds.top + anchorBounds.height / 2
        return IntOffset(
            x = (anchorCenterX - originXPx).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y = (anchorCenterY - originYPx).coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ViewfinderProfileSelector(
    activeProfile: CameraProfile,
    visibleProfiles: List<CameraProfile>,
    onProfileSelected: (CameraProfile) -> Unit,
    onOpenProfileSettings: (CameraProfile) -> Unit,
    modifier: Modifier = Modifier,
    uiRotationDegrees: Float = 0f
) {
    var expanded by remember { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = remember(gapPx) { AnchorPopupPositionProvider(placeAbove = false, gapPx = gapPx) }
    val orderedProfiles = remember(visibleProfiles) {
        visibleProfiles.sortedWith(
            compareBy<CameraProfile> { if (it.id.endsWith("_disabled")) 0 else 1 }
                .thenBy {
                    it.id.substringAfterLast("_profile_", "999").toIntOrNull() ?: 999
                }
                .thenBy { it.id }
        )
    }

    Box(modifier = modifier) {
        Surface(
            shape = CircleShape,
            color = Color.DarkGray.copy(alpha = 0.6f),
            modifier = Modifier
                .border(0.5.dp, AccentPistachio.copy(alpha = 0.5f), CircleShape)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = {
                        if (!activeProfile.id.endsWith("_disabled")) onOpenProfileSettings(activeProfile)
                    }
                )
        ) {
            Box(
                modifier = Modifier.defaultMinSize(minWidth = 96.dp, minHeight = 40.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = activeProfile.name.replaceFirstChar { it.uppercase() },
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }
        }

        if (expanded) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xEE151515),
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        orderedProfiles.chunked(3).forEach { rowProfiles ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowProfiles.forEach { profile ->
                                    val active = profile.id == activeProfile.id
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (active) AccentPistachio.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.08f),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(58.dp)
                                            .border(
                                                width = if (active) 1.5.dp else 0.5.dp,
                                                color = if (active) AccentPistachio else Color.White.copy(alpha = 0.18f),
                                                shape = RoundedCornerShape(16.dp)
                                            )
                                            .combinedClickable(
                                                onClick = {
                                                    onProfileSelected(profile)
                                                    expanded = false
                                                },
                                                onLongClick = {
                                                    expanded = false
                                                    if (!profile.id.endsWith("_disabled")) onOpenProfileSettings(profile)
                                                }
                                            )
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = profile.name.replaceFirstChar { it.uppercase() },
                                                color = if (active) AccentPistachio else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 1,
                                                modifier = Modifier
                                                    .padding(horizontal = 6.dp)
                                                    .rotate(uiRotationDegrees)
                                            )
                                        }
                                    }
                                }
                                repeat(3 - rowProfiles.size) {
                                    Box(modifier = Modifier.weight(1f).height(58.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ViewfinderLensSelector(
    activeLens: LensInfo,
    visibleLenses: List<LensInfo>,
    uiRotationDegrees: Float,
    onLensSelected: (LensInfo) -> Unit,
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    BackHandler(enabled = expanded) {
        expanded = false
    }
    var dragVector by remember { mutableStateOf(Offset.Zero) }
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }

    // Required interaction order: selfie first on the horizontal left, then rear cameras from
    // widest to longest in one continuous arc ending vertically above the master button.
    val selectorLenses = remember(visibleLenses) {
        visibleLenses.sortedWith(
            compareBy<LensInfo> { if (it.facing == CameraCharacteristics.LENS_FACING_FRONT) 0 else 1 }
                .thenBy { it.opticalZoomRatio }
                .thenBy { it.id }
        )
    }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val minimumDragPx = with(density) { 20.dp.toPx() }
    // Keep at least ~56 dp center-to-center spacing along the 90 degree arc. This scales
    // automatically when devices expose more than four physical/logical lens routes.
    val radiusDp = maxOf(104f, 38f * (selectorLenses.size - 1).coerceAtLeast(1))
    val popupExtentDp = radiusDp + 62f
    val popupWidth = popupExtentDp.dp
    val popupHeight = popupExtentDp.dp
    val originX = (popupExtentDp - 26f).dp
    val originY = (popupExtentDp - 26f).dp
    val originXPx = with(density) { originX.roundToPx() }
    val originYPx = with(density) { originY.roundToPx() }
    val positionProvider = remember(originXPx, originYPx) {
        LensArcPopupPositionProvider(originXPx = originXPx, originYPx = originYPx)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = Color.DarkGray.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxSize()
                .rotate(uiRotationDegrees)
                .border(0.5.dp, AccentPistachio.copy(alpha = 0.5f), CircleShape)
                .pointerInput(selectorLenses, hapticsEnabled) {
                    detectDragGestures(
                        onDragStart = {
                            expanded = true
                            dragVector = Offset.Zero
                            hoveredIndex = null
                        },
                        onDragEnd = {
                            hoveredIndex?.let { index -> selectorLenses.getOrNull(index)?.let(onLensSelected) }
                            expanded = false
                            dragVector = Offset.Zero
                            hoveredIndex = null
                        },
                        onDragCancel = {
                            expanded = false
                            dragVector = Offset.Zero
                            hoveredIndex = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragVector += dragAmount
                            val nextHovered = nearestRadialSelectorIndex(
                                dragX = dragVector.x,
                                dragY = dragVector.y,
                                count = selectorLenses.size,
                                minimumDistance = minimumDragPx
                            )
                            if (nextHovered != hoveredIndex) {
                                hoveredIndex = nextHovered
                                if (nextHovered != null && hapticsEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                        }
                    )
                }
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { expanded = true }
                )
        ) {
            Box(contentAlignment = Alignment.Center) {
                LensSelectorContent(
                    lens = activeLens,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (expanded && selectorLenses.isNotEmpty()) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true)
            ) {
                val expansion = remember { Animatable(0f) }
                LaunchedEffect(Unit) { expansion.animateTo(1f, tween(durationMillis = 180)) }
                Box(modifier = Modifier.width(popupWidth).height(popupHeight)) {
                    if (dragVector.getDistance() >= minimumDragPx) {
                        val rawX = originX.value + with(density) { dragVector.x.toDp().value }
                        val rawY = originY.value + with(density) { dragVector.y.toDp().value }
                        val attractedTarget = hoveredIndex?.let {
                            radialSelectorPoint(it, selectorLenses.size, radius = radiusDp)
                        }
                        val pull = if (attractedTarget != null) 0.62f else 0f
                        val targetX = attractedTarget?.let { originX.value + it.x } ?: rawX
                        val targetY = attractedTarget?.let { originY.value + it.y } ?: rawY
                        val haloX = rawX * (1f - pull) + targetX * pull
                        val haloY = rawY * (1f - pull) + targetY * pull
                        Surface(
                            shape = CircleShape,
                            color = AccentPistachio.copy(alpha = 0.12f),
                            modifier = Modifier
                                .offset(x = (haloX - 23f).dp, y = (haloY - 23f).dp)
                                .size(46.dp)
                                .border(1.5.dp, AccentPistachio.copy(alpha = 0.72f), CircleShape)
                        ) {}
                    }

                    selectorLenses.forEachIndexed { index, lens ->
                        val target = radialSelectorPoint(index, selectorLenses.size, radius = radiusDp)
                        val centerX = originX.value + target.x * expansion.value
                        val centerY = originY.value + target.y * expansion.value
                        val active = lens.id == activeLens.id
                        val hovered = hoveredIndex == index
                        Surface(
                            shape = CircleShape,
                            color = when {
                                hovered -> AccentPistachio.copy(alpha = 0.36f)
                                active -> AccentPistachio.copy(alpha = 0.22f)
                                else -> Color(0xE6202020)
                            },
                            modifier = Modifier
                                .offset(x = (centerX - 24f).dp, y = (centerY - 24f).dp)
                                .size(48.dp)
                                .rotate(uiRotationDegrees)
                                .scale(if (hovered) 1.16f else 1f)
                                .border(
                                    width = when { hovered -> 2.dp; active -> 1.5.dp; else -> 0.5.dp },
                                    color = when { hovered || active -> AccentPistachio; else -> Color.White.copy(alpha = 0.25f) },
                                    shape = CircleShape
                                )
                                .combinedClickable(
                                    onClick = {
                                        onLensSelected(lens)
                                        expanded = false
                                    },
                                    onLongClick = {}
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                LensSelectorContent(
                                    lens = lens,
                                    color = if (active || hovered) AccentPistachio else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LensSelectorContent(
    lens: LensInfo,
    color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight
) {
    if (lens.facing == CameraCharacteristics.LENS_FACING_FRONT) {
        Icon(
            imageVector = Icons.Default.Cameraswitch,
            contentDescription = "Switch camera",
            tint = color,
            modifier = Modifier.size(22.dp)
        )
    } else {
        Text(
            text = formatLensZoomRatio(lens.opticalZoomRatio),
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1
        )
    }
}
