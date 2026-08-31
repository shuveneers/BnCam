package com.bncam.ui.screens.capture

import com.bncam.core.capture.MeteringMode
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.quality.DefaultIspProfile
import com.bncam.R
import com.bncam.core.capture.LiveRgbHistogram
import com.bncam.core.engine.FocusPeakingGuidance
import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.Face
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.bncam.core.debug.ShotLogger
import com.bncam.core.engine.BnCameraManager
import com.bncam.core.engine.CameraStreamGeometryPolicy
import com.bncam.core.engine.LensInfo
import com.bncam.core.capture.ViewfinderMode
import com.bncam.core.output.CaptureProcessingQueue
import com.bncam.core.quality.ViewfinderLiveTuning
import com.bncam.core.output.CaptureWorkState
import com.bncam.data.profile.CameraProfile
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.ProfileAwbSettings
import com.bncam.data.settings.ViewfinderSliderAssignment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val AccentPistachio = Color(0xFFB2D3A8)

private fun Context.findActivityLifecycleOwner(): LifecycleOwner? {
    var current: Context? = this
    while (current != null) {
        if (current is ComponentActivity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}

// ==========================================
// MEDIASTORE HELPER FUNCTIE
// ==========================================
fun getLatestImageFromMediaStore(context: Context): Uri? {
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
    } catch (e: Exception) {
        Log.e("CameraScreen", "Kon MediaStore niet uitlezen", e)
    }
    return null
}

private fun logarithmicIntValue(fraction: Float, minValue: Int, maxValue: Int): Int {
    if (minValue <= 0 || maxValue <= minValue) return minValue
    val f = fraction.coerceIn(0f, 1f).toDouble()
    return exp(ln(minValue.toDouble()) + f * (ln(maxValue.toDouble()) - ln(minValue.toDouble())))
        .roundToInt()
        .coerceIn(minValue, maxValue)
}

private fun logarithmicLongValue(fraction: Float, minValue: Long, maxValue: Long): Long {
    if (minValue <= 0L || maxValue <= minValue) return minValue
    val f = fraction.coerceIn(0f, 1f).toDouble()
    return exp(ln(minValue.toDouble()) + f * (ln(maxValue.toDouble()) - ln(minValue.toDouble())))
        .toLong()
        .coerceIn(minValue, maxValue)
}

private fun kelvinToPerceptualFraction(kelvin: Int): Float {
    val clamped = kelvin.coerceIn(2000, 10000).toDouble()
    val warmMired = 1_000_000.0 / 2000.0
    val coolMired = 1_000_000.0 / 10000.0
    val currentMired = 1_000_000.0 / clamped
    return ((warmMired - currentMired) / (warmMired - coolMired)).toFloat().coerceIn(0f, 1f)
}

private fun perceptualFractionToKelvin(fraction: Float): Int {
    val warmMired = 1_000_000.0 / 2000.0
    val coolMired = 1_000_000.0 / 10000.0
    val mired = warmMired - fraction.coerceIn(0f, 1f) * (warmMired - coolMired)
    return (1_000_000.0 / mired.coerceAtLeast(1.0)).roundToInt().coerceIn(2000, 10000)
}


private fun parseIsoString(isoStr: String): Int? {
    if (isoStr == "AUTO") return null
    return isoStr.removePrefix("ISO ").toIntOrNull()
}

private fun parseShutterString(shutterStr: String): Long? {
    if (shutterStr == "AUTO") return null
    return runCatching {
        when {
            shutterStr.endsWith("s") -> (shutterStr.removeSuffix("s").toDouble() * 1_000_000_000.0).toLong()
            shutterStr.startsWith("1/") -> 1_000_000_000L / shutterStr.removePrefix("1/").toLong().coerceAtLeast(1L)
            else -> null
        }
    }.getOrNull()
}

private fun formatShutterNs(ns: Long): String {
    if (ns >= 1_000_000_000L) {
        val seconds = ns / 1_000_000_000.0
        return if (kotlin.math.abs(seconds - seconds.toLong()) < 0.02) "${seconds.toLong()}s"
        else String.format(java.util.Locale.US, "%.1fs", seconds)
    }
    val denominator = (1_000_000_000.0 / ns.coerceAtLeast(1L)).roundToInt().coerceAtLeast(1)
    return "1/$denominator"
}

private fun rotateNormalizedPointForDisplay(x: Float, y: Float, clockwiseDegrees: Int): Pair<Float, Float> {
    val normalized = ((clockwiseDegrees % 360) + 360) % 360
    return when (normalized) {
        90 -> (1f - y) to x
        180 -> (1f - x) to (1f - y)
        270 -> y to (1f - x)
        else -> x to y
    }
}

private fun undoPreviewOrientationCorrection(x: Float, y: Float, clockwiseDegrees: Int): Pair<Float, Float> {
    return rotateNormalizedPointForDisplay(x, y, 360 - clockwiseDegrees)
}

data class FocusPeakingDisplayTarget(
    val active: Boolean = false,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val radiusX: Float = 0.5f,
    val radiusY: Float = 0.5f
)

private fun focusPeakingDisplayTarget(
    guidance: FocusPeakingGuidance,
    focusOwner: com.bncam.core.engine.FocusOwner,
    sensorOrientation: Int,
    lensFacing: Int,
    previewOrientationCorrectionDegrees: Int
): FocusPeakingDisplayTarget {
    val region = guidance.afRegion ?: return FocusPeakingDisplayTarget()
    val bounds = guidance.coordinateBounds ?: return FocusPeakingDisplayTarget()
    // Camera2 may report a broad/default AF region during continuous AUTO. That is an AF search
    // domain, not a subject ROI, and treating it as one lowers peaking selectivity over most of
    // the frame. Spatial peaking guidance is reserved for an explicitly localized focus owner.
    val localizedFocusOwner = when (focusOwner) {
        com.bncam.core.engine.FocusOwner.TAP,
        com.bncam.core.engine.FocusOwner.TRACK_ACQUIRING,
        com.bncam.core.engine.FocusOwner.TRACK_TIMED,
        com.bncam.core.engine.FocusOwner.TRACK_PINNED,
        com.bncam.core.engine.FocusOwner.FACE_PRIORITY,
        com.bncam.core.engine.FocusOwner.AE_AF_LOCK -> true
        else -> false
    }
    if (!guidance.subjectRoiUsed || !localizedFocusOwner || region.isEmpty ||
        bounds.width() <= 0 || bounds.height() <= 0
    ) {
        return FocusPeakingDisplayTarget()
    }
    val nx = ((region.centerX() - bounds.left).toFloat() / bounds.width()).coerceIn(0f, 1f)
    val ny = ((region.centerY() - bounds.top).toFloat() / bounds.height()).coerceIn(0f, 1f)
    var rx = (region.width().toFloat() / bounds.width() * 0.65f).coerceIn(0.04f, 0.35f)
    var ry = (region.height().toFloat() / bounds.height() * 0.65f).coerceIn(0.04f, 0.35f)

    val base = when (sensorOrientation) {
        90 -> if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            (1f - ny) to nx
        } else {
            (1f - ny) to (1f - nx)
        }
        270 -> if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
            ny to (1f - nx)
        } else {
            ny to nx
        }
        180 -> (1f - nx) to (1f - ny)
        else -> nx to ny
    }
    if (sensorOrientation == 90 || sensorOrientation == 270) {
        val swap = rx
        rx = ry
        ry = swap
    }
    val corrected = rotateNormalizedPointForDisplay(
        base.first,
        base.second,
        previewOrientationCorrectionDegrees
    )
    val correction = ((previewOrientationCorrectionDegrees % 360) + 360) % 360
    if (correction == 90 || correction == 270) {
        val swap = rx
        rx = ry
        ry = swap
    }
    return FocusPeakingDisplayTarget(
        active = true,
        centerX = corrected.first.coerceIn(0f, 1f),
        centerY = corrected.second.coerceIn(0f, 1f),
        radiusX = rx,
        radiusY = ry
    )
}

private fun logarithmicFraction(value: Long, minValue: Long, maxValue: Long): Float {
    if (minValue <= 0L || maxValue <= minValue) return 0f
    val v = value.coerceIn(minValue, maxValue).toDouble()
    return ((ln(v) - ln(minValue.toDouble())) / (ln(maxValue.toDouble()) - ln(minValue.toDouble())))
        .toFloat().coerceIn(0f, 1f)
}

private fun adaptiveIsoLabels(bounds: ManualExposureBounds?): List<String> {
    bounds ?: return listOf("AUTO")
    val values = (0..24).map { step ->
        logarithmicIntValue(step / 24f, bounds.minIso, bounds.maxIso)
    }.distinct().sorted()
    return listOf("AUTO") + values.map { "ISO $it" }
}

private fun adaptiveShutterLabels(bounds: ManualExposureBounds?): List<String> {
    bounds ?: return listOf("AUTO")
    val values = (0..28).map { step ->
        logarithmicLongValue(step / 28f, bounds.minExposureNs, bounds.maxExposureNs)
    }.distinct().sorted()
    return listOf("AUTO") + values.map(::formatShutterNs).distinct()
}

@Composable
fun CameraModeSelector(
    selectedMode: ViewfinderMode,
    onModeSelected: (ViewfinderMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = remember {
        listOf(ViewfinderMode.NIGHT, ViewfinderMode.PHOTO, ViewfinderMode.PORTRAIT, ViewfinderMode.VIDEO)
    }
    var targetIndex by remember { mutableIntStateOf(modes.indexOf(selectedMode).coerceAtLeast(0)) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val itemSpacingPx = with(density) { 88.dp.toPx() }
    val commitThresholdPx = with(density) { 58.dp.toPx() }
    val dragResistance = 0.42f

    LaunchedEffect(selectedMode) {
        val resolvedIndex = modes.indexOf(selectedMode)
        if (resolvedIndex >= 0 && resolvedIndex != targetIndex) {
            targetIndex = resolvedIndex
            dragOffsetPx = 0f
        }
    }

    val animatedIndex by animateFloatAsState(
        targetValue = targetIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "mode_snap"
    )
    val currentIndexFloat = animatedIndex - (dragOffsetPx / itemSpacingPx)

    Box(
        modifier = modifier
            .height(40.dp)
            .pointerInput(targetIndex, modes) {
                var gestureDragPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        gestureDragPx = 0f
                        dragOffsetPx = 0f
                    },
                    onDragEnd = {
                        val direction = when {
                            gestureDragPx <= -commitThresholdPx -> 1
                            gestureDragPx >= commitThresholdPx -> -1
                            else -> 0
                        }
                        val nextIndex = (targetIndex + direction).coerceIn(0, modes.size - 1)
                        targetIndex = nextIndex
                        dragOffsetPx = 0f
                        if (nextIndex != modes.indexOf(selectedMode)) {
                            onModeSelected(modes[nextIndex])
                        }
                    },
                    onDragCancel = {
                        gestureDragPx = 0f
                        dragOffsetPx = 0f
                    }
                ) { change, dragAmount ->
                    change.consume()
                    gestureDragPx += dragAmount
                    dragOffsetPx = (gestureDragPx * dragResistance)
                        .coerceIn(-itemSpacingPx * 0.68f, itemSpacingPx * 0.68f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val fadeBrush = androidx.compose.ui.graphics.Brush.horizontalGradient(
            0.0f to Color.Transparent,
            0.15f to Color.Black,
            0.85f to Color.Black,
            1.0f to Color.Transparent
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.99f }
                .drawWithContent {
                    drawContent()
                    drawRect(brush = fadeBrush, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                },
            contentAlignment = Alignment.Center
        ) {
            modes.forEachIndexed { index, mode ->
                val distance = index - currentIndexFloat
                val xOffset = distance * itemSpacingPx
                val alpha = (1f - abs(distance) * 0.5f).coerceIn(0f, 1f)
                val scale = (1f - abs(distance) * 0.15f).coerceIn(0.7f, 1f)
                val isCenter = abs(distance) < 0.3f
                Text(
                    text = mode.label,
                    color = (if (isCenter) AccentPistachio else Color.White).copy(alpha = alpha),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 2.sp,
                    modifier = Modifier
                        .offset { IntOffset(xOffset.roundToInt(), 0) }
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InteractiveHaloButton(
    currentValue: String,
    valuesList: List<String>,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
    isIso: Boolean, // true = ISO, false = Shutter
    uiRotationDegrees: Float = 0f,
    onDialStateChange: (visible: Boolean, residualOffsetPx: Float, stepPx: Float, isIso: Boolean) -> Unit = { _, _, _, _ -> }
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) } // Voor het verticale scrollen
    var horizontalDrag by remember { mutableFloatStateOf(0f) } // Voor swipe-to-reset
    var hasReset by remember { mutableStateOf(false) }

    // NIEUW: Zorgt ervoor dat we altijd de échte actuele waarde hebben, ook tijdens snelle recomposities
    val latestValue by rememberUpdatedState(currentValue)
    val latestDialStateChange by rememberUpdatedState(onDialStateChange)

    val alphaAnim by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = tween(150),
        label = "overlay_alpha"
    )

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    val pixelsPerStep = with(density) { 15.dp.toPx() } // Gevoeligheid
    val resetThresholdPx = with(density) { 60.dp.toPx() } // Swipe-to-auto afstand

    Box(
        modifier = Modifier
            .pointerInput(Unit) {
                // NIEUW: Een interne teller die meeloopt TIJDENS het slepen
                var localIndex = 0

                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        dragOffset = 0f
                        horizontalDrag = 0f
                        hasReset = false
                        // Pakt de start-index exact op het moment dat je je vinger neerzet
                        localIndex = valuesList.indexOf(latestValue).coerceAtLeast(0)
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        latestDialStateChange(true, 0f, pixelsPerStep, isIso)
                    },
                    onDragEnd = {
                        isDragging = false
                        latestDialStateChange(false, 0f, pixelsPerStep, isIso)
                    },
                    onDragCancel = {
                        isDragging = false
                        latestDialStateChange(false, 0f, pixelsPerStep, isIso)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()

                        // 1. UPDATE HORIZONTALE POSITIE (Voor Auto Reset)
                        horizontalDrag += dragAmount.x

                        val isInwardSwipe = if (isIso) horizontalDrag < -resetThresholdPx else horizontalDrag > resetThresholdPx
                        if (isInwardSwipe && !hasReset) {
                            hasReset = true
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            onReset()
                        }

                        // 2. UPDATE VERTICALE POSITIE (Door de lijst scrollen)
                        if (!hasReset) {
                            dragOffset -= dragAmount.y

                            val steps = (dragOffset / pixelsPerStep).toInt()
                            if (steps != 0) {
                                // Bereken vanaf de locale index (die meegroeit!)
                                val newIndex = (localIndex + steps).coerceIn(0, valuesList.size - 1)

                                if (newIndex != localIndex) {
                                    localIndex = newIndex // CRUCIAAL: Update de interne teller!
                                    onValueChange(valuesList[newIndex])
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    dragOffset -= (steps * pixelsPerStep) // Trek verwerkte pixels eraf
                                } else {
                                    // Zorgt dat je aan de uiteindes van de lijst een 'weerstand' voelt
                                    dragOffset = dragOffset.coerceIn(-pixelsPerStep, pixelsPerStep)
                                }
                            }
                        }
                        latestDialStateChange(true, dragOffset, pixelsPerStep, isIso)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // ==========================================
        // 0-PIXEL OVERLAY LAAG (Geen layout shifts!)
        // ==========================================
        Box(
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(0, 0) {
                    placeable.place(-placeable.width / 2, -placeable.height / 2)
                }
            },
            contentAlignment = Alignment.Center
        ) {

            // --- 1. VISUELE 'SWIPE TO AUTO' HINT ---
            if (alphaAnim > 0f) {
                val pullProgress = if (isIso) (-horizontalDrag / resetThresholdPx) else (horizontalDrag / resetThresholdPx)
                val safePullProgress = pullProgress.coerceIn(0f, 1f)

                val baseHintOffset = if (isIso) (-35).dp else 35.dp
                val activeSlide = if (isIso) (-35 * safePullProgress).dp else (35 * safePullProgress).dp

                Box(
                    modifier = Modifier
                        .offset(x = baseHintOffset + activeSlide)
                        .background(Color.DarkGray.copy(alpha = 0.4f + (0.4f * safePullProgress)), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .graphicsLayer { alpha = alphaAnim },
                    contentAlignment = Alignment.Center
                ) {
                    val indicatorColor = AccentPistachio.copy(alpha = 0.5f + (0.5f * safePullProgress))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.rotate(uiRotationDegrees)
                    ) {
                        if (isIso) {
                            Text("<", color = indicatorColor, fontSize = 12.sp, fontWeight = FontWeight.Black)
                            Spacer(Modifier.width(4.dp))
                            Text("Reset", color = indicatorColor, fontSize = 12.sp, fontWeight = FontWeight.Light)
                        } else {
                            Text("Reset", color = indicatorColor, fontSize = 12.sp, fontWeight = FontWeight.Light)
                            Spacer(Modifier.width(4.dp))
                            Text(">", color = indicatorColor, fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }

        }

        // ==========================================
        // DE WERKELIJKE KNOP (Altijd op zijn plek)
        // ==========================================
        val textColor = if (isDragging) AccentPistachio else Color.White

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isIso) Arrangement.End else Arrangement.Start,
            modifier = Modifier
                .defaultMinSize(minWidth = 60.dp)
                .rotate(uiRotationDegrees)
        ) {
            if (isIso) {
                Canvas(modifier = Modifier.size(width = 8.dp, height = 24.dp)) {
                    drawArc(color = AccentPistachio, startAngle = 90f, sweepAngle = 180f, useCenter = false, style = Stroke(width = 2.dp.toPx()))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(currentValue, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Light)
            } else {
                Text(currentValue, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.Light)
                Spacer(modifier = Modifier.width(6.dp))
                Canvas(modifier = Modifier.size(width = 8.dp, height = 24.dp)) {
                    drawArc(color = AccentPistachio, startAngle = -90f, sweepAngle = 180f, useCenter = false, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun FullWidthExposureDial(
    state: ViewfinderExposureDialState,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.height(86.dp)) {
        val step = state.stepPx.coerceAtLeast(1f)
        val baseY = size.height - 7.dp.toPx()
        val rise = 25.dp.toPx()
        fun arcY(x: Float): Float {
            val normalized = ((x / size.width) * 2f - 1f).coerceIn(-1f, 1f)
            return baseY - rise * (1f - normalized * normalized)
        }
        val arcPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, arcY(0f))
            val segments = 64
            for (i in 1..segments) {
                val x = size.width * i / segments.toFloat()
                lineTo(x, arcY(x))
            }
        }
        drawPath(
            path = arcPath,
            color = Color.White.copy(alpha = 0.25f),
            style = Stroke(width = 1.dp.toPx())
        )

        val centerX = size.width / 2f
        val residual = state.residualOffsetPx % step
        val count = (size.width / step).toInt() + 6
        for (i in -count..count) {
            val x = centerX + residual + i * step
            if (x !in 0f..size.width) continue
            val major = abs(i) % 5 == 0
            val isCenterTick = abs(x - centerX) < step * 0.45f
            val tickHeight = when {
                isCenterTick -> 22.dp.toPx()
                major -> 13.dp.toPx()
                else -> 7.dp.toPx()
            }
            val y = arcY(x)
            drawLine(
                color = (if (isCenterTick) AccentPistachio else Color.White)
                    .copy(alpha = if (isCenterTick) 1f else 0.55f),
                start = Offset(x, y),
                end = Offset(x, y - tickHeight),
                strokeWidth = if (isCenterTick) 2.2.dp.toPx() else 1.1.dp.toPx()
            )
        }

        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(178, 211, 168)
            textSize = 13.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        drawContext.canvas.nativeCanvas.drawText(
            state.label.ifBlank { if (state.isIso) "ISO" else "Shutter" },
            centerX,
            arcY(centerX) - 29.dp.toPx(),
            textPaint
        )
    }
}

@Composable
private fun FullWidthFocalLengthDial(
    currentFocalMm: Float,
    sensorFocalLengthsMm: List<Float>,
    maxFocalMm: Float,
    modifier: Modifier = Modifier
) {
    val sensorAnchors = sensorFocalLengthsMm.filter { it.isFinite() && it > 0f }.distinct().sorted()
    val minFocal = (sensorAnchors.firstOrNull() ?: currentFocalMm).coerceAtLeast(1f)
    val safeCurrent = currentFocalMm.coerceAtLeast(minFocal)
    val safeMax = maxOf(maxFocalMm, safeCurrent, minFocal + 0.1f)
    val logMin = ln(minFocal)
    val logSpan = (ln(safeMax) - logMin).coerceAtLeast(0.0001f)
    val normalizedProgress = ((ln(safeCurrent.coerceIn(minFocal, safeMax)) - logMin) / logSpan)
        .coerceIn(0f, 1f)

    Canvas(modifier = modifier.height(86.dp)) {
        val step = 15.dp.toPx()
        val virtualTickPosition = normalizedProgress * 48f
        val fractionalTick = virtualTickPosition - kotlin.math.floor(virtualTickPosition)
        val residual = -fractionalTick * step
        val baseY = size.height - 7.dp.toPx()
        val rise = 25.dp.toPx()
        fun arcY(x: Float): Float {
            val normalized = ((x / size.width) * 2f - 1f).coerceIn(-1f, 1f)
            return baseY - rise * (1f - normalized * normalized)
        }
        val arcPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, arcY(0f))
            val segments = 64
            for (i in 1..segments) {
                val x = size.width * i / segments.toFloat()
                lineTo(x, arcY(x))
            }
        }
        drawPath(
            path = arcPath,
            color = Color.White.copy(alpha = 0.25f),
            style = Stroke(width = 1.dp.toPx())
        )

        val centerX = size.width / 2f
        val count = (size.width / step).toInt() + 6
        for (i in -count..count) {
            val x = centerX + residual + i * step
            if (x !in 0f..size.width) continue
            val major = abs(i) % 5 == 0
            val isCenterTick = abs(x - centerX) < step * 0.45f
            val tickHeight = when {
                isCenterTick -> 22.dp.toPx()
                major -> 13.dp.toPx()
                else -> 7.dp.toPx()
            }
            val y = arcY(x)
            drawLine(
                color = (if (isCenterTick) AccentPistachio else Color.White)
                    .copy(alpha = if (isCenterTick) 1f else 0.55f),
                start = Offset(x, y),
                end = Offset(x, y - tickHeight),
                strokeWidth = if (isCenterTick) 2.2.dp.toPx() else 1.1.dp.toPx()
            )
        }

        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(178, 211, 168)
            textSize = 13.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        drawContext.canvas.nativeCanvas.drawText(
            String.format(Locale.US, "%.0fmm", safeCurrent),
            centerX,
            arcY(centerX) - 29.dp.toPx(),
            textPaint
        )
    }
}

private data class ViewfinderExposureDialState(
    val visible: Boolean = false,
    val residualOffsetPx: Float = 0f,
    val stepPx: Float = 1f,
    val isIso: Boolean = false,
    val label: String = ""
)

private data class CameraUiHardwareState(
    val adaptiveViewfinderAspect: Float = 3f / 4f,
    val maxDigitalZoom: Float = 1f,
    val manualWhiteBalanceSupported: Boolean = false,
    val manualExposureBounds: ManualExposureBounds? = null,
    val maxFocusDiopters: Float = 0f,
    val supportsRaw10: Boolean = false,
    val supportsRawSensor: Boolean = false
)

private data class PreviewCameraHardwareState(
    val logicalCameraId: String,
    val isFrontCamera: Boolean
)

@OptIn(ExperimentalFoundationApi::class)

@Composable
fun CameraScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToProfileSettings: (String) -> Unit,
    onCapture: () -> Unit,
    activeProfile: CameraProfile,
    visibleProfiles: List<CameraProfile>,
    onProfileSelected: (CameraProfile) -> Unit,
    activeLens: LensInfo,
    visibleLenses: List<LensInfo>,
    onLensSelected: (LensInfo) -> Unit,
    bnCameraManager: BnCameraManager,
    isRootCameraRoute: Boolean = true,
) {
    val context = LocalContext.current
    // Viewfinder mode is deliberately session-local. A fresh CameraScreen always starts in Photo,
    // while the persistent camera host keeps the selected mode when Settings/Profile pages overlay it.
    // Nothing is written to DataStore, so a new app/camera host never restores Night or Video.
    var viewfinderMode by remember { mutableStateOf(ViewfinderMode.PHOTO) }
    val uiCameraManager = remember(context) {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }
    var cameraUiHardwareState by remember(activeLens.id) {
        mutableStateOf(CameraUiHardwareState())
    }
    LaunchedEffect(activeLens.id) {
        cameraUiHardwareState = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val chars = runCatching {
                uiCameraManager.getCameraCharacteristics(activeLens.id)
            }.recoverCatching {
                val route = bnCameraManager.resolveCameraDeviceRoute(activeLens.id)
                uiCameraManager.getCameraCharacteristics(route.logicalCameraId)
            }.getOrNull()
            if (chars == null) {
                CameraUiHardwareState()
            } else {
                val rawRect =
                    chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                        ?: chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val adaptiveAspect = if (rawRect != null && rawRect.width() > 0 && rawRect.height() > 0) {
                    val shortSide = min(rawRect.width(), rawRect.height()).toFloat()
                    val longSide = max(rawRect.width(), rawRect.height()).toFloat()
                    (shortSide / longSide).coerceIn(0.50f, 1.0f)
                } else {
                    3f / 4f
                }
                val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val shutter = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val rawCapability = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
                CameraUiHardwareState(
                    adaptiveViewfinderAspect = adaptiveAspect,
                    maxDigitalZoom = chars
                        .get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                        ?.coerceAtLeast(1f) ?: 1f,
                    manualWhiteBalanceSupported = bnCameraManager.supportsManualWhiteBalance(activeLens.id),
                    manualExposureBounds = if (iso != null && shutter != null) {
                        ManualExposureBounds(iso.lower, iso.upper, shutter.lower, shutter.upper)
                    } else {
                        null
                    },
                    maxFocusDiopters = chars
                        .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
                    supportsRaw10 = rawCapability &&
                        streamMap?.getOutputSizes(ImageFormat.RAW10)?.isNotEmpty() == true,
                    supportsRawSensor = rawCapability &&
                        streamMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.isNotEmpty() == true
                )
            }
        }
    }
    val adaptiveViewfinderAspect = cameraUiHardwareState.adaptiveViewfinderAspect
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    // Capture work must survive recomposition and temporary removal of CameraScreen. The regular
    // Compose scope remains correct for UI-only jobs such as focus animations and controls.
    val captureScope = remember(lifecycleOwner) { lifecycleOwner.lifecycleScope }
    val repository = remember { SettingsRepository(context) }
    val shotLogger = remember { ShotLogger(context) }

    // --- Viewfinder Settings Ophalen ---
    val gridLines by repository.gridLinesFlow.collectAsState(initial = "Off")
    val horizonLeveler by repository.horizonLevelerFlow.collectAsState(initial = false)
    val geotagEnabled by repository.saveLocationDataFlow.collectAsState(initial = false)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissions ->
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            coroutineScope.launch { repository.setSaveLocationData(granted) }
            if (!granted) {
                Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    )
    val centerCrosshair by repository.centerCrosshairFlow.collectAsState(initial = false)
    val outputPolicy by repository.outputPolicyFlow.collectAsState(initial = OutputPolicy.JPEG)
    val viewfinderStream by repository.viewfinderStreamFlow.collectAsState(initial = ViewfinderStream.YUV)

    // --- Focus Settings Ophalen ---
    val focusData by repository.focusDataFlow.collectAsState(initial = false)
    val focusMode by repository.focusModeFlow.collectAsState(initial = "Continuous")
    val leftSliderAssignment by repository.leftSliderAssignmentFlow.collectAsState(initial = ViewfinderSliderAssignment.OFF)
    val rightSliderAssignment by repository.rightSliderAssignmentFlow.collectAsState(initial = ViewfinderSliderAssignment.OFF)
    val dedicatedShutterSlider by repository.shutterSpeedSliderFlow.collectAsState(initial = false)
    val dedicatedIsoSlider by repository.isoSliderFlow.collectAsState(initial = false)
    val liveViewfinderTuning by ViewfinderLiveTuning.state.collectAsState()
    val activeProfileAwbSettings by repository.getProfileAwbSettingsFlow(activeProfile.id)
        .collectAsState(initial = ProfileAwbSettings())
    val focusRing by repository.focusRingFlow.collectAsState(initial = true)
    val focusLock by repository.focusLockFlow.collectAsState(initial = "3s")
    val resetFocusCapture by repository.resetFocusCaptureFlow.collectAsState(initial = false)
    val focusPeak by repository.focusPeakFlow.collectAsState(initial = false)
    val focusPeakColor by repository.focusPeakColorFlow.collectAsState(initial = "Red")
    val mirrorFrontPreview by repository.mirrorFrontPreviewFlow.collectAsState(initial = true)
    val previewOrientationCorrectionSetting by repository
        .getLensPreviewOrientationCorrectionFlow(activeLens.id)
        .collectAsState(initial = "Auto")
    val manualPreviewOrientationCorrectionDegrees = remember(previewOrientationCorrectionSetting) {
        when (previewOrientationCorrectionSetting) {
            "+90°" -> 90
            "+180°" -> 180
            "+270°" -> 270
            else -> 0
        }
    }
    val effectiveYuvOrientationCorrectionDegrees = remember(
        activeLens.yuvPreviewOrientationCorrectionDegrees,
        manualPreviewOrientationCorrectionDegrees
    ) {
        ((activeLens.yuvPreviewOrientationCorrectionDegrees + manualPreviewOrientationCorrectionDegrees) % 360 + 360) % 360
    }

    LaunchedEffect(activeLens.id, manualPreviewOrientationCorrectionDegrees) {
        bnCameraManager.setPreviewOrientationCorrection(manualPreviewOrientationCorrectionDegrees)
    }


    // --- Detection Data ---
    val detectedFaces by bnCameraManager.detectedFaces.collectAsState()
    val sensorRect by bnCameraManager.sensorRect.collectAsState()
    val detectedQrCode by bnCameraManager.detectedQrCode.collectAsState()
    val sensorOrientation by bnCameraManager.sensorOrientation.collectAsState()
    val lensFacing by bnCameraManager.lensFacing.collectAsState()
    val trackedObjectBounds by bnCameraManager.trackedObjectBounds.collectAsState()
    val focusTrackingActive by bnCameraManager.focusTrackingActive.collectAsState()
    val focusTrackingState by bnCameraManager.focusTrackingState.collectAsState()
    val focusOwnership by bnCameraManager.focusOwnership.collectAsState()
    val priorityFaceBounds by bnCameraManager.priorityFaceBounds.collectAsState()

    val faceDetection by repository.faceDetectionFlow.collectAsState(initial = false)
    val facePriorityFocus by repository.facePriorityFocusFlow.collectAsState(initial = false)
    val focusTracking by repository.focusTrackingFlow.collectAsState(initial = false)
    val portraitEffectEnabled by repository.portraitEffectEnabledFlow.collectAsState(initial = false)
    val qrDetection by repository.qrDetectionFlow.collectAsState(initial = false)
    val doubleTapAction by repository.doubleTapActionFlow.collectAsState(initial = "2x Zoom")

    // --- Exposure Settings Ophalen ---
    val meteringStyle by repository.meteringStyleFlow.collectAsState(
        initial = MeteringMode.AUTO_DEFAULT_AE.settingValue
    )
    val showHistogram by repository.histogramFlow.collectAsState(initial = false)

    val portraitModeActive = viewfinderMode == ViewfinderMode.PORTRAIT
    LaunchedEffect(showHistogram, qrDetection, focusTracking, portraitEffectEnabled, portraitModeActive) {
        bnCameraManager.setOptionalAnalysisEnabled(
            histogram = showHistogram,
            qr = qrDetection,
            objectTracking = focusTracking,
            portraitEffect = portraitEffectEnabled || portraitModeActive
        )
    }

    LaunchedEffect(faceDetection, facePriorityFocus) {
        bnCameraManager.setFaceIntelligence(
            faceDetection = faceDetection,
            facePriorityFocus = faceDetection && facePriorityFocus
        )
    }

    // --- Live Engine Data ---
    val liveFocusDiopters by bnCameraManager.liveFocusDiopters.collectAsState()
    val focusPeakingGuidance by bnCameraManager.focusPeakingGuidance.collectAsState()
    val isAeAfLocked = focusOwnership.focusLocked && focusOwnership.aeLocked
    val liveHistogram by bnCameraManager.liveHistogram.collectAsState()
    val liveRgbHistogram by bnCameraManager.liveRgbHistogram.collectAsState()

    val cameraState by bnCameraManager.cameraState.collectAsState()
    val captureContractError by bnCameraManager.captureContractError.collectAsState()
    val isCaptureReady = cameraState == com.bncam.core.engine.CameraEngineState.CAPTURE_READY

    LaunchedEffect(captureContractError) {
        captureContractError?.let { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // --- Lokale UI States ---
    var manualExposureValue by remember { mutableFloatStateOf(0f) }
    var manualFocusDistance by remember { mutableFloatStateOf(0.5f) }
    var manualFocusOverrideActive by remember(activeLens.id) { mutableStateOf(false) }
    var currentZoomLevel by remember(activeLens.id) { mutableFloatStateOf(1f) }
    val currentZoomLevelState = rememberUpdatedState(currentZoomLevel)
    var requestedZoomLevel by remember(activeLens.id) { mutableFloatStateOf(1f) }
    var zoomAnimationDurationMs by remember(activeLens.id) { mutableIntStateOf(90) }
    var exposureDialState by remember { mutableStateOf(ViewfinderExposureDialState()) }
    var pinchZoomDialVisible by remember { mutableStateOf(false) }
    var pinchGestureGeneration by remember { mutableIntStateOf(0) }
    var pinchVirtualFocalMm by remember { mutableStateOf<Float?>(null) }
    var pendingPinchLensId by remember { mutableStateOf<String?>(null) }
    var pendingPinchFocalMm by remember { mutableStateOf<Float?>(null) }
    val maxDigitalZoom = cameraUiHardwareState.maxDigitalZoom
    val manualWhiteBalanceSupported = cameraUiHardwareState.manualWhiteBalanceSupported
    val rearFocalLenses = remember(visibleLenses) {
        visibleLenses
            .asSequence()
            .filter { it.facing != CameraCharacteristics.LENS_FACING_FRONT }
            .filter { (it.equivalentFocalLength35mm ?: 0f) > 0f }
            .sortedBy { it.equivalentFocalLength35mm }
            .toList()
    }
    val activeNativeFocalMm = activeLens.equivalentFocalLength35mm
        ?.takeIf { it > 0f }
        ?: rearFocalLenses.minByOrNull { kotlin.math.abs(it.opticalZoomRatio - activeLens.opticalZoomRatio) }
            ?.equivalentFocalLength35mm
        ?: 24f
    fun requestSmoothZoom(targetZoom: Float, durationMs: Int = 90) {
        requestedZoomLevel = targetZoom.coerceIn(1f, maxDigitalZoom.coerceAtLeast(1f))
        zoomAnimationDurationMs = durationMs.coerceIn(45, 360)
    }

    // --- Timer States ---
    val timerDuration by repository.timerDurationFlow.collectAsState(initial = 0)
    var activeCountdown by remember { mutableIntStateOf(0) }
    var isCountingDown by remember { mutableStateOf(false) }
    // One UI shutter intent may own the synchronous admission/dispatch path at a time. RAW work
    // becomes asynchronous immediately after queue submission, so this guard is released as soon
    // as executeCapture() returns; it does not serialize background processing.
    var shutterDispatchInFlight by remember { mutableStateOf(false) }

    // ---- Flash ----
    val flashMode by repository.flashModeFlow.collectAsState(initial = "Off") // NIEUW

    // ---- Watermerk ----
    val watermarkEnabled by repository.watermarkEnabledFlow.collectAsState(initial = false)

    // --- Overlay State ---
    var currentOverlayMessage by remember { mutableStateOf<OverlayMessage?>(null) }
    var quickSettingsExpanded by remember { mutableStateOf(false) }
    var quickSettingsAnchorBottomPx by remember { mutableIntStateOf(0) }
    var leftSliderGestureActive by remember { mutableStateOf(false) }
    var rightSliderGestureActive by remember { mutableStateOf(false) }
    var lastExitBackPressElapsedMs by remember { mutableStateOf(0L) }

    BackHandler(enabled = quickSettingsExpanded) {
        quickSettingsExpanded = false
    }
    BackHandler(enabled = isRootCameraRoute && !quickSettingsExpanded) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastExitBackPressElapsedMs <= 2_000L) {
            (context.findActivityLifecycleOwner() as? ComponentActivity)?.finish()
        } else {
            lastExitBackPressElapsedMs = now
            Toast.makeText(context, "Press Back again to exit BnCam", Toast.LENGTH_SHORT).show()
        }
    }
    var sliderFeedbackText by remember { mutableStateOf<String?>(null) }
    var sliderFeedbackGeneration by remember { mutableIntStateOf(0) }
    fun showSliderFeedback(text: String) {
        sliderFeedbackText = text
        sliderFeedbackGeneration += 1
    }
    LaunchedEffect(sliderFeedbackGeneration) {
        if (sliderFeedbackGeneration > 0) {
            delay(3_000)
            sliderFeedbackText = null
        }
    }

    val manualExposureBounds = cameraUiHardwareState.manualExposureBounds
    val maxFocusDiopters = cameraUiHardwareState.maxFocusDiopters
    var assignedIsoFraction by remember(activeLens.id) { mutableFloatStateOf(0.5f) }
    var assignedShutterFraction by remember(activeLens.id) { mutableFloatStateOf(0.5f) }
    var assignedIsoValue by remember(activeLens.id) { mutableStateOf<Int?>(null) }
    var assignedShutterNs by remember(activeLens.id) { mutableStateOf<Long?>(null) }
    val dedicatedIsoValues = remember(manualExposureBounds) { adaptiveIsoLabels(manualExposureBounds) }
    val dedicatedShutterValues = remember(manualExposureBounds) { adaptiveShutterLabels(manualExposureBounds) }
    val dedicatedIsoLabel = assignedIsoValue?.let { "ISO $it" } ?: "AUTO"
    val dedicatedShutterLabel = assignedShutterNs?.let(::formatShutterNs) ?: "AUTO"
    val isoAssignmentActive = dedicatedIsoSlider
    val shutterAssignmentActive = dedicatedShutterSlider

    LaunchedEffect(
        assignedIsoValue, assignedShutterNs, isoAssignmentActive, shutterAssignmentActive, activeLens.id
    ) {
        bnCameraManager.setManualIsoAndShutter(
            iso = assignedIsoValue.takeIf { isoAssignmentActive },
            shutterSpeedNs = assignedShutterNs.takeIf { shutterAssignmentActive },
            cameraId = activeLens.id
        )
    }

    LaunchedEffect(flashMode) {
        bnCameraManager.setFlashMode(flashMode)
    }

    var tapPoint by remember { mutableStateOf<Offset?>(null) }
    var showPassiveRing by remember { mutableStateOf(false) }

    // Autofocus Ring Listener
    LaunchedEffect(focusOwnership.owner) {
        bnCameraManager.passiveFocusAchieved.collect {
            if (tapPoint == null && focusOwnership.owner == com.bncam.core.engine.FocusOwner.AUTO) {
                showPassiveRing = true
            }
        }
    }

    LaunchedEffect(activeLens.id) {
        tapPoint = null
        showPassiveRing = false
        if (focusTrackingActive && focusTracking) {
            bnCameraManager.prepareFocusTrackingForLensHandover()
        } else {
            bnCameraManager.stopFocusTracking(reason = "lens_changed", restoreConfiguredAf = false)
        }
    }

    LaunchedEffect(focusOwnership.owner) {
        if (focusOwnership.owner == com.bncam.core.engine.FocusOwner.AUTO ||
            focusOwnership.owner == com.bncam.core.engine.FocusOwner.FACE_PRIORITY ||
            focusOwnership.owner == com.bncam.core.engine.FocusOwner.MANUAL
        ) {
            tapPoint = null
        }
        if (focusOwnership.owner != com.bncam.core.engine.FocusOwner.MANUAL) {
            manualFocusOverrideActive = false
        }
    }

    // Smooth the physical actuators instead of handing every raw pointer sample directly to Camera2.
    // The UI value remains immediate; only the hardware command is interpolated over a short window.
    val evSliderActive = leftSliderAssignment == ViewfinderSliderAssignment.EV ||
            rightSliderAssignment == ViewfinderSliderAssignment.EV
    val evActuator = remember(activeLens.id) { Animatable(0f) }
    LaunchedEffect(manualExposureValue, evSliderActive, activeLens.id) {
        val target = if (evSliderActive) manualExposureValue else 0f
        evActuator.animateTo(
            targetValue = target,
            animationSpec = tween(
                durationMillis = 90,
                easing = androidx.compose.animation.core.LinearEasing
            )
        ) { bnCameraManager.setExposure(value, activeLens.id) }
    }

    val focusActuator = remember(activeLens.id) { Animatable(0.5f) }
    LaunchedEffect(manualFocusDistance, manualFocusOverrideActive, activeLens.id) {
        if (!manualFocusOverrideActive) {
            focusActuator.snapTo(manualFocusDistance)
            return@LaunchedEffect
        }
        focusActuator.animateTo(
            targetValue = manualFocusDistance.coerceIn(0f, 1f),
            animationSpec = tween(
                durationMillis = 110,
                easing = androidx.compose.animation.core.LinearEasing
            )
        ) { bnCameraManager.setFocus(value, activeLens.id) }
    }

    val zoomActuator = remember(activeLens.id) { Animatable(1f) }
    LaunchedEffect(requestedZoomLevel, zoomAnimationDurationMs, maxDigitalZoom, activeLens.id) {
        val target = requestedZoomLevel.coerceIn(1f, maxDigitalZoom.coerceAtLeast(1f))
        zoomActuator.animateTo(
            targetValue = target,
            animationSpec = tween(
                durationMillis = zoomAnimationDurationMs,
                easing = androidx.compose.animation.core.LinearOutSlowInEasing
            )
        ) {
            currentZoomLevel = value
            bnCameraManager.setZoom(value)
        }
    }

    LaunchedEffect(pinchGestureGeneration) {
        if (pinchGestureGeneration > 0) {
            pinchZoomDialVisible = true
            delay(420)
            pinchZoomDialVisible = false
            pinchVirtualFocalMm = null
        }
    }

    LaunchedEffect(focusMode, activeLens.id) {
        bnCameraManager.setFocusMode(focusMode)
    }

    // Profile/lens changes re-apply the current live override. Slider movement itself calls the
    // lightweight WB render target directly below, avoiding a recomposition/effect round-trip.
    LaunchedEffect(activeProfileAwbSettings, activeLens.id) {
        bnCameraManager.setViewfinderWhiteBalance(
            profileSettings = activeProfileAwbSettings,
            liveKelvin = liveViewfinderTuning.whiteBalanceKelvin,
            cameraId = activeLens.id
        )
    }

    // 2. Zorg dat de engine de structurele Metering Style kent
    LaunchedEffect(meteringStyle, activeLens.id) {
        bnCameraManager.setMeteringStyle(meteringStyle)
    }

    // A physical lens transition starts at native 1x. If the transition originated from a pinch
    // gesture, restore the requested equivalent focal length on the new sensor immediately after
    // its zoom capability is known.
    LaunchedEffect(activeLens.id) {
        currentZoomLevel = 1f
        requestedZoomLevel = 1f
        zoomActuator.snapTo(1f)
        bnCameraManager.setZoom(1f)
    }

    LaunchedEffect(activeLens.id, maxDigitalZoom, pendingPinchLensId, pendingPinchFocalMm) {
        if (pendingPinchLensId != activeLens.id) return@LaunchedEffect
        val requestedFocal = pendingPinchFocalMm ?: return@LaunchedEffect
        val residualZoom = (requestedFocal / activeNativeFocalMm).coerceAtLeast(1f)
        // CameraUiHardwareState is intentionally reset while a new sensor is opening. Do not lose
        // a >1x residual request during that short 1x placeholder window.
        if (residualZoom > 1.001f && maxDigitalZoom <= 1.001f) return@LaunchedEffect
        requestedZoomLevel = residualZoom.coerceIn(1f, maxDigitalZoom.coerceAtLeast(1f))
        zoomAnimationDurationMs = 70
        pendingPinchLensId = null
        pendingPinchFocalMm = null
    }

// --- Sensor Utils (Voor Waterpas) ---
    val sensorUtils = remember { com.bncam.core.utils.SensorUtils(context) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Preserve the current session mode across lifecycle resumes; a fresh CameraScreen
                // still deterministically starts in Photo via its remembered initial state.
                sensorUtils.register()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                sensorUtils.unregister()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sensorUtils.unregister()
        }
    }

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val useHaptics by repository.hapticFeedbackFlow.collectAsState(initial = true)

// ==========================================
    // NIEUW: Haptische feedback voor de waterpas
    // ==========================================
    var wasLevel by remember { mutableStateOf(false) }
    var hasInitializedSensor by remember { mutableStateOf(false) }

    LaunchedEffect(sensorUtils.roll.floatValue) {
        val currentRoll = sensorUtils.roll.floatValue
        val isLevel = kotlin.math.abs(currentRoll) < 1.0f

        // We willen niet trillen op de allereerste "lege" 0f waarde bij het opstarten
        if (currentRoll != 0f) {
            hasInitializedSensor = true
        }

        if (hasInitializedSensor && isLevel && !wasLevel) {
            if (useHaptics && horizonLeveler) {
                // AANGEPAST: We gebruiken LongPress. Dit is een veel duidelijker 'klik'
                // die door elke fabrikant gegarandeerd wordt doorgegeven.
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            }
        }

        // Sla de huidige status op voor de volgende frame
        wasLevel = isLevel
    }

    val forceGooglePhotos by repository.forceGooglePhotosFlow.collectAsState(initial = false)
    val initialPublishedUri = remember { com.bncam.core.utils.ThumbnailScanner.findLatestPublishedImageUri(context) }
    var publishedUriState by remember { mutableStateOf<Uri?>(initialPublishedUri) }

    val latestSnapshot by CaptureProcessingQueue.latestSnapshotFlow.collectAsState()
    val previewViewRef = remember { arrayOfNulls<FocusPeakingView>(1) }

    // ==========================================
    // CENTRALE CAPTURE FUNCTIE (Met Timer Ondersteuning)
    // ==========================================
    val triggerCaptureSequence = {
        if (viewfinderMode == ViewfinderMode.VIDEO) {
            // Video is intentionally present in the product strip but is not implemented by the
            // still-capture engine. Never silently take a photo while the UI says Video.
            android.widget.Toast.makeText(
                context,
                "Video mode is not active yet",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else if (!isCountingDown && !shutterDispatchInFlight) { // Voorkom dubbele triggers
            // Claim before launching the coroutine. Rapid taps otherwise enqueue several shutter
            // coroutines while the first one is still waiting on the capture admission gate.
            shutterDispatchInFlight = true
            val immediateShutterClickNs = if (timerDuration == 0) android.os.SystemClock.elapsedRealtimeNanos() else 0L
            captureScope.launch {
                try {
                val userShutterTimestampNs = if (timerDuration > 0) {
                    isCountingDown = true
                    for (i in timerDuration downTo 1) {
                        activeCountdown = i
                        // Lichte tik bij elke seconde
                        if (useHaptics) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        delay(1000)
                    }
                    activeCountdown = 0
                    isCountingDown = false
                    android.os.SystemClock.elapsedRealtimeNanos()
                } else {
                    immediateShutterClickNs
                }

                // The camera transaction has priority over thumbnail cosmetics. The old path waited
                // up to 150 ms for a 128x128 viewfinder JPEG *before* executeCapture(), even though
                // userShutterTimestampNs had already been frozen. At high preview cadence that delay
                // can churn a substantial part of the Near-ZSL ring before MultiFrameRunner pins its
                // shutter-time source. Admit/lease the photo first; attach the temporary thumbnail to
                // the newly created processing job afterwards.
                if (useHaptics) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onCapture()

                try {
                    val resultUri: Uri? = bnCameraManager.executeCapture(
                        activeProfile = activeProfile,
                        activeLens = activeLens,
                        shotLogger = shotLogger,
                        deviceRotation = sensorUtils.captureRotation.intValue,
                        temporaryPreviewPath = null,
                        userShutterTimestampNs = userShutterTimestampNs,
                        viewfinderMode = viewfinderMode
                    )

                    if (resultUri != null) {
                        publishedUriState = resultUri
                    } else {
                        // Match the temporary preview to this exact shutter timestamp. Using the
                        // globally latest queue item is racy when multiple RAW jobs overlap and can
                        // attach a thumbnail from shot N to shot N+1. Normal Near-ZSL runners store
                        // the same elapsedRealtime shutter timestamp in CaptureWorkSnapshot.
                        val submittedWork = CaptureProcessingQueue
                            .snapshotForCaptureStartedNs(userShutterTimestampNs)
                            ?.takeIf { snapshot ->
                                snapshot.state != CaptureWorkState.PUBLISHED &&
                                    snapshot.state != CaptureWorkState.FAILED
                            }
                        if (submittedWork != null) {
                            val tempPreviewPath = TemporaryPreviewCapture.captureTemporaryPreview(
                                context = context,
                                previewView = previewViewRef[0]
                            )
                            if (tempPreviewPath != null) {
                                CaptureProcessingQueue.attachTemporaryPreview(
                                    workId = submittedWork.workId,
                                    path = tempPreviewPath
                                )
                            }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // BnCameraManager records normal capture failures and returns null. An unexpected
                    // UI-layer failure must remain visible without reaching the main uncaught handler.
                    android.util.Log.e(
                        "CameraScreen",
                        "Capture failed without terminating the camera UI: ${failure.message}",
                        failure
                    )
                    android.widget.Toast.makeText(
                        context,
                        failure.message ?: "Capture failed",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }

                if (resetFocusCapture) {
                    manualFocusOverrideActive = false
                    manualFocusDistance = 0.5f
                    tapPoint = null
                    showPassiveRing = false
                    bnCameraManager.stopFocusTracking(
                        reason = "capture_reset",
                        restoreConfiguredAf = false
                    )
                    bnCameraManager.triggerContinuousAutoFocus()
                }
                } finally {
                    isCountingDown = false
                    activeCountdown = 0
                    shutterDispatchInFlight = false
                }
            }
        }
    }
    val latestTriggerCaptureSequence by rememberUpdatedState(triggerCaptureSequence)

// ==========================================
// EVENT BUS LISTENER (Volumeknoppen)
// ==========================================
    LaunchedEffect(Unit) {
        CaptureProcessingQueue.events.collect { work ->
            if (work.state == CaptureWorkState.PUBLISHED) {
                val thumbUri = work.thumbnailUri ?: work.publishedUri
                if (!thumbUri.isNullOrBlank()) {
                    publishedUriState = Uri.parse(thumbUri)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        com.bncam.CameraEventBus.captureRequests.collect {
            // This collector intentionally lives for the whole CameraScreen lifetime. Resolve the
            // current closure at delivery time so profile/lens/settings changes are never captured
            // from the first composition.
            latestTriggerCaptureSequence()
        }
    }

    LaunchedEffect(Unit) {
        com.bncam.CameraEventBus.zoomRequests.collect { direction ->
            val safeMax = maxDigitalZoom.coerceAtLeast(1f)
            if (safeMax <= 1.001f) return@collect
            // Multiplicative stepping gives useful fine control near 1x without becoming painfully
            // slow at larger digital zoom ratios. The manager independently clamps to HAL limits.
            val factor = 1.12f
            val target = if (direction > 0) {
                requestedZoomLevel * factor
            } else {
                requestedZoomLevel / factor
            }.coerceIn(1f, safeMax)
            requestSmoothZoom(target, durationMs = 110)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val latestFromGallery = com.bncam.core.utils.ThumbnailScanner.findLatestPublishedImageUri(context)
                if (latestFromGallery != null) {
                    publishedUriState = latestFromGallery
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // --- GEDEELDE ROTATIE VOOR HET HELE SCHERM ---
        val animatedUiRotation by animateFloatAsState(
            targetValue = sensorUtils.uiRotationDegrees.floatValue,
            animationSpec = spring(stiffness = Spring.StiffnessLow),
            label = "uiRotation"
        )

        // --- 1. GEÏNTEGREERDE TOP BAR (Settings, Tools & Profiles) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ==========================================
            // ZONE 1: HEILIGE SETTINGS KNOP (Uitgekleed voor meer ruimte)
            // ==========================================
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onNavigateToSettings() }
                    .padding(end = 6.dp)
                    .size(24.dp)
                    .rotate(animatedUiRotation)
            )

            Surface(
                color = if (quickSettingsExpanded) AccentPistachio.copy(alpha = 0.20f) else Color.Transparent,
                shape = CircleShape,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(32.dp)
                    .onGloballyPositioned { coordinates ->
                        quickSettingsAnchorBottomPx = coordinates.boundsInWindow().bottom.roundToInt()
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { quickSettingsExpanded = !quickSettingsExpanded }
            ) {
                Canvas(modifier = Modifier.padding(7.dp).rotate(animatedUiRotation)) {
                    val color = if (quickSettingsExpanded) AccentPistachio else Color.White.copy(alpha = 0.9f)
                    val stroke = 1.6.dp.toPx()
                    val ys = listOf(size.height * 0.25f, size.height * 0.50f, size.height * 0.75f)
                    val knobs = listOf(size.width * 0.68f, size.width * 0.34f, size.width * 0.58f)
                    ys.forEachIndexed { index, y ->
                        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
                        drawCircle(color, radius = 2.2.dp.toPx(), center = Offset(knobs[index], y))
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Keep the compact top-bar control fixed in screen orientation. Only the expanded
            // profile panel follows device UI rotation.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Profiles:",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                ViewfinderProfileSelector(
                    activeProfile = activeProfile,
                    visibleProfiles = visibleProfiles,
                    onProfileSelected = onProfileSelected,
                    onOpenProfileSettings = { profile -> onNavigateToProfileSettings(profile.id) },
                    uiRotationDegrees = animatedUiRotation
                )
            }
        }

        if (quickSettingsExpanded) {
            val quickSettingsGapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(
                    x = 0,
                    y = (quickSettingsAnchorBottomPx + quickSettingsGapPx).coerceAtLeast(0)
                ),
                onDismissRequest = { quickSettingsExpanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false
                )
            ) {
                ViewfinderQuickSettingsOverlay(
                    flashMode = flashMode,
                    timerDurationSeconds = timerDuration,
                    watermarkEnabled = watermarkEnabled,
                    outputPolicy = outputPolicy,
                    viewfinderStream = viewfinderStream,
                    geotagEnabled = geotagEnabled,
                    focusPeakingEnabled = focusPeak,
                    meteringMode = MeteringMode.fromSetting(meteringStyle),
                    histogramEnabled = showHistogram,
                    focusTrackingEnabled = focusTracking,
                    horizonLevelerEnabled = horizonLeveler,
                    faceDetectionEnabled = faceDetection,
                    onFlashModeChange = { mode -> coroutineScope.launch { repository.setFlashMode(mode) } },
                    onTimerDurationChange = { seconds -> coroutineScope.launch { repository.setTimerDuration(seconds) } },
                    onWatermarkEnabledChange = { enabled -> coroutineScope.launch { repository.setWatermarkEnabled(enabled) } },
                    onOutputPolicyChange = { policy -> coroutineScope.launch { repository.setOutputPolicy(policy) } },
                    onViewfinderStreamChange = { stream ->
                        coroutineScope.launch { repository.setViewfinderStream(stream) }
                    },
                    onGeotagEnabledChange = { enabled ->
                        if (enabled) {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        } else {
                            coroutineScope.launch { repository.setSaveLocationData(false) }
                        }
                    },
                    onFocusPeakingEnabledChange = { enabled -> coroutineScope.launch { repository.setFocusPeak(enabled) } },
                    onMeteringModeChange = { mode -> coroutineScope.launch { repository.setMeteringStyle(mode.settingValue) } },
                    onHistogramEnabledChange = { enabled -> coroutineScope.launch { repository.setHistogram(enabled) } },
                    onFocusTrackingEnabledChange = { enabled -> coroutineScope.launch { repository.setFocusTracking(enabled) } },
                    onHorizonLevelerEnabledChange = { enabled -> coroutineScope.launch { repository.setHorizonLeveler(enabled) } },
                    onFaceDetectionEnabledChange = { enabled -> coroutineScope.launch { repository.setFaceDetection(enabled) } },
                    onDismiss = { quickSettingsExpanded = false },
                    uiRotationDegrees = animatedUiRotation,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .fillMaxWidth()
                        .zIndex(20f)
                )
            }
        }

        val peakingDisplayTarget = remember(
            focusPeakingGuidance,
            focusOwnership.owner,
            sensorOrientation,
            lensFacing,
            manualPreviewOrientationCorrectionDegrees
        ) {
            focusPeakingDisplayTarget(
                guidance = focusPeakingGuidance,
                focusOwner = focusOwnership.owner,
                sensorOrientation = sensorOrientation,
                lensFacing = lensFacing,
                previewOrientationCorrectionDegrees = manualPreviewOrientationCorrectionDegrees
            )
        }

        // --- CAMERA VIEWPORT ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(adaptiveViewfinderAspect)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.DarkGray)
                .pointerInput(quickSettingsExpanded, leftSliderGestureActive, rightSliderGestureActive) {
                    val openThresholdPx = 56.dp.toPx()
                    val sideControlExclusionPx = 96.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var blocked = quickSettingsExpanded || leftSliderGestureActive || rightSliderGestureActive ||
                            down.position.x <= sideControlExclusionPx ||
                            down.position.x >= size.width - sideControlExclusionPx
                        val start = down.position
                        var opened = false

                        while (true) {
                            // Observe before tap/transform handlers consume the same movement.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } > 1) blocked = true
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (leftSliderGestureActive || rightSliderGestureActive) blocked = true
                            if (!blocked && change.pressed) {
                                val travel = change.position - start
                                if (travel.y >= openThresholdPx && travel.y > abs(travel.x) * 1.15f) {
                                    quickSettingsExpanded = true
                                    change.consume()
                                    opened = true
                                } else if (abs(travel.x) >= openThresholdPx || travel.y <= -openThresholdPx * 0.5f) {
                                    blocked = true
                                }
                            }
                            if (!change.pressed || opened) break
                        }
                    }
                }
                .pointerInput(
                    focusLock,
                    doubleTapAction,
                    focusTracking,
                    mirrorFrontPreview,
                    lensFacing,
                    activeLens.id,
                    maxDigitalZoom
                ) {
                    detectTapGestures(
                        onDoubleTap = { _ ->
                            val resolvedDoubleTapAction = if (doubleTapAction == "None") "2x Zoom" else doubleTapAction
                            val safeMaxZoom = maxDigitalZoom.coerceAtLeast(1f)
                            val currentZoom = currentZoomLevelState.value.coerceIn(1f, safeMaxZoom)
                            val targetZoom = when (resolvedDoubleTapAction) {
                                // Default double-tap is a true toggle: first tap-pair zooms, the
                                // next tap-pair returns to this physical lens' native 1.0x FOV.
                                "2x Zoom" -> if (currentZoom > 1.01f) 1f else min(2f, safeMaxZoom)
                                "Reset Zoom" -> 1f
                                else -> if (currentZoom > 1.01f) 1f else min(2f, safeMaxZoom)
                            }.coerceIn(1f, safeMaxZoom)
                            requestSmoothZoom(targetZoom, durationMs = 280)
                        },
                        onTap = { offset ->
                            val displayXPct = offset.x / size.width
                            val displayYPct = offset.y / size.height
                            val (xPct, yPct) = undoPreviewOrientationCorrection(
                                displayXPct,
                                displayYPct,
                                manualPreviewOrientationCorrectionDegrees
                            )
                            val displayRotationDegrees = when (
                                (context.findActivityLifecycleOwner() as? ComponentActivity)
                                    ?.windowManager?.defaultDisplay?.rotation
                            ) {
                                Surface.ROTATION_90 -> 90
                                Surface.ROTATION_180 -> 180
                                Surface.ROTATION_270 -> 270
                                else -> 0
                            }
                            val previewMirrored =
                                lensFacing == CameraCharacteristics.LENS_FACING_FRONT && mirrorFrontPreview

                            // Any new explicit touch exits manual ownership before requesting AF.
                            manualFocusOverrideActive = false
                            // Focus Track owns the visual focus indicator while active. Normal tap
                            // AF still runs immediately so the lens reacts before the first analysis frame.
                            tapPoint = if (focusTracking) null else offset
                            showPassiveRing = false
                            val traceTap = com.bncam.core.debug.AfGroundTruthUiTap(
                                rawTouchX = offset.x,
                                rawTouchY = offset.y,
                                viewWidth = size.width,
                                viewHeight = size.height,
                                displayNormalizedX = displayXPct,
                                displayNormalizedY = displayYPct,
                                mapperInputNormalizedX = xPct,
                                mapperInputNormalizedY = yPct,
                                displayRotationDegrees = displayRotationDegrees,
                                previewOrientationCorrectionDegrees =
                                    manualPreviewOrientationCorrectionDegrees,
                                previewMirrored = previewMirrored
                            )
                            val lockTimeMs =
                                focusLock.replace("s", "").toLongOrNull()?.times(1000) ?: 3000L

                            if (focusTracking) {
                                bnCameraManager.tapToFocusAndStartTracking(
                                    xPct = xPct,
                                    yPct = yPct,
                                    cameraId = activeLens.id,
                                    traceTap = traceTap,
                                    pinned = false,
                                    mirrorX = previewMirrored,
                                    durationMs = lockTimeMs
                                )
                            } else {
                                bnCameraManager.tapToFocusAt(xPct, yPct, activeLens.id, traceTap)
                                bnCameraManager.holdTapFocusFor(lockTimeMs)
                            }
                        },
                        onLongPress = { offset ->
                            showPassiveRing = false
                            manualFocusOverrideActive = false
                            if (focusTracking) {
                                val displayXPct = offset.x / size.width
                                val displayYPct = offset.y / size.height
                                val (xPct, yPct) = undoPreviewOrientationCorrection(
                                    displayXPct,
                                    displayYPct,
                                    manualPreviewOrientationCorrectionDegrees
                                )
                                val displayRotationDegrees = when (
                                    (context.findActivityLifecycleOwner() as? ComponentActivity)
                                        ?.windowManager?.defaultDisplay?.rotation
                                ) {
                                    Surface.ROTATION_90 -> 90
                                    Surface.ROTATION_180 -> 180
                                    Surface.ROTATION_270 -> 270
                                    else -> 0
                                }
                                val previewMirrored =
                                    lensFacing == CameraCharacteristics.LENS_FACING_FRONT && mirrorFrontPreview
                                tapPoint = null
                                bnCameraManager.tapToFocusAndStartTracking(
                                    xPct = xPct,
                                    yPct = yPct,
                                    cameraId = activeLens.id,
                                    traceTap = com.bncam.core.debug.AfGroundTruthUiTap(
                                        rawTouchX = offset.x,
                                        rawTouchY = offset.y,
                                        viewWidth = size.width,
                                        viewHeight = size.height,
                                        displayNormalizedX = displayXPct,
                                        displayNormalizedY = displayYPct,
                                        mapperInputNormalizedX = xPct,
                                        mapperInputNormalizedY = yPct,
                                        displayRotationDegrees = displayRotationDegrees,
                                        previewOrientationCorrectionDegrees =
                                            manualPreviewOrientationCorrectionDegrees,
                                        previewMirrored = previewMirrored
                                    ),
                                    pinned = true,
                                    mirrorX = previewMirrored,
                                    durationMs = null
                                )
                            } else {
                                val displayXPct = offset.x / size.width
                                val displayYPct = offset.y / size.height
                                val (xPct, yPct) = undoPreviewOrientationCorrection(
                                    displayXPct,
                                    displayYPct,
                                    manualPreviewOrientationCorrectionDegrees
                                )
                                val displayRotationDegrees = when (
                                    (context.findActivityLifecycleOwner() as? ComponentActivity)
                                        ?.windowManager?.defaultDisplay?.rotation
                                ) {
                                    Surface.ROTATION_90 -> 90
                                    Surface.ROTATION_180 -> 180
                                    Surface.ROTATION_270 -> 270
                                    else -> 0
                                }
                                val previewMirrored =
                                    lensFacing == CameraCharacteristics.LENS_FACING_FRONT && mirrorFrontPreview
                                tapPoint = offset
                                bnCameraManager.focusAndLockAt(
                                    xPct = xPct,
                                    yPct = yPct,
                                    cameraId = activeLens.id,
                                    traceTap = com.bncam.core.debug.AfGroundTruthUiTap(
                                        rawTouchX = offset.x,
                                        rawTouchY = offset.y,
                                        viewWidth = size.width,
                                        viewHeight = size.height,
                                        displayNormalizedX = displayXPct,
                                        displayNormalizedY = displayYPct,
                                        mapperInputNormalizedX = xPct,
                                        mapperInputNormalizedY = yPct,
                                        displayRotationDegrees = displayRotationDegrees,
                                        previewOrientationCorrectionDegrees = manualPreviewOrientationCorrectionDegrees,
                                        previewMirrored = previewMirrored
                                    )
                                )
                            }
                            if (useHaptics) {
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                )
                            }
                        }
                    )
                }
                .pointerInput(activeLens.id, rearFocalLenses, maxDigitalZoom) {
                    detectTransformGestures { _, _, zoomMultiplier, _ ->
                        if (!zoomMultiplier.isFinite() || zoomMultiplier <= 0f) return@detectTransformGestures
                        if (abs(zoomMultiplier - 1f) < 0.002f) return@detectTransformGestures
                        pinchGestureGeneration += 1

                        val firstNative = rearFocalLenses.firstOrNull()?.equivalentFocalLength35mm ?: activeNativeFocalMm
                        val lastNative = rearFocalLenses.lastOrNull()?.equivalentFocalLength35mm ?: activeNativeFocalMm
                        val minFocal = firstNative.coerceAtMost(activeNativeFocalMm)
                        val maxFocal = (lastNative * maxDigitalZoom.coerceAtLeast(1f))
                            .coerceAtLeast(activeNativeFocalMm)
                        val currentVirtual = pinchVirtualFocalMm
                            ?: (activeNativeFocalMm * requestedZoomLevel)
                        val requestedFocal = (currentVirtual * zoomMultiplier).coerceIn(minFocal, maxFocal)
                        pinchVirtualFocalMm = requestedFocal

                        val targetLens = rearFocalLenses
                            .lastOrNull { (it.equivalentFocalLength35mm ?: Float.MAX_VALUE) <= requestedFocal + 0.15f }
                            ?: rearFocalLenses.firstOrNull()
                            ?: activeLens

                        if (targetLens.id != activeLens.id) {
                            pendingPinchFocalMm = requestedFocal
                            if (pendingPinchLensId != targetLens.id) {
                                pendingPinchLensId = targetLens.id
                                onLensSelected(targetLens)
                            }
                        } else if (pendingPinchLensId == null) {
                            requestSmoothZoom(
                                targetZoom = requestedFocal / activeNativeFocalMm,
                                durationMs = 70
                            )
                        } else {
                            pendingPinchFocalMm = requestedFocal
                        }
                    }
                }
        ) {
            CameraPreview(
                cameraId = activeLens.id,
                yuvOrientationCorrectionDegrees = effectiveYuvOrientationCorrectionDegrees,
                bnCameraManager = bnCameraManager,
                isPeakingEnabled = focusPeak,
                peakingColorStr = focusPeakColor,
                focusGuidance = focusPeakingGuidance,
                peakingTarget = peakingDisplayTarget,
                activeProfile = activeProfile,
                previewViewRef = previewViewRef,
                digitalZoom = { currentZoomLevelState.value },
                modifier = Modifier.fillMaxSize()
            )

            // De Generieke Status Overlay
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                ActionStatusOverlay(
                    message = currentOverlayMessage,
                    onDismiss = { currentOverlayMessage = null },
                    modifier = Modifier.rotate(animatedUiRotation) // Draait netjes mee met je UI!
                )
            }

            // De Grote Afteller Weergave
            if (activeCountdown > 0) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = activeCountdown.toString(),
                        color = Color.White,
                        fontSize = 120.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.rotate(animatedUiRotation),
                        style = androidx.compose.ui.text.TextStyle(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.5f),
                                offset = Offset(0f, 6f),
                                blurRadius = 12f
                            )
                        )
                    )
                }
            }

            CompositionOverlay(
                modifier = Modifier.fillMaxSize(),
                gridStyle = gridLines,
                showHorizon = horizonLeveler,
                showCrosshair = centerCrosshair,
                roll = sensorUtils.roll.floatValue
            )

            val faceOwnsFocusVisual =
                focusOwnership.owner == com.bncam.core.engine.FocusOwner.FACE_PRIORITY

            FocusOverlay(
                showData = focusData,
                showSlider = false,
                showRing = focusRing && !focusTrackingActive && !faceOwnsFocusVisual,
                tapPoint = tapPoint,
                showPassiveRing = showPassiveRing,
                focusDistance = manualFocusDistance,
                liveFocusDiopters = liveFocusDiopters,
                onDistanceChange = { newValue ->
                    manualFocusOverrideActive = true
                    manualFocusDistance = newValue
                },
                onReset = {
                    manualFocusOverrideActive = false
                    manualFocusDistance = 0.5f
                    bnCameraManager.triggerContinuousAutoFocus()
                },
                onRingAnimationEnd = { },
                onPassiveRingEnd = { showPassiveRing = false },
                uiRotationDegrees = animatedUiRotation
            )

            if (pinchZoomDialVisible) {
                FullWidthFocalLengthDial(
                    currentFocalMm = pinchVirtualFocalMm ?: (activeNativeFocalMm * currentZoomLevel),
                    sensorFocalLengthsMm = rearFocalLenses.mapNotNull { it.equivalentFocalLength35mm },
                    maxFocalMm = ((rearFocalLenses.lastOrNull()?.equivalentFocalLength35mm
                        ?: activeNativeFocalMm) * maxDigitalZoom.coerceAtLeast(1f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 4.dp)
                        .zIndex(8f)
                )
            } else if (exposureDialState.visible) {
                FullWidthExposureDial(
                    state = exposureDialState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 4.dp)
                        .zIndex(8f)
                )
            }

            if (!exposureDialState.visible && !pinchZoomDialVisible) {
                QuickZoomPill(
                    currentZoom = { requestedZoomLevel },
                    maxZoom = maxDigitalZoom,
                    uiRotationDegrees = animatedUiRotation,
                    onZoomSelected = { target ->
                        pinchVirtualFocalMm = activeNativeFocalMm * target
                        requestSmoothZoom(target, durationMs = 180)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 7.dp)
                        .zIndex(9f)
                )
            }

            ExposureOverlay(
                showSlider = false,
                showHistogram = showHistogram,
                liveHistogram = liveHistogram,
                liveRgbHistogram = liveRgbHistogram,
                exposureValue = manualExposureValue,
                onExposureChange = { newValue ->
                    manualExposureValue = newValue
                }
            )

            AssignedViewfinderSlider(
                assignment = leftSliderAssignment,
                side = SliderSide.LEFT,
                focusValue = manualFocusDistance,
                exposureValue = manualExposureValue,
                isoFraction = assignedIsoFraction,
                shutterFraction = assignedShutterFraction,
                exposureBounds = manualExposureBounds,
                zoomValue = currentZoomLevel,
                maxZoom = maxDigitalZoom,
                saturationValue = liveViewfinderTuning.saturationOffset,
                contrastValue = liveViewfinderTuning.contrastOffset,
                whiteBalanceKelvin = liveViewfinderTuning.whiteBalanceKelvin,
                whiteBalanceSupported = manualWhiteBalanceSupported,
                onFocusChange = { newValue ->
                    manualFocusOverrideActive = true
                    manualFocusDistance = newValue
                    val diopters = maxFocusDiopters * (1f - newValue).let { it * it * it }
                    showSliderFeedback(if (maxFocusDiopters > 0f) "Focus ${String.format(Locale.US, "%.2f", diopters)} D" else "Focus ${Math.round(newValue * 100f)}%")
                },
                onFocusReset = {
                    manualFocusOverrideActive = false
                    manualFocusDistance = 0.5f
                    bnCameraManager.triggerContinuousAutoFocus()
                    showSliderFeedback("Focus Auto")
                },
                onExposureChange = {
                    manualExposureValue = it
                    showSliderFeedback("EV ${if (it >= 0f) "+" else ""}${String.format(Locale.US, "%.2f", it)}")
                },
                onIsoChange = { fraction ->
                    assignedIsoFraction = fraction
                    assignedIsoValue = manualExposureBounds?.let { bounds ->
                        logarithmicIntValue(fraction, bounds.minIso, bounds.maxIso)
                    }
                    showSliderFeedback(assignedIsoValue?.let { "ISO $it" } ?: "ISO Auto")
                },
                onIsoReset = {
                    assignedIsoFraction = 0.5f
                    assignedIsoValue = null
                    showSliderFeedback("ISO Auto")
                },
                onShutterChange = { fraction ->
                    assignedShutterFraction = fraction
                    assignedShutterNs = manualExposureBounds?.let { bounds ->
                        logarithmicLongValue(fraction, bounds.minExposureNs, bounds.maxExposureNs)
                    }
                    showSliderFeedback(assignedShutterNs?.let(::formatShutterNs) ?: "Shutter Auto")
                },
                onShutterReset = {
                    assignedShutterFraction = 0.5f
                    assignedShutterNs = null
                    showSliderFeedback("Shutter Auto")
                },
                onZoomChange = { zoom ->
                    val targetZoom = zoom.coerceIn(1f, maxDigitalZoom)
                    requestSmoothZoom(targetZoom, durationMs = 70)
                    showSliderFeedback("Zoom ${String.format(Locale.US, "%.2f", targetZoom)}×")
                },
                onZoomReset = {
                    requestSmoothZoom(1f, durationMs = 100)
                    showSliderFeedback("Zoom 1.00×")
                },
                onSaturationChange = { value ->
                    ViewfinderLiveTuning.setSaturationOffset(value)
                    bnCameraManager.notifyViewfinderLiveTuningChanged()
                    showSliderFeedback("Saturation ${if (value >= 0f) "+" else ""}${String.format(Locale.US, "%.2f", value)}")
                },
                onSaturationReset = {
                    ViewfinderLiveTuning.resetSaturation()
                    bnCameraManager.notifyViewfinderLiveTuningChanged()
                    showSliderFeedback("Saturation 0.00")
                },
                onContrastChange = { value ->
                    ViewfinderLiveTuning.setContrastOffset(value)
                    bnCameraManager.notifyViewfinderLiveTuningChanged()
                    showSliderFeedback("Contrast ${if (value >= 0f) "+" else ""}${String.format(Locale.US, "%.2f", value)}")
                },
                onContrastReset = {
                    ViewfinderLiveTuning.resetContrast()
                    bnCameraManager.notifyViewfinderLiveTuningChanged()
                    showSliderFeedback("Contrast 0.00")
                },
                onWhiteBalanceChange = { kelvin ->
                    ViewfinderLiveTuning.setWhiteBalanceKelvin(kelvin)
                    bnCameraManager.setViewfinderWhiteBalance(
                        profileSettings = activeProfileAwbSettings,
                        liveKelvin = kelvin,
                        cameraId = activeLens.id
                    )
                    showSliderFeedback("WB $kelvin K")
                },
                onWhiteBalanceReset = {
                    ViewfinderLiveTuning.resetWhiteBalance()
                    bnCameraManager.setViewfinderWhiteBalance(
                        profileSettings = activeProfileAwbSettings,
                        liveKelvin = null,
                        cameraId = activeLens.id
                    )
                    showSliderFeedback("WB Auto")
                },
                uiRotationDegrees = animatedUiRotation,
                onGestureActiveChange = { leftSliderGestureActive = it }
            )
            AssignedViewfinderSlider(
                assignment = rightSliderAssignment,
                side = SliderSide.RIGHT,
                focusValue = manualFocusDistance,
                exposureValue = manualExposureValue,
                isoFraction = assignedIsoFraction,
                shutterFraction = assignedShutterFraction,
                exposureBounds = manualExposureBounds,
                zoomValue = currentZoomLevel,
                maxZoom = maxDigitalZoom,
                saturationValue = liveViewfinderTuning.saturationOffset,
                contrastValue = liveViewfinderTuning.contrastOffset,
                whiteBalanceKelvin = liveViewfinderTuning.whiteBalanceKelvin,
                whiteBalanceSupported = manualWhiteBalanceSupported,
                onFocusChange = { newValue ->
                    manualFocusOverrideActive = true
                    manualFocusDistance = newValue
                    val diopters = maxFocusDiopters * (1f - newValue).let { it * it * it }
                    showSliderFeedback(if (maxFocusDiopters > 0f) "Focus ${String.format(Locale.US, "%.2f", diopters)} D" else "Focus ${Math.round(newValue * 100f)}%")
                },
                onFocusReset = {
                    manualFocusOverrideActive = false
                    manualFocusDistance = 0.5f
                    bnCameraManager.triggerContinuousAutoFocus()
                    showSliderFeedback("Focus Auto")
                },
                onExposureChange = {
                    manualExposureValue = it
                    showSliderFeedback("EV ${if (it >= 0f) "+" else ""}${String.format(Locale.US, "%.2f", it)}")
                },
                onIsoChange = { fraction ->
                    assignedIsoFraction = fraction
                    assignedIsoValue = manualExposureBounds?.let { bounds ->
                        logarithmicIntValue(fraction, bounds.minIso, bounds.maxIso)
                    }
                    showSliderFeedback(assignedIsoValue?.let { "ISO $it" } ?: "ISO Auto")
                },
                onIsoReset = {
                    assignedIsoFraction = 0.5f
                    assignedIsoValue = null
                    showSliderFeedback("ISO Auto")
                },
                onShutterChange = { fraction ->
                    assignedShutterFraction = fraction
                    assignedShutterNs = manualExposureBounds?.let { bounds ->
                        logarithmicLongValue(fraction, bounds.minExposureNs, bounds.maxExposureNs)
                    }
                    showSliderFeedback(assignedShutterNs?.let(::formatShutterNs) ?: "Shutter Auto")
                },
                onShutterReset = {
                    assignedShutterFraction = 0.5f
                    assignedShutterNs = null
                    showSliderFeedback("Shutter Auto")
                },
                onZoomChange = { zoom ->
                    val targetZoom = zoom.coerceIn(1f, maxDigitalZoom)
                    requestSmoothZoom(targetZoom, durationMs = 70)
                    showSliderFeedback("Zoom ${String.format(Locale.US, "%.2f", targetZoom)}×")
                },
                onZoomReset = {
                    requestSmoothZoom(1f, durationMs = 100)
                    showSliderFeedback("Zoom 1.00×")
                },
                onSaturationChange = { value ->
                    ViewfinderLiveTuning.setSaturationOffset(value)
                    bnCameraManager.notifyViewfinderLiveTuningChanged()
                    showSliderFeedback("Saturation ${if (value >= 0f) "+" else ""}${String.format(Locale.US, "%.2f", value)}")
                },
                onSaturationReset = {
                    ViewfinderLiveTuning.resetSaturation()
                    bnCameraManager.notifyViewfinderLiveTuningChanged()
                    showSliderFeedback("Saturation 0.00")
                },
                onContrastChange = { value ->
                    ViewfinderLiveTuning.setContrastOffset(value)
                    bnCameraManager.notifyViewfinderLiveTuningChanged()
                    showSliderFeedback("Contrast ${if (value >= 0f) "+" else ""}${String.format(Locale.US, "%.2f", value)}")
                },
                onContrastReset = {
                    ViewfinderLiveTuning.resetContrast()
                    bnCameraManager.notifyViewfinderLiveTuningChanged()
                    showSliderFeedback("Contrast 0.00")
                },
                onWhiteBalanceChange = { kelvin ->
                    ViewfinderLiveTuning.setWhiteBalanceKelvin(kelvin)
                    bnCameraManager.setViewfinderWhiteBalance(
                        profileSettings = activeProfileAwbSettings,
                        liveKelvin = kelvin,
                        cameraId = activeLens.id
                    )
                    showSliderFeedback("WB $kelvin K")
                },
                onWhiteBalanceReset = {
                    ViewfinderLiveTuning.resetWhiteBalance()
                    bnCameraManager.setViewfinderWhiteBalance(
                        profileSettings = activeProfileAwbSettings,
                        liveKelvin = null,
                        cameraId = activeLens.id
                    )
                    showSliderFeedback("WB Auto")
                },
                uiRotationDegrees = animatedUiRotation,
                onGestureActiveChange = { rightSliderGestureActive = it }
            )

            DetectionOverlay(
                showFaces = faceDetection && !focusTrackingActive,
                faces = detectedFaces,
                sensorRect = sensorRect,
                sensorOrientation = sensorOrientation,
                lensFacing = lensFacing,
                previewOrientationCorrectionDegrees = manualPreviewOrientationCorrectionDegrees,
                showQr = qrDetection,
                qrUrl = detectedQrCode,
                trackedObjectBounds = trackedObjectBounds,
                trackingState = focusTrackingState,
                priorityFaceBounds = priorityFaceBounds,
                onOpenUrl = { url ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("CameraScreen", "Kan URL niet openen", e)
                    }
                }
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = sliderFeedbackText != null,
                enter = androidx.compose.animation.fadeIn(animationSpec = tween(120)),
                exit = androidx.compose.animation.fadeOut(animationSpec = tween(500))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(top = 72.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = sliderFeedbackText.orEmpty(),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        // --- 3. BOTTOM CONTROLS AREA ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f), // Vult alle ruimte flexibel op onder de viewfinder
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // 1. Top buffer: Zorgt dat de moduskiezer niet direct tegen de viewfinder aanplakt
            Spacer(modifier = Modifier.weight(1f))

            // A. MIDDELSTE LAAG (Halo Buttons + Interactive Swipe Modes)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dedicatedShutterSlider && manualExposureBounds != null) {
                    InteractiveHaloButton(
                        currentValue = dedicatedShutterLabel,
                        valuesList = dedicatedShutterValues,
                        onValueChange = { label ->
                            assignedShutterNs = parseShutterString(label)
                            assignedShutterFraction = assignedShutterNs?.let { ns ->
                                logarithmicFraction(ns, manualExposureBounds.minExposureNs, manualExposureBounds.maxExposureNs)
                            } ?: 0.5f
                        },
                        onReset = {
                            assignedShutterNs = null
                            assignedShutterFraction = 0.5f
                        },
                        isIso = false,
                        uiRotationDegrees = animatedUiRotation,
                        onDialStateChange = { visible, residual, step, iso ->
                            exposureDialState = ViewfinderExposureDialState(visible, residual, step, iso, dedicatedShutterLabel)
                        }
                    )
                } else {
                    Box(modifier = Modifier.defaultMinSize(minWidth = 60.dp))
                }

                CameraModeSelector(
                    selectedMode = viewfinderMode,
                    onModeSelected = { mode -> viewfinderMode = mode },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )

                if (dedicatedIsoSlider && manualExposureBounds != null) {
                    InteractiveHaloButton(
                        currentValue = dedicatedIsoLabel,
                        valuesList = dedicatedIsoValues,
                        onValueChange = { label ->
                            assignedIsoValue = parseIsoString(label)
                            assignedIsoFraction = assignedIsoValue?.let { iso ->
                                logarithmicFraction(iso.toLong(), manualExposureBounds.minIso.toLong(), manualExposureBounds.maxIso.toLong())
                            } ?: 0.5f
                        },
                        onReset = {
                            assignedIsoValue = null
                            assignedIsoFraction = 0.5f
                        },
                        isIso = true,
                        uiRotationDegrees = animatedUiRotation,
                        onDialStateChange = { visible, residual, step, iso ->
                            exposureDialState = ViewfinderExposureDialState(visible, residual, step, iso, dedicatedIsoLabel)
                        }
                    )
                } else {
                    Box(modifier = Modifier.defaultMinSize(minWidth = 60.dp))
                }
            }

            // 2. Center buffer: Creëert een perfect gebalanceerde, flexibele ruimte tussen de twee lagen
            Spacer(modifier = Modifier.weight(1f))

            // B. ONDERSTE LAAG (Gallery | Grote Shutter | Lens Selector)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {

                CaptureThumbnailFeedback(
                    latestSnapshot = latestSnapshot,
                    publishedModel = publishedUriState,
                    uiRotationDegrees = animatedUiRotation,
                    onOpenPublished = {
                        publishedUriState?.let { uri ->
                            fun openGallery(packageName: String? = null) {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    if (packageName != null) setPackage(packageName)
                                }
                                context.startActivity(intent)
                            }
                            if (forceGooglePhotos) {
                                try {
                                    openGallery("com.google.android.apps.photos")
                                } catch (_: Exception) {
                                    runCatching { openGallery() }
                                }
                            } else {
                                runCatching { openGallery() }
                            }
                        }
                    }
                )

                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()

                // 1. Schaal: Krimpt strakker bij indrukken
                val shutterScale by animateFloatAsState(
                    targetValue = if (isPressed) 0.82f else 1f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                    label = "shutterScale"
                )

                // 2. Vorm: Van perfecte cirkel (44.dp) naar Material "Squircle" (28.dp)
                val cornerRadius by animateDpAsState(
                    targetValue = if (isPressed) 28.dp else 44.dp,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                    label = "shutterShape"
                )

                // 3. Witte "Fill" animatie: 0f = lege ring, 1f = inkt-druppel vult de hele knop
                val fillProgress by animateFloatAsState(
                    targetValue = if (isPressed) 1f else 0f,
                    animationSpec = tween(150, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
                    label = "shutterFill"
                )

                // 4. Rotatie: Mechanische klik-bevestiging
                var shutterRotation by remember { mutableFloatStateOf(0f) }
                val animatedRotation by animateFloatAsState(
                    targetValue = shutterRotation,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 200f),
                    label = "shutterRot"
                )

                // GROTE SHUTTER KNOP (Vullende Ring Design)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(88.dp)
                        .scale(shutterScale)
                        .rotate(animatedRotation)
                        .clickable(
                            enabled = !shutterDispatchInFlight &&
                                CaptureProcessingQueue.nonIdleCount() < CaptureProcessingQueue.MAX_IN_FLIGHT_RAW_WORK,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {
                                shutterRotation += 90f
                                triggerCaptureSequence()
                            }
                        )
                ) {
                    // De basis is altijd jouw pistachio groen.
                    // We clippen de layer, zodat het uitvouwende wit nooit buiten de knop steekt.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AccentPistachio, RoundedCornerShape(cornerRadius))
                            .clip(RoundedCornerShape(cornerRadius))
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val centerPt = Offset(size.width / 2, size.height / 2)
                            val ringRadius = 12.dp.toPx()

                            // Teken de statische smalle witte ring in het midden (Dimt als we nog niet ready zijn)
                            if (fillProgress < 1f) {
                                drawCircle(
                                    color = if (isCaptureReady) Color.White else Color.Gray.copy(alpha = 0.4f),
                                    radius = ringRadius,
                                    center = centerPt,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                )
                            }

                            // De fill-animatie: groeit vanuit het nulpunt totdat het de randen overspoelt
                            if (fillProgress > 0f) {
                                // 0.75f is ruim voldoende om vanuit het midden de hoeken van een 88.dp box te raken
                                val maxRadius = size.width * 0.75f
                                val currentRadius = maxRadius * fillProgress
                                drawCircle(
                                    color = Color.White,
                                    radius = currentRadius,
                                    center = centerPt,
                                    style = androidx.compose.ui.graphics.drawscope.Fill
                                )
                            }
                        }
                    }
                }

                // Lens routing remains owned by AppNavigation/BnCameraManager. This control only
                // chooses among the capability-derived visible routes already supplied to CameraScreen.
                ViewfinderLensSelector(
                    activeLens = activeLens,
                    visibleLenses = visibleLenses,
                    uiRotationDegrees = animatedUiRotation,
                    onLensSelected = onLensSelected,
                    modifier = Modifier.size(72.dp),
                    hapticsEnabled = useHaptics
                )
            }

            // 3. Bottom buffer: Houdt de knoppen flexibel en veilig boven de Android systeem-navigatiebalk
            Spacer(modifier = Modifier.weight(0.8f))
        }

    }
}

// ==========================================
// ANDROID PREVIEW COMPONENT (MET OPENGL FOCUS PEAKING & SOFT RESET)
// ==========================================
@SuppressLint("MissingPermission")
@Composable
fun CameraPreview(
    cameraId: String,
    yuvOrientationCorrectionDegrees: Int,
    bnCameraManager: BnCameraManager,
    isPeakingEnabled: Boolean,
    peakingColorStr: String,
    focusGuidance: FocusPeakingGuidance,
    peakingTarget: FocusPeakingDisplayTarget,
    activeProfile: CameraProfile,
    previewViewRef: Array<FocusPeakingView?>? = null,
    digitalZoom: () -> Float = { 1f },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val systemCameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val navigationLifecycleOwner = LocalLifecycleOwner.current
    // LocalLifecycleOwner inside Navigation is the NavBackStackEntry. It pauses when navigating
    // to another in-app destination even though the Activity (and camera permission/lifetime) is
    // still foreground. Use the Activity lifecycle for real foreground/background ownership.
    val activityLifecycleOwner = remember(context) {
        context.findActivityLifecycleOwner() ?: navigationLifecycleOwner
    }
    var isAppInForeground by remember {
        mutableStateOf(activityLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    val repository = remember { SettingsRepository(context) }
    val latestActiveProfile = rememberUpdatedState(activeProfile)
    val livePreviewTuning by ViewfinderLiveTuning.state.collectAsState()
    val liveWbDisplayCompensation by bnCameraManager.liveWhiteBalanceDisplayCompensation.collectAsState()
    val viewfinderRebuildVisualState by bnCameraManager.viewfinderRebuildVisualState.collectAsState()
    val viewfinderRebuildOverlayAlpha by animateFloatAsState(
        targetValue = if (viewfinderRebuildVisualState.active) 1f else 0f,
        animationSpec = tween(durationMillis = if (viewfinderRebuildVisualState.active) 70 else 120),
        label = "viewfinderProducerRebuildBlackTransition"
    )

    // UI reset identity is deliberately only the producer buffer source. Settings/profile
    // edits that leave YUV/RAW10/RAW_SENSOR unchanged keep the warm ImageReader/ring buffer.
    // Viewfinder-stream mode is tracked separately because YUV <-> Selected buffer is itself an
    // explicit soft-reset boundary.
    fun pipelineUiKey(frameSource: String): String {
        val normalized = frameSource.uppercase()
        return when {
            normalized.contains("RAW_SENSOR") -> "RAW_SENSOR"
            normalized.contains("RAW10") -> "RAW10"
            else -> "YUV"
        }
    }

    val mirrorFront by repository.mirrorFrontPreviewFlow.collectAsState(initial = true)
    val viewfinderStream by repository.viewfinderStreamFlow.collectAsState(initial = ViewfinderStream.YUV)
    val preferredFormat by repository.getProfileFrameSourceFlow(activeProfile.id).collectAsState(initial = null)
    // Keep the existing display host alive while the next lens route is resolved. Resetting this
    // state to null on every cameraId change removed the entire GLSurfaceView for one composition
    // and guaranteed a black transition before Camera2 handover even started.
    var previewCameraHardwareState by remember {
        mutableStateOf<PreviewCameraHardwareState?>(null)
    }
    LaunchedEffect(cameraId) {
        previewCameraHardwareState = withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val route = bnCameraManager.resolveCameraDeviceRoute(cameraId)
                val chars = runCatching { systemCameraManager.getCameraCharacteristics(cameraId) }
                    .getOrElse { systemCameraManager.getCameraCharacteristics(route.logicalCameraId) }
                PreviewCameraHardwareState(
                    logicalCameraId = route.logicalCameraId,
                    isFrontCamera = chars.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                )
            }.getOrElse {
                PreviewCameraHardwareState(logicalCameraId = cameraId, isFrontCamera = false)
            }
        }
    }
    val isFrontCamera = previewCameraHardwareState?.isFrontCamera ?: false

    val peakRgb = remember(peakingColorStr) {
        when (peakingColorStr) {
            "Red" -> floatArrayOf(1.0f, 0.0f, 0.0f)
            "Green" -> floatArrayOf(0.0f, 1.0f, 0.0f)
            "Blue" -> floatArrayOf(0.0f, 0.5f, 1.0f)
            "Yellow" -> floatArrayOf(1.0f, 1.0f, 0.0f)
            else -> floatArrayOf(1.0f, 0.0f, 0.0f)
        }
    }

    DisposableEffect(activityLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) isAppInForeground = true
            else if (event == Lifecycle.Event.ON_PAUSE) isAppInForeground = false
        }
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { activityLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (isAppInForeground && previewCameraHardwareState != null) {
        // The display Surface is process/UI-host owned, not CameraDevice owned. Camera2 sessions
        // may retire and a different logical CameraDevice may later reuse the same valid Surface
        // only after the serialized manager transition has released the previous hardware owner.
        // Keeping this key stable preserves the last-known-good GL texture through both physical
        // and hard logical-camera handovers.
        val activeLogicalCameraId = previewCameraHardwareState!!.logicalCameraId
        val previewSurfaceOwnerKey = "persistent_camera_viewfinder"
        var surfaceTexture by remember(previewSurfaceOwnerKey) { mutableStateOf<SurfaceTexture?>(null) }
        var cameraOutputSurface by remember(previewSurfaceOwnerKey) { mutableStateOf<Surface?>(null) }
        var configuredPreviewSurfaceSize by remember(previewSurfaceOwnerKey) {
            mutableStateOf<Pair<Int, Int>?>(null)
        }
        val ownedPreviewViewRef = remember(previewSurfaceOwnerKey) { arrayOfNulls<FocusPeakingView>(1) }
        val effectivePreviewViewRef = previewViewRef ?: ownedPreviewViewRef
        var previewView by remember(previewSurfaceOwnerKey) { mutableStateOf<FocusPeakingView?>(null) }

        var isFirstLaunch by remember { mutableStateOf(true) }
        var activePipelineKey by remember { mutableStateOf<String?>(null) }
        var appliedViewfinderStream by remember { mutableStateOf<ViewfinderStream?>(null) }

        Box(modifier = modifier) {
            key(previewSurfaceOwnerKey) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = if (isFrontCamera && mirrorFront) -1f else 1f
                        },
                    factory = { ctx ->
                        FocusPeakingView(ctx).apply {
                            effectivePreviewViewRef[0] = this
                            previewView = this
                            clearPreviewForCameraTransition()
                            this.onSurfaceTextureCreated = { st ->
                                surfaceTexture = st
                            }
                            this.onRawUploadTiming = { uploadMs ->
                                bnCameraManager.reportRawPreviewGlUploadTime(uploadMs)
                            }
                            this.onYuvFrameAvailable = { sensorTimestampNs ->
                                bnCameraManager.reportYuvViewfinderFrameAvailable(sensorTimestampNs)
                            }
                        }
                    },
                    update = { view ->
                        view.isPeakingEnabled = isPeakingEnabled
                        view.peakingColor = peakRgb
                        view.setFocusPeakingGuidance(
                            focusConfidence = focusGuidance.focusConfidence,
                            confidenceClass = when (focusGuidance.confidenceState) {
                                com.bncam.core.quality.FocusConfidenceState.CONFIDENT_SHARP -> 1f
                                com.bncam.core.quality.FocusConfidenceState.CONFIDENT_SOFT -> -1f
                                com.bncam.core.quality.FocusConfidenceState.INDETERMINATE -> 0f
                            },
                            afScanning = focusGuidance.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
                                focusGuidance.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
                            lensMoving = focusGuidance.lensState == android.hardware.camera2.CaptureResult.LENS_STATE_MOVING,
                            subjectRoiActive = peakingTarget.active,
                            targetCenterX = peakingTarget.centerX,
                            targetCenterY = peakingTarget.centerY,
                            targetRadiusX = peakingTarget.radiusX,
                            targetRadiusY = peakingTarget.radiusY
                        )
                        view.setDiagnosticLensId(cameraId)
                        view.setYuvOrientationCorrection(yuvOrientationCorrectionDegrees)
                        // Camera2 owns YUV zoom. RAW buffers keep their full sensor payload, so
                        // mirror the same user zoom in the RAW-only texture-coordinate path.
                        view.setRawDisplayZoom(digitalZoom())
                        view.setLiveColorTuning(
                            saturationOffset = livePreviewTuning.saturationOffset,
                            contrastOffset = livePreviewTuning.contrastOffset,
                            whiteBalanceCompensation = floatArrayOf(
                                liveWbDisplayCompensation.red,
                                liveWbDisplayCompensation.green,
                                liveWbDisplayCompensation.blue
                            )
                        )
                    },
                    onRelease = { view ->
                        view.quiesceForComposeRelease()
                        if (effectivePreviewViewRef[0] === view) {
                            effectivePreviewViewRef[0] = null
                        }
                    }
                )
            }

            if (viewfinderRebuildOverlayAlpha > 0.001f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = viewfinderRebuildOverlayAlpha }
                        .background(Color.Black)
                )
            }
        }

        DisposableEffect(bnCameraManager, previewView, viewfinderStream, activeProfile.id) {
            val target = previewView
            val callbackRegistrationId = if (target != null) {
                bnCameraManager.configureViewfinderStream(
                    setting = viewfinderStream,
                    profileId = activeProfile.id,
                    onEffectiveSourceChanged = { source, generation ->
                        target.setDisplayedSource(source, generation)
                    },
                    onRawFrame = { frame -> target.submitRawPreviewFrame(frame) }
                )
            } else {
                0L
            }
            onDispose {
                if (callbackRegistrationId > 0L) {
                    bnCameraManager.clearViewfinderStreamCallbacks(callbackRegistrationId)
                }
            }
        }

        // ==========================================
        // 1A. PREVIEW SURFACE OWNERSHIP
        // ==========================================
        // Surface lifetime is tied to the persistent viewfinder host. CameraDevice/session
        // ownership is serialized inside BnCameraManager; lens changes must not destroy the
        // SurfaceTexture merely because a different producer will target it.
        DisposableEffect(previewSurfaceOwnerKey, surfaceTexture) {
            val st = surfaceTexture
            if (st != null) {
                val outputSurface = Surface(st)
                cameraOutputSurface = outputSurface

                onDispose {
                    effectivePreviewViewRef[0]?.clearPreviewForCameraTransition()
                    bnCameraManager.closeCameraForSurfaceRelease(
                        surface = outputSurface,
                        reason = "CAMERA_SCREEN_SURFACE_OWNER_DISPOSED"
                    ) {
                        outputSurface.release()
                    }
                    if (cameraOutputSurface === outputSurface) cameraOutputSurface = null
                    isFirstLaunch = true
                    activePipelineKey = null
                }
            } else {
                onDispose { }
            }
        }

        // ==========================================
        // 1B. CAMERA TARGET TRANSITION
        // ==========================================
        // Physical lens changes retarget the serialized Camera2 pipeline without owning the
        // Surface. Same-logical physical switches are attempted first; a hard CameraDevice start
        // is reserved for routes that cannot retain the currently open logical device.
        LaunchedEffect(cameraId, surfaceTexture, cameraOutputSurface) {
            val st = surfaceTexture ?: return@LaunchedEffect
            val outputSurface = cameraOutputSurface ?: return@LaunchedEffect

            isFirstLaunch = true
            // Keep the current GL/OES texture visible while the new Camera2 producer is prepared.
            // Display generation commits only after BnCameraManager proves a valid target frame.
            var previewWidth = 0
            var previewHeight = 0
            try {
                val chars = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { systemCameraManager.getCameraCharacteristics(cameraId) }
                        .getOrElse {
                            val route = bnCameraManager.resolveCameraDeviceRoute(cameraId)
                            systemCameraManager.getCameraCharacteristics(route.logicalCameraId)
                        }
                }
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(SurfaceTexture::class.java)

                val activeRect =
                    chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                        ?: chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val sensorWidth = activeRect?.width() ?: 0
                val sensorHeight = activeRect?.height() ?: 0
                val sensorAspect = CameraStreamGeometryPolicy.sensorAspect(sensorWidth, sensorHeight)
                    ?.toFloat() ?: (4f / 3f)
                val surfaceTextureSizes = sizes?.toList().orEmpty()
                val fullFovExtents = CameraStreamGeometryPolicy.fullFovCandidates(
                    candidates = surfaceTextureSizes.map {
                        CameraStreamGeometryPolicy.Extent(it.width, it.height)
                    },
                    sensorWidth = sensorWidth,
                    sensorHeight = sensorHeight
                )
                val fullFovKeys = fullFovExtents.mapTo(hashSetOf()) { it.width to it.height }
                val previewCandidateSizes = if (fullFovKeys.isNotEmpty()) {
                    surfaceTextureSizes.filter { (it.width to it.height) in fullFovKeys }
                } else {
                    // Last-resort HAL fallback. Never manufacture an unsupported buffer geometry.
                    surfaceTextureSizes
                }

                val metrics = context.resources.displayMetrics
                val requiredShortSide = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
                val displayAdequate = previewCandidateSizes.filter { size ->
                    min(size.width, size.height) >= requiredShortSide
                }
                val adaptivePreviewSize = if (displayAdequate.isNotEmpty()) {
                    displayAdequate.minByOrNull { it.width.toLong() * it.height.toLong() }
                } else {
                    previewCandidateSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
                }

                if (adaptivePreviewSize != null) {
                    // A SurfaceTexture buffer may be retained across physical-lens switches only
                    // when it is still part of the selected lens's full-FOV candidate pool. Merely
                    // being advertised by the new lens is insufficient: a retained 16:9 main-lens
                    // preview can otherwise force a 4:3 ultra-wide sensor into a cropped readout.
                    val retainedSize = configuredPreviewSurfaceSize?.takeIf { (width, height) ->
                        previewCandidateSizes.any { it.width == width && it.height == height }
                    }
                    val selectedWidth = retainedSize?.first ?: adaptivePreviewSize.width
                    val selectedHeight = retainedSize?.second ?: adaptivePreviewSize.height
                    val surfaceResized = configuredPreviewSurfaceSize != (selectedWidth to selectedHeight)
                    previewWidth = selectedWidth
                    previewHeight = selectedHeight
                    if (surfaceResized) {
                        st.setDefaultBufferSize(previewWidth, previewHeight)
                        configuredPreviewSurfaceSize = previewWidth to previewHeight
                    }
                    effectivePreviewViewRef[0]?.setDiagnosticPreviewBuffer(
                        lensId = cameraId,
                        width = previewWidth,
                        height = previewHeight
                    )
                    Log.i(
                        "BnCamPreviewDiag",
                        "event=SURFACE_TEXTURE_BUFFER_CONFIG selectedLensId=$cameraId " +
                            "surfaceOwner=$previewSurfaceOwnerKey defaultBuffer=${previewWidth}x$previewHeight " +
                            "surfaceResized=$surfaceResized retainedAcrossPhysicalSwitch=${retainedSize != null} " +
                            "source=${if (fullFovKeys.isNotEmpty()) "full_fov_sensor_aspect" else "hal_geometry_fallback"} " +
                            "sensorAspect=$sensorAspect sensor=${sensorWidth}x$sensorHeight " +
                            "fullFovCandidates=${fullFovKeys.size}/${surfaceTextureSizes.size} " +
                            "density=${metrics.density} densityDpi=${metrics.densityDpi} " +
                            "displayPx=${metrics.widthPixels}x${metrics.heightPixels}"
                    )
                } else {
                    Log.w(
                        "BnCamPreviewDiag",
                        "event=SURFACE_TEXTURE_BUFFER_CONFIG selectedLensId=$cameraId " +
                            "surfaceOwner=$previewSurfaceOwnerKey defaultBuffer=platform_default " +
                            "reason=no_compatible_SurfaceTexture_size sensorAspect=$sensorAspect"
                    )
                }
            } catch (e: Exception) {
                Log.e("BnCamPreviewDiag", "Failed to configure preview Surface for lens=$cameraId", e)
            }

            // Use pre-collected Compose state to start reattaching immediately without
            // suspending DataStore disk IO during the navigation entrance animation.
            val startupProfileId = activeProfile.id
            val isStartupProfileDisabled = DefaultIspProfile.isDisabledProfileId(startupProfileId)
            // Frame-source state is asynchronous. Until DataStore resolves the profile-owned
            // value, bootstrap with the repository's canonical frame-source default rather than a
            // capture-strategy enum name. pipelineUiKey() canonicalizes both sides, so the later
            // null -> YUV emission cannot masquerade as a physical buffer transition.
            val initialFormat = if (isStartupProfileDisabled) "YUV" else (preferredFormat ?: "YUV")
            val pipelineReady = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val warmReattached = bnCameraManager.reattachPreviewToWarmPipeline(
                    cameraId = cameraId,
                    previewSurface = outputSurface,
                    preferredFormat = initialFormat,
                    profileId = startupProfileId,
                    previewWidth = previewWidth,
                    previewHeight = previewHeight
                )
                if (!warmReattached) {
                    val sameLogicalHandover = bnCameraManager.handoverToLensWithinOpenLogicalCamera(
                        cameraId = cameraId,
                        previewSurface = outputSurface,
                        preferredFormat = initialFormat,
                        profileId = startupProfileId,
                        previewWidth = previewWidth,
                        previewHeight = previewHeight
                    )
                    if (sameLogicalHandover) {
                        Log.i(
                            "BnCamPreviewDiag",
                            "event=PHYSICAL_LENS_HANDOVER_RETAINED_DEVICE selectedLensId=$cameraId " +
                                "surfaceOwner=$previewSurfaceOwnerKey profile=$startupProfileId format=$initialFormat"
                        )
                        true
                    } else {
                        bnCameraManager.startCameraAndZsl(
                            cameraId = cameraId,
                            previewSurface = outputSurface,
                            preferredFormat = initialFormat,
                            profileId = startupProfileId,
                            previewWidth = previewWidth,
                            previewHeight = previewHeight
                        )
                    }
                } else {
                    Log.i(
                        "BnCamPreviewDiag",
                        "event=NAVIGATION_WARM_PIPELINE_REATTACHED selectedLensId=$cameraId " +
                            "surfaceOwner=$previewSurfaceOwnerKey profile=$startupProfileId format=$initialFormat"
                    )
                    true
                }
            }

            if (!pipelineReady) {
                Log.e(
                    "BnCamPreviewDiag",
                    "event=CAMERA_PIPELINE_START_FAILED selectedLensId=$cameraId " +
                        "logicalCameraId=$activeLogicalCameraId surfaceOwner=$previewSurfaceOwnerKey " +
                        "profile=$startupProfileId format=$initialFormat"
                )
                return@LaunchedEffect
            }

            activePipelineKey = pipelineUiKey(initialFormat)
            appliedViewfinderStream = viewfinderStream
            isFirstLaunch = false
        }

        // ==========================================
        // 2. SOFT RESET (Bij profielwissel OF formaat wissel in het menu)
        // ==========================================
        LaunchedEffect(
            preferredFormat,
            viewfinderStream,
            activePipelineKey,
            isFirstLaunch,
            activeProfile.id
        ) {
            val isProfileDisabled = DefaultIspProfile.isDisabledProfileId(activeProfile.id)
            val resolvedPreferredFormat = if (isProfileDisabled) "YUV" else (preferredFormat ?: return@LaunchedEffect)
            if (surfaceTexture == null || isFirstLaunch) return@LaunchedEffect

            val previousViewfinderStream = appliedViewfinderStream
            val viewfinderStreamChanged = previousViewfinderStream != null &&
                previousViewfinderStream != viewfinderStream
            appliedViewfinderStream = viewfinderStream

            val requestedPipelineKey = pipelineUiKey(resolvedPreferredFormat)
            val bufferChanged = requestedPipelineKey != activePipelineKey
            if (!bufferChanged) {
                // Viewfinder stream changes (YUV <-> Selected buffer) are handled atomically
                // by configureViewfinderStream(). Do not interrupt the warm Camera2 pipeline.
                return@LaunchedEffect
            }

            // Transition coalescing/serialization belongs to BnCameraManager, not to UI sleeps.
            // Submit the current desired state immediately and keep hardware work off main.
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val resetSurface = cameraOutputSurface ?: return@withContext
                bnCameraManager.softResetPipeline(
                    previewSurface = resetSurface,
                    newFormat = resolvedPreferredFormat,
                    profileId = activeProfile.id,
                    forceSessionRebuild = false,
                    reason = "PROFILE_BUFFER_CHANGED_${activePipelineKey ?: "none"}_TO_$requestedPipelineKey"
                )
            }
            activePipelineKey = requestedPipelineKey
        }

    } else {
        Box(modifier = modifier.background(Color.Black))
    }
}

// ==========================================
// COMPOSITION OVERLAY (Grid, Horizon, Crosshair)
// ==========================================
@Composable
fun CompositionOverlay(
    modifier: Modifier = Modifier,
    gridStyle: String,
    showHorizon: Boolean,
    showCrosshair: Boolean,
    roll: Float
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2, h / 2)

        val gridStroke = 0.5.dp.toPx()
        val horizonStroke = 1.dp.toPx()

        val guideColor = Color.White.copy(alpha = 0.4f)

        if (gridStyle != "Off") {
            val fractions = when (gridStyle) {
                "3x3" -> listOf(1f / 3f, 2f / 3f)
                "4x4" -> listOf(1f / 4f, 2f / 4f, 3f / 4f)
                "Golden Ratio" -> listOf(0.382f, 0.618f)
                else -> emptyList()
            }

            fractions.forEach { f ->
                drawLine(guideColor, Offset(w * f, 0f), Offset(w * f, h), gridStroke)
                drawLine(guideColor, Offset(0f, h * f), Offset(w, h * f), gridStroke)
            }
        }

        if (showCrosshair) {
            val crossLength = 6.dp.toPx()
            val crossGap = 3.dp.toPx()
            val crossColor = Color.White.copy(alpha = 0.8f)

            drawLine(crossColor, Offset(center.x - crossGap - crossLength, center.y), Offset(center.x - crossGap, center.y), horizonStroke)
            drawLine(crossColor, Offset(center.x + crossGap, center.y), Offset(center.x + crossGap + crossLength, center.y), horizonStroke)

            drawLine(crossColor, Offset(center.x, center.y - crossGap - crossLength), Offset(center.x, center.y - crossGap), horizonStroke)
            drawLine(crossColor, Offset(center.x, center.y + crossGap), Offset(center.x, center.y + crossGap + crossLength), horizonStroke)

            drawCircle(crossColor, radius = 0.5.dp.toPx(), center = center)
        }

        if (showHorizon) {
            val isLevel = abs(roll) < 1.0f
            val levelColor = if (isLevel) AccentPistachio else Color.White
            val lineLength = w * 0.35f

            rotate(degrees = -roll) {
                drawLine(levelColor, Offset(center.x - lineLength / 2, center.y), Offset(center.x - 16.dp.toPx(), center.y), horizonStroke)
                drawLine(levelColor, Offset(center.x + 16.dp.toPx(), center.y), Offset(center.x + lineLength / 2, center.y), horizonStroke)
            }

            drawCircle(guideColor, radius = 1.5.dp.toPx(), center = Offset(center.x - 30.dp.toPx(), center.y))
            drawCircle(guideColor, radius = 1.5.dp.toPx(), center = Offset(center.x + 30.dp.toPx(), center.y))
        }
    }
}

// ==========================================
// FOCUS OVERLAY (Ring, Data, Slider)
// ==========================================
private data class ManualExposureBounds(
    val minIso: Int,
    val maxIso: Int,
    val minExposureNs: Long,
    val maxExposureNs: Long
)

private enum class SliderSide { LEFT, RIGHT }

@Composable
private fun AssignedViewfinderSlider(
    assignment: ViewfinderSliderAssignment,
    side: SliderSide,
    focusValue: Float,
    exposureValue: Float,
    isoFraction: Float,
    shutterFraction: Float,
    exposureBounds: ManualExposureBounds?,
    zoomValue: Float,
    maxZoom: Float,
    saturationValue: Float,
    contrastValue: Float,
    whiteBalanceKelvin: Int?,
    whiteBalanceSupported: Boolean,
    onFocusChange: (Float) -> Unit,
    onFocusReset: () -> Unit,
    onExposureChange: (Float) -> Unit,
    onIsoChange: (Float) -> Unit,
    onIsoReset: () -> Unit,
    onShutterChange: (Float) -> Unit,
    onShutterReset: () -> Unit,
    onZoomChange: (Float) -> Unit,
    onZoomReset: () -> Unit,
    onSaturationChange: (Float) -> Unit,
    onSaturationReset: () -> Unit,
    onContrastChange: (Float) -> Unit,
    onContrastReset: () -> Unit,
    onWhiteBalanceChange: (Int) -> Unit,
    onWhiteBalanceReset: () -> Unit,
    uiRotationDegrees: Float = 0f,
    onGestureActiveChange: (Boolean) -> Unit = {}
) {
    val alignment = if (side == SliderSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd
    val padding = if (side == SliderSide.LEFT) {
        Modifier.padding(start = 16.dp)
    } else {
        Modifier.padding(end = 16.dp)
    }
    when (assignment) {
        ViewfinderSliderAssignment.FOCUS -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = alignment
        ) {
            Box(modifier = padding) {
                ExpandableVerticalSlider(
                    value = focusValue,
                    valueRange = 0f..1f,
                    onValueChange = onFocusChange,
                    onReset = onFocusReset,
                    activeColor = AccentPistachio,
                    iconType = "AF",
                    uiRotationDegrees = uiRotationDegrees,
                    onGestureActiveChange = onGestureActiveChange
                )
            }
        }
        ViewfinderSliderAssignment.EV -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = alignment
        ) {
            Box(modifier = padding) {
                ExpandableVerticalSlider(
                    value = exposureValue,
                    valueRange = -2f..2f,
                    onValueChange = onExposureChange,
                    onReset = { onExposureChange(0f) },
                    activeColor = Color(0xFFFFD700),
                    iconType = "EV",
                    uiRotationDegrees = uiRotationDegrees,
                    onGestureActiveChange = onGestureActiveChange
                )
            }
        }
        ViewfinderSliderAssignment.WHITE_BALANCE -> if (whiteBalanceSupported) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = alignment) {
                Box(modifier = padding) {
                    ExpandableVerticalSlider(
                        value = kelvinToPerceptualFraction(whiteBalanceKelvin ?: 5200),
                        valueRange = 0f..1f,
                        onValueChange = { onWhiteBalanceChange(perceptualFractionToKelvin(it)) },
                        onReset = onWhiteBalanceReset,
                        activeColor = Color(0xFFFFCC80),
                        iconType = "WB",
                        uiRotationDegrees = uiRotationDegrees,
                        onGestureActiveChange = onGestureActiveChange
                    )
                }
            }
        }
        ViewfinderSliderAssignment.SATURATION -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = alignment
        ) {
            Box(modifier = padding) {
                ExpandableVerticalSlider(
                    value = saturationValue,
                    valueRange = -1f..1f,
                    onValueChange = onSaturationChange,
                    onReset = onSaturationReset,
                    activeColor = Color(0xFFFFAB91),
                    iconType = "SAT",
                    uiRotationDegrees = uiRotationDegrees,
                    onGestureActiveChange = onGestureActiveChange
                )
            }
        }
        ViewfinderSliderAssignment.CONTRAST -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = alignment
        ) {
            Box(modifier = padding) {
                ExpandableVerticalSlider(
                    value = contrastValue,
                    valueRange = -1f..1f,
                    onValueChange = onContrastChange,
                    onReset = onContrastReset,
                    activeColor = Color(0xFFCE93D8),
                    iconType = "C",
                    uiRotationDegrees = uiRotationDegrees,
                    onGestureActiveChange = onGestureActiveChange
                )
            }
        }
        else -> Unit
    }
}

@Composable
fun FocusOverlay(
    showData: Boolean,
    showSlider: Boolean,
    showRing: Boolean,
    tapPoint: Offset?,
    showPassiveRing: Boolean,
    focusDistance: Float,
    liveFocusDiopters: Float,
    onDistanceChange: (Float) -> Unit,
    onReset: () -> Unit,
    onRingAnimationEnd: () -> Unit,
    onPassiveRingEnd: () -> Unit,
    uiRotationDegrees: Float = 0f
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // 1. HANDMATIGE TAP RING
        if (showRing && tapPoint != null) {
            val scale = remember { Animatable(1.5f) }
            val alpha = remember { Animatable(0f) }

            LaunchedEffect(tapPoint) {
                scale.snapTo(1.5f)
                alpha.snapTo(0.8f)
                launch { scale.animateTo(1f, animationSpec = tween(300)) }
                delay(600)
                // Ring verdwijnt volledig naar 0f (zoals je vroeg), maar de lock blijft op de achtergrond actief!
                alpha.animateTo(0f, animationSpec = tween(400))
                onRingAnimationEnd()
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                if (alpha.value > 0f) {
                    drawCircle(
                        color = AccentPistachio.copy(alpha = alpha.value),
                        radius = 24.dp.toPx() * scale.value,
                        center = tapPoint,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }

        // 2. PASSIEVE AUTO-FOCUS RING
        if (showRing && showPassiveRing && tapPoint == null) {
            val scale = remember { Animatable(1.5f) }
            val alpha = remember { Animatable(0f) }

            LaunchedEffect(showPassiveRing) {
                scale.snapTo(1.5f)
                alpha.snapTo(0.6f)
                launch { scale.animateTo(1f, animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
                delay(1000)
                alpha.animateTo(0f, animationSpec = tween(400))
                onPassiveRingEnd()
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                if (alpha.value > 0f) {
                    val centerPt = Offset(size.width / 2, size.height / 2)
                    drawCircle(
                        color = AccentPistachio.copy(alpha = alpha.value),
                        radius = 32.dp.toPx() * scale.value,
                        center = centerPt,
                        style = Stroke(width = 1.0.dp.toPx())
                    )
                }
            }
        }

        // 3. FOCUS DATA — intentionally compact: focus distance only.
        if (showData) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            ) {
                val distanceText = if (liveFocusDiopters <= 0.05f) {
                    "∞"
                } else {
                    String.format(Locale.US, "%.2fm", 1f / liveFocusDiopters)
                }

                Text(
                    text = "DIST $distanceText",
                    color = AccentPistachio,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .rotate(uiRotationDegrees)
                )
            }
        }

        // 4. MANUAL FOCUS SLIDER
        if (showSlider) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            ) {
                ExpandableVerticalSlider(
                    value = focusDistance,
                    valueRange = 0f..1f,
                    onValueChange = onDistanceChange,
                    onReset = onReset,
                    activeColor = AccentPistachio,
                    iconType = "AF"
                )
            }
        }
    }
}

// ==========================================
// EXPOSURE OVERLAY (Slider & Interactief Histogram)
// ==========================================
@Composable
fun ExposureOverlay(
    showSlider: Boolean,
    showHistogram: Boolean,
    liveHistogram: List<Float>,
    liveRgbHistogram: LiveRgbHistogram,
    exposureValue: Float,
    onExposureChange: (Float) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {

        if (showSlider) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            ) {
                ExpandableVerticalSlider(
                    value = exposureValue,
                    valueRange = -2f..2f,
                    onValueChange = onExposureChange,
                    onReset = {
                        onExposureChange(0f)
                    },
                    activeColor = Color(0xFFFFD700),
                    iconType = "EV"
                )
            }
        }

        if (showHistogram) {
            var isExpanded by remember { mutableStateOf(false) }

            val width by animateDpAsState(targetValue = if (isExpanded) 240.dp else 80.dp, animationSpec = spring(), label = "hist_width")
            val height by animateDpAsState(targetValue = if (isExpanded) 140.dp else 50.dp, animationSpec = spring(), label = "hist_height")
            val bgAlpha by animateFloatAsState(targetValue = if (isExpanded) 0.8f else 0.4f, label = "hist_alpha")

            Surface(
                color = Color.Black.copy(alpha = bgAlpha),
                shape = RoundedCornerShape(if (isExpanded) 16.dp else 8.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp)
                    .size(width = width, height = height)
                    .clip(RoundedCornerShape(if (isExpanded) 16.dp else 8.dp))
                    .clickable { isExpanded = !isExpanded }
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(if (isExpanded) 12.dp else 4.dp)) {
                    val w = size.width
                    val h = size.height
                    val luma = liveRgbHistogram.luma.takeIf { it.size >= 2 }
                        ?: liveHistogram
                    if (luma.isNotEmpty()) {
                        val barWidth = w / luma.size
                        luma.forEachIndexed { index, value ->
                            drawRect(
                                color = Color.White.copy(alpha = 0.16f),
                                topLeft = Offset(x = index * barWidth, y = h - (h * value.coerceIn(0f, 1f))),
                                size = androidx.compose.ui.geometry.Size(
                                    width = (barWidth - 0.5.dp.toPx()).coerceAtLeast(0.5f),
                                    height = h * value.coerceIn(0f, 1f)
                                )
                            )
                        }
                    }

                    fun drawChannel(values: List<Float>, color: Color) {
                        if (values.size < 2) return
                        val step = w / (values.size - 1).coerceAtLeast(1)
                        for (index in 0 until values.lastIndex) {
                            val y0 = h - h * values[index].coerceIn(0f, 1f)
                            val y1 = h - h * values[index + 1].coerceIn(0f, 1f)
                            drawLine(
                                color = color.copy(alpha = 0.92f),
                                start = Offset(index * step, y0),
                                end = Offset((index + 1) * step, y1),
                                strokeWidth = if (isExpanded) 1.6.dp.toPx() else 1.0.dp.toPx()
                            )
                        }
                    }
                    drawChannel(liveRgbHistogram.red, Color.Red)
                    drawChannel(liveRgbHistogram.green, Color.Green)
                    drawChannel(liveRgbHistogram.blue, Color.Blue)
                }
            }
        }
    }
}

@Composable
fun ExpandableVerticalSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onReset: () -> Unit,
    activeColor: Color,
    iconType: String,
    uiRotationDegrees: Float = 0f,
    onGestureActiveChange: (Boolean) -> Unit = {}
) {
    var isDragging by remember { mutableStateOf(false) }
    val trackAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = tween(140),
        label = "track"
    )
    val buttonAlpha by animateFloatAsState(
        targetValue = if (isDragging) 0.18f else 1f,
        animationSpec = tween(120),
        label = "button"
    )

    val trackHeight = 320.dp
    val thumbSize = 44.dp
    val density = LocalDensity.current
    val trackHeightPx = with(density) { trackHeight.toPx() }
    val trackTopPx = with(density) { (thumbSize / 2).toPx() }
    val resetExtensionPx = with(density) { 48.dp.toPx() }
    val resetActivationPx = trackHeightPx + with(density) { 22.dp.toPx() }

    var dragOffsetPx by remember(valueRange.start, valueRange.endInclusive, trackHeightPx) {
        mutableFloatStateOf(
            ViewfinderSliderGeometry.trackOffsetForValue(
                value = value,
                rangeStart = valueRange.start,
                rangeEnd = valueRange.endInclusive,
                trackHeightPx = trackHeightPx
            )
        )
    }

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var hasHapticFired by remember { mutableStateOf(false) }

    fun synchronizeFromValue(newValue: Float) {
        dragOffsetPx = ViewfinderSliderGeometry.trackOffsetForValue(
            value = newValue,
            rangeStart = valueRange.start,
            rangeEnd = valueRange.endInclusive,
            trackHeightPx = trackHeightPx
        )
    }

    fun updateFromPointer(pointerY: Float) {
        dragOffsetPx = ViewfinderSliderGeometry.pointerToTrackOffset(
            pointerY = pointerY,
            trackTopPx = trackTopPx,
            trackHeightPx = trackHeightPx,
            resetExtensionPx = resetExtensionPx
        )

        if (dragOffsetPx >= resetActivationPx && !hasHapticFired) {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            hasHapticFired = true
        } else if (dragOffsetPx < resetActivationPx) {
            hasHapticFired = false
        }

        ViewfinderSliderGeometry.valueForTrackOffset(
            trackOffsetPx = dragOffsetPx,
            rangeStart = valueRange.start,
            rangeEnd = valueRange.endInclusive,
            trackHeightPx = trackHeightPx
        )?.let(onValueChange)
    }

    LaunchedEffect(value, valueRange.start, valueRange.endInclusive, trackHeightPx) {
        if (!isDragging) synchronizeFromValue(value)
    }

    val visualFillFraction = (1f - (dragOffsetPx / trackHeightPx)).coerceIn(0f, 1f)
    // Collapsed controls are fixed to the visual centre on both sides. The thumb only follows the
    // setting value while the user is actively dragging; otherwise different values made the
    // left/right shortcut buttons appear vertically misaligned.
    val thumbYOffsetDp = if (isDragging) {
        with(density) { dragOffsetPx.coerceAtMost(trackHeightPx).toDp() }
    } else {
        (trackHeight + 64.dp) / 2
    }

    Box(
        modifier = Modifier
            .width(thumbSize + 28.dp)
            .height(trackHeight + thumbSize + 64.dp)
            .pointerInput(valueRange.start, valueRange.endInclusive, trackHeightPx) {
                detectVerticalDragGestures(
                    onDragStart = { startOffset ->
                        isDragging = true
                        onGestureActiveChange(true)
                        hasHapticFired = false
                        updateFromPointer(startOffset.y)
                    },
                    onDragEnd = {
                        val resetRequested = dragOffsetPx >= resetActivationPx
                        isDragging = false
                        onGestureActiveChange(false)
                        if (resetRequested) {
                            onReset()
                        } else if (dragOffsetPx > trackHeightPx) {
                            synchronizeFromValue(value)
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        onGestureActiveChange(false)
                        synchronizeFromValue(value)
                    }
                ) { change, _ ->
                    change.consume()
                    // Absolute pointer mapping prevents cumulative drag drift and keeps the visual
                    // indicator under the finger even when a gesture starts away from the thumb.
                    updateFromPointer(change.position.y)
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .padding(top = thumbSize / 2)
                .width(4.dp)
                .height(trackHeight)
                .background(Color.White.copy(alpha = 0.20f * trackAlpha), CircleShape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(visualFillFraction)
                    .align(Alignment.BottomCenter)
                    .background(activeColor.copy(alpha = trackAlpha), CircleShape)
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = isDragging && dragOffsetPx > trackHeightPx,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        if (dragOffsetPx >= resetActivationPx) AccentPistachio.copy(alpha = 0.32f)
                        else Color.White.copy(alpha = 0.16f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "A",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.rotate(uiRotationDegrees)
                )
            }
        }

        Box(
            modifier = Modifier
                .offset(y = thumbYOffsetDp)
                .size(thumbSize)
                .background(Color.DarkGray.copy(alpha = buttonAlpha * 0.60f), CircleShape)
                .border(1.dp, Color.White.copy(alpha = buttonAlpha * 0.40f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize().rotate(uiRotationDegrees)) {
                val centerPt = Offset(size.width / 2, size.height / 2)
                val stroke = 1.5.dp.toPx()

                if (iconType == "AF") {
                    val s = 6.dp.toPx(); val p = 8.dp.toPx()
                    drawLine(activeColor.copy(buttonAlpha), Offset(centerPt.x - p, centerPt.y - s), Offset(centerPt.x - p, centerPt.y + s), stroke)
                    drawLine(activeColor.copy(buttonAlpha), Offset(centerPt.x - p, centerPt.y - s), Offset(centerPt.x - p + s/2, centerPt.y - s), stroke)
                    drawLine(activeColor.copy(buttonAlpha), Offset(centerPt.x - p, centerPt.y + s), Offset(centerPt.x - p + s/2, centerPt.y + s), stroke)
                    drawLine(activeColor.copy(buttonAlpha), Offset(centerPt.x + p, centerPt.y - s), Offset(centerPt.x + p, centerPt.y + s), stroke)
                    drawLine(activeColor.copy(buttonAlpha), Offset(centerPt.x + p, centerPt.y - s), Offset(centerPt.x + p - s/2, centerPt.y - s), stroke)
                    drawLine(activeColor.copy(buttonAlpha), Offset(centerPt.x + p, centerPt.y + s), Offset(centerPt.x + p - s/2, centerPt.y + s), stroke)
                } else if (iconType == "EV") {
                    val gap = 3.dp.toPx(); val length = 5.dp.toPx()
                    drawLine(activeColor.copy(buttonAlpha), Offset(centerPt.x, centerPt.y - gap - length), Offset(centerPt.x, centerPt.y - gap), stroke)
                    drawLine(activeColor.copy(buttonAlpha), Offset(centerPt.x - length/2, centerPt.y - gap - length/2), Offset(centerPt.x + length/2, centerPt.y - gap - length/2), stroke)
                    drawLine(activeColor.copy(buttonAlpha), Offset(centerPt.x - length/2, centerPt.y + gap + length/2), Offset(centerPt.x + length/2, centerPt.y + gap + length/2), stroke)
                }
            }
            if (iconType != "AF" && iconType != "EV") {
                Text(
                    text = iconType,
                    color = activeColor.copy(alpha = buttonAlpha),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.rotate(uiRotationDegrees)
                )
            }
        }
    }
}

@Composable
fun DetectionOverlay(
    showFaces: Boolean,
    faces: Array<Face>,
    sensorRect: Rect,
    sensorOrientation: Int,
    lensFacing: Int,
    previewOrientationCorrectionDegrees: Int,
    showQr: Boolean,
    qrUrl: String?,
    trackedObjectBounds: RectF?,
    trackingState: com.bncam.core.engine.FocusTrackingState,
    priorityFaceBounds: Rect?,
    onOpenUrl: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // 1. GEZICHTSHERKENNING
        if (showFaces && faces.isNotEmpty() && sensorRect.width() > 0) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cropWidth = sensorRect.width().toFloat()
                val cropHeight = sensorRect.height().toFloat()

                faces.forEach { face ->
                    if (face.score >= 50) {
                        val bounds = face.bounds
                        val normX = bounds.centerX() / cropWidth
                        val normY = bounds.centerY() / cropHeight

                        val baseMappedX: Float
                        val baseMappedY: Float

                        if (sensorOrientation == 90) {
                            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                                baseMappedX = (1f - normY) * size.width
                                baseMappedY = normX * size.height
                            } else {
                                baseMappedX = (1f - normY) * size.width
                                baseMappedY = (1f - normX) * size.height
                            }
                        } else if (sensorOrientation == 270) {
                            if (lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                                baseMappedX = normY * size.width
                                baseMappedY = (1f - normX) * size.height
                            } else {
                                baseMappedX = normY * size.width
                                baseMappedY = normX * size.height
                            }
                        } else {
                            baseMappedX = normX * size.width
                            baseMappedY = normY * size.height
                        }

                        val (displayNormX, displayNormY) = rotateNormalizedPointForDisplay(
                            baseMappedX / size.width,
                            baseMappedY / size.height,
                            previewOrientationCorrectionDegrees
                        )
                        val mappedX = displayNormX * size.width
                        val mappedY = displayNormY * size.height

                        val faceSizeOnScreen = maxOf(bounds.width() / cropWidth, bounds.height() / cropHeight) * maxOf(size.width, size.height)
                        val selectedForFocus = priorityFaceBounds?.let { selected ->
                            val dx = kotlin.math.abs(selected.centerX() - bounds.centerX())
                            val dy = kotlin.math.abs(selected.centerY() - bounds.centerY())
                            dx <= maxOf(4, selected.width() / 12) && dy <= maxOf(4, selected.height() / 12)
                        } == true
                        drawCircle(
                            color = AccentPistachio,
                            radius = (faceSizeOnScreen / 2f) * 1.2f,
                            center = Offset(mappedX, mappedY),
                            style = Stroke(width = (if (selectedForFocus) 1.4.dp else 0.5.dp).toPx())
                        )
                    }
                }
            }
        }

        // 2. FOCUS TRACK — draw the tracked subject bounds themselves rather than a second
        // generic AF ring. The same bounds drive the moving Camera2 AF region.
        if (trackedObjectBounds != null) {
            val animLeft by animateFloatAsState(
                targetValue = trackedObjectBounds.left,
                animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = 0.92f),
                label = "trackLeft"
            )
            val animTop by animateFloatAsState(
                targetValue = trackedObjectBounds.top,
                animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = 0.92f),
                label = "trackTop"
            )
            val animRight by animateFloatAsState(
                targetValue = trackedObjectBounds.right,
                animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = 0.92f),
                label = "trackRight"
            )
            val animBottom by animateFloatAsState(
                targetValue = trackedObjectBounds.bottom,
                animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = 0.92f),
                label = "trackBottom"
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val corners = listOf(
                    rotateNormalizedPointForDisplay(animLeft, animTop, previewOrientationCorrectionDegrees),
                    rotateNormalizedPointForDisplay(animRight, animTop, previewOrientationCorrectionDegrees),
                    rotateNormalizedPointForDisplay(animRight, animBottom, previewOrientationCorrectionDegrees),
                    rotateNormalizedPointForDisplay(animLeft, animBottom, previewOrientationCorrectionDegrees)
                )
                val left = corners.minOf { it.first } * size.width
                val top = corners.minOf { it.second } * size.height
                val right = corners.maxOf { it.first } * size.width
                val bottom = corners.maxOf { it.second } * size.height
                val safeWidth = (right - left).coerceAtLeast(24.dp.toPx())
                val safeHeight = (bottom - top).coerceAtLeast(24.dp.toPx())
                val corner = min(safeWidth, safeHeight) * 0.16f
                val trackColor = when (trackingState.phase) {
                    com.bncam.core.engine.FocusTrackingPhase.TRACKING -> AccentPistachio.copy(alpha = 0.96f)
                    com.bncam.core.engine.FocusTrackingPhase.REACQUIRING -> Color(0xFFFFC857).copy(alpha = 0.94f)
                    com.bncam.core.engine.FocusTrackingPhase.LOST -> Color(0xFFFF8A80).copy(alpha = 0.88f)
                    else -> Color.White.copy(alpha = 0.92f)
                }
                val trackingPathEffect = when (trackingState.phase) {
                    com.bncam.core.engine.FocusTrackingPhase.REACQUIRING,
                    com.bncam.core.engine.FocusTrackingPhase.LOST -> androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(8.dp.toPx(), 6.dp.toPx())
                    )
                    else -> null
                }

                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.26f),
                    topLeft = Offset(left - 1.5.dp.toPx(), top - 1.5.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        safeWidth + 3.dp.toPx(),
                        safeHeight + 3.dp.toPx()
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                    style = Stroke(width = 3.dp.toPx())
                )
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(safeWidth, safeHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
                    style = Stroke(width = 1.2.dp.toPx(), pathEffect = trackingPathEffect)
                )
                drawCircle(
                    color = trackColor,
                    radius = 2.3.dp.toPx(),
                    center = Offset(left + safeWidth / 2f, top + safeHeight / 2f)
                )
            }
        }

        // 3. QR CODE CHIP
        if (showQr && qrUrl != null) {
            Surface(
                color = AccentPistachio, shape = RoundedCornerShape(24.dp), modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp).clickable { onOpenUrl(qrUrl) }
            ) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Link", tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Open Link", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

// ==========================================
// GENERIEKE STATUS OVERLAY COMPONENT
// ==========================================
data class OverlayMessage(
    val title: String,
    val value: String,
    val subText: String? = null, // NIEUW: Optionele subtitel
    val id: Long = System.currentTimeMillis()
)

@Composable
fun ActionStatusOverlay(
    message: OverlayMessage?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = message != null,
        enter = androidx.compose.animation.fadeIn(animationSpec = tween(750)) + androidx.compose.animation.scaleIn(initialScale = 0.9f, animationSpec = tween(750)),
        exit = androidx.compose.animation.fadeOut(animationSpec = tween(1000)),
        modifier = modifier
    ) {
        if (message != null) {
            LaunchedEffect(message.id) {
                delay(1500)
                onDismiss()
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(24.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 40.dp, vertical = 24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = message.title,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.value,
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light
                    )
                    // NIEUW: Render de subtitel als deze is meegegeven
                    if (message.subText != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message.subText,
                            color = AccentPistachio.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Light,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
