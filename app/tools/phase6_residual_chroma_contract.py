#!/usr/bin/env python3
from __future__ import annotations
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HDR = ROOT / 'src/main/cpp/SpectraResidualChromaArtifact.h'
CPP = ROOT / 'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.cpp'
HPP = ROOT / 'src/main/cpp/vulkan/VulkanSpectraResidentDemosaicBackend.h'
GLSL = ROOT / 'src/main/cpp/vulkan/shaders/spectra_demosaic_resident.comp'
ISP = ROOT / 'src/main/cpp/IspCore.cpp'


def smoothstep(a,b,x):
    if not b>a: return 1.0 if x>=b else 0.0
    t=max(0.0,min(1.0,(x-a)/(b-a)))
    return t*t*(3-2*t)

def median4(a,b,c,d):
    lo0=min(a,b); hi0=max(a,b); lo1=min(c,d); hi1=max(c,d)
    return 0.5*(max(lo0,lo1)+min(hi0,hi1))

def classify(center,n,g0,gs,sigy=0.006,sigc=0.009,pressure=0.4):
    sigma=max(sigc,2.5e-5); sigmaY=max(sigy,2.5e-5)
    target=median4(*n[:4]); mn=min(n); mx=max(n); delta=center-target
    outside=center-mn if center<mn else (center-mx if center>mx else 0.0)
    tol=2.35*sigma+0.045*abs(center)+7.5e-4
    support=sum(abs(v-center)<=tol for v in n)
    h=abs(n[0]-2*center+n[1]); v=abs(n[2]-2*center+n[3]); zmetric=max(h,v)
    span=max(n[:4])-min(n[:4])
    gdelta=max(abs(v-g0) for v in gs)
    structure=smoothstep(2*sigmaY+0.004,7*sigmaY+0.055,gdelta)
    directional=min(min(abs(center-n[0]),abs(center-n[1])),min(abs(center-n[2]),abs(center-n[3])))
    coherent=support>=2 or directional <= 2.75*sigma+0.035*abs(center)+7.5e-4
    edge=coherent and (span>2*sigma or structure>0.20)
    saturated=abs(center)>=max(0.075,6*sigma) and support>=2
    isolated=1-smoothstep(0.75,2.75,float(support))
    residual=smoothstep(2*sigma,5.5*sigma+0.002,abs(delta))
    overshoot=smoothstep(1.35*sigma,4*sigma+0.0015,abs(outside))
    zipper=smoothstep(2.5*sigma+0.30*span,6*sigma+0.70*span+0.002,zmetric)*isolated
    conf=max(overshoot,zipper,residual*isolated)
    if edge: conf*=0.10+0.22*overshoot
    if saturated: conf*=0.08
    if coherent: conf*=1-0.45*structure
    maxblend=max(0.82,min(0.92,0.82+0.10*pressure))
    conf=max(0,min(maxblend,conf))
    maxcorr=max(0.055,min(0.12,0.045+5.5*sigc))
    corr=0.0
    if conf>=0.015:
        cap=min(maxcorr,0.006+6*sigma+0.045*abs(target))
        corr=max(-cap,min(cap,delta))*conf
    return dict(correction=corr, confidence=conf, edge=edge, saturated=saturated,
                isolated=(isolated>0.62 and max(residual,overshoot)>0.35), zipper=zipper>0.38,
                support=support, target=target)

checks=[]
def check(name, cond, detail=''):
    checks.append((name,bool(cond),detail))

# Source architecture contract.
texts={p:p.read_text() for p in [HDR,CPP,HPP,GLSL,ISP]}
check('gpu_two_pass_modes', 'pc.mode == 9u' in texts[GLSL] and 'pc.mode == 10u' in texts[GLSL])
check('gpu_green_preserved', 'outputRgb[rgbBase + 1u] = center.g' in texts[GLSL])
check('gpu_no_feedback_scratch', 'scratchBase = pixelIndex * 2u' in texts[GLSL] and 'phase6GuideReady' in texts[CPP])
check('mode_aware_scratch_capacity', 'requiredScratchBytes = std::max' in texts[CPP] and 'phase6ResidualChromaRequested ? phase6ScratchBytes : 0u' in texts[CPP])
check('physical_sigma_gate', 'phase6ResidualChromaRequested = request.phase6ResidualChromaEnabled' in texts[CPP] and 'request.noiseSigmaChroma > 1.0e-7f' in texts[CPP])
check('cpu_failure_parity', 'applyResidualChromaArtifactCpu' in texts[ISP] and 'phase6CpuFallbackApplied' in texts[ISP])
check('old_local_blend_suppressed', '!phase6ResidualChromaUsedForOutput' in texts[ISP])
check('telemetry_present', 'phase6IsolatedOutlierPixels' in texts[HPP] and 'phase6SaturatedDetailProtectedPixels' in texts[HPP])
check('telemetry_cpu_gpu_unified', 'phase6ExecutionBackend' in texts[ISP] and 'phase6CpuTelemetry.candidatePixels' in texts[ISP])
check('no_variance_credit', 'SELECTIVE_NONLINEAR_ARTIFACT_CORRECTION_NO_VARIANCE_CREDIT' in texts[ISP])
check('band_ownership_truth', 'phase6ResidualChromaBandOwner=LOCAL_DEMOSAIC_ARTIFACT_TOPOLOGY' in texts[ISP] and 'phase6ResidualChromaLowFrequencyCloudOwner=UNCHANGED_SEPARATE_OWNER' in texts[ISP])
check('luma_mutation_truth', 'phase6ResidualChromaLumaMutation=false' in texts[ISP])
check('no_new_full_frame_readback', 'phase6ClassifyAndStore' in texts[GLSL] and 'colorStatistics_.mapped' in texts[CPP])

# Synthetic behavior: flat/noisy chroma should not be broadly changed.
flat=classify(0.010,[0.009,0.011,0.010,0.010,0.0095,0.0105,0.010,0.010],0.20,[0.20]*8)
check('flat_no_blur', abs(flat['correction']) < 1e-5, str(flat))

# Isolated false-colour spike: no coherent neighbour support -> correct strongly.
spike=classify(0.16,[0.01,0.012,0.009,0.011,0.010,0.012,0.009,0.011],0.20,[0.20]*8)
check('isolated_spike_detected', spike['isolated'] and spike['correction'] > 0.025, str(spike))

# Zipper-like chroma oscillation with quiet green should be classified/corrected.
zipper=classify(0.085,[-0.045,-0.040,-0.042,-0.047,-0.043,-0.041,-0.044,-0.046],0.20,[0.20]*8)
check('zipper_corrected', zipper['zipper'] or zipper['isolated'], str(zipper))
check('zipper_nonzero_correction', abs(zipper['correction']) > 0.015, str(zipper))

# Coherent iso-luminant colour edge: center is supported by multiple same-colour neighbours.
edge=classify(0.14,[0.14,-0.03,0.139,-0.031,0.141,0.138,-0.03,-0.032],0.20,[0.20]*8)
check('isoluminant_edge_protected', edge['edge'] and abs(edge['correction']) < 0.012, str(edge))

# Small saturated real detail with spatial support must survive.
sat=classify(0.22,[0.219,0.221,0.02,0.018,0.218,0.222,0.021,0.019],0.18,[0.18]*8)
check('saturated_detail_protected', sat['saturated'] and abs(sat['correction']) < 0.010, str(sat))

# Physical truth absent is hard no-op by contract (source check + conceptual condition).
check('no_sigma_no_authority', 'DISABLED_NO_PHYSICAL_NOISE_TRUTH' in texts[HDR])

failed=[c for c in checks if not c[1]]
for name,ok,detail in checks:
    print(f"{'PASS' if ok else 'FAIL'} {name}" + (f" :: {detail}" if detail and not ok else ''))
print(f"PHASE6 CONTRACT: {len(checks)-len(failed)}/{len(checks)} PASS")
raise SystemExit(1 if failed else 0)
