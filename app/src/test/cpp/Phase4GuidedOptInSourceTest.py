"""SF2-D04: frozen OFF/legacy source and explicit debug-only Guided Fine.

Checks affected production paths only; does not claim on-device GPU parity.
"""
from pathlib import Path
import subprocess
from Sf2ReadOnlyIspNormalization import strip_phase4_opt_in_isp

ROOT=Path(__file__).resolve().parents[4]
BASE="76e98fbf09ba6905323b0356293118c8f6892381"
CPP="app/src/main/cpp/"

def frozen(path):
    return subprocess.check_output(
        ["git","show",BASE+":"+path],cwd=ROOT).decode()
def live(path):
    return (ROOT/path).read_text()

changed=subprocess.check_output(
    ["git","diff","--name-only",BASE,"HEAD","--",CPP],
    cwd=ROOT,text=True).splitlines()
assert changed==[
    CPP+"IspCore.cpp",
    CPP+"SingleFrameGuidedLuma.h",
    CPP+"native-lib.cpp",
    CPP+"vulkan/VulkanSpectraResidentDemosaicBackend.cpp",
    CPP+"vulkan/VulkanSpectraResidentDemosaicBackend.h",
    CPP+"vulkan/shaders/physical_luma_denoise.glsl",
],f"Unexpected phase-4 native production change: {changed!r}"

assert strip_phase4_opt_in_isp(live(CPP+"IspCore.cpp"))==frozen(CPP+"IspCore.cpp")
for protected in (
    "PhysicalChromaDenoise.h",
    "PhysicalLumaDenoise.h",
    "Demosaic.h",
    "SingleFrameNoiseContext.h",
    "RawPoissonGaussianEstimator.h",
    "vulkan/shaders/physical_chroma_denoise.glsl",
    "vulkan/shaders/spectra_demosaic_resident.comp",
):
    assert live(CPP+protected)==frozen(CPP+protected),protected

shader=live(CPP+"vulkan/shaders/physical_luma_denoise.glsl")
marker="// SF2-D04:"
assert shader.count(marker)==1
prefix=shader.split(marker)[0]
expectedLegacy=(
    "// Phase-4 bounded Guided Fine remains debug-only; stage OFF by default.\n"
    + frozen(CPP+"vulkan/shaders/physical_luma_denoise.glsl").replace(
        "vec3 physicalLumaDenoise(", "vec3 physicalLegacyLumaDenoise("))
assert prefix.rstrip()==expectedLegacy.rstrip(), "Legacy kernel changed beyond wrapper"
assert "if(stage==2u)return physicalGuidedFine(xy,postChroma,telemetry,true);" in shader
assert "if(stage==1u)return physicalLegacyLumaDenoise(xy,postChroma,telemetry);" in shader
assert "return postChroma;" in shader
assert "stage==3u" not in shader

backend=live(CPP+"vulkan/VulkanSpectraResidentDemosaicBackend.cpp")
assert backend.count("request.lumaStage!=bncam::luma::Stage::OFF")==1
assert backend.count("static_cast<std::uint32_t>(request.lumaStage)<<2u")==1
before=backend.replace(
    "    const bool physicalLumaReady=request.lumaStage!=bncam::luma::Stage::OFF &&\n"
    "            request.baselinePhysicalLuma.valid();",
    "    const bool physicalLumaReady=request.baselinePhysicalLuma.valid();")
before=before.replace(
    "    push.residualStride = (noise.adaptiveSpatialNoise ? 1u : 0u) |\n"
    "                          (noise.protectCoherentDetail ? 2u : 0u) |\n"
    "                          (physicalLumaReady ? (static_cast<std::uint32_t>(request.lumaStage)<<2u) : 0u);",
    "    push.residualStride = (noise.adaptiveSpatialNoise ? 1u : 0u) |\n"
    "                          (noise.protectCoherentDetail ? 2u : 0u);")
assert before==frozen(CPP+"vulkan/VulkanSpectraResidentDemosaicBackend.cpp")

header=live(CPP+"vulkan/VulkanSpectraResidentDemosaicBackend.h")
assert "lumaStage = bncam::luma::Stage::OFF;" in header
header=header.replace(
    "    // Explicit debug-selected stage; OFF if caller does not opt in.\n"
    "    bncam::luma::Stage lumaStage = bncam::luma::Stage::OFF;\n","")
assert header==frozen(CPP+"vulkan/VulkanSpectraResidentDemosaicBackend.h")

jni=live(CPP+"native-lib.cpp")
assert "Java_com_bncam_core_engine_ImageUtils_setPhysicalLumaStageDebug" in jni
assert "stage==2 ? bncam::luma::Stage::GUIDED_V2" in jni
assert "#ifndef NDEBUG" in jni
assert "stage==3 ?" not in jni
assert "Stage::OFF" in jni
debug=live("app/src/debug/java/com/bncam/BenchmarkDebugReceiverController.kt")
assert 'intent.hasExtra("physicalLumaStage")' in debug
assert "setPhysicalLumaStageDebug" in debug
print("Phase-4 debug opt-in, exact OFF/legacy shader and ISP frozen, chroma unmodified")
