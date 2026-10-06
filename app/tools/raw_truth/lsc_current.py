"""Reproduce current LSC node clamp separately from the unmodified metadata map."""
from pathlib import Path
import numpy as np,json
from scipy.ndimage import map_coordinates
r=Path('build/raw-truth-audit');out={}
for id in ['IMG_BNC_20260927_143007_948','IMG_BNC_20260927_143700_632','IMG_BNC_20260927_143709_928']:
 f=r/'fixtures'/id;m=json.loads((f/'metadata.json').read_text());h,w=m['height'],m['width'];maps=np.load(f/'maps.npy');gain=np.empty((h,w),dtype='f4')
 for y,x,c in [(0,0,3),(0,1,1),(1,0,2),(1,1,0)]:
  a=np.clip(maps[:,:,c],.25,3.5);yy,xx=np.meshgrid(np.arange(y,h,2)*(a.shape[0]-1)/(h-1),np.arange(x,w,2)*(a.shape[1]-1)/(w-1),indexing='ij');gain[y::2,x::2]=map_coordinates(a,[yy,xx],order=1,mode='nearest')
 np.save(f/'gain-current.npy',gain);n=np.memmap(f/'normalized-off.f32',dtype='f4',mode='r',shape=(h,w));np.clip(n*gain,0,4).astype('f4').tofile(f/'normalized-current.f32');full=np.load(f/'gain.npy',mmap_mode='r')
 out[id]=dict(map_nodes_above_3_5=int((maps>3.5).sum()),map_nodes_total=maps.size,pixel_gain_changed_fraction=float((abs(full-gain)>1e-6).mean()),maximum_gain_deficit=float((full-gain).max()),minimum_current_over_metadata_gain=float(np.min(gain/full)),max_normalized_signal_delta=float(np.max(abs(np.clip(n*gain,0,4)-n*full))),current_gain_range=[float(gain.min()),float(gain.max())])
(r/'metrics/lsc-clamp.json').write_text(json.dumps(out,indent=2));print(json.dumps(out,indent=2))
