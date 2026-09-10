package com.bncam.core.vulkan

/**
 * Stable JNI boundary for the process-scoped native Vulkan runtime.
 *
 * It deliberately exposes metadata only. Raw Vulkan handles, AHardwareBuffer pointers and image
 * payloads must never cross this boundary.
 */
internal object VulkanNativeBridge {
    external fun nativeInitialize(
        debugValidationRequested: Boolean,
        requireAndroidHardwareBuffer: Boolean,
        pipelineCachePath: String
    ): Int

    external fun nativeConfigureSpectraNeuralModel(packageBytes: ByteArray): Boolean

    external fun nativeShutdown(): Int
    external fun nativeGetRuntimeStateCode(): Int
    external fun nativeGetSchemaVersion(): Int
    external fun nativeGetRuntimeIdentity(): String
    external fun nativeIsLoaderAvailable(): Boolean
    external fun nativeIsRuntimeInitialized(): Boolean
    external fun nativeGetSelectedDeviceName(): String
    external fun nativeGetEnabledExtensions(): Array<String>
    external fun nativeGetEnabledFeatures(): Array<String>
    external fun nativeGetMissingRequirements(): Array<String>
    external fun nativeGetActiveProductionStages(): Array<String>
    external fun nativeGetCreationCounters(): LongArray
    external fun nativeGetInFlightSubmissionCount(): Long
    external fun nativeGetRawPreviewOutputHardwareBufferUsage(): Long
    external fun nativePrepareRawPreviewBackend(): Boolean
    external fun nativeGetLastFailureCode(): String
    external fun nativeGetLastFailureMessage(): String
    external fun nativeIsLastFailureRetryable(): Boolean
    external fun nativeGetRuntimeSnapshotJson(): String
    external fun nativeGetCapabilitiesJson(): String
    external fun nativeGetDiagnosticsJson(): String
    external fun nativeGetValidationMessagesJson(): String
    external fun nativeGetRuntimeHumanReadable(): String
    external fun nativeGetCapabilitiesHumanReadable(): String
    external fun nativeGetValidationHumanReadable(): String
}
