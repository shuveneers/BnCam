"""SF2 phase-0 guard against accidental pixel change on the product baseline."""
from pathlib import Path
import subprocess
from Sf2ReadOnlyIspNormalization import strip_phase4_opt_in_isp

ROOT = Path(__file__).resolve().parents[4]
BASE = "277826469978febf4e243b15363b8340840bd971"
PROTECTED = [
    "PhysicalChromaDenoise.h",
    "PhysicalNoiseAuthority.h",
    "SpectraNoisePropagation.h",
    "vulkan/shaders/physical_chroma_denoise.glsl",
    "vulkan/shaders/spectra_demosaic_resident.comp",
]
for name in PROTECTED:
    path = "app/src/main/cpp/" + name
    expected = subprocess.check_output(["git", "show", BASE + ":" + path], cwd=ROOT)
    actual = (ROOT / path).read_bytes()
    assert actual == expected, f"Protected phase 0 output differs from main: {name}"

luma_path = "app/src/main/cpp/PhysicalLumaDenoise.h"
old = subprocess.check_output(["git", "show", BASE + ":" + luma_path], cwd=ROOT).decode()
new = (ROOT / luma_path).read_text()
needle = "template<class Read, class Shape>\nResult filter("
assert old[old.index(needle):] == new[new.index(needle):], "Legacy algorithm modified"
assert "selectedStage{Stage::OFF}" in new

isp = strip_phase4_opt_in_isp((ROOT / "app/src/main/cpp/IspCore.cpp").read_text())
assert "if (!bncam::luma::legacyAuthorized(singleFrameLumaStage)) baselinePhysicalLuma = {};" in isp
assert "PHYSICAL_LUMA_STAGE_OFF" in isp

native = (ROOT / "app/src/main/cpp/native-lib.cpp").read_text()
assert "#ifndef NDEBUG\n    bncam::luma::selectedStage.store" in native
kt = (ROOT / "app/src/main/java/com/bncam/core/engine/ImageUtils.kt").read_text()
assert "setPhysicalLumaDebugEnabled(false)" in kt
assert "setPhysicalLumaStageDebug(0)" in kt
assert "finally {" in kt
print("SF2 phase-0 chroma frozen; phase-4 shader/backend opt-in verified separately; default OFF")
