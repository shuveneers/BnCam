package com.bncam.ui.screens.capture

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.bncam.R
import com.bncam.core.debug.Phase0PerformanceTrace
import com.bncam.core.output.CaptureWorkSnapshot
import com.bncam.core.output.CaptureWorkState
import kotlinx.coroutines.delay

internal data class CaptureFeedbackModel(
    val busy: Boolean,
    val statusLabel: String?,
    val failed: Boolean
)

internal fun captureFeedbackModel(snapshot: CaptureWorkSnapshot?): CaptureFeedbackModel = when (snapshot?.state) {
    CaptureWorkState.QUEUED -> CaptureFeedbackModel(true, "Queued", false)
    CaptureWorkState.PROCESSING -> CaptureFeedbackModel(true, "Processing", false)
    CaptureWorkState.SAVING -> CaptureFeedbackModel(true, "Saving", false)
    CaptureWorkState.FAILED -> CaptureFeedbackModel(false, "Failed", true)
    CaptureWorkState.PUBLISHED, null -> CaptureFeedbackModel(false, null, false)
}

@Composable
internal fun CaptureThumbnailFeedback(
    latestSnapshot: CaptureWorkSnapshot?,
    publishedModel: Any?,
    publishedCaptureStartedNs: Long,
    immediateShutterPreviewPath: String?,
    immediateShutterStartedNs: Long,
    uiRotationDegrees: Float,
    onOpenPublished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val feedback = captureFeedbackModel(latestSnapshot)
    val thumbnailModel = resolveCaptureThumbnailModel(
        latestSnapshot = latestSnapshot,
        publishedModel = publishedModel,
        publishedCaptureStartedNs = publishedCaptureStartedNs,
        immediateShutterPreviewPath = immediateShutterPreviewPath,
        immediateShutterStartedNs = immediateShutterStartedNs
    )

    var pulseTarget by remember { mutableFloatStateOf(1f) }
    var lastAnimatedPublishedSequence by remember {
        mutableLongStateOf(
            latestSnapshot?.takeIf { it.state == CaptureWorkState.PUBLISHED }?.shotSequenceId ?: -1L
        )
    }
    LaunchedEffect(latestSnapshot?.shotSequenceId, latestSnapshot?.state) {
        val current = latestSnapshot
        if (current?.state == CaptureWorkState.PUBLISHED &&
            current.shotSequenceId != lastAnimatedPublishedSequence
        ) {
            lastAnimatedPublishedSequence = current.shotSequenceId
            pulseTarget = 1.16f
            delay(110)
            pulseTarget = 1f
        }
    }
    val thumbnailScale by animateFloatAsState(
        targetValue = pulseTarget,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "publishedThumbnailPulse"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (feedback.busy) 2.dp else 0.5.dp,
        animationSpec = tween(120),
        label = "captureStateBorder"
    )

    Box(
        modifier = modifier
            .size(72.dp)
            .scale(thumbnailScale)
            .rotate(uiRotationDegrees),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = Color.DarkGray,
            modifier = Modifier
                .fillMaxSize()
                .border(
                    borderWidth,
                    when {
                        feedback.failed -> Color(0xFFFF7A7A)
                        feedback.busy -> AccentPistachio
                        else -> AccentPistachio.copy(alpha = 0.5f)
                    },
                    CircleShape
                )
                .clip(CircleShape)
                .clickable(enabled = publishedModel != null, onClick = onOpenPublished)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = thumbnailModel,
                    contentDescription = "Latest capture",
                    onSuccess = {
                        latestSnapshot?.let { snapshot ->
                            Phase0PerformanceTrace.thumbnailUiPresented(
                                workId = snapshot.workId,
                                modelKind = when {
                                    immediateShutterPreviewPath != null &&
                                        thumbnailModel == immediateShutterPreviewPath ->
                                        "TEMPORARY_VIEWFINDER_JPEG"
                                    publishedCaptureStartedNs == snapshot.captureStartedNs &&
                                        snapshot.jpegUri == null && snapshot.dngUri != null &&
                                        thumbnailModel == publishedModel ->
                                        "DNG_DERIVED_THUMBNAIL_JPEG"
                                    snapshot.thumbnailUri != null -> "PUBLISHED_THUMBNAIL_URI"
                                    snapshot.publishedUri != null -> "PUBLISHED_OUTPUT_URI"
                                    else -> "FALLBACK_MODEL"
                                }
                            )
                        }
                    },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (feedback.busy) {
                    CaptureBusyArc()
                }
            }
        }

        feedback.statusLabel?.let { label ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.78f),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Text(
                    text = label,
                    color = if (feedback.failed) Color(0xFFFF9A9A) else Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    maxLines = 1
                )
            }
        }
    }
}

internal fun resolveCaptureThumbnailModel(
    latestSnapshot: CaptureWorkSnapshot?,
    publishedModel: Any?,
    publishedCaptureStartedNs: Long,
    immediateShutterPreviewPath: String?,
    immediateShutterStartedNs: Long
): Any {
    val snapshotStartedNs = latestSnapshot?.captureStartedNs ?: Long.MIN_VALUE
    val publishedJpeg = latestSnapshot
        ?.takeIf { it.state == CaptureWorkState.PUBLISHED }
        ?.let { it.jpegUri ?: it.thumbnailUri }

    return when {
        // The shutter preview for a newly pressed shot outranks every older completed job.
        immediateShutterPreviewPath != null && immediateShutterStartedNs > snapshotStartedNs ->
            immediateShutterPreviewPath
        // Once processing for this shot publishes JPEG, it always owns the final thumbnail.
        !publishedJpeg.isNullOrBlank() -> publishedJpeg
        // DNG-only publication installs a bitmap decoded from that DNG asynchronously.
        latestSnapshot?.state == CaptureWorkState.PUBLISHED &&
            publishedCaptureStartedNs == snapshotStartedNs && publishedModel != null -> publishedModel
        // Keep the exact shutter frame visible while this same shot is being processed/published.
        immediateShutterPreviewPath != null && immediateShutterStartedNs == snapshotStartedNs ->
            immediateShutterPreviewPath
        latestSnapshot?.temporaryPreviewPath != null -> latestSnapshot.temporaryPreviewPath
        publishedModel != null -> publishedModel
        else -> R.mipmap.ic_launcher
    }
}

@Composable
private fun CaptureBusyArc() {
    val transition = rememberInfiniteTransition(label = "captureBusyArc")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 760, easing = LinearEasing)),
        label = "captureBusyRotation"
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawArc(
            color = Color.Black.copy(alpha = 0.38f),
            startAngle = rotation,
            sweepAngle = 92f,
            useCenter = false,
            style = Stroke(width = 6.dp.toPx())
        )
    }
}
