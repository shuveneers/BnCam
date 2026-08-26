package com.bncam.core.vulkan

import com.bncam.core.tracing.StableJson

enum class VulkanRuntimeState(val nativeCode: Int) {
    UNINITIALIZED(0),
    INITIALIZING(1),
    READY(2),
    UNAVAILABLE(3),
    FAILED(4),
    SHUTTING_DOWN(5),
    DESTROYED(6);

    companion object {
        fun fromNativeCode(code: Int): VulkanRuntimeState =
            entries.firstOrNull { it.nativeCode == code } ?: FAILED
    }
}

enum class VulkanCapabilityStatus {
    AVAILABLE,
    SUPPORTED,
    ENABLED,
    REQUIRED_LATER,
    MISSING,
    NOT_SCANNED,
    SUPPORTED_BY_CORE_VERSION,
    SUPPORTED_BY_EXTENSION,
    ENABLED_AS_CORE_FEATURE,
    ENABLED_AS_EXTENSION,
    SUPPORTED_NOT_ENABLED
}

data class VulkanRuntimeConfig(
    val debugValidationRequested: Boolean,
    val requireAndroidHardwareBuffer: Boolean = true
)

data class VulkanRuntimeFailure(
    val code: String,
    val message: String,
    val retryable: Boolean
) {
    val isPresent: Boolean get() = code.isNotBlank() || message.isNotBlank()
}

data class VulkanRuntimeSnapshot(
    val schemaVersion: Int,
    val state: VulkanRuntimeState,
    val runtimeIdentity: String,
    val loaderAvailable: Boolean,
    val runtimeInitialized: Boolean,
    val selectedDevice: String?,
    val enabledExtensions: List<String>,
    val enabledFeatures: List<String>,
    val missingRequirements: List<String>,
    val activeProductionStages: List<String>,
    val instanceCreationCount: Long,
    val deviceCreationCount: Long,
    val initializeRequestCount: Long,
    val shutdownRequestCount: Long,
    val inFlightSubmissionCount: Long,
    val lastFailure: VulkanRuntimeFailure,
    val nativeBridgeAvailable: Boolean
) {
    init {
        require(schemaVersion > 0)
        require(instanceCreationCount >= 0)
        require(deviceCreationCount >= 0)
        require(initializeRequestCount >= 0)
        require(shutdownRequestCount >= 0)
        require(inFlightSubmissionCount >= 0)
        if (state == VulkanRuntimeState.READY) {
            require(runtimeInitialized)
            require(loaderAvailable)
            require(!selectedDevice.isNullOrBlank())
        }
    }

    val productionVulkanActive: Boolean get() = activeProductionStages.isNotEmpty()

    fun toJson(): String = StableJson.encode(
        linkedMapOf(
            "schemaVersion" to schemaVersion,
            "state" to state.name,
            "runtimeIdentity" to runtimeIdentity,
            "loaderAvailable" to loaderAvailable,
            "runtimeInitialized" to runtimeInitialized,
            "selectedDevice" to selectedDevice,
            "enabledExtensions" to enabledExtensions,
            "enabledFeatures" to enabledFeatures,
            "missingRequirements" to missingRequirements,
            "activeProductionStages" to activeProductionStages,
            "instanceCreationCount" to instanceCreationCount,
            "deviceCreationCount" to deviceCreationCount,
            "initializeRequestCount" to initializeRequestCount,
            "shutdownRequestCount" to shutdownRequestCount,
            "inFlightSubmissionCount" to inFlightSubmissionCount,
            "lastFailure" to linkedMapOf(
                "code" to lastFailure.code,
                "message" to lastFailure.message,
                "retryable" to lastFailure.retryable
            ),
            "nativeBridgeAvailable" to nativeBridgeAvailable
        )
    )

    companion object {
        const val SCHEMA_VERSION = 1

        fun bridgeUnavailable(reason: String): VulkanRuntimeSnapshot = VulkanRuntimeSnapshot(
            schemaVersion = SCHEMA_VERSION,
            state = VulkanRuntimeState.UNAVAILABLE,
            runtimeIdentity = "native-bridge-unavailable",
            loaderAvailable = false,
            runtimeInitialized = false,
            selectedDevice = null,
            enabledExtensions = emptyList(),
            enabledFeatures = emptyList(),
            missingRequirements = listOf("native_vulkan_runtime_bridge"),
            activeProductionStages = emptyList(),
            instanceCreationCount = 0,
            deviceCreationCount = 0,
            initializeRequestCount = 0,
            shutdownRequestCount = 0,
            inFlightSubmissionCount = 0,
            lastFailure = VulkanRuntimeFailure(
                code = "NATIVE_BRIDGE_UNAVAILABLE",
                message = reason.take(512),
                retryable = true
            ),
            nativeBridgeAvailable = false
        )
    }
}

data class VulkanDiagnosticExport(
    val runtimeJson: String,
    val capabilitiesJson: String,
    val diagnosticsJson: String,
    val validationJson: String,
    val runtimeText: String,
    val capabilitiesText: String,
    val validationText: String
)
