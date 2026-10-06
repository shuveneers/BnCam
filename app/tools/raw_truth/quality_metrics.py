"""Diagnostic operators only. No measurement is fed back into reconstruction."""
import numpy as np
from scipy.ndimage import uniform_filter

def measure(v, truth=None):
    v=np.asarray(v,dtype='f8'); y=v@np.array([.2126,.7152,.0722])
    rg=v[...,0]-v[...,1]; bg=v[...,2]-v[...,1]
    hp=np.stack([rg-uniform_filter(rg,3),bg-uniform_filter(bg,3)],-1)
    alt=(np.roll(hp,1,0)+np.roll(hp,-1,0)+np.roll(hp,1,1)+np.roll(hp,-1,1)-4*hp)/8
    dx=np.diff(y,axis=1); dy=np.diff(y,axis=0)
    edges=np.hypot(dx[:-1],dy[:,:-1]); mask=edges>max(np.percentile(edges,75),1.e-8)
    cm=.5*(rg+bg); delta=np.diff(cm,axis=1)
    power=abs(np.fft.rfft2(y-y.mean()))**2
    fy=np.fft.fftfreq(y.shape[0])[:,None]; fx=np.fft.rfftfreq(y.shape[1])[None,:]
    out=dict(alternating_chroma_rms=float(np.sqrt(np.mean(alt[2:-2,2:-2]**2))),
        opponent_edge_residual=float(np.sqrt(np.mean(hp[:-1,:-1][mask]**2))) if mask.any() else 0,
        cyan_magenta_hp_rms=float(np.std(cm-uniform_filter(cm,3))),
        sign_reversal_fraction=float(np.mean((delta[:,1:]*delta[:,:-1]<0)&(abs(delta[:,1:])>1.e-7)&(abs(delta[:,:-1])>1.e-7))),
        luma_gradient_rms=float(np.sqrt((np.mean(dx**2)+np.mean(dy**2))/2)),
        high_frequency_energy=float(power[(abs(fy)>=.35)|(fx>=.35)].sum()/max(power.sum(),1.e-30)),
        opponent_rms=float(np.sqrt(np.mean(np.stack([rg,bg],-1)**2))),
        local_chroma_variance=float(np.mean(hp**2)),mean_rgb=v.mean(axis=(0,1)).tolist())
    if truth is not None:
        ref=truth[...,1]; refgrad=np.sqrt((np.mean(np.diff(ref,axis=1)**2)+np.mean(np.diff(ref,axis=0)**2))/2)
        out.update(luma_retention=float(y.std()/max(ref.std(),1.e-12)),
            luma_gradient_retention=float(out['luma_gradient_rms']/max(refgrad,1.e-12)),
            luma_rmse=float(np.sqrt(np.mean((y-ref)**2))))
    return out
