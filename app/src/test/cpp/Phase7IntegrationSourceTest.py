"""SF2-D07: strict app wiring/qualification boundary, frozen phase-6 pixels.

The unmodified native source tests of phase 6 run on their own Git worktree.
This gate independently rejects any current modification to pixel algorithms,
normal OFF/chroma, JNI global setter, resident Vulkan or protected S/O state.
"""
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[4]
BASE="793768e296721e1eeede16724cdba983834443bf"

def source(path: str) -> str:
    return (ROOT/path).read_text(encoding="utf-8")

def baseline(path: str) -> str:
    return subprocess.check_output(
        ["git","show",BASE+":"+path],cwd=ROOT,text=True)

def contains(path: str,*needles: str):
    text=source(path)
    for needle in needles:
        assert needle in text, f"Missing {needle!r} in {path}"

# The production pixel engine is immutable from completed phase 6.
for item in (
    "app/src/main/cpp/IspCore.cpp",
    "app/src/main/cpp/native-lib.cpp",
    "app/src/main/cpp/PhysicalLumaDenoise.h",
    "app/src/main/cpp/PhysicalChromaDenoise.h",
    "app/src/main/cpp/SingleFrameGuidedLuma.h",
    "app/src/main/cpp/SingleFrameGuidedCoarse.h",
    "app/src/main/cpp/SingleFrameResidualConfidence.h",
    "app/src/main/cpp/SingleFrameNoiseContext.h",
    "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp",
    "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h",
    "app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp",
    "app/src/main/cpp/vulkan/shaders/physical_luma_denoise.glsl",
    "app/src/main/cpp/vulkan/shaders/physical_luma_confidence.glsl",
    "app/src/main/cpp/vulkan/shaders/physical_luma_coarse.glsl",
    "app/src/main/cpp/vulkan/shaders/physical_chroma_denoise.glsl",
):
    assert source(item)==baseline(item), f"Phase-7 touched protected pixel path: {item}"

cpp="app/src/main/cpp/"
changed=subprocess.check_output(
    ["git","diff","--name-only",BASE,"HEAD","--",cpp],
    cwd=ROOT,text=True).splitlines()
assert changed==[
    cpp+"CMakeLists.txt",
    cpp+"SingleFrameIntegrationJni.cpp",
    cpp+"SingleFrameIntegrationQualification.h",
], f"Unexpected phase-7 native changes: {changed!r}"

contains(cpp+"CMakeLists.txt",
    "        SingleFrameIntegrationJni.cpp",
    "add_library(bncam SHARED",
    "        native-lib.cpp")
contains(cpp+"SingleFrameIntegrationJni.cpp",
    "Java_com_bncam_core_engine_ImageUtils_validateSf2IntegrationNative",
    "Java_com_bncam_core_engine_ImageUtils_getPhysicalLumaStageDebug",
    "#ifndef NDEBUG",
    "Stage::GUIDED_V2_FINE_COARSE",
    "Stage::GUIDED_V2_CONFIDENCE",
    "gpu.baselinePhysicalLumaApplied",
    "gpu.guidedCoarseApplied",
    "gpu.guidedConfidenceGateApplied",
    "POST_STAGE_OFF_PIXEL_IDENTITY_FAILED",
    "CPU_GPU_PARITY_FAILURE",
    "r.physicalChromaValidationOnly=true",
    "r.lumaDemosaicKernelProxyAvailable=entry.second")
contains(cpp+"SingleFrameIntegrationQualification.h",
    "kGpuParityTolerance",
    "source=SYNTHETIC",
    "bncam::sf2::guidedFine",
    "bncam::sf2::buildGuidedCoarse",
    "bncam::sf2::residualConfidenceAt",
    "bncam::sf2::applyResidualConfidence",
) if False else None
contains(cpp+"SingleFrameIntegrationQualification.h",
    "kGpuParityTolerance",
    "bncam::sf2::guidedFine",
    "bncam::sf2::buildGuidedCoarse",
    "bncam::sf2::residualConfidenceAt",
    "bncam::sf2::applyResidualConfidence")
kt="app/src/main/java/com/bncam/core/engine/ImageUtils.kt"
contains(kt,"validateSf2IntegrationNative(): String",
         "getPhysicalLumaStageDebug(): Int",
         "physicalLumaAbDirectory.takeIf { BuildConfig.DEBUG }",
         '"off_repeat.jpg"','"off_repeat.txt"','"qualification.txt"',
         "rawMasterCrc32=", "offRepeatJpegBytesIdentical=",
         "stageEvidence.joinToString",
         "finally {",
         "setPhysicalLumaStageDebug(0)",
         "setPhysicalLumaDebugEnabled(false)")
selected=source(kt)
for item in ("""checkStage("off", 0)""", """checkStage("legacy", 1)""",
             """checkStage("fine", 2)""", """checkStage("coarse", 4)""",
             """checkStage("confidence", 5)""", """checkStage("offRepeat", 0)"""):
    assert item in selected, item
receiver="app/src/debug/java/com/bncam/BenchmarkDebugReceiverController.kt"
contains(receiver,
    'ACTION_VALIDATE_SF2 = "com.bncam.VALIDATE_SF2"',
    "activity.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default)",
    "ImageUtils.validateSf2IntegrationNative()",
    "sf2_qualification", '"report.txt"',
    "BuildConfig.GIT_REVISION",
    "addAction(ACTION_VALIDATE_SF2)",
    "sf2QualificationRunning = false")
assert "SF2-D07" not in source("app/src/main/cpp/IspCore.cpp")
print("SF2-D07: phase-6 pixel path unchanged; debug JNI, stage/RAW and device test hooks connected")
