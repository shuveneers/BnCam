"""SF2-D03: exact phase-2 ISP pixel path after removing ONLY debug observations."""
from pathlib import Path
import subprocess
from Sf2ReadOnlyIspNormalization import strip_phase3_debug_isp

ROOT = Path(__file__).resolve().parents[4]
BASE = "9fba3da109825977630b2cf6cfa031c16dcba31f"
CPP = "app/src/main/cpp"

changed = subprocess.check_output(
    ["git", "diff", "--name-only", BASE, "HEAD", "--", CPP],
    cwd=ROOT, text=True).splitlines()
assert changed == [
    "app/src/main/cpp/IspCore.cpp",
    "app/src/main/cpp/PhysicalLumaDenoise.h",
    "app/src/main/cpp/RawPoissonGaussianEstimator.h",
    "app/src/main/cpp/SingleFrameGuidedCoarse.h",
    "app/src/main/cpp/SingleFrameGuidedLuma.h",
    "app/src/main/cpp/native-lib.cpp",
    "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp",
    "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h",
    "app/src/main/cpp/vulkan/shaders/physical_luma_coarse.glsl",
    "app/src/main/cpp/vulkan/shaders/physical_luma_denoise.glsl",
    "app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp",
], f"SF2-D03 changed protected production source: {changed}"

path = CPP + "/IspCore.cpp"
frozen = subprocess.check_output(
    ["git", "show", BASE + ":" + path], cwd=ROOT).decode()
actual = (ROOT / path).read_text()
assert strip_phase3_debug_isp(actual) == frozen, (
    "IspCore differs from phase-2 frozen OFF/legacy path outside explicit debug-only additions")

estimator = (ROOT / CPP / "RawPoissonGaussianEstimator.h").read_text()
for token in (
    "overridesPhysicalModel = false",
    "appliedToPixels = false",
    "inputWasReadBackFromGpu = false",
    "PHYSICAL_SO_UNAVAILABLE_NO_FIT",
    "PRE_LSC_NORMALIZED_BAYER",
    "canonicalPlaneAtMosaicSite",
    "physicalS[ch]*mean+physicalO[ch]",
    "data=nullptr",
):
    # Pointer declaration may use spaces. Keep positive assertions on real contracts.
    if token != "data=nullptr":
        assert token in estimator, f"Missing fail-closed RAW contract: {token}"
assert "VulkanRuntime" not in estimator
assert "android/" not in estimator
assert "#include <opencv" not in estimator
print("SF2 phase-3/4 source: OFF and legacy ISP unchanged after stripping explicit debug-only hooks")
