#!/usr/bin/env python3
"""Source + policy contract for Phase 4 profiled CFA chroma posterior.

This does not compile GLSL.  It locks the intended physical policy and checks
that the Vulkan shader still contains the required model-driven mechanisms.
"""
from __future__ import annotations

import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHADER = ROOT / "src/main/cpp/vulkan/shaders/spectra_chroma_resident.comp"
PASS1_SHADER = ROOT / "src/main/cpp/vulkan/shaders/spectra_pass1_resident.comp"


def smoothstep(edge0: float, edge1: float, x: float) -> float:
    if edge1 <= edge0:
        return 1.0 if x >= edge1 else 0.0
    t = max(0.0, min(1.0, (x - edge0) / (edge1 - edge0)))
    return t * t * (3.0 - 2.0 * t)


def retention(z: float, noise_pressure: float, confidence: float, structure: float) -> float:
    confidence = max(0.0, min(1.0, confidence))
    threshold_scale = 0.72 + 0.28 * confidence
    low_z = (1.18 + (1.82 - 1.18) * noise_pressure) * threshold_scale
    high_z = (3.10 + (4.45 - 3.10) * noise_pressure) * threshold_scale
    r = smoothstep(low_z, max(low_z + 0.35, high_z), abs(z))
    r = max(r, 0.32 * (1.0 - confidence))
    r = max(r, 0.94 * max(0.0, min(1.0, structure)))
    return max(0.0, min(1.0, r))


def main() -> None:
    text = SHADER.read_text(encoding="utf-8")
    pass1_text = PASS1_SHADER.read_text(encoding="utf-8")
    required = [
        "profiledOpponentRetention(",
        "galoshLocalChromaStructureProtection(",
        "posteriorChroma = loess.targetChroma + retained * residualToModel",
        "telemetry[13]",
        "telemetry[14]",
        "telemetry[15]",
        "2.85 + 1.20 * saturate(pc.params0.y)",
        "0.0180",
        "lowZ = mix(1.05, 1.45, pressure)",
        "highZ = mix(2.85, 3.95, pressure)",
        "mix(0.84, 0.94, saturate(denoisePressure))",
        "float shrinkage = clamp(a * structureWeight * visibleBoost * confidenceBoost, 0.0, 0.90)",
        "mix(0.78, 0.92, localGridConfidence) * abs(blotch)",
    ]
    missing = [token for token in required if token not in text]
    assert not missing, f"missing shader contract tokens: {missing}"
    pass1_required = [
        "resolveProfiledPatchConsensus(",
        "meanDistance - 2.0",
        "telemetry[87]",
        "telemetry[88]",
        "texture > 0.78",
        "edge > 0.78",
    ]
    missing_pass1 = [token for token in pass1_required if token not in pass1_text]
    assert not missing_pass1, f"missing pass1 patch contract tokens: {missing_pass1}"

    # A sub-sigma residual in a noisy flat must collapse to the model.
    assert retention(0.8, 0.75, 1.0, 0.0) < 0.02
    # Strong statistically significant chroma must survive without shrinkage bias.
    assert retention(5.2, 0.75, 1.0, 0.0) > 0.95
    # Coherent iso-luminant colour structure overrides the statistical shrinker.
    assert retention(0.7, 0.75, 1.0, 0.90) >= 0.84
    # Reduced S/O confidence must not allow an over-confident clean posterior.
    assert retention(0.7, 0.75, 0.5, 0.0) >= 0.15

    # Mid-band firm threshold: a noise-like coefficient should be removed while
    # a clearly significant one should survive.
    pressure = 0.75
    low_mid = 1.05 + (1.45 - 1.05) * pressure
    high_mid = 2.85 + (3.95 - 2.85) * pressure
    assert smoothstep(low_mid, high_mid, 0.9) < 0.01
    assert smoothstep(low_mid, high_mid, 4.4) > 0.98

    print("PASS phase4_profiled_chroma_contract")


if __name__ == "__main__":
    main()
