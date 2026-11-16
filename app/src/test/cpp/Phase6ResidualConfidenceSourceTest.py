"""SF2-D06: strict reviewed source boundary for gated-vs-ungated luma.

Earlier phase-0..5 regressions are executed unmodified on the frozen phase-5
tree by the phase-6 workflow. This guard checks current files against that
exact commit and pins every reviewed changed production file. No weakening
of baseline checks is hidden in a cumulative source-normalizer.
"""
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[4]
BASE="701fcb0df6541d5ede0d0b451159934ae9a6928b"

def read(path):
    return (ROOT/path).read_text()

def frozen(path):
    return subprocess.check_output(
        ["git","show",BASE+":"+path],cwd=ROOT,text=True)

def fingerprint(source):
    h=2166136261
    for ch in source:
        h=((h^ord(ch))*16777619)&0xffffffff
    return f"{h:08x}"

# Exact per-file reviewed source identity. Any further change requires a
# fresh review and explicit update to this release contract.
source_sha={
    "app/src/main/cpp/IspCore.cpp":"c0fc5df8",
    "app/src/main/cpp/PhysicalLumaDenoise.h":"a390835d",
    "app/src/main/cpp/SingleFrameResidualConfidence.h":"4cc95ac8",
    "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp":"ae0db0f6",
    "app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h":"d0d1415c",
    "app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp":"fb16cdb1",
    "app/src/main/cpp/vulkan/shaders/physical_luma_denoise.glsl":"00e95c35",
    "app/src/main/cpp/vulkan/shaders/physical_luma_confidence.glsl":"c9fffa76",
    "app/src/main/cpp/native-lib.cpp":"ce84e478",
    "app/src/main/java/com/bncam/core/engine/ImageUtils.kt":"27574445",
}
for path,expected in source_sha.items():
    assert fingerprint(read(path))==expected, (
        f"Unreviewed phase-6 production change: {path}")
cpp_changes=subprocess.check_output(
    ["git","diff","--name-only",BASE,"HEAD","--","app/src/main/cpp"],
    cwd=ROOT,text=True).splitlines()
assert cpp_changes==sorted(p for p in source_sha
     if p.startswith("app/src/main/cpp/")), (
     f"Unexpected native production path(s) in phase 6: {cpp_changes!r}")

# Chromatic, demosaic, physical noise authority and fine/coarse *algorithmic*
# implementations are byte identical to delivered phase 5.
for p in (
    "app/src/main/cpp/PhysicalChromaDenoise.h",
    "app/src/main/cpp/SingleFrameGuidedLuma.h",
    "app/src/main/cpp/SingleFrameGuidedCoarse.h",
    "app/src/main/cpp/SingleFrameNoiseContext.h",
    "app/src/main/cpp/RawPoissonGaussianEstimator.h",
    "app/src/main/cpp/PhysicalNoiseAuthority.h",
    "app/src/main/cpp/Demosaic.h",
    "app/src/main/cpp/vulkan/shaders/physical_chroma_denoise.glsl",
    "app/src/main/cpp/vulkan/shaders/physical_luma_coarse.glsl",
):
    assert read(p)==frozen(p), f"Protected phase-5 content changed: {p}"

oldLuma=frozen("app/src/main/cpp/vulkan/shaders/physical_luma_denoise.glsl")
newLuma=read("app/src/main/cpp/vulkan/shaders/physical_luma_denoise.glsl")
begin="vec3 physicalLegacyLumaDenoise("
cut="// Mode-3 bits"
assert newLuma[newLuma.index(begin):newLuma.index(cut)]==\
       oldLuma[oldLuma.index(begin):oldLuma.index(cut)], (
       "Legacy or guided fine GLSL kernel body was changed")
baseStage4=oldLuma[oldLuma.index("    if(stage==4u)"):oldLuma.index("    return postChroma;",oldLuma.index("    if(stage==4u)"))]
assert baseStage4 in newLuma, "Prior coarse ungated branch changed"
assert "if(stage==5u)" in newLuma and "sf2ResidualConfidenceAt(" in newLuma
assert "vec3 gated=postChroma+vec3(evidence.weight*(fineDelta+coarseDelta));" in newLuma
assert "if(evidence.valid<0.5)return postChroma;" in newLuma
assert '#include "physical_luma_confidence.glsl"' in read(
    "app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp")

model=read("app/src/main/cpp/SingleFrameResidualConfidence.h")
shader=read("app/src/main/cpp/vulkan/shaders/physical_luma_confidence.glsl")
for native,glsl in (
    ("boundedCoherentEvidence", "sf2CoherentEvidence"),
    ("lineSum*inv","lineSum*inv"),
    ("edgeSum*inv","edgeSum*inv"),
    ("model.varianceY*centerShape*centerShape","pc.padding0*local*local"),
    ("kernelProxyAvailable ? .92f : .78f","kernelProxyAvailable?0.92:0.78"),
    ("1.f-riskStrength*eraseRisk", "1.0-riskStrength*eraseRisk"),
    ("opposition/support","opposition/support"),
    ("lineEvidence,.5f*edgeEvidence","lineEvidence,.5*edgeEvidence"),
):
    assert native in model and glsl in shader, (
        f"Missing matching CPU/GPU evidence: {native} / {glsl}")
assert "out.weight=std::clamp(" in model and "out.valid=true;" in model
assert "if(!model.valid()" in model
assert "return postChroma;" in model
assert "inputIndependenceVerified" not in model
assert "predictDemosaicSpatialNoise(" not in model
assert "varianceIndependent" not in model # no invented rho/covariance
assert "#include <opencv" not in model

stage=read("app/src/main/cpp/PhysicalLumaDenoise.h")
assert "selectedStage{Stage::OFF}" in stage
assert "GUIDED_V2_CONFIDENCE = 5" in stage
assert stage[stage.index("template<class Read, class Shape>"):]==\
       frozen("app/src/main/cpp/PhysicalLumaDenoise.h")[
       frozen("app/src/main/cpp/PhysicalLumaDenoise.h").index(
           "template<class Read, class Shape>"):], "Legacy filter algorithm changed"
backend=read("app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp")
hdr=read("app/src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h")
assert "GUIDED_V2_CONFIDENCE) &&" in backend
assert "effectiveLumaStage==bncam::luma::Stage::GUIDED_V2_CONFIDENCE" in backend
assert "request.lumaDemosaicKernelProxyAvailable ? 32u : 0u" in backend
assert "request.lumaStage==bncam::luma::Stage::GUIDED_V2_CONFIDENCE" in backend
assert "result.guidedConfidenceGateApplied=" in backend
assert "lumaDemosaicKernelProxyAvailable = false;" in hdr
assert "guidedConfidenceGateApplied = false;" in hdr
assert "sizeof(PushConstants) == 128u" in backend
assert "bindings[10]" in backend and "poolSize.descriptorCount = 10u" in backend
assert "VK_ACCESS_SHADER_READ_BIT" in backend
assert "push.mode=14u" in backend and "push.mode=15u" in backend

isp=read("app/src/main/cpp/IspCore.cpp")
assert "#include \"SingleFrameResidualConfidence.h\"" in isp
assert "sf2NoiseContext.demosaicSpatialNoise.kernelProxyAvailable" in isp
assert "const bool gated=singleFrameLumaStage == bncam::luma::Stage::GUIDED_V2_CONFIDENCE;" in isp
assert "bncam::sf2::residualConfidenceAt(" in isp
assert "bncam::sf2::applyResidualConfidence(" in isp
assert "if(combined && coarse.valid)" in isp
assert "singleFrameLumaStage != bncam::luma::Stage::GUIDED_V2_CONFIDENCE" in isp
assert "request.lumaStage = singleFrameLumaStage" in isp
assert "confidenceDemosaicSpatialCorrelationMeasured=false" in isp
# No accidental double fusion/LSC, no raw sensor rewriting.
assert "workingMeta.calibration.effectiveS[0] =" not in model
assert "workingMeta.calibration.effectiveO[0] =" not in model

jni=read("app/src/main/cpp/native-lib.cpp")
assert "stage==5 ? bncam::luma::Stage::GUIDED_V2_CONFIDENCE" in jni
assert "#ifndef NDEBUG\n    const auto selected" in jni
kt=read("app/src/main/java/com/bncam/core/engine/ImageUtils.kt")
for token in (
    "physicalLumaAbDirectory.takeIf { BuildConfig.DEBUG }",
    "setPhysicalLumaStageDebug(0)", "setPhysicalLumaStageDebug(1)",
    "setPhysicalLumaStageDebug(2)", "setPhysicalLumaStageDebug(4)",
    "setPhysicalLumaStageDebug(5)", '"off.jpg"', '"on.jpg"',
    '"guided.jpg"', '"combined.jpg"', '"confidence.jpg"',
    '"confidence.txt"', "finally {",
):
    assert token in kt, f"Missing five-way identical RAW comparison: {token}"
assert "setPhysicalLumaStageDebug(0)\n                setPhysicalLumaDebugEnabled(false)" in kt
print("SF2-D06 current source: frozen chroma/fine/coarse; debug-only gate, no ABI/buffer/readback changes")
