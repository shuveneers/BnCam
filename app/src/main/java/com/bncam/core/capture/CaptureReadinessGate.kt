package com.bncam.core.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import com.bncam.core.buffer.FrameRingBuffer

enum class ReadinessState {
    COLD_EMPTY,
    FILLING,
    METADATA_UNSTABLE,
    AF_SCANNING,
    AE_AWB_CONVERGING,
    READY_DEGRADED,
    READY
}

object CaptureReadinessGate {
    // Testing Backdoor
    var testAfStateOverride: Int? = null
    var testAeStateOverride: Int? = null
    var testAwbStateOverride: Int? = null
    var testLensStateOverride: Int? = null
    var testTimestampOverride: Long? = null
    var testExposureOverride: Long? = null
    var testSensitivityOverride: Int? = null

    fun determineState(
        ringBuffer: FrameRingBuffer,
        characteristics: CameraCharacteristics?,
        latestResult: CaptureResult?,
        activeFormat: Int,
        requirement: WarmBufferReadinessRequirement =
            WarmBufferReadinessPolicy.streamHealth(activeFormat)
    ): ReadinessState {
        val totalCount = ringBuffer.completeFrameCount()
        if (totalCount == 0) {
            return ReadinessState.COLD_EMPTY
        }

        if (totalCount < requirement.requiredCompleteFrames) {
            return ReadinessState.FILLING
        }

        // A still-capture shutter must never be held hostage by transient 3A result states.
        // The warm ring already owns exact per-frame metadata and downstream selection/alignment
        // ranks or rejects weak supports. Keep strict 3A readiness for stream qualification, but
        // allow every capture route (single and multi) to proceed in degraded-ready mode.
        val latencyFirstCaptureRoute = requirement.purpose.startsWith("CAPTURE_ROUTE_")

        if (latestResult == null && testTimestampOverride == null) {
            // A completed ring pair already owns its capture metadata. For a single-frame route,
            // `lastCaptureResult` may lag that pair briefly at startup; the downstream freshness
            // gate validates the actual ring metadata before capture proceeds.
            return if (latencyFirstCaptureRoute) {
                ReadinessState.READY_DEGRADED
            } else {
                ReadinessState.METADATA_UNSTABLE
            }
        }

        // Validate essential metadata fields
        val timestamp = testTimestampOverride ?: try { latestResult?.get(CaptureResult.SENSOR_TIMESTAMP) } catch (_: Throwable) { null }
        val exposure = testExposureOverride ?: try { latestResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) } catch (_: Throwable) { null }
        val sensitivity = testSensitivityOverride ?: try { latestResult?.get(CaptureResult.SENSOR_SENSITIVITY) } catch (_: Throwable) { null }

        if (timestamp == null || timestamp <= 0L || exposure == null || exposure <= 0L || sensitivity == null || sensitivity <= 0) {
            return if (latencyFirstCaptureRoute) {
                ReadinessState.READY_DEGRADED
            } else {
                ReadinessState.METADATA_UNSTABLE
            }
        }

        // Check AF scan state and lens motion
        val afState = testAfStateOverride ?: try { latestResult?.get(CaptureResult.CONTROL_AF_STATE) } catch (_: Throwable) { null }
        val lensState = testLensStateOverride ?: try { latestResult?.get(CaptureResult.LENS_STATE) } catch (_: Throwable) { null }

        val isScanning = afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN ||
                afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN ||
                lensState == 1 // CameraMetadata.LENS_STATE_MOVING

        if (isScanning) {
            return if (latencyFirstCaptureRoute) {
                ReadinessState.READY_DEGRADED
            } else {
                ReadinessState.AF_SCANNING
            }
        }

        // Check AE and AWB states
        val aeState = testAeStateOverride ?: try { latestResult?.get(CaptureResult.CONTROL_AE_STATE) } catch (_: Throwable) { null }
        val awbState = testAwbStateOverride ?: try { latestResult?.get(CaptureResult.CONTROL_AWB_STATE) } catch (_: Throwable) { null }

        val isAeAwbSearching = aeState == CaptureResult.CONTROL_AE_STATE_SEARCHING ||
                aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                awbState == CaptureResult.CONTROL_AWB_STATE_SEARCHING

        if (isAeAwbSearching) {
            return if (latencyFirstCaptureRoute) {
                ReadinessState.READY_DEGRADED
            } else {
                ReadinessState.AE_AWB_CONVERGING
            }
        }

        val isAwbReady = awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
                awbState == CaptureResult.CONTROL_AWB_STATE_LOCKED ||
                awbState == -1 || awbState == null
        val isAeReady = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                aeState == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                aeState == -1 || aeState == null
        val isAfReady = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                afState == CaptureResult.CONTROL_AF_STATE_INACTIVE ||
                afState == -1 || afState == null

        if (isAwbReady && isAeReady && isAfReady) {
            return ReadinessState.READY
        }

        return ReadinessState.READY_DEGRADED
    }

    fun resetOverrides() {
        testAfStateOverride = null
        testAeStateOverride = null
        testAwbStateOverride = null
        testLensStateOverride = null
        testTimestampOverride = null
        testExposureOverride = null
        testSensitivityOverride = null
    }
}
