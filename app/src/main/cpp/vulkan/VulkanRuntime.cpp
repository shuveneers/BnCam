#include "VulkanRuntime.h"
#include "VulkanJson.h"
#include "VulkanVmaIntegration.h"

#ifndef BNCAM_VMA_HEADER_AVAILABLE
#define BNCAM_VMA_HEADER_AVAILABLE 0
#endif
#if BNCAM_VMA_HEADER_AVAILABLE
#include "vk_mem_alloc.h"
#endif

#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <sstream>
#include <thread>
#include <vector>
#include <utility>

#ifndef BNCAM_VULKAN_VALIDATION_ENABLED
#define BNCAM_VULKAN_VALIDATION_ENABLED 0
#endif

namespace bncam::vulkan {
namespace {
std::atomic<std::uint64_t> gRuntimeSerial{0};

std::uint64_t epochMilliseconds() {
    return static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count()
    );
}

bool readbackNeuralStageFp32(
        VkDevice device,
        VkQueue queue,
        VkCommandPool commandPool,
        VulkanAllocatorOwner& allocatorOwner,
        std::mutex& queueMutex,
        VkBuffer source,
        std::uint64_t bytes,
        std::vector<float>& output,
        float& elapsedOutMs,
        std::string& failureReason) noexcept {
    const auto started = std::chrono::steady_clock::now();
    elapsedOutMs = 0.0f;
    output.clear();
    if (device == VK_NULL_HANDLE || queue == VK_NULL_HANDLE || commandPool == VK_NULL_HANDLE ||
        source == VK_NULL_HANDLE || bytes == 0u || (bytes % sizeof(float)) != 0u) {
        failureReason = "NEURAL_STAGE_DUMP_INVALID_INPUT";
        return false;
    }
#if !BNCAM_VMA_HEADER_AVAILABLE
    (void)allocatorOwner;
    (void)queueMutex;
    failureReason = "NEURAL_STAGE_DUMP_VMA_UNAVAILABLE";
    return false;
#else
    VmaAllocator allocator = allocatorOwner.handle();
    if (allocator == nullptr) {
        failureReason = "NEURAL_STAGE_DUMP_ALLOCATOR_UNAVAILABLE";
        return false;
    }

    VkBuffer staging = VK_NULL_HANDLE;
    VmaAllocation allocation = nullptr;
    VmaAllocationInfo allocationInfo{};
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = static_cast<VkDeviceSize>(bytes);
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VmaAllocationCreateInfo createInfo{};
    createInfo.usage = VMA_MEMORY_USAGE_AUTO_PREFER_HOST;
    createInfo.flags = VMA_ALLOCATION_CREATE_MAPPED_BIT |
            VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT;
    const VkResult allocationResult = vmaCreateBuffer(
            allocator, &bufferInfo, &createInfo, &staging, &allocation, &allocationInfo);
    if (allocationResult != VK_SUCCESS || staging == VK_NULL_HANDLE ||
        allocation == nullptr || allocationInfo.pMappedData == nullptr) {
        if (staging != VK_NULL_HANDLE && allocation != nullptr) {
            vmaDestroyBuffer(allocator, staging, allocation);
        }
        failureReason = "NEURAL_STAGE_DUMP_STAGING_ALLOCATION_FAILED_" +
                std::to_string(allocationResult);
        return false;
    }

    VkCommandBuffer command = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    bool ok = false;
    do {
        VkCommandBufferAllocateInfo allocCommand{};
        allocCommand.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocCommand.commandPool = commandPool;
        allocCommand.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocCommand.commandBufferCount = 1u;
        if (vkAllocateCommandBuffers(device, &allocCommand, &command) != VK_SUCCESS) {
            failureReason = "NEURAL_STAGE_DUMP_COMMAND_ALLOC_FAILED";
            break;
        }

        VkCommandBufferBeginInfo begin{};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(command, &begin) != VK_SUCCESS) {
            failureReason = "NEURAL_STAGE_DUMP_COMMAND_BEGIN_FAILED";
            break;
        }

        VkBufferMemoryBarrier sourceReady{};
        sourceReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        sourceReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        sourceReady.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        sourceReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sourceReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        sourceReady.buffer = source;
        sourceReady.offset = 0u;
        sourceReady.size = static_cast<VkDeviceSize>(bytes);
        vkCmdPipelineBarrier(
                command,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0u, 0u, nullptr, 1u, &sourceReady, 0u, nullptr);

        VkBufferCopy copy{};
        copy.size = static_cast<VkDeviceSize>(bytes);
        vkCmdCopyBuffer(command, source, staging, 1u, &copy);

        VkBufferMemoryBarrier hostReady{};
        hostReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        hostReady.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        hostReady.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        hostReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        hostReady.buffer = staging;
        hostReady.offset = 0u;
        hostReady.size = static_cast<VkDeviceSize>(bytes);
        vkCmdPipelineBarrier(
                command,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_HOST_BIT,
                0u, 0u, nullptr, 1u, &hostReady, 0u, nullptr);

        if (vkEndCommandBuffer(command) != VK_SUCCESS) {
            failureReason = "NEURAL_STAGE_DUMP_COMMAND_END_FAILED";
            break;
        }

        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        if (vkCreateFence(device, &fenceInfo, nullptr, &fence) != VK_SUCCESS) {
            failureReason = "NEURAL_STAGE_DUMP_FENCE_CREATE_FAILED";
            break;
        }
        VkSubmitInfo submit{};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.commandBufferCount = 1u;
        submit.pCommandBuffers = &command;
        {
            std::lock_guard<std::mutex> lock(queueMutex);
            if (vkQueueSubmit(queue, 1u, &submit, fence) != VK_SUCCESS) {
                failureReason = "NEURAL_STAGE_DUMP_SUBMIT_FAILED";
                break;
            }
        }
        const VkResult wait = vkWaitForFences(device, 1u, &fence, VK_TRUE, UINT64_MAX);
        if (wait != VK_SUCCESS) {
            failureReason = "NEURAL_STAGE_DUMP_WAIT_FAILED_" + std::to_string(wait);
            break;
        }
        vmaInvalidateAllocation(allocator, allocation, 0u, static_cast<VkDeviceSize>(bytes));
        output.resize(static_cast<std::size_t>(bytes / sizeof(float)));
        std::memcpy(output.data(), allocationInfo.pMappedData, static_cast<std::size_t>(bytes));
        failureReason = "none";
        ok = true;
    } while (false);

    if (fence != VK_NULL_HANDLE) {
        vkDestroyFence(device, fence, nullptr);
    }
    if (command != VK_NULL_HANDLE) {
        vkFreeCommandBuffers(device, commandPool, 1u, &command);
    }
    vmaDestroyBuffer(allocator, staging, allocation);
    elapsedOutMs = std::chrono::duration<float, std::milli>(
            std::chrono::steady_clock::now() - started).count();
    if (!ok) output.clear();
    return ok;
#endif
}
}  // namespace

VulkanRuntime& VulkanRuntime::instance() {
    // Function-local static gives one authoritative process lifetime owner and is thread-safe since
    // C++11. Camera/session code must never instantiate another runtime.
    static VulkanRuntime runtime;
    return runtime;
}

VulkanRuntime::VulkanRuntime()
    : runtimeIdentity_(createRuntimeIdentity()),
      validationCollector_(256, 1024, 16) {
}

std::string VulkanRuntime::createRuntimeIdentity() {
    const auto serial = ++gRuntimeSerial;
    std::ostringstream out;
    out << "bncam-vulkan-runtime-" << epochMilliseconds() << '-' << serial;
    return out.str();
}

void VulkanRuntime::recordLifecycleEventLocked(
    const std::string& event,
    const std::string& detail
) {
    constexpr std::size_t kMaxLifecycleEvents = 64;
    if (lifecycleEvents_.size() >= kMaxLifecycleEvents) {
        lifecycleEvents_.erase(lifecycleEvents_.begin());
    }
    lifecycleEvents_.push_back({epochMilliseconds(), event, detail});
}

RuntimeSnapshot VulkanRuntime::initialize(const RuntimeConfig& config) noexcept {
    std::unique_lock<std::mutex> lock(mutex_);
    ++initializeRequestCount_;

    while (state_ == RuntimeState::INITIALIZING || state_ == RuntimeState::SHUTTING_DOWN) {
        stateChanged_.wait(lock);
    }

    if (state_ == RuntimeState::READY) {
        recordLifecycleEventLocked("initialize_idempotent", "runtime_already_ready");
        return snapshotLocked();
    }
    if (state_ == RuntimeState::DESTROYED) {
        lastFailure_ = {
            "RUNTIME_ALREADY_DESTROYED",
            "A destroyed process-scoped Vulkan runtime cannot be reinitialized.",
            false,
        };
        recordLifecycleEventLocked("initialize_rejected", lastFailure_.code);
        return snapshotLocked();
    }
    if (state_ == RuntimeState::UNAVAILABLE || state_ == RuntimeState::FAILED) {
        recordLifecycleEventLocked(
            "initialize_idempotent_terminal",
            "explicit_process_restart_required_for_retry"
        );
        return snapshotLocked();
    }
    if (!handles_.empty()) {
        lastFailure_ = {
            "RUNTIME_OWNERSHIP_NOT_EMPTY",
            "Initialization was rejected because previous Vulkan ownership was not destroyed.",
            false,
        };
        state_ = RuntimeState::FAILED;
        recordLifecycleEventLocked("initialize_rejected", lastFailure_.code);
        return snapshotLocked();
    }

    RuntimeConfig effectiveConfig = config;
#if !BNCAM_VULKAN_VALIDATION_ENABLED
    effectiveConfig.debugValidationRequested = false;
#endif
    config_ = effectiveConfig;
    state_ = RuntimeState::INITIALIZING;
    lastFailure_ = {};
    recordLifecycleEventLocked("initialize_requested", "authoritative_runtime");
    lock.unlock();

    BootstrapResult result;
    try {
        result = VulkanRuntimeBootstrap::initialize(effectiveConfig, validationCollector_);
    } catch (...) {
        result.success = false;
        result.unavailable = false;
        result.failure = {
            "VULKAN_BOOTSTRAP_EXCEPTION",
            "Unexpected native exception escaped the Vulkan bootstrap implementation.",
            false,
        };
    }

    OwnedRuntimeHandles candidate = std::move(result.handles);
    const bool candidateComplete = candidate.complete() &&
        result.capabilities.loaderAvailable &&
        !result.capabilities.selectedDeviceName.empty();
    const bool candidateHadInstance = candidate.instance != VK_NULL_HANDLE;
    const bool candidateHadDevice = candidate.device != VK_NULL_HANDLE;
    RuntimeFailure cleanupFailure;

    // A failed or incomplete bootstrap may still have created partial Vulkan ownership. It must be
    // destroyed before the runtime publishes a terminal state; raw handles are never discarded.
    if ((!result.success || !candidateComplete) && !candidate.empty()) {
        cleanupFailure = VulkanRuntimeBootstrap::destroy(candidate);
    }

    lock.lock();
    capabilities_ = std::move(result.capabilities);
    if (candidateComplete) {
        capabilities_.computeQueueFamilyIndex = candidate.computeQueueFamilyIndex;
        if (capabilities_.computeQueueCount == 0) capabilities_.computeQueueCount = 1;
        capabilities_.vmaReady = candidate.allocator.isReady();
    }
    if (candidateHadInstance) ++instanceCreationCount_;
    if (candidateHadDevice) ++deviceCreationCount_;

    if (result.success && candidateComplete) {
        handles_ = std::move(candidate);
        state_ = RuntimeState::READY;
        recordLifecycleEventLocked("initialize_completed", "READY");
    } else {
        if (result.success) {
            lastFailure_ = {
                "INCOMPLETE_VULKAN_OWNERSHIP",
                "Bootstrap reported success without all mandatory persistent handles and VMA ownership.",
                false,
            };
        } else {
            lastFailure_ = result.failure;
            if (lastFailure_.empty()) {
                lastFailure_ = {
                    "VULKAN_BOOTSTRAP_FAILED_WITHOUT_REASON",
                    "Bootstrap did not succeed and supplied no typed failure reason.",
                    false,
                };
            }
        }
        if (!cleanupFailure.empty()) {
            lastFailure_.message += " Partial ownership cleanup also failed: " +
                cleanupFailure.code + ": " + cleanupFailure.message;
            lastFailure_.retryable = false;
        }
        // If cleanup failed, candidate still owns raw handles. Moving it into the runtime prevents
        // silent loss and blocks retry until controlled shutdown can destroy that ownership.
        if (!candidate.empty()) {
            handles_ = std::move(candidate);
        }
        state_ = result.unavailable && handles_.empty()
            ? RuntimeState::UNAVAILABLE
            : RuntimeState::FAILED;
        recordLifecycleEventLocked("initialize_not_ready", lastFailure_.code);
    }
    stateChanged_.notify_all();
    return snapshotLocked();
}

RuntimeSnapshot VulkanRuntime::shutdown() noexcept {
    // Match neural configure/clear lock ordering: orchestration before runtime state.
    std::unique_lock<std::mutex> neuralLock(neuralOrchestrationMutex_);
    std::unique_lock<std::mutex> lock(mutex_);
    ++shutdownRequestCount_;
    while (state_ == RuntimeState::INITIALIZING) {
        stateChanged_.wait(lock);
    }
    if (state_ == RuntimeState::DESTROYED) return snapshotLocked();

    state_ = RuntimeState::SHUTTING_DOWN;
    recordLifecycleEventLocked("shutdown_requested", "authoritative_runtime");

    // Later submissions use tryRegisterSubmission/completeSubmission. Shutdown never destroys
    // resources while a registered submission is in flight. The bounded wait avoids deadlock.
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (inFlightSubmissionCount_.load(std::memory_order_acquire) != 0 &&
           std::chrono::steady_clock::now() < deadline) {
        lock.unlock();
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
        lock.lock();
    }
    if (inFlightSubmissionCount_.load(std::memory_order_acquire) != 0) {
        lastFailure_ = {
            "VULKAN_SHUTDOWN_IN_FLIGHT_TIMEOUT",
            "Timed out before all registered Vulkan submissions completed; handles were not destroyed.",
            true,
        };
        state_ = RuntimeState::FAILED;
        recordLifecycleEventLocked("shutdown_failed", lastFailure_.code);
        stateChanged_.notify_all();
        return snapshotLocked();
    }

    // RAW preview submissions are intentionally detached from the submitting CPU call in Phase
    // 11A. Drain their slot fences explicitly before destroying any device child resources.
    if (!rawPreviewBackend_.waitForIdle(handles_.device, 500'000'000ull)) {
        lastFailure_ = {
            "RAW_PREVIEW_SHUTDOWN_IN_FLIGHT_TIMEOUT",
            "Timed out waiting for asynchronous RAW preview GPU submissions; handles were not destroyed.",
            true,
        };
        state_ = RuntimeState::FAILED;
        recordLifecycleEventLocked("shutdown_failed", lastFailure_.code);
        stateChanged_.notify_all();
        return snapshotLocked();
    }

    // Milestone 8E: the production-connected SPECTRA pipeline owns device objects and
    // must be destroyed before the authoritative device/pipeline-cache ownership moves.
    spectraTemporalObserverBackend_.destroy(handles_.device);
    spectraResidentDemosaicBackend_.destroy(handles_.device);
    rawPreviewBackend_.destroy(handles_.device);
    rawCaptureBackend_.destroy(handles_.device);
    rawJpegNormalizeBackend_.destroy(handles_.device);
    rawMultiFrameBackend_.destroy(handles_.device);
    yuvMultiFrameBackend_.destroy(handles_.device);
    yuvSingleFrameBackend_.destroy(handles_.device);
    yuvExposureStatisticsBackend_.destroy(handles_.device);
    spectraPass3PlannerBackend_.destroy(handles_.device);
    spectraRawFinalizeBackend_.destroy(handles_.device);
    spectraResidentToneBackend_.destroy(handles_.device);
    // Phase 5: neural device objects must die before VMA/device ownership.
    spectraNeuralRemainingLscBackend_.destroy(handles_.device);
    spectraNeuralProductionBridge_.destroy(handles_.device);
    spectraNeuralRawDenoiseBackend_.destroy();
    spectraNeuralModelAvailable_.store(false, std::memory_order_release);
    OwnedRuntimeHandles handlesToDestroy = std::move(handles_);
    lock.unlock();
    RuntimeFailure destroyFailure = VulkanRuntimeBootstrap::destroy(handlesToDestroy);
    if (destroyFailure.empty() && !handlesToDestroy.empty()) {
        destroyFailure = {
            "VULKAN_DESTROY_LEFT_LIVE_HANDLES",
            "Bootstrap destruction reported success but did not clear all authoritative handles.",
            false,
        };
    }
    lock.lock();
    if (!destroyFailure.empty()) {
        handles_ = std::move(handlesToDestroy);
        lastFailure_ = std::move(destroyFailure);
        state_ = RuntimeState::FAILED;
        recordLifecycleEventLocked("shutdown_failed", lastFailure_.code);
    } else {
        handles_ = {};
        state_ = RuntimeState::DESTROYED;
        recordLifecycleEventLocked("shutdown_completed", "DESTROYED");
    }
    stateChanged_.notify_all();
    return snapshotLocked();
}

bool VulkanRuntime::tryRegisterSubmission() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ != RuntimeState::READY) return false;
    inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
    return true;
}

void VulkanRuntime::completeSubmission() noexcept {
    auto current = inFlightSubmissionCount_.load(std::memory_order_acquire);
    while (current > 0 && !inFlightSubmissionCount_.compare_exchange_weak(
        current, current - 1, std::memory_order_acq_rel, std::memory_order_acquire)) {
    }
}

void VulkanRuntime::markGpuStalled(const std::string& stageName) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    gpuStalled_.store(true, std::memory_order_release);
    if (firstFailingStage_ == "none") {
        firstFailingStage_ = stageName;
    }
    if (state_ == RuntimeState::READY) {
        state_ = RuntimeState::GPU_STALLED;
    }
    recordLifecycleEventLocked("gpu_stalled", stageName);
}

bool VulkanRuntime::isGpuStalled() const noexcept {
    return gpuStalled_.load(std::memory_order_relaxed) ||
           state_ == RuntimeState::GPU_STALLED ||
           state_ == RuntimeState::QUARANTINED;
}

std::string VulkanRuntime::firstFailingStage() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    return firstFailingStage_;
}

void VulkanRuntime::resetShotCircuitBreaker() noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (gpuStalled_.load(std::memory_order_relaxed)) {
        if (inFlightSubmissionCount_.load(std::memory_order_acquire) > 0) {
            // Queue safety cannot be established because a timed-out submission remains in flight.
            // Quarantine the runtime until complete process reinitialization/reconstruction.
            quarantined_.store(true, std::memory_order_release);
            quarantineReason_ = "Unsafe in-flight submission pending at shot reset";
            state_ = RuntimeState::QUARANTINED;
            recordLifecycleEventLocked("quarantined_on_reset", quarantineReason_);
            return;
        }
        gpuStalled_.store(false, std::memory_order_release);
        firstFailingStage_ = "none";
        if (state_ == RuntimeState::GPU_STALLED) {
            state_ = RuntimeState::READY;
        }
    }
}

void VulkanRuntime::quarantineRuntime(const std::string& reason) noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    quarantined_.store(true, std::memory_order_release);
    quarantineReason_ = reason;
    state_ = RuntimeState::QUARANTINED;
    recordLifecycleEventLocked("runtime_quarantined", reason);
}

bool VulkanRuntime::isQuarantined() const noexcept {
    return quarantined_.load(std::memory_order_relaxed) || state_ == RuntimeState::QUARANTINED;
}

SpectraNoiseMapPlannerResult VulkanRuntime::executeRawNoiseMapPlannerFromNormalize(
        const SpectraNoiseMapPlannerRequest& request,
        std::uint64_t rawNormalizeGeneration
) noexcept {
    SpectraNoiseMapPlannerResult rejected{};
    rejected.attempted = true;
    if (rawNormalizeGeneration == 0u || request.residentInputGeneration != 0u ||
        request.frameWidth == 0u || request.frameHeight == 0u ||
        request.gridWidth == 0u || request.gridHeight == 0u) {
        rejected.status = "RAW_NOISE_MAP_NORMALIZE_REQUEST_INVALID";
        rejected.failureReason = "Noise-map handoff requires one opaque normalize generation and valid frame/grid dimensions.";
        return rejected;
    }

    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.status = "VULKAN_RUNTIME_NOT_READY";
            rejected.failureReason = "Authoritative Vulkan runtime is not READY.";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }

    SpectraNoiseMapPlannerResult result{};
    {
        // Producer generation resolution and compact consumer dispatch are one transaction.
        // No VkBuffer or full-frame normalized mosaic escapes VulkanRuntime.
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer normalizedMosaic = VK_NULL_HANDLE;
        std::uint64_t normalizedBytes = 0u;
        std::uint32_t normalizedWidth = 0u;
        std::uint32_t normalizedHeight = 0u;
        if (!rawJpegNormalizeBackend_.resolveResidentOutput(
                    rawNormalizeGeneration, normalizedMosaic, normalizedBytes,
                    normalizedWidth, normalizedHeight)) {
            result.attempted = true;
            result.status = "RAW_NOISE_MAP_NORMALIZE_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque RAW JPEG normalize generation could not be resolved.";
        } else if (normalizedWidth != request.frameWidth || normalizedHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "RAW_NOISE_MAP_NORMALIZE_DIMENSION_MISMATCH";
            result.failureReason = "Resolved normalized RAW dimensions do not match noise-map request.";
        } else {
            SpectraNoiseMapPlannerRequest resolved = request;
            resolved.residentInputGeneration = rawNormalizeGeneration;
            result = spectraPass3PlannerBackend_.executeNoiseMapFromResident(
                    physicalDevice, device, queue, commandPool, *allocator,
                    normalizedMosaic, normalizedBytes, resolved);
        }
    }
    completeSubmission();
    return result;
}

SpectraTemporalObserverResult VulkanRuntime::executeSpectraTemporalObserver(
        const SpectraTemporalObserverRequest& request
) noexcept {
    SpectraTemporalObserverResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.status = "VULKAN_RUNTIME_NOT_READY";
            rejected.failureReason = "Authoritative Vulkan runtime is not READY.";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }
    SpectraTemporalObserverResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraTemporalObserverBackend_.execute(
                physicalDevice, device, queue, commandPool, *allocator, request);
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("SPECTRA_TEMPORAL_OBSERVER");
        return result;
    }
    completeSubmission();
    return result;
}


SpectraRawFinalizeResult VulkanRuntime::executeSpectraRawFinalize(
        const SpectraRawFinalizeRequest& request
) noexcept {
    std::lock_guard<std::mutex> orchestrationLock(neuralOrchestrationMutex_);
    SpectraRawFinalizeResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.status = "VULKAN_RUNTIME_NOT_READY";
            rejected.failureReason = "Authoritative Vulkan runtime is not READY.";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }
    SpectraRawFinalizeResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraRawFinalizeBackend_.execute(
                physicalDevice, device, queue, commandPool, *allocator, request);
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("SPECTRA_RAW_FINALIZE");
        return result;
    }
    completeSubmission();
    return result;
}

bool VulkanRuntime::readbackSpectraRawFinalizeResident(
        std::uint64_t generation,
        std::vector<float>& output
) noexcept {
    std::lock_guard<std::mutex> orchestrationLock(neuralOrchestrationMutex_);
    output.clear();
    if (generation == 0u) return false;

    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) return false;
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        device = handles_.device;
        queue = handles_.computeQueue;
    }

    bool ok = false;
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        ok = spectraRawFinalizeBackend_.readbackResidentOutput(
                device, queue, generation, output);
    }
    completeSubmission();
    return ok;
}

SpectraResidentDemosaicResult VulkanRuntime::executeSpectraResidentDemosaicFromRawFinalize(
        const SpectraResidentDemosaicRequest& request,
        std::uint64_t rawFinalizeGeneration
) noexcept {
    SpectraResidentDemosaicResult rejected{};
    rejected.attempted = true;
    if (rawFinalizeGeneration == 0u) {
        rejected.cpuFallbackRequired = true;
        rejected.status = "RAW_FINALIZE_RESIDENT_GENERATION_MISSING";
        rejected.failureReason = "Resident RAW-finalize generation is required.";
        return rejected;
    }
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.cpuFallbackRequired = true;
            rejected.status = "VULKAN_RUNTIME_NOT_READY";
            rejected.failureReason = "Authoritative Vulkan runtime is not READY.";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }
    SpectraResidentDemosaicResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer residentInput = VK_NULL_HANDLE;
        std::uint64_t residentBytes = 0u;
        std::uint32_t residentWidth = 0u;
        std::uint32_t residentHeight = 0u;
        bool resolved = spectraRawFinalizeBackend_.resolveResidentOutput(
                rawFinalizeGeneration, residentInput, residentBytes,
                residentWidth, residentHeight);
        if (!resolved) {
            resolved = spectraNeuralRemainingLscBackend_.resolveResidentOutput(
                    rawFinalizeGeneration, residentInput, residentBytes,
                    residentWidth, residentHeight);
        }
        if (!resolved) {
            result.attempted = true;
            result.cpuFallbackRequired = true;
            result.status = "PRE_DEMOSAIC_RESIDENT_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque RawFinalize/remaining-LSC generation could not be resolved.";
        } else if (residentWidth != request.frameWidth || residentHeight != request.frameHeight) {
            result.attempted = true;
            result.cpuFallbackRequired = true;
            result.status = "RAW_FINALIZE_RESIDENT_DIMENSION_MISMATCH";
            result.failureReason = "Resolved RAW-finalize dimensions do not match demosaic request.";
        } else {
            result = spectraResidentDemosaicBackend_.executeFromResidentMosaic(
                    physicalDevice, device, queue, commandPool, *allocator,
                    residentInput, residentBytes, request);
        }
    }
    completeSubmission();
    return result;
}

SpectraResidentDemosaicResult VulkanRuntime::executeSpectraResidentDemosaic(
        const SpectraResidentDemosaicRequest& request
) noexcept {
    SpectraResidentDemosaicResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.cpuFallbackRequired = true;
            rejected.status = "VULKAN_RUNTIME_NOT_READY";
            rejected.failureReason = "Authoritative Vulkan runtime is not READY.";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }
    SpectraResidentDemosaicResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraResidentDemosaicBackend_.execute(
                physicalDevice, device, queue, commandPool, *allocator, request);
    }
    completeSubmission();
    return result;
}

SpectraResidentColorTransformResult VulkanRuntime::executeSpectraResidentAwbCcm(
        const SpectraResidentColorTransformRequest& request
) noexcept {
    SpectraResidentColorTransformResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.status = "VULKAN_RUNTIME_NOT_READY";
            rejected.failureReason = "Authoritative Vulkan runtime is not READY.";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }
    SpectraResidentColorTransformResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraResidentDemosaicBackend_.executeAwbCcm(
                physicalDevice, device, queue, commandPool, *allocator, request);
    }
    completeSubmission();
    return result;
}

std::uint64_t VulkanRuntime::rawPreviewOutputHardwareBufferUsage() const noexcept {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ != RuntimeState::READY || handles_.physicalDevice == VK_NULL_HANDLE ||
        handles_.device == VK_NULL_HANDLE) {
        return 0u;
    }
    const auto& enabled = capabilities_.enabledExtensions;
    if (std::find(enabled.begin(), enabled.end(),
                  "VK_ANDROID_external_memory_android_hardware_buffer") == enabled.end()) {
        return 0u;
    }

    VkFormatProperties formatProperties{};
    vkGetPhysicalDeviceFormatProperties(
            handles_.physicalDevice, VK_FORMAT_R8G8B8A8_UNORM, &formatProperties);
    const VkFormatFeatureFlags requiredFormatFeatures =
            VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    if ((formatProperties.optimalTilingFeatures & requiredFormatFeatures) != requiredFormatFeatures) {
        return 0u;
    }

    VkPhysicalDeviceExternalImageFormatInfo externalInfo{};
    externalInfo.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO;
    externalInfo.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkPhysicalDeviceImageFormatInfo2 query{};
    query.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2;
    query.pNext = &externalInfo;
    query.format = VK_FORMAT_R8G8B8A8_UNORM;
    query.type = VK_IMAGE_TYPE_2D;
    query.tiling = VK_IMAGE_TILING_OPTIMAL;
    query.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;

    VkAndroidHardwareBufferUsageANDROID ahbUsage{};
    ahbUsage.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_USAGE_ANDROID;
    VkExternalImageFormatProperties externalProperties{};
    externalProperties.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES;
    externalProperties.pNext = &ahbUsage;
    VkImageFormatProperties2 properties{};
    properties.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2;
    properties.pNext = &externalProperties;

    if (vkGetPhysicalDeviceImageFormatProperties2(handles_.physicalDevice, &query, &properties) != VK_SUCCESS) {
        return 0u;
    }
    if ((externalProperties.externalMemoryProperties.externalMemoryFeatures &
         VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) == 0u) {
        return 0u;
    }
    return ahbUsage.androidHardwareBufferUsage;
}

RawCaptureCanonicalizeResult VulkanRuntime::executeRawCaptureCanonicalize(
        const RawCaptureCanonicalizeRequest& request
) noexcept {
    RawCaptureCanonicalizeResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.failureReason = "VULKAN_RUNTIME_NOT_READY";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }
    RawCaptureCanonicalizeResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = rawCaptureBackend_.canonicalize(
                physicalDevice, device, queue, commandPool, *allocator, request);
    }
    if (result.submissionMayRemainInFlight) {
        // Keep the registered in-flight count non-zero. resetShotCircuitBreaker()/shutdown() will
        // quarantine rather than destroying resources whose GPU completion is unknown.
        markGpuStalled("RAW_CAPTURE_CANONICALIZE");
        return result;
    }
    completeSubmission();
    return result;
}

RawMultiFrameResult VulkanRuntime::executeRawMultiFrame(
        const RawMultiFrameRequest& request
) noexcept {
    RawMultiFrameResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    bool rawMultiFrameCommandPoolIsolated = false;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.failureReason = "VULKAN_RUNTIME_NOT_READY";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        rawMultiFrameCommandPoolIsolated = handles_.rawMultiFrameCommandPool != VK_NULL_HANDLE;
        commandPool = rawMultiFrameCommandPoolIsolated
            ? handles_.rawMultiFrameCommandPool
            : handles_.commandPool;
        allocator = &handles_.allocator;
    }
    RawMultiFrameResult result{};
    if (rawMultiFrameCommandPoolIsolated) {
        // Capture owns an isolated command pool, so only the externally synchronized VkQueue
        // submission itself needs the global queue lock. RAW preview and lightweight runtime work
        // can interleave safely between capture stages even on single-queue devices.
        result = rawMultiFrameBackend_.execute(
                physicalDevice, device, queue, commandPool, *allocator, &submissionMutex_,
                spectraTemporalObserverBackend_, request);
    } else {
        // Safe fallback when a dedicated RAW multi-frame command pool could not be created.
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = rawMultiFrameBackend_.execute(
                physicalDevice, device, queue, commandPool, *allocator, nullptr,
                spectraTemporalObserverBackend_, request);
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("RAW_MULTIFRAME_RESIDENT");
        return result;
    }
    completeSubmission();
    return result;
}

RawJpegNormalizeResult VulkanRuntime::executeRawJpegNormalizeFromMultiFrame(
        const RawJpegNormalizeRequest& request,
        std::uint64_t rawMultiFrameGeneration
) noexcept {
    RawJpegNormalizeResult rejected{};
    rejected.attempted = true;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.failureReason = "VULKAN_RUNTIME_NOT_READY";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }

    RawJpegNormalizeResult result{};
    {
        // Resolve and consume in one runtime transaction. No other RAW multi-frame execute can
        // invalidate/reuse finalRaw_ between generation validation and normalization dispatch.
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer residentRaw = VK_NULL_HANDLE;
        std::uint64_t residentBytes = 0u;
        std::uint32_t residentWidth = 0u;
        std::uint32_t residentHeight = 0u;
        if (!rawMultiFrameBackend_.resolveResidentOutput(
                    rawMultiFrameGeneration, residentRaw, residentBytes,
                    residentWidth, residentHeight) ||
            residentWidth != request.inputWidth || residentHeight != request.inputHeight) {
            result.attempted = true;
            result.failureReason = "RAW_JPEG_NORMALIZE_RESIDENT_MULTIFRAME_GENERATION_INVALID";
        } else {
            RawJpegNormalizeRequest resolved = request;
            resolved.canonicalRawBuffer = residentRaw;
            resolved.canonicalRawBytes = residentBytes;
            if (resolved.generationId == 0u) resolved.generationId = rawMultiFrameGeneration;
            result = rawJpegNormalizeBackend_.execute(
                    device, queue, commandPool, *allocator, resolved);
        }
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("RAW_JPEG_NORMALIZE_RESIDENT_MULTIFRAME");
        return result;
    }
    completeSubmission();
    return result;
}

RawJpegNormalizeResult VulkanRuntime::executeRawJpegNormalizeFromResidentRaw(
        const RawJpegNormalizeRequest& request,
        std::uint64_t residentRawGeneration
) noexcept {
    RawJpegNormalizeResult rejected{};
    rejected.attempted = true;
    if (residentRawGeneration == 0u || request.inputWidth == 0u || request.inputHeight == 0u ||
        request.width == 0u || request.height == 0u) {
        rejected.failureReason = "RAW_JPEG_NORMALIZE_RESIDENT_RAW_REQUEST_INVALID";
        return rejected;
    }

    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.failureReason = "VULKAN_RUNTIME_NOT_READY";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }

    RawJpegNormalizeResult result{};
    {
        // Both RAW producer backends reuse persistent output buffers. Generation resolution and
        // normalization therefore happen under the same submission mutex as one ownership transaction.
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer residentRaw = VK_NULL_HANDLE;
        std::uint64_t residentBytes = 0u;
        std::uint32_t residentWidth = 0u;
        std::uint32_t residentHeight = 0u;
        bool resolved = rawMultiFrameBackend_.resolveResidentOutput(
                residentRawGeneration, residentRaw, residentBytes, residentWidth, residentHeight);
        const char* sourceBackend = "RAW_MULTIFRAME_RESIDENT";
        if (!resolved) {
            resolved = rawCaptureBackend_.resolveResidentOutput(
                    residentRawGeneration, residentRaw, residentBytes, residentWidth, residentHeight);
            sourceBackend = "RAW_SINGLEFRAME_CAPTURE_RESIDENT";
        }
        if (!resolved) {
            result.attempted = true;
            result.failureReason = "RAW_JPEG_NORMALIZE_RESIDENT_RAW_GENERATION_UNAVAILABLE";
        } else if (residentWidth != request.inputWidth || residentHeight != request.inputHeight) {
            result.attempted = true;
            result.failureReason = "RAW_JPEG_NORMALIZE_RESIDENT_RAW_DIMENSION_MISMATCH";
        } else {
            RawJpegNormalizeRequest resolvedRequest = request;
            resolvedRequest.canonicalRawBuffer = residentRaw;
            resolvedRequest.canonicalRawBytes = residentBytes;
            if (resolvedRequest.generationId == 0u) {
                resolvedRequest.generationId = residentRawGeneration ^ 0x5241574a5045474eull;
            }
            result = rawJpegNormalizeBackend_.execute(
                    device, queue, commandPool, *allocator, resolvedRequest);
            if (result.success) {
                result.backend += std::string("_FROM_") + sourceBackend;
            }
        }
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("RAW_JPEG_NORMALIZE_RESIDENT_RAW");
        return result;
    }
    completeSubmission();
    return result;
}

bool VulkanRuntime::configureSpectraNeuralModel(
        const void* packageBytes,
        std::size_t packageSize,
        bool releaseApproved,
        std::uint32_t inFlightSlots
) noexcept {
    // A package cannot self-promote from a test fixture to production. Release
    // approval is an explicit caller contract supplied by the model deployment layer.
    if (!releaseApproved || packageBytes == nullptr || packageSize == 0u ||
        inFlightSlots < 1u || inFlightSlots > 4u) {
        clearSpectraNeuralModel();
        return false;
    }

    std::lock_guard<std::mutex> orchestrationLock(neuralOrchestrationMutex_);
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            spectraNeuralModelAvailable_.store(false, std::memory_order_release);
            return false;
        }
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }

    // Reconfiguration is fail-closed and owns no second runtime/device.
    spectraNeuralProductionBridge_.destroy(device);
    spectraNeuralRawDenoiseBackend_.destroy();
    const bool ready = spectraNeuralRawDenoiseBackend_.initialize(
            physicalDevice, device, queue, commandPool, *allocator,
            submissionMutex_, packageBytes, packageSize, inFlightSlots);
    spectraNeuralModelAvailable_.store(ready, std::memory_order_release);
    return ready;
}

void VulkanRuntime::clearSpectraNeuralModel() noexcept {
    std::lock_guard<std::mutex> orchestrationLock(neuralOrchestrationMutex_);
    VkDevice device = VK_NULL_HANDLE;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        device = handles_.device;
    }
    spectraNeuralModelAvailable_.store(false, std::memory_order_release);
    spectraNeuralProductionBridge_.destroy(device);
    spectraNeuralRawDenoiseBackend_.destroy();
}

bool VulkanRuntime::spectraNeuralModelAvailable() const noexcept {
    return spectraNeuralModelAvailable_.load(std::memory_order_acquire);
}

SpectraRawFinalizeResult VulkanRuntime::executeSpectraNeuralThenRawFinalizeFromRawNormalize(
        const SpectraRawFinalizeRequest& request,
        const neural::SpectraNeuralProductionRequest& neuralRequest,
        std::uint64_t rawNormalizeGeneration,
        neural::SpectraNeuralProductionTrace* traceOut,
        neural::SpectraNeuralStageDumps* stageDumpsOut
) noexcept {
    using bncam::spectra::neural::NeuralBackendFailureCode;
    using bncam::spectra::neural::NeuralBypassReason;
    using bncam::spectra::neural::decideNeuralInvocation;
    using bncam::spectra::neural::mergeProductionReadiness;
    using bncam::spectra::neural::neuralBypassReasonName;

    const auto orchestrationStarted = std::chrono::steady_clock::now();
    const auto orchestrationElapsedMs = [&]() noexcept -> float {
        return std::chrono::duration<float, std::milli>(
                std::chrono::steady_clock::now() - orchestrationStarted).count();
    };

    neural::SpectraNeuralProductionTrace trace{};
    trace.stageDumpsRequested = neuralRequest.collectStageDumps;
    neural::SpectraNeuralStageDumps stageDumps{};
    stageDumps.requested = neuralRequest.collectStageDumps;
    stageDumps.width = request.frameWidth;
    stageDumps.height = request.frameHeight;
    stageDumps.status = neuralRequest.collectStageDumps ? "REQUESTED" : "NOT_REQUESTED";
    if (traceOut != nullptr) *traceOut = trace;
    if (stageDumpsOut != nullptr) *stageDumpsOut = stageDumps;

    SpectraRawFinalizeResult rejected{};
    rejected.attempted = true;
    if (rawNormalizeGeneration == 0u) {
        rejected.status = "NEURAL_RAW_FINALIZE_NORMALIZE_GENERATION_MISSING";
        rejected.failureReason = "Resident RAW-normalize generation is required.";
        return rejected;
    }

    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.status = "VULKAN_RUNTIME_NOT_READY";
            rejected.failureReason = "Authoritative Vulkan runtime is not READY.";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }

    SpectraRawFinalizeResult result{};
    {
        // One capture may orchestrate several internally serialized queue submissions,
        // but we never recursively lock submissionMutex_. The orchestration mutex also
        // prevents model reconfiguration from invalidating neural resources mid-shot.
        std::lock_guard<std::mutex> orchestrationLock(neuralOrchestrationMutex_);
        // Snapshot native model identity only after model reconfiguration is excluded.
        trace.modelAvailable = spectraNeuralModelAvailable();
        trace.modelIdentity = spectraNeuralRawDenoiseBackend_.modelIdentity();

        VkBuffer normalizedInput = VK_NULL_HANDLE;
        std::uint64_t normalizedBytes = 0u;
        std::uint32_t normalizedWidth = 0u;
        std::uint32_t normalizedHeight = 0u;
        if (!rawJpegNormalizeBackend_.resolveResidentOutput(
                    rawNormalizeGeneration, normalizedInput, normalizedBytes,
                    normalizedWidth, normalizedHeight)) {
            result.attempted = true;
            result.status = "NEURAL_RAW_FINALIZE_NORMALIZE_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque RAW-normalize generation could not be resolved.";
        } else if (normalizedWidth != request.frameWidth || normalizedHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "NEURAL_RAW_FINALIZE_NORMALIZE_DIMENSION_MISMATCH";
            result.failureReason = "Resolved normalized RAW dimensions do not match finalizer request.";
        } else {
            const std::uint64_t imageBytes = static_cast<std::uint64_t>(request.frameWidth) *
                    request.frameHeight * sizeof(float);

            const auto captureDebugStage = [&](VkBuffer source, std::vector<float>& destination,
                                               bool& ready, const char* stageName) noexcept {
                if (!neuralRequest.collectStageDumps || stageDumpsOut == nullptr) return;
                float readbackMs = 0.0f;
                std::string failure;
                if (readbackNeuralStageFp32(
                            device, queue, commandPool, *allocator, submissionMutex_,
                            source, imageBytes, destination, readbackMs, failure)) {
                    ready = true;
                    ++stageDumps.stageCount;
                    stageDumps.debugReadbackBytes += imageBytes;
                    stageDumps.debugReadbackMs += readbackMs;
                } else {
                    ready = false;
                    if (stageDumps.status == "REQUESTED" || stageDumps.status == "COMPLETE") {
                        stageDumps.status = std::string("PARTIAL_") + stageName + "_" + failure;
                    }
                }
            };

            const auto runExactBaselineFinalize = [&]() noexcept -> SpectraRawFinalizeResult {
                std::lock_guard<std::mutex> submitLock(submissionMutex_);
                return spectraRawFinalizeBackend_.executeFromResident(
                        physicalDevice, device, queue, commandPool, *allocator,
                        normalizedInput, normalizedBytes, request);
            };

            // Preflight is deliberately before the split physical/neural path. Off, model-missing,
            // OOD, schema mismatch and every other structural bypass execute the exact historical
            // normalized-RAW -> full RawFinalize route, with no extra full-frame GPU stage.
            const bool conditioningValid = neuralRequest.conditioningConfig.valid();
            const bool modelReady = spectraNeuralModelAvailable();
            auto readiness = neuralRequest.runtimeReadiness;
            // configureSpectraNeuralModel() publishes modelReady only after a release-approved
            // package has passed the frozen package/schema/hash loader and backend initialization.
            // These are therefore runtime facts, not caller-provided optimistic hints.
            readiness.conditioningSchemaCompatible = conditioningValid;
            readiness.modelPresent = modelReady;
            readiness.modelIntegrityVerified = modelReady;
            readiness.modelSchemaCompatible = modelReady;
            readiness.backendAvailable = spectraNeuralRawDenoiseBackend_.available();
            readiness = mergeProductionReadiness(neuralRequest.prepared, readiness);
            const auto decision = decideNeuralInvocation(
                    neuralRequest.prepared.core, neuralRequest.prepared.controls, readiness);
            if (!decision.runInference || !conditioningValid) {
                trace.exactPreflightBypass = true;
                trace.originalPublished = true;
                trace.neuralPublished = false;
                trace.bypassReason = conditioningValid
                        ? decision.bypassReason : NeuralBypassReason::InvalidConditioningSchema;
                trace.status = "EXACT_PREFLIGHT_BYPASS_" +
                        std::string(neuralBypassReasonName(trace.bypassReason));
                result = runExactBaselineFinalize();
            } else if (normalizedBytes < imageBytes) {
                trace.exactPreflightBypass = true;
                trace.bypassReason = NeuralBypassReason::BackendFailure;
                trace.failureCode = NeuralBackendFailureCode::InvalidRequest;
                trace.status = "FAIL_BYPASS_NORMALIZED_RAW_SIZE_MISMATCH";
                result = runExactBaselineFinalize();
            } else {
                // Stage A: hard physical Bayer correction only. LSC and spatial exposure are
                // intentionally absent so the Student sees defect/green-corrected pre-LSC RAW.
                SpectraRawFinalizeRequest hardRequest = request;
                hardRequest.lensShadingMap = nullptr;
                hardRequest.lensShadingColumns = 0u;
                hardRequest.lensShadingRows = 0u;
                hardRequest.lensShadingGenerationId = 0u;
                hardRequest.adaptiveExposureEnabled = false;

                SpectraRawFinalizeResult hardPhysical{};
                {
                    std::lock_guard<std::mutex> submitLock(submissionMutex_);
                    hardPhysical = spectraRawFinalizeBackend_.executeFromResident(
                            physicalDevice, device, queue, commandPool, *allocator,
                            normalizedInput, normalizedBytes, hardRequest);
                }
                trace.prePhysicalMs = hardPhysical.totalMs;
                trace.hardPhysicalCorrectionApplied = hardPhysical.success;

                if (hardPhysical.submissionMayRemainInFlight) {
                    result = hardPhysical;
                    trace.failureCode = NeuralBackendFailureCode::DispatchFailed;
                    trace.bypassReason = NeuralBypassReason::BackendFailure;
                    trace.status = "GPU_STALLED_PRE_NEURAL_HARD_PHYSICAL";
                } else if (!hardPhysical.success || hardPhysical.residentOutputGeneration == 0u ||
                           !hardPhysical.sourceClipConfidenceMapReady) {
                    trace.failureCode = NeuralBackendFailureCode::DispatchFailed;
                    trace.bypassReason = NeuralBypassReason::BackendFailure;
                    trace.status = "FAIL_BYPASS_PRE_NEURAL_HARD_PHYSICAL";
                    result = runExactBaselineFinalize();
                } else {
                    VkBuffer hardPhysicalBuffer = VK_NULL_HANDLE;
                    std::uint64_t hardPhysicalBytes = 0u;
                    std::uint32_t hardPhysicalWidth = 0u;
                    std::uint32_t hardPhysicalHeight = 0u;
                    if (!spectraRawFinalizeBackend_.resolveResidentOutput(
                                hardPhysical.residentOutputGeneration,
                                hardPhysicalBuffer, hardPhysicalBytes,
                                hardPhysicalWidth, hardPhysicalHeight) ||
                        hardPhysicalWidth != request.frameWidth ||
                        hardPhysicalHeight != request.frameHeight ||
                        hardPhysicalBytes < imageBytes + hardPhysical.sourceClipConfidenceMapBytes) {
                        trace.failureCode = NeuralBackendFailureCode::InternalError;
                        trace.bypassReason = NeuralBypassReason::BackendFailure;
                        trace.status = "FAIL_BYPASS_PRE_NEURAL_RESIDENT_TRANSPORT_INVALID";
                        result = runExactBaselineFinalize();
                    } else {
                        captureDebugStage(
                                hardPhysicalBuffer,
                                stageDumps.preNeuralHardPhysicalMosaic,
                                stageDumps.preNeuralHardPhysicalReady,
                                "PRE_NEURAL_HARD_PHYSICAL");
                        neural::NeuralProductionGpuRequest gpuRequest{};
                        gpuRequest.normalizedBayerInput = hardPhysicalBuffer;
                        // The bridge consumes only the Bayer image prefix. The source-clip tail
                        // remains owned by RawFinalize and is transported separately after neural.
                        gpuRequest.normalizedBayerBytes = imageBytes;
                        gpuRequest.prepared = neuralRequest.prepared;
                        gpuRequest.conditioningConfig = neuralRequest.conditioningConfig;
                        gpuRequest.runtimeReadiness = readiness;
                        gpuRequest.remainingLscMap = neuralRequest.remainingLscMap;
                        gpuRequest.remainingLscWidth = neuralRequest.remainingLscWidth;
                        gpuRequest.remainingLscHeight = neuralRequest.remainingLscHeight;
                        gpuRequest.remainingLscChannels = neuralRequest.remainingLscChannels;
                        gpuRequest.remainingLscGeneration = neuralRequest.remainingLscGeneration;
                        gpuRequest.generationId = neuralRequest.generationId;

                        const auto neuralResult = spectraNeuralProductionBridge_.execute(
                                device, queue, commandPool, *allocator, submissionMutex_,
                                spectraNeuralRawDenoiseBackend_, gpuRequest);
                        trace.attempted = neuralResult.attempted;
                        trace.neuralPublished = neuralResult.neuralPublished;
                        trace.originalPublished = neuralResult.originalPublished;
                        trace.modelAvailable = spectraNeuralModelAvailable();
                        trace.neuralKernelDispatches = neuralResult.neuralKernelDispatches;
                        trace.bridgeKernelDispatches = neuralResult.bridgeKernelDispatches;
                        trace.compactMetadataUploadBytes = neuralResult.compactMetadataUploadBytes;
                        trace.posteriorSummaryReady = neuralResult.posteriorSummaryReady;
                        trace.posteriorMeanVarianceCfa = neuralResult.posteriorMeanVarianceCfa;
                        trace.compactPosteriorReadbackBytes = neuralResult.compactPosteriorReadbackBytes;
                        trace.effectTelemetryReady = neuralResult.effectTelemetryReady;
                        trace.effectTelemetry = neuralResult.effectTelemetry;
                        trace.compactEffectReadbackBytes = neuralResult.compactEffectReadbackBytes;
                        trace.effectSummaryMs = neuralResult.effectSummaryMs;
                        trace.effectTelemetryStatus = neuralResult.effectTelemetryStatus;
                        trace.modelIdentity = spectraNeuralRawDenoiseBackend_.modelIdentity();
                        trace.persistentGpuBytes = neuralResult.persistentGpuBytes;
                        trace.fullFrameCpuReadbackBytes = neuralResult.fullFrameCpuReadbackBytes;
                        trace.cpuFallbackUsed = neuralResult.cpuFallbackUsed;
                        trace.neuralWallMs = neuralResult.totalWallMs;
                        trace.bypassReason = neuralResult.bypassReason;
                        trace.failureCode = neuralResult.failureCode;
                        trace.status = neuralResult.status;

                        if (!neuralResult.success || !neuralResult.neuralPublished ||
                            neuralResult.downstreamBayerBuffer == VK_NULL_HANDLE ||
                            neuralResult.downstreamBayerBytes != imageBytes) {
                            // Fail=bypass means discard every split-stage intermediate and rerun
                            // the exact baseline full finalizer from immutable normalized RAW.
                            if (trace.bypassReason == NeuralBypassReason::None) {
                                trace.bypassReason = NeuralBypassReason::BackendFailure;
                            }
                            if (trace.failureCode == NeuralBackendFailureCode::None) {
                                trace.failureCode = NeuralBackendFailureCode::DispatchFailed;
                            }
                            trace.status = neuralResult.status.empty() || neuralResult.status == "NOT_RUN"
                                    ? "FAIL_BYPASS_NEURAL_PUBLICATION"
                                    : "FAIL_BYPASS_NEURAL_PUBLICATION_CAUSE_" + neuralResult.status;
                            trace.neuralPublished = false;
                            trace.originalPublished = true;
                            result = runExactBaselineFinalize();
                        } else {
                            captureDebugStage(
                                    neuralResult.downstreamBayerBuffer,
                                    stageDumps.postNeuralMosaic,
                                    stageDumps.postNeuralReady,
                                    "POST_NEURAL");
                            neural::NeuralRemainingLscRequest lscRequest{};
                            lscRequest.bayerInput = neuralResult.downstreamBayerBuffer;
                            lscRequest.bayerInputBytes = neuralResult.downstreamBayerBytes;
                            lscRequest.sourceClipTransport = hardPhysicalBuffer;
                            lscRequest.sourceClipTransportBytes = hardPhysicalBytes;
                            lscRequest.frameWidth = request.frameWidth;
                            lscRequest.frameHeight = request.frameHeight;
                            lscRequest.sensorCfaPattern = request.sensorCfaPattern;
                            lscRequest.cfaOffsetX = request.cfaOffsetX;
                            lscRequest.cfaOffsetY = request.cfaOffsetY;
                            lscRequest.lensShadingMap = request.lensShadingMap;
                            lscRequest.lensShadingColumns = request.lensShadingColumns;
                            lscRequest.lensShadingRows = request.lensShadingRows;
                            lscRequest.lensShadingGenerationId = request.lensShadingGenerationId;
                            lscRequest.collectAutoSceneMetrics = neuralRequest.collectAutoSceneMetrics;

                            neural::NeuralRemainingLscResult lscResult{};
                            {
                                std::lock_guard<std::mutex> submitLock(submissionMutex_);
                                lscResult = spectraNeuralRemainingLscBackend_.execute(
                                        physicalDevice, device, queue, commandPool,
                                        *allocator, lscRequest);
                            }
                            trace.remainingLscMs = lscResult.totalMs;
                            trace.remainingLscApplied = lscResult.lensShadingApplied;
                            trace.sourceClipProvenancePreserved =
                                    lscResult.sourceClipConfidenceMapPreserved;

                            if (lscResult.submissionMayRemainInFlight) {
                                result = hardPhysical;
                                result.success = false;
                                result.submissionMayRemainInFlight = true;
                                result.status = "GPU_STALLED_NEURAL_REMAINING_LSC";
                                result.failureReason = lscResult.failureReason;
                                trace.failureCode = NeuralBackendFailureCode::DispatchFailed;
                                trace.bypassReason = NeuralBypassReason::BackendFailure;
                                trace.status = "GPU_STALLED_NEURAL_REMAINING_LSC";
                            } else if (!lscResult.success ||
                                       lscResult.residentOutputGeneration == 0u ||
                                       !lscResult.sourceClipConfidenceMapPreserved ||
                                       (neuralRequest.collectAutoSceneMetrics &&
                                        !lscResult.autoSceneMetricsReady)) {
                                trace.failureCode = NeuralBackendFailureCode::DispatchFailed;
                                trace.bypassReason = NeuralBypassReason::BackendFailure;
                                trace.status = "FAIL_BYPASS_NEURAL_REMAINING_LSC";
                                trace.neuralPublished = false;
                                trace.originalPublished = true;
                                result = runExactBaselineFinalize();
                            } else {
                                VkBuffer postLscBuffer = VK_NULL_HANDLE;
                                std::uint64_t postLscBytes = 0u;
                                std::uint32_t postLscWidth = 0u;
                                std::uint32_t postLscHeight = 0u;
                                if (neuralRequest.collectStageDumps && stageDumpsOut != nullptr &&
                                    spectraNeuralRemainingLscBackend_.resolveResidentOutput(
                                            lscResult.residentOutputGeneration,
                                            postLscBuffer, postLscBytes,
                                            postLscWidth, postLscHeight) &&
                                    postLscWidth == request.frameWidth &&
                                    postLscHeight == request.frameHeight &&
                                    postLscBytes >= imageBytes) {
                                    captureDebugStage(
                                            postLscBuffer,
                                            stageDumps.postRemainingLscMosaic,
                                            stageDumps.postRemainingLscReady,
                                            "POST_REMAINING_LSC");
                                }
                                // Preserve hard-physical/source telemetry, then replace only the
                                // fields owned by the post-neural remaining-LSC stage.
                                result = hardPhysical;
                                result.success = true;
                                result.submissionMayRemainInFlight = false;
                                result.lensShadingApplied = lscResult.lensShadingApplied;
                                result.lensCorrectedPixelCount = lscResult.lensCorrectedPixelCount;
                                result.overRangePixelCount = lscResult.overRangePixelCount;
                                result.lensMaximumGain = lscResult.lensMaximumGain;
                                result.lensMapUploadMs += lscResult.lensMapUploadMs;
                                result.finalizeKernelMs += lscResult.lscKernelMs;
                                result.synchronizationMs += lscResult.synchronizationMs;
                                result.autoSceneMetricsReady = lscResult.autoSceneMetricsReady;
                                result.autoSceneSampleCount = lscResult.autoSceneSampleCount;
                                result.autoSceneMedianSignal = lscResult.autoSceneMedianSignal;
                                result.autoSceneMeanGradient = lscResult.autoSceneMeanGradient;
                                result.autoSceneP90Gradient = lscResult.autoSceneP90Gradient;
                                result.autoSceneEdgeFraction = lscResult.autoSceneEdgeFraction;
                                result.autoSceneCoherentEdgeFraction =
                                        lscResult.autoSceneCoherentEdgeFraction;
                                result.autoSceneLowSignalFraction = lscResult.autoSceneLowSignalFraction;
                                result.sourceClipConfidenceMapReady =
                                        lscResult.sourceClipConfidenceMapPreserved;
                                result.sourceClipConfidenceMapBytes =
                                        lscResult.sourceClipConfidenceMapBytes;
                                result.persistentBufferReuseHit =
                                        hardPhysical.persistentBufferReuseHit &&
                                        lscResult.persistentBufferReuseHit;
                                result.persistentBufferReallocated =
                                        hardPhysical.persistentBufferReallocated ||
                                        lscResult.persistentBufferReallocated;
                                result.persistentResidentBytes = hardPhysical.persistentResidentBytes +
                                        neuralResult.persistentGpuBytes +
                                        lscResult.persistentResidentBytes;
                                result.residentOutputGeneration = lscResult.residentOutputGeneration;
                                result.status =
                                        "GPU_RAW_FINALIZE_NEURAL_REMAINING_LSC_PRIMARY_DEMOSAIC_HANDOFF_READY";
                                result.failureReason = "none";
                                result.totalMs = hardPhysical.totalMs + neuralResult.totalWallMs +
                                        lscResult.totalMs;
                                trace.neuralPublished = true;
                                trace.originalPublished = false;
                                trace.bypassReason = NeuralBypassReason::None;
                                trace.failureCode = NeuralBackendFailureCode::None;
                                trace.status = "NEURAL_PRE_DEMOSAIC_RESIDENT_PUBLISHED";
                            }
                        }
                    }
                }
            }
        }
    }

    if (neuralRequest.collectStageDumps) {
        if (stageDumps.stageCount == 3u) {
            stageDumps.status = "COMPLETE";
        } else if (stageDumps.stageCount == 0u && stageDumps.status == "REQUESTED") {
            stageDumps.status = "NO_NEURAL_STAGE_PUBLISHED";
        } else if (stageDumps.status == "REQUESTED") {
            stageDumps.status = "PARTIAL";
        }
        trace.stageDumpCount = stageDumps.stageCount;
        trace.stageDumpsCollected = stageDumps.stageCount > 0u;
        trace.debugStageDumpReadbackBytes = stageDumps.debugReadbackBytes;
        trace.debugStageDumpReadbackMs = stageDumps.debugReadbackMs;
    }
    if (!trace.effectTelemetryReady && trace.effectTelemetryStatus == "NOT_RUN" &&
        trace.bypassReason != NeuralBypassReason::None) {
        trace.effectTelemetryStatus = "BYPASS_" +
                std::string(neuralBypassReasonName(trace.bypassReason));
    }
    trace.totalWallMs = orchestrationElapsedMs();
    if (traceOut != nullptr) *traceOut = trace;
    if (stageDumpsOut != nullptr) *stageDumpsOut = stageDumps;
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("NEURAL_RAW_FINALIZE_FROM_RAW_NORMALIZE");
        return result;
    }
    completeSubmission();
    return result;
}

SpectraRawFinalizeResult VulkanRuntime::executeSpectraRawFinalizeFromRawNormalize(
        const SpectraRawFinalizeRequest& request,
        std::uint64_t rawNormalizeGeneration
) noexcept {
    std::lock_guard<std::mutex> orchestrationLock(neuralOrchestrationMutex_);
    SpectraRawFinalizeResult rejected{};
    rejected.attempted = true;
    if (rawNormalizeGeneration == 0u) {
        rejected.status = "RAW_FINALIZE_RAW_NORMALIZE_GENERATION_MISSING";
        rejected.failureReason = "Resident RAW-normalize generation is required.";
        return rejected;
    }
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.status = "VULKAN_RUNTIME_NOT_READY";
            rejected.failureReason = "Authoritative Vulkan runtime is not READY.";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }
    SpectraRawFinalizeResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer residentInput = VK_NULL_HANDLE;
        std::uint64_t residentBytes = 0u;
        std::uint32_t residentWidth = 0u;
        std::uint32_t residentHeight = 0u;
        if (!rawJpegNormalizeBackend_.resolveResidentOutput(
                    rawNormalizeGeneration, residentInput, residentBytes,
                    residentWidth, residentHeight)) {
            result.attempted = true;
            result.status = "RAW_FINALIZE_RAW_NORMALIZE_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque RAW-normalize generation could not be resolved.";
        } else if (residentWidth != request.frameWidth || residentHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "RAW_FINALIZE_RAW_NORMALIZE_DIMENSION_MISMATCH";
            result.failureReason = "Resolved normalized RAW dimensions do not match finalizer request.";
        } else {
            result = spectraRawFinalizeBackend_.executeFromResident(
                    physicalDevice, device, queue, commandPool, *allocator,
                    residentInput, residentBytes, request);
        }
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("RAW_FINALIZE_FROM_RAW_NORMALIZE");
        return result;
    }
    completeSubmission();
    return result;
}

YuvMultiFrameAlignmentResult VulkanRuntime::executeYuvMultiFrameAlignment(
        const YuvMultiFrameAlignmentRequest& request
) noexcept {
    YuvMultiFrameAlignmentResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.failureReason = "VULKAN_RUNTIME_NOT_READY";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }

    YuvMultiFrameAlignmentResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = yuvMultiFrameBackend_.align(
                physicalDevice, device, queue, commandPool, *allocator, request);
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("YUV_MULTIFRAME_ALIGNMENT");
        return result;
    }
    completeSubmission();
    return result;
}

YuvSingleFrameIspResult VulkanRuntime::executeYuvSingleFrameIsp(
        const YuvSingleFrameIspRequest& request
) noexcept {
    YuvSingleFrameIspResult rejected{};
    rejected.attempted = true;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.failureReason = "VULKAN_RUNTIME_NOT_READY";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }

    YuvSingleFrameIspResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        YuvSingleFrameIspRequest resolvedRequest = request;
        if (request.residentLumaGeneration != 0u) {
            VkBuffer residentBuffer = VK_NULL_HANDLE;
            std::uint64_t residentBytes = 0u;
            std::uint32_t residentWidth = 0u;
            std::uint32_t residentHeight = 0u;
            if (!yuvMultiFrameBackend_.resolveResidentOutput(
                        request.residentLumaGeneration,
                        residentBuffer,
                        residentBytes,
                        residentWidth,
                        residentHeight) ||
                residentWidth != request.width || residentHeight != request.height) {
                result.attempted = true;
                result.failureReason = "YUV_SINGLE_FRAME_RESIDENT_LUMA_GENERATION_INVALID";
            } else {
                resolvedRequest.residentLumaBuffer = residentBuffer;
                resolvedRequest.residentLumaBytes = residentBytes;
                if (request.ultraHdrGainmapRequested) {
                    VkBuffer hdrAccumulator = VK_NULL_HANDLE;
                    VkBuffer hdrWeights = VK_NULL_HANDLE;
                    std::uint64_t hdrBytes = 0u;
                    std::uint32_t hdrWidth = 0u;
                    std::uint32_t hdrHeight = 0u;
                    const bool hdrAuthorityValid = yuvMultiFrameBackend_.resolveResidentHdrAuthority(
                            request.residentLumaGeneration,
                            hdrAccumulator,
                            hdrWeights,
                            hdrBytes,
                            hdrWidth,
                            hdrHeight);
                    if (hdrAuthorityValid &&
                        hdrWidth == request.width && hdrHeight == request.height &&
                        hdrAccumulator != VK_NULL_HANDLE && hdrWeights != VK_NULL_HANDLE) {
                        resolvedRequest.residentHdrAccumulatorBuffer = hdrAccumulator;
                        resolvedRequest.residentHdrWeightBuffer = hdrWeights;
                        resolvedRequest.residentHdrBytes = hdrBytes;
                    } else {
                        // Ultra HDR is optional. An invalid/missing Computational-HDR authority
                        // must never fail normal SDR publication.
                        resolvedRequest.ultraHdrGainmapRequested = false;
                    }
                }
                result = yuvSingleFrameBackend_.execute(
                        device, queue, commandPool, *allocator, resolvedRequest);
            }
        } else {
            result = yuvSingleFrameBackend_.execute(
                    device, queue, commandPool, *allocator, resolvedRequest);
        }
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("YUV_SINGLE_FRAME_ISP");
        return result;
    }
    completeSubmission();
    return result;
}

YuvExposureStatisticsResult VulkanRuntime::executeYuvExposureStatistics(
        const YuvExposureStatisticsRequest& request
) noexcept {
    YuvExposureStatisticsResult rejected{};
    rejected.attempted = true;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.failureReason = "VULKAN_RUNTIME_NOT_READY";
            return rejected;
        }
        if (gpuStalled_.load(std::memory_order_acquire) || quarantined_.load(std::memory_order_acquire)) {
            rejected.failureReason = "VULKAN_RUNTIME_QUARANTINED";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }
    YuvExposureStatisticsResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = yuvExposureStatisticsBackend_.execute(
                device, queue, commandPool, *allocator, request);
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("YUV_EXPOSURE_STATISTICS");
        return result;
    }
    completeSubmission();
    return result;
}

bool VulkanRuntime::prepareYuvSingleFrameBackend() noexcept {
    VkDevice device = VK_NULL_HANDLE;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete() ||
            gpuStalled_.load(std::memory_order_acquire) ||
            quarantined_.load(std::memory_order_acquire)) {
            return false;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        device = handles_.device;
    }

    // Backend-local mutex serializes preparation against the first real YUV execute(). No queue
    // submission occurs here, so camera preview/RAW preview queues remain untouched.
    const bool prepared = yuvSingleFrameBackend_.prepare(device);
    completeSubmission();
    return prepared;
}

bool VulkanRuntime::prepareRawPreviewBackend() noexcept {
    VkDevice device = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete() ||
            handles_.previewCommandPool == VK_NULL_HANDLE ||
            gpuStalled_.load(std::memory_order_acquire) ||
            quarantined_.load(std::memory_order_acquire)) {
            return false;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        device = handles_.device;
        commandPool = handles_.previewCommandPool;
    }

    const bool prepared = rawPreviewBackend_.prepare(device, commandPool);
    completeSubmission();
    return prepared;
}

bool VulkanRuntime::prepareRawSingleFrameWorkingSet(
        std::uint32_t frameWidth,
        std::uint32_t frameHeight
) noexcept {
    if (frameWidth == 0u || frameHeight == 0u) return false;

    VkDevice device = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete() ||
            handles_.commandPool == VK_NULL_HANDLE ||
            gpuStalled_.load(std::memory_order_acquire) ||
            quarantined_.load(std::memory_order_acquire)) {
            return false;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        device = handles_.device;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }

    bool demosaicPrepared = false;
    bool tonePrepared = false;
    {
        // VkCommandPool is externally synchronized. A shutter pressed while prewarm is still
        // running waits on this same capture-domain mutex instead of racing resource setup.
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        std::string demosaicFailure;
        std::string toneFailure;
        demosaicPrepared = spectraResidentDemosaicBackend_.prepareWorkingSet(
                device, commandPool, *allocator, frameWidth, frameHeight, demosaicFailure);
        tonePrepared = spectraResidentToneBackend_.prepareWorkingSet(
                device, commandPool, *allocator, frameWidth, frameHeight, toneFailure);
    }

    completeSubmission();
    return demosaicPrepared && tonePrepared;
}

RawPreviewGpuResult VulkanRuntime::executeRawPreview(
        const RawPreviewGpuRequest& request
) noexcept {
    RawPreviewGpuResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    std::uint32_t queueFamilyIndex = 0u;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    bool dedicatedPreviewQueue = false;
    bool foreignQueueFamilyEnabled = false;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.failureReason = "VULKAN_RUNTIME_NOT_READY";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        // P0 stability recovery: keep RAW preview on the production compute queue until the
        // Phase-11A multi-queue regression is proven safe on-device. The preview still owns its
        // isolated command pool, so host command-buffer access remains independently synchronized,
        // but GPU submissions are ordered on the same queue as Single RAW capture processing.
        // Re-enable the dedicated preview queue only after the RAW viewfinder + Single-capture
        // device acceptance matrix is stable.
        dedicatedPreviewQueue = false;
        const bool isolatedPreviewCommandPool = handles_.previewCommandPool != VK_NULL_HANDLE;
        queue = handles_.computeQueue;
        queueFamilyIndex = handles_.computeQueueFamilyIndex;
        commandPool = isolatedPreviewCommandPool ? handles_.previewCommandPool : handles_.commandPool;
        foreignQueueFamilyEnabled = std::find(
                capabilities_.enabledExtensions.begin(), capabilities_.enabledExtensions.end(),
                "VK_EXT_queue_family_foreign") != capabilities_.enabledExtensions.end();
        allocator = &handles_.allocator;
    }
    RawPreviewGpuResult result = rawPreviewBackend_.execute(
            physicalDevice, device, queue, queueFamilyIndex, foreignQueueFamilyEnabled,
            commandPool, *allocator,
            dedicatedPreviewQueue ? &previewSubmissionMutex_ : &submissionMutex_,
            request);
    completeSubmission();
    return result;
}

RawPreviewGpuResult VulkanRuntime::pollRawPreview(
        std::uint32_t frameSlotIndex,
        std::uint64_t submissionId
) noexcept {
    RawPreviewGpuResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.failureReason = "VULKAN_RUNTIME_NOT_READY";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        allocator = &handles_.allocator;
    }
    RawPreviewGpuResult result = rawPreviewBackend_.pollCompletion(
            physicalDevice, device, *allocator, frameSlotIndex, submissionId);
    completeSubmission();
    return result;
}


SpectraResidentSceneObserverResult VulkanRuntime::executeSpectraResidentSceneObserverFromAwbCcm(
        const SpectraResidentSceneObserverRequest& request,
        std::uint64_t residentColorGeneration
) noexcept {
    SpectraResidentSceneObserverResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.status = "VULKAN_RUNTIME_NOT_READY";
            rejected.failureReason = "Authoritative Vulkan runtime is not READY.";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }
    SpectraResidentSceneObserverResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer colorBuffer = VK_NULL_HANDLE;
        std::uint64_t colorBytes = 0u;
        std::uint32_t width = 0u, height = 0u;
        if (!spectraResidentDemosaicBackend_.resolveResidentColorOutput(
                residentColorGeneration, colorBuffer, colorBytes, width, height) ||
            width != request.frameWidth || height != request.frameHeight) {
            result.attempted = true;
            result.status = "RESIDENT_AWB_CCM_GENERATION_UNAVAILABLE";
            result.failureReason = "POST_CCM_RESIDENT_GENERATION_MISMATCH";
        } else {
            result = spectraResidentToneBackend_.executeSceneObserverFromResident(
                    physicalDevice, device, queue, commandPool, *allocator,
                    colorBuffer, colorBytes, request);
        }
    }
    completeSubmission();
    return result;
}

SpectraResidentToneResult VulkanRuntime::executeSpectraResidentTone(
        const SpectraResidentToneRequest& request
) noexcept {
    SpectraResidentToneResult rejected{};
    rejected.attempted = true;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            rejected.status = "VULKAN_RUNTIME_NOT_READY";
            rejected.failureReason = "Authoritative Vulkan runtime is not READY.";
            return rejected;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        physicalDevice = handles_.physicalDevice;
        device = handles_.device;
        queue = handles_.computeQueue;
        commandPool = handles_.commandPool;
        allocator = &handles_.allocator;
    }
    SpectraResidentToneResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraResidentToneBackend_.executeTone(
                physicalDevice, device, queue, commandPool, *allocator, request);
    }
    completeSubmission();
    return result;
}

bool VulkanRuntime::readbackSpectraResidentTone(
        std::uint64_t residentToneGeneration,
        std::vector<float>& outputRgb,
        std::uint32_t& width,
        std::uint32_t& height,
        std::string& failureReason
) noexcept {
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    VulkanAllocatorOwner* allocator = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) {
            failureReason = "VULKAN_RUNTIME_NOT_READY";
            return false;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        device = handles_.device;
        queue = handles_.computeQueue;
        allocator = &handles_.allocator;
    }
    bool ok = false;
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer buffer = VK_NULL_HANDLE;
        std::uint64_t bytes = 0u;
        if (!spectraResidentToneBackend_.resolveResidentOutput(
                residentToneGeneration, buffer, bytes, width, height)) {
            failureReason = "RESIDENT_TONE_GENERATION_UNAVAILABLE";
        } else {
            ok = spectraResidentToneBackend_.readbackResidentOutput(
                    device, queue, *allocator, residentToneGeneration, outputRgb, failureReason);
        }
    }
    completeSubmission();
    return ok;
}

RuntimeSnapshot VulkanRuntime::snapshotLocked() const {
    RuntimeSnapshot result;
    result.state = state_;
    result.runtimeIdentity = runtimeIdentity_;
    result.loaderAvailable = capabilities_.loaderAvailable;
    result.runtimeInitialized = state_ == RuntimeState::READY;
    result.selectedDeviceName = capabilities_.selectedDeviceName;
    result.enabledExtensions = capabilities_.enabledExtensions;
    result.enabledFeatures = capabilities_.enabledFeatures;
    result.missingRequirements = capabilities_.missingRequirements;
    result.activeProductionStages.clear();
    if (spectraPass3PlannerBackend_.productionKernelConnected()) {
        result.activeProductionStages.push_back("SPECTRA_FP32_PASS3_COMPACT_PLANNER_GPU_PRIMARY");
        result.activeProductionStages.push_back("SPECTRA_FP32_PASS3_ROW_COLUMN_LOWFREQ_NO_FULL_FRAME_SCAN");
    }
    if (spectraTemporalObserverBackend_.productionKernelConnected()) {
        result.activeProductionStages.push_back("SPECTRA_FP32_TEMPORAL_OBSERVER_GPU_PRIMARY");
        result.activeProductionStages.push_back("SPECTRA_FP32_TEMPORAL_NORMALIZED_INNOVATION_STATIC_PROBABILITY");
    }
    if (spectraRawFinalizeBackend_.productionKernelConnected()) {
        result.activeProductionStages.push_back("SPECTRA_FP32_RAW_FINALIZE_GPU_PRIMARY");
        result.activeProductionStages.push_back("SPECTRA_FP32_DEFECT_GREEN_LENS_RESIDENT_TO_DEMOSAIC");
    }
    if (spectraResidentDemosaicBackend_.productionKernelConnected()) {
        result.activeProductionStages.push_back("SPECTRA_FP32_DEMOSAIC_GPU_PRIMARY");
        result.activeProductionStages.push_back("SPECTRA_FP32_MALVAR_BILINEAR_GPU_PRIMARY");
        result.activeProductionStages.push_back("SPECTRA_FP32_DEMOSAIC_TO_AWB_CCM_RESIDENT");
        result.activeProductionStages.push_back("SPECTRA_FP32_AWB_CCM_GPU_PRIMARY");
        result.activeProductionStages.push_back("SPECTRA_FP32_LINEAR_RESIDUAL_COMPACT_GPU_OBSERVER");
    }
    if (spectraResidentToneBackend_.productionKernelConnected()) {
        result.activeProductionStages.push_back("SPECTRA_FP32_POST_CCM_SCENE_TONE_RESIDENT");
        result.activeProductionStages.push_back("SPECTRA_FP32_HIGHLIGHT_RECOVERY_SCENE_OBSERVER_GPU_PRIMARY");
        result.activeProductionStages.push_back("SPECTRA_FP32_TONE_VIBRANCE_PROFILE_COLOR_GPU_PRIMARY");
    }
    result.instanceCreationCount = instanceCreationCount_;
    result.deviceCreationCount = deviceCreationCount_;
    result.initializeRequestCount = initializeRequestCount_;
    result.shutdownRequestCount = shutdownRequestCount_;
    result.inFlightSubmissionCount = inFlightSubmissionCount_.load(std::memory_order_acquire);
    result.lastFailure = lastFailure_;
    return result;
}

RuntimeSnapshot VulkanRuntime::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return snapshotLocked();
}

CapabilitySnapshot VulkanRuntime::capabilities() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return capabilities_;
}

ValidationSnapshot VulkanRuntime::validationSnapshot() const {
    return validationCollector_.snapshot();
}

std::string VulkanRuntime::diagnosticsJson() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::ostringstream out;
    out << '{'
        << "\"schemaVersion\":1,"
        << "\"runtime\":" << snapshotLocked().toJson() << ','
        << "\"validationCompiledIn\":"
        << json::boolean(BNCAM_VULKAN_VALIDATION_ENABLED != 0) << ','
        << "\"validationRequested\":"
        << json::boolean(config_.debugValidationRequested) << ','
        << "\"vma\":{"
        << "\"pinnedVersion\":\"" << VulkanAllocatorOwner::pinnedVersion() << "\","
        << "\"headerAvailableAtBuildTime\":"
        << json::boolean(VulkanAllocatorOwner::headerAvailableAtBuildTime())
        << ','
        << "\"statistics\":" << handles_.allocator.boundedStatisticsJson()
        << "},"
        << "\"lifecycleEvents\":[";
    for (std::size_t index = 0; index < lifecycleEvents_.size(); ++index) {
        if (index != 0) out << ',';
        const auto& event = lifecycleEvents_[index];
        out << '{'
            << "\"timestampEpochMs\":" << event.timestampEpochMs << ','
            << "\"event\":" << json::quote(event.event) << ','
            << "\"detail\":" << json::quote(event.detail)
            << '}';
    }
    out << "]}";
    return out.str();
}

std::string VulkanRuntime::diagnosticsHumanReadable() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::ostringstream out;
    out << snapshotLocked().toHumanReadable() << '\n'
        << "Validation compiled in: "
        << (BNCAM_VULKAN_VALIDATION_ENABLED != 0 ? "true" : "false") << '\n'
        << "Validation requested: "
        << (config_.debugValidationRequested ? "true" : "false") << '\n'
        << "VMA pinned version: " << VulkanAllocatorOwner::pinnedVersion() << '\n'
        << "VMA header available at build time: "
        << (VulkanAllocatorOwner::headerAvailableAtBuildTime() ? "true" : "false") << '\n'
        << "VMA allocator ready: " << (handles_.allocator.isReady() ? "true" : "false") << '\n'
        << "Pipeline cache: lifecycle-persistent VkPipelineCache object; compatible disk cache enabled\n"
        << "Ownership scope: process/native-engine\n"
        << "Camera/session ownership: forbidden\n"
        << "Compose ownership: forbidden\n"
        << "Production Vulkan stages: "
        << (spectraPass3PlannerBackend_.productionKernelConnected() ||
                spectraTemporalObserverBackend_.productionKernelConnected() ||
                spectraRawFinalizeBackend_.productionKernelConnected() ||
                spectraResidentDemosaicBackend_.productionKernelConnected() ||
                spectraResidentToneBackend_.productionKernelConnected()
                ? "retained GPU stages connected"
                : "none")
        << '\n';
    return out.str();
}

}  // namespace bncam::vulkan
