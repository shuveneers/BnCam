"""SF2 regression guard: main-HEAD chroma and legacy luma pixels are protected.

The earlier 8aee2d2 freeze predated proven chroma covariance/detail changes.
That stale historical pin fails even on main and is NOT the SF2 baseline.
In phase 0 the intentionally changed routing is separately asserted below.
"""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[4]
BASELINE = "277826469978febf4e243b15363b8340840bd971"
PROTECTED = [
    "PhysicalChromaDenoise.h",
    "PhysicalNoiseAuthority.h",
    "SpectraNoisePropagation.h",
    "vulkan/shaders/physical_chroma_denoise.glsl",
]
for name in PROTECTED:
    path = "app/src/main/cpp/" + name
    frozen = subprocess.check_output(["git", "show", BASELINE + ":" + path], cwd=ROOT)
    current = (ROOT / path).read_bytes()
    assert current == frozen, f"Protected physical/chroma path changed: {name}"

luma = (ROOT / "app/src/main/cpp/PhysicalLumaDenoise.h").read_text(encoding="utf-8")
old_luma = subprocess.check_output(
    ["git", "show", BASELINE + ":app/src/main/cpp/PhysicalLumaDenoise.h"],
    cwd=ROOT).decode()
# Keep original legacy filter body intact; only stage switch may change.
start = "template<class Read, class Shape>\nResult filter("
assert luma[luma.index(start):] == old_luma[old_luma.index(start):], "Legacy pixel oracle changed"
assert "selectedStage{Stage::OFF}" in luma, "Product default must be chroma-only"

isp = (ROOT / "app/src/main/cpp/IspCore.cpp").read_text(encoding="utf-8")
assert "singleFrameLumaStage==bncam::luma::Stage::OFF) baselinePhysicalLuma = {}" in isp
assert "request.lumaStage = singleFrameLumaStage;" in isp
gpu = (ROOT / "app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp").read_text(encoding="utf-8")
assert gpu.index("physicalChromaDenoise(ivec2(gid)") < gpu.index("physicalLumaDenoise(ivec2(gid)")
shader = (ROOT / "app/src/main/cpp/vulkan/shaders/physical_luma_denoise.glsl").read_text(encoding="utf-8")
assert "uint stage=(pc.residualStride>>2u)&7u;" in shader
assert "physicalLegacyLumaDenoise(xy,postChroma,telemetry)" in shader
assert "physicalGuidedFine(xy,postChroma,telemetry)" in shader
print("SF2 baseline chroma byte-identity, legacy pixel oracle and explicit OFF/legacy/guided routing verified")
