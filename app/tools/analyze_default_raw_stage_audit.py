"""Measure paired detrended opponent residuals from native stage-audit ROI dumps.
Install numpy separately; no image filtering or modifications are performed.
Usage: python analyze_default_raw_stage_audit.py PATH_TO_AUDIT [--tile 16]
"""
import argparse,csv,json
from pathlib import Path
import numpy as np
parser=argparse.ArgumentParser(__doc__)
parser.add_argument('audit',type=Path)
parser.add_argument('--tile',type=int,default=16)
args=parser.parse_args()
rois=json.loads((args.audit/'rois.json').read_text())
dumps=args.audit/'dumps'
def read(variant,stage,name,patch):
    w,h=rois[patch][2:]
    a=np.fromfile(dumps/(variant+'-stage-'+str(stage))/(name+'__'+patch+'.f32'),dtype='<f4').reshape(h,w,3)
    assert np.isfinite(a).all()
    return a.astype(np.float64)
def detrend(rgb):
    # Same non-overlapping supports at every boundary. A 2-D quadratic removes
    # slowly varying illumination/colour; only fit coefficients, never blur RGB.
    chroma=np.stack([rgb[...,0]-rgb[...,1],rgb[...,2]-rgb[...,1]],-1)
    residuals=[];dof=0
    h,w=chroma.shape[:2]
    for y in range(0,h,args.tile):
        for x in range(0,w,args.tile):
            tile=chroma[y:min(y+args.tile,h),x:min(x+args.tile,w)]
            th,tw=tile.shape[:2]
            if min(th,tw)<4:continue
            yy,xx=np.mgrid[:th,:tw].astype(float);xx=(xx-(tw-1)/2)/tw;yy=(yy-(th-1)/2)/th
            design=np.column_stack([np.ones(th*tw),xx.ravel(),yy.ravel(),(xx*xx).ravel(),(xx*yy).ravel(),(yy*yy).ravel()])
            values=tile.reshape(-1,2)
            residuals.append(values-design@np.linalg.lstsq(design,values,rcond=None)[0]);dof+=len(values)-6
    residual=np.concatenate(residuals)
    cov=residual.T@residual/dof
    return cov,chroma.mean((0,1)),len(residual),dof
rows=[];identity=[]
old_transform=np.fromfile(dumps/'old-stage--1'/'transform.f32',dtype='<f4')
new_transform=np.fromfile(dumps/'new-stage--1'/'transform.f32',dtype='<f4')
assert np.array_equal(old_transform,new_transform), 'Paired audit must not change WB or CCM'
for variant in ['old','new']:
    trans=np.fromfile(dumps/(variant+'-stage--1')/'transform.f32',dtype='<f4').astype(float)
    for patch in rois:
        demosaic=read(variant,-1,'post_demosaic',patch)
        denoised=read(variant,-2,'post_existing_denoise',patch)
        if variant == 'new':
            assert np.array_equal(demosaic,read('old',-1,'post_demosaic',patch)), 'Demosaic changed'
            assert np.array_equal(denoised,read('old',-2,'post_existing_denoise',patch)), 'Existing denoise changed'
        wb=denoised*trans[:3]
        ccm=wb@trans[3:].reshape(3,3).T
        values=[('post_demosaic',demosaic),('post_existing_denoise',denoised),('post_WB',wb),('post_CCM',ccm),
                ('post_highlight_characterization',read(variant,5,'post_highlight_characterization',patch)),
                ('post_FLLF',read(variant,1,'tone',patch)),('post_KHRONOS',read(variant,2,'tone',patch)),
                ('post_automatic_color',read(variant,4,'tone',patch)),('post_final_gamut',read(variant,5,'tone',patch))]
        previous=None
        for name,rgb in values:
            cov,mean,n,dof=detrend(rgb);y=float((rgb@np.array([.2126,.7152,.0722])).mean());variance=float(np.trace(cov));sigma=np.sqrt(np.maximum(cov.diagonal(),0))
            row=dict(variant=variant,patch=patch,stage=name,samples=n,dof=dof,
                mean_RG=float(mean[0]),mean_BG=float(mean[1]),mean_Y=y,
                sigma_RG=float(sigma[0]),sigma_BG=float(sigma[1]),cov_RG_BG=float(cov[0,1]),
                chroma_residual_variance=variance,relative_chroma_variance=variance/max(y*y,1e-18))
            row.update(gain_sigma_RG=1.,gain_sigma_BG=1.,gain_chroma_variance=1.,gain_Y=1.,gain_variance_over_Y_squared=1.)
            if previous:
                row['gain_sigma_RG']=row['sigma_RG']/max(previous['sigma_RG'],1e-18)
                row['gain_sigma_BG']=row['sigma_BG']/max(previous['sigma_BG'],1e-18)
                row['gain_chroma_variance']=variance/max(previous['chroma_residual_variance'],1e-18)
                row['gain_Y']=y/max(previous['mean_Y'],1e-18)
                row['gain_variance_over_Y_squared']=row['gain_chroma_variance']/max(row['gain_Y']**2,1e-18)
            rows.append(row);previous=row
        # Profile tonal LUT also lies between these two boundaries; with neutral controls
        # it is identity apart from interpolation roundoff. Final gamut is checked exactly.
        auto=values[-2][1];tone=values[-3][1];final=values[-1][1]
        # Check whether FLLF introduces any channel-dependent gain beyond its
        # common luminance scale. Variance alone mixes that scale with texture.
        pre_fllf=values[4][1];post_fllf=values[5][1]
        luma=np.array([.2126,.7152,.0722])
        common_scale=(post_fllf@luma)/np.maximum(pre_fllf@luma,1e-15)
        common_error=float(np.abs(post_fllf-pre_fllf*common_scale[...,None]).max())
        def opponent_norm(rgb):
            return np.hypot(rgb[...,0]-rgb[...,1],rgb[...,2]-rgb[...,1])
        before_norm=opponent_norm(post_fllf);valid=before_norm>1e-5
        khronos_gain=opponent_norm(tone)[valid]/before_norm[valid]
        identity.append(dict(variant=variant,patch=patch,max_auto_delta=float(np.abs(auto-tone).max()),
                             max_gamut_delta=float(np.abs(final-auto).max()),
                             max_fllf_common_scale_rgb_error=common_error,
                             max_khronos_pointwise_chroma_gain=float(khronos_gain.max())))
base=args.audit/('stage-residuals-tile'+str(args.tile))
with base.with_suffix('.csv').open('w',newline='') as f:
    writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
base.with_suffix('.json').write_text(json.dumps({'tile':args.tile,'rows':rows,'pointwise_identity':identity},indent=2))
print('variant patch stage sigmaRG sigmaBG covariance traceVariance varianceGain normalizedGain')
for r in rows:
 print(r['variant'],r['patch'],r['stage'],*[f'{r[k]:.6g}' for k in ['sigma_RG','sigma_BG','cov_RG_BG','chroma_residual_variance','gain_chroma_variance','gain_variance_over_Y_squared']])
print(json.dumps(identity,indent=2))
