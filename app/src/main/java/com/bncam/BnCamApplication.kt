package com.bncam

import android.app.Application
import android.os.Build
import android.os.SystemClock
import com.bncam.core.debug.Phase0PerformanceTrace
import com.bncam.core.nativebridge.NativeEngineLoader
import com.bncam.core.runtime.ViewfinderStartupGate
import com.bncam.core.vulkan.VulkanRuntimeConfig
import com.bncam.core.vulkan.VulkanRuntimeOwner
import com.bncam.core.vulkan.SpectraNeuralModelInstaller
import org.lsposed.hiddenapibypass.HiddenApiBypass

class BnCamApplication : Application() {
    override fun onCreate() {
        val applicationOnCreateStartNs = SystemClock.elapsedRealtimeNanos()
        super.onCreate()
        Phase0PerformanceTrace.applicationOnCreateStarted(this, applicationOnCreateStartNs)

        // Preserve the existing startup prerequisite before any native engine work begins.
        Phase0PerformanceTrace.markStartup("hidden_api_setup_start")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
        Phase0PerformanceTrace.markStartup("hidden_api_setup_end")

        // Diagnostics export has one process-scoped sink; producers never own output files.
        Phase0PerformanceTrace.markStartup("diagnostics_initialize_start")
        com.bncam.core.debug.DiagnosticsAggregator.initialize(applicationContext)
        Phase0PerformanceTrace.markStartup("diagnostics_initialize_end")

        // Native libraries and the Vulkan owner are process-scoped and installed exactly once.
        Phase0PerformanceTrace.markStartup("native_load_start")
        NativeEngineLoader.ensureLoaded()
        Phase0PerformanceTrace.markStartup("native_load_end")
        Phase0PerformanceTrace.markStartup("vulkan_attach_start")
        VulkanRuntimeOwner.attach(applicationContext)
        Phase0PerformanceTrace.markStartup("vulkan_attach_end")
        Phase0PerformanceTrace.markStartup("vulkan_init_start")
        VulkanRuntimeOwner.initialize(
            VulkanRuntimeConfig(
                debugValidationRequested = BuildConfig.DEBUG,
                requireAndroidHardwareBuffer = true
            )
        )
        Phase0PerformanceTrace.markStartup("vulkan_init_end")
        Phase0PerformanceTrace.markStartup("neural_model_prepare_start")
        SpectraNeuralModelInstaller.loadBundled(applicationContext)
        Phase0PerformanceTrace.markStartup("neural_model_prepare_end")

        // RAW preview backend preparation is deliberately NOT part of Application.onCreate().
        // BnCameraManager prepares it when RAW10/RAW_SENSOR is actually requested.

        com.bncam.core.debug.RawRecoveryTrace.init(applicationContext)
        Phase0PerformanceTrace.markStartup("recovery_initialize_start")
        com.bncam.core.debug.ShotLogger(applicationContext).recoverStaleStartedAttempts()
        Phase0PerformanceTrace.markStartup("recovery_initialize_end")
        Phase0PerformanceTrace.markStartup("application_onCreate_end")

        // DELTA 0217A: never compile/persist optional Vulkan pipelines while the first viewfinder
        // is still trying to become visible. On a fresh install the pipeline cache is empty and the
        // old immediate background prewarm could contend with SurfaceTexture/EGL/RAW initialization.
        // There is intentionally no time-based sleep or timeout: capture can lazily initialize the
        // backend if the user presses shutter before this optional prewarm has run.
        ViewfinderStartupGate.runAfterFirstPresentation {
            Thread({
                val firstPresentation = ViewfinderStartupGate.snapshot()
                com.bncam.core.debug.DiagnosticsAggregator.record(
                    com.bncam.core.debug.DiagnosticsAggregator.Stream.PERFORMANCE,
                    "APPLICATION",
                    "POST FIRST VIEWFINDER WARMUP START",
                    "source=${firstPresentation?.source ?: "unknown"};" +
                        "generation=${firstPresentation?.generation ?: -1};" +
                        "signal=${firstPresentation?.signal ?: "unknown"}"
                )

                val startNs = SystemClock.elapsedRealtimeNanos()
                val prepared = VulkanRuntimeOwner.prepareYuvSingleFrameBackend()
                val wallMs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0
                com.bncam.core.debug.DiagnosticsAggregator.record(
                    com.bncam.core.debug.DiagnosticsAggregator.Stream.PERFORMANCE,
                    "APPLICATION",
                    "YUV SINGLE FRAME PREWARM",
                    "prepared=$prepared;wallMs=${String.format(java.util.Locale.US, "%.3f", wallMs)}"
                )

                val diagnosticsStartNs = SystemClock.elapsedRealtimeNanos()
                val diagnosticsExported = VulkanRuntimeOwner.exportApplicationDiagnostics()
                val diagnosticsWallMs =
                    (SystemClock.elapsedRealtimeNanos() - diagnosticsStartNs) / 1_000_000.0
                com.bncam.core.debug.DiagnosticsAggregator.record(
                    com.bncam.core.debug.DiagnosticsAggregator.Stream.PERFORMANCE,
                    "APPLICATION",
                    "POST STARTUP VULKAN DIAGNOSTICS",
                    "exported=$diagnosticsExported;wallMs=${
                        String.format(java.util.Locale.US, "%.3f", diagnosticsWallMs)
                    }"
                )
            }, "BnCam-PostFirstViewfinderWarmup").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
                start()
            }
        }
    }

    override fun onTerminate() {
        // Android production processes are normally killed without this callback. It exists as a
        // deterministic emulator/instrumentation shutdown hook; camera/activity lifecycles must
        // never destroy the process-scoped Vulkan runtime.
        VulkanRuntimeOwner.shutdown()
        super.onTerminate()
    }
}
