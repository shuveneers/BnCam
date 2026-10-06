"""Collect provenance, DNG validation, publication checks, LSC and review galleries."""
from pathlib import Path
import json,csv,re,hashlib,html
import numpy as np,tifffile,rawpy
from PIL import Image,ImageDraw,JpegImagePlugin
root=Path('build/raw-truth-audit');results={};gallery=[]
def display(v):
 v=np.clip(v,0,1);return np.rint(255*np.where(v<=.0031308,12.92*v,1.055*v**(1/2.4)-.055)).astype('u1')
for folder in sorted((root/'fixtures').iterdir()):
 if not (folder/'metadata.json').exists():continue
 m=json.loads((folder/'metadata.json').read_text());id=m['id'];h,w=m['height'],m['width'];raw=np.memmap(folder/'raw.bin',dtype='<u2',mode='r',shape=(h,w))
 with tifffile.TiffFile(root/'dng'/(id+'.dng')) as tf:
  t=tf.pages[0].tags;bl=np.array(t[50714].value).reshape(-1,2);bl=bl[:,0]/bl[:,1];black=np.array(m['black_canonical'])[[3,2,1,0]]
  with rawpy.imread(str(root/'dng'/(id+'.dng'))) as rp:same=np.array_equal(raw,rp.raw_image);rbsizes=rp.sizes
  validation=dict(rawpy_tifffile_pixels_identical=bool(same),payload_sha256=hashlib.sha256(raw.tobytes()).hexdigest(),payload_matches_saved_sha256=hashlib.sha256(raw.tobytes()).hexdigest()==m['raw_sha256'],dimensions=[w,h],bits_per_sample=t[258].value,effective_container_max=int(raw.max()),white=m['white'],fraction_over_white=float((raw>m['white']).mean()),black_spatial_dng=bl.tolist(),black_spatial_camera2=black.tolist(),black_max_dng_rounding_difference=float(abs(black-bl).max()),active_area=list(t[50829].value),default_crop_origin=list(t[50719].value),default_crop_size=list(t[50720].value),cfa=list(t[33422].value),libraw_margins=[rbsizes.top_margin,rbsizes.left_margin])
 maps=np.load(folder/'maps.npy');gain=np.load(folder/'gain.npy',mmap_mode='r');n=np.memmap(folder/'normalized-off.f32',dtype='f4',mode='r',shape=(h,w));mosaic=np.stack([n[0::2,0::2],n[0::2,1::2],n[1::2,0::2],n[1::2,1::2]],-1)
 validation['g_even_minus_g_odd']=dict(mean=float((mosaic[...,1]-mosaic[...,2]).mean()),median=float(np.median(mosaic[...,1]-mosaic[...,2])),std=float((mosaic[...,1]-mosaic[...,2]).std()),interpretation='Different spatial sites; includes true scene gradients, not a black-frame offset measurement')
 lsc={}
 for region,x,y in [('center',w//2-96,h//2-96),('top_left',16,16),('top_right',w-208,16),('bottom_left',16,h-208),('bottom_right',w-208,h-208)]:
  g=gain[y:y+192,x:x+192];lsc[region]={'mean_gain':float(g.mean()),'mean_gain_squared':float(np.mean(g*g))}
 validation['lsc_regions']=lsc
 d=root/'demosaic'/id;cropdir=root/'stage-crops'/id
 if not d.exists():results[id]=validation;continue
 raw_neural=d/'neural_slot-lsc-off.rgb.f32';malvar=d/'malvar-lsc-off.rgb.f32'
 if raw_neural.exists():validation['neural_fallback_bit_identical_malvar']=hashlib.sha256(raw_neural.read_bytes()).digest()==hashlib.sha256(malvar.read_bytes()).digest()
 preservation={}
 for path in d.glob('*.rgb.f32'):
  rgb=np.memmap(path,dtype='f4',mode='r',shape=(h,w,3));expected=np.memmap(folder/'normalized-current.f32',dtype='f4',mode='r',shape=(h,w)) if 'lsc-current' in path.name else n if 'lsc-off' in path.name else n*gain
  preservation[path.stem]=max(float(np.max(abs(rgb[4+y:h-4:2,4+x:w-4:2,c]-expected[4+y:h-4:2,4+x:w-4:2]))) for y,x,c in [(0,0,2),(0,1,1),(1,0,1),(1,1,0)])
 validation['interior_sample_preservation_max_error']=preservation
 rois=json.loads((cropdir/'coordinates.json').read_text());images=[]
 for name,path in [('LibRaw AHD',root/'dng'/(id+'-libraw-ahd.jpg')),('Golden Malvar',d/'malvar-lsc-off.jpg'),('Golden AMaZE',d/'amaze-lsc-off.jpg'),('LSC DNG map',d/'malvar-lsc-on.jpg'),('LSC current clamp',d/'malvar-lsc-current.jpg')]:
  if path.exists():images.append((name,np.array(Image.open(path))))
 original=Image.open(root/'raw'/(id+'.jpg'));prod=np.array(original);validation['original_jpeg_subsampling']=JpegImagePlugin.get_sampling(original)
 if prod.shape[:2]==(w,h):prod=np.rot90(prod,1)
 images.append(('Original BnCam',prod))
 for roi,(x,y,cw,ch) in rois.items():
  canvas=Image.new('RGB',(cw*len(images),ch+26));draw=ImageDraw.Draw(canvas)
  for i,(name,v) in enumerate(images):canvas.paste(Image.fromarray(v[y:y+ch,x:x+cw]),(i*cw,26));draw.text((i*cw+4,5),name,fill='white')
  canvas.save(cropdir/(roi+'-truth-comparison.png'))
 publication={};stage=root/'stages'/id
 if (stage/'normal-replay.jpg').exists():
  jpeg=Image.open(stage/'normal-replay.jpg');j=np.rot90(np.asarray(jpeg),1);publication['sampling']=JpegImagePlugin.get_sampling(jpeg)
  for roi,(x,y,cw,ch) in rois.items():
   file=stage/'stage5'/('tone__'+roi+'.f32')
   if not file.exists():continue
   v=np.fromfile(file,dtype='f4').reshape(ch,cw,3);pre=display(v);decoded=j[y:y+ch,x:x+cw];delta=pre.astype('f4')-decoded
   publication[roi]=dict(pre_jpeg_vs_decoded_mae_8bit=float(abs(delta).mean()),maximum_8bit=float(abs(delta).max()))
   Image.fromarray(pre).save(cropdir/(roi+'-pre-jpeg.png'));Image.fromarray(decoded).save(cropdir/(roi+'-decoded-jpeg.png'))
 validation['publication']=publication;results[id]=validation
 links=''.join('<figure><figcaption>'+html.escape(roi)+'</figcaption><img loading="lazy" src="'+str((cropdir/(roi+'-truth-comparison.png')).relative_to(root)).replace('\\','/')+'"></figure>' for roi in rois)
 stage_links=''.join('<figure><figcaption>Production boundaries: '+html.escape(p.stem)+'</figcaption><img loading="lazy" src="'+str(p.relative_to(root)).replace('\\','/')+'"></figure>' for p in cropdir.glob('*-production-stages.png'))
 gallery.append('<section><h2>'+id+' · camera '+m['sensor']+' · '+m['format']+' · ISO '+str(m['iso'])+'</h2>'+links+stage_links+'</section>')
(root/'metrics/integrity-lsc-publication.json').write_text(json.dumps(results,indent=2))
(root/'index.html').write_text('''<!doctype html><meta charset="utf-8"><title>BnCam RAW truth audit</title><style>body{background:#151719;color:#eee;font:16px system-ui;margin:30px}a{color:#9ccaff}figure{margin:22px 0;overflow:auto}img{image-rendering:auto;max-width:none}figcaption{margin:8px 0;color:#bbb}button{padding:10px}section{border-top:1px solid #444;margin-top:40px}</style><h1>RAW truth audit</h1><p>Native 100% crops, identical sensor coordinates. Each row uses the same immutable RAW. Golden: no denoise or sharpening. LibRaw uses independent DNG colour/black handling; this column is not a demosaic-only A/B.</p><p><a href="report.md">Report</a> · <a href="metrics/measurements.csv">Demosaic metrics</a> · <a href="metrics/oracle-and-synthetic.json">Known-truth validation</a></p><button onclick="document.querySelectorAll('img').forEach(i=>{i.style.width=i.style.width?'':i.naturalWidth*2+'px';i.style.imageRendering=i.style.width?'pixelated':'auto'})">100% / 200% nearest-neighbour</button>'''+''.join(gallery),encoding='utf8')
print('Validated',len(results),'DNGs; gallery written')
