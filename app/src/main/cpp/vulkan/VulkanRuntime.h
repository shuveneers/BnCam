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
#include "VulkanNeuralRawDenoiseBackend.h"
#include "VulkanNeuralRawProductionBridge.h"
#include "VulkanNeuralRemainingLscBackend.h"

#include <array>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace bncam::vulkan {
namespace neural {

/**
 * Capture-local Phase-5 request. The normalized RAW VkBuffer is deliberately
 * absent: VulkanRuntime resolves that opaque resident generation internally.
 */
struct SpectraNeuralProductionRequest {
    bncam::spectra::neural::NeuralProductionPreparedContext prepared{};
    bncam::spectra::neural::SpectraNeuralConditioningConfig conditioningConfig{};
    bncam::spectra::neural::NeuralRuntimeReadiness runtimeReadiness{};
    const float* remainingLscMap = nullptr;
    std::uint32_t remainingLscWidth = 0u;
    std::uint32_t remainingLscHeight = 0u;
    std::uint32_t remainingLscChannels = 0u;
    std::uint64_t remainingLscGeneration = 0u;
    // Avoids a compact post-LSC observer dispatch for fixed demosaic modes.
    bool collectAutoSceneMetrics = true;
    // Developer-only exact scene-stage dumps. Normal production capture keeps this false,
    // therefore the resident neural path performs zero full-frame host readback.
    bool collectStageDumps = false;
    std::uint64_t generationId = 0u;
};

struct SpectraNeuralStageDumps {
    bool requested = false;
    bool preNeuralHardPhysicalReady = false;
    bool postNeuralReady = false;
    bool postRemainingLscReady = false;
    std::uint32_t width = 0u;
    std::uint32_t height = 0u;
    std::uint32_t stageCount = 0u;
    std::uint64_t debugReadbackBytes = 0u;
    float debugReadbackMs = 0.0f;
    std::vector<float> preNeuralHardPhysicalMosaic;
    std::vector<float> postNeuralMosaic;
    std::vector<float> postRemainingLscMosaic;
    std::string status = "NOT_REQUESTED";
};

/** Compact telemetry only. No Vulkan resource handle escapes the runtime. */
struct SpectraNeuralProductionTrace {
    bool attempted = false;
    bool neuralPublished = false;
    bool originalPublished = true;
    bool modelAvailable = false;
    bool exactPreflightBypass = false;
    bool hardPhysicalCorrectionApplied = false;
    bool remainingLscApplied = false;
    bool sourceClipProvenancePreserved = false;
    std::uint32_t neuralKernelDispatches = 0u;
    std::uint32_t bridgeKernelDispatches = 0u;
    std::uint64_t compactMetadataUploadBytes = 0u;
    bool posteriorSummaryReady = false;
    std::array<float, 4> posteriorMeanVarianceCfa{{0.0f, 0.0f, 0.0f, 0.0f}};
    std::uint64_t compactPosteriorReadbackBytes = 0u;
    std::uint64_t persistentGpuBytes = 0u;
    std::uint64_t fullFrameCpuReadbackBytes = 0u;
    bool cpuFallbackUsed = false;
    bool stageDumpsRequested = false;
    bool stageDumpsCollected = false;
    std::uint32_t stageDumpCount = 0u;
    std::uint64_t debugStageDumpReadbackBytes = 0u;
    float debugStageDumpReadbackMs = 0.0f;
    float prePhysicalMs = 0.0f;
    float neuralWallMs = 0.0f;
    float remainingLscMs = 0.0f;
    float totalWallMs = 0.0f;
    bncam::spectra::neural::NeuralBypassReason bypassReason =
            bncam::spectra::neural::NeuralBypassReason::None;
    bncam::spectra::neural::NeuralBackendFailureCode failureCode =
            bncam::spectra::neural::NeuralBackendFailureCode::None;
    std::string status = "NOT_RUN";
};

} // namespace neural


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


    /**
     * Phase 5: configure one release-approved Student package on the existing
     * process-scoped Vulkan runtime. Test/unapproved packages fail closed.
     */
    bool configureSpectraNeuralModel(
            const void* packageBytes,
            std::size_t packageSize,
            bool releaseApproved,
            std::uint32_t inFlightSlots = 3u
    ) noexcept;

    /** Clear neural model/resources without changing the authoritative Vulkan runtime. */
    void clearSpectraNeuralModel() noexcept;

    /** True only after a release-approved package has initialized successfully. */
    bool spectraNeuralModelAvailable() const noexcept;

    /**
     * Phase 5 production handoff:
     * normalized resident RAW -> exact preflight bypass OR hard physical correction ->
     * neural -> remaining software LSC -> demosaic-resident generation.
     * Normal production capture performs no full-frame host readback. When developer-only
     * collectStageDumps is explicitly requested, exact pre-neural/post-neural/post-LSC Bayer
     * buffers may be copied to host through the separate stageDumpsOut observability channel.
     * No Vulkan handle leaves this method.
     */
    SpectraRawFinalizeResult executeSpectraNeuralThenRawFinalizeFromRawNormalize(
            const SpectraRawFinalizeRequest& request,
            const neural::SpectraNeuralProductionRequest& neuralRequest,
            std::uint64_t rawNormalizeGeneration,
            neural::SpectraNeuralProductionTrace* traceOut = nullptr,
            neural::SpectraNeuralStageDumps* stageDumpsOut = nullptr
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
    neural::VulkanNeuralRawDenoiseBackend spectraNeuralRawDenoiseBackend_;
    neural::VulkanNeuralRawProductionBridge spectraNeuralProductionBridge_;
    neural::VulkanNeuralRemainingLscBackend spectraNeuralRemainingLscBackend_;
    std::atomic<bool> spectraNeuralModelAvailable_{false};
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
