package com.bncam.core.alignment

/**
 * Immutable alignment result returned by any multi-frame alignment backend.
 * Provides displacement, rotation, confidence metrics, and optional local motion vector fields.
 */
data class AlignmentResult(
    val backendId: String,
    val success: Boolean,
    val transformType: String,
    val horizontalDisplacement: Float,
    val verticalDisplacement: Float,
    val rotationDegrees: Float = 0.0f,
    val globalConfidence: Float,
    val validOverlapPercentage: Float,
    val residualAlignmentError: Float,
    val localMotionField: FloatArray? = null,
    val tileConfidenceValues: FloatArray? = null,
    val rejectionReason: String = "none",
    val processingTimeMs: Long = 0L
) {
    fun isAccepted(maxShiftPixels: Int = 150): Boolean {
        if (!success) return false
        if (globalConfidence < 0.30f) return false
        if (validOverlapPercentage < 50.0f) return false
        if (kotlin.math.abs(horizontalDisplacement) > maxShiftPixels ||
            kotlin.math.abs(verticalDisplacement) > maxShiftPixels) return false
        return true
    }
}
