"""Compare frozen-worktree/current production GPU stages of the same RAW.
Requires numpy; measurements are linear before JPEG. Hue is the explicitly
defined RGB opponent angle, not a perceptual color-accuracy reference.
"""
from pathlib import Path
import argparse, csv, json
import numpy as np

p=argparse.ArgumentParser(__doc__)
p.add_argument('--build',type=Path,default=Path('build/phase1-color'))
a=p.parse_args(); root=a.build
rois=json.loads((root/'after/rois.json').read_text())
stages=[('post_demosaic',-1,'post_demosaic'),('post_denoise',-2,'post_existing_denoise'),
        ('post_wb',-3,'post_wb'),('post_ccm',-4,'post_ccm'),
        ('post_highlight_gamut',1,'post_highlight_characterization'),
        ('post_fllf',1,'tone'),('post_khronos',2,'tone'),
        ('post_profile',4,'tone'),('post_final_gamut',5,'tone')]
ycoef=np.array([.2126,.7152,.0722])
xyz=np.array([[.4124564,.3575761,.1804375],[.2126729,.7151522,.072175],
              [.0193339,.119192,.9503041]])
def xy(v):
    q=v@xyz.T; return q[:,:2]/np.maximum(q.sum(axis=1,keepdims=True),1e-12)
def opponent(v):
    return np.stack([v[:,0]-v[:,1],v[:,2]-v[:,1]],axis=-1)
def change(v,old):
    o,n=opponent(old),opponent(v)
    co,cn=np.linalg.norm(o,axis=1),np.linalg.norm(n,axis=1)
    valid=(old@ycoef>1e-5)&(v@ycoef>1e-5)&(co>1e-5)&(cn>1e-5)
    angles=np.rad2deg(np.arctan2(n[:,1],n[:,0])-np.arctan2(o[:,1],o[:,0]))
    angles=(angles+180)%360-180
    return dict(changed_pixels=int(np.any(np.abs(v-old)>1e-6,axis=1).sum()),
        max_rgb_delta=float(np.abs(v-old).max()),
        mean_abs_xy_delta=float(np.linalg.norm(xy(v[valid])-xy(old[valid]),axis=1).mean()) if valid.any() else 0,
        mean_abs_opponent_hue_deg=float(np.abs(angles[valid]).mean()) if valid.any() else 0,
        mean_chroma_ratio=float(cn[valid].mean()/co[valid].mean()) if valid.any() else 1,
        mean_luma_delta=float(((v-old)@ycoef).mean()))

data={}; rows=[]; counts={}; transforms={}
for variant in ['before','after']:
    counts[variant]={}
    transforms[variant]=np.fromfile(root/variant/'dumps'/f'{variant}-stage-1/transform.f32',dtype='<f4').tolist()
    for stage,stop,file in stages:
        directory=root/variant/'dumps'/f'{variant}-stage-{stop}'
        counts[variant][stage]=(directory/f'{file}-counts.txt').read_text().strip()
    for patch in rois:
        previous=None
        for stage,stop,file in stages:
            v=np.fromfile(root/variant/'dumps'/f'{variant}-stage-{stop}'/f'{file}__{patch}.f32',dtype='<f4').reshape(-1,3).astype(float)
            assert np.isfinite(v).all()
            data[variant,patch,stage]=v
            row=dict(variant=variant,patch=patch,stage=stage,pixels=len(v),negative_pixels=int((v.min(axis=1)<-1e-6).sum()),
                     mean_luma=float((v@ycoef).mean()),mean_chroma=float(np.linalg.norm(opponent(v),axis=1).mean()),
                     mean_x=float(xy(v).mean(axis=0)[0]),mean_y=float(xy(v).mean(axis=0)[1]))
            if previous is not None: row.update(change(v,previous))
            rows.append(row);previous=v

comparison={}
for patch in rois:
    for stage in ['post_demosaic','post_denoise']:
        assert np.array_equal(data['before',patch,stage],data['after',patch,stage]),(patch,stage,'upstream changed')
    comparison[patch]=change(data['after',patch,'post_final_gamut'],data['before',patch,'post_final_gamut'])

# Isolate the gamut owner on the actual old post-CCM samples. This comparison
# holds WB, matrix and scene color fixed; it cannot be mistaken for a WB effect.
isolated={}
for patch in rois:
    v=data['before',patch,'post_ccm']; y=v@ycoef; minimum=v.min(axis=1)
    scale=np.where(minimum < -1e-6,np.clip(y/np.maximum(y-minimum,1e-8),0,1),1)
    mapped=y[:,None]+(v-y[:,None])*scale[:,None]
    result=change(mapped,v);result['affected_negative_pixels']=int((minimum < -1e-6).sum())
    mask=minimum < -1e-6
    result['positive_xyz_among_negative']=int((mask & ((v@xyz.T).min(axis=1)>0)).sum())
    if mask.any(): result['affected_only']=change(mapped[mask],v[mask])
    result['minimum_chroma_scale']=float(scale.min());isolated[patch]=result

keys=list(dict.fromkeys(k for row in rows for k in row))
with (root/'stage-color-metrics.csv').open('w',newline='') as f:
    writer=csv.DictWriter(f,keys);writer.writeheader();writer.writerows(rows)
result=dict(full_frame_count_columns='pixels negative(<-1e-6) nonfinite minimum',
            full_frame_counts=counts,transforms=transforms,final_comparison=comparison,
            isolated_early_gamut=isolated,upstream_patch_bit_identity=True)
(root/'color-results.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))
