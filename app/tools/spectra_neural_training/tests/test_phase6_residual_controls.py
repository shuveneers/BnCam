import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SHADER = ROOT / "app/src/main/cpp/vulkan/shaders/neural_writeback.comp"
BACKEND = ROOT / "app/src/main/cpp/vulkan/VulkanNeuralRawDenoiseBackend.cpp"


def forward(v):
    r,g1,g2,b=v
    q2=math.sqrt(0.5)
    return ((r+g1+g2+b)/2, q2*(r-b), (r-g1-g2+b)/2, q2*(g1-g2))

def inverse(q):
    l,c1,c2,gs=q
    q2=math.sqrt(0.5)
    return (l/2+q2*c1+c2/2, l/2-c2/2+q2*gs, l/2-c2/2-q2*gs, l/2-q2*c1+c2/2)

def test_basis_roundtrip():
    for v in [(0.1,-0.2,0.3,-0.4),(1,0,0,0),(0,1,0,0),(0,0,1,0),(0,0,0,1)]:
        o=inverse(forward(v))
        assert max(abs(a-b) for a,b in zip(v,o)) < 1e-7

def test_shader_is_single_residual_gpu_control_path():
    text=SHADER.read_text()
    assert "residualBasis" in text and "componentControlled" in text
    assert "boundedNativeDelta" in text
    assert "lowFrequencyCleanup" in text
    assert "adaptiveResponse" in text
    assert "detailProtection" in text
    assert "confidenceBuf" in text
    executable = "\n".join(line.split("//",1)[0] for line in text.splitlines())
    assert "captureSensitivityIso" not in executable
    assert "isoPressure" not in executable
    assert "OpenCV" not in text and "CPU" not in text

def test_backend_projects_all_visible_controls_to_writeback():
    text=BACKEND.read_text()
    for field in ("noiseReduction","lumaNoise","chromaNoise","detailProtection","lowFrequencyCleanup","adaptiveResponse"):
        assert f"request.controls.{field}" in text
    assert "slot.confidenceLogits.buffer" in text
    assert "std::array<VkDescriptorSetLayoutBinding,10>" in text
