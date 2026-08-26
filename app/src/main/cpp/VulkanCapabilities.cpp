#include "VulkanCapabilities.h"
#include "vulkan/VulkanRuntime.h"

/**
 * Compatibility adapter for the old debug profiler API.
 *
 * It must never create a standalone VkInstance. All information now comes from the authoritative
 * process-scoped runtime. The legacy struct remains only to avoid breaking the debug JNI surface.
 */
VulkanCapabilityReport VulkanCapabilities::queryCapabilities() {
    VulkanCapabilityReport report;
    const auto runtime = bncam::vulkan::VulkanRuntime::instance().snapshot();
    const auto capabilities = bncam::vulkan::VulkanRuntime::instance().capabilities();

    report.isAvailable =
        runtime.state == bncam::vulkan::RuntimeState::READY && runtime.loaderAvailable;
    report.apiVersion = capabilities.deviceApiVersion != 0
        ? capabilities.deviceApiVersion : capabilities.loaderApiVersion;
    report.deviceName = capabilities.selectedDeviceName;
    report.hasComputeQueue = capabilities.computeQueueCount > 0;
    report.timestampQueriesSupported = capabilities.timestampQueriesSupported;
    report.deviceLocalMemoryBytes = capabilities.deviceLocalMemoryBytes;
    report.hostVisibleMemoryBytes = capabilities.hostVisibleMemoryBytes;
    report.hardwareBufferImportSupported =
        capabilities.androidHardwareBufferExtensionSupported;
    report.timelineSemaphoresSupported = capabilities.timelineSemaphoresSupported;
    report.classification = report.isAvailable
        ? GpuClassification::GPU_COMPUTE_SUPPORTED_WITH_COPY
        : GpuClassification::GPU_COMPUTE_UNSUPPORTED;
    report.summaryJson = capabilities.toJson();
    return report;
}
