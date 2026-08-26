package com.bncam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.debug.BenchmarkWriter
import com.bncam.core.debug.AfGroundTruthTrace
import com.bncam.core.engine.CaptureStrategy
import com.bncam.core.quality.DemosaicMode
import com.bncam.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug-source-set-only external control surface used by deterministic device verification.
 * This implementation and its action strings are absent from release builds.
 */
object BenchmarkDebugReceiverController {
    const val ACTION_SET_CONFIG = "com.bncam.SET_BENCHMARK_CONFIG"
    const val ACTION_TRIGGER_CAPTURE = "com.bncam.TRIGGER_CAPTURE"
    const val ACTION_AF_GROUND_TRUTH_CONTROL = "com.bncam.AF_GROUND_TRUTH_CONTROL"

    fun register(
        activity: MainActivity,
        repository: SettingsRepository
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_AF_GROUND_TRUTH_CONTROL -> {
                        if (context != null && intent.getBooleanExtra("clear", false)) {
                            AfGroundTruthTrace.clear(context)
                        }
                        intent.getStringExtra("transaction_id")
                            ?.takeIf { it.isNotBlank() }
                            ?.let(AfGroundTruthTrace::setNextTransactionId)
                        if (intent.hasExtra("passive_ae_duration_ms")) {
                            AfGroundTruthTrace.beginPassiveAeObservation(
                                transactionId = intent.getStringExtra("transaction_id")
                                    ?: "AE_PASSIVE",
                                durationMs = intent.getLongExtra("passive_ae_duration_ms", 6_000L)
                            )
                        }
                    }
                    ACTION_SET_CONFIG -> {
                        val requestedProfileId = intent.getStringExtra("profile")
                        val format = intent.getStringExtra("format")
                        val demosaic = intent.getStringExtra("demosaic")
                        val captureMode = intent.getStringExtra("mode")
                        val outputPolicy = intent.getStringExtra("output")
                        val meteringStyle = intent.getStringExtra("metering")
                        val processingFrames =
                            intent.takeIf { it.hasExtra("processingFrames") }
                                ?.getIntExtra("processingFrames", 1)
                        val dngMasterFrames =
                            intent.takeIf { it.hasExtra("dngMasterFrames") }
                                ?.getIntExtra("dngMasterFrames", 1)
                        val shotLogger =
                            if (intent.hasExtra("shotLogger")) {
                                intent.getBooleanExtra("shotLogger", false)
                            } else {
                                null
                            }
                        if (intent.getBooleanExtra("clear", false) && context != null) {
                            BenchmarkWriter.clear(context)
                        }
                        activity.lifecycleScope.launch {
                            requestedProfileId
                                ?.takeIf { it.isNotBlank() }
                                ?.let { repository.setActiveProfileId(it) }
                            meteringStyle
                                ?.takeIf { it.isNotBlank() }
                                ?.let { repository.setMeteringStyle(it) }
                            val activeProfileId =
                                requestedProfileId?.takeIf { it.isNotBlank() }
                                    ?: repository.activeProfileIdFlow.first()
                            if (!activeProfileId.isNullOrBlank()) {
                                format?.takeIf { it.isNotBlank() }?.let {
                                    repository.setProfileFrameSource(activeProfileId, it)
                                }
                                demosaic?.takeIf { it.isNotBlank() }?.let {
                                    repository.setProfileString(
                                        activeProfileId,
                                        DemosaicMode.PROFILE_KEY,
                                        it
                                    )
                                }
                                captureMode?.let { requested ->
                                    CaptureStrategy.entries
                                        .firstOrNull {
                                            it.name.equals(requested, ignoreCase = true)
                                        }
                                        ?.let {
                                            repository.setProfileCaptureMode(activeProfileId, it)
                                        }
                                }
                                outputPolicy?.let {
                                    repository.setOutputPolicy(OutputPolicy.parse(it))
                                }
                                processingFrames?.let { count ->
                                    when (format?.uppercase()) {
                                        "RAW10" ->
                                            repository.setProfileMultiFrameFusionFramesRaw10(
                                                activeProfileId,
                                                count
                                            )
                                        "RAW_SENSOR" ->
                                            repository.setProfileMultiFrameFusionFramesRawSensor(
                                                activeProfileId,
                                                count
                                            )
                                        "YUV" ->
                                            repository.setProfileMultiFrameFusionFramesYuv(
                                                activeProfileId,
                                                count
                                            )
                                    }
                                }
                                dngMasterFrames?.let { count ->
                                    when (format?.uppercase()) {
                                        "RAW10" ->
                                            repository.setProfileMultiFrameDngMasterFramesRaw10(
                                                activeProfileId,
                                                count
                                            )
                                        "RAW_SENSOR" ->
                                            repository.setProfileMultiFrameDngMasterFramesRawSensor(
                                                activeProfileId,
                                                count
                                            )
                                    }
                                }
                                shotLogger?.let { repository.setEnableShotLogger(it) }
                            }
                        }
                    }
                    ACTION_TRIGGER_CAPTURE -> {
                        activity.lifecycleScope.launch {
                            CameraEventBus.captureRequests.emit(Unit)
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_SET_CONFIG)
                addAction(ACTION_TRIGGER_CAPTURE)
                addAction(ACTION_AF_GROUND_TRUTH_CONTROL)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        return receiver
    }
}
