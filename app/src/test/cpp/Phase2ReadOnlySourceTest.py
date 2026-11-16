"""SF2-D02 pixel-path isolation contract relative to the completed phase-1 release."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[4]
BASE = "ea7f4b45c41e5770cd8d3938bfd1133efc40aa65"
maincpp = "app/src/main/cpp"
actual = subprocess.check_output(
    ["git", "diff", "--name-only", BASE, "HEAD", "--", maincpp],
    cwd=ROOT, text=True).splitlines()
expected = [
    "app/src/main/cpp/DemosaicNoiseCorrelation.h",
    "app/src/main/cpp/SingleFrameNoiseContext.h",
]
assert actual == expected, f"Unexpected phase-2 main-cpp changes: {actual!r}"

# Product output, demosaic implementation, chroma, Vulkan and stage selector are
# untouched relative to phase 1; only read-only headers differ.
isp = maincpp + "/IspCore.cpp"
assert (ROOT / isp).read_bytes() == subprocess.check_output(
    ["git", "show", BASE + ":" + isp], cwd=ROOT)

model = (ROOT / maincpp / "DemosaicNoiseCorrelation.h").read_text()
context = (ROOT / maincpp / "SingleFrameNoiseContext.h").read_text()
assert "inputIndependenceVerified = false" in model
assert "measured = false" in model
assert "AUTO_HYBRID_SPATIAL_WEIGHTS_UNKNOWN" in model
assert "ADAPTIVE_OR_UNSUPPORTED_KERNEL_UNKNOWN" in model
assert "NEURAL_POSTERIOR_INPUT_SPATIAL_CORRELATION_UNKNOWN" in model
assert 'demosaicCorrelationValues=UNAVAILABLE' in context
assert 'predictDemosaicSpatialNoise(' in context
assert 'appliedToPixels=false' in context
print("SF2 phase-2 unchanged ISP pixels: only read-only context and correlation headers differ")
