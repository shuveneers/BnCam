#include "VulkanRawStagingManager.h"

#include <cassert>
#include <iostream>

using namespace bncam::vulkan;

int main() {
    VulkanRawStagingManager stagingMgr;
    assert(stagingMgr.getPoolAllocationCount() == 0);
    assert(stagingMgr.getPoolReuseCount() == 0);
    assert(stagingMgr.getNativeLockCount() == 0);
    assert(stagingMgr.getNativeUnlockCount() == 0);

    VulkanAllocatorOwner allocatorOwner;

    // Null pointer stageRawSensorMemory handles invalid arguments gracefully
    RawStagingResult nullResult = stagingMgr.stageRawSensorMemory(
        VK_NULL_HANDLE, allocatorOwner, VK_NULL_HANDLE, VK_NULL_HANDLE,
        nullptr, 4096, 3072, 8192, 2001L
    );

    assert(!nullResult.success);
    assert(nullResult.diagnostics.identity.state == ResourceState::FAILED);
    assert(!nullResult.failureReason.empty());

    std::cout << "Vulkan RAW staging manager host test PASS\n";
    return 0;
}
