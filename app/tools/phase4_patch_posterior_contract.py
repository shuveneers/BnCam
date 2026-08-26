#!/usr/bin/env python3
from pathlib import Path
import math, re, sys

ROOT = Path(__file__).resolve().parents[2]
SHADER = ROOT / 'app/src/main/cpp/vulkan/shaders/spectra_pass1_resident.comp'
BACKEND_H = ROOT / 'app/src/main/cpp/vulkan/VulkanSpectraResidentPreDemosaicBackend.h'
BACKEND_CPP = ROOT / 'app/src/main/cpp/vulkan/VulkanSpectraResidentPreDemosaicBackend.cpp'
DETAIL_H = ROOT / 'app/src/main/cpp/SpectraAnisotropicDetail.h'
ISP = ROOT / 'app/src/main/cpp/IspCore.cpp'

for path in (SHADER, BACKEND_H, BACKEND_CPP, DETAIL_H, ISP):
    if not path.exists():
        raise SystemExit(f'missing {path}')

shader = SHADER.read_text()
backend_h = BACKEND_H.read_text()
backend_cpp = BACKEND_CPP.read_text()
detail_h = DETAIL_H.read_text()
isp = ISP.read_text()

required_shader = [
    'posteriorRetainedRatio',
    'gradientGuard',
    'firmRetainedCoefficient(',
    'float centerResidual = centerVst - consensus;',
    'float shadowNoisePressure = 1.0 - smoothstepSpectra(4.25, 9.50, localSnr);',
    'float effectiveNoise = max(noise, 0.42 * shadowNoisePressure);',
    'atomicAdd(telemetry[89], 1u)',
    'atomicAdd(telemetry[90], 1u)',
]
for token in required_shader:
    assert token in shader, token
assert 'constexpr std::uint64_t kTelemetryWordCount = 96u;' in backend_cpp
assert 'profiledPatchPosteriorCleanPixelCount' in backend_h
assert 'profiledPatchGradientProtectedPixelCount' in backend_h
assert 'words[89]' in backend_cpp and 'words[90]' in backend_cpp
assert 'profiledPatchPosteriorCleanFraction' in detail_h
assert 'spectraProfiledPatchPosteriorCleanFraction' in isp
assert 'spectraProfiledPatchGradientProtectedFraction' in isp

# Local identifiers may not use GLSL's reserved `flat` keyword.
assert re.search(r'\b(?:float|int|bool|uint|vec\d|ivec\d)\s+flat\b', shader) is None


def smoothstep(a, b, x):
    t = min(1.0, max(0.0, (x-a)/(b-a)))
    return t*t*(3.0-2.0*t)

def firm(c, lo, hi):
    m=abs(c)
    if m <= lo: return 0.0
    if m >= hi: return c
    return c*smoothstep(lo,hi,m)

def posterior(residual, flatness, noise, texture, edge, gradient_z):
    lo = 1.08 + 0.44*flatness + 0.18*noise
    hi = 3.05 + 0.55*(1.0-texture) + 0.20*(1.0-edge)
    kept=firm(residual,lo,max(lo+0.75,hi))
    ratio=abs(kept)/max(abs(residual),1e-5)
    grad_guard=1.0-smoothstep(1.30,2.85,gradient_z)
    return ratio, grad_guard

# High-noise flat stochastic residual collapses strongly.
r,g=posterior(1.15,0.82,0.66,0.08,0.05,0.25)
assert r < 0.05 and g > 0.95, (r,g)
# Clearly significant structure stays substantially intact.
r,g=posterior(4.8,0.82,0.66,0.08,0.05,0.25)
assert r > 0.95 and g > 0.95, (r,g)
# Opposing-candidate gradient suppresses patch authority.
r,g=posterior(1.15,0.82,0.66,0.08,0.05,3.1)
assert g < 0.05, (r,g)


def effective_noise(global_noise, raw_signal, s, o):
    sigma=math.sqrt(max(1e-12, s*max(0.0,raw_signal)+o))
    snr=max(0.0,raw_signal)/max(sigma,1e-6)
    shadow=1.0-smoothstep(4.25,9.50,snr)
    return max(global_noise,0.42*shadow), snr

# Low global ISO/noise pressure must not suppress cleaning in a genuinely
# underexposed, low-SNR shadow. The same sensor model should leave a bright
# high-SNR pixel on the conservative low-noise path.
eff,snr=effective_noise(0.014,0.0072,1.77e-4,2.2e-6)
assert snr < 5.0 and eff > 0.30, (eff,snr)
eff_bright,snr_bright=effective_noise(0.014,0.08,1.77e-4,2.2e-6)
assert snr_bright > 9.5 and eff_bright < 0.02, (eff_bright,snr_bright)

print('PASS phase4_patch_posterior_contract')
