#include "VulkanResourceContracts.h"

namespace bncam::vulkan {

const char* toString(ResourceType type) noexcept {
    switch (type) {
        case ResourceType::EXTERNAL_AHARDWAREBUFFER: return "EXTERNAL_AHARDWAREBUFFER";
        case ResourceType::VMA_OWNED_BUFFER: return "VMA_OWNED_BUFFER";
        case ResourceType::VMA_OWNED_IMAGE: return "VMA_OWNED_IMAGE";
        case ResourceType::PERSISTENT_STAGING_BUFFER: return "PERSISTENT_STAGING_BUFFER";
        case ResourceType::BORROWED_CAPTURE_RESOURCE: return "BORROWED_CAPTURE_RESOURCE";
    }
    return "BORROWED_CAPTURE_RESOURCE";
}

const char* toString(ResourceState state) noexcept {
    switch (state) {
        case ResourceState::ACQUIRED: return "ACQUIRED";
        case ResourceState::IMPORTING: return "IMPORTING";
        case ResourceState::VULKAN_READY: return "VULKAN_READY";
        case ResourceState::IN_FLIGHT: return "IN_FLIGHT";
        case ResourceState::RELEASE_PENDING: return "RELEASE_PENDING";
        case ResourceState::RELEASED: return "RELEASED";
        case ResourceState::FAILED: return "FAILED";
    }
    return "FAILED";
}

const char* toString(ImportPath path) noexcept {
    switch (path) {
        case ImportPath::DIRECT_AHARDWAREBUFFER_IMPORT: return "DIRECT_AHARDWAREBUFFER_IMPORT";
        case ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING: return "NATIVE_LOCK_TO_PERSISTENT_STAGING";
        case ImportPath::UNSUPPORTED: return "UNSUPPORTED";
    }
    return "UNSUPPORTED";
}

SourceImportDecision resolveSourceImportDecision(
    const std::string& sourceName,
    std::uint32_t androidFormat,
    bool ahbExtensionSupported,
    bool externalMemorySupported,
    bool dedicatedAllocSupported,
    bool formatPropertiesQueried
) {
    SourceImportDecision decision{};
    decision.sourceName = sourceName;
    decision.androidFormat = androidFormat;
    decision.requiredAcquireFence = true;
    decision.requiredReleaseFence = true;

    if (sourceName == "YUV") {
        decision.ownership = ResourceType::EXTERNAL_AHARDWAREBUFFER;
        decision.fallbackPath = ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING;
        if (ahbExtensionSupported && externalMemorySupported) {
            decision.directImportEligible = true;
            decision.resolvedPath = ImportPath::DIRECT_AHARDWAREBUFFER_IMPORT;
            decision.knownBlocker = "";
        } else {
            decision.directImportEligible = false;
            decision.resolvedPath = ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING;
            decision.knownBlocker = "AHB external memory extension or Vulkan 1.1 external memory core unsupported";
        }
    } else if (sourceName == "RAW10") {
        decision.ownership = ResourceType::EXTERNAL_AHARDWAREBUFFER;
        decision.fallbackPath = ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING;
        if (ahbExtensionSupported && externalMemorySupported && formatPropertiesQueried) {
            decision.directImportEligible = true;
            decision.resolvedPath = ImportPath::DIRECT_AHARDWAREBUFFER_IMPORT;
            decision.knownBlocker = "";
        } else {
            decision.directImportEligible = false;
            decision.resolvedPath = ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING;
            decision.knownBlocker = "Packed RAW10 direct AHB VkImage import requires format property query verification";
        }
    } else if (sourceName == "RAW_SENSOR") {
        decision.ownership = ResourceType::PERSISTENT_STAGING_BUFFER;
        decision.directImportEligible = false;
        decision.resolvedPath = ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING;
        decision.fallbackPath = ImportPath::UNSUPPORTED;
        decision.knownBlocker = "RAW_SENSOR uses native lock to persistent VMA staging buffer";
    } else {
        decision.ownership = ResourceType::BORROWED_CAPTURE_RESOURCE;
        decision.directImportEligible = false;
        decision.resolvedPath = ImportPath::UNSUPPORTED;
        decision.fallbackPath = ImportPath::UNSUPPORTED;
        decision.knownBlocker = "Unknown capture source";
    }

    return decision;
}

}  // namespace bncam::vulkan
