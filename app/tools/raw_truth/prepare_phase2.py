"""Create independent Phase 2 fixtures without modifying the Phase 1 evidence."""
from pathlib import Path
import json, shutil, numpy as np

r=Path('build/phase2-raw-quality'); old=Path('build/raw-truth-audit')
ids=['IMG_BNC_20260927_143700_632','IMG_BNC_20260927_143709_928','IMG_BNC_20260927_143007_948']
for d in ['fixtures','demosaic','metrics','stage-crops','golden','lsc']:
    (r/d).mkdir(parents=True,exist_ok=True)
for id in ids:
    f=r/'fixtures'/id; f.mkdir(exist_ok=True)
    for name in ['raw.bin','fixture.txt','capture-metadata.txt','metadata.json','golden.txt','maps.npy','rois.json','normalized-off.f32']:
        shutil.copy2(old/'fixtures'/id/name,f/name)
    m=json.loads((f/'metadata.json').read_text()); h,w=m['height'],m['width']
    n=np.memmap(f/'normalized-off.f32',dtype='f4',mode='r',shape=(h,w))
    g=np.load(old/'fixtures'/id/'gain.npy',mmap_mode='r')
    # Preserve the existing scene-linear range; only the gain cap changes.
    np.clip(n*g,0,4).astype('f4').tofile(f/'normalized-on.f32')
    maps=np.load(f/'maps.npy')
    (f/'gain-test.txt').write_text(f'{maps.shape[0]} {maps.shape[1]}\n'+' '.join(map(str,maps.flatten()))+f'\n{w} {h}\n')
    rois=json.loads((f/'rois.json').read_text())
    rois.update(center=[w//2//2*2,h//2//2*2,192,192],top_left=[8,8,192,192],top_right=[w-200,8,192,192],bottom_left=[8,h-200,192,192],bottom_right=[w-200,h-200,192,192])
    (f/'rois.json').write_text(json.dumps(rois,indent=2))
(r/'fixture-ids.json').write_text(json.dumps(ids))

# Known zero-chroma truth, no denoise and no artificial coloured scene labels.
y,x=np.indices((256,256)); t=x/255
scenes={
    'one_pixel_vertical': .2+.6*(x%2),
    'one_pixel_horizontal': .2+.6*(y%2),
    'two_pixel_vertical': .2+.6*((x//2)%2),
    'two_pixel_horizontal': .2+.6*((y//2)%2),
    'diagonal_edge': .2+.6*(x>y*.73+20),
    'frequency_sweep': .5+.3*np.sin(2*np.pi*(.01*x+.49*x*x/(2*256))),
    'checkerboard_nyquist': .2+.6*((x+y)%2),
    'fine_repetitive': .5+.15*np.cos(2*np.pi*x/8)+.15*np.cos(2*np.pi*y/8),
}
for name, gray in scenes.items():
    f=r/'fixtures'/('synthetic-'+name); f.mkdir(exist_ok=True)
    gray.astype('f4').tofile(f/'normalized-off.f32')
    np.stack([gray]*3,-1).astype('f4').tofile(f/'ground-truth.rgb.f32')
    (f/'golden.txt').write_text('256 256 3\n1 1 1 1\n1 0 0 0 1 0 0 0 1\n')
