from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CPP = ROOT / "app" / "src" / "main" / "cpp"
TRAIN = ROOT / "tools" / "spectra_neural_training"


def text(path: str) -> str:
    return (CPP / path).read_text(encoding="utf-8")


def orchestration_body() -> str:
    cpp = text("vulkan/VulkanRuntime.cpp")
    start = cpp.index("executeSpectraNeuralThenRawFinalizeFromRawNormalize")
    end = cpp.index("executeSpectraRawFinalizeFromRawNormalize", start)
    return cpp[start:end]


def test_phase5_audit_closes_all_eleven_masterprompt_tasks_and_keeps_device_gate_truthful():
    audit = (TRAIN / "PHASE5_AUDIT.md").read_text(encoding="utf-8")
    numbered = re.findall(r"(?m)^([1-9]|1[01])\. \*\*", audit)
    assert len(numbered) == 11
    assert "External device gate" in audit
    assert "not** claimed" in audit or "not**" in audit
    assert "full Android/NDK application build" in audit


def test_single_frame_order_is_hard_physical_then_neural_then_remaining_lsc_then_demosaic_handoff():
    body = orchestration_body()
    hard = body.index("spectraRawFinalizeBackend_.executeFromResident")
    neural = body.index("spectraNeuralProductionBridge_.execute")
    lsc = body.index("spectraNeuralRemainingLscBackend_.execute")
    publish = body.index("GPU_RAW_FINALIZE_NEURAL_REMAINING_LSC_PRIMARY_DEMOSAIC_HANDOFF_READY")
    assert hard < neural < lsc < publish


def test_exact_bypass_is_before_split_stages_and_failure_paths_reuse_one_baseline_lambda():
    body = orchestration_body()
    decision = body.index("decideNeuralInvocation")
    bypass = body.index("if (!decision.runInference || !conditioningValid)")
    hard = body.index("SpectraRawFinalizeRequest hardRequest")
    assert decision < bypass < hard
    assert body.count("runExactBaselineFinalize()") >= 5
    assert "cpuNeural" not in body
    assert "NeuralReference" not in body


def test_raw10_and_raw_sensor_share_same_production_neural_request_path():
    cpp = text("IspCore.cpp")
    start = cpp.index("// Phase 5 production neural integration")
    end = cpp.index("} else {", cpp.index("executeSpectraNeuralThenRawFinalizeFromRawNormalize", start))
    body = cpp[start:end]
    assert "RawCaptureDomain::Raw10" in body
    assert "RawCaptureDomain::RawSensor" in body
    assert "meta.isRaw10" in body
    assert "Honor" not in body
    assert "manufacturer" not in body.lower()


def test_post_neural_generation_is_accepted_by_existing_resident_demosaic_entry():
    cpp = text("vulkan/VulkanRuntime.cpp")
    start = cpp.index("executeSpectraResidentDemosaicFromRawFinalize")
    end = cpp.index("executeSpectraResidentDemosaic(", start)
    body = cpp[start:end]
    assert "spectraRawFinalizeBackend_.resolveResidentOutput" in body
    assert "spectraNeuralRemainingLscBackend_.resolveResidentOutput" in body
    assert "executeFromResidentMosaic" in body


def test_all_current_production_demosaic_routes_remain_mapped_after_neural_handoff():
    cpp = text("IspCore.cpp")
    for token in (
        "SpectraGpuDemosaicAlgorithm::MALVAR_2004",
        "SpectraGpuDemosaicAlgorithm::RCD_INSPIRED",
        "SpectraGpuDemosaicAlgorithm::AMAZE_INSPIRED",
        "SpectraGpuDemosaicAlgorithm::AUTO_HYBRID",
    ):
        assert token in cpp


def test_stage_dumps_are_opt_in_and_do_not_pollute_production_cpu_readback_counter():
    h = text("vulkan/VulkanRuntime.h")
    body = orchestration_body()
    assert "bool collectStageDumps = false;" in h
    assert "debugStageDumpReadbackBytes" in h
    assert "trace.fullFrameCpuReadbackBytes = neuralResult.fullFrameCpuReadbackBytes;" in body
    assert "trace.debugStageDumpReadbackBytes = stageDumps.debugReadbackBytes;" in body
    assert "trace.fullFrameCpuReadbackBytes += stageDumps" not in body


def test_latency_memory_and_debug_overhead_are_exported():
    cpp = text("IspCore.cpp")
    for token in (
        "spectraNeuralPersistentGpuBytes=",
        "spectraNeuralProductionFullFrameCpuReadbackBytes=",
        "spectraNeuralPrePhysicalMs=",
        "spectraNeuralInferenceWallMs=",
        "spectraNeuralRemainingLscMs=",
        "spectraNeuralTotalWallMs=",
        "spectraNeuralDebugStageDumpReadbackBytes=",
        "spectraNeuralDebugStageDumpReadbackMs=",
        "spectraNeuralDebugStageDumpWriteMs=",
    ):
        assert token in cpp


def test_no_removed_classical_or_cpu_neural_owner_is_resurrected_by_phase5():
    paths = [
        CPP / "SpectraNeuralProductionPolicy.h",
        CPP / "vulkan" / "VulkanNeuralRawProductionBridge.cpp",
        CPP / "vulkan" / "VulkanNeuralRemainingLscBackend.cpp",
        CPP / "vulkan" / "VulkanRuntime.cpp",
    ]
    combined = "\n".join(p.read_text(encoding="utf-8") for p in paths).lower()
    for forbidden in (
        "spectracontextfusionnoregret",
        "spectrachromanoregret",
        "spectralowfrequencycorrectionceiling",
        "spectramultiscalecontext",
        "applyspectrapass0",
        "applyspectrapass1",
        "cpuneural",
        "neuralreference",
        "wiener",
    ):
        assert forbidden not in combined


def test_gain_split_is_truthful_and_post_raw_boost_is_not_neural_raw_gain():
    cpp = text("IspCore.cpp")
    start = cpp.index("// RAW_SENSOR/RAW10 sensitivity is SENSOR_SENSITIVITY")
    end = cpp.index("switch (meta.calibration.spectraProcessingMode)", start)
    body = cpp[start:end]
    assert "framePhysics.analogGain = 1.0f" in body
    assert "framePhysics.digitalGain = 1.0f" in body
    assert "explicitGainMetadataValid = false" in body
    assert "post-RAW" in body and "sensitivity boost" in body  # documentation explicitly rejects it
    gain_assignments = [
        line for line in body.splitlines()
        if "framePhysics.analogGain =" in line or "framePhysics.digitalGain =" in line
    ]
    assert all("postRawSensitivityBoost" not in line for line in gain_assignments)
