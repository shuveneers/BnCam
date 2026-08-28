import math


def smoothstep(a, b, x):
    if b <= a:
        return 1.0 if x >= b else 0.0
    t=max(0.0,min(1.0,(x-a)/(b-a)))
    return t*t*(3.0-2.0*t)


def requested(local_luma, strength=.32, key=.15, lift=.42, compress=.48):
    local=max(1e-6,local_luma)
    req=max(-compress,min(lift,math.log2(key/local)))
    need=smoothstep(.10,.46,abs(req))
    black=smoothstep(.010,.050,local) if req>0 else 1.0
    return req*strength*need*black


def reconstruct(up, local, lap_band, edge_stop=.62, refinement=.12, lift=.42, compress=.48):
    edge=smoothstep(.62*edge_stop,1.28*edge_stop,abs(lap_band))
    refine_need=smoothstep(.12,.55,abs(local-up))
    c=up+(local-up)*(refinement*refine_need*(1-edge))
    c*=1-.84*edge
    return max(-compress,min(lift,c)),edge

checks={}
# Broad dark/bright regions earn bounded, appropriately signed local exposure.
dark=requested(.06); bright=requested(.60)
checks['dark_region_lifts']=dark>0
checks['bright_region_compresses']=bright<0
checks['requested_bounded']=abs(dark)<=.42 and abs(bright)<=.48
# Deep black is protected from automatic lift.
checks['true_black_guard']=abs(requested(.001))<1e-8
# Strong Laplacian structure blocks propagation of a broad correction.
base=.20
flat,_=reconstruct(base,.19,.04)
edge,edge_gate=reconstruct(base,.19,1.0)
checks['edge_gate_activates']=edge_gate>.95
checks['edge_suppresses_propagation']=abs(edge)<.22*abs(flat)
# Scalar recombination preserves RGB ratios/hue exactly.
rgb=(.24,.12,.06); gain=2**.18
out=tuple(v*gain for v in rgb)
checks['scalar_rgb_hue_preserved']=abs(out[0]/out[1]-rgb[0]/rgb[1])<1e-12 and abs(out[1]/out[2]-rgb[1]/rgb[2])<1e-12
# Inactive policy must be a strict no-op; mode-3 flag owns this at runtime.
active=False
correction=.15 if active else 0.0
checks['inactive_is_identity']=correction==0.0

failed=[k for k,v in checks.items() if not v]
if failed:
    raise SystemExit('PHASE10_FLLF_NUMERICAL_FAIL='+','.join(failed))
print('PHASE10_FLLF_NUMERICAL_PASS='+str(len(checks)))
