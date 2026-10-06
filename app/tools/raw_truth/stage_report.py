"""Create fixed-coordinate production stage contact sheets and numerical tables."""
from pathlib import Path
import argparse,json,csv
import numpy as np
from PIL import Image,ImageDraw
from scipy.ndimage import uniform_filter
p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('id');a=p.parse_args();root=a.root;id=a.id
fixture=root/'fixtures'/id;meta=json.loads((fixture/'metadata.json').read_text());rois=json.loads((fixture/'rois.json').read_text());src=root/'stages'/id;out=root/'stage-crops'/id;out.mkdir(exist_ok=True)
stages=[('demosaic',-1,'post_demosaic'),('physical',-2,'post_existing_denoise'),('WB',-3,'post_wb'),('CCM',-4,'post_ccm'),('highlight',1,'post_highlight_characterization'),('FLLF',1,'tone'),('KHRONOS',2,'tone'),('profile',4,'tone'),('gamut',5,'tone')]
def display(v):
 v=np.clip(v,0,1);return np.rint(255*np.where(v<=.0031308,12.92*v,1.055*v**(1/2.4)-.055)).astype('u1')
rows=[];manifest=[]
wb=np.array(meta['wb'])[[0,1,3]];ccm=np.array(meta['ccm']).reshape(3,3)
for roi,(x,y,w,h) in rois.items():
 canvas=Image.new('RGB',(w*5,(h+44)*2),'#191919');draw=ImageDraw.Draw(canvas);previous=None;previous_hue=None
 for i,(name,stop,filename) in enumerate(stages):
  v=np.fromfile(src/('stage'+str(stop))/(filename+'__'+roi+'.f32'),dtype='f4').reshape(h,w,3);np.save(out/(roi+'-stage-'+name+'.npy'),v)
  # Same sRGB OETF for every boundary. Sensor/WB panels are labelled uncalibrated.
  im=Image.fromarray(display(v));im.save(out/(roi+'-stage-'+name+'.png'));xx=(i%5)*w;yy=(i//5)*(h+44);canvas.paste(im,(xx,yy+44));draw.text((xx+5,yy+5),name,fill='white')
  rg=v[...,0]-v[...,1];bg=v[...,2]-v[...,1];luma=v@np.array([.2126,.7152,.0722]);hp=np.stack([rg-uniform_filter(rg,3),bg-uniform_filter(bg,3)],-1);hue=np.arctan2(bg,rg)
  grad=np.hypot(np.diff(luma,axis=1)[:-1],np.diff(luma,axis=0)[:,:-1]);edges=grad>np.percentile(grad,75)
  row=dict(id=id,roi=roi,stage=name,sigma_rg=float(rg.std()),sigma_bg=float(bg.std()),local_chroma_variance=float(np.mean(hp**2)),edge_opponent_hp_rms=float(np.sqrt(np.mean(hp[:-1,:-1][edges]**2))),luma_gradient_rms=float(np.sqrt(np.mean(grad**2))),mean_luma=float(luma.mean()),hue_change_deg=0.,maximum_delta=0.)
  if previous is not None:
   valid=(rg**2+bg**2>1e-8)&(previous[...,0]**2+previous[...,1]**2>1e-8)
   delta=(hue-previous_hue+np.pi)%(2*np.pi)-np.pi
   row['hue_change_deg']=float(np.mean(abs(delta[valid]))*180/np.pi) if valid.any() else 0.;row['maximum_delta']=float(np.max(abs(v-previous)))
  rows.append(row);previous=v;previous_hue=hue
 canvas.save(out/(roi+'-production-stages.png'));manifest.append(roi+'-production-stages.png')
with (root/'metrics'/(id+'-stages.csv')).open('w',newline='') as f:
 w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
(out/'stage-manifest.json').write_text(json.dumps(manifest,indent=2))
