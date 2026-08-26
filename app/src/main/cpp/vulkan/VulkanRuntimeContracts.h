#pragma once

#include "VulkanRuntimeState.h"

#include <cstdint>
#include <string>
#include <vector>

namespace bncam::vulkan {

constexpr std::int32_t kRuntimeSchemaVersion = 1;

struct RuntimeConfig {
    bool debugValidationRequested = false;
    bool requireAndroidHardwareBuffer = true;
    std::string pipelineCachePath;
};

struct RuntimeFailure {
    std::string code;
    std::string message;
    bool retryable = false;

    bool empty() const noexcept { return code.empty() && message.empty(); }
};

struct CapabilityRecord {
    std::string key;
    CapabilityStatus status = CapabilityStatus::NOT_SCANNED;
    std::string value;
    std::string reason;
};

struct CapabilitySnapshot {
    std::int32_t schemaVersion = kRuntimeSchemaVersion;
    bool loaderAvailable = false;
    std::uint32_t loaderApiVersion = 0;
    std::string selectedDeviceName;
    std::uint32_t vendorId = 0;
    std::uint32_t deviceId = 0;
    std::uint32_t deviceApiVersion = 0;
    std::uint32_t driverVersion = 0;
    std::string deviceType;
    std::uint32_t computeQueueFamilyIndex = UINT32_MAX;
    std::uint32_t computeQueueCount = 0;
    std::uint64_t deviceLocalMemoryBytes = 0;
    std::uint64_t hostVisibleMemoryBytes = 0;
    bool timestampQueriesSupported = false;
    bool timelineSemaphoresSupported = false;
    bool androidHardwareBufferExtensionSupported = false;
    bool validationLayerAvailable = false;
    bool validationLayerEnabled = false;
    bool debugUtilsAvailable = false;
    bool debugUtilsEnabled = false;
    bool debugMessengerCreated = false;
    bool vmaReady = false;
    std::vector<std::string> enabledExtensions;
    std::vector<std::string> enabledFeatures;
    std::vector<std::string> missingRequirements;
    std::vector<CapabilityRecord> records;

    static CapabilitySnapshot notScanned();
    std::string toJson() const;
    std::string toHumanReadable() const;
};

struct RuntimeSnapshot {
    std::int32_t schemaVersion = kRuntimeSchemaVersion;
    RuntimeState state = RuntimeState::UNINITIALIZED;
    std::string runtimeIdentity;
    bool loaderAvailable = false;
    bool runtimeInitialized = false;
    std::string selectedDeviceName;
    std::vector<std::string> enabledExtensions;
    std::vector<std::string> enabledFeatures;
    std::vector<std::string> missingRequirements;
    std::vector<std::string> activeProductionStages;
    std::uint64_t instanceCreationCount = 0;
    std::uint64_t deviceCreationCount = 0;
    std::uint64_t initializeRequestCount = 0;
    std::uint64_t shutdownRequestCount = 0;
    std::uint64_t inFlightSubmissionCount = 0;
    RuntimeFailure lastFailure;

    std::string toJson() const;
    std::string toHumanReadable() const;
};

}  // namespace bncam::vulkan
