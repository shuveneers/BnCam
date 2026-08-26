package com.bncam.core.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bncam.core.engine.CaptureStrategy
import com.bncam.data.settings.LensHardwareTuningModes
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.StableLensKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object DebugTestOverride {
    @Volatile var forcedCaptureStrategy: CaptureStrategy? = null
}

class DebugTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("DebugTestReceiver", "Received broadcast action: $action")
        if (action == "com.bncam.TRIGGER_CAPTURE") {
            Log.i("DebugTestReceiver", "Triggering capture via ADB broadcast...")
            com.bncam.CameraEventBus.captureRequests.tryEmit(Unit)
            return
        }
        if (action == "com.bncam.DUMP_TELEMETRY") {
            val pendingResult = goAsync()
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                try {
                    val cameraManager = com.bncam.core.engine.BnCameraManager.activeInstance
                    val snapshot = cameraManager?.getLiveDiagnosticsSnapshot().orEmpty()
                    val activationReport = com.bncam.core.debug.RawPreviewFirstActivationTrace.latestReport()
                    val cadenceReport = com.bncam.ui.screens.capture.RawPreviewCadenceDiagnostics.latestReport()
                    val sb = StringBuilder()
                    sb.appendLine("=== TELEMETRY DUMP ===")
                    snapshot.forEach { (k, v) -> sb.appendLine("$k: $v") }
                    sb.appendLine("\n=== ACTIVATION TRACE ===")
                    sb.appendLine(activationReport ?: "No activation trace available")
                    sb.appendLine("\n=== CADENCE REPORT ===")
                    sb.appendLine(cadenceReport ?: "No cadence report available")
                    val content = sb.toString()
                    val internalFile = java.io.File(context.filesDir, "telemetry_dump.txt")
                    internalFile.writeText(content)
                    val debugDir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "Documents/BnCamDebug")
                    if (debugDir.exists() || debugDir.mkdirs()) {
                        java.io.File(debugDir, "telemetry_dump.txt").writeText(content)
                    }
                    Log.i("DebugTestReceiver", "DUMP_TELEMETRY written to ${internalFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e("DebugTestReceiver", "Error dumping telemetry", e)
                } finally {
                    pendingResult.finish()
                }
            }
            return
        }
        if (action == "com.bncam.SET_TEST_CONFIG") {
            val pendingResult = goAsync()
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                try {
                    val repo = SettingsRepository(context.applicationContext)
                    val mode = intent.getStringExtra("mode")
                    val dynamicIso = intent.getFloatExtra("dynamic_iso", -1f)
                    val calibAdj = intent.getDoubleExtra("calib_adj", Double.NaN)
                    val chromaAdj = intent.getDoubleExtra("chroma_adj", Double.NaN)
                    val lumaAdj = intent.getDoubleExtra("luma_adj", Double.NaN)
                    val forceFailure = intent.getBooleanExtra("force_failure", false)
                    val captureMode = intent.getStringExtra("capture_mode")
                    val frameSource = intent.getStringExtra("frame_source")
                    val activeLens = repo.activeLensIdFlow.first() ?: "0"
                    val lensId = intent.getStringExtra("lens_id") ?: activeLens
                    val stableKey = StableLensKey.fromString(lensId)

                    val meteringStyle = intent.getStringExtra("metering_style")
                    if (meteringStyle != null) {
                        com.bncam.core.engine.BnCameraManager.activeInstance?.setMeteringStyle(meteringStyle)
                        Log.i("DebugTestReceiver", "SET_TEST_CONFIG set metering_style=$meteringStyle")
                    }

                    if (intent.hasExtra("iso") || intent.hasExtra("shutter_ns")) {
                        val rawIso = intent.getIntExtra("iso", -1).takeIf { it > 0 }
                        val rawShutter = intent.getLongExtra("shutter_ns", -1L).takeIf { it > 0L }
                        com.bncam.core.engine.BnCameraManager.activeInstance?.setManualIsoAndShutter(
                            iso = rawIso,
                            shutterSpeedNs = rawShutter,
                            cameraId = lensId
                        )
                        Log.i("DebugTestReceiver", "SET_TEST_CONFIG set manual exposure iso=$rawIso shutter=$rawShutter")
                    }

                    if (intent.getBooleanExtra("restore_focus", false)) {
                        com.bncam.core.engine.BnCameraManager.activeInstance?.restoreConfiguredAutoFocusAfterTap()
                        Log.i("DebugTestReceiver", "SET_TEST_CONFIG restored configured autofocus")
                    }

                    if (mode != null) {
                        val current = repo.getLensNoiseModelSettingsFlow(stableKey).first()
                        val normMode = when (mode.lowercase()) {
                            "auto" -> LensHardwareTuningModes.AUTO
                            "manual" -> LensHardwareTuningModes.MANUAL
                            "off" -> LensHardwareTuningModes.OFF
                            else -> LensHardwareTuningModes.AUTO
                        }
                        repo.setLensNoiseModelSettings(stableKey, current.copy(mode = normMode))
                    }
                    if (dynamicIso >= 0f) {
                        repo.setDynamicIsoCoeff(stableKey, dynamicIso)
                    }
                    if (!calibAdj.isNaN()) {
                        repo.setNoiseModelCalibrationAdjustment(stableKey, calibAdj.toFloat())
                    }
                    if (!chromaAdj.isNaN()) {
                        repo.setDynamicChromaAuthorityAdjustment(stableKey, chromaAdj.toFloat())
                    }
                    if (!lumaAdj.isNaN()) {
                        repo.setDynamicLumaAuthorityAdjustment(stableKey, lumaAdj.toFloat())
                    }
                    if (frameSource != null) {
                        val normSource = frameSource.uppercase()
                        for (l in 0..3) {
                            repo.setProfileFrameSource("${l}_disabled", normSource)
                            for (p in 1..12) {
                                repo.setProfileFrameSource("${l}_profile_$p", normSource)
                            }
                        }
                    }
                    val vfStream = intent.getStringExtra("viewfinder_stream")
                    if (vfStream != null) {
                        val parsed = com.bncam.ui.screens.capture.ViewfinderStream.parse(vfStream)
                        repo.setViewfinderStream(parsed)
                        com.bncam.core.engine.BnCameraManager.activeInstance?.setViewfinderStreamDirect(parsed)
                        Log.i("DebugTestReceiver", "SET_TEST_CONFIG updated viewfinderStream=$vfStream")
                    }
                    DebugTestOverride.forcedCaptureStrategy = null

                    val isHdrRequested = when {
                        captureMode != null && (captureMode.equals("hdr_enhanced", ignoreCase = true) || captureMode.equals("hdr", ignoreCase = true) || captureMode.equals("computational_hdr", ignoreCase = true)) -> true
                        captureMode != null && (captureMode.equals("single", ignoreCase = true) || captureMode.equals("normal", ignoreCase = true)) -> false
                        else -> null
                    }

                    val compHdrExtra = when {
                        isHdrRequested != null -> isHdrRequested
                        intent.hasExtra("computational_hdr") -> {
                            val raw = intent.extras?.get("computational_hdr")
                            when (raw) {
                                is Boolean -> raw
                                is String -> raw.equals("true", ignoreCase = true) || raw == "1"
                                else -> intent.getBooleanExtra("computational_hdr", false)
                            }
                        }
                        intent.hasExtra("hdr_enhanced") -> {
                            val raw = intent.extras?.get("hdr_enhanced")
                            when (raw) {
                                is Boolean -> raw
                                is String -> raw.equals("true", ignoreCase = true) || raw == "1"
                                else -> intent.getBooleanExtra("hdr_enhanced", false)
                            }
                        }
                        else -> null
                    }

                    if (compHdrExtra != null) {
                        repo.setComputationalHdrEnabled(compHdrExtra)
                        Log.i("DebugTestReceiver", "SET_TEST_CONFIG updated computationalHdrEnabled=$compHdrExtra (forcedCaptureStrategy=null)")
                    }

                    if (forceFailure) {
                        DebugFailureTestTrigger.forceFailureForNextCapture = true
                    }
                    Log.i("DebugTestReceiver", "Successfully updated test settings for $stableKey: mode=$mode, iso=$dynamicIso, capture_mode=$captureMode, compHdr=$compHdrExtra")
                } catch (e: Exception) {
                    Log.e("DebugTestReceiver", "Error updating test settings", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
