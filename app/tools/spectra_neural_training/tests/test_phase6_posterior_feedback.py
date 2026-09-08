from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CPP = ROOT / "app/src/main/cpp/vulkan/VulkanNeuralRawProductionBridge.cpp"
HDR = ROOT / "app/src/main/cpp/vulkan/VulkanNeuralRawProductionBridge.h"
RUN = ROOT / "app/src/main/cpp/vulkan/VulkanRuntime.cpp"
RH = ROOT / "app/src/main/cpp/vulkan/VulkanRuntime.h"
SHADER = ROOT / "app/src/main/cpp/vulkan/shaders/neural_mosaic_bridge.comp"

def test_posterior_reduction_is_gpu_first_and_compact():
    cpp = CPP.read_text()
    shader = SHADER.read_text()
    assert "pc.mode == 2u" in shader
    assert "shared vec4 posteriorPartial[64]" in shader
    assert "posteriorSummaryBytes" in cpp
    assert "vmaInvalidateAllocation" in cpp
    assert "groupCount" in cpp and "sampleCount" in cpp
    assert "full" not in "compactPosteriorReadbackBytes".lower()

def test_posterior_summary_is_required_for_neural_publication():
    cpp = CPP.read_text()
    assert "FAIL_BYPASS_POSTERIOR_REDUCTION_" in cpp
    assert "FAIL_BYPASS_POSTERIOR_SUMMARY_INVALID" in cpp
    assert "NeuralBypassReason::PosteriorInvalid" in cpp
    assert "out.bridgeKernelDispatches=3u" in cpp

def test_only_compact_summary_escapes_runtime():
    hdr = HDR.read_text(); run = RUN.read_text(); rh = RH.read_text()
    assert "posteriorMeanVarianceCfa" in hdr
    assert "posteriorSummaryReady" in rh
    assert "trace.posteriorMeanVarianceCfa = neuralResult.posteriorMeanVarianceCfa" in run
    assert "VkBuffer posteriorVariance" not in rh
