#pragma once

#include "VulkanRuntimeBootstrap.h"
#include "VulkanRuntimeContracts.h"
#include "VulkanValidationCollector.h"
#include "VulkanSpectraOpponentBackend.h"
#include "VulkanSpectraVisibleChromaBackend.h"
#include "VulkanSpectraResidentPostDemosaicBackend.h"
#include "VulkanSpectraResidentPreDemosaicBackend.h"
#include "VulkanSpectraResidentChromaBackend.h"
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

#include <atomic>
#include <condition_variable>
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


    /**
     * Milestone 8F striped, persistent-buffer SPECTRA FP32 opponent-feature stage.
     * The method never exposes Vulkan handles and always returns a typed CPU fallback reason.
     */
    SpectraOpponentExecutionResult executeSpectraOpponentFeatures(
            const SpectraOpponentExecutionRequest& request
    ) noexcept;

    /** Milestone 8G shadow/qualified visible-chroma candidate filter. */
    SpectraVisibleChromaExecutionResult executeSpectraVisibleChromaCandidate(
            const SpectraVisibleChromaExecutionRequest& request
    ) noexcept;

    /** Milestone 8H resident post-demosaic chain: spatial NR + visible-chroma final decision; CPU only on typed fallback. */
    SpectraResidentPostDemosaicResult executeSpectraResidentPostDemosaic(
            const SpectraResidentPostDemosaicRequest& request
    ) noexcept;

    /** Milestone 8H-K resident tone -> spatial NR -> visible-chroma handoff with zero RGB re-upload. */
    SpectraResidentPostDemosaicResult executeSpectraResidentPostDemosaicFromTone(
            const SpectraResidentPostDemosaicRequest& request,
            std::uint64_t residentToneGeneration
    ) noexcept;

    /** Phase 10 GPU Pass 0 + on-device No-Regret blend; output remains in the shared pre-demosaic ping-pong backend. */
    SpectraResidentPreDemosaicResult executeSpectraResidentPreDemosaicPass0(
            const SpectraResidentPreDemosaicRequest& request
    ) noexcept;

    /**
     * Phase 13 resident RAW-normalize -> SPECTRA Pass-0 handoff.
     * Resolves the opaque normalize generation under submissionMutex_ and never exposes VkBuffer to callers.
     */
    SpectraResidentPreDemosaicResult executeSpectraResidentPreDemosaicPass0FromRawNormalize(
            const SpectraResidentPreDemosaicRequest& request,
            std::uint64_t rawNormalizeGeneration
    ) noexcept;

    /** Milestone 8H-B GPU-primary pre-demosaic Pass 1 + on-device No-Regret blend. */
    SpectraResidentPreDemosaicResult executeSpectraResidentPreDemosaicPass1(
            const SpectraResidentPreDemosaicRequest& request
    ) noexcept;

    /**
     * Single-frame physical baseline RAW-normalize -> Pass-1 handoff.
     * This is the SPECTRA-Off counterpart to the Pass-0 handoff: the normalized RAW
     * stays resident and enters the existing VST/Wiener + structure-aware Pass-1 kernel
     * directly, without exposing VkBuffer handles or materializing a host float mosaic.
     */
    SpectraResidentPreDemosaicResult executeSpectraResidentPreDemosaicPass1FromRawNormalize(
            const SpectraResidentPreDemosaicRequest& request,
            std::uint64_t rawNormalizeGeneration
    ) noexcept;

    /** Milestone 8H-C GPU-primary Pass 2 chroma bands. */
    SpectraResidentChromaResult executeSpectraResidentPass2(
            const SpectraResidentPass2Request& request
    ) noexcept;

    /**
     * Milestone 8H-G resident Pass-1 -> Pass-2 handoff.
     * Resolves the opaque Pass-1 generation inside VulkanRuntime so no VkBuffer
     * handle or full-frame mosaic crosses back through IspCore.
     */
    SpectraResidentChromaResult executeSpectraResidentPass2FromPass1(
            const SpectraResidentPass2Request& request
    ) noexcept;

    /** Materializes Pass-1 only when a downstream branch cannot consume resident input. */
    bool readbackSpectraResidentPass1(
            std::uint64_t generation,
            std::vector<float>& output
    ) noexcept;

    /** Milestone 8H-C GPU-primary Pass 3 low-frequency/banding application. */
    SpectraResidentChromaResult executeSpectraResidentPass3(
            const SpectraResidentPass3Request& request
    ) noexcept;

    /** Milestone 8H-G resident Pass-2 -> Pass-3 handoff; Pass-2 planning readback is not re-uploaded. */
    SpectraResidentChromaResult executeSpectraResidentPass3FromPass2(
            const SpectraResidentPass3Request& request
    ) noexcept;

    /** Phase 10 resident Pass-0/Pass-1 -> Pass-3 handoff when Pass 2 is budget-skipped. */
    SpectraResidentChromaResult executeSpectraResidentPass3FromPreDemosaic(
            const SpectraResidentPass3Request& request
    ) noexcept;

    /** Milestone 8H-H compact GPU Pass-3 planner from the resident Pass-2 mosaic. */
    SpectraPass3PlannerResult executeSpectraPass3PlannerFromPass2(
            const SpectraPass3PlannerRequest& request
    ) noexcept;

    /** Phase 10 compact planner/observer from a resident Pass-0/Pass-1 mosaic. */
    SpectraPass3PlannerResult executeSpectraPass3PlannerFromPreDemosaic(
            const SpectraPass3PlannerRequest& request
    ) noexcept;

    /** Phase 13 exact compact RAW noise-map observation from the resident JPEG-normalized mosaic. */
    SpectraNoiseMapPlannerResult executeRawNoiseMapPlannerFromNormalize(
            const SpectraNoiseMapPlannerRequest& request,
            std::uint64_t rawNormalizeGeneration
    ) noexcept;

    /** Explicit fail-safe readback of the latest resident Pass-2/Pass-3 chroma mosaic. */
    bool readbackSpectraResidentChroma(
            std::uint64_t generation,
            std::vector<float>& output
    ) noexcept;

    /** Milestone 8H-D GPU-primary temporal observer; CPU only performs compact fit/reduction. */
    SpectraTemporalObserverResult executeSpectraTemporalObserver(
            const SpectraTemporalObserverRequest& request
    ) noexcept;

    /** Milestone 8H-I GPU-primary JPEG-only RAW defect/green/lens finalization. */
    SpectraRawFinalizeResult executeSpectraRawFinalize(
            const SpectraRawFinalizeRequest& request
    ) noexcept;

    /** Resident Pass-3 -> RAW-finalize handoff without exposing VkBuffer to IspCore. */
    SpectraRawFinalizeResult executeSpectraRawFinalizeFromPass3(
            const SpectraRawFinalizeRequest& request
    ) noexcept;

    /** Phase 10 generic resident Pass2/Pass3 chroma handoff. */
    SpectraRawFinalizeResult executeSpectraRawFinalizeFromChroma(
            const SpectraRawFinalizeRequest& request
    ) noexcept;

    /** Phase 10 resident Pass0/Pass1 pre-demosaic handoff when chroma passes are skipped. */
    SpectraRawFinalizeResult executeSpectraRawFinalizeFromPreDemosaic(
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

    /** Prepare immutable RAW-preview Vulkan resources without requiring a RAW frame. */
    bool prepareRawPreviewBackend() noexcept;

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
    VulkanSpectraOpponentBackend spectraOpponentBackend_;
    VulkanSpectraVisibleChromaBackend spectraVisibleChromaBackend_;
    VulkanSpectraResidentPostDemosaicBackend spectraResidentPostDemosaicBackend_;
    VulkanSpectraResidentPreDemosaicBackend spectraResidentPreDemosaicBackend_;
    VulkanSpectraResidentChromaBackend spectraResidentChromaBackend_;
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
