"""Independent MHC convolution oracle and known-truth diagnostic controls.
Kernels: Malvar, He & Cutler, ICASSP 2004, Fig. 2.
https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/Demosaicing_ICASSP04.pdf
"""
from pathlib import Path
import numpy as np,json
from scipy.ndimage import convolve
from PIL import Image,ImageDraw
root=Path('build/raw-truth-audit')
def mhc(n):
    g=np.array([[0,0,-1,0,0],[0,0,2,0,0],[-1,2,4,2,-1],[0,0,2,0,0],[0,0,-1,0,0]],dtype='f4')/8
    h=np.array([[0,0,.5,0,0],[0,-1,0,-1,0],[-1,4,5,4,-1],[0,-1,0,-1,0],[0,0,.5,0,0]],dtype='f4')/8
    d=np.array([[0,0,-1.5,0,0],[0,2,0,2,0],[-1.5,0,6,0,-1.5],[0,2,0,2,0],[0,0,-1.5,0,0]],dtype='f4')/8
    gg=convolve(n,g,mode='nearest');hh=convolve(n,h,mode='nearest');vv=convolve(n,h.T,mode='nearest');dd=convolve(n,d,mode='nearest');rgb=np.stack([n]*3,-1)
    # BGGR: B at even/even, R at odd/odd, green-even at even/odd.
    rgb[0::2,0::2,0]=dd[0::2,0::2];rgb[0::2,0::2,1]=gg[0::2,0::2]
    rgb[1::2,1::2,2]=dd[1::2,1::2];rgb[1::2,1::2,1]=gg[1::2,1::2]
    rgb[0::2,1::2,0]=vv[0::2,1::2];rgb[0::2,1::2,2]=hh[0::2,1::2]
    rgb[1::2,0::2,0]=hh[1::2,0::2];rgb[1::2,0::2,2]=vv[1::2,0::2]
    return rgb
results={}
for id in ['IMG_BNC_20260927_143700_632','IMG_BNC_20260927_143709_928']:
    f=root/'fixtures'/id;m=json.loads((f/'metadata.json').read_text());h,w=m['height'],m['width'];n=np.memmap(f/'normalized-off.f32',dtype='f4',mode='r',shape=(h,w));v=np.memmap(root/'demosaic'/id/'malvar-lsc-off.rgb.f32',dtype='f4',mode='r',shape=(h,w,3))
    errs=[]
    for y in [100,h//2,h-300]:
        for x in [100,w//2,w-300]:
            x=x//2*2;y=y//2*2;q=mhc(n[y:y+256,x:x+256]);errs.append(float(np.max(abs(q[4:-4,4:-4]-v[y+4:y+252,x+4:x+252]))))
    results[id]={'MHC_oracle_max_abs_error':max(errs),'patch_count':len(errs)}
f=root/'fixtures/synthetic-neutral';truth=np.fromfile(f/'ground-truth.rgb.f32',dtype='f4').reshape(512,512,3);checks={};panels=[]
for path in (root/'demosaic/synthetic-neutral').glob('*-lsc-off.rgb.f32'):
    v=np.fromfile(path,dtype='f4').reshape(512,512,3);checks[path.stem]={}
    for name,top,bottom in [('chirp',8,120),('2px_lines',136,248),('1px_diagonal',264,376),('hard_diagonal',392,504)]:
        q=v[top:bottom,8:-8];t=truth[top:bottom,8:-8];lum=q@np.array([.2126,.7152,.0722]);ref=t[...,1];op=np.stack([q[...,0]-q[...,1],q[...,2]-q[...,1]],-1)
        checks[path.stem][name]=dict(false_color_rms=float(np.sqrt(np.mean(op**2))),rgb_rmse=float(np.sqrt(np.mean((q-t)**2))),luma_rmse=float(np.sqrt(np.mean((lum-ref)**2))),luma_contrast_ratio=float(lum.std()/max(ref.std(),1e-12)),luma_gradient_ratio=float(np.std(np.diff(lum,axis=1))/max(np.std(np.diff(ref,axis=1)),1e-12)))
    panels.append((path.name.split('-lsc')[0],np.uint8(np.clip(v,0,1)*255)))
canvas=Image.new('RGB',(512*(len(panels)+1),540));d=ImageDraw.Draw(canvas)
for i,(name,img) in enumerate([('known neutral truth',np.uint8(truth*255))]+panels):canvas.paste(Image.fromarray(img),(512*i,28));d.text((512*i+5,5),name,fill='white')
canvas.save(root/'stage-crops/synthetic-known-truth.png');results['synthetic']=checks
(root/'metrics/oracle-and-synthetic.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2))
