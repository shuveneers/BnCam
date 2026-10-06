"""Fixed-coordinate Phase 1/2 comparisons, including full-gain corner residuals."""
from pathlib import Path
import json,csv,html,numpy as np
from PIL import Image,ImageDraw
from quality_metrics import measure

r=Path('build/phase2-raw-quality'); old=Path('build/raw-truth-audit')
ids=json.loads((r/'fixture-ids.json').read_text()); rows=[]; lsc={}; stages={}; gallery=[]
def display(v):
    v=np.clip(v,0,1); return np.rint(255*np.where(v<=.0031308,12.92*v,1.055*v**(1/2.4)-.055)).astype('u1')
def contact(panels,path):
    w,h=panels[0][1].size; canvas=Image.new('RGB',(w*len(panels),h+36),'#181818'); d=ImageDraw.Draw(canvas)
    for i,(label,im) in enumerate(panels): canvas.paste(im,(i*w,36)); d.text((i*w+3,5),label,fill='white')
    canvas.save(path)
for id in ids:
    f=r/'fixtures'/id; m=json.loads((f/'metadata.json').read_text()); h,w=m['height'],m['width']; shape=(h,w,3)
    rois=json.loads((f/'rois.json').read_text()); dest=r/'stage-crops'/id; dest.mkdir(exist_ok=True)
    n=np.memmap(f/'normalized-off.f32',dtype='f4',mode='r',shape=(h,w))
    corrected=np.memmap(f/'normalized-on.f32',dtype='f4',mode='r',shape=(h,w))
    maps=np.load(f/'maps.npy'); g=np.load(old/'fixtures'/id/'gain.npy',mmap_mode='r'); beforeg=np.load(old/'fixtures'/id/'gain-current.npy',mmap_mode='r')
    safe=np.where(np.isfinite(maps)&(maps>=1),maps,1)
    lsc[id]=dict(metadata_min=float(maps.min()),metadata_max=float(maps.max()),applied_min=float(g.min()),applied_max=float(g.max()),
        before_applied_min=float(beforeg.min()),before_applied_max=float(beforeg.max()),
        nodes_safety_changed_pct=float(np.mean(safe!=maps)*100),sensels_safety_changed_pct=0.,
        before_cap_affected_sensels_pct=float(np.mean(abs(g-beforeg)>1.e-6)*100),
        scene_linear_cap_4_affected_pct=float(np.mean(n*g>4)*100),patches={})
    wb=np.array(m['wb'])[[0,1,3]]; ccm=np.array(m['ccm']).reshape(3,3)
    variants={
        'P1 Malvar capped': np.memmap(old/'demosaic'/id/'malvar-lsc-current.rgb.f32',dtype='f4',mode='r',shape=shape),
        'P2 Malvar full LSC':np.memmap(r/'demosaic'/id/'malvar-lsc-on.rgb.f32',dtype='f4',mode='r',shape=shape),
        'P2 AMaZE full LSC':np.memmap(r/'demosaic'/id/'amaze-lsc-on.rgb.f32',dtype='f4',mode='r',shape=shape),
    }
    preserve={}
    for label,v in variants.items():
        if label.startswith('P2'):
            preserve[label]=max(float(np.max(abs(v[y+8:h-8:2,x+8:w-8:2,c]-corrected[y+8:h-8:2,x+8:w-8:2]))) for y,x,c in [(0,0,2),(0,1,1),(1,0,1),(1,1,0)])
    lsc[id]['original_sensel_max_errors']=preserve
    assert max(preserve.values())<2.e-7,(id,preserve)
    product=Image.open(r/'stages'/id/'normal-replay.jpg').rotate(90,expand=True)
    libpath=next((old/'dng').glob(id+'*libraw*.jpg'),None)
    if libpath is None: libpath=next((old/'dng').glob(id+'*.jpg'),None)
    external=Image.open(libpath) if libpath else None
    for name,(x,y,cw,ch) in rois.items():
        sl=np.s_[y:y+ch,x:x+cw]; panels=[('CFA (uncalibrated)',Image.fromarray(display(n[sl])).convert('RGB'))]
        patch={}
        for label,v in variants.items():
            lin=(v[sl]*wb)@ccm.T; np.save(dest/(name+'-'+label.replace(' ','_')+'.npy'),lin)
            metrics=measure(lin); rows.append(dict(id=id,roi=name,route=label,**metrics));patch[label]=metrics
            panels.append((label,Image.fromarray(display(lin))))
        panels.append(('P2 product JPEG',product.crop((x,y,x+cw,y+ch))))
        if external: panels.append(('LibRaw AHD',external.crop((x,y,x+cw,y+ch))))
        filename=name+'-phase2.png';contact(panels,dest/filename);gallery.append((id,name,str((dest/filename).relative_to(r)).replace('\\','/')))
        # Scene colour/residuals are descriptive, not random-noise measurements.
        lsc[id]['patches'][name]=dict(gain_before_mean=float(beforeg[sl].mean()),gain_after_mean=float(g[sl].mean()),
            before_rgb=patch['P1 Malvar capped']['mean_rgb'],after_rgb=patch['P2 Malvar full LSC']['mean_rgb'],
            before_residual_variance=patch['P1 Malvar capped']['local_chroma_variance'],
            after_residual_variance=patch['P2 Malvar full LSC']['local_chroma_variance'])
        p1=old/'stage-crops'/id; p2=r/'stage-crops'/id
        if (p1/(name+'-stage-CCM.npy')).exists():
            stage_rows={}; stage_panels=[]
            for label,path in [('P1',p1),('P2',p2)]:
                for stage in ['demosaic','CCM','FLLF','gamut']:
                    v=np.load(path/(name+'-stage-'+stage+'.npy'));stage_rows[label+'_'+stage]=measure(v)
                    stage_panels.append((label+' '+stage,Image.fromarray(display(v))))
                stage_rows[label+'_FLLF_over_CCM']=stage_rows[label+'_FLLF']['local_chroma_variance']/max(stage_rows[label+'_CCM']['local_chroma_variance'],1.e-15)
            stages[id+'/'+name]=stage_rows
            filename=name+'-before-after-stages.png';contact(stage_panels,dest/filename);gallery.append((id,name+' stage before/after',str((dest/filename).relative_to(r)).replace('\\','/')))
with (r/'metrics/real-comparisons.csv').open('w',newline='') as f:
    writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
(r/'metrics/lsc-before-after.json').write_text(json.dumps(lsc,indent=2))
(r/'metrics/stage-before-after.json').write_text(json.dumps(stages,indent=2))
body='''<!doctype html><meta charset="utf-8"><title>BnCam Phase 2</title>
<style>body{background:#17191c;color:#eee;font:16px system-ui;margin:24px}a{color:#8cf}img{display:block;max-width:none}figure{margin:24px 0;overflow:auto}button{padding:10px}</style>
<h1>Phase 2 — same RAW, same pixels</h1><p>100% sensor orientation. CFA is uncalibrated; RGB golden panels use the same WB/CCM. No display resize.</p>
<p>Fine repetitive texture is a known failed quality target. Real foliage residuals include real scene colour.</p>
<script>let zoom=1;function setZoom(v){zoom=v;document.querySelectorAll("img").forEach(i=>{if(i.naturalWidth)i.style.width=(i.naturalWidth*zoom)+"px";i.style.imageRendering="pixelated"})}</script>
<a href="report.md">Report</a> <button onclick="setZoom(2)">200% nearest</button>
<button onclick="setZoom(1)">100%</button>'''
for id,name,path in gallery: body+=f'<h3>{html.escape(id+" / "+name)}</h3><figure><img loading="lazy" src="{path}"></figure>'
for p in sorted((r/'stage-crops').glob('synthetic-*.png')):body+=f'<h3>{p.stem}</h3><figure><img loading="lazy" src="stage-crops/{p.name}"></figure>'
body+='<script>document.querySelectorAll("img").forEach(i=>i.addEventListener("load",()=>setZoom(zoom)))</script>'
(r/'index.html').write_text(body,encoding='utf-8')
print('Phase 2 gallery and numerical comparisons exported')
