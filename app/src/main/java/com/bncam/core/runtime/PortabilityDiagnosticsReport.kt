package com.bncam.core.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import com.bncam.core.debug.DiagnosticsAggregator

object PortabilityDiagnosticsReport {
    private const val TAG = "PortabilityReport"
    fun generateReport(
        context: Context,
        profile: RawPipelineRuntimeProfile?,
        state: RawPipelineRuntimeState
    ): Boolean {
        DiagnosticsAggregator.initialize(context.applicationContext)
        try {
            val sb = StringBuilder()
            sb.appendLine("==================================================")
            sb.appendLine("BNCAM RAW PIPELINE RUNTIME PROFILE REPORT")
            sb.appendLine("==================================================")
            sb.appendLine()

            sb.appendLine("--- DEVICE INFORMATION ---")
            sb.appendLine("Manufacturer: ${Build.MANUFACTURER}")
            sb.appendLine("Model: ${Build.MODEL} (${Build.DEVICE})")
            sb.appendLine("Hardware: ${Build.HARDWARE}")
            sb.appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            sb.appendLine("Fingerprint: ${Build.FINGERPRINT}")
            sb.appendLine()

            if (profile == null) {
                sb.appendLine("--- STATUS ---")
                sb.appendLine("No active RAW pipeline session initialized.")
                DiagnosticsAggregator.record(
                    stream = DiagnosticsAggregator.Stream.CAMERA,
                    scope = "SESSION",
                    section = "RAW PIPELINE RUNTIME PROFILE",
                    content = sb.toString()
                )
                return true
            }

            sb.appendLine("--- CAMERA IDENTITY ---")
            sb.appendLine("Session ID: ${profile.sessionId}")
            sb.appendLine("Logical Camera ID: ${profile.logicalCameraId}")
            sb.appendLine("Physical Camera ID: ${profile.physicalCameraId}")
            sb.appendLine("Session Generation: ${profile.sessionGeneration}")
            sb.appendLine()

            sb.appendLine("--- RAW STREAM ---")
            sb.appendLine("Format: ${profile.format} (${if (profile.format == android.graphics.ImageFormat.RAW10) "RAW10" else if (profile.format == android.graphics.ImageFormat.RAW_SENSOR) "RAW_SENSOR" else "YUV"})")
            sb.appendLine("Capture Resolution: ${profile.capabilities.captureSize.width}x${profile.capabilities.captureSize.height}")
            sb.appendLine("Row Stride: ${profile.bufferLayout.rowStrideBytes} bytes")
            sb.appendLine("Pixel Stride: ${profile.bufferLayout.pixelStrideBytes} bytes")
            sb.appendLine("Packed Row Payload: ${profile.bufferLayout.packedRowBytes} bytes")
            sb.appendLine("Layout Valid: ${profile.bufferLayout.isValid}")
            if (profile.bufferLayout.validationError != null) {
                sb.appendLine("Layout Warning: ${profile.bufferLayout.validationError}")
            }
            sb.appendLine()

            sb.appendLine("--- SENSOR GEOMETRY ---")
            sb.appendLine("Pixel Array: ${profile.geometry.pixelArrayRect}")
            sb.appendLine("Pre-Correction Active Array: ${profile.geometry.preCorrectionActiveRect}")
            sb.appendLine("Active Array: ${profile.geometry.activeArrayRect}")
            sb.appendLine("Visible Raw Rect: ${profile.geometry.visibleRawRect}")
            sb.appendLine("Crop Offset: Left=${profile.geometry.cropLeft}, Top=${profile.geometry.cropTop}, Width=${profile.geometry.cropWidth}, Height=${profile.geometry.cropHeight}")
            sb.appendLine("CFA Pattern: ${profile.geometry.cfaArrangement.cfaName} (Phase +X:${profile.geometry.cfaPhaseX}, +Y:${profile.geometry.cfaPhaseY})")
            sb.appendLine("Sensor Orientation: ${profile.geometry.sensorOrientation}°")
            sb.appendLine("Visible Aspect Ratio: ${String.format(java.util.Locale.US, "%.4f", profile.geometry.visibleAspectRatio)}")
            sb.appendLine()

            sb.appendLine("--- PAIRING ---")
            sb.appendLine("Pairing Mode: ${state.timestampPairing.pairingMode}")
            sb.appendLine("Pairing Confidence: ${String.format(java.util.Locale.US, "%.2f", state.timestampPairing.pairingConfidence)}")
            sb.appendLine("Learned Offset: ${state.timestampPairing.learnedOffsetNs} ns (${state.timestampPairing.learnedOffsetNs / 1_000_000.0} ms)")
            sb.appendLine("Tolerance: ${state.timestampPairing.toleranceNs} ns (${state.timestampPairing.toleranceNs / 1_000_000.0} ms)")
            sb.appendLine("Ambiguous Matches: ${state.timestampPairing.ambiguousMatchCount}")
            sb.appendLine("Pairing Latency (P50/P95/P99): ${String.format(java.util.Locale.US, "%.2f / %.2f / %.2f ms", state.timestampPairing.pairingLatencyP50Ms, state.timestampPairing.pairingLatencyP95Ms, state.timestampPairing.pairingLatencyP99Ms)}")
            sb.appendLine("Self-Healing Active: ${state.timestampPairing.isSelfHealingActive} (Broken Count: ${state.timestampPairing.brokenCount})")
            sb.appendLine()

            sb.appendLine("--- BUFFER ---")
            sb.appendLine("Logical History Target: ${state.bufferHealth.logicalHistoryTarget}")
            sb.appendLine("Physical Resident Limit: ${state.bufferHealth.physicalResidentLimit}")
            sb.appendLine("Producer Reserve: ${state.bufferHealth.producerReserve}")
            sb.appendLine("Complete Frames Available: ${state.bufferHealth.completeFramesAvailable}")
            sb.appendLine("Single Frame Ready: ${state.bufferHealth.isSingleFrameCaptureReady}")
            sb.appendLine("Multi Frame Ready: ${state.bufferHealth.isMultiFrameCaptureReady}")
            sb.appendLine("Ownership Invariants: leakedLeaseCount=${state.bufferHealth.leakedLeaseCount}, leasedFrameMutations=${state.bufferHealth.leasedFrameMutations}, closedBorrowedFrames=${state.bufferHealth.closedBorrowedFrames}, deferredCloseCount=${state.bufferHealth.deferredCloseCount}/${state.bufferHealth.completedDeferredCloseCount}")
            sb.appendLine()

            sb.appendLine("--- VIEWFINDER ---")
            sb.appendLine("Effective Source: ${profile.previewConfig.viewfinderEffectiveSource}")
            sb.appendLine("User RAW Stream Setting: ${profile.previewConfig.rawStreamSelectedByUser}")
            sb.appendLine("Active Preview Stream Size: ${profile.previewConfig.activePreviewStreamSize.width}x${profile.previewConfig.activePreviewStreamSize.height}")
            sb.appendLine("Sharing Capture Stream: ${profile.previewConfig.isSharingCaptureStream}")
            sb.appendLine("Source Cadence FPS: ${String.format(java.util.Locale.US, "%.1f", state.cadence.sensorTimestampFps)}")
            sb.appendLine("Processing FPS: ${String.format(java.util.Locale.US, "%.1f", state.cadence.processingFps)}")
            sb.appendLine("Presentation FPS: ${String.format(java.util.Locale.US, "%.1f", state.cadence.presentationFps)}")
            sb.appendLine("Active Preview Resolution Tier: ${state.cadence.activePreviewTier}")
            sb.appendLine("AE Target FPS Range: [${state.cadence.activeAeTargetFps.first}, ${state.cadence.activeAeTargetFps.second}]")
            sb.appendLine()

            sb.appendLine("--- GPU ---")
            sb.appendLine("RAW10 Direct Import: ${profile.vulkanCapabilities.raw10DirectAHardwareBufferImportable}")
            sb.appendLine("RAW_SENSOR Direct Import: ${profile.vulkanCapabilities.rawSensorDirectAHardwareBufferImportable}")
            sb.appendLine("Preferred Import Route: ${profile.vulkanCapabilities.preferredImportMode}")
            sb.appendLine("Zero-Copy Output: ${profile.vulkanCapabilities.zeroCopyPreviewOutputSupported}")
            sb.appendLine()

            sb.appendLine("--- FALLBACKS ---")
            if (state.fallbacks.activeFallbacks.isEmpty()) {
                sb.appendLine("No active fallbacks. Pipeline running at optimal capability.")
            } else {
                state.fallbacks.activeFallbacks.forEach { fallback ->
                    sb.appendLine("- $fallback")
                }
            }

            DiagnosticsAggregator.record(
                stream = DiagnosticsAggregator.Stream.CAMERA,
                scope = "RAW SESSION #${profile.sessionId}",
                section = "RAW PIPELINE RUNTIME PROFILE",
                content = sb.toString()
            )
            Log.i(TAG, "Portability report routed to unified diagnostics: ${profile.sessionId}")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to route portability report", t)
            return false
        }
    }
}
