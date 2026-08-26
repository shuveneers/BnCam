package com.bncam.core.vulkan

import android.content.Context
import com.bncam.core.debug.DiagnosticsAggregator
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-facing facade for the one authoritative native Vulkan runtime.
 *
 * [attach] intentionally does not initialize Vulkan in the preparation phase. The Phase 3A
 * implementation injects real initialization behind the native bootstrap and may then call
 * [initialize] from the application/native-engine lifecycle. Camera sessions and Compose must not
 * own or destroy this runtime.
 */
object VulkanRuntimeOwner {
    private val attached = AtomicBoolean(false)
    @Volatile private var applicationPackageName: String = "unattached"
    @Volatile private var pipelineCachePath: String = ""

    fun attach(context: Context) {
        val appContext = context.applicationContext
        applicationPackageName = appContext.packageName
        pipelineCachePath = File(appContext.codeCacheDir, "bncam_vulkan_pipeline_cache.bin").absolutePath
        attached.set(true)
    }

    fun isAttached(): Boolean = attached.get()

    @Synchronized
    fun initialize(config: VulkanRuntimeConfig): VulkanRuntimeSnapshot {
        if (!attached.get()) {
            return VulkanRuntimeSnapshot.bridgeUnavailable(
                "VulkanRuntimeOwner.initialize called before application attachment"
            )
        }
        return nativeOrUnavailable {
            VulkanNativeBridge.nativeInitialize(
                debugValidationRequested = config.debugValidationRequested,
                requireAndroidHardwareBuffer = config.requireAndroidHardwareBuffer,
                pipelineCachePath = pipelineCachePath
            )
            readSnapshot()
        }
    }

    @Synchronized
    fun shutdown(): VulkanRuntimeSnapshot = nativeOrUnavailable {
        VulkanNativeBridge.nativeShutdown()
        readSnapshot()
    }

    fun snapshot(): VulkanRuntimeSnapshot = nativeOrUnavailable(::readSnapshot)

    fun rawPreviewOutputHardwareBufferUsage(): Long = try {
        VulkanNativeBridge.nativeGetRawPreviewOutputHardwareBufferUsage().coerceAtLeast(0L)
    } catch (_: Throwable) {
        0L
    }

    fun prepareRawPreviewBackend(): Boolean = try {
        VulkanNativeBridge.nativePrepareRawPreviewBackend()
    } catch (_: Throwable) {
        false
    }

    fun exportApplicationDiagnostics(): Boolean = try {
        val export = diagnosticExport()
        DiagnosticsAggregator.record(
            DiagnosticsAggregator.Stream.ISP,
            "APPLICATION",
            "VULKAN RUNTIME",
            export.runtimeText + "\nruntimeJson=" + export.runtimeJson
        )
        DiagnosticsAggregator.record(
            DiagnosticsAggregator.Stream.ISP,
            "APPLICATION",
            "VULKAN CAPABILITIES",
            export.capabilitiesText + "\ncapabilitiesJson=" + export.capabilitiesJson
        )
        DiagnosticsAggregator.record(
            DiagnosticsAggregator.Stream.ISP,
            "APPLICATION",
            "VULKAN DIAGNOSTICS",
            "diagnosticsJson=" + export.diagnosticsJson
        )
        DiagnosticsAggregator.record(
            DiagnosticsAggregator.Stream.PERFORMANCE,
            "APPLICATION",
            "VULKAN VALIDATION",
            export.validationText + "\nvalidationJson=" + export.validationJson
        )
        true
    } catch (_: Throwable) {
        false
    }

    fun diagnosticExport(): VulkanDiagnosticExport = try {
        VulkanDiagnosticExport(
            runtimeJson = VulkanNativeBridge.nativeGetRuntimeSnapshotJson(),
            capabilitiesJson = VulkanNativeBridge.nativeGetCapabilitiesJson(),
            diagnosticsJson = VulkanNativeBridge.nativeGetDiagnosticsJson(),
            validationJson = VulkanNativeBridge.nativeGetValidationMessagesJson(),
            runtimeText = VulkanNativeBridge.nativeGetRuntimeHumanReadable(),
            capabilitiesText = VulkanNativeBridge.nativeGetCapabilitiesHumanReadable(),
            validationText = VulkanNativeBridge.nativeGetValidationHumanReadable()
        )
    } catch (failure: Throwable) {
        val fallback = VulkanRuntimeSnapshot.bridgeUnavailable(
            "${failure.javaClass.simpleName}:${failure.message.orEmpty()}"
        )
        val text = buildString {
            appendLine("VULKAN RUNTIME")
            appendLine("State: ${fallback.state}")
            appendLine("Native bridge available: false")
            appendLine("Reason: ${fallback.lastFailure.message}")
            appendLine("Application package: $applicationPackageName")
            appendLine("Production Vulkan stages: none")
        }
        VulkanDiagnosticExport(
            runtimeJson = fallback.toJson(),
            capabilitiesJson = "{\"schemaVersion\":1,\"records\":[],\"reason\":\"native_bridge_unavailable\"}",
            diagnosticsJson = fallback.toJson(),
            validationJson = "{\"schemaVersion\":1,\"records\":[],\"reason\":\"native_bridge_unavailable\"}",
            runtimeText = text,
            capabilitiesText = "VULKAN CAPABILITIES\nNot available: native bridge unavailable.\n",
            validationText = "VULKAN VALIDATION\nNot available: native bridge unavailable.\n"
        )
    }

    private fun readSnapshot(): VulkanRuntimeSnapshot {
        val counters = VulkanNativeBridge.nativeGetCreationCounters()
        fun counter(index: Int): Long = counters.getOrNull(index)?.coerceAtLeast(0L) ?: 0L
        val selectedDevice = VulkanNativeBridge.nativeGetSelectedDeviceName()
            .trim()
            .ifBlank { null }
        return VulkanRuntimeSnapshot(
            schemaVersion = VulkanNativeBridge.nativeGetSchemaVersion()
                .takeIf { it > 0 } ?: VulkanRuntimeSnapshot.SCHEMA_VERSION,
            state = VulkanRuntimeState.fromNativeCode(
                VulkanNativeBridge.nativeGetRuntimeStateCode()
            ),
            runtimeIdentity = VulkanNativeBridge.nativeGetRuntimeIdentity(),
            loaderAvailable = VulkanNativeBridge.nativeIsLoaderAvailable(),
            runtimeInitialized = VulkanNativeBridge.nativeIsRuntimeInitialized(),
            selectedDevice = selectedDevice,
            enabledExtensions = VulkanNativeBridge.nativeGetEnabledExtensions().toList(),
            enabledFeatures = VulkanNativeBridge.nativeGetEnabledFeatures().toList(),
            missingRequirements = VulkanNativeBridge.nativeGetMissingRequirements().toList(),
            activeProductionStages = VulkanNativeBridge.nativeGetActiveProductionStages().toList(),
            instanceCreationCount = counter(0),
            deviceCreationCount = counter(1),
            initializeRequestCount = counter(2),
            shutdownRequestCount = counter(3),
            inFlightSubmissionCount = VulkanNativeBridge
                .nativeGetInFlightSubmissionCount()
                .coerceAtLeast(0L),
            lastFailure = VulkanRuntimeFailure(
                code = VulkanNativeBridge.nativeGetLastFailureCode(),
                message = VulkanNativeBridge.nativeGetLastFailureMessage(),
                retryable = VulkanNativeBridge.nativeIsLastFailureRetryable()
            ),
            nativeBridgeAvailable = true
        )
    }

    private inline fun nativeOrUnavailable(
        action: () -> VulkanRuntimeSnapshot
    ): VulkanRuntimeSnapshot = try {
        action()
    } catch (failure: Throwable) {
        VulkanRuntimeSnapshot.bridgeUnavailable(
            "${failure.javaClass.simpleName}:${failure.message.orEmpty()}"
        )
    }
}
