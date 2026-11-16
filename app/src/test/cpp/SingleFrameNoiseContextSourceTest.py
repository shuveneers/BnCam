"""SF2-D01: pixel-producing path remains byte-identical to phase 0."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[4]
BASE = "ba509c90f71c63e3493aecbdef5e7c8890ede870"
PATH = "app/src/main/cpp/IspCore.cpp"
baseline = subprocess.check_output(["git", "show", BASE + ":" + PATH], cwd=ROOT).decode()
actual = (ROOT / PATH).read_text()

include = '#include "SingleFrameNoiseContext.h"\n'
assert actual.count(include) == 1
actual = actual.replace(include, "", 1)

prefix = '    // SF2-D01-03: read-only snapshot of the already propagated physical evidence.\n'
suffix = '    // Delta 18: explicit demosaic chroma-amplification audit.'
assert actual.count(prefix) == 1
assert actual.count(suffix) == 1
start = actual.index(prefix)
end = actual.index(suffix, start)
assert 'SingleFrameNoiseContext::observe(' in actual[start:end]
actual = actual[:start] + actual[end:]

telemetry = '            << bncam::sf2::formatNoiseContextOnly(sf2NoiseContext)\n'
assert actual.count(telemetry) == 1
actual = actual.replace(telemetry, "", 1)
assert actual == baseline, "Pixel-producing ISP source differs from phase-0 baseline"

ctx = (ROOT / "app/src/main/cpp/SingleFrameNoiseContext.h").read_text()
assert "appliedToPixels=false" in ctx
assert "extraRawGainApplications=0;extraFusionGainApplications=0" in ctx
assert "const spectra2::NoiseState postFinalize" in ctx
assert "const spectra2::NoiseState postDemosaic" in ctx
assert "RawSpatialNoiseCalibrationPolicy.h" in ctx
print("Phase-1 read-only integration; phase-0 pixel-producing ISP source unchanged")
