package com.bncam

import android.app.Application
import android.os.Build
import android.os.SystemClock
import com.bncam.core.debug.Phase0PerformanceTrace
import com.bncam.core.nativebridge.NativeEngineLoader
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
        Phase0PerformanceTrace.markStartup("raw_preview_backend_prepare_start")
        VulkanRuntimeOwner.prepareRawPreviewBackend()
        Phase0PerformanceTrace.markStartup("raw_preview_backend_prepare_end")
        Phase0PerformanceTrace.markStartup("diagnostics_export_start")
        VulkanRuntimeOwner.exportApplicationDiagnostics()
        Phase0PerformanceTrace.markStartup("diagnostics_export_end")
        com.bncam.core.debug.RawRecoveryTrace.init(applicationContext)
        Phase0PerformanceTrace.markStartup("recovery_initialize_start")
        com.bncam.core.debug.ShotLogger(applicationContext).recoverStaleStartedAttempts()
        Phase0PerformanceTrace.markStartup("recovery_initialize_end")
        Phase0PerformanceTrace.markStartup("application_onCreate_end")
    }

    override fun onTerminate() {
        // Android production processes are normally killed without this callback. It exists as a
        // deterministic emulator/instrumentation shutdown hook; camera/activity lifecycles must
        // never destroy the process-scoped Vulkan runtime.
        VulkanRuntimeOwner.shutdown()
        super.onTerminate()
    }
}
