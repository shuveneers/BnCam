#include "VulkanYuvImporter.h"

#include <cassert>
#include <iostream>

using namespace bncam::vulkan;

int main() {
    VulkanYuvImporter importer;
    assert(importer.getPoolAllocationCount() == 0);
    assert(importer.getPoolReuseCount() == 0);
    assert(importer.getReleaseCount() == 0);

    // Null buffer import returns typed failure without crash
    YuvImportResult nullResult = importer.importYuvBuffer(
        VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE,
        VK_NULL_HANDLE, VK_NULL_HANDLE, nullptr, 1001L, -1
    );

    assert(!nullResult.success);
    assert(nullResult.diagnostics.identity.state == ResourceState::FAILED);
    assert(!nullResult.failureReason.empty());

    std::cout << "Vulkan YUV importer host test PASS\n";
    return 0;
}
