"""SF2 Final: product default gate, isolation and stage/RAW correctness."""
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[4]
BASE="c56797c440f14293931a0fe84b0afcacebad486d"
FAST_CAPTURE_BASE="4a92510314260ba42224cfa8aac9497d45ebbae7"
CPP="app/src/main/cpp/"

def live(path): return (ROOT/path).read_text(encoding="utf-8")
def base(path): return subprocess.check_output(["git","show",BASE+":"+path],cwd=ROOT,encoding="utf-8")
def contains(source,*needles):
    for needle in needles: assert needle in source, f"Missing {needle}"

expected_native={CPP+path for path in (
    "CMakeLists.txt", "IspCore.cpp", "PhysicalLumaDenoise.h",
    "SingleFrameIntegrationJni.cpp", "tests/Sf2PerformanceLinear.h",
    "vulkan/shaders/physical_luma_denoise.glsl",
)}
fast_capture_native={CPP+path for path in (
    "DngMerger.cpp", "native-lib.cpp",
    "vulkan/VulkanYuvSingleFrameBackend.cpp", "vulkan/VulkanYuvSingleFrameBackend.h",
)}

def changed_native(reference):
    return set(subprocess.check_output(
        ["git","diff","--name-only",reference,"--",CPP],cwd=ROOT,text=True).splitlines())

changed=changed_native(BASE)
expected=expected_native | fast_capture_native
assert changed==expected, f"Unreviewed pixel engine changes: {changed ^ expected}"
fast_capture_changed=changed_native(FAST_CAPTURE_BASE)
assert fast_capture_changed==fast_capture_native, (
    f"Unreviewed changes from the qualified performance base: {fast_capture_changed ^ fast_capture_native}"
)
raw_owner=CPP+"DngMerger.cpp"
frozen_raw_owner=subprocess.check_output(
    ["git","show",FAST_CAPTURE_BASE+":"+raw_owner],cwd=ROOT,encoding="utf-8")
assert live(raw_owner)==frozen_raw_owner.replace(
    "const auto gpu = bncam::vulkan::VulkanRuntime::instance().executeRawCaptureCanonicalize(request);",
    "auto gpu = bncam::vulkan::VulkanRuntime::instance().executeRawCaptureCanonicalize(request);",
    1,
), "Fast capture may only change the RAW16 ownership handoff"

# No changes to rawnormalization, physical noise, chroma, demosaic,
# the original Guided Fine body, Vulkan backend/push constants, tone or sharpening.
for path in (
    "SingleFrameGuidedLuma.h","SingleFrameGuidedCoarse.h",
    "SingleFrameResidualConfidence.h","PhysicalChromaDenoise.h",
    "SingleFrameNoiseContext.h","RawPoissonGaussianEstimator.h","Demosaic.h",
    "vulkan/VulkanSpectraResidentDemosaicBackend.cpp",
    "vulkan/VulkanSpectraResidentDemosaicBackend.h",
    "vulkan/shaders/spectra_demosaic_resident.comp",
    "vulkan/shaders/physical_chroma_denoise.glsl",
):
    assert live(CPP+path)==base(CPP+path),f"Protected kernel changed: {path}"

shader_path=CPP+"vulkan/shaders/physical_luma_denoise.glsl"
frozen_shader=base(shader_path)
candidate_shader=live(shader_path)
assert candidate_shader.split("// Cache the 3x3 guide",1)[0].rstrip()==frozen_shader.split("// Mode-3 bits",1)[0].rstrip()
original_dispatch="    if(stage==3u)return physicalGuidedFine(xy,postChroma,telemetry,true,true);"
candidate_dispatch="""    if(stage==3u)return physicalGuidedFineBalanced(xy,postChroma,telemetry);
#if defined(BNCAM_ENABLE_GPU_BENCHMARKS)
    if(stage==6u)return physicalGuidedFine(xy,postChroma,telemetry,true,true);
#endif"""
assert candidate_dispatch in candidate_shader
selector="vec3 physicalLumaDenoise("
assert candidate_shader.split(selector,1)[1].replace(candidate_dispatch,original_dispatch,1)==frozen_shader.split(selector,1)[1]

hdr=live(CPP+"PhysicalLumaDenoise.h")
contains(hdr,'inline std::atomic<Stage> selectedStage{Stage::OFF}',
        'inline std::atomic<bool> debugStageOverrideActive{false}',
        'if (!singleShotRaw || !spectraOff) return Stage::OFF',
        'return Stage::GUIDED_V2_BALANCED;')
isp=live(CPP+"IspCore.cpp")
contains(isp,'bncam::luma::resolveStage(',
        'meta.singleShotRaw, meta.calibration.spectraProcessingMode == 0',
        '!bncam::luma::balancedAuthorized(singleFrameLumaStage)',
        'request.lumaStage = singleFrameLumaStage',
        'bncam::luma::balancedAuthorized(singleFrameLumaStage)).pixel',
        'GUIDED_V2_BALANCED_DEFAULT')
jni=live(CPP+"native-lib.cpp")
contains(jni,'#ifndef NDEBUG\n    bncam::luma::selectedStage.store',
        'Java_com_bncam_core_engine_ImageUtils_clearPhysicalLumaDebugOverride',
        'debugStageOverrideActive.store(false,std::memory_order_relaxed)',
        'debugStageOverrideActive.store(true,std::memory_order_relaxed)')
kt=live("app/src/main/java/com/bncam/core/engine/ImageUtils.kt")
contains(kt,'checkStage("off", 0)','checkStage("balanced", 3)',
         'checkStage("offRepeat", 0)',
         'balanced ?: off',
         'clearPhysicalLumaDebugOverride()')
for removed in ('checkStage("legacy"','checkStage("fine"','checkStage("coarse"',
                'checkStage("confidence"','confidence ?: combined'):
    assert removed not in kt, f"Retired render path still present: {removed}"
ui=live("app/src/main/java/com/bncam/ui/screens/settings/DeveloperSettingsScreen.kt")
contains(ui,'balancedEnabled by mutableStateOf(true)',
         'ImageUtils.clearPhysicalLumaDebugOverride()',
         'ImageUtils.setPhysicalLumaStageDebug(0)',
         'Guided Fine Balanced (default)','Capture OFF / Balanced')
assert 'Sf2Mode(' not in ui
assert 'fine + coarse' not in ui.lower()
receiver=live("app/src/debug/java/com/bncam/BenchmarkDebugReceiverController.kt")
assert 'clearPhysicalLumaOverride' in receiver
collector=live("scripts/sf2_phase7_collect.py")
contains(collector,'"balanced.txt": ("GUIDED_V2_BALANCED_DEFAULT", True)',
         '"three_debug_stage_checks_pass": matches',
         '"clearPhysicalLumaOverride", "true"')
print("SF2: Balanced default, OFF rollback, and protected kernels verified")
