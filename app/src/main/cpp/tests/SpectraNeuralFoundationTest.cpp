#include "../NeuralRawDenoiseBackend.h"
#include "../SpectraNeuralTelemetry.h"

#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <type_traits>

using namespace bncam::spectra::neural;

namespace {

bool near(float a, float b, float eps = 1.0e-6f) {
    return std::abs(a - b) <= eps;
}

SpectraCoreSnapshot makeValidSnapshot(int sensorArrangement = bncam::raw::CFA_RGGB) {
    SpectraCoreSnapshot snapshot{};
    snapshot.frameId = 42;
    snapshot.rawWidth = 4000;
    snapshot.rawHeight = 3000;
    snapshot.cfa = resolveCanonicalBayerPack(sensorArrangement, 0, 0);
    snapshot.blackLevelRaw = {{64.0f, 64.0f, 64.0f, 64.0f}};
    snapshot.whiteLevelRaw = {{1023.0f, 1023.0f, 1023.0f, 1023.0f}};
    snapshot.rawLevelsValid = true;

    snapshot.noise.shotS = {{0.010f, 0.011f, 0.012f, 0.013f}};
    snapshot.noise.readO = {{0.0010f, 0.0011f, 0.0012f, 0.0013f}};
    snapshot.noise.trust = 0.90f;

    snapshot.metadataTrust.noiseProfile = 0.90f;
    snapshot.metadataTrust.blackLevel = 0.85f;
    snapshot.metadataTrust.whiteLevel = 0.95f;
    snapshot.metadataTrust.lensShading = 0.80f;
    snapshot.metadataTrust.exposureGain = 0.90f;
    snapshot.metadataTrust.sensorDomain = 0.92f;

    snapshot.blackResidual.maxAbsResidualNormalized = 0.01f;
    snapshot.blackResidual.residualNormalized = {{0.0f, 0.0f, 0.0f, 0.0f}};
    snapshot.blackResidual.trust = 0.85f;
    snapshot.blackResidual.dynamicBlackPriorUsed = true;

    snapshot.remainingLsc.lensShadingAlreadyApplied = false;
    snapshot.remainingLsc.remainingCorrectionExpected = false;
    snapshot.remainingLsc.hasSpatialGainMap = false;
    snapshot.remainingLsc.minGain = 1.0f;
    snapshot.remainingLsc.medianGain = 1.0f;
    snapshot.remainingLsc.maxGain = 1.0f;
    snapshot.remainingLsc.mapTrust = 0.80f;

    snapshot.structuredNoise.rowPeriodicity = 0.10f;
    snapshot.structuredNoise.columnPeriodicity = 0.05f;
    snapshot.structuredNoise.fixedPattern = 0.15f;
    snapshot.structuredNoise.dsnuLike = 0.08f;
    snapshot.structuredNoise.prnuLike = 0.04f;
    snapshot.structuredNoise.lowFrequencyResidual = 0.12f;
    snapshot.structuredNoise.lowFrequencyChroma = 0.07f;
    snapshot.structuredNoise.channelImbalance = 0.03f;
    snapshot.structuredNoise.spatialBlackDrift = 0.02f;
    snapshot.structuredNoise.rareReadoutPattern = 0.01f;
    snapshot.structuredNoise.confidence = 0.75f;
    snapshot.structuredNoise.dominantRowPeriodPixels = 16.0f;
    snapshot.structuredNoise.dominantColumnPeriodPixels = 0.0f;
    return snapshot;
}

NeuralDenoiseControls makeEnabledControls() {
    NeuralDenoiseControls controls{};
    controls.enabled = true;
    controls.noiseReduction = 0.80f;
    controls.lumaNoise = 0.75f;
    controls.chromaNoise = 0.70f;
    controls.detailProtection = 0.90f;
    controls.lowFrequencyCleanup = 0.50f;
    controls.adaptiveResponse = 0.60f;
    return controls;
}

NeuralRuntimeReadiness makeReadyRuntime() {
    NeuralRuntimeReadiness runtime{};
    runtime.conditioningSchemaCompatible = true;
    runtime.modelPresent = true;
    runtime.modelIntegrityVerified = true;
    runtime.modelSchemaCompatible = true;
    runtime.backendAvailable = true;
    runtime.oodSafe = true;
    return runtime;
}

NeuralResourceView makeResource(
        std::uint64_t token,
        std::uint32_t width,
        std::uint32_t height,
        std::uint32_t channels,
        NeuralResourceAccess access) {
    NeuralResourceView view{};
    view.kind = NeuralResourceKind::VulkanBuffer;
    view.elementType = NeuralElementType::Fp16;
    view.access = access;
    view.token = token;
    view.width = width;
    view.height = height;
    view.channels = channels;
    view.rowStrideBytes = width * channels * 2u;
    return view;
}

} // namespace

int main() {
    static_assert(kCanonicalCfaChannelCount == 4, "Neural Bayer pack must remain four-channel");
    static_assert(kBaseSpatialConditioningChannelCount == 14, "Base conditioning ABI changed");
    static_assert(std::is_standard_layout<SpectraCoreSnapshot>::value,
                  "Core snapshot should remain a simple value contract");

    // Golden CFA vectors: every Android Bayer arrangement maps to the same
    // semantic [R, G1(horizontal-to-R), G2(vertical-to-R), B] channel order.
    {
        const auto rggb = resolveCanonicalBayerPack(bncam::raw::CFA_RGGB, 0, 0);
        const auto grbg = resolveCanonicalBayerPack(bncam::raw::CFA_GRBG, 0, 0);
        const auto gbrg = resolveCanonicalBayerPack(bncam::raw::CFA_GBRG, 0, 0);
        const auto bggr = resolveCanonicalBayerPack(bncam::raw::CFA_BGGR, 0, 0);
        assert(rggb.valid() && grbg.valid() && gbrg.valid() && bggr.valid());

        assert(rggb.sourceOffsets[0].x == 0 && rggb.sourceOffsets[0].y == 0);
        assert(rggb.sourceOffsets[1].x == 1 && rggb.sourceOffsets[1].y == 0);
        assert(rggb.sourceOffsets[2].x == 0 && rggb.sourceOffsets[2].y == 1);
        assert(rggb.sourceOffsets[3].x == 1 && rggb.sourceOffsets[3].y == 1);

        assert(grbg.sourceOffsets[0].x == 1 && grbg.sourceOffsets[0].y == 0);
        assert(grbg.sourceOffsets[1].x == 0 && grbg.sourceOffsets[1].y == 0);
        assert(grbg.sourceOffsets[2].x == 1 && grbg.sourceOffsets[2].y == 1);
        assert(grbg.sourceOffsets[3].x == 0 && grbg.sourceOffsets[3].y == 1);

        assert(gbrg.sourceOffsets[0].x == 0 && gbrg.sourceOffsets[0].y == 1);
        assert(gbrg.sourceOffsets[1].x == 1 && gbrg.sourceOffsets[1].y == 1);
        assert(gbrg.sourceOffsets[2].x == 0 && gbrg.sourceOffsets[2].y == 0);
        assert(gbrg.sourceOffsets[3].x == 1 && gbrg.sourceOffsets[3].y == 0);

        assert(bggr.sourceOffsets[0].x == 1 && bggr.sourceOffsets[0].y == 1);
        assert(bggr.sourceOffsets[1].x == 0 && bggr.sourceOffsets[1].y == 1);
        assert(bggr.sourceOffsets[2].x == 1 && bggr.sourceOffsets[2].y == 0);
        assert(bggr.sourceOffsets[3].x == 0 && bggr.sourceOffsets[3].y == 0);

        // Crop parity must alter the effective Bayer phase instead of silently
        // treating every crop as RGGB.
        const auto shifted = resolveCanonicalBayerPack(bncam::raw::CFA_RGGB, 1, 0);
        assert(shifted.effectiveBayerPattern == bncam::raw::CFA_GRBG);
        for (std::size_t i = 0; i < 4; ++i) {
            assert(shifted.sourceOffsets[i].x == grbg.sourceOffsets[i].x);
            assert(shifted.sourceOffsets[i].y == grbg.sourceOffsets[i].y);
        }

        assert(!resolveCanonicalBayerPack(bncam::raw::CFA_RGB, 0, 0).valid());
        assert(!rggb.supportsExtent(3999, 3000));
        assert(rggb.supportsExtent(4000, 3000));
    }

    // Golden normalization/noise/LSC/headroom vectors.
    {
        const auto normalized = normalizeRawSample(164.0f, 64.0f, 1064.0f);
        assert(normalized.valid && near(normalized.value, 0.10f));
        assert(!normalizeRawSample(10.0f, 100.0f, 100.0f).valid);

        auto snapshot = makeValidSnapshot();
        assert(snapshot.structurallyValid());
        assert(snapshot.neuralConditioningReady());
        assert(near(snapshot.noise.variance(CanonicalCfaChannel::R, 0.25f), 0.0035f));
        assert(near(snapshot.noise.sigma(CanonicalCfaChannel::R, 0.25f), std::sqrt(0.0035f)));
        assert(near(varianceAfterGain(0.01f, 2.0f), 0.04f));
        assert(near(sigmaAfterGain(0.1f, 2.0f), 0.2f));
        assert(near(headroomForNormalizedSignal(0.95f, 0.10f), 0.50f));

        SpectraNeuralConditioningConfig config{};
        config.headroomSpan = 0.10f;
        config.clippingEpsilon = 0.01f;
        const std::array<float, 4> raw{{0.10f, 0.20f, 0.30f, 0.95f}};
        const std::array<float, 4> ignoredLsc{{2.0f, 2.0f, 2.0f, 2.0f}};
        const auto cell = buildSpatialConditioningCell(snapshot, raw, ignoredLsc, config);
        assert(cell.valid);
        assert(near(cell.values[0], 0.10f));
        assert(near(cell.values[3], 0.95f));
        // No remaining LSC map: condition must be exact identity even if a
        // caller passes unrelated values.
        for (std::size_t i = 8; i < 12; ++i) {
            assert(near(cell.values[i], 1.0f));
        }
        assert(near(cell.values[12], 0.80f));
        assert(near(cell.values[13], 0.50f));
        assert(cell.clipped[3] == 0u);
    }

    // Required-LSC state is explicit and fail-closed.
    {
        auto snapshot = makeValidSnapshot();
        snapshot.remainingLsc.remainingCorrectionExpected = true;
        assert(snapshot.structurallyValid());
        assert(!snapshot.neuralConditioningReady());

        const auto decision = decideNeuralInvocation(snapshot, makeEnabledControls(), makeReadyRuntime());
        assert(!decision.runInference);
        assert(decision.bypassReason == NeuralBypassReason::MissingRequiredLsc);
    }

    // Exact bypass: disabled and zero-authority states never reach a backend.
    {
        const auto snapshot = makeValidSnapshot();
        auto controls = makeEnabledControls();
        const auto runtime = makeReadyRuntime();

        controls.enabled = false;
        auto decision = decideNeuralInvocation(snapshot, controls, runtime);
        assert(!decision.runInference);
        assert(decision.bypassReason == NeuralBypassReason::UserDisabled);

        controls.enabled = true;
        controls.noiseReduction = 0.0f;
        decision = decideNeuralInvocation(snapshot, controls, runtime);
        assert(!decision.runInference);
        assert(decision.bypassReason == NeuralBypassReason::ZeroAuthority);

        controls = makeEnabledControls();
        auto unavailable = runtime;
        unavailable.backendAvailable = false;
        decision = decideNeuralInvocation(snapshot, controls, unavailable);
        assert(!decision.runInference);
        assert(decision.bypassReason == NeuralBypassReason::BackendUnavailable);

        decision = decideNeuralInvocation(snapshot, controls, runtime);
        assert(decision.runInference);
        assert(near(decision.masterAuthority, 0.80f));
    }

    // Backend-neutral resource ABI and fail-closed publication semantics.
    {
        NeuralRawDenoiseRequest request{};
        request.core = makeValidSnapshot();
        request.framePhysics.captureDomain = RawCaptureDomain::RawSensor;
        request.framePhysics.exposureTimeSeconds = 1.0 / 30.0;
        request.framePhysics.analogGain = 4.0f;
        request.framePhysics.digitalGain = 1.0f;
        request.framePhysics.bitDepth = 12;
        request.conditioningConfig.headroomSpan = 0.10f;
        request.conditioningConfig.clippingEpsilon = 0.01f;
        request.controls = makeEnabledControls();

        const std::uint32_t pw = request.core.cfa.packedWidth(request.core.rawWidth);
        const std::uint32_t ph = request.core.cfa.packedHeight(request.core.rawHeight);
        request.packedNormalizedRawInput = makeResource(1, pw, ph, 4, NeuralResourceAccess::ReadOnly);
        request.cleanPackedRawOutput = makeResource(2, pw, ph, 4, NeuralResourceAccess::WriteOnly);
        request.posteriorVarianceOutput = makeResource(3, pw, ph, 4, NeuralResourceAccess::WriteOnly);
        assert(request.validResourceShape());

        const auto decision = decideNeuralInvocation(request.core, request.controls, makeReadyRuntime());
        assert(decision.runInference);

        NeuralRawDenoiseResult result{};
        result.status = NeuralBackendStatus::Completed;
        result.cleanRawWritten = true;
        result.posteriorVarianceWritten = false;
        assert(selectNeuralPublicationSource(decision, result) == NeuralPublicationSource::OriginalInput);

        result.posteriorVarianceWritten = true;
        assert(selectNeuralPublicationSource(decision, result) == NeuralPublicationSource::NeuralOutput);

        result.status = NeuralBackendStatus::Failed;
        result.failureCode = NeuralBackendFailureCode::DispatchFailed;
        assert(selectNeuralPublicationSource(decision, result) == NeuralPublicationSource::OriginalInput);
    }

    // Telemetry observes the same physics but cannot change the decision.
    {
        const auto snapshot = makeValidSnapshot();
        const auto input = makeInputTelemetry(snapshot);
        assert(near(input.shotS[0], 0.010f));
        assert(near(input.readO[3], 0.0013f));
        assert(near(input.conservativeMetadataTrust, 0.80f));
        assert(near(input.blackLevelTrust, 0.85f));

        const auto safety = makeBypassSafetyTelemetry(
            NeuralBypassReason::ModelIntegrityFailed,
            ModelIntegrityStatus::HashMismatch);
        assert(safety.backendStatus == NeuralBackendStatus::Bypassed);
        assert(safety.bypassReason == NeuralBypassReason::ModelIntegrityFailed);
        assert(safety.modelIntegrity == ModelIntegrityStatus::HashMismatch);
    }

    return 0;
}
