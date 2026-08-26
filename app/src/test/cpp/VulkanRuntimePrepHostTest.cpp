#include "VulkanRuntime.h"

#include <cassert>
#include <atomic>
#include <cstdint>
#include <iostream>
#include <thread>
#include <vector>

using namespace bncam::vulkan;

namespace {
template <typename Handle>
Handle fakeHandle(std::uintptr_t value) {
    return reinterpret_cast<Handle>(value);
}
}

int main() {
    // Raw handle ownership must transfer rather than duplicate scalar Vulkan handles.
    OwnedRuntimeHandles source;
    source.instance = fakeHandle<VkInstance>(1);
    source.physicalDevice = fakeHandle<VkPhysicalDevice>(2);
    source.device = fakeHandle<VkDevice>(3);
    source.computeQueue = fakeHandle<VkQueue>(4);
    source.computeQueueFamilyIndex = 7;
    source.commandPool = fakeHandle<VkCommandPool>(5);
    source.descriptorPool = fakeHandle<VkDescriptorPool>(6);
    source.pipelineCache = fakeHandle<VkPipelineCache>(7);

    OwnedRuntimeHandles moved(std::move(source));
    assert(source.empty());
    assert(moved.instance != VK_NULL_HANDLE);
    assert(!moved.complete());  // VMA is intentionally unavailable in the host preparation build.

    // Clear the fake handles without invoking real Vulkan destruction.
    moved.instance = VK_NULL_HANDLE;
    moved.physicalDevice = VK_NULL_HANDLE;
    moved.device = VK_NULL_HANDLE;
    moved.computeQueue = VK_NULL_HANDLE;
    moved.computeQueueFamilyIndex = UINT32_MAX;
    moved.commandPool = VK_NULL_HANDLE;
    moved.descriptorPool = VK_NULL_HANDLE;
    moved.pipelineCache = VK_NULL_HANDLE;
    assert(moved.empty());

    VulkanRuntime& runtime = VulkanRuntime::instance();
    assert(runtime.snapshot().state == RuntimeState::UNINITIALIZED);

    RuntimeConfig config;
    config.debugValidationRequested = true;
    config.requireAndroidHardwareBuffer = true;

    constexpr int kConcurrentInitializeRequests = 8;
    std::atomic<bool> start{false};
    std::vector<std::thread> threads;
    std::vector<RuntimeSnapshot> results(kConcurrentInitializeRequests);
    threads.reserve(kConcurrentInitializeRequests);
    for (int index = 0; index < kConcurrentInitializeRequests; ++index) {
        threads.emplace_back([&, index] {
            while (!start.load(std::memory_order_acquire)) std::this_thread::yield();
            results[index] = runtime.initialize(config);
        });
    }
    start.store(true, std::memory_order_release);
    for (auto& thread : threads) thread.join();

    for (const auto& unavailable : results) {
        assert(unavailable.state == RuntimeState::UNAVAILABLE);
        assert(!unavailable.runtimeInitialized);
        assert(unavailable.activeProductionStages.empty());
        assert(unavailable.instanceCreationCount == 0);
        assert(unavailable.deviceCreationCount == 0);
        assert(unavailable.lastFailure.code == "VULKAN_LOADER_UNAVAILABLE");
    }
    const RuntimeSnapshot afterConcurrentInitialize = runtime.snapshot();
    assert(afterConcurrentInitialize.initializeRequestCount == kConcurrentInitializeRequests);

    const RuntimeSnapshot unavailableAgain = runtime.initialize(config);
    assert(unavailableAgain.state == RuntimeState::UNAVAILABLE);
    assert(unavailableAgain.initializeRequestCount == kConcurrentInitializeRequests + 1);
    assert(unavailableAgain.instanceCreationCount == 0);
    assert(unavailableAgain.deviceCreationCount == 0);

    // Test partial handle cleanup directly on bootstrap destroy
    OwnedRuntimeHandles partial;
    partial.instance = fakeHandle<VkInstance>(42);
    const RuntimeFailure partialDestroyResult = VulkanRuntimeBootstrap::destroy(partial);
    assert(partialDestroyResult.empty());
    assert(partial.empty());

    const RuntimeSnapshot destroyed = runtime.shutdown();
    assert(destroyed.state == RuntimeState::DESTROYED);
    assert(destroyed.inFlightSubmissionCount == 0);

    const RuntimeSnapshot destroyedAgain = runtime.shutdown();
    assert(destroyedAgain.state == RuntimeState::DESTROYED);

    const RuntimeSnapshot rejected = runtime.initialize(config);
    assert(rejected.state == RuntimeState::DESTROYED);
    assert(rejected.lastFailure.code == "RUNTIME_ALREADY_DESTROYED");

    std::cout << "Vulkan runtime host test PASS\n";
    return 0;
}
