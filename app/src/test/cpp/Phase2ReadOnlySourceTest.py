"""SF2-D02 pixel-path isolation contract relative to the completed phase-1 release."""
from pathlib import Path
from Sf2ReadOnlyIspNormalization import strip_phase3_debug_isp
import subprocess

ROOT = Path(__file__).resolve().parents[4]
BASE = "ea7f4b45c41e5770cd8d3938bfd1133efc40aa65"
maincpp = "app/src/main/cpp"
actual = subprocess.check_output(
    ["git", "diff", "--name-only", BASE, "HEAD", "--", maincpp],
    cwd=ROOT, text=True).splitlines()
expected = [
    "app/src/main/cpp/DemosaicNoiseCorrelation.h",
    "app/src/main/cpp/IspCore.cpp",
    "app/src/main/cpp/PhysicalLumaDenoise.h",
    "app/src/main/cpp/RawPoissonGaussianEstimator.h",
    "app/src/main/cpp/SingleFrameGuidedCoarse.h",
    "app/src/main/cpp/SingleFrameGuidedLuma.h",
    "app/src/main/cpp/SingleFrameNoiseContext.h",
    "app/src/main/cpp/native-lib.cpp",
    "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp",
    "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h",
    "app/src/main/cpp/vulkan/shaders/physical_luma_coarse.glsl",
    "app/src/main/cpp/vulkan/shaders/physical_luma_denoise.glsl",
    "app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp",
]
assert actual == expected, f"Unexpected phase-2 main-cpp changes: {actual!r}"

# OFF/legacy pixel path must be identical after removing precisely the phase-4
# opt-in Guided Fine stage and the phase-3 read-only RAW observation.
isp = maincpp + "/IspCore.cpp"
assert strip_phase3_debug_isp((ROOT / isp).read_text()) == subprocess.check_output(
    ["git", "show", BASE + ":" + isp], cwd=ROOT).decode()

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
print("SF2 phase-2/3/4 frozen OFF+legacy ISP matched after opt-in stripping")
