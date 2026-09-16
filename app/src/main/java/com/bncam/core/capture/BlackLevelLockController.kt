package com.bncam.core.capture

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BlackLevelLockApplyState(
    val lensId: String,
    val stage: BlackLevelLockRequestStage,
    val captureIntent: Int?,
    val requestState: BlackLevelLockRequestState,
    val reason: String
)

data class BlackLevelLockFrameState(
    val lensId: String,
    val sensorTimestampNs: Long?,
    val captureIntent: Int?,
    val submissionType: String?,
    val requestState: BlackLevelLockRequestState,
    val resultState: BlackLevelLockResultState,
    val coverage: BlackLevelLockFrameCoverage,
    val actuallyLocked: Boolean,
    val dynamicBlackLevel: List<Float>?,
    val reason: String
) {
    fun summary(): String = buildString {
        append("request=").append(requestState)
        append(";result=").append(resultState)
        append(";coverage=").append(coverage)
        append(";actuallyLocked=").append(actuallyLocked)
        append(";submission=").append(submissionType ?: "UNKNOWN")
        append(";captureIntent=").append(captureIntent ?: "UNAVAILABLE")
        append(";sensorTimestampNs=").append(sensorTimestampNs ?: "UNAVAILABLE")
        append(";dynamicBlack=").append(dynamicBlackLevel ?: "UNAVAILABLE")
        append(";reason=").append(reason)
    }
}

/**
 * Standard Camera2 BLACK_LEVEL_LOCK owner.
 *
 * The controller intentionally uses the existing request-construction hook but is not a vendor-tag
 * feature. It requests lock only for still/ZSL-producing builders. Ordinary preview repeating
 * requests stay untouched. Result truth is evaluated per TotalCaptureResult so pre-shutter warm
 * frames are covered only when their own original repeating request contained the lock request.
 */
object BlackLevelLockController {
    private const val FRAME_HISTORY_LIMIT = 256

    private val lastApplyByLens = ConcurrentHashMap<String, BlackLevelLockApplyState>()
    private val _latestFrameByLens = MutableStateFlow<Map<String, BlackLevelLockFrameState>>(emptyMap())
    val latestFrameByLens: StateFlow<Map<String, BlackLevelLockFrameState>> =
        _latestFrameByLens.asStateFlow()

    private val frameHistoryLock = Any()
    private val frameHistory = LinkedHashMap<String, BlackLevelLockFrameState>(FRAME_HISTORY_LIMIT)

    fun latest(lensId: String): BlackLevelLockFrameState? = _latestFrameByLens.value[lensId]

    fun latestApply(lensId: String): BlackLevelLockApplyState? = lastApplyByLens[lensId]

    /**
     * Called from the already-centralized request-builder hook. Builder validity is the immediate
     * capability gate: unsupported optional keys throw IllegalArgumentException on get/set.
     * BL5 can additionally expose CameraCharacteristics request/result-key capability telemetry.
     */
    fun applyToBuilder(
        lensId: String,
        builder: CaptureRequest.Builder,
        stage: BlackLevelLockRequestStage
    ): BlackLevelLockApplyState {
        val captureIntent = runCatching {
            builder.get(CaptureRequest.CONTROL_CAPTURE_INTENT)
        }.getOrNull()
        val shouldRequest = BlackLevelLockPolicy.shouldRequest(
            stage = stage,
            repeatingIntentIsStillOrZsl =
                captureIntent == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE ||
                    captureIntent == CaptureRequest.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG
        )
        if (!shouldRequest) {
            return recordApply(
                BlackLevelLockApplyState(
                    lensId = lensId,
                    stage = stage,
                    captureIntent = captureIntent,
                    requestState = BlackLevelLockRequestState.NOT_REQUESTED,
                    reason = "stage/capture intent is not a still or near-ZSL producer"
                )
            )
        }

        val keyReadable = try {
            builder.get(CaptureRequest.BLACK_LEVEL_LOCK)
            true
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: Throwable) {
            false
        }
        if (!keyReadable) {
            return recordApply(
                BlackLevelLockApplyState(
                    lensId = lensId,
                    stage = stage,
                    captureIntent = captureIntent,
                    requestState = BlackLevelLockRequestState.UNSUPPORTED,
                    reason = "CaptureRequest.BLACK_LEVEL_LOCK is unavailable on this builder"
                )
            )
        }

        return try {
            builder.set(CaptureRequest.BLACK_LEVEL_LOCK, true)
            val readback = runCatching { builder.get(CaptureRequest.BLACK_LEVEL_LOCK) }.getOrNull()
            if (readback == true) {
                recordApply(
                    BlackLevelLockApplyState(
                        lensId = lensId,
                        stage = stage,
                        captureIntent = captureIntent,
                        requestState = BlackLevelLockRequestState.REQUESTED,
                        reason = "BLACK_LEVEL_LOCK=true written to Camera2 request builder"
                    )
                )
            } else {
                recordApply(
                    BlackLevelLockApplyState(
                        lensId = lensId,
                        stage = stage,
                        captureIntent = captureIntent,
                        requestState = BlackLevelLockRequestState.APPLY_FAILED,
                        reason = "BLACK_LEVEL_LOCK write did not survive builder readback"
                    )
                )
            }
        } catch (_: IllegalArgumentException) {
            recordApply(
                BlackLevelLockApplyState(
                    lensId = lensId,
                    stage = stage,
                    captureIntent = captureIntent,
                    requestState = BlackLevelLockRequestState.UNSUPPORTED,
                    reason = "CaptureRequest.BLACK_LEVEL_LOCK rejected by Camera2 builder"
                )
            )
        } catch (failure: Throwable) {
            recordApply(
                BlackLevelLockApplyState(
                    lensId = lensId,
                    stage = stage,
                    captureIntent = captureIntent,
                    requestState = BlackLevelLockRequestState.APPLY_FAILED,
                    reason = "BLACK_LEVEL_LOCK apply failed: ${failure.javaClass.simpleName}"
                )
            )
        }
    }

    /** Resolve exact request/result truth without relying on global latest state. */
    fun stateForResult(lensId: String, result: TotalCaptureResult): BlackLevelLockFrameState {
        val request = result.request
        val requestValue = try {
            request.get(CaptureRequest.BLACK_LEVEL_LOCK)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: Throwable) {
            null
        }
        // Exact-frame truth must never inherit a later/global apply state. A null/missing key in
        // this immutable request means the lock was not requested for this frame. Capability and
        // builder-apply diagnostics remain available separately through latestApply(lensId).
        val requestState = if (requestValue == true) {
            BlackLevelLockRequestState.REQUESTED
        } else {
            BlackLevelLockRequestState.NOT_REQUESTED
        }
        val reported = runCatching { result.get(CaptureResult.BLACK_LEVEL_LOCK) }.getOrNull()
        val tag = request.tag as? CameraRequestTag
        val submissionType = tag?.snapshot?.submissionType?.name
        val captureIntent = runCatching {
            request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)
        }.getOrNull()
        val resolution = BlackLevelLockPolicy.resolve(
            requestState = requestState,
            resultReportedLocked = reported,
            submissionType = submissionType
        )
        val dynamicBlack = runCatching {
            result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                ?.takeIf { it.size >= 4 }
                ?.take(4)
        }.getOrNull()
        return BlackLevelLockFrameState(
            lensId = lensId,
            sensorTimestampNs = runCatching { result.get(CaptureResult.SENSOR_TIMESTAMP) }.getOrNull(),
            captureIntent = captureIntent,
            submissionType = submissionType,
            requestState = resolution.requestState,
            resultState = resolution.resultState,
            coverage = resolution.coverage,
            actuallyLocked = resolution.actuallyLocked,
            dynamicBlackLevel = dynamicBlack,
            reason = resolution.reason
        )
    }

    fun observeResult(lensId: String, result: TotalCaptureResult): BlackLevelLockFrameState {
        val state = stateForResult(lensId, result)
        _latestFrameByLens.value = _latestFrameByLens.value + (lensId to state)
        val historyKey = "$lensId:${state.sensorTimestampNs ?: result.frameNumber}"
        synchronized(frameHistoryLock) {
            frameHistory[historyKey] = state
            while (frameHistory.size > FRAME_HISTORY_LIMIT) {
                val first = frameHistory.entries.iterator()
                if (!first.hasNext()) break
                first.next()
                first.remove()
            }
        }
        return state
    }

    fun stateForTimestamp(lensId: String, sensorTimestampNs: Long): BlackLevelLockFrameState? =
        synchronized(frameHistoryLock) { frameHistory["$lensId:$sensorTimestampNs"] }

    fun clear(lensId: String) {
        lastApplyByLens.remove(lensId)
        _latestFrameByLens.value = _latestFrameByLens.value - lensId
        synchronized(frameHistoryLock) {
            frameHistory.keys.removeAll { it.startsWith("$lensId:") }
        }
    }

    private fun recordApply(state: BlackLevelLockApplyState): BlackLevelLockApplyState {
        lastApplyByLens[state.lensId] = state
        return state
    }
}
