import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def src(p): return (ROOT/p).read_text(encoding="utf-8")

class FinalProductControl(unittest.TestCase):
    def test_product_default_is_guarded(self):
        h=src("app/src/main/cpp/PhysicalLumaDenoise.h")
        self.assertIn('if (!singleShotRaw || !spectraOff) return Stage::OFF;',h)
        self.assertIn('return Stage::GUIDED_V2_BALANCED;',h)
        self.assertIn('debugStageOverrideActive{false}',h)
    def test_spectral_and_multiframe_not_promoted(self):
        s=src("app/src/main/cpp/IspCore.cpp")
        self.assertIn('meta.singleShotRaw, meta.calibration.spectraProcessingMode == 0',s)
    def test_compare_saved_jpeg_is_balanced(self):
        s=src("app/src/main/java/com/bncam/core/engine/ImageUtils.kt")
        self.assertIn('balanced ?: off',s)
        self.assertIn('checkStage("balanced", 3)',s)
        self.assertNotIn('confidence ?: combined',s)
    def test_camera_debug_selection_only_off_or_balanced(self):
        ui=src("app/src/main/java/com/bncam/ui/screens/settings/DeveloperSettingsScreen.kt")
        self.assertNotIn('Sf2Mode(',ui)
        self.assertIn('balancedEnabled by mutableStateOf(true)',ui)
        self.assertIn('Capture OFF / Balanced',ui)
        self.assertIn('clearPhysicalLumaDebugOverride()',ui)
    def test_gpu_qualification_retained(self):
        s=src("app/src/main/cpp/SingleFrameIntegrationJni.cpp")
        self.assertIn('std::array<std::pair<Stage,bool>,7>',s)
        self.assertIn('POST_STAGE_OFF_PIXEL_IDENTITY_FAILED',s)
    def test_fail_closed_collector_retained(self):
        s=src("scripts/sf2_phase7_collect.py")
        self.assertIn('three_debug_stage_checks_pass',s)
        self.assertIn('gpu_cpu_parity_pass',s)
        self.assertIn('clearPhysicalLumaOverride',s)

if __name__=="__main__": unittest.main()
