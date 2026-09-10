from pathlib import Path
import json
import numpy as np

ROOT = Path(__file__).resolve().parents[3]
CPP = ROOT / "src/main/cpp"


def text(path: str) -> str:
    return (CPP / path).read_text(encoding="utf-8")


def orchestration_body() -> str:
    cpp = text("vulkan/VulkanRuntime.cpp")
    start = cpp.index("executeSpectraNeuralThenRawFinalizeFromRawNormalize")
    end = cpp.index("executeSpectraRawFinalizeFromRawNormalize", start)
    return cpp[start:end]


def test_runtime_owns_remaining_lsc_backend_and_destroys_it():
    h = text("vulkan/VulkanRuntime.h")
    cpp = text("vulkan/VulkanRuntime.cpp")
    assert "VulkanNeuralRemainingLscBackend spectraNeuralRemainingLscBackend_;" in h
    assert "spectraNeuralRemainingLscBackend_.destroy(handles_.device)" in cpp


def test_release_model_readiness_comes_from_validated_runtime_not_caller_aliases():
    body = orchestration_body()
    assert "const bool modelReady = spectraNeuralModelAvailable();" in body
    assert "readiness.conditioningSchemaCompatible = conditioningValid;" in body
    assert "readiness.modelPresent = modelReady;" in body
    assert "readiness.modelIntegrityVerified = modelReady;" in body
    assert "readiness.modelSchemaCompatible = modelReady;" in body
    assert "readiness.modelAvailable" not in body


def test_exact_preflight_bypass_precedes_split_physical_and_neural_stages():
    body = orchestration_body()
    decision = body.index("decideNeuralInvocation")
    bypass = body.index("if (!decision.runInference || !conditioningValid)")
    hard = body.index("SpectraRawFinalizeRequest hardRequest")
    neural = body.index("spectraNeuralProductionBridge_.execute")
    lsc = body.index("spectraNeuralRemainingLscBackend_.execute")
    assert decision < bypass < hard < neural < lsc


def test_exact_bypass_reruns_historical_full_finalize_from_immutable_normalized_raw():
    body = orchestration_body()
    baseline = body[body.index("runExactBaselineFinalize"):body.index("// Preflight", body.index("runExactBaselineFinalize"))]
    assert "normalizedInput" in baseline
    assert "normalizedBytes" in baseline
    assert "spectraRawFinalizeBackend_.executeFromResident" in baseline
    assert "request);" in baseline
    # Every fail-bypass path resolves through the same lambda; no CPU image fallback is introduced here.
    assert body.count("runExactBaselineFinalize()") >= 5
    assert "readbackSpectra" not in body
    # Full-frame host vectors are allowed only in the explicit developer stage-dump
    # observability lambda; the exact bypass lambda itself stays resident and vector-free.
    assert "std::vector<float>" not in baseline


def test_hard_physical_stage_removes_only_remaining_lsc_and_spatial_exposure():
    body = orchestration_body()
    block = body[body.index("SpectraRawFinalizeRequest hardRequest"):body.index("SpectraRawFinalizeResult hardPhysical")]
    assert "hardRequest = request" in block
    assert "hardRequest.lensShadingMap = nullptr" in block
    assert "hardRequest.lensShadingColumns = 0u" in block
    assert "hardRequest.lensShadingRows = 0u" in block
    assert "hardRequest.lensShadingGenerationId = 0u" in block
    assert "hardRequest.adaptiveExposureEnabled = false" in block
    assert "autoDemosaicEvidenceRequested" not in block


def test_neural_consumes_hard_physical_bayer_not_normalized_source():
    body = orchestration_body()
    assert "gpuRequest.normalizedBayerInput = hardPhysicalBuffer;" in body
    assert "gpuRequest.normalizedBayerBytes = imageBytes;" in body
    neural_pos = body.index("spectraNeuralProductionBridge_.execute")
    hard_pos = body.index("hardPhysical = spectraRawFinalizeBackend_.executeFromResident")
    assert hard_pos < neural_pos


def test_remaining_lsc_consumes_neural_bayer_and_preserves_original_clip_transport():
    body = orchestration_body()
    assert "lscRequest.bayerInput = neuralResult.downstreamBayerBuffer;" in body
    assert "lscRequest.sourceClipTransport = hardPhysicalBuffer;" in body
    assert "lscRequest.sourceClipTransportBytes = hardPhysicalBytes;" in body
    assert "lscRequest.lensShadingMap = request.lensShadingMap;" in body
    assert "lscResult.sourceClipConfidenceMapPreserved" in body


def test_remaining_lsc_backend_copies_clip_tail_byte_for_byte_and_has_no_full_frame_readback():
    cpp = text("vulkan/VulkanNeuralRemainingLscBackend.cpp")
    assert "clipCopy.srcOffset = static_cast<VkDeviceSize>(imageBytes);" in cpp
    assert "clipCopy.dstOffset = static_cast<VkDeviceSize>(imageBytes);" in cpp
    assert "clipCopy.size = static_cast<VkDeviceSize>(clipBytes);" in cpp
    assert "vkCmdCopyBuffer(commandBuffer_, request.sourceClipTransport, output_.buffer" in cpp
    assert "result.sourceClipConfidenceMapPreserved = true;" in cpp
    assert "outputMosaic.resize" not in cpp
    assert "readbackResident" not in cpp


def test_remaining_lsc_shader_is_lsc_identity_plus_compact_observer_only():
    shader = text("vulkan/shaders/neural_remaining_lsc.comp")
    assert "void applyRemainingLsc()" in shader
    assert "value = sceneClamp(value * gain);" in shader
    assert "void sampleAutoScene()" in shader
    assert "denoise" not in shader.lower().replace("never denoises", "")
    for forbidden in ("wiener", "bilateral", "median", "blur", "convolution"):
        assert forbidden not in shader.lower()


def test_demosaic_accepts_both_historical_and_post_neural_resident_generations():
    cpp = text("vulkan/VulkanRuntime.cpp")
    start = cpp.index("executeSpectraResidentDemosaicFromRawFinalize")
    end = cpp.index("executeSpectraResidentDemosaic(", start)
    body = cpp[start:end]
    assert "spectraRawFinalizeBackend_.resolveResidentOutput" in body
    assert "spectraNeuralRemainingLscBackend_.resolveResidentOutput" in body
    assert "executeFromResidentMosaic" in body


def test_build_module_attaches_remaining_lsc_shader_and_backend():
    module = text("vulkan/cmake/SpectraNeuralBackend.cmake")
    assert "neural_remaining_lsc|NeuralRemainingLscSpirv.h|getNeuralRemainingLscSpirv" in module
    assert "VulkanNeuralRemainingLscBackend.cpp" in module
    assert "BNCAM_NEURAL_REMAINING_LSC_SHADER_AVAILABLE" in module


def test_isp_wires_real_spectra_evidence_and_neutralizes_unavailable_gain_split():
    cpp = text("IspCore.cpp")
    start = cpp.index("// Phase 5 production neural integration")
    end = cpp.index("} else {", cpp.index("executeSpectraNeuralThenRawFinalizeFromRawNormalize", start))
    body = cpp[start:end]
    assert "pass0State.rowPatternConfidence" in body
    assert "pass0State.columnPatternConfidence" in body
    assert "pass0State.channelBiasConfidence" in body
    assert "lowBandEvidence.residualPressure" in body
    assert "meta.calibration.effectiveS" in body
    assert "meta.calibration.effectiveO" in body
    assert "meta.lensShadingMap" in body
    assert "RawCaptureDomain::Raw10" in body
    assert "RawCaptureDomain::RawSensor" in body
    assert "meta.captureExposureTimeNs" in body
    assert "jpegRaw.info.nativeBitDepth" in body
    assert "framePhysics.analogGain = 1.0f" in body
    assert "framePhysics.digitalGain = 1.0f" in body
    assert "explicitGainMetadataValid = false" in body
    # CONTROL_POST_RAW_SENSITIVITY_BOOST is not a RAW gain and must not feed
    # either frozen gain compatibility slot. ISO is not converted into an
    # invented analog-gain scalar either.
    gain_lines = [line for line in body.splitlines() if "framePhysics.analogGain" in line or "framePhysics.digitalGain" in line]
    assert all("postRawSensitivityBoost" not in line for line in gain_lines)
    assert all("Iso" not in line and "ISO" not in line for line in gain_lines)


def test_frozen_student_v1_global_film_is_exactly_zero_so_neutral_gain_is_identity():
    contract = ROOT / "tools/spectra_neural_training/tests/golden/student_v1_contract"
    manifest = json.loads((contract / "model_manifest.json").read_text(encoding="utf-8"))
    assert manifest["architecture_config"]["global_lowres_context"] is False
    weights = (contract / manifest["weights_file"]).read_bytes()
    film_tensors = [item for item in manifest["tensor_manifest"] if ".film.proj." in item["name"]]
    assert film_tensors
    for item in film_tensors:
        offset = int(item["byte_offset"])
        length = int(item["byte_length"])
        values = np.frombuffer(weights[offset:offset + length], dtype=np.dtype("<f2"))
        assert np.count_nonzero(values) == 0, item["name"]


def test_missing_gain_split_is_not_a_permanent_ood_gate_for_student_v1():
    policy = text("SpectraNeuralProductionPolicy.h")
    gate = policy[policy.index("if (!out.core.remainingLsc.hasRequiredCondition())"):policy.index("out.structuralOodSafe = true;")]
    assert "explicitGainMetadataValid ||" not in gate
    assert "!input.explicitGainMetadataValid" not in gate
    assert "if (!out.framePhysics.valid())" in gate

def test_conditioning_constants_match_frozen_phase3_reference_contract():
    cpp = text("IspCore.cpp")
    assert "conditioningConfig.logSigmaFloor = 1.0e-8f" in cpp
    assert "conditioningConfig.headroomSpan = 0.08f" in cpp
    assert "conditioningConfig.clippingEpsilon = 0.0f" in cpp


def test_auto_observer_sampling_contract_matches_historical_50k_target():
    cpp = text("vulkan/VulkanNeuralRemainingLscBackend.cpp")
    assert "50000.0" in cpp and "autoStrideFor" in cpp
    shader = text("vulkan/shaders/neural_remaining_lsc.comp")
    for token in (
        "float center = outputAt(x, y);",
        "float signedDx = 0.5 * (outputAt(x + 2, y) - outputAt(x - 2, y));",
        "float signedDy = 0.5 * (outputAt(x, y + 2) - outputAt(x, y - 2));",
        "float gradient = max(dx, dy);",
        "coherentSameSign",
        "center < 0.060 ? 1.0 : 0.0",
    ):
        assert token in shader


def test_no_second_cpu_neural_owner_is_introduced():
    body = orchestration_body()
    assert "cpuFallbackUsed" in body  # telemetry field propagated from backend
    assert "executeSpectraNeuralThenRawFinalizeFromRawNormalize" not in text("IspCore.cpp").split("if (residentEntry && !residentCpuFallbackUsed)")[0]
    assert "NeuralReference" not in body
    assert "cpuNeural" not in body


def test_phase5_stage_dumps_are_developer_only_and_cover_exact_three_bayer_boundaries():
    h = text("vulkan/VulkanRuntime.h")
    body = orchestration_body()
    assert "bool collectStageDumps = false;" in h
    assert "SpectraNeuralStageDumps" in h
    assert "preNeuralHardPhysicalMosaic" in h
    assert "postNeuralMosaic" in h
    assert "postRemainingLscMosaic" in h
    pre = body.index("stageDumps.preNeuralHardPhysicalMosaic")
    post_neural = body.index("stageDumps.postNeuralMosaic")
    post_lsc = body.index("stageDumps.postRemainingLscMosaic")
    assert pre < post_neural < post_lsc
    assert "if (!neuralRequest.collectStageDumps || stageDumpsOut == nullptr) return;" in body


def test_stage_dump_readback_is_separate_from_production_no_cpu_readback_telemetry():
    h = text("vulkan/VulkanRuntime.h")
    body = orchestration_body()
    assert "debugStageDumpReadbackBytes" in h
    assert "debugStageDumpReadbackMs" in h
    assert "trace.fullFrameCpuReadbackBytes = neuralResult.fullFrameCpuReadbackBytes;" in body
    assert "trace.debugStageDumpReadbackBytes = stageDumps.debugReadbackBytes;" in body
    assert "trace.fullFrameCpuReadbackBytes += stageDumps" not in body
    assert "trace.cpuFallbackUsed" in body


def test_stage_dump_readback_is_explicit_transfer_only_not_cpu_pixel_processing():
    cpp = text("vulkan/VulkanRuntime.cpp")
    start = cpp.index("bool readbackNeuralStageFp32")
    end = cpp.index("}  // namespace", start)
    helper = cpp[start:end]
    assert "vkCmdCopyBuffer" in helper
    assert "VK_ACCESS_TRANSFER_READ_BIT" in helper
    assert "VK_ACCESS_HOST_READ_BIT" in helper
    assert "vmaInvalidateAllocation" in helper
    assert "std::memcpy(output.data()" in helper
    for forbidden in ("demosaic", "median", "bilateral", "wiener", "denoise"):
        assert forbidden not in helper.lower()


def test_isp_activates_stage_dumps_only_through_existing_developer_debug_switch():
    cpp = text("IspCore.cpp")
    assert "meta.rawJpegDebugDumpsEnabled && !meta.rawJpegDebugDumpDirectory.empty()" in cpp
    assert "neuralRequest.collectStageDumps ?" in cpp
    assert "writeNeuralStageDumps(" in cpp
    assert "spectra_neural_" in cpp
    assert ".rawf32" in cpp
    assert "bncam.spectra_neural.stage_dump.v1" in cpp


def test_phase5_neural_latency_and_memory_are_exported_to_existing_debug_telemetry():
    cpp = text("IspCore.cpp")
    required = (
        "spectraNeuralPersistentGpuBytes=",
        "spectraNeuralProductionFullFrameCpuReadbackBytes=",
        "spectraNeuralPrePhysicalMs=",
        "spectraNeuralInferenceWallMs=",
        "spectraNeuralRemainingLscMs=",
        "spectraNeuralTotalWallMs=",
        "spectraNeuralDebugStageDumpReadbackBytes=",
        "spectraNeuralDebugStageDumpReadbackMs=",
        "spectraNeuralDebugStageDumpWriteMs=",
    )
    for token in required:
        assert token in cpp


def test_stage_dump_observability_failure_never_grants_or_revokes_pixel_authority():
    body = orchestration_body()
    capture_start = body.index("const auto captureDebugStage")
    capture_end = body.index("const auto runExactBaselineFinalize", capture_start)
    capture = body[capture_start:capture_end]
    assert "stageDumps.status" in capture
    assert "result =" not in capture
    assert "bypassReason" not in capture
    assert "failureCode" not in capture
