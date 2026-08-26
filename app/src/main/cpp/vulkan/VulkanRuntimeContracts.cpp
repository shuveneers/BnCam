#include "VulkanRuntimeContracts.h"
#include "VulkanJson.h"

#include <sstream>

namespace bncam::vulkan {

CapabilitySnapshot CapabilitySnapshot::notScanned() {
    CapabilitySnapshot snapshot;
    const std::vector<std::string> requiredKeys = {
        "vulkan_loader",
        "instance_api_version",
        "physical_device",
        "device_type",
        "queue_families",
        "compute_queue",
        "command_pool",
        "descriptor_pool",
        "pipeline_cache",
        "vma_allocator",
        "memory_heaps",
        "memory_types",
        "storage_buffers",
        "storage_images",
        "timestamps",
        "subgroups",
        "synchronization",
        "descriptor_indexing",
        "validation_layer",
        "VK_EXT_debug_utils",
        "external_memory",
        "dedicated_allocation",
        "external_semaphore",
        "external_fence",
        "VK_ANDROID_external_memory_android_hardware_buffer",
        "yuv_ahardwarebuffer_import",
        "raw10_ahardwarebuffer_import",
        "raw_sensor_ahardwarebuffer_import",
    };
    snapshot.records.reserve(requiredKeys.size());
    for (const auto& key : requiredKeys) {
        snapshot.records.push_back({key, CapabilityStatus::NOT_SCANNED, "", "runtime_not_initialized"});
    }
    snapshot.missingRequirements = {
        "real_vulkan_instance_device_bootstrap_not_injected",
        "vma_header_may_need_fetch",
        "connected_device_capability_scan_not_run",
    };
    return snapshot;
}

std::string CapabilitySnapshot::toJson() const {
    std::ostringstream out;
    out << '{'
        << "\"schemaVersion\":" << schemaVersion << ','
        << "\"loaderAvailable\":" << json::boolean(loaderAvailable) << ','
        << "\"loaderApiVersion\":" << loaderApiVersion << ','
        << "\"selectedDeviceName\":" << json::quote(selectedDeviceName) << ','
        << "\"vendorId\":" << vendorId << ','
        << "\"deviceId\":" << deviceId << ','
        << "\"deviceApiVersion\":" << deviceApiVersion << ','
        << "\"driverVersion\":" << driverVersion << ','
        << "\"deviceType\":" << json::quote(deviceType) << ','
        << "\"computeQueueFamilyIndex\":" << computeQueueFamilyIndex << ','
        << "\"computeQueueCount\":" << computeQueueCount << ','
        << "\"deviceLocalMemoryBytes\":" << deviceLocalMemoryBytes << ','
        << "\"hostVisibleMemoryBytes\":" << hostVisibleMemoryBytes << ','
        << "\"timestampQueriesSupported\":" << json::boolean(timestampQueriesSupported) << ','
        << "\"timelineSemaphoresSupported\":" << json::boolean(timelineSemaphoresSupported) << ','
        << "\"androidHardwareBufferExtensionSupported\":"
        << json::boolean(androidHardwareBufferExtensionSupported) << ','
        << "\"validationLayerAvailable\":" << json::boolean(validationLayerAvailable) << ','
        << "\"validationLayerEnabled\":" << json::boolean(validationLayerEnabled) << ','
        << "\"debugUtilsAvailable\":" << json::boolean(debugUtilsAvailable) << ','
        << "\"debugUtilsEnabled\":" << json::boolean(debugUtilsEnabled) << ','
        << "\"debugMessengerCreated\":" << json::boolean(debugMessengerCreated) << ','
        << "\"vmaReady\":" << json::boolean(vmaReady) << ','
        << "\"enabledExtensions\":" << json::stringArray(enabledExtensions) << ','
        << "\"enabledFeatures\":" << json::stringArray(enabledFeatures) << ','
        << "\"missingRequirements\":" << json::stringArray(missingRequirements) << ','
        << "\"records\":[";
    for (std::size_t index = 0; index < records.size(); ++index) {
        if (index != 0) out << ',';
        const auto& record = records[index];
        out << '{'
            << "\"key\":" << json::quote(record.key) << ','
            << "\"status\":" << json::quote(toString(record.status)) << ','
            << "\"value\":" << json::quote(record.value) << ','
            << "\"reason\":" << json::quote(record.reason)
            << '}';
    }
    out << "]}";
    return out.str();
}

std::string CapabilitySnapshot::toHumanReadable() const {
    std::ostringstream out;
    out << "VULKAN CAPABILITIES\n"
        << "Schema version: " << schemaVersion << '\n'
        << "Loader available: " << (loaderAvailable ? "true" : "false") << '\n'
        << "Loader API version: " << loaderApiVersion << '\n'
        << "Selected device: " << (selectedDeviceName.empty() ? "not selected" : selectedDeviceName) << '\n'
        << "Device type: " << (deviceType.empty() ? "not scanned" : deviceType) << '\n'
        << "Compute queue family: "
        << (computeQueueFamilyIndex == UINT32_MAX ? "not selected" : std::to_string(computeQueueFamilyIndex)) << '\n'
        << "Compute queue count: " << computeQueueCount << '\n'
        << "VMA ready: " << (vmaReady ? "true" : "false") << '\n'
        << "Validation layer: available=" << (validationLayerAvailable ? "true" : "false")
        << " enabled=" << (validationLayerEnabled ? "true" : "false") << '\n'
        << "Debug utils: available=" << (debugUtilsAvailable ? "true" : "false")
        << " enabled=" << (debugUtilsEnabled ? "true" : "false")
        << " messenger=" << (debugMessengerCreated ? "created" : "none") << '\n'
        << "Enabled extensions: " << enabledExtensions.size() << '\n'
        << "Enabled features: " << enabledFeatures.size() << '\n'
        << "Missing requirements: " << missingRequirements.size() << "\n\n";
    for (const auto& record : records) {
        out << record.key << ": " << toString(record.status);
        if (!record.value.empty()) out << " | value=" << record.value;
        if (!record.reason.empty()) out << " | reason=" << record.reason;
        out << '\n';
    }
    return out.str();
}

std::string RuntimeSnapshot::toJson() const {
    std::ostringstream out;
    out << '{'
        << "\"schemaVersion\":" << schemaVersion << ','
        << "\"state\":" << json::quote(toString(state)) << ','
        << "\"runtimeIdentity\":" << json::quote(runtimeIdentity) << ','
        << "\"loaderAvailable\":" << json::boolean(loaderAvailable) << ','
        << "\"runtimeInitialized\":" << json::boolean(runtimeInitialized) << ','
        << "\"selectedDeviceName\":" << json::quote(selectedDeviceName) << ','
        << "\"enabledExtensions\":" << json::stringArray(enabledExtensions) << ','
        << "\"enabledFeatures\":" << json::stringArray(enabledFeatures) << ','
        << "\"missingRequirements\":" << json::stringArray(missingRequirements) << ','
        << "\"activeProductionStages\":" << json::stringArray(activeProductionStages) << ','
        << "\"instanceCreationCount\":" << instanceCreationCount << ','
        << "\"deviceCreationCount\":" << deviceCreationCount << ','
        << "\"initializeRequestCount\":" << initializeRequestCount << ','
        << "\"shutdownRequestCount\":" << shutdownRequestCount << ','
        << "\"inFlightSubmissionCount\":" << inFlightSubmissionCount << ','
        << "\"lastFailure\":{"
        << "\"code\":" << json::quote(lastFailure.code) << ','
        << "\"message\":" << json::quote(lastFailure.message) << ','
        << "\"retryable\":" << json::boolean(lastFailure.retryable)
        << "}}";
    return out.str();
}

std::string RuntimeSnapshot::toHumanReadable() const {
    std::ostringstream out;
    out << "VULKAN RUNTIME\n"
        << "Schema version: " << schemaVersion << '\n'
        << "State: " << toString(state) << '\n'
        << "Runtime identity: " << runtimeIdentity << '\n'
        << "Loader available: " << (loaderAvailable ? "true" : "false") << '\n'
        << "Runtime initialized: " << (runtimeInitialized ? "true" : "false") << '\n'
        << "Selected device: " << (selectedDeviceName.empty() ? "none" : selectedDeviceName) << '\n'
        << "Instance creation count: " << instanceCreationCount << '\n'
        << "Device creation count: " << deviceCreationCount << '\n'
        << "Initialize requests: " << initializeRequestCount << '\n'
        << "Shutdown requests: " << shutdownRequestCount << '\n'
        << "In-flight submissions: " << inFlightSubmissionCount << '\n'
        << "Active production stages: "
        << (activeProductionStages.empty() ? "none" : std::to_string(activeProductionStages.size())) << '\n';
    if (!lastFailure.empty()) {
        out << "Last failure: " << lastFailure.code << " | " << lastFailure.message
            << " | retryable=" << (lastFailure.retryable ? "true" : "false") << '\n';
    }
    return out.str();
}

}  // namespace bncam::vulkan
