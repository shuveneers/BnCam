"""Assert known-truth detail/false-colour bounds on actual GPU output, not source strings."""
from pathlib import Path
import json, numpy as np
from PIL import Image,ImageDraw
from quality_metrics import measure

root=Path('build/phase2-raw-quality'); rows={}; failures=[]
for f in sorted((root/'fixtures').glob('synthetic-*')):
    truth=np.fromfile(f/'ground-truth.rgb.f32',dtype='f4').reshape(256,256,3)
    raw=truth[...,1]; results={}; panels=[('Known neutral truth',truth)]
    for alg in ['malvar','amaze','auto','bnc-unavailable']:
        v=np.fromfile(root/'demosaic'/f.name/(alg+'-lsc-off.rgb.f32'),dtype='f4').reshape(truth.shape)
        q=v[8:-8,8:-8]; t=truth[8:-8,8:-8]
        results[alg]=measure(q,t)
        errors=[np.max(abs(v[y+8:248:2,x+8:248:2,c]-raw[y+8:248:2,x+8:248:2])) for y,x,c in [(0,0,2),(0,1,1),(1,0,1),(1,1,0)]]
        results[alg]['sampled_sensel_max_error']=float(max(errors))
        if max(errors)>2.e-7 or not np.isfinite(v).all(): failures.append(f.name+': sensel/finite '+alg)
        if alg=='malvar': malvar=v.copy()
        if alg=='bnc-unavailable' and not np.array_equal(malvar,v): failures.append(f.name+': dishonest neural fallback')
        panels.append((alg,v))
    m=results['malvar']; a=results['amaze']; name=f.name.removeprefix('synthetic-')
    if name.startswith('two_pixel'):
        if not (a['opponent_rms']<.5*m['opponent_rms'] and .99<a['luma_retention']<1.01 and a['luma_rmse']<.005):
            failures.append(name+': two-pixel detail/false-colour')
    if name=='diagonal_edge':
        if not (a['opponent_rms']<m['opponent_rms'] and a['luma_gradient_retention']>=.95*m['luma_gradient_retention']):
            failures.append(name+': diagonal detail/false-colour')
    if name=='frequency_sweep' and not (a['opponent_rms']<m['opponent_rms'] and a['luma_gradient_retention']>=.95*m['luma_gradient_retention']):
        failures.append(name+': sweep detail/false-colour')
    if name=='fine_repetitive' and not (.98<a['luma_retention']<1.02 and a['opponent_rms']<.005): failures.append(name+': repetitive detail')
    # At exact CFA Nyquist, one-pixel/checker inputs are ambiguous. Record failure of
    # reconstruction honestly; never interpret a blur/zero-contrast output as a success.
    rows[name]=results
    canvas=Image.new('RGB',(256*len(panels),282)); d=ImageDraw.Draw(canvas)
    for i,(label,v) in enumerate(panels):
        canvas.paste(Image.fromarray(np.rint(np.clip(v,0,1)*255).astype('u1')),(i*256,26));d.text((i*256+4,5),label,fill='white')
    canvas.save(root/'stage-crops'/(f.name+'.png'))
(root/'metrics/synthetic-regression.json').write_text(json.dumps(dict(metrics=rows,failures=failures),indent=2))
print(json.dumps({k:{a:{m:v[m] for m in ['opponent_rms','luma_retention','luma_gradient_retention']} for a,v in val.items() if a in ['malvar','amaze']} for k,val in rows.items()},indent=2))
assert not failures, failures
print('PHASE2_SYNTHETIC_REGRESSION_PASS')
