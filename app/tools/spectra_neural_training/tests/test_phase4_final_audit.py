from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CPP = ROOT / "app" / "src" / "main" / "cpp"
TRAIN = ROOT / "tools" / "spectra_neural_training"
VULKAN = CPP / "vulkan"
SHADERS = VULKAN / "shaders"


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_phase4_masterprompt_task_surface_is_complete():
    required = [
        VULKAN / "VulkanNeuralModelPackage.cpp",
        VULKAN / "VulkanNeuralExecutionPlan.cpp",
        VULKAN / "VulkanNeuralTensorLayout.h",
        VULKAN / "VulkanNeuralResourceBridge.cpp",
        VULKAN / "VulkanNeuralRawDenoiseBackend.cpp",
        VULKAN / "cmake" / "SpectraNeuralBackend.cmake",
    ]
    for path in required:
        assert path.exists(), path

    shader_names = {
        "neural_condition.comp",
        "neural_conv.comp",
        "neural_film_params.comp",
        "neural_film_apply.comp",
        "neural_gate.comp",
        "neural_add.comp",
        "neural_scaled_add.comp",
        "neural_writeback.comp",
    }
    # Phase 4 owns this exact primitive set; later production phases may add bridge shaders
    # without changing the frozen Phase-4 model graph.
    discovered = {p.name for p in SHADERS.glob("neural_*.comp")}
    assert shader_names <= discovered
    assert discovered - shader_names <= {
        "neural_mosaic_bridge.comp",
        "neural_remaining_lsc.comp",  # Phase-5 production bridge stage, not a Phase-4 model primitive.
    }


def test_backend_keeps_single_runtime_and_phase5_callsite_boundary():
    cmake = _text(VULKAN / "cmake" / "SpectraNeuralBackend.cmake")
    backend = _text(VULKAN / "VulkanNeuralRawDenoiseBackend.cpp")
    bridge = _text(VULKAN / "VulkanNeuralResourceBridge.cpp")
    combined = cmake + backend + bridge

    for forbidden in (
        "vkCreateInstance(",
        "vkCreateDevice(",
        "AHardwareBuffer_lock(",
        "AHardwareBuffer_lockPlanes(",
        "cv::Mat",
        "CPU_FALLBACK",
    ):
        assert forbidden not in combined

    # Phase 4 ships an attachable backend module but must not edit/connect the
    # production IspCore/VulkanRuntime callsite itself.
    assert "IspCore.cpp" not in cmake
    assert "VulkanRuntime.cpp" not in cmake
    assert "bncam_attach_spectra_neural_backend" in cmake


def test_exact_bypass_precedes_import_and_dispatch_and_strength_is_writeback_only():
    backend = _text(VULKAN / "VulkanNeuralRawDenoiseBackend.cpp")
    writeback = _text(SHADERS / "neural_writeback.comp")

    bypass = backend.index("if (!request.controls.valid() || !request.controls.enabled ||")
    assert bypass < backend.index("VulkanNeuralResourceBridge::resolveInput", bypass)
    assert bypass < backend.index("recordAndSubmit", bypass)
    assert "NeuralDisabled" in backend
    assert "ZeroAuthority" in backend
    assert "request.controls.noiseReduction" in backend
    assert "authority" in writeback
    assert "kSigma" in writeback and "tanh" in writeback


def test_future_highlight_reconstruction_boundary_preserves_evidence_only():
    abi = _text(CPP / "NeuralRawDenoiseBackend.h")
    backend = _text(VULKAN / "VulkanNeuralRawDenoiseBackend.cpp")
    writeback = _text(SHADERS / "neural_writeback.comp")
    contract = _text(TRAIN / "PHASE4_BACKEND_CONTRACT.md")

    for token in (
        "originalSaturationEvidenceRequested",
        "originalSaturationMaskOutput",
        "originalHeadroomEvidenceOutput",
    ):
        assert token in abi
    assert "Neural Highlight Reconstruction" in abi
    assert "Neural Highlight Reconstruction" in contract
    assert "reconstruct" not in writeback.lower()
    # Documentation comments may name the future reconstruction boundary; the
    # denoiser must not expose or dispatch a reconstruction owner/kernel.
    for forbidden in ("NeuralHighlightReconstruction", "highlight_reconstruct", "reconstructHighlight"):
        assert forbidden not in backend


def test_gpu_input_async_persistent_and_no_full_frame_cpu_fallback_contracts():
    backend = _text(VULKAN / "VulkanNeuralRawDenoiseBackend.cpp")
    header = _text(VULKAN / "VulkanNeuralRawDenoiseBackend.h")
    bridge = _text(VULKAN / "VulkanNeuralResourceBridge.cpp")

    for token in (
        "submitAsync",
        "vkGetFenceStatus",
        "vkWaitForFences",
        "slots_.resize",
        "ensureSlots",
        "GpuStagingFallback",
        "vkGetPhysicalDeviceExternalBufferProperties",
        "VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT",
        "VkImportAndroidHardwareBufferInfoANDROID",
        "NEURAL_AHB_PRODUCER_SYNC_MISSING",
    ):
        assert token in backend + bridge + header

    assert "fullFrameCpuReadbackBytes=0" in header
    assert "cpuFallbackUsed=false" in header


def test_frozen_parity_contract_records_exact_reference_and_explicit_device_gate():
    parity_path = TRAIN / "tests" / "golden" / "vulkan_parity_contract" / "phase4_parity.json"
    parity = json.loads(parity_path.read_text(encoding="utf-8"))
    assert parity["phase3_package_reference_exact"] is True
    assert parity["valid_center_tiled_reference_exact"] is True
    assert parity["clipped_reference_cell_identity"] is True
    assert parity["gpu_validation_gate"]["status"] == "REQUIRED_ON_ANDROID_VULKAN_DEVICE"


def test_no_device_identity_conditioning_and_no_legacy_correction_owner_resurrection():
    paths = [
        CPP / "NeuralRawDenoiseBackend.h",
        VULKAN / "VulkanNeuralRawDenoiseBackend.cpp",
        VULKAN / "VulkanNeuralExecutionPlan.cpp",
        SHADERS / "neural_condition.comp",
        SHADERS / "neural_writeback.comp",
    ]
    combined = "\n".join(_text(p) for p in paths)
    lowered = combined.lower()
    for forbidden in (
        "phonemodel",
        "sensormodel",
        "manufacturer",
        "lensid",
        "deviceid",
        "spectranoregretresult",
        "applyspectranoregretgate",
        "resolvecontextfusionnoregret",
        "resolvechromanoregret",
        "applyspectrapass0",
        "applyspectrapass1",
    ):
        assert forbidden not in lowered


def test_phase4_audit_documents_all_eighteen_tasks_and_external_gate_truthfully():
    audit = _text(TRAIN / "PHASE4_AUDIT.md")
    numbered = re.findall(r"(?m)^([1-9]|1[0-8])\. \*\*", audit)
    assert len(numbered) == 18
    assert "DEVICE GATE OPEN" in audit
    assert "not" in audit.lower() and "glslc" in audit
    assert "No full-frame CPU fallback" in audit
