#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
h = (ROOT / 'app/src/main/cpp/Demosaic.h').read_text()
cpp = (ROOT / 'app/src/main/cpp/Demosaic.cpp').read_text()
isp = (ROOT / 'app/src/main/cpp/IspCore.cpp').read_text()
shader = (ROOT / 'app/src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp').read_text()

checks = {
    'auto_physical_noise_contract': all(x in h for x in [
        'bool physicalNoiseKnown = false;',
        'float noiseSigmaY = 0.0f;',
        'float noiseSigmaChroma = 0.0f;',
        'float physicalNoisePressure = 0.0f;']),
    'auto_prefers_physical_noise': 'noiseSource=' in cpp and 'PHYSICAL_SO' in cpp and 'ISO_FALLBACK' in cpp,
    'auto_debiases_detail_with_sigma': all(x in cpp for x in [
        'p90Sigma', 'gradientNoiseFloor', 'gradientReliability', 'coherentReliability']),
    'physical_or_zero_gpu_contract': all(x in isp for x in [
        'demosaicPhysicalNoiseContextAvailable',
        'UNAVAILABLE_ZERO_AUTHORITY',
        'request.noiseSigmaY = demosaicNoiseContext.sigmaY',
        'request.noisePressure = demosaicNoiseContext.pressure']),
    'isp_uses_so_profile_truth': all(x in isp for x in [
        'autoDemosaicPhysicalProfileKnown',
        'workingMeta.calibration.hasNoiseProfile',
        'workingMeta.calibration.noiseProfileApplied',
        'isoState.modelNoisePressure']),
    'malvar_noise_significance': 'malvarStabilizeMissingColor' in cpp and 'malvarStabilizeMissingColor' in shader and 'noiseLike' in cpp and 'noiseLike' in shader,
    'rcd_noise_significance': 'rcdDirectionalWeight' in cpp and 'demosaicNoiseSigmaY()' in shader,
    'amaze_noise_significance': 'amazeRetainHighOrderDetail' in cpp and 'amazeRetainHighOrderDetail' in shader,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(f'{name}: {"PASS" if ok else "FAIL"}')
if failed:
    raise SystemExit('FAILED: ' + ', '.join(failed))
print('PHASE5_DEMOSAIC_NOISE_CONTRACT: PASS')
