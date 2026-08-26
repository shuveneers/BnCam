#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
import math, re

ROOT = Path(__file__).resolve().parents[1]
shader = (ROOT/'src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp').read_text()
backend_h = (ROOT/'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h').read_text()
backend_cpp = (ROOT/'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp').read_text()
demosaic_cpp = (ROOT/'src/main/cpp/Demosaic.cpp').read_text()
demosaic_h = (ROOT/'src/main/cpp/Demosaic.h').read_text()
isp = (ROOT/'src/main/cpp/IspCore.cpp').read_text()
kt = (ROOT/'src/main/java/com/bncam/core/quality/DemosaicMode.kt').read_text()
noise = (ROOT/'src/main/cpp/SpectraNoisePropagation.h').read_text()

checks = {
    'auto_ui_name': 'AUTO(0, "Auto Hybrid", true)' in kt,
    'auto_persist_migration': '"AUTO_HYBRID", "AUTO HYBRID", "AUTOHYBRID"' in kt,
    'resolver_marks_hybrid': 'result.autoHybridExecution = true;' in demosaic_cpp,
    'resolver_priors': all(x in demosaic_cpp for x in ['autoMalvarPrior','autoNeuralJddPrior','autoAmazePrior']),
    'gpu_enum': 'AUTO_HYBRID = 5u' in backend_h,
    'gpu_request_priors': all(x in backend_h for x in ['autoMalvarPrior','autoNeuralJddPrior','autoAmazePrior']),
    'guide_mode': 'push.mode = 7u;' in backend_cpp and 'pc.mode == 7u' in shader,
    'blend_mode': '? 6u : 8u' in backend_cpp and 'pc.mode == 8u' in shader,
    'guide_buffer_reused': 'usesGuideScratch' in backend_cpp and 'rgbUpload_.buffer' in backend_cpp,
    'local_structure': 'autoHybridSmoothedStructure' in shader and 'colorRgb[base + 2u]' in shader,
    'local_nyquist': 'amazeSmoothedNyquist' in shader and 'float nyquist = amazeSmoothedNyquist' in shader,
    'local_chroma_risk': 'float chromaRisk = 0.5 * (amazeChromaRisk(0u) + amazeChromaRisk(2u));' in shader,
    'physical_noise': 'float noise = demosaicNoisePressure();' in shader,
    'soft_blend': 'weights.r * malvarRgb + weights.g * neuralRgb + weights.b * amazeRgb' in shader,
    'spatial_regularization': 'nearTie' in shader and 'weights = mix(weights, prior, 0.35 * nearTie);' in shader,
    'no_new_descriptor_binding': 'VkDescriptorSetLayoutBinding bindings[6]' in backend_cpp,
    'isp_routes_auto_hybrid': 'SpectraGpuDemosaicAlgorithm::AUTO_HYBRID' in isp,
    'isp_typed_fallback': 'auto_hybrid_gpu_failed_typed_single_route_cpu_fallback' in isp,
    'telemetry_identity': 'resolvedDemosaicAlgorithm=' in isp and '"AUTO_HYBRID"' in isp,
    'noise_proxy': 'propagateAutoHybridDemosaic' in noise and 'AUTO_HYBRID_PRIOR_WEIGHTED_LOCAL_BLEND_ENERGY_PROXY' in noise,
}

# Mirror shader confidence math to prove normalization/continuity and intended region response.
def normalize(v):
    s=sum(v)
    return tuple(x/s for x in v)

def weights(prior, structure, nyquist, noise_p, chroma, low_signal):
    p=normalize(prior)
    mal=p[0]*(0.86+0.30*noise_p+0.16*chroma+0.10*low_signal+0.08*(1.0-structure))
    neu=p[1]*(0.78+0.62*noise_p+0.24*chroma+0.16*low_signal+0.10*structure-0.18*nyquist)
    ama=p[2]*(0.74+0.58*structure+0.72*nyquist-0.42*noise_p-0.18*chroma-0.08*low_signal)
    w=list(normalize((max(mal,0.015),max(neu,0.015),max(ama,0.015))))
    top=max(w); second=sorted(w)[-2]; d=top-second
    # smoothstep(0.045,0.16,d)
    t=max(0.0,min(1.0,(d-0.045)/(0.16-0.045)))
    sm=t*t*(3-2*t)
    near=1-sm
    w=[(1-0.35*near)*w[i]+0.35*near*p[i] for i in range(3)]
    w=[max(w[i],0.035*p[i]) for i in range(3)]
    return normalize(w)

prior=(0.34,0.33,0.33)
flat_noisy=weights(prior,0.05,0.02,0.95,0.70,0.90)
clean_texture=weights(prior,0.95,0.90,0.05,0.05,0.05)
neutral=weights(prior,0.45,0.25,0.25,0.15,0.20)
checks['weights_normalized'] = all(abs(sum(w)-1.0)<1e-9 and min(w)>=0 for w in [flat_noisy,clean_texture,neutral])
checks['noisy_region_not_amaze_dominant'] = max(flat_noisy[0],flat_noisy[1]) > flat_noisy[2]
checks['clean_texture_amaze_dominant'] = clean_texture[2] > clean_texture[0] and clean_texture[2] > clean_texture[1]
# Small input perturbations must not produce a hard selector jump.
a=weights(prior,0.500,0.300,0.300,0.200,0.200)
b=weights(prior,0.505,0.305,0.300,0.200,0.200)
checks['continuous_soft_confidence'] = max(abs(a[i]-b[i]) for i in range(3)) < 0.03

failed=[k for k,v in checks.items() if not v]
for k,v in checks.items(): print(f'{k}: {"PASS" if v else "FAIL"}')
print('flat_noisy=',tuple(round(x,4) for x in flat_noisy))
print('clean_texture=',tuple(round(x,4) for x in clean_texture))
print('neutral=',tuple(round(x,4) for x in neutral))
if failed:
    raise SystemExit('FAILED: '+', '.join(failed))
print(f'AUTO_HYBRID_CONTRACT_PASS {len(checks)}/{len(checks)}')
