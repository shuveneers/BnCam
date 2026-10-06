"""Exact requested v2 eight-term loss, applied after deterministic RGB merge."""
import torch
from torch.nn import functional as F
from ..losses import rgb_from_missing
from .model import missing_rgb

WEIGHTS=dict(missing_charbonnier=1.,green_missing=.30,opponent_value=.20,opponent_gradient=.20,
             luma_gradient=.20,laplacian=.08,zero_chroma_falsecolor=.10,chromatic_edge=.10)
LUMA=(.2126,.7152,.0722)


def opp(rgb):return torch.stack((rgb[:,0]-rgb[:,1],rgb[:,2]-rgb[:,1]),1)
def diffs(v):return v[...,1:]-v[...,:-1],v[...,1:,:]-v[...,:-1,:]
def average(v,mask):return (v*mask).sum()/mask.expand_as(v).sum().clamp_min(1)


def truth_masks(opponents):
    """Disjoint neutral/edge masks with an excluded uncertain/constant-color band.

    A neutral pixel needs BOTH local opponent magnitude <=1e-4 and local gradient
    <=1e-4 (normalized units). A chromatic edge requires truth gradient >=.002.
    Masks are decided from truth only, never from a prediction or noise estimate.
    """
    dx,dy=diffs(opponents)
    g=torch.maximum(F.pad(dx.abs().amax(1,keepdim=True),(0,1,0,0)),F.pad(dy.abs().amax(1,keepdim=True),(0,0,0,1)))
    local_gradient=F.max_pool2d(g,3,stride=1,padding=1)
    local_magnitude=F.max_pool2d(opponents.abs().amax(1,keepdim=True),3,stride=1,padding=1)
    neutral=(local_magnitude<=1e-4)&(local_gradient<=1e-4)
    chromatic=(local_gradient>=.002)&(~neutral)
    return neutral,chromatic


def missing_targets(rgb):
    return torch.stack((rgb[:,1,0::2,0::2],rgb[:,2,0::2,0::2],
        rgb[:,0,0::2,1::2],rgb[:,2,0::2,1::2],rgb[:,0,1::2,0::2],rgb[:,2,1::2,0::2],
        rgb[:,0,1::2,1::2],rgb[:,1,1::2,1::2]),1)


def reconstruction_loss(packed,outputs,truth,structured=True):
    predicted_missing=missing_rgb(packed,outputs) if structured else outputs.float()
    rgb=rgb_from_missing(packed.float(),predicted_missing)
    target_missing=missing_targets(truth)
    error=predicted_missing-target_missing
    predicted_opp,target_opp=opp(rgb),opp(truth)
    opponent_error=predicted_opp-target_opp
    # RG at G1/G2/B; BG at R/G1/G2: exactly the six missing opponent positions.
    missing_opponent=torch.stack((opponent_error[:,0,0::2,1::2],opponent_error[:,0,1::2,0::2],
        opponent_error[:,0,1::2,1::2],opponent_error[:,1,0::2,0::2],
        opponent_error[:,1,0::2,1::2],opponent_error[:,1,1::2,0::2]),1)
    pdx,pdy=diffs(predicted_opp);tdx,tdy=diffs(target_opp)
    odx,ody=pdx-tdx,pdy-tdy
    luma_weights=rgb.new_tensor(LUMA)[None,:,None,None]
    luma_error=((rgb-truth)*luma_weights).sum(1,keepdim=True)
    ldx,ldy=diffs(luma_error)
    e=rgb-truth
    lap=-4*e[:,:,1:-1,1:-1]+e[:,:,:-2,1:-1]+e[:,:,2:,1:-1]+e[:,:,1:-1,:-2]+e[:,:,1:-1,2:]
    neutral,chromatic=truth_masks(target_opp)
    nx=neutral[...,1:]&neutral[...,:-1];ny=neutral[...,1:,:]&neutral[...,:-1,:]
    cx=chromatic[...,1:]|chromatic[...,:-1];cy=chromatic[...,1:,:]|chromatic[...,:-1,:]
    terms=dict(missing_charbonnier=(error.square()+1e-8).sqrt().mean(),
        green_missing=error[:,[0,7]].abs().mean(),opponent_value=missing_opponent.abs().mean(),
        opponent_gradient=.5*(odx.abs().mean()+ody.abs().mean()),
        luma_gradient=.5*(ldx.abs().mean()+ldy.abs().mean()),laplacian=lap.abs().mean(),
        zero_chroma_falsecolor=.5*(average(pdx.square(),nx)+average(pdy.square(),ny)),
        chromatic_edge=.5*(average(odx.abs(),cx)+average(ody.abs(),cy)))
    return sum(WEIGHTS[k]*v for k,v in terms.items()),terms,rgb


@torch.no_grad()
def diagnostics(packed,outputs,truth,structured=True):
    loss,terms,rgb=reconstruction_loss(packed,outputs,truth,structured)
    p,t=opp(rgb),opp(truth);dx,dy=diffs(p);tx,ty=diffs(t)
    neutral,chromatic=truth_masks(t)
    y=lambda v:(v*v.new_tensor(LUMA)[None,:,None,None]).sum(1,keepdim=True)
    gradient_rms=lambda v:torch.sqrt(sum(z.square().mean() for z in diffs(v))*.5)
    ratio=lambda a,b:float(a/b) if float(b)>1e-7 else None
    missing=missing_rgb(packed,outputs) if structured else outputs.float()
    result=dict(total_loss=float(loss),losses={k:float(v) for k,v in terms.items()},
        missing_rgb_l1=float((missing-missing_targets(truth)).abs().mean()),
        missing_g_l1=float(terms["green_missing"]),rg_rms=float((p[:,0]-t[:,0]).square().mean().sqrt()),
        bg_rms=float((p[:,1]-t[:,1]).square().mean().sqrt()),
        luma_gradient_ratio=ratio(gradient_rms(y(rgb)),gradient_rms(y(truth))),
        opponent_gradient_ratio=ratio(gradient_rms(p),gradient_rms(t)),
        neutral_false_color_energy=float(average(p.square(),neutral)),chromatic_edge_retention={})
    cx=chromatic[...,1:]|chromatic[...,:-1];cy=chromatic[...,1:,:]|chromatic[...,:-1,:]
    for c,name in enumerate(("RG","BG")):
        a=(average(dx[:,c:c+1].square(),cx)+average(dy[:,c:c+1].square(),cy)).sqrt()
        b=(average(tx[:,c:c+1].square(),cx)+average(ty[:,c:c+1].square(),cy)).sqrt()
        result["chromatic_edge_retention"][name]=ratio(a,b)
    # Diagnostic only: preserve the exact requested eight-term objective.
    result['opponent_gradient_error_rms']=float(torch.sqrt(((dx-tx).square().mean()+(dy-ty).square().mean())*.5))
    def sign_changes(v):
        a,b=v[...,1:],v[...,:-1];c,d=v[...,1:,:],v[...,:-1,:]
        return ((a*b<0)&(a.abs()>2.5e-6)&(b.abs()>2.5e-6),
                (c*d<0)&(c.abs()>2.5e-6)&(d.abs()>2.5e-6))
    pc,tc=sign_changes(p),sign_changes(t)
    result['alternating_opponent_sign_change_fraction']=float(sum(z.float().mean() for z in pc)*.5)
    result['alternating_opponent_sign_change_error']=float(sum((a!=b).float().mean() for a,b in zip(pc,tc))*.5)
    return result
