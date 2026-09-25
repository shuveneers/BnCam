#pragma once

#include "VulkanRuntimeBootstrap.h"
#include "VulkanRuntimeContracts.h"
#include "VulkanValidationCollector.h"
#include "VulkanSpectraTemporalObserverBackend.h"
#include "VulkanSpectraResidentDemosaicBackend.h"
#include "VulkanRawPreviewBackend.h"
#include "VulkanRawCaptureBackend.h"
#include "VulkanRawMultiFrameBackend.h"
#include "VulkanRawJpegNormalizeBackend.h"
#include "VulkanYuvMultiFrameBackend.h"
#include "VulkanYuvSingleFrameBackend.h"
#include "VulkanYuvExposureStatisticsBackend.h"
#include "VulkanSpectraPass3PlannerBackend.h"
#include "VulkanSpectraRawFinalizeBackend.h"
#include "VulkanSpectraResidentToneBackend.h"

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {


/**
 * The only authoritative Vulkan runtime owner in BnCam.
 *
 * This object is process/native-engine scoped. Camera sessions and captures may borrow it later,
 * but they may never create or destroy the instance/device. No Vulkan handles are exposed to JNI.
 */
class VulkanRuntime final {
public:
    static VulkanRuntime& instance();

    VulkanRuntime(const VulkanRuntime&) = delete;
    VulkanRuntime& operator=(const VulkanRuntime&) = delete;

    RuntimeSnapshot initialize(const RuntimeConfig& config) noexcept;
    RuntimeSnapshot shutdown() noexcept;
    RuntimeSnapshot snapshot() const;
    CapabilitySnapshot capabilities() const;
    ValidationSnapshot validationSnapshot() const;

    std::string diagnosticsJson() const;
    std::string diagnosticsHumanReadable() const;

    /** Future compute submission ownership hooks. */
    bool tryRegisterSubmission() noexcept;
    void completeSubmission() noexcept;

    /** Circuit breaker and queue safety quarantine. */
    void markGpuStalled(const std::string& stageName) noexcept;
    bool isGpuStalled() const noexcept;
    std::string firstFailingStage() const noexcept;
    void resetShotCircuitBreaker() noexcept;
    void quarantineRuntime(const std::string& reason) noexcept;
    bool isQuarantined() const noexcept;

    /** Phase 13 exact compact RAW noise-map observation from the resident JPEG-normalized mosaic. */
    SpectraNoiseMapPlannerResult executeRawNoiseMapPlannerFromNormalize(
            const SpectraNoiseMapPlannerRequest& request,
            std::uint64_t rawNormalizeGeneration
    ) noexcept;

    /** Milestone 8H-D GPU-primary temporal observer; CPU only performs compact fit/reduction. */
    SpectraTemporalObserverResult executeSpectraTemporalObserver(
            const SpectraTemporalObserverRequest& request
    ) noexcept;

    /** Milestone 8H-I GPU-primary JPEG-only RAW defect/green/lens finalization. */
    SpectraRawFinalizeResult executeSpectraRawFinalize(
            const SpectraRawFinalizeRequest& request
    ) noexcept;

    /** Failure-recovery only: materialize the exact resident RAW-finalize generation. */
    bool readbackSpectraRawFinalizeResident(
            std::uint64_t generation,
            std::vector<float>& output
    ) noexcept;

    /** Resident RAW-finalize -> demosaic handoff without a full Bayer readback. */
    SpectraResidentDemosaicResult executeSpectraResidentDemosaicFromRawFinalize(
            const SpectraResidentDemosaicRequest& request,
            std::uint64_t rawFinalizeGeneration
    ) noexcept;

    /** Milestone 8H-E GPU-primary bilinear/Malvar demosaic; typed Menon fallback. */
    SpectraResidentDemosaicResult executeSpectraResidentDemosaic(
            const SpectraResidentDemosaicRequest& request
    ) noexcept;

    SpectraResidentColorTransformResult executeSpectraResidentAwbCcm(
            const SpectraResidentColorTransformRequest& request
    ) noexcept;

    /** Capture RAW10/RAW_SENSOR canonicalization. Full-frame pixel unpack/crop/domain mapping runs on Vulkan. */
    RawCaptureCanonicalizeResult executeRawCaptureCanonicalize(
            const RawCaptureCanonicalizeRequest& request
    ) noexcept;

    /** GPU-primary RAW multi-frame canonicalization, alignment, temporal observation and fusion. */
    RawMultiFrameResult executeRawMultiFrame(
            const RawMultiFrameRequest& request
    ) noexcept;

    /** Phase 13: consume Phase-9 resident canonical RAW16 and normalize it for JPEG/SPECTRA without host pixels. */
    RawJpegNormalizeResult executeRawJpegNormalizeFromMultiFrame(
            const RawJpegNormalizeRequest& request,
            std::uint64_t rawMultiFrameGeneration
    ) noexcept;

    /** Phase 13 final production entry: resolve either single-frame capture or multi-frame RAW resident output. */
    RawJpegNormalizeResult executeRawJpegNormalizeFromResidentRaw(
            const RawJpegNormalizeRequest& request,
            std::uint64_t residentRawGeneration
    ) noexcept;

    /** SPECTRA-off resident handoff: normalized RAW goes directly to RAW finalization without host pixels. */
    SpectraRawFinalizeResult executeSpectraRawFinalizeFromRawNormalize(
            const SpectraRawFinalizeRequest& request,
            std::uint64_t rawNormalizeGeneration
    ) noexcept;


    /** Phase 11: GPU-primary YUV luma alignment with compact score readback only. */
    YuvMultiFrameAlignmentResult executeYuvMultiFrameAlignment(
            const YuvMultiFrameAlignmentRequest& request
    ) noexcept;

    /** Phase 12: GPU-primary single-frame YUV tone/color/rotation with one JPEG-boundary BGR24 readback. */
    YuvSingleFrameIspResult executeYuvSingleFrameIsp(
            const YuvSingleFrameIspRequest& request
    ) noexcept;

    /** Phase 2 compact Vulkan-primary YUV histogram/exposure statistics. */
    YuvExposureStatisticsResult executeYuvExposureStatistics(
            const YuvExposureStatisticsRequest& request
    ) noexcept;

    /** Prepare the YUV single-frame capture pipeline without requiring a capture frame. */
    bool prepareYuvSingleFrameBackend() noexcept;

    /** Prepare immutable RAW-preview Vulkan resources without requiring a RAW frame. */
    bool prepareRawPreviewBackend() noexcept;

    /**
     * Prepare persistent full-resolution RAW single-frame still resources for the exact active
     * RAW stream dimensions. Allocation-only: no pixel processing or resident generation.
     */
    bool prepareRawSingleFrameWorkingSet(
            std::uint32_t frameWidth,
            std::uint32_t frameHeight
    ) noexcept;

    /** Fused preview-only RAW demosaic/colour/tone/RGBA path. */
    RawPreviewGpuResult executeRawPreview(const RawPreviewGpuRequest& request) noexcept;

    /**
     * Exact Android HardwareBuffer usage mask required for an RGBA8 storage+sampled preview image.
     * Returns zero when the current Vulkan device cannot import that image contract.
     */
    std::uint64_t rawPreviewOutputHardwareBufferUsage() const noexcept;

    /** Phase 9: consume upstream-protected post-CCM RGB; pre-tone cleanup + compact scene observer. */
    SpectraResidentSceneObserverResult executeSpectraResidentSceneObserverFromAwbCcm(
            const SpectraResidentSceneObserverRequest& request,
            std::uint64_t residentColorGeneration
    ) noexcept;

    /** 8H-J resident tone/vibrance/profile-colour processing. */
    SpectraResidentToneResult executeSpectraResidentTone(
            const SpectraResidentToneRequest& request
    ) noexcept;

    /** Failure-only readback of an already computed resident tone generation. */
    bool readbackSpectraResidentTone(
            std::uint64_t residentToneGeneration,
            std::vector<float>& outputRgb,
            std::uint32_t& width,
            std::uint32_t& height,
            std::string& failureReason
    ) noexcept;

private:
    VulkanRuntime();
    ~VulkanRuntime() = default;

    RuntimeSnapshot snapshotLocked() const;
    void recordLifecycleEventLocked(const std::string& event, const std::string& detail);
    static std::string createRuntimeIdentity();

    mutable std::mutex mutex_;
    std::condition_variable stateChanged_;
    RuntimeState state_ = RuntimeState::UNINITIALIZED;
    std::string runtimeIdentity_;
    RuntimeConfig config_;
    CapabilitySnapshot capabilities_ = CapabilitySnapshot::notScanned();
    RuntimeFailure lastFailure_;
    OwnedRuntimeHandles handles_;
    ValidationCollector validationCollector_;
    VulkanSpectraTemporalObserverBackend spectraTemporalObserverBackend_;
    VulkanSpectraResidentDemosaicBackend spectraResidentDemosaicBackend_;
    VulkanRawPreviewBackend rawPreviewBackend_;
    VulkanRawCaptureBackend rawCaptureBackend_;
    VulkanRawMultiFrameBackend rawMultiFrameBackend_;
    VulkanRawJpegNormalizeBackend rawJpegNormalizeBackend_;
    VulkanYuvMultiFrameBackend yuvMultiFrameBackend_;
    VulkanYuvSingleFrameBackend yuvSingleFrameBackend_;
    VulkanYuvExposureStatisticsBackend yuvExposureStatisticsBackend_;
    VulkanSpectraPass3PlannerBackend spectraPass3PlannerBackend_;
    VulkanSpectraRawFinalizeBackend spectraRawFinalizeBackend_;
    VulkanSpectraResidentToneBackend spectraResidentToneBackend_;
    // Serializes resident RAW/SPECTRA orchestration across finalize/readback and the optional Neural path.
    // Kept independent from submissionMutex_: this guards multi-stage resource-generation ownership,
    // while submissionMutex_ provides Vulkan queue external synchronization.
    std::mutex neuralOrchestrationMutex_;
    std::mutex submissionMutex_;
    // RAW preview uses this independent external-synchronization domain only when bootstrap
    // provides a dedicated second queue and command pool. Single-queue devices keep using
    // submissionMutex_ so VkQueue external synchronization remains valid.
    std::mutex previewSubmissionMutex_;
    std::uint64_t instanceCreationCount_ = 0;
    std::uint64_t deviceCreationCount_ = 0;
    std::uint64_t initializeRequestCount_ = 0;
    std::uint64_t shutdownRequestCount_ = 0;
    std::atomic<std::uint64_t> inFlightSubmissionCount_{0};
    std::atomic<bool> gpuStalled_{false};
    std::atomic<bool> quarantined_{false};
    std::string firstFailingStage_ = "none";
    std::string quarantineReason_ = "none";

    struct LifecycleEvent {
        std::uint64_t timestampEpochMs = 0;
        std::string event;
        std::string detail;
    };
    std::vector<LifecycleEvent> lifecycleEvents_;
};

}  // namespace bncam::vulkan
