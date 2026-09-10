package com.bncam

import android.app.Application
import android.os.Build
import com.bncam.core.nativebridge.NativeEngineLoader
import com.bncam.core.vulkan.VulkanRuntimeConfig
import com.bncam.core.vulkan.VulkanRuntimeOwner
import com.bncam.core.vulkan.SpectraNeuralModelInstaller
import org.lsposed.hiddenapibypass.HiddenApiBypass

class BnCamApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Preserve the existing startup prerequisite before any native engine work begins.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }

        // Diagnostics export has one process-scoped sink; producers never own output files.
        com.bncam.core.debug.DiagnosticsAggregator.initialize(applicationContext)

        // Native libraries and the Vulkan owner are process-scoped and installed exactly once.
        NativeEngineLoader.ensureLoaded()
        VulkanRuntimeOwner.attach(applicationContext)
        VulkanRuntimeOwner.initialize(
            VulkanRuntimeConfig(
                debugValidationRequested = BuildConfig.DEBUG,
                requireAndroidHardwareBuffer = true
            )
        )
        SpectraNeuralModelInstaller.loadBundled(applicationContext)
        VulkanRuntimeOwner.prepareRawPreviewBackend()
        VulkanRuntimeOwner.exportApplicationDiagnostics()
        com.bncam.core.debug.RawRecoveryTrace.init(applicationContext)
        com.bncam.core.debug.ShotLogger(applicationContext).recoverStaleStartedAttempts()
    }

    override fun onTerminate() {
        // Android production processes are normally killed without this callback. It exists as a
        // deterministic emulator/instrumentation shutdown hook; camera/activity lifecycles must
        // never destroy the process-scoped Vulkan runtime.
        VulkanRuntimeOwner.shutdown()
        super.onTerminate()
    }
}
