#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ISP = ROOT / "src/main/cpp/IspCore.cpp"

s = ISP.read_text(encoding="utf-8")
checks = []

def check(name, cond):
    checks.append((name, bool(cond)))

audit_pos = s.find("phase8ColorMatrixAudit")
vibrance_pos = s.find("const float rawJpegBaseVibrance")
tone_pos = s.find("POINTWISE PASS 1: EXPOSURE")
tele_pos = s.find("phase8ColorValidationDomain=POST_AWB_CCM_LINEAR_SRGB_PRE_PRESENTATION")

check("SensorColorScienceV2 included", '#include "SensorColorScienceV2.h"' in s)
check("matrix audit integrated", audit_pos >= 0)
check("scene mean audit integrated", "phase8PrePresentationSceneAudit" in s)
check("audit occurs before hidden base vibrance", 0 <= audit_pos < vibrance_pos)
check("audit occurs before pointwise tone/presentation pass", 0 <= audit_pos < tone_pos)
check("explicit pre-presentation domain telemetry", tele_pos >= 0)
check("vibrance exclusion telemetry", "phase8PresentationVibranceExcluded=true" in s)
check("profile color exclusion telemetry", "phase8ProfileColorControlsExcluded=true" in s)
check("tone exclusion telemetry", "phase8ToneExcluded=true" in s)
check("no full frame readback telemetry", "phase8ColorValidationFullFrameReadback=false" in s)
check("matrix neutral axis spread telemetry", "phase8EffectiveCcmNeutralAxisSpread=" in s)
check("pre-presentation scene spread telemetry", "phase8PrePresentationSceneMeanRgbSpread=" in s)
check("existing hidden vibrance behavior untouched", "const float rawJpegBaseVibrance = lowLightPresentationPlan.rawBaseVibrance;" in s)

for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {name}")
failed = [name for name, ok in checks if not ok]
if failed:
    raise SystemExit(f"PHASE8_NEUTRAL_INTEGRATION_CONTRACT_FAIL={len(failed)}")
print(f"PHASE8_NEUTRAL_INTEGRATION_CONTRACT_PASS={len(checks)}")
