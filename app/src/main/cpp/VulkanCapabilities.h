#pragma once
#include <cstdint>
#include <string>

enum class GpuClassification {
    GPU_COMPUTE_UNSUPPORTED,
    GPU_COMPUTE_SUPPORTED_WITH_COPY,
    GPU_COMPUTE_SUPPORTED_WITH_AHARDWAREBUFFER_IMPORT,
    GPU_COMPUTE_SUPPORTED_WITH_ZERO_COPY_CANDIDATE
};

struct VulkanCapabilityReport {
    bool isAvailable = false;
    uint32_t apiVersion = 0;
    std::string deviceName;
    bool hasComputeQueue = false;
    bool timestampQueriesSupported = false;
    float timestampPeriodNs = 0.0f;
    uint32_t maxWorkgroupCount[3] = {0, 0, 0};
    uint32_t maxWorkgroupSize[3] = {0, 0, 0};
    uint32_t maxWorkgroupInvocations = 0;
    uint64_t maxStorageBufferRange = 0;
    uint32_t maxStorageImageDim2D = 0;
    uint64_t deviceLocalMemoryBytes = 0;
    uint64_t hostVisibleMemoryBytes = 0;
    bool hostCoherentSupported = false;
    bool shaderInt16Supported = false;
    bool shaderFloat16Supported = false;
    bool storage8BitSupported = false;
    bool storage16BitSupported = false;
    bool subgroupSupported = false;
    uint32_t subgroupSize = 0;
    bool hardwareBufferImportSupported = false;
    bool timelineSemaphoresSupported = false;

    // AHardwareBuffer Zero-Copy Proof metrics
    bool raw10AHardwareBufferImportable = false;
    bool rawSensorAHardwareBufferImportable = false;
    bool yuvAHardwareBufferImportable = false;
    bool shaderConsumedImportedPayloadDirectly = false;
    bool downstreamReadbackRequired = true;
    bool trueEndToEndZeroCopyAvoided = false;

    GpuClassification classification = GpuClassification::GPU_COMPUTE_UNSUPPORTED;
    std::string summaryJson;
};

class VulkanCapabilities {
public:
    static VulkanCapabilityReport queryCapabilities();
};
