"""Read-only, same-RAW quality measurements. Requires numpy and Pillow.

All large inputs/outputs belong under ignored work/. Metrics describe the declared
stage and ROI; no automatic semantic ROI selection or aesthetic quality score.
"""
import argparse
import hashlib
import itertools
import json
import pathlib
import numpy as np
from PIL import Image

MODES = ('Malvar', 'AMaZE', 'Auto Hybrid')
def sha(path):
    h=hashlib.sha256()
    with open(path,'rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def describe(a):
    a=np.asarray(a,dtype=np.float64)
    if not a.size:raise ValueError('Empty measurement')
    if not np.isfinite(a).all():raise ValueError('Nonfinite measurement input')
    return dict(n=int(a.size),mean=float(a.mean()),variance=float(a.var()),std=float(a.std()),
                min=float(a.min()),p95=float(np.quantile(a,.95)),p99=float(np.quantile(a,.99)),max=float(a.max()))

def clipping(rgb, low=0., high=1.):
    lo=rgb<=low;hi=rgb>=high
    n=int(rgb.shape[0]*rgb.shape[1])
    def counts(mask):
        c=mask.sum(axis=2)
        return dict(perChannel=[dict(count=int(mask[:,:,k].sum()),percent=float(mask[:,:,k].mean()*100)) for k in range(3)],
                    singleChannel=int((c==1).sum()),multiChannel=int((c>=2).sum()),allChannels=int((c==3).sum()))
    return dict(pixels=n,low=counts(lo),high=counts(hi),bounds=[low,high],
                interpretation='Stage range exceedance; JPEG endpoints are encoding clipping, not proof of sensor clipping.')

def region(rgb, box):
    x,y,w,h=map(int,box)
    if x<0 or y<0 or w<1 or h<1 or x+w>rgb.shape[1] or y+h>rgb.shape[0]:
        raise ValueError('ROI outside image: '+str(box))
    return rgb[y:y+h,x:x+w]

def roi_metrics(rgb, kind):
    l=rgb@np.array([.2126,.7152,.0722])
    rg=rgb[:,:,0]-rgb[:,:,1];bg=rgb[:,:,2]-rgb[:,:,1]
    result=dict(channels=[describe(rgb[:,:,i]) for i in range(3)],lumaProxy=describe(l),
                redMinusGreen=describe(rg),blueMinusGreen=describe(bg),
                chromaResidualRms=float(np.sqrt(np.mean((rg*rg+bg*bg)/2))))
    if min(l.shape)>1:
        gx=np.diff(l,axis=1);gy=np.diff(l,axis=0)
        result['gradientMagnitude']=describe(np.hypot(gx[:-1,:],gy[:,:-1]))
    if min(l.shape)>2:
        def highpass(v):
            return v[1:-1,1:-1]-(v[:-2,1:-1]+v[2:,1:-1]+v[1:-1,:-2]+v[1:-1,2:])/4
        result['highFrequencyRedMinusGreen']=describe(highpass(rg))
        result['highFrequencyBlueMinusGreen']=describe(highpass(bg))
        result['highFrequencyInterpretation']='Four-neighbor residual; false-color evidence only in a declared neutral texture ROI, not a universal noise metric.'
    result['interpretation']=('Spatial variance includes scene texture, lens shading, quantization and noise; '
                              'not a temporal sensor-noise estimate. Manual ROI kind: '+kind)
    return result

def edge_profile(rgb, spec):
    # An operator declares the straight edge and averaging direction, never an automatic guess.
    l=rgb@np.array([.2126,.7152,.0722])
    axis=spec.get('profileAxis','x')
    if axis not in ('x','y'):raise ValueError('profileAxis must be x or y')
    profile=l.mean(axis=0 if axis=='x' else 1)
    n=max(1,len(profile)//8)
    left=float(np.median(profile[:n]));right=float(np.median(profile[-n:]))
    delta=right-left
    if abs(delta)<1e-8:return dict(status='UNMEASURABLE',reason='No distinct endpoint plateaus')
    normalized=(profile-left)/delta
    def crossing(level):
        hits=np.flatnonzero((normalized[:-1]<=level)&(normalized[1:]>level))
        if len(hits)!=1:return None
        i=int(hits[0]);return float(i+(level-normalized[i])/(normalized[i+1]-normalized[i]))
    a,b=crossing(.1),crossing(.9)
    valid=a is not None and b is not None and b>=a and a>=n-1 and b<=len(profile)-n
    return dict(status='MEASURED' if valid else 'AMBIGUOUS',
        width10To90Pixels=b-a if valid else None,
        overshootFraction=float(max(0,normalized.max()-1)) if valid else None,
        undershootFraction=float(max(0,-normalized.min())) if valid else None,
        plateauSupportPixels=n,
        plateauContrast=abs(delta),profileAxis=axis,profile=profile.tolist(),
        interpretation='Relative straight-edge diagnostic; not MTF or proof of real versus sharpened detail.')

def load_rgb(root, entry):
    path=root/entry['file']
    if 'sha256' in entry and sha(path)!=entry['sha256']:raise ValueError('Output checksum mismatch')
    if path.suffix.lower()=='.npy':
        a=np.load(path,allow_pickle=False)
    elif path.suffix.lower()=='.f32':
        a=np.fromfile(path,dtype='<f4').reshape(entry['height'],entry['width'],3)
    else:
        with Image.open(path) as im:
            if im.mode!='RGB':raise ValueError('RGB input required; implicit channel conversion prohibited')
            a=np.asarray(im).astype(np.float32)/255
    if a.ndim!=3 or a.shape[2]!=3 or not np.isfinite(a).all():raise ValueError('Invalid RGB input')
    return np.asarray(a,dtype=np.float32)

def preview(a, stage):
    # Preview only. Float metrics are always calculated before this display conversion.
    if stage!='FINAL_JPEG':
        a=np.where(a<=.0031308,12.92*a,1.055*np.maximum(a,0)**(1/2.4)-.055)
    return Image.fromarray(np.round(np.clip(a,0,1)*255).astype(np.uint8))

def compare(manifest_path, output, roi_path=None):
    manifest_path=pathlib.Path(manifest_path);root=manifest_path.parent
    m=json.loads(manifest_path.read_text(encoding='utf-8-sig'));output=pathlib.Path(output)
    if output.resolve()==root.resolve():raise ValueError('Analysis output must not overwrite fixture directory')
    raw_sha=m['rawSha256']
    if len(raw_sha)!=64 or any(c not in '0123456789abcdef' for c in raw_sha):raise ValueError('Canonical RAW SHA-256 required')
    if m.get('rawFile') and sha(root/m['rawFile'])!=raw_sha:raise ValueError('RAW checksum mismatch')
    rois=json.loads(pathlib.Path(roi_path).read_text()) if roi_path else {'rois':[]}
    output.mkdir(parents=True,exist_ok=True)
    result=dict(schemaVersion=1,identity={k:m.get(k) for k in ['captureId','gitRevision','cameraId','lensRole','sceneId','repetition','frameSource','rawSha256']},
                stages={},findings=[],roiStatus='MANUAL' if rois['rois'] else 'NO SEMANTIC ROI; no noise/edge/false-color verdict',
                classification='UNKNOWN unless independent stage/scene evidence establishes origin')
    grouped={}
    if not m['outputs']:raise ValueError('At least one complete compared stage is required')
    for entry in m['outputs']:
        if entry['rawSha256']!=raw_sha:raise ValueError('Different RAW inputs cannot be treated as demosaic comparison')
        stage=entry['stage'];mode=entry['mode']
        if mode not in MODES:raise ValueError('Out-of-scope demosaic mode')
        if mode in grouped.setdefault(stage,{}):raise ValueError('Duplicate stage/mode')
        grouped[stage][mode]=entry
    for stage,entries in grouped.items():
        if set(entries)!=set(MODES):raise ValueError('Every compared stage needs all three algorithms')
        controls={e['controlsSha256'] for e in entries.values()}
        orientations={e.get('orientationDegrees',0) for e in entries.values()}
        domains={e['domain'] for e in entries.values()}
        coordinates={e.get('coordinateFrame','UNSPECIFIED') for e in entries.values()}
        if len(controls)!=1 or len(orientations)!=1 or len(domains)!=1 or len(coordinates)!=1:
            raise ValueError('Frozen controls, domain, coordinate frame or orientation differ')
        images={mode:load_rgb(root,e) for mode,e in entries.items()}
        if len({a.shape for a in images.values()})!=1:raise ValueError('Image geometry differs')
        s=dict(domain=entries[MODES[0]]['domain'],coordinateFrame=entries[MODES[0]].get('coordinateFrame','UNSPECIFIED'),
               dimensions=list(images[MODES[0]].shape[:2][::-1]),modes={},differences={},rois={})
        for mode,a in images.items():s['modes'][mode]=dict(clipping=clipping(a),channels=[describe(a[:,:,k]) for k in range(3)])
        differences={a+'__'+b:np.mean(np.abs(images[a]-images[b]),axis=2) for a,b in itertools.combinations(MODES,2)}
        heat_scale=max(float(np.quantile(d,.99)) for d in differences.values())
        for pair,d in differences.items():
            s['differences'][pair]=describe(d)
            view=np.round(np.clip(d/max(heat_scale,1e-12),0,1)*255).astype(np.uint8)
            Image.fromarray(view).save(output/(stage+'-'+pair+'-difference.png'))
        s['differenceHeatmapSharedP99Scale']=heat_scale
        relevant=[r for r in rois['rois'] if r['stage']==stage]
        # A positional crop is useful for review but carries no invented semantic classification.
        h,w=images[MODES[0]].shape[:2]
        positional=dict(id='position_center',kind='POSITION_ONLY',stage=stage,box=[max(0,w//2-128),max(0,h//2-128),min(256,w),min(256,h)])
        for spec in relevant+[positional]:
            name=spec['id']
            if not name.replace('_','').isalnum():raise ValueError('Safe ROI identifier required')
            metrics={}
            for mode,a in images.items():
                crop=region(a,spec['box']);metrics[mode]=roi_metrics(crop,spec['kind'])
                if spec['kind']=='EDGE':metrics[mode]['edge']=edge_profile(crop,spec)
                im=preview(crop,stage)
                im.save(output/(stage+'-'+name+'-'+mode+'-100.png'))
                im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(output/(stage+'-'+name+'-'+mode+'-200-nearest.png'))
            s['rois'][name]=dict(spec=spec,metrics=metrics)
        result['stages'][stage]=s
    (output/'analysis.json').write_text(json.dumps(result,indent=2,allow_nan=False),encoding='utf-8')
    return result

def hybrid(path, metadata, output, roi_path=None):
    meta=json.loads(pathlib.Path(metadata).read_text());w,h=meta['width'],meta['height']
    a=np.fromfile(path,dtype='<f4').reshape(h,w,20)
    if not np.isfinite(a).all():raise ValueError('Nonfinite Hybrid evidence')
    names=['malvarWeight','amazeWeight','structure','nyquist','noise','chromaRisk','lowSignal','nearTie']
    result=dict(scope=meta,fields={name:describe(a[:,:,i]) for i,name in enumerate(names)},correlations={},rois={})
    weight=a[:,:,1]
    for i,name in enumerate(names[2:],2):
        x=a[:,:,i]
        r=None if float(x.std())==0 or float(weight.std())==0 else float(np.corrcoef(x.ravel(),weight.ravel())[0,1])
        result['correlations'][name]=dict(pearsonWithAmazeWeight=r,interpretation='Association with model input, not proof of quality benefit or causation')
    if roi_path:
        for spec in json.loads(pathlib.Path(roi_path).read_text())['rois']:
            if spec['stage']!='HYBRID_MASK':continue
            crop=region(a,spec['box']);result['rois'][spec['id']]={name:describe(crop[:,:,i]) for i,name in enumerate(names)}
    precise=a.astype(np.float64)
    result['invariants']=dict(complementMax=float(np.abs(precise[:,:,0]+precise[:,:,1]-1).max()),
        outOfRange=int(((a[:,:,:2]<0)|(a[:,:,:2]>1)).sum()),
        oracleMax=float(np.abs(precise[:,:,0:1]*precise[:,:,8:11]+precise[:,:,1:2]*precise[:,:,11:14]-precise[:,:,14:17]).max()))
    output=pathlib.Path(output);output.mkdir(parents=True,exist_ok=True)
    Image.fromarray(np.round(weight*255).astype(np.uint8)).save(output/'amaze-weight.png')
    (output/'hybrid-analysis.json').write_text(json.dumps(result,indent=2,allow_nan=False))
    return result

def raw_analysis(path, metadata, output, roi_path=None):
    m=json.loads(pathlib.Path(metadata).read_text());w,h=m['width'],m['height']
    if sha(path)!=m['rawSha256']:raise ValueError('RAW checksum mismatch')
    a=np.fromfile(path,dtype='<u2').reshape(h,w).astype(np.float64)
    # Export explicitly carries canonical R,Gr,Gb,B developed levels.
    if m['cfa'] not in range(4):raise ValueError('Unsupported RAW CFA')
    canonical=[[0,1,2,3],[1,0,3,2],[2,3,0,1],[3,2,1,0]][m['cfa']]
    white=float(m['whiteLevel']);black=list(map(float,m['blackLevel'].split(',')))
    if len(black)!=4 or not np.isfinite([white,*black]).all() or white<=max(black):
        raise ValueError('Invalid RAW black/white calibration')
    result=dict(rawSha256=m['rawSha256'],coordinateFrame='CROPPED_SENSOR',channels={},rois={},
        interpretation='Spatial residuals are not isolated stochastic noise. A single frame cannot separate shot/read/FPN without model/flat temporal evidence.')
    for pos,ch in enumerate(canonical):
        plane=a[pos//2::2,pos%2::2];b=black[ch]
        normalized=(plane-b)/max(1,white-b)
        result['channels'][['R','Gr','Gb','B'][ch]]=dict(payload=describe(plane),normalized=describe(normalized),
            atOrBelowBlack=int((plane<=b).sum()),atOrAboveWhite=int((plane>=white).sum()),
            rowMean=describe(normalized.mean(axis=1)),columnMean=describe(normalized.mean(axis=0)))
    if roi_path:
        for spec in json.loads(pathlib.Path(roi_path).read_text())['rois']:
            if spec['stage']!='RAW':continue
            roi=region(a[:,:,None],spec['box'])[:,:,0]
            x,y,rw,rh=map(int,spec['box']);channels={}
            if min(rw,rh)<2:raise ValueError('RAW ROI needs all four CFA phases')
            for dy in range(2):
                for dx in range(2):
                    ch=canonical[((y+dy)&1)*2+((x+dx)&1)]
                    plane=roi[dy::2,dx::2];b=black[ch]
                    channels[['R','Gr','Gb','B'][ch]]=dict(payload=describe(plane),
                        normalized=describe((plane-b)/max(1,white-b)),
                        atOrBelowBlack=int((plane<=b).sum()),atOrAboveWhite=int((plane>=white).sum()))
            result['rois'][spec['id']]=dict(payload=describe(roi),kind=spec['kind'],channels=channels,
                note='CFA phase includes ROI origin; spatial variance still includes scene content, not isolated sensor noise.')
    output=pathlib.Path(output);output.mkdir(parents=True,exist_ok=True)
    (output/'raw-analysis.json').write_text(json.dumps(result,indent=2,allow_nan=False))
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['compare','hybrid','raw'])
    p.add_argument('--input',required=True);p.add_argument('--metadata');p.add_argument('--output',required=True);p.add_argument('--rois')
    a=p.parse_args()
    if a.action=='compare':compare(a.input,a.output,a.rois)
    else:
        if not a.metadata:p.error('--metadata required')
        (hybrid if a.action=='hybrid' else raw_analysis)(a.input,a.metadata,a.output,a.rois)
    print('Analysis written to',a.output)
if __name__=='__main__':main()
