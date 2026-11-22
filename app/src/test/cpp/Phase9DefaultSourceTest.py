"""SF2 Final: product default gate, isolation and stage/RAW correctness."""
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[4]
BASE="572ef4546a6fd0ecdb67dfb7f87d64f0f452a132"
CPP="app/src/main/cpp/"

def live(path): return (ROOT/path).read_text(encoding="utf-8")
def base(path): return subprocess.check_output(["git","show",BASE+":"+path],cwd=ROOT,text=True)
def contains(source,*needles):
    for needle in needles: assert needle in source, f"Missing {needle}"

expected_native={CPP+"IspCore.cpp",CPP+"PhysicalLumaDenoise.h",CPP+"native-lib.cpp"}
changed=set(subprocess.check_output(
    ["git","diff","--name-only",BASE,"HEAD","--",CPP],cwd=ROOT,text=True).splitlines())
assert changed==expected_native, f"Unreviewed pixel engine changes: {changed ^ expected_native}"

# No changes to rawnormalization, physical noise, chroma, demosaic,
# Guided Fine itself, Vulkan backend/push constants/shader, tone or sharpening.
for path in (
    "SingleFrameGuidedLuma.h","SingleFrameGuidedCoarse.h",
    "SingleFrameResidualConfidence.h","PhysicalChromaDenoise.h",
    "SingleFrameNoiseContext.h","RawPoissonGaussianEstimator.h","Demosaic.h",
    "vulkan/VulkanSpectraResidentDemosaicBackend.cpp",
    "vulkan/VulkanSpectraResidentDemosaicBackend.h",
    "vulkan/shaders/physical_luma_denoise.glsl",
    "vulkan/shaders/spectra_demosaic_resident.comp",
    "vulkan/shaders/physical_chroma_denoise.glsl",
):
    assert live(CPP+path)==base(CPP+path),f"Protected kernel changed: {path}"

hdr=live(CPP+"PhysicalLumaDenoise.h")
contains(hdr,'inline std::atomic<Stage> selectedStage{Stage::OFF}',
        'inline std::atomic<bool> debugStageOverrideActive{false}',
        'if (!singleShotRaw || !spectraOff) return Stage::OFF',
        'return Stage::GUIDED_V2_BALANCED;')
isp=live(CPP+"IspCore.cpp")
contains(isp,'bncam::luma::resolveStage(',
        'meta.singleShotRaw, meta.calibration.spectraProcessingMode == 0',
        'singleFrameLumaStage != bncam::luma::Stage::GUIDED_V2_BALANCED',
        'request.lumaStage = singleFrameLumaStage',
        'singleFrameLumaStage==bncam::luma::Stage::GUIDED_V2_BALANCED).pixel',
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
print("SF2-FINAL: balanced default and OFF rollback gated, experimental UI modes retired, protected pixel kernels unchanged")
