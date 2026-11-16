"""SF2-D05: exact approved production delta and OFF/fine-only isolation.

Whole-source fingerprints intentionally pin every modified production file.
The earlier phase-0..4 regression suite independently compares frozen source
after stripping precisely the debug-only hook. Updating fingerprints requires
an explicit reviewed source change; this guard does not run generated baselines.
"""
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[4]
BASE="ccf3e96976ae90b2ce59bcf774a4c5fae803555d"

def read(path):
    return (ROOT/path).read_text()

def git_show(path,ref=BASE):
    return subprocess.check_output(
        ["git","show",ref+":"+path],cwd=ROOT,text=True)

def fingerprint(source):
    h=2166136261
    for ch in source:
        h=((h^ord(ch))*16777619)&0xffffffff
    return f"{h:08x}"

expected={
    "app/src/main/cpp/IspCore.cpp": "10453b5f",
    "app/src/main/cpp/PhysicalLumaDenoise.h": "3dafdaf5",
    "app/src/main/cpp/SingleFrameGuidedCoarse.h": "9eb68348",
    "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp": "2774eb81",
    "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h": "368734f3",
    "app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp": "b713eb0a",
    "app/src/main/cpp/vulkan/shaders/physical_luma_coarse.glsl": "76989350",
    "app/src/main/cpp/vulkan/shaders/physical_luma_denoise.glsl": "2e7fa3d5",
    "app/src/main/cpp/native-lib.cpp": "0059486e",
    "app/src/main/java/com/bncam/core/engine/ImageUtils.kt": "29db50c0",
}
for path,known in expected.items():
    actual=fingerprint(read(path))
    assert actual==known, f"Unreviewed phase-5 production modification: {path} {actual}!={known}"

changes=subprocess.check_output(
    ["git","diff","--name-only",BASE,"HEAD","--","app/src/main/cpp"],
    cwd=ROOT,text=True).splitlines()
expected_cpp=sorted([p for p in expected if p.startswith("app/src/main/cpp/")])
assert changes==expected_cpp, f"Unexpected native production path(s) in phase-5: {changes!r}"

# ALL protected chroma/RAW and Phase-4 Guided Fine code must remain exact.
for path in (
    "app/src/main/cpp/PhysicalChromaDenoise.h",
    "app/src/main/cpp/PhysicalNoiseAuthority.h",
    "app/src/main/cpp/SingleFrameGuidedLuma.h",
    "app/src/main/cpp/SingleFrameNoiseContext.h",
    "app/src/main/cpp/RawPoissonGaussianEstimator.h",
    "app/src/main/cpp/vulkan/shaders/physical_chroma_denoise.glsl",
    "app/src/main/cpp/Demosaic.h",
):
    assert read(path)==git_show(path), f"Unexpected protected Phase-4 source mutation: {path}"

stage=read("app/src/main/cpp/PhysicalLumaDenoise.h")
assert "selectedStage{Stage::OFF}" in stage
assert "GUIDED_V2_FINE_COARSE = 4" in stage
native=read("app/src/main/cpp/native-lib.cpp")
assert "stage==4 ? bncam::luma::Stage::GUIDED_V2_FINE_COARSE" in native
assert "#ifndef NDEBUG\n    const auto selected = stage==1" in native
kt=read("app/src/main/java/com/bncam/core/engine/ImageUtils.kt")
for item in (
    "val abDirectory = physicalLumaAbDirectory.takeIf { BuildConfig.DEBUG }",
    "setPhysicalLumaStageDebug(0)",
    "setPhysicalLumaStageDebug(1)",
    "setPhysicalLumaStageDebug(2)",
    "setPhysicalLumaStageDebug(4)",
    '"combined.jpg"',
    '"combined.txt"',
    "finally {",
):
    assert item in kt, f"Same-RAW debug control missing {item}"
assert "setPhysicalLumaStageDebug(0)\n                setPhysicalLumaDebugEnabled(false)" in kt

backend=read("app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp")
header=read("app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h")
shader=read("app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp")
coarse=read("app/src/main/cpp/vulkan/shaders/physical_luma_coarse.glsl")
combined=read("app/src/main/cpp/vulkan/shaders/physical_luma_denoise.glsl")
for phrase in (
    "bindings[10]", "descriptorInfo.bindingCount = 10u",
    "poolSize.descriptorCount = 10u", "infos[8].buffer", "infos[9].buffer",
    "vkUpdateDescriptorSets(device, 10u",
    "guidedCoarseInput_", "guidedCoarseResidual_",
    "request.lumaStage==bncam::luma::Stage::GUIDED_V2_FINE_COARSE",
    "if(coarseReady) {", "push.mode=14u", "push.mode=15u",
    "coarseBarrier(guidedCoarseInput_.buffer)",
    "coarseBarrier(guidedCoarseResidual_.buffer)",
    "VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT",
    "result.guidedCoarseApplied=coarseReady",
):
    assert phrase in backend, f"Coarse GPU barrier/resource routing missing: {phrase}"
assert "guidedCoarseInput_" in header and "guidedCoarseResidual_" in header
assert "GUIDED_V2_FINE_COARSE" in header or "lumaStage" in header
for phrase in (
    "layout(std430, binding = 8)", "layout(std430, binding = 9)",
    '#include "physical_luma_coarse.glsl"',
    "if (pc.mode == 14u)", "if (pc.mode == 15u)",
):
    assert phrase in shader, phrase
for phrase in (
    "float taps[4]=float[4](0.125,0.375,0.375,0.125)",
    "varIndependent+=w*w*v",
    "sigmaBound+=w*sqrt(v)",
    "float varianceUpper=sigmaBound*sigmaBound",
    "coarseDelta[index]", "coarseCorrectionAt",
):
    assert phrase in coarse, phrase
assert "if(stage==2u)return physicalGuidedFine" in combined
assert "if(stage==4u)" in combined
assert "float coarse=coarseCorrectionAt(xy)" in combined
assert "vec3 combined=fine+vec3(coarse)" in combined
assert "if(stage==1u)return physicalLegacyLumaDenoise" in combined
assert "return postChroma;" in combined

isp=read("app/src/main/cpp/IspCore.cpp")
assert "bncam::sf2::buildGuidedCoarse(source.cols,source.rows" in isp
assert "if(combined && coarse.valid)" in isp
assert "bncam::sf2::addCoarseResidual(" in isp
assert "singleFrameLumaStage != bncam::luma::Stage::GUIDED_V2_FINE_COARSE" in isp
assert "request.lumaStage = singleFrameLumaStage" in isp

# Both compact storage buffers use device VMA access=0; no mapping/readback,
# no upload or extra full-frame CPU buffers in the successful resident route.
assert "coarseCells*4u*sizeof(float),0u" in backend
assert "coarseCells*sizeof(float),0u" in backend
assert "coarseWidth=(request.frameWidth+3u)/4u" in backend
assert "result.guidedCoarseResidentBytes=" in backend
assert "if(coarseRequested)" in backend
assert "coarseReady=ensureBufferLocked" in backend
assert "GUIDED_V2 : request.lumaStage" in backend
print("SF2-D05 frozen chroma, unchanged fine/legacy, 1/4-size GPU-only coarse, exact four-way debug")
