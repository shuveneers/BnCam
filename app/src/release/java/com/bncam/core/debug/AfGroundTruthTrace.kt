package com.bncam.core.debug

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Release no-op: the ground-truth tracer, control action, file, and overlay are debug-only. */
object AfGroundTruthTrace {
    private val emptyOverlay = MutableStateFlow(AfGroundTruthOverlayState())
    val overlay: StateFlow<AfGroundTruthOverlayState> = emptyOverlay

    fun initialize(context: Context) = Unit
    fun clear(context: Context) = Unit
    fun setNextTransactionId(transactionId: String) = Unit
    fun recordCameraRoute(
        stage: String,
        selectedCameraId: String,
        directOpenResult: String,
        chosenRoute: String,
        logicalCameraId: String,
        physicalChildCameraId: String?,
        openedCameraDeviceId: String?,
        detail: String? = null
    ) = Unit
    fun beginTap(tap: AfGroundTruthUiTap): String = "RELEASE_NO_TRACE"
    fun recordTapMapping(
        mapping: AfGroundTruthMappingContext,
        selectedLensId: String,
        openedCameraDeviceId: String,
        logicalCameraId: String,
        activePhysicalCameraId: String?,
        geometryCameraId: String,
        requestedPhysicalCameraId: String?,
        previewOutputPhysicalCameraId: String?,
        imageReaderOutputPhysicalCameraId: String?,
        standalonePhysicalCameraListed: Boolean?,
        activePipeline: Map<String, Any?>,
        logicalCharacteristics: CameraCharacteristics,
        physicalCharacteristics: CameraCharacteristics?
    ) = Unit
    fun recordWriter(builder: CaptureRequest.Builder, sourceFunction: String, reason: String) = Unit
    fun recordSubmission(
        builder: CaptureRequest.Builder,
        request: CaptureRequest,
        requestSequenceNumber: Int,
        submissionType: String,
        reason: String
    ) = Unit
    fun recordCaptureResult(
        logicalCameraId: String,
        activePhysicalCameraId: String?,
        request: CaptureRequest,
        result: TotalCaptureResult
    ) = Unit
}
