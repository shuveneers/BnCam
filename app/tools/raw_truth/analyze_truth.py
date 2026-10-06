"""Measurement-only RAW truth analysis. No smoothing/correction is applied to renders.
High-pass operators below are diagnostics, never fed back to pixel production.
"""
from pathlib import Path
import argparse,json,csv,itertools,hashlib
import numpy as np
from scipy.ndimage import convolve,uniform_filter
from PIL import Image,ImageDraw,JpegImagePlugin
import rawpy,tifffile
p=argparse.ArgumentParser(__doc__);p.add_argument('root',type=Path);p.add_argument('--ids',nargs='+',required=True);a=p.parse_args();root=a.root

def display(v):
    v=np.clip(v,0,1);return np.rint(255*np.where(v<=.0031308,12.92*v,1.055*np.power(v,1/2.4)-.055)).astype('u1')
def metrics(v):
    v=np.asarray(v,dtype='f8');y=v@np.array([.2126,.7152,.0722]);rg=v[...,0]-v[...,1];bg=v[...,2]-v[...,1]
    dx=np.diff(y,axis=1);dy=np.diff(y,axis=0);grad=np.hypot(dx[:-1],dy[:,:-1]);mask=grad>max(float(np.percentile(grad,75)),1e-6)
    hp=np.stack([rg-uniform_filter(rg,size=3),bg-uniform_filter(bg,size=3)],-1)
    alt=(np.roll(hp,1,0)+np.roll(hp,-1,0)+np.roll(hp,1,1)+np.roll(hp,-1,1)-4*hp)/8
    cm=.5*(rg+bg);d=np.diff(cm,axis=1);sign=(d[:,1:]*d[:,:-1]<0)
    fft=np.fft.rfft2(y-y.mean());fy=np.fft.fftfreq(y.shape[0])[:,None];fx=np.fft.rfftfreq(y.shape[1])[None,:];power=np.abs(fft)**2
    return dict(sigma_rg=float(rg.std()),sigma_bg=float(bg.std()),local_chroma_variance=float(np.mean(hp**2)),edge_opponent_hp_rms=float(np.sqrt(np.mean(hp[:-1,:-1][mask]**2))) if mask.any() else 0,
        alternating_chroma_rms=float(np.sqrt(np.mean(alt[2:-2,2:-2]**2))),cyan_magenta_hp_rms=float(np.std(cm-uniform_filter(cm,3))),zipper_sign_reversal_fraction=float(sign.mean()),luma_gradient_rms=float(np.sqrt((np.mean(dx**2)+np.mean(dy**2))/2)),nyquist_luma_energy_fraction=float(power[(abs(fy)>=.35)|(fx>=.35)].sum()/max(power.sum(),1e-30)),mean_luma=float(y.mean()))
def bilinear(n,pattern):
    colors=np.array(pattern).reshape(2,2);out=[]
    for c in range(3):
        mask=np.tile(colors==c,((n.shape[0]+1)//2,(n.shape[1]+1)//2))[:n.shape[0],:n.shape[1]]
        k=np.array([[1,2,1],[2,4,2],[1,2,1]],dtype='f4') if c!=1 else np.array([[0,1,0],[1,4,1],[0,1,0]],dtype='f4')
        out.append(convolve(n*mask,k,mode='mirror')/np.maximum(convolve(mask.astype('f4'),k,mode='mirror'),1))
    return np.stack(out,-1)
def save_contact(panels,path,scale=1):
    w=max(im.width for _,im in panels);h=max(im.height for _,im in panels);out=Image.new('RGB',(w*len(panels),h+30),'#181818');draw=ImageDraw.Draw(out)
    for i,(name,im) in enumerate(panels):out.paste(im,(i*w,30));draw.text((i*w+5,8),name,fill='white')
    out.save(path)
rows=[]
for id in a.ids:
    f=root/'fixtures'/id;meta=json.loads((f/'metadata.json').read_text());h,w=meta['height'],meta['width'];shape=(h,w,3)
    dest=root/'demosaic'/id;cropdir=root/'stage-crops'/id;cropdir.mkdir(exist_ok=True);gold=root/'golden'/id;gold.mkdir(exist_ok=True)
    # Shared coordinates in unrotated sensor space. Include center and four corners.
    if (f/'rois.json').exists():rois=json.loads((f/'rois.json').read_text())
    else:
        rois={k:[int(x*w)//2*2,int(y*h)//2*2,192,192] for k,x,y in [('center',.45,.45),('top_left',.1,.1),('top_right',.8,.1),('bottom_left',.1,.8),('bottom_right',.8,.8)]}
    (cropdir/'coordinates.json').write_text(json.dumps(rois,indent=2))
    wb=np.array(meta['wb'],dtype='f4')[[0,1,3]];ccm=np.array(meta['ccm'],dtype='f4').reshape(3,3)
    n=np.memmap(f/'normalized-off.f32',dtype='<f4',mode='r',shape=(h,w));raw=np.memmap(f/'raw.bin',dtype='<u2',mode='r',shape=(h,w));gain=np.load(f/'gain.npy',mmap_mode='r')
    loaded={path.name.removesuffix('.rgb.f32'):np.memmap(path,dtype='<f4',mode='r',shape=shape) for path in dest.glob('*.rgb.f32')}
    # Assert the golden readback preserves measured CFA samples: no hidden NR.
    preserve={}
    for alg,v in loaded.items():
        expected=np.memmap(f/'normalized-current.f32',dtype='f4',mode='r',shape=(h,w)) if 'lsc-current' in alg else n if 'lsc-off' in alg else n*gain
        preserve[alg]=max(float(np.max(np.abs(v[y::2,x::2,c]-expected[y::2,x::2]))) for y,x,c in [(0,0,2),(0,1,1),(1,0,1),(1,1,0)])
    (root/'metrics'/(id+'-sample-preservation.json')).write_text(json.dumps(preserve,indent=2))
    for name,(x,y,cw,ch) in rois.items():
        sl=np.s_[y:y+ch,x:x+cw];panels=[]
        np.save(cropdir/(name+'-normalized-cfa.npy'),n[sl]);Image.fromarray(display(n[sl])).save(cropdir/(name+'-normalized-cfa.png'))
        for alg,v in loaded.items():
            rgb=v[sl].copy();linear=(rgb*wb)@ccm.T
            np.save(cropdir/(name+'-'+alg+'-sensor-rgb.npy'),rgb);np.save(cropdir/(name+'-'+alg+'-linear.npy'),linear)
            im=Image.fromarray(display(linear));im.save(cropdir/(name+'-'+alg+'.png'));panels.append((alg,im))
            rows.append(dict(id=id,roi=name,stage=alg,**metrics(linear)))
            rows.append(dict(id=id,roi=name,stage=alg+'-sensor-rgb',**metrics(rgb)))
            decoded=np.asarray(Image.open(dest/(alg+'.jpg')))[sl]/255.
            pre=display(linear)/255.;err=decoded-pre
            (cropdir/(name+'-'+alg+'-jpeg-error.json')).write_text(json.dumps(dict(mae=float(abs(err).mean()),maximum=float(abs(err).max()),sampling=JpegImagePlugin.get_sampling(Image.open(dest/(alg+'.jpg'))))))
        if panels:save_contact(panels,cropdir/(name+'-algorithms.png'))
    # Empirical phase test on evenly spaced unresized patches. R/B exchange is
    # not identifiable from green continuity alone; expose the degeneracy.
    parity=[]
    patterns=[[0,1,1,2],[1,0,2,1],[1,2,0,1],[2,1,1,0]]
    for pi,pattern in enumerate(patterns):
        vals=[];gs=[]
        for y in np.linspace(100,h-164,8).astype(int)//2*2:
            for x in np.linspace(100,w-164,8).astype(int)//2*2:
                q=n[y:y+64,x:x+64];rgb=bilinear(q,pattern);vals.append(metrics((rgb*wb)@ccm.T)['alternating_chroma_rms'])
                greens=[q[yy::2,xx::2] for yy,xx in itertools.product(range(2),repeat=2) if pattern[yy*2+xx]==1]
                gs.append(float(np.median(abs(greens[0]-greens[1]))))
        parity.append(dict(pattern=['RGGB','GRBG','GBRG','BGGR'][pi],green_pair_median_abs_difference=float(np.median(gs)),alternating_chroma_rms_median=float(np.median(vals))))
    (root/'metrics'/(id+'-parity.json')).write_text(json.dumps(parity,indent=2))
    # Deliberately wrong black permutations: full 24 orders measured on same ROIs.
    perm=[];spatial=np.array(meta['black_canonical'])[[3,2,1,0]]
    for order in itertools.permutations(range(4)):
        values=[]
        for name,(x,y,cw,ch) in rois.items():
            q=raw[y:y+ch,x:x+cw].astype('f4');bl=spatial[list(order)].reshape(2,2);b=np.tile(bl,(ch//2,cw//2));q=np.maximum(0,(q-b)/(meta['white']-b));v=(bilinear(q,[2,1,1,0])*wb)@ccm.T;values.append(metrics(v)['alternating_chroma_rms'])
            if order in [(0,1,2,3),(3,2,1,0),(0,2,1,3)]:Image.fromarray(display(v)).save(cropdir/(name+'-black-'+''.join(map(str,order))+'.png'))
        perm.append(dict(order=order,alternating_chroma_rms=float(np.mean(values))))
    (root/'metrics'/(id+'-black-permutations.json')).write_text(json.dumps(perm,indent=2))
    maps=np.load(f/'maps.npy');panels=[]
    for c,name in enumerate(['R','G_even','G_odd','B']):
        v=maps[:,:,c];im=Image.fromarray(np.uint8(np.clip((v-1)/max(float(maps.max()-1),1e-6),0,1)*255)).convert('RGB').resize((340,260),Image.Resampling.NEAREST);panels.append((name+f' {v.min():.3f}-{v.max():.3f}',im))
    save_contact(panels,root/'lsc'/(id+'-maps.png'))
    # Independent LibRaw AHD. Uses DNG camera characterization, not Camera2 CCM.
    # Therefore colour/LSC differences are explicitly not a demosaic-only A/B.
    external=root/'dng'/(id+'-libraw-ahd.tiff')
    if not external.exists():
        with rawpy.imread(str(root/'dng'/(id+'.dng'))) as r:
            audit=dict(raw_shape=list(r.raw_image.shape),visible_shape=list(r.raw_image_visible.shape),raw_pattern=r.raw_pattern.tolist(),black=r.black_level_per_channel,white=r.white_level,camera_whitebalance=r.camera_whitebalance,rawpy_version=rawpy.__version__,libraw_version=rawpy.libraw_version)
            output=r.postprocess(demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,use_camera_wb=True,no_auto_bright=True,output_bps=16,user_flip=0,gamma=(1,1),median_filter_passes=0,fbdd_noise_reduction=rawpy.FBDDNoiseReductionMode.Off)
            tifffile.imwrite(external,output,photometric='rgb');(root/'metadata'/(id+'-libraw.json')).write_text(json.dumps(audit,indent=2))
        Image.fromarray(display(output.astype('f4')/65535)).save(root/'dng'/(id+'-libraw-ahd.jpg'),quality=100,subsampling=0)
    print(id,'analysed',preserve,flush=True)
with (root/'metrics/measurements.csv').open('w',newline='') as out:
    writer=csv.DictWriter(out,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
