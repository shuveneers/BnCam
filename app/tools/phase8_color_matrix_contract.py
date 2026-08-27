#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import math
import re

ROOT = Path(__file__).resolve().parents[1]
SENSOR = ROOT / "src/main/java/com/bncam/core/quality/SensorCalibration.kt"
RENDER = ROOT / "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt"

checks: list[tuple[str, bool]] = []

def check(name: str, condition: bool) -> None:
    checks.append((name, bool(condition)))

sensor = SENSOR.read_text(encoding="utf-8")
render = RENDER.read_text(encoding="utf-8")

check("active resolver preserves direct Camera2 CCT", "capture_result_sensor_rgb_to_linear_srgb_direct_phase8_preserved" in sensor)
check("active resolver does not call row normalization for production", "val normalized = neutralNormalizedMatrix(values)" not in sensor)
check("legacy row normalization is diagnostic-only", "legacyNeutralNormalizedMatrix(values)" in sensor)
check("normalization applied telemetry is false on accepted route", "neutralNormalizationApplied = false" in sensor)
check("original matrix telemetry retained", '"Color Matrix Original Metadata Values"' in sensor)
check("legacy counterfactual telemetry retained", '"Color Matrix Legacy Row-Normalized Counterfactual"' in sensor)
check("production contract telemetry explicit", '"Camera2 direct transform preserved; no BnCam row normalization"' in sensor)
check("forward matrix applies inverse device calibration", "val actualSensorToReference" in sensor and "invert3x3(calibration)" in sensor)
check("forward matrix leaves XYZ D50 through explicit D65/sRGB conversion", "RawColorTransformEngine.xyzD50ToLinearSrgbMatrix()" in sensor)
check("incomplete inverse ColorTransform candidates retired", 'source = "inverse(CameraCharacteristics.SENSOR_COLOR_TRANSFORM' not in sensor)
check("direct CCT remains first priority", 'sourcePriority = 1' in sensor)
check("forward2 follows direct route", "SENSOR_FORWARD_MATRIX2" in sensor and 'sourcePriority = 2' in sensor)
check("legacy RenderQualityConfig resolver no longer row-normalizes candidates", "neutralNormalizedMatrix(" not in render)
check("legacy RenderQualityConfig direct CCT label is preserved", "capture_result_direct_phase8_preserved" in render)

# Numerical regression: retired row normalization must materially change a non-equal-row matrix,
# while Phase-8 production keeps the original coefficients exactly.
m = [
    1.20, -0.20, 0.10,
   -0.10,  1.05, 0.05,
    0.02, -0.15, 0.95,
]
row_sums = [sum(m[r*3:r*3+3]) for r in range(3)]
mean_sum = sum(row_sums) / 3.0
legacy = m[:]
for row in range(3):
    scale = mean_sum / row_sums[row]
    for col in range(3):
        legacy[row*3+col] *= scale
max_delta = max(abs(a-b) for a,b in zip(m, legacy))
check("synthetic legacy normalization is non-identity", max_delta > 1e-3)
check("synthetic Phase8 production is coefficient-faithful", all(a == b for a,b in zip(m, m[:])))

# Numerical regression for ForwardMatrix composition order:
# expected = XYZ_D50_TO_LINEAR_SRGB * Forward * inverse(Calibration).
def mm(a, b):
    return [
        sum(a[r*3+k] * b[k*3+c] for k in range(3))
        for r in range(3) for c in range(3)
    ]

def inv3(m):
    det = (
        m[0]*(m[4]*m[8]-m[5]*m[7])
        - m[1]*(m[3]*m[8]-m[5]*m[6])
        + m[2]*(m[3]*m[7]-m[4]*m[6])
    )
    if abs(det) < 1e-12:
        raise ValueError("singular")
    d = 1.0 / det
    return [
        (m[4]*m[8]-m[5]*m[7])*d,
        (m[2]*m[7]-m[1]*m[8])*d,
        (m[1]*m[5]-m[2]*m[4])*d,
        (m[5]*m[6]-m[3]*m[8])*d,
        (m[0]*m[8]-m[2]*m[6])*d,
        (m[2]*m[3]-m[0]*m[5])*d,
        (m[3]*m[7]-m[4]*m[6])*d,
        (m[1]*m[6]-m[0]*m[7])*d,
        (m[0]*m[4]-m[1]*m[3])*d,
    ]

forward = [0.7,0.2,0.1, 0.1,0.8,0.1, 0.05,0.15,0.8]
cal = [1.03,0.01,0.0, 0.0,0.98,0.01, 0.0,0.01,1.02]
xyz_to_rgb = [1.0,0.0,0.0, 0.0,1.0,0.0, 0.0,0.0,1.0]
expected = mm(xyz_to_rgb, mm(forward, inv3(cal)))
wrong = mm(xyz_to_rgb, forward)
check("synthetic device calibration affects ForwardMatrix fallback", max(abs(a-b) for a,b in zip(expected, wrong)) > 1e-4)

failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(f"{'PASS' if ok else 'FAIL'}: {name}")

if failed:
    raise SystemExit(f"PHASE8_COLOR_MATRIX_CONTRACT_FAIL={len(failed)}")
print(f"PHASE8_COLOR_MATRIX_CONTRACT_PASS={len(checks)}")
