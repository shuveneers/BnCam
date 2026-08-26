#pragma once

#include <cstdint>

namespace bncam::vulkan {

enum class RuntimeState : std::int32_t {
    UNINITIALIZED = 0,
    INITIALIZING = 1,
    READY = 2,
    UNAVAILABLE = 3,
    FAILED = 4,
    SHUTTING_DOWN = 5,
    DESTROYED = 6,
    GPU_STALLED = 7,
    QUARANTINED = 8,
};

inline const char* toString(RuntimeState state) noexcept {
    switch (state) {
        case RuntimeState::UNINITIALIZED: return "UNINITIALIZED";
        case RuntimeState::INITIALIZING: return "INITIALIZING";
        case RuntimeState::READY: return "READY";
        case RuntimeState::UNAVAILABLE: return "UNAVAILABLE";
        case RuntimeState::FAILED: return "FAILED";
        case RuntimeState::SHUTTING_DOWN: return "SHUTTING_DOWN";
        case RuntimeState::DESTROYED: return "DESTROYED";
        case RuntimeState::GPU_STALLED: return "GPU_STALLED";
        case RuntimeState::QUARANTINED: return "QUARANTINED";
    }
    return "FAILED";
}

enum class CapabilityStatus : std::int32_t {
    AVAILABLE = 0,
    SUPPORTED = 1,
    ENABLED = 2,
    REQUIRED_LATER = 3,
    MISSING = 4,
    NOT_SCANNED = 5,
    SUPPORTED_BY_CORE_VERSION = 6,
    SUPPORTED_BY_EXTENSION = 7,
    ENABLED_AS_CORE_FEATURE = 8,
    ENABLED_AS_EXTENSION = 9,
    SUPPORTED_NOT_ENABLED = 10,
};

inline const char* toString(CapabilityStatus status) noexcept {
    switch (status) {
        case CapabilityStatus::AVAILABLE: return "AVAILABLE";
        case CapabilityStatus::SUPPORTED: return "SUPPORTED";
        case CapabilityStatus::ENABLED: return "ENABLED";
        case CapabilityStatus::REQUIRED_LATER: return "REQUIRED_LATER";
        case CapabilityStatus::MISSING: return "MISSING";
        case CapabilityStatus::NOT_SCANNED: return "NOT_SCANNED";
        case CapabilityStatus::SUPPORTED_BY_CORE_VERSION: return "SUPPORTED_BY_CORE_VERSION";
        case CapabilityStatus::SUPPORTED_BY_EXTENSION: return "SUPPORTED_BY_EXTENSION";
        case CapabilityStatus::ENABLED_AS_CORE_FEATURE: return "ENABLED_AS_CORE_FEATURE";
        case CapabilityStatus::ENABLED_AS_EXTENSION: return "ENABLED_AS_EXTENSION";
        case CapabilityStatus::SUPPORTED_NOT_ENABLED: return "SUPPORTED_NOT_ENABLED";
    }
    return "NOT_SCANNED";
}

}  // namespace bncam::vulkan
