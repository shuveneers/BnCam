#include "VulkanComputePipelineManager.h"

#include <cassert>
#include <iostream>

using namespace bncam::vulkan;

int main() {
    VulkanComputePipelineManager pipeMgr;
    assert(!pipeMgr.isInitialized());
    assert(pipeMgr.getPipelineCreationCount() == 0);
    assert(pipeMgr.getPipelineReuseCount() == 0);

    // Null device handles initialization gracefully
    assert(!pipeMgr.initializePipelines(VK_NULL_HANDLE));

    VulkanAllocatorOwner allocatorOwner;

    // Uninitialized pipeline returns typed failure
    ComputeExecutionResult result10 = pipeMgr.executeRaw10Unpack(
        VK_NULL_HANDLE, allocatorOwner, VK_NULL_HANDLE, VK_NULL_HANDLE,
        VK_NULL_HANDLE, 15728640, 4096, 3072, 5120, 3001L
    );

    assert(!result10.success);
    assert(result10.diagnostics.stageId == "RAW10_UNPACK");
    assert(!result10.failureReason.empty());

    ComputeExecutionResult result16 = pipeMgr.executeRaw16Canonicalize(
        VK_NULL_HANDLE, allocatorOwner, VK_NULL_HANDLE, VK_NULL_HANDLE,
        VK_NULL_HANDLE, 25165824, 4096, 3072, 8192, 3002L
    );

    assert(!result16.success);
    assert(result16.diagnostics.stageId == "RAW16_CANONICALIZE");
    assert(!result16.failureReason.empty());

    std::cout << "Vulkan compute pipeline host test PASS\n";
    return 0;
}
