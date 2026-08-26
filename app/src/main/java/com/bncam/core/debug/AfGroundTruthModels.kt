package com.bncam.core.debug

import android.graphics.Rect

/** Immutable input facts captured by Compose before the tap reaches the camera worker. */
data class AfGroundTruthUiTap(
    val rawTouchX: Float,
    val rawTouchY: Float,
    val viewWidth: Int,
    val viewHeight: Int,
    val displayNormalizedX: Float,
    val displayNormalizedY: Float,
    val mapperInputNormalizedX: Float,
    val mapperInputNormalizedY: Float,
    val displayRotationDegrees: Int,
    val previewOrientationCorrectionDegrees: Int,
    val previewMirrored: Boolean,
    val inputSource: String = "COMPOSE"
)

/** Exact geometry needed to invert the transform used by the production touch-focus mapper. */
data class AfGroundTruthMappingContext(
    val transactionId: String,
    val uiTap: AfGroundTruthUiTap,
    val mappingDisplayRotationDegrees: Int,
    val sensorOrientationDegrees: Int,
    val lensFacingFront: Boolean,
    val previewSurfaceWidth: Int,
    val previewSurfaceHeight: Int,
    val analysisBufferWidth: Int,
    val analysisBufferHeight: Int,
    val sensorVisibleBounds: Rect,
    val mapperSensorNormalizedX: Float,
    val mapperSensorNormalizedY: Float,
    val requestedCropRegion: Rect?,
    val requestedZoomRatio: Float?
)

/** DEBUG overlay coordinates are in the same Compose view-pixel domain as [AfGroundTruthUiTap]. */
data class AfGroundTruthOverlayState(
    val visible: Boolean = false,
    val transactionId: String = "NO_TRANSACTION",
    val touchX: Float = 0f,
    val touchY: Float = 0f,
    val regionLeft: Float = 0f,
    val regionTop: Float = 0f,
    val regionRight: Float = 0f,
    val regionBottom: Float = 0f,
    val regionCenterX: Float = 0f,
    val regionCenterY: Float = 0f,
    val requestSequenceNumber: Int = -1,
    val submissionReason: String = "none"
)
