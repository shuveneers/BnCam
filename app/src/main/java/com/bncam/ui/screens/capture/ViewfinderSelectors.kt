package com.bncam.ui.screens.capture

import android.hardware.camera2.CameraCharacteristics
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Settings
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
    activeStreamLabel: String,
    onProfileSelected: (CameraProfile) -> Unit,
    onOpenProfileSettings: (CameraProfile) -> Unit,
    modifier: Modifier = Modifier,
    uiRotationDegrees: Float = 0f
) {
    var expanded by remember { mutableStateOf(false) }
    val activeProfileDisabled = activeProfile.id.endsWith("_disabled")
    BackHandler(enabled = expanded) { expanded = false }

    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = remember(gapPx) {
        AnchorPopupPositionProvider(placeAbove = false, gapPx = gapPx)
    }
    val disabledProfileEntry = remember(visibleProfiles) {
        visibleProfiles.firstOrNull { it.id.endsWith("_disabled") }
    }
    val orderedProfiles = remember(visibleProfiles) {
        visibleProfiles
            .filterNot { it.id.endsWith("_disabled") }
            .sortedWith(
                compareBy<CameraProfile> {
                    it.id.substringAfterLast("_profile_", "999").toIntOrNull() ?: 999
                }.thenBy { it.id }
            )
    }

    Box(modifier = modifier) {
        Surface(
            shape = CircleShape,
            color = Color(0xC92A2A2A),
            shadowElevation = 3.dp,
            modifier = Modifier
                .border(0.75.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = {
                        if (!activeProfileDisabled) onOpenProfileSettings(activeProfile)
                    }
                )
        ) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 96.dp, minHeight = 40.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = activeProfile.name.replaceFirstChar { it.uppercase() },
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }
        }

        if (expanded) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xF2181818),
                    shadowElevation = 12.dp,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = 0.12f)
                    ),
                    modifier = Modifier.width(352.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Surface(
                                    color = Color.White.copy(alpha = 0.055f),
                                    shape = RoundedCornerShape(50),
                                    border = androidx.compose.foundation.BorderStroke(
                                        0.75.dp,
                                        Color.White.copy(alpha = 0.10f)
                                    )
                                ) {
                                    Text(
                                        text = activeStreamLabel,
                                        color = AccentPistachio.copy(alpha = 0.94f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .padding(horizontal = 10.dp, vertical = 7.dp)
                                            .rotate(uiRotationDegrees)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier.weight(1.35f),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    color = if (activeProfileDisabled) {
                                        AccentPistachio.copy(alpha = 0.14f)
                                    } else {
                                        Color.White.copy(alpha = 0.055f)
                                    },
                                    shape = RoundedCornerShape(50),
                                    border = androidx.compose.foundation.BorderStroke(
                                        if (activeProfileDisabled) 1.dp else 0.75.dp,
                                        if (activeProfileDisabled) {
                                            AccentPistachio.copy(alpha = 0.62f)
                                        } else {
                                            Color.White.copy(alpha = 0.12f)
                                        }
                                    ),
                                    modifier = Modifier.combinedClickable(
                                        enabled = disabledProfileEntry != null,
                                        onClick = {
                                            disabledProfileEntry?.let { disabled ->
                                                onProfileSelected(disabled)
                                                expanded = false
                                            }
                                        },
                                        onLongClick = {}
                                    )
                                ) {
                                    Text(
                                        text = disabledProfileEntry?.name
                                            ?.replaceFirstChar { it.uppercase() }
                                            ?: "Disabled",
                                        color = if (activeProfileDisabled) {
                                            AccentPistachio
                                        } else {
                                            Color.White.copy(alpha = 0.82f)
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = if (activeProfileDisabled) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        modifier = Modifier
                                            .padding(horizontal = 12.dp, vertical = 7.dp)
                                            .rotate(uiRotationDegrees)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (activeProfileDisabled) {
                                        Color.White.copy(alpha = 0.035f)
                                    } else {
                                        AccentPistachio.copy(alpha = 0.12f)
                                    },
                                    border = androidx.compose.foundation.BorderStroke(
                                        0.75.dp,
                                        if (activeProfileDisabled) {
                                            Color.White.copy(alpha = 0.09f)
                                        } else {
                                            AccentPistachio.copy(alpha = 0.44f)
                                        }
                                    ),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .combinedClickable(
                                            enabled = !activeProfileDisabled,
                                            onClick = {
                                                expanded = false
                                                onOpenProfileSettings(activeProfile)
                                            },
                                            onLongClick = {}
                                        )
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Profile settings",
                                            tint = if (activeProfileDisabled) {
                                                Color.White.copy(alpha = 0.24f)
                                            } else {
                                                AccentPistachio
                                            },
                                            modifier = Modifier
                                                .size(18.dp)
                                                .rotate(uiRotationDegrees)
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                drawLine(
                                    color = Color.White.copy(alpha = 0.10f),
                                    start = Offset.Zero,
                                    end = Offset(size.width, 0f),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }
                        }

                        orderedProfiles.chunked(3).forEach { rowProfiles ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowProfiles.forEach { profile ->
                                    val active = profile.id == activeProfile.id
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (active) {
                                            AccentPistachio.copy(alpha = 0.16f)
                                        } else {
                                            Color.White.copy(alpha = 0.055f)
                                        },
                                        border = androidx.compose.foundation.BorderStroke(
                                            width = if (active) 1.25.dp else 0.75.dp,
                                            color = if (active) {
                                                AccentPistachio.copy(alpha = 0.78f)
                                            } else {
                                                Color.White.copy(alpha = 0.13f)
                                            }
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(58.dp)
                                            .combinedClickable(
                                                onClick = {
                                                    onProfileSelected(profile)
                                                    expanded = false
                                                },
                                                onLongClick = {
                                                    expanded = false
                                                    if (!profile.id.endsWith("_disabled")) {
                                                        onOpenProfileSettings(profile)
                                                    }
                                                }
                                            )
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = profile.name.replaceFirstChar { it.uppercase() },
                                                color = if (active) AccentPistachio else Color.White.copy(alpha = 0.90f),
                                                fontSize = 12.sp,
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
                                    Spacer(modifier = Modifier.weight(1f).height(58.dp))
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
    onExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    hapticsEnabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        onExpandedChange(value)
    }
    BackHandler(enabled = expanded) {
        setExpanded(false)
    }
    var dragVector by remember { mutableStateOf(Offset.Zero) }
    var hoveredIndex by remember { mutableStateOf<Int?>(null) }

    // Rear cameras own arc 1: widest exactly left of the master, longest exactly above it,
    // and intermediate cameras distributed along the quarter circle. With three rear cameras
    // the selfie occupies arc 2 directly above the upper rear stop. With only one or two rear
    // cameras the selfie folds into arc 1 so the selector never leaves an unnecessary empty ring.
    val rearLenses = remember(visibleLenses) {
        visibleLenses
            .filter { it.facing != CameraCharacteristics.LENS_FACING_FRONT }
            .sortedWith(compareBy<LensInfo> { it.opticalZoomRatio }.thenBy { it.id })
    }
    val selfieLens = remember(visibleLenses) {
        visibleLenses.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
    }
    val selectorLenses = remember(rearLenses, selfieLens) {
        rearLenses + listOfNotNull(selfieLens)
    }

    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val minimumDragPx = with(density) { 18.dp.toPx() }

    // Slightly smaller than the previous visual pass. Geometry is intentionally fixed around
    // the master button so switching active lenses never changes the selector layout.
    val buttonDiameterDp = 42f
    val buttonRadiusDp = buttonDiameterDp / 2f
    val rearRadiusDp = 112f
    val selfieGapDp = 58f
    val selfieUsesOuterArc = selfieLens != null && rearLenses.size >= 3
    val outerArcExtensionDp = if (selfieUsesOuterArc) selfieGapDp else 0f
    val popupWidthDp = rearRadiusDp + 92f
    val popupHeightDp = rearRadiusDp + outerArcExtensionDp + 88f
    val originX = (popupWidthDp - 38f).dp
    val originY = (popupHeightDp - 38f).dp
    val popupWidth = popupWidthDp.dp
    val popupHeight = popupHeightDp.dp
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
                            setExpanded(true)
                            dragVector = Offset.Zero
                            hoveredIndex = null
                        },
                        onDragEnd = {
                            hoveredIndex?.let { index -> selectorLenses.getOrNull(index)?.let(onLensSelected) }
                            setExpanded(false)
                            dragVector = Offset.Zero
                            hoveredIndex = null
                        },
                        onDragCancel = {
                            setExpanded(false)
                            dragVector = Offset.Zero
                            hoveredIndex = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragVector += dragAmount
                            val nextHovered = nearestLensSelectorIndex(
                                dragX = dragVector.x,
                                dragY = dragVector.y,
                                rearCount = rearLenses.size,
                                hasSelfie = selfieLens != null,
                                rearRadius = with(density) { rearRadiusDp.dp.toPx() },
                                selfieGap = with(density) { selfieGapDp.dp.toPx() },
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
                    onClick = { setExpanded(!expanded) },
                    onLongClick = { setExpanded(true) }
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
                onDismissRequest = { setExpanded(false) },
                properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true)
            ) {
                val expansion = remember { Animatable(0f) }
                LaunchedEffect(Unit) { expansion.animateTo(1f, tween(durationMillis = 180)) }

                Box(modifier = Modifier.width(popupWidth).height(popupHeight)) {
                    // CameraScreen draws the shade above the viewfinder/shutter. Re-draw the
                    // master lens button in this Popup so it stays crisp above that shade and so
                    // tapping the same lens indicator a second time always closes Floating mode.
                    Surface(
                        shape = CircleShape,
                        color = Color.DarkGray.copy(alpha = 0.60f),
                        modifier = Modifier
                            .offset(x = (originX.value - 36f).dp, y = (originY.value - 36f).dp)
                            .size(72.dp)
                            .rotate(uiRotationDegrees)
                            .border(0.5.dp, AccentPistachio.copy(alpha = 0.5f), CircleShape)
                            .combinedClickable(
                                onClick = { setExpanded(false) },
                                onLongClick = { setExpanded(false) }
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

                    if (dragVector.getDistance() >= minimumDragPx) {
                        val rawX = originX.value + with(density) { dragVector.x.toDp().value }
                        val rawY = originY.value + with(density) { dragVector.y.toDp().value }
                        val attractedTarget = hoveredIndex?.let { index ->
                            lensSelectorPoint(
                                index = index,
                                rearCount = rearLenses.size,
                                hasSelfie = selfieLens != null,
                                rearRadius = rearRadiusDp,
                                selfieGap = selfieGapDp
                            )
                        }
                        val pull = if (attractedTarget != null) 0.66f else 0f
                        val targetX = attractedTarget?.let { originX.value + it.x } ?: rawX
                        val targetY = attractedTarget?.let { originY.value + it.y } ?: rawY
                        val haloX = rawX * (1f - pull) + targetX * pull
                        val haloY = rawY * (1f - pull) + targetY * pull
                        Surface(
                            shape = CircleShape,
                            color = AccentPistachio.copy(alpha = 0.11f),
                            modifier = Modifier
                                .offset(x = (haloX - buttonRadiusDp).dp, y = (haloY - buttonRadiusDp).dp)
                                .size(buttonDiameterDp.dp)
                                .border(1.5.dp, AccentPistachio.copy(alpha = 0.74f), CircleShape)
                        ) {}
                    }

                    selectorLenses.forEachIndexed { index, lens ->
                        val target = lensSelectorPoint(
                            index = index,
                            rearCount = rearLenses.size,
                            hasSelfie = selfieLens != null,
                            rearRadius = rearRadiusDp,
                            selfieGap = selfieGapDp
                        )
                        val centerX = originX.value + target.x * expansion.value
                        val centerY = originY.value + target.y * expansion.value
                        val active = lens.id == activeLens.id
                        val hovered = hoveredIndex == index
                        Surface(
                            shape = CircleShape,
                            color = when {
                                hovered -> AccentPistachio.copy(alpha = 0.34f)
                                active -> AccentPistachio.copy(alpha = 0.22f)
                                else -> Color(0xE6202020)
                            },
                            modifier = Modifier
                                .offset(x = (centerX - buttonRadiusDp).dp, y = (centerY - buttonRadiusDp).dp)
                                .size(buttonDiameterDp.dp)
                                .rotate(uiRotationDegrees)
                                .scale(if (hovered) 1.12f else 1f)
                                .border(
                                    width = when { hovered -> 2.dp; active -> 1.5.dp; else -> 0.5.dp },
                                    color = when { hovered || active -> AccentPistachio; else -> Color.White.copy(alpha = 0.25f) },
                                    shape = CircleShape
                                )
                                .combinedClickable(
                                    onClick = {
                                        onLensSelected(lens)
                                        setExpanded(false)
                                    },
                                    onLongClick = {}
                                )
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                LensSelectorContent(
                                    lens = lens,
                                    color = if (active || hovered) AccentPistachio else Color.White,
                                    fontSize = 11.sp,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ViewfinderLensPopupList(
    activeLens: LensInfo,
    visibleLenses: List<LensInfo>,
    uiRotationDegrees: Float,
    onLensSelected: (LensInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }

    val orderedLenses = remember(visibleLenses) {
        val selfie = visibleLenses
            .filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            .sortedBy { it.id }
        val rear = visibleLenses
            .filter { it.facing != CameraCharacteristics.LENS_FACING_FRONT }
            .sortedWith(compareByDescending<LensInfo> { it.opticalZoomRatio }.thenBy { it.id })
        selfie + rear
    }
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val positionProvider = remember(gapPx) {
        AnchorPopupPositionProvider(placeAbove = true, gapPx = gapPx)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = Color.DarkGray.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxSize()
                .rotate(uiRotationDegrees)
                .border(0.5.dp, AccentPistachio.copy(alpha = 0.5f), CircleShape)
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = { expanded = !expanded }
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

        if (expanded && orderedLenses.isNotEmpty()) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xF21A1A1A),
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .width(154.dp)
                        .border(0.75.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        orderedLenses.forEach { lens ->
                            val active = lens.id == activeLens.id
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (active) {
                                    AccentPistachio.copy(alpha = 0.10f)
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp)
                                    .combinedClickable(
                                        onClick = {
                                            onLensSelected(lens)
                                            expanded = false
                                        },
                                        onLongClick = {}
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp)
                                        .rotate(uiRotationDegrees),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    if (lens.facing == CameraCharacteristics.LENS_FACING_FRONT) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Cameraswitch,
                                                contentDescription = null,
                                                tint = if (active) AccentPistachio else Color.White.copy(alpha = 0.88f),
                                                modifier = Modifier.size(17.dp)
                                            )
                                            Text(
                                                text = "Selfie",
                                                color = if (active) AccentPistachio else Color.White.copy(alpha = 0.88f),
                                                fontSize = 12.sp,
                                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = formatLensZoomRatio(lens.opticalZoomRatio),
                                            color = if (active) AccentPistachio else Color.White.copy(alpha = 0.88f),
                                            fontSize = 12.sp,
                                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }

                                    if (active) {
                                        Text(
                                            text = "Active",
                                            color = AccentPistachio.copy(alpha = 0.78f),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ViewfinderLensList(
    activeLens: LensInfo,
    visibleLenses: List<LensInfo>,
    uiRotationDegrees: Float,
    onLensSelected: (LensInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val orderedLenses = remember(visibleLenses) {
        val selfie = visibleLenses
            .filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }
            .sortedBy { it.id }
        val rear = visibleLenses
            .filter { it.facing != CameraCharacteristics.LENS_FACING_FRONT }
            .sortedWith(compareByDescending<LensInfo> { it.opticalZoomRatio }.thenBy { it.id })
        selfie + rear
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        orderedLenses.forEach { lens ->
            val active = lens.id == activeLens.id
            Surface(
                shape = RoundedCornerShape(17.dp),
                color = if (active) AccentPistachio.copy(alpha = 0.18f) else Color(0xCC202020),
                modifier = Modifier
                    .width(58.dp)
                    .height(34.dp)
                    .border(
                        width = if (active) 1.5.dp else 0.5.dp,
                        color = if (active) AccentPistachio else Color.White.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(17.dp)
                    )
                    .combinedClickable(
                        onClick = { onLensSelected(lens) },
                        onLongClick = {}
                    )
            ) {
                Box(
                    modifier = Modifier.rotate(uiRotationDegrees),
                    contentAlignment = Alignment.Center
                ) {
                    LensSelectorContent(
                        lens = lens,
                        color = if (active) AccentPistachio else Color.White.copy(alpha = 0.88f),
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
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
