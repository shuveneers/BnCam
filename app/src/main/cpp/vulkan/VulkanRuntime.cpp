#include "VulkanRuntime.h"
#include "VulkanJson.h"
#include "VulkanVmaIntegration.h"

#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <chrono>
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

    // Milestone 8E: the production-connected SPECTRA pipeline owns device objects and
    // must be destroyed before the authoritative device/pipeline-cache ownership moves.
    spectraResidentChromaBackend_.destroy(handles_.device);
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
    spectraResidentPreDemosaicBackend_.destroy(handles_.device);
    spectraResidentPostDemosaicBackend_.destroy(handles_.device);
    spectraVisibleChromaBackend_.destroy(handles_.device);
    spectraOpponentBackend_.destroy(handles_.device);
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


SpectraOpponentExecutionResult VulkanRuntime::executeSpectraOpponentFeatures(
        const SpectraOpponentExecutionRequest& request
) noexcept {
    SpectraOpponentExecutionResult rejected{};
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

    SpectraOpponentExecutionResult result{};
    {
        // VkQueue and the authoritative command pool require external synchronization.
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraOpponentBackend_.execute(
                physicalDevice,
                device,
                queue,
                commandPool,
                *allocator,
                request
        );
    }
    completeSubmission();
    return result;
}

SpectraVisibleChromaExecutionResult VulkanRuntime::executeSpectraVisibleChromaCandidate(
        const SpectraVisibleChromaExecutionRequest& request
) noexcept {
    SpectraVisibleChromaExecutionResult rejected{};
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
    SpectraVisibleChromaExecutionResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraVisibleChromaBackend_.execute(
                physicalDevice, device, queue, commandPool, *allocator, request
        );
    }
    completeSubmission();
    return result;
}

SpectraResidentPostDemosaicResult VulkanRuntime::executeSpectraResidentPostDemosaic(
        const SpectraResidentPostDemosaicRequest& request
) noexcept {
    SpectraResidentPostDemosaicResult rejected{};
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
    SpectraResidentPostDemosaicResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraResidentPostDemosaicBackend_.execute(
                physicalDevice, device, queue, commandPool, *allocator, request
        );
    }
    completeSubmission();
    return result;
}

SpectraResidentPostDemosaicResult VulkanRuntime::executeSpectraResidentPostDemosaicFromTone(
        const SpectraResidentPostDemosaicRequest& request,
        std::uint64_t residentToneGeneration
) noexcept {
    SpectraResidentPostDemosaicResult rejected{};
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
    SpectraResidentPostDemosaicResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer toneBuffer = VK_NULL_HANDLE;
        std::uint64_t toneBytes = 0u;
        std::uint32_t width = 0u;
        std::uint32_t height = 0u;
        if (!spectraResidentToneBackend_.resolveResidentOutput(
                residentToneGeneration, toneBuffer, toneBytes, width, height) ||
            width != request.frameWidth || height != request.frameHeight) {
            result.attempted = true;
            result.status = "RESIDENT_TONE_GENERATION_UNAVAILABLE";
            result.failureReason = "POST_TONE_RESIDENT_GENERATION_MISMATCH";
        } else {
            SpectraResidentPostDemosaicRequest residentRequest = request;
            residentRequest.rgbData = nullptr;
            residentRequest.residentInputBuffer = toneBuffer;
            residentRequest.residentInputBytes = toneBytes;
            result = spectraResidentPostDemosaicBackend_.execute(
                    physicalDevice, device, queue, commandPool, *allocator, residentRequest);
        }
    }
    completeSubmission();
    return result;
}

SpectraResidentPreDemosaicResult VulkanRuntime::executeSpectraResidentPreDemosaicPass0(
        const SpectraResidentPreDemosaicRequest& request
) noexcept {
    SpectraResidentPreDemosaicResult rejected{};
    rejected.attempted = true;
    if (!request.pass0Only || request.residentInputGeneration != 0u) {
        rejected.status = "SPECTRA_PASS0_REQUEST_INVALID";
        rejected.failureReason = "Pass0 requires pass0Only=true and a host source mosaic.";
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
    SpectraResidentPreDemosaicResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraResidentPreDemosaicBackend_.executePass1(
                physicalDevice, device, queue, commandPool, *allocator, request);
    }
    completeSubmission();
    return result;
}

SpectraResidentPreDemosaicResult VulkanRuntime::executeSpectraResidentPreDemosaicPass0FromRawNormalize(
        const SpectraResidentPreDemosaicRequest& request,
        std::uint64_t rawNormalizeGeneration
) noexcept {
    SpectraResidentPreDemosaicResult rejected{};
    rejected.attempted = true;
    rejected.pass0Only = request.pass0Only;
    if (!request.pass0Only || rawNormalizeGeneration == 0u ||
        request.residentInputGeneration != 0u ||
        request.externalResidentInputBuffer != VK_NULL_HANDLE) {
        rejected.status = "SPECTRA_PASS0_RESIDENT_NORMALIZE_REQUEST_INVALID";
        rejected.failureReason = "Pass0 resident-normalize handoff requires pass0Only=true and one opaque normalize generation.";
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

    SpectraResidentPreDemosaicResult result{};
    {
        // Resolve + consume in one runtime transaction. A later normalization execute cannot
        // recycle the producer buffer between generation validation and Pass-0 dispatch.
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer normalizedMosaic = VK_NULL_HANDLE;
        std::uint64_t normalizedBytes = 0u;
        std::uint32_t normalizedWidth = 0u;
        std::uint32_t normalizedHeight = 0u;
        if (!rawJpegNormalizeBackend_.resolveResidentOutput(
                    rawNormalizeGeneration, normalizedMosaic, normalizedBytes,
                    normalizedWidth, normalizedHeight) ||
            normalizedWidth != request.frameWidth || normalizedHeight != request.frameHeight) {
            result.attempted = true;
            result.pass0Only = true;
            result.status = "SPECTRA_PASS0_RESIDENT_NORMALIZE_INPUT_UNAVAILABLE";
            result.failureReason = "RAW_JPEG_NORMALIZE_RESIDENT_GENERATION_INVALID";
        } else {
            SpectraResidentPreDemosaicRequest resolved = request;
            resolved.mosaicData = nullptr;
            resolved.rowStrideFloats = request.frameWidth;
            resolved.residentInputGeneration = 0u;
            resolved.externalResidentInputBuffer = normalizedMosaic;
            resolved.externalResidentInputBytes = normalizedBytes;
            result = spectraResidentPreDemosaicBackend_.executePass1(
                    physicalDevice, device, queue, commandPool, *allocator, resolved);
        }
    }
    completeSubmission();
    return result;
}

SpectraResidentPreDemosaicResult VulkanRuntime::executeSpectraResidentPreDemosaicPass1FromRawNormalize(
        const SpectraResidentPreDemosaicRequest& request,
        std::uint64_t rawNormalizeGeneration
) noexcept {
    SpectraResidentPreDemosaicResult rejected{};
    rejected.attempted = true;
    if (request.pass0Only || rawNormalizeGeneration == 0u ||
        request.residentInputGeneration != 0u ||
        request.externalResidentInputBuffer != VK_NULL_HANDLE) {
        rejected.status = "SINGLE_FRAME_PASS1_RESIDENT_NORMALIZE_REQUEST_INVALID";
        rejected.failureReason = "Direct physical Pass1 handoff requires pass0Only=false and one opaque normalize generation.";
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

    SpectraResidentPreDemosaicResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer normalizedMosaic = VK_NULL_HANDLE;
        std::uint64_t normalizedBytes = 0u;
        std::uint32_t normalizedWidth = 0u;
        std::uint32_t normalizedHeight = 0u;
        if (!rawJpegNormalizeBackend_.resolveResidentOutput(
                    rawNormalizeGeneration, normalizedMosaic, normalizedBytes,
                    normalizedWidth, normalizedHeight) ||
            normalizedWidth != request.frameWidth || normalizedHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "SINGLE_FRAME_PASS1_RESIDENT_NORMALIZE_INPUT_UNAVAILABLE";
            result.failureReason = "RAW_JPEG_NORMALIZE_RESIDENT_GENERATION_INVALID";
        } else {
            SpectraResidentPreDemosaicRequest resolved = request;
            resolved.mosaicData = nullptr;
            resolved.rowStrideFloats = request.frameWidth;
            resolved.residentInputGeneration = 0u;
            resolved.externalResidentInputBuffer = normalizedMosaic;
            resolved.externalResidentInputBytes = normalizedBytes;
            result = spectraResidentPreDemosaicBackend_.executePass1(
                    physicalDevice, device, queue, commandPool, *allocator, resolved);
        }
    }
    completeSubmission();
    return result;
}

SpectraResidentPreDemosaicResult VulkanRuntime::executeSpectraResidentPreDemosaicPass1(
        const SpectraResidentPreDemosaicRequest& request
) noexcept {
    SpectraResidentPreDemosaicResult rejected{};
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
    SpectraResidentPreDemosaicResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraResidentPreDemosaicBackend_.executePass1(
                physicalDevice, device, queue, commandPool, *allocator, request
        );
    }
    completeSubmission();
    return result;
}


SpectraResidentChromaResult VulkanRuntime::executeSpectraResidentPass2(
        const SpectraResidentPass2Request& request
) noexcept {
    SpectraResidentChromaResult rejected{};
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
    SpectraResidentChromaResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraResidentChromaBackend_.executePass2(
                physicalDevice, device, queue, commandPool, *allocator, request);
    }
    completeSubmission();
    return result;
}

SpectraResidentChromaResult VulkanRuntime::executeSpectraResidentPass2FromPass1(
        const SpectraResidentPass2Request& request
) noexcept {
    SpectraResidentChromaResult rejected{};
    rejected.attempted = true;
    if (request.residentInputGeneration == 0u) {
        rejected.status = "PASS1_RESIDENT_GENERATION_MISSING";
        rejected.failureReason = "Pass-2 resident handoff requires a non-zero Pass-1 generation.";
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

    SpectraResidentChromaResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer residentInput = VK_NULL_HANDLE;
        std::uint64_t residentBytes = 0u;
        std::uint32_t residentWidth = 0u;
        std::uint32_t residentHeight = 0u;
        if (!spectraResidentPreDemosaicBackend_.resolveResidentOutput(
                    request.residentInputGeneration,
                    residentInput,
                    residentBytes,
                    residentWidth,
                    residentHeight)) {
            result.attempted = true;
            result.status = "PASS1_RESIDENT_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque Pass-1 resident generation could not be resolved.";
        } else if (residentWidth != request.frameWidth || residentHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "PASS1_RESIDENT_DIMENSION_MISMATCH";
            result.failureReason = "Resolved Pass-1 resident frame dimensions do not match Pass-2.";
        } else {
            result = spectraResidentChromaBackend_.executePass2FromResident(
                    physicalDevice,
                    device,
                    queue,
                    commandPool,
                    *allocator,
                    residentInput,
                    residentBytes,
                    request);
        }
    }
    completeSubmission();
    return result;
}

bool VulkanRuntime::readbackSpectraResidentPass1(
        std::uint64_t generation,
        std::vector<float>& output
) noexcept {
    output.clear();
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete() || generation == 0u) {
            return false;
        }
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
        device = handles_.device;
        queue = handles_.computeQueue;
    }

    bool ok = false;
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        ok = spectraResidentPreDemosaicBackend_.readbackResidentOutput(
                device, queue, generation, output);
    }
    completeSubmission();
    return ok;
}

SpectraResidentChromaResult VulkanRuntime::executeSpectraResidentPass3(
        const SpectraResidentPass3Request& request
) noexcept {
    SpectraResidentChromaResult rejected{};
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
    SpectraResidentChromaResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        result = spectraResidentChromaBackend_.executePass3(
                physicalDevice, device, queue, commandPool, *allocator, request);
    }
    completeSubmission();
    return result;
}


SpectraResidentChromaResult VulkanRuntime::executeSpectraResidentPass3FromPass2(
        const SpectraResidentPass3Request& request
) noexcept {
    SpectraResidentChromaResult rejected{};
    rejected.attempted = true;
    if (request.residentInputGeneration == 0u) {
        rejected.status = "PASS2_RESIDENT_GENERATION_MISSING";
        rejected.failureReason = "Pass-3 resident handoff requires a non-zero Pass-2 generation.";
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

    SpectraResidentChromaResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer residentInput = VK_NULL_HANDLE;
        std::uint64_t residentBytes = 0u;
        std::uint32_t residentWidth = 0u;
        std::uint32_t residentHeight = 0u;
        if (!spectraResidentChromaBackend_.resolveResidentOutput(
                    request.residentInputGeneration,
                    residentInput,
                    residentBytes,
                    residentWidth,
                    residentHeight)) {
            result.attempted = true;
            result.status = "PASS2_RESIDENT_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque Pass-2 resident generation could not be resolved.";
        } else if (residentWidth != request.frameWidth || residentHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "PASS2_RESIDENT_DIMENSION_MISMATCH";
            result.failureReason = "Resolved Pass-2 frame dimensions do not match Pass-3.";
        } else {
            result = spectraResidentChromaBackend_.executePass3FromResident(
                    physicalDevice, device, queue, commandPool, *allocator,
                    residentInput, residentBytes, request);
        }
    }
    completeSubmission();
    return result;
}

SpectraResidentChromaResult VulkanRuntime::executeSpectraResidentPass3FromPreDemosaic(
        const SpectraResidentPass3Request& request
) noexcept {
    SpectraResidentChromaResult rejected{};
    rejected.attempted = true;
    if (request.residentInputGeneration == 0u) {
        rejected.status = "PASS3_PRE_DEMOSAIC_RESIDENT_GENERATION_MISSING";
        rejected.failureReason = "Resident pre-demosaic generation is required.";
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
    SpectraResidentChromaResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer residentInput = VK_NULL_HANDLE;
        std::uint64_t residentBytes = 0u;
        std::uint32_t residentWidth = 0u;
        std::uint32_t residentHeight = 0u;
        if (!spectraResidentPreDemosaicBackend_.resolveResidentOutput(
                    request.residentInputGeneration, residentInput, residentBytes,
                    residentWidth, residentHeight)) {
            result.attempted = true;
            result.status = "PASS3_PRE_DEMOSAIC_RESIDENT_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque Pass-0/Pass-1 generation could not be resolved.";
        } else if (residentWidth != request.frameWidth || residentHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "PASS3_PRE_DEMOSAIC_RESIDENT_DIMENSION_MISMATCH";
            result.failureReason = "Resolved pre-demosaic dimensions do not match Pass 3.";
        } else {
            result = spectraResidentChromaBackend_.executePass3FromResident(
                    physicalDevice, device, queue, commandPool, *allocator,
                    residentInput, residentBytes, request);
        }
    }
    completeSubmission();
    return result;
}

SpectraPass3PlannerResult VulkanRuntime::executeSpectraPass3PlannerFromPass2(
        const SpectraPass3PlannerRequest& request
) noexcept {
    SpectraPass3PlannerResult rejected{};
    rejected.attempted = true;
    if (request.residentInputGeneration == 0u) {
        rejected.status = "PASS3_PLANNER_RESIDENT_GENERATION_MISSING";
        rejected.failureReason = "Compact Pass-3 planning requires a resident Pass-2 generation.";
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
    SpectraPass3PlannerResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer residentInput = VK_NULL_HANDLE;
        std::uint64_t residentBytes = 0u;
        std::uint32_t residentWidth = 0u;
        std::uint32_t residentHeight = 0u;
        if (!spectraResidentChromaBackend_.resolveResidentOutput(
                    request.residentInputGeneration, residentInput, residentBytes,
                    residentWidth, residentHeight)) {
            result.attempted = true;
            result.status = "PASS3_PLANNER_RESIDENT_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque Pass-2 resident generation could not be resolved.";
        } else if (residentWidth != request.frameWidth || residentHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "PASS3_PLANNER_RESIDENT_DIMENSION_MISMATCH";
            result.failureReason = "Resolved Pass-2 dimensions do not match planner request.";
        } else {
            result = spectraPass3PlannerBackend_.executeFromResident(
                    physicalDevice, device, queue, commandPool, *allocator,
                    residentInput, residentBytes, request);
        }
    }
    completeSubmission();
    return result;
}

SpectraPass3PlannerResult VulkanRuntime::executeSpectraPass3PlannerFromPreDemosaic(
        const SpectraPass3PlannerRequest& request
) noexcept {
    SpectraPass3PlannerResult rejected{};
    rejected.attempted = true;
    if (request.residentInputGeneration == 0u) {
        rejected.status = "PASS3_PLANNER_RESIDENT_GENERATION_MISSING";
        rejected.failureReason = "Compact planning requires a resident Pass-0/Pass-1 generation.";
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
    SpectraPass3PlannerResult result{};
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        VkBuffer residentInput = VK_NULL_HANDLE;
        std::uint64_t residentBytes = 0u;
        std::uint32_t residentWidth = 0u;
        std::uint32_t residentHeight = 0u;
        if (!spectraResidentPreDemosaicBackend_.resolveResidentOutput(
                    request.residentInputGeneration, residentInput, residentBytes,
                    residentWidth, residentHeight)) {
            result.attempted = true;
            result.status = "PRE_DEMOSAIC_RESIDENT_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque Pass-0/Pass-1 resident generation could not be resolved.";
        } else if (residentWidth != request.frameWidth || residentHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "PRE_DEMOSAIC_RESIDENT_DIMENSION_MISMATCH";
            result.failureReason = "Resolved pre-demosaic dimensions do not match planner request.";
        } else {
            result = spectraPass3PlannerBackend_.executeFromResident(
                    physicalDevice, device, queue, commandPool, *allocator,
                    residentInput, residentBytes, request);
        }
    }
    completeSubmission();
    return result;
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

bool VulkanRuntime::readbackSpectraResidentChroma(
        std::uint64_t generation,
        std::vector<float>& output
) noexcept {
    output.clear();
    if (generation == 0u) return false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != RuntimeState::READY || !handles_.complete()) return false;
        inFlightSubmissionCount_.fetch_add(1, std::memory_order_acq_rel);
    }
    bool ok = false;
    {
        std::lock_guard<std::mutex> submitLock(submissionMutex_);
        ok = spectraResidentChromaBackend_.readbackResidentOutput(generation, output);
    }
    completeSubmission();
    return ok;
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

SpectraRawFinalizeResult VulkanRuntime::executeSpectraRawFinalizeFromPass3(
        const SpectraRawFinalizeRequest& request
) noexcept {
    SpectraRawFinalizeResult rejected{};
    rejected.attempted = true;
    if (request.residentInputGeneration == 0u) {
        rejected.status = "RAW_FINALIZE_PASS3_RESIDENT_GENERATION_MISSING";
        rejected.failureReason = "Resident Pass-3 generation is required.";
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
        if (!spectraResidentChromaBackend_.resolveResidentOutput(
                    request.residentInputGeneration, residentInput, residentBytes,
                    residentWidth, residentHeight)) {
            result.attempted = true;
            result.status = "RAW_FINALIZE_PASS3_RESIDENT_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque Pass-3 resident generation could not be resolved.";
        } else if (residentWidth != request.frameWidth || residentHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "RAW_FINALIZE_PASS3_RESIDENT_DIMENSION_MISMATCH";
            result.failureReason = "Resolved Pass-3 dimensions do not match RAW finalizer request.";
        } else {
            result = spectraRawFinalizeBackend_.executeFromResident(
                    physicalDevice, device, queue, commandPool, *allocator,
                    residentInput, residentBytes, request);
        }
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("RAW_FINALIZE_FROM_PASS3");
        return result;
    }
    completeSubmission();
    return result;
}


SpectraRawFinalizeResult VulkanRuntime::executeSpectraRawFinalizeFromChroma(
        const SpectraRawFinalizeRequest& request
) noexcept {
    // Pass 2 and Pass 3 generations share VulkanSpectraResidentChromaBackend's
    // opaque generation namespace. The legacy Pass3-named entrypoint already
    // resolves that backend correctly; keep it as the implementation owner.
    return executeSpectraRawFinalizeFromPass3(request);
}

SpectraRawFinalizeResult VulkanRuntime::executeSpectraRawFinalizeFromPreDemosaic(
        const SpectraRawFinalizeRequest& request
) noexcept {
    SpectraRawFinalizeResult rejected{};
    rejected.attempted = true;
    if (request.residentInputGeneration == 0u) {
        rejected.status = "RAW_FINALIZE_PRE_DEMOSAIC_RESIDENT_GENERATION_MISSING";
        rejected.failureReason = "Resident Pass0/Pass1 generation is required.";
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
        if (!spectraResidentPreDemosaicBackend_.resolveResidentOutput(
                    request.residentInputGeneration, residentInput, residentBytes,
                    residentWidth, residentHeight)) {
            result.attempted = true;
            result.status = "RAW_FINALIZE_PRE_DEMOSAIC_RESIDENT_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque Pass0/Pass1 resident generation could not be resolved.";
        } else if (residentWidth != request.frameWidth || residentHeight != request.frameHeight) {
            result.attempted = true;
            result.status = "RAW_FINALIZE_PRE_DEMOSAIC_RESIDENT_DIMENSION_MISMATCH";
            result.failureReason = "Resolved pre-demosaic dimensions do not match RAW finalizer request.";
        } else {
            result = spectraRawFinalizeBackend_.executeFromResident(
                    physicalDevice, device, queue, commandPool, *allocator,
                    residentInput, residentBytes, request);
        }
    }
    if (result.submissionMayRemainInFlight) {
        markGpuStalled("RAW_FINALIZE_FROM_PRE_DEMOSAIC");
        return result;
    }
    completeSubmission();
    return result;
}

bool VulkanRuntime::readbackSpectraRawFinalizeResident(
        std::uint64_t generation,
        std::vector<float>& output
) noexcept {
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
        if (!spectraRawFinalizeBackend_.resolveResidentOutput(
                    rawFinalizeGeneration, residentInput, residentBytes,
                    residentWidth, residentHeight)) {
            result.attempted = true;
            result.cpuFallbackRequired = true;
            result.status = "RAW_FINALIZE_RESIDENT_GENERATION_UNAVAILABLE";
            result.failureReason = "Opaque RAW-finalize generation could not be resolved.";
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

SpectraRawFinalizeResult VulkanRuntime::executeSpectraRawFinalizeFromRawNormalize(
        const SpectraRawFinalizeRequest& request,
        std::uint64_t rawNormalizeGeneration
) noexcept {
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
        dedicatedPreviewQueue =
            handles_.previewQueue != VK_NULL_HANDLE &&
            handles_.previewCommandPool != VK_NULL_HANDLE;
        const bool isolatedPreviewCommandPool = handles_.previewCommandPool != VK_NULL_HANDLE;
        queue = dedicatedPreviewQueue ? handles_.previewQueue : handles_.computeQueue;
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
    if (spectraOpponentBackend_.productionKernelConnected()) {
        result.activeProductionStages.push_back("SPECTRA_FP32_OPPONENT_FEATURES");
        result.activeProductionStages.push_back("SPECTRA_FP32_OPPONENT_FEATURES_STRIPED");
    }
    if (spectraVisibleChromaBackend_.productionKernelConnected()) {
        result.activeProductionStages.push_back("SPECTRA_FP32_VISIBLE_CHROMA_CANDIDATE");
        result.activeProductionStages.push_back("SPECTRA_FP32_VISIBLE_CHROMA_CANDIDATE_STRIPED");
    }
    if (spectraResidentPostDemosaicBackend_.productionKernelConnected()) {
        result.activeProductionStages.push_back("SPECTRA_FP32_POST_DEMOSAIC_CHAIN_RESIDENT");
        result.activeProductionStages.push_back("SPECTRA_FP32_SPATIAL_NR_VISIBLE_CHROMA_RESIDENT_STRIPED");
    }
    if (spectraResidentPreDemosaicBackend_.productionKernelConnected()) {
        result.activeProductionStages.push_back("SPECTRA_FP32_PRE_DEMOSAIC_PASS1_RESIDENT");
        result.activeProductionStages.push_back("SPECTRA_FP32_PASS1_VST_ANISOTROPIC_NO_REGRET_GPU_PRIMARY");
    }
    if (spectraResidentChromaBackend_.productionKernelConnected()) {
        result.activeProductionStages.push_back("SPECTRA_FP32_PRE_DEMOSAIC_PASS2_PASS3_RESIDENT");
        result.activeProductionStages.push_back("SPECTRA_FP32_PASS2_CHROMA_GPU_PRIMARY");
        result.activeProductionStages.push_back("SPECTRA_FP32_PASS3_LOW_BANDING_GPU_PRIMARY");
    }
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
        if (spectraResidentPostDemosaicBackend_.productionKernelConnected()) {
            result.activeProductionStages.push_back("SPECTRA_FP32_TONE_TO_POST_DEMOSAIC_RESIDENT_HANDOFF");
        }
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
        << (spectraOpponentBackend_.productionKernelConnected() ||
                spectraVisibleChromaBackend_.productionKernelConnected() ||
                spectraResidentPostDemosaicBackend_.productionKernelConnected()
                ? "SPECTRA GPU stages connected; resident final-decision path preferred when compiled"
                : "none")
        << '\n';
    return out.str();
}

}  // namespace bncam::vulkan
