from pathlib import Path
import math, re

root = Path(__file__).resolve().parents[1]
shader = (root/'src/main/cpp/vulkan/shaders/spectra_tone_resident.comp').read_text()
header = (root/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.h').read_text()
backend = (root/'src/main/cpp/vulkan/VulkanSpectraResidentToneBackend.cpp').read_text()
isp = (root/'src/main/cpp/IspCore.cpp').read_text()
demosaic_shader = (root/'src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp').read_text()
demosaic_backend = (root/'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp').read_text()

checks = {
    'no_demosaic_family_request': 'preToneChromaDemosaicFamily' not in header and 'preToneChromaDemosaicFamily' not in isp,
    'no_authority_scale_request': 'preToneChromaAuthorityScale' not in header and 'phase9DemosaicAuthorityScale' not in isp,
    'no_detail_boost_request': 'preToneChromaDetailProtectionBoost' not in header and 'phase9DemosaicDetailProtectionBoost' not in isp,
    'no_residual_floor_scale_request': 'preToneChromaResidualFloorScale' not in header and 'phase9DemosaicResidualFloorScale' not in isp,
    'no_shader_family_heuristic': 'phase9DemosaicFamily' not in shader and 'autoHybridBias' not in shader and 'demosaicAuthorityScale' not in shader,
    'measured_noise_inputs_retained': 'preToneChromaNoisePressure' in header and 'preToneChromaWbCcmPressure' in header,
    'uniform_tile_authority': '0.28 * tileFlatness * tileNoisePressure' in shader,
    'multiscale_detail_guard_retained': 'multiScalePredictionDisagreement' in shader and 'predictionDisagreementProtection' in shader,
    'stochastic_gate_uniform': '0.45 * tileResidualVariance' in shader and '1.85 * tileResidualVariance' in shader,
    'per_opponent_gate_uniform': 'smoothstep(0.30, 1.80, residualZ.x)' in shader and 'smoothstep(0.30, 1.80, residualZ.y)' in shader,
    'telemetry_back_to_eight_words': 'kTelemetryWords = 8u' in backend,
    '0065_post_demosaic_domain_retained': 'writeResidualCandidate(gid, false)' in demosaic_shader and 'push.mode = 4u;' in demosaic_backend,
    '0065_post_colour_domain_retained': 'writeResidualCandidate(gid, true)' in demosaic_shader and 'push.mode = 11u;' in demosaic_backend,
}

indices = [int(x) for x in re.findall(r'telemetry\[(\d+)\]', shader)]
checks['telemetry_indexes_fit_eight_words'] = bool(indices) and max(indices) <= 7

failed = [k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('PHASE9_0066_UNIFORM_EVIDENCE_CONTRACT_FAIL=' + ','.join(failed))

# Numerical sanity of the uniform decision structure. This does not compile GLSL; it
# verifies the intended gate behaviour independently from any demosaic/format label.
def smoothstep(a,b,x):
    if b <= a:
        return 1.0 if x >= b else 0.0
    t=max(0.0,min(1.0,(x-a)/(b-a)))
    return t*t*(3.0-2.0*t)

def authority(*, strength=.75, tile_noise=.8, flatness=.9, local_var=4e-5, tile_var=1e-5,
              luma_prot=0.0, chroma_prot=0.0, disagreement=0.0, tile_sigma=.003,
              residual_z=2.2, saturation=.1, y=.25):
    disagreement_prot=smoothstep(1.0*tile_sigma,2.4*tile_sigma,disagreement)
    detail=max(luma_prot,chroma_prot,disagreement_prot)
    stochastic=smoothstep(.45*tile_var,1.85*tile_var,local_var)*(1.0-detail)
    channel=smoothstep(.30,1.80,residual_z)
    sat_prot=1.0-.70*smoothstep(.34,.78,saturation)
    highlight=1.0-.78*smoothstep(.70,1.08,y)
    base=max(0.0,min(.94,strength*tile_noise*flatness*stochastic*sat_prot*highlight))
    return base*channel

flat_noisy=authority()
luma_edge=authority(luma_prot=.98)
chroma_edge=authority(chroma_prot=.98)
multiscale_detail=authority(disagreement=.010)
clean_flat=authority(local_var=2e-6,residual_z=.25)
highlight=authority(y=1.05)
saturated=authority(saturation=.9)

num_checks = {
    'flat_noisy_keeps_useful_authority': flat_noisy > .25,
    'luma_edge_is_protected': luma_edge < flat_noisy * .05,
    'chroma_edge_is_protected': chroma_edge < flat_noisy * .05,
    'multiscale_disagreement_is_protected': multiscale_detail < flat_noisy * .05,
    'clean_flat_does_not_get_false_authority': clean_flat < .01,
    'highlight_reduces_authority': highlight < flat_noisy * .35,
    'saturation_reduces_authority': saturated < flat_noisy * .40,
}
failed_num=[k for k,v in num_checks.items() if not v]
if failed_num:
    raise SystemExit('PHASE9_0066_NUMERICAL_SANITY_FAIL=' + ','.join(failed_num))

print('PHASE9_0066_UNIFORM_EVIDENCE_CONTRACT_PASS=' + str(len(checks)))
print('PHASE9_0066_NUMERICAL_SANITY_PASS=' + str(len(num_checks)))
print('PHASE9_0066_AUTHORITIES=' + ','.join(f'{k}:{v:.6f}' for k,v in {
    'flat_noisy':flat_noisy,'luma_edge':luma_edge,'chroma_edge':chroma_edge,
    'multiscale_detail':multiscale_detail,'clean_flat':clean_flat,
    'highlight':highlight,'saturated':saturated}.items()))
