"""SF2-D08: explicitly reviewed change boundary and seven-stage routing.
Earlier phase-7 tests execute separately against their frozen checkout.
"""
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[4]
BASE="445f2694260e6c83a9b71496ba49c20703439b55"
CPP="app/src/main/cpp/"

def live(path): return (ROOT/path).read_text(encoding="utf-8")
def old(path):
    return subprocess.check_output(["git","show",BASE+":"+path],cwd=ROOT,text=True)

allowed={
    CPP+"PhysicalLumaDenoise.h",
    CPP+"SingleFrameGuidedLuma.h",
    CPP+"vulkan/shaders/physical_luma_denoise.glsl",
    CPP+"native-lib.cpp",
    CPP+"IspCore.cpp",
    CPP+"SingleFrameIntegrationQualification.h",
    CPP+"SingleFrameIntegrationJni.cpp",
}
changed=set(subprocess.check_output(
    ["git","diff","--name-only",BASE,"HEAD","--",CPP],cwd=ROOT,text=True
).splitlines())
assert changed==allowed, f"Unexpected SF2-D08 native changes: {sorted(changed ^ allowed)}"

protected=(
    "PhysicalChromaDenoise.h",
    "Demosaic.h",
    "SingleFrameNoiseContext.h",
    "SingleFrameGuidedCoarse.h",
    "SingleFrameResidualConfidence.h",
    "RawPoissonGaussianEstimator.h",
    "PhysicalNoiseAuthority.h",
    "vulkan/VulkanSpectraResidentDemosaicBackend.cpp",
    "vulkan/VulkanSpectraResidentDemosaicBackend.h",
    "vulkan/shaders/spectra_demosaic_resident.comp",
    "vulkan/shaders/physical_chroma_denoise.glsl",
    "vulkan/shaders/physical_luma_coarse.glsl",
    "vulkan/shaders/physical_luma_confidence.glsl",
)
for rel in protected:
    assert live(CPP+rel)==old(CPP+rel), f"Protected production file changed: {rel}"

# The entire old CPU Fine source reconstructs byte-for-byte when the optional
# Balanced parameter and its authority multiplier are removed.
cpu=live(CPP+"SingleFrameGuidedLuma.h")
cpu=cpu.replace("Read read,Shape shape,bool gate=true,bool balanced=false) {",
                "Read read,Shape shape,bool gate=true) {")
start=cpu.index("    // SF2-D08:")
end=cpu.index("    const float correction=authority",start)
cpu=cpu[:start]+(
    "    const float authority=std::clamp(.78f*physical*wiener*gateFactor,0.f,.78f);\n"
)+cpu[end:]
assert cpu==old(CPP+"SingleFrameGuidedLuma.h"),"Unintended legacy Guided Fine CPU alteration"

shader=live(CPP+"vulkan/shaders/physical_luma_denoise.glsl")
shader=shader.replace("out vec3 telemetry,bool gate,bool balanced) {",
                      "out vec3 telemetry,bool gate) {")
start=shader.index("    // SF2-D08 mirrors")
end=shader.index("    float correction=authority",start)
shader=shader[:start]+(
    "    float authority=clamp(.78*physical*wiener*(gate?1.0-.98*structure:1.0),0.0,.78);\n"
)+shader[end:]
shader=shader.replace("    if(stage==3u)return physicalGuidedFine(xy,postChroma,telemetry,true,true);\n","")
shader=shader.replace("physicalGuidedFine(xy,postChroma,telemetry,true,false)",
                      "physicalGuidedFine(xy,postChroma,telemetry,true)")
shader=shader.replace("physicalGuidedFine(xy,postChroma,fineTelemetry,true,false)",
                      "physicalGuidedFine(xy,postChroma,fineTelemetry,true)")
assert shader==old(CPP+"vulkan/shaders/physical_luma_denoise.glsl"),"Unintended legacy shader alteration"

stage=live(CPP+"PhysicalLumaDenoise.h")
assert stage.replace("GUIDED_V2 = 2, GUIDED_V2_BALANCED = 3,","GUIDED_V2 = 2,")==old(CPP+"PhysicalLumaDenoise.h")
assert "selectedStage{Stage::OFF}" in stage
assert 'stage==3 ? bncam::luma::Stage::GUIDED_V2_BALANCED' in live(CPP+"native-lib.cpp")
assert '#ifndef NDEBUG\n    const auto selected' in live(CPP+"native-lib.cpp")
isp=live(CPP+"IspCore.cpp")
for item in ("GUIDED_V2_BALANCED_DEBUG","GUIDED_V2_BALANCED).pixel",
             "singleFrameLumaStage != bncam::luma::Stage::GUIDED_V2_BALANCED"):
    assert item in isp, item
jni=live(CPP+"SingleFrameIntegrationJni.cpp")
assert "std::array<std::pair<Stage,bool>,7> matrix" in jni
assert "{Stage::GUIDED_V2_BALANCED,false}" in jni
assert "POST_STAGE_OFF_PIXEL_IDENTITY_FAILED" in jni

kt=live("app/src/main/java/com/bncam/core/engine/ImageUtils.kt")
ui=live("app/src/main/java/com/bncam/ui/screens/settings/DeveloperSettingsScreen.kt")
collector=live("scripts/sf2_phase7_collect.py")
for marker in ('checkStage("balanced", 3)','"balanced.jpg"','"balanced.txt"',
               'checkStage("offRepeat", 0)','"rawMasterCrc32="'):
    assert marker in kt, marker
assert 'Sf2Mode(3, "Guided Fine Balanced"' in ui
assert "setPhysicalLumaStageDebug(active)" in ui
assert "report.contains(\"testedStages=7;\")" in ui
assert '"balanced.txt": ("GUIDED_V2_BALANCED_DEBUG", True)' in collector
assert '"seven_debug_stage_checks_pass": matches' in collector
print("SF2-D08: protected ISP/colour/RAW modules untouched, frozen Fine reconstructed exactly, new debug stage wired")
