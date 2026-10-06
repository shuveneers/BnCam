"""Analytical, scene-linear RGB truth. Never reconstruction output or denoise pairs.

Splits have distinct seed namespaces AND disjoint quantized frequency, orientation,
phase and line-width bins. Existing Phase-2 fixture functions are not imported.
Anti-aliased edges are defined as analytic unit-pixel ramp coverage in the truth.
"""
import hashlib
import numpy as np

KINDS=("thin_lines","slanted_edge","grating","chirp","checker_below_nyquist",
       "periodic_texture","weave","cross_hatching","curved_lines","circles_arcs","branches",
       "isoluminant_edge","mixed_color_edge")
SPLIT_IDS={"train":(0,1,2),"validation":(3,),"test":(4,)}
LUMA=(.2126,.7152,.0722)
MIN_ISOLUMINANT_CHROMATIC_DISTANCE=.20


def rng_for(split,index,seed=172901):
    if split not in SPLIT_IDS:raise ValueError("unknown procedural split")
    key=f"bnc-v2-procedural:{seed}:{split}:{index}".encode()
    return np.random.default_rng(int.from_bytes(hashlib.sha256(key).digest()[:8],"little"))


def parameters(rng,split):
    residues=SPLIT_IDS[split]
    def draw_bin(lo,hi):
        values=np.arange(lo,hi,dtype=np.int32)
        values=values[np.isin(values%5,residues)]
        return int(rng.choice(values))
    return dict(frequency=draw_bin(65,683)/2048,
                angle=(draw_bin(0,720)+.5)*np.pi/720,
                phase=2*np.pi*(draw_bin(0,1024)+.5)/1024,
                width=1.2+draw_bin(0,680)*.01,
                chirp_end=draw_bin(65,860)/2048,
                texture_other_frequency=draw_bin(65,683)/2048,
                # Even after rotation the product's projected frequencies stay <.25.
                checker_frequency=draw_bin(65,350)/2048,
                checker_other_frequency=draw_bin(65,350)/2048)


def isoluminant_pair(rng):
    """Construct two distinct in-gamut colors on one exact linear-Y plane.

    The target stays away from gamut corners. R/B are chosen by a continuous
    random angle and G is solved analytically from the nullspace of Rec.709 Y.
    Antipodal points yield a guaranteed separation; no rejection or fallback.
    """
    target=float(rng.uniform(.23,.77))
    angle=float(rng.uniform(0,2*np.pi))
    red,blue=np.cos(angle),np.sin(angle)
    green=-(LUMA[0]*red+LUMA[2]*blue)/LUMA[1]
    direction=np.array((red,green,blue),dtype=np.float64)
    amplitude=min(target,1-target)*float(rng.uniform(.55,.95))/np.max(np.abs(direction))
    center=np.full(3,target,dtype=np.float64)
    left=(center+amplitude*direction).astype(np.float32)
    right=(center-amplitude*direction).astype(np.float32)
    return left,right


def scene(index,split="train",size=256,seed=172901,*,_kind_for_test=None):
    rng=rng_for(split,index,seed);p=parameters(rng,split)
    kind=KINDS[int(rng.integers(len(KINDS)))]
    if _kind_for_test is not None:
        if _kind_for_test not in KINDS:raise ValueError("unknown test scene kind")
        kind=_kind_for_test
    y,x=np.indices((size,size),dtype=np.float32)
    x=x-size/2-rng.uniform(-32,32);y=y-size/2-rng.uniform(-32,32)
    u=x*np.cos(p["angle"])+y*np.sin(p["angle"])
    v=-x*np.sin(p["angle"])+y*np.cos(p["angle"])
    phase=p["phase"];f=p["frequency"];width=p["width"]
    edge=lambda distance: np.clip(.5+distance,0,1)
    distance=lambda a,period: abs((a+period/2)%period-period/2)
    if kind=="thin_lines":
        signal=edge(width/2-distance(u+phase, max(2*width+3,1/f)))
    elif kind in ("slanted_edge","isoluminant_edge","mixed_color_edge"):
        signal=edge(u)
    elif kind=="grating":signal=.5+.5*np.sin(2*np.pi*f*u+phase)
    elif kind=="chirp":
        signal=.5+.5*np.sin(2*np.pi*((f+p["chirp_end"])*.5*u+(p["chirp_end"]-f)*u*u/(3.2*size))+phase)
    elif kind=="checker_below_nyquist":
        # Smooth periodic product with frequency strictly below exact CFA ambiguity.
        signal=.5+.5*np.sin(2*np.pi*p["checker_frequency"]*u+phase)*np.sin(2*np.pi*p["checker_other_frequency"]*v-phase)
    elif kind=="periodic_texture":signal=.5+.25*np.cos(2*np.pi*f*u+phase)+.25*np.cos(2*np.pi*p["texture_other_frequency"]*v-phase)
    elif kind=="weave":signal=.5+.35*np.sin(2*np.pi*f*u+phase)*np.cos(2*np.pi*p["texture_other_frequency"]*v-phase)+.15*np.cos(2*np.pi*f*(u+v))
    elif kind=="cross_hatching":signal=np.maximum(edge(width/2-distance(u+phase,1/f)),edge(width/2-distance(v-phase,1/f)))
    elif kind=="curved_lines":signal=edge(width/2-distance(u+8*np.sin(v/(17+width)+phase),max(1/f,2*width+2)))
    elif kind=="circles_arcs":signal=edge(width/2-distance(np.sqrt(x*x+y*y)+phase,max(1/f,2*width+3)))
    else:
        signal=np.zeros_like(x)
        segments=[(0.,float(size)/2, -8.,-float(size)/3, width)]
        for generation in range(3):
            for ax,ay,bx,by,w in list(segments[-(2**generation):]):
                for sign in (-1,1):
                    t=rng.uniform(.2,.8);cx=ax+(bx-ax)*t;cy=ay+(by-ay)*t
                    segments.append((cx,cy,cx+sign*rng.uniform(15,60),cy-rng.uniform(15,60),max(1.2,w*.65)))
        for ax,ay,bx,by,w in segments:
            dx,dy=bx-ax,by-ay;t=np.clip(((x-ax)*dx+(y-ay)*dy)/(dx*dx+dy*dy),0,1)
            d=np.hypot(x-(ax+t*dx),y-(ay+t*dy));signal=np.maximum(signal,edge(w/2-d))
    neutral=kind not in ("isoluminant_edge","mixed_color_edge") and rng.random()<.35
    left,right=rng.uniform(.05,.9,(2,3)).astype("f4")
    if neutral:
        left[:]=rng.uniform(.03,.4);right[:]=rng.uniform(.5,.95)
    elif kind=="isoluminant_edge":
        left,right=isoluminant_pair(rng)
    exposure=float(np.exp(rng.uniform(np.log(.125),np.log(4))))
    rgb=((1-signal[...,None])*left+signal[...,None]*right)*exposure
    assert rgb.min()>=0 and rgb.max()<=4
    meta=dict(kind=kind,split=split,index=index,neutral=neutral,exposure=exposure,**p)
    if kind=="isoluminant_edge":
        meta.update(leftColor=left.tolist(),rightColor=right.tolist(),
            linearLuminance=float(left@np.array(LUMA)),
            chromaticDistance=float(np.linalg.norm(left-right)))
    return rgb.astype("f4"),meta
