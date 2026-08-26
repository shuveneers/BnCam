#include "VulkanResourceContracts.h"

#include <cassert>
#include <iostream>
#include <string>

using namespace bncam::vulkan;

int main() {
    assert(std::string(toString(ResourceType::EXTERNAL_AHARDWAREBUFFER)) == "EXTERNAL_AHARDWAREBUFFER");
    assert(std::string(toString(ResourceType::VMA_OWNED_BUFFER)) == "VMA_OWNED_BUFFER");
    assert(std::string(toString(ResourceType::VMA_OWNED_IMAGE)) == "VMA_OWNED_IMAGE");
    assert(std::string(toString(ResourceType::PERSISTENT_STAGING_BUFFER)) == "PERSISTENT_STAGING_BUFFER");
    assert(std::string(toString(ResourceType::BORROWED_CAPTURE_RESOURCE)) == "BORROWED_CAPTURE_RESOURCE");

    assert(std::string(toString(ResourceState::ACQUIRED)) == "ACQUIRED");
    assert(std::string(toString(ResourceState::IMPORTING)) == "IMPORTING");
    assert(std::string(toString(ResourceState::VULKAN_READY)) == "VULKAN_READY");
    assert(std::string(toString(ResourceState::IN_FLIGHT)) == "IN_FLIGHT");
    assert(std::string(toString(ResourceState::RELEASE_PENDING)) == "RELEASE_PENDING");
    assert(std::string(toString(ResourceState::RELEASED)) == "RELEASED");
    assert(std::string(toString(ResourceState::FAILED)) == "FAILED");

    assert(std::string(toString(ImportPath::DIRECT_AHARDWAREBUFFER_IMPORT)) == "DIRECT_AHARDWAREBUFFER_IMPORT");
    assert(std::string(toString(ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING)) == "NATIVE_LOCK_TO_PERSISTENT_STAGING");
    assert(std::string(toString(ImportPath::UNSUPPORTED)) == "UNSUPPORTED");

    // YUV Decision
    const auto yuvDecision = resolveSourceImportDecision("YUV", 35, true, true, true, true);
    assert(yuvDecision.directImportEligible);
    assert(yuvDecision.resolvedPath == ImportPath::DIRECT_AHARDWAREBUFFER_IMPORT);
    assert(yuvDecision.ownership == ResourceType::EXTERNAL_AHARDWAREBUFFER);

    // RAW10 Decision
    const auto raw10Decision = resolveSourceImportDecision("RAW10", 37, true, true, true, true);
    assert(raw10Decision.directImportEligible);
    assert(raw10Decision.resolvedPath == ImportPath::DIRECT_AHARDWAREBUFFER_IMPORT);

    // RAW10 without format property query -> Fallback to NATIVE_LOCK_TO_PERSISTENT_STAGING
    const auto raw10Fallback = resolveSourceImportDecision("RAW10", 37, true, true, true, false);
    assert(!raw10Fallback.directImportEligible);
    assert(raw10Fallback.resolvedPath == ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING);

    // RAW_SENSOR Decision -> Always NATIVE_LOCK_TO_PERSISTENT_STAGING
    const auto rawSensorDecision = resolveSourceImportDecision("RAW_SENSOR", 32, true, true, true, true);
    assert(!rawSensorDecision.directImportEligible);
    assert(rawSensorDecision.resolvedPath == ImportPath::NATIVE_LOCK_TO_PERSISTENT_STAGING);
    assert(rawSensorDecision.ownership == ResourceType::PERSISTENT_STAGING_BUFFER);

    std::cout << "Vulkan resource contract host test PASS\n";
    return 0;
}
