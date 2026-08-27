from pathlib import Path
root=Path(__file__).resolve().parents[1]
physical=(root/'src/main/cpp/SpectraPhysicalBaselineNr.h').read_text()
cloud=(root/'src/main/cpp/SpectraNoiseCalibration.h').read_text()
tone=(root/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp').read_text()
checks={
 'wb ccm pressure strengthened only in chroma plan': '0.18f * plan.wbCcmPressure' in physical,
 'upstream credit reduced': '1.0f - 0.50f * plan.upstreamChromaReduction' in physical,
 'headroom still bounded': '0.42f, 1.0f' in physical,
 'physical chroma cap retained': '0.0f, 0.86f' in physical,
 'spectra enhancement unchanged': 'spectraContextFusionActive ? 0.10f : 0.0f' in physical,
 'cloud authority red 024': '0.24f * plan.riskEvidence * redSupport' in cloud,
 'cloud authority blue 024': '0.24f * plan.riskEvidence * blueSupport' in cloud,
 'cloud cap 026': cloud.count('0.0f, 0.26f') >= 2,
 'cloud measured residual budget unchanged': 'std::min(0.010f, 0.50f * residualRms)' in cloud,
 'shadow cloud cap bounded': 'std::min(0.30f, plan.redAuthority * shadowAuthorityBoost)' in cloud and 'std::min(0.30f, plan.blueAuthority * shadowAuthorityBoost)' in cloud,
 'luma strength explicitly capped': 'float lumaStrength = min(strength, 0.64);' in tone,
 'luma authority uses capped strength': '0.08 + 0.28 * lumaStrength' in tone,
 'luma noise sigma uses capped strength': 'mix(0.0025, 0.0070, lumaStrength)' in tone,
 'chroma still uses full strength': 'float blend = clamp(strength * saturatedColourProtection * highlightProtection' in tone,
 'chroma ridge still uses full strength': 'mix(0.45, 1.90, strength)' in tone,
 'pre-tone upper headroom still unclamped': 'Do NOT upper-clamp here' in tone,
}
failed=[n for n,v in checks.items() if not v]
if failed:
    for n in failed: print('FAIL',n)
    raise SystemExit(1)
print(f'PHASE9_0062_INTEGRATION_CONTRACT_PASS={len(checks)}')
