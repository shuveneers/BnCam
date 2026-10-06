"""Post-schedule Phase A evidence; never enables the product or calls proxies truth."""
from pathlib import Path
import argparse
import hashlib
import json
import numpy as np
import torch
from PIL import Image, ImageDraw
from ..contracts import pack, merge, measured_mask
from ..evaluate import evaluate as synthetic_evaluate
from ..inspect_candidate import reconstruct, display
from ..model import BncNeural, Config as V1Config, infer_tiled
from ..package import load_reference
from app.tools.raw_truth.quality_metrics import measure
from .model import BncNeuralV2, MissingRgbAdapter
from .data import read_corpus, validation_cases
from .train import atomic_json, validate, source_snapshot


REQUIRED=('two_pixel_horizontal','two_pixel_vertical','frequency_sweep','diagonal_edge','fine_repetitive')
PERIODS=tuple(f'repetitive_period_{n}' for n in (5,7,11,13))
DETAIL=('neutral_headroom','slanted_edge_0417','slanted_edge_131','thin_lines','zero_chroma_fine_detail')
AMBIGUOUS=('one_pixel_vertical','one_pixel_horizontal','checkerboard_nyquist')


def synthetic_gate(rows):
    failures=[]
    for name in REQUIRED+PERIODS+DETAIL+('flat_neutral','color_edge','isoluminant_color_edge'):
        if not rows.get(name,{}).get('bnc_neural'):
            failures.append(name+': missing evidence');continue
        q=rows[name]['bnc_neural']
        if q['sampled_sensel_max_error']!=0:failures.append(name+': measured sensel changed')
        if name in REQUIRED+('flat_neutral',):
            limit=.001 if name=='flat_neutral' else .005
            if not np.isfinite(q['opponent_rms']) or q['opponent_rms']>limit:
                failures.append(f'{name}: opponent RMS exceeds {limit}')
        if name in REQUIRED+PERIODS+DETAIL:
            value=q['luma_gradient_retention']
            if value is None or not np.isfinite(value) or not .98<=value<=1.02:
                failures.append(name+': luma gradient outside [0.98,1.02]')
        if name in PERIODS:
            baseline=[rows[name].get(route) for route in ('malvar','amaze')]
            if not all(baseline):failures.append(name+': classical evidence missing')
            # Conservative reading: match or improve each baseline, not just the worse one.
            elif q['opponent_error_rms']>min(v['opponent_error_rms'] for v in baseline):
                failures.append(name+': opponent error worse than at least one classical route')
        if name in ('color_edge','isoluminant_color_edge'):
            for channel in ('RG','BG'):
                v=q['opponent_channels'][channel]['chromatic_gradient_retention']
                if v is None or not np.isfinite(v) or not .95<=v<=1.05:
                    failures.append(f'{name}: {channel} edge outside [0.95,1.05]')
    for name,row in rows.items():
        q=row.get('bnc_neural')
        if q and q['sampled_sensel_max_error']!=0 and name not in REQUIRED+PERIODS+DETAIL+('flat_neutral','color_edge','isoluminant_color_edge'):
            failures.append(name+': measured sensel changed')
    return dict(passed=not failures,failures=failures,periodRmsTarget=.005,
        periodRule='no worse than either classical route',productReady=False)


def candidate(run,structured):
    complete=json.loads((run/'completed.json').read_text())
    if complete['trainingSteps']<100000:raise ValueError('minimum 100k completed optimizer updates required')
    state=torch.load(run/'best-fp32.pt',map_location='cpu',weights_only=True)
    model=BncNeuralV2() if structured else BncNeural(V1Config(12,6))
    model.load_state_dict(state['model']);model.eval()
    package=run/'best.bncmodel';sha=package.with_suffix('.bncmodel.sha256').read_text().strip()
    quantized,manifest=load_reference(package,sha)
    if manifest['trainingSteps']!=state['step']:raise ValueError('FP32/package checkpoint mismatch')
    return model,quantized,dict(completed=complete,bestStep=state['step'],packageSHA256=sha)


@torch.no_grad()
def parity(model,quantized):
    generator=torch.Generator().manual_seed(981300)
    a=torch.rand((1,4,137,149),generator=generator)
    reference=model(a);quant=quantized(a)
    tiled={str(inner):float((infer_tiled(model,a,inner)-reference).abs().max()*4) for inner in (32,64,128)}
    return dict(sceneLinearTiledMaxError=tiled,passed=all(v<=1e-5 for v in tiled.values()),
        fp16WeightFp32ComputeMaxError=float((reference-quant).abs().max()*4),
        note='FP32 host; FP16 weight quantization measured separately; no Vulkan result')


def real_comparison(models,out,phase2):
    out.mkdir(parents=True,exist_ok=True);rows=[]
    ids=('IMG_BNC_20260927_143700_632','IMG_BNC_20260927_143709_928','IMG_BNC_20260927_143007_948')
    for capture in ids:
        fixture=phase2/'fixtures'/capture;meta=json.loads((fixture/'metadata.json').read_text())
        h,w=meta['height'],meta['width'];rawfile=fixture/'normalized-on.f32'
        raw=np.memmap(rawfile,mode='r',dtype='f4',shape=(h,w))
        with rawfile.open('rb') as f:raw_sha=hashlib.file_digest(f,'sha256').hexdigest()
        classical={r:np.memmap(phase2/'demosaic'/capture/(r+'-lsc-on.rgb.f32'),mode='r',dtype='f4',shape=(h,w,3)) for r in ('malvar','amaze')}
        wb=np.array(meta['wb'])[[0,1,3]];ccm=np.array(meta['ccm']).reshape(3,3)
        if meta['wb'][1]!=meta['wb'][2]:raise ValueError('unequal greens require upstream balance')
        for name,(x,y,cw,ch) in json.loads((fixture/'rois.json').read_text()).items():
            images={r:np.array(v[y:y+ch,x:x+cw]) for r,v in classical.items()}
            for route,model in models.items():
                halo=model.config.halo*2+4
                sx,sy=max(0,x-halo)//2*2,max(0,y-halo)//2*2
                ex,ey=min(w,(x+cw+halo+1)//2*2),min(h,(y+ch+halo+1)//2*2)
                rgb=reconstruct(model,np.array(raw[sy:ey,sx:ex]),meta['cfa'],(sx,sy))
                images[route]=rgb[y-sy:y-sy+ch,x-sx:x-sx+cw]
            canvas=Image.new('RGB',(cw*len(images),ch+30),'#202020');draw=ImageDraw.Draw(canvas)
            for i,(route,rgb) in enumerate(images.items()):
                post=(rgb*wb)@ccm.T
                rows.append(dict(capture=capture,sensor=meta['sensor'],roi=name,route=route,
                    finalizedBayerSHA256=raw_sha,post_demosaic=measure(rgb),post_WB_CCM=measure(post),
                    evidence='reference proxy only; no real RGB truth',post_FLLF=None,final=None))
                np.save(out/f'{capture}-{name}-{route}.npy',rgb)
                canvas.paste(display(post),(i*cw,30));draw.text((i*cw+4,6),route,fill='white')
            canvas.save(out/f'{capture}-{name}.png')
    atomic_json(out/'proxies.json',dict(rows=rows,visualAcceptance='PENDING_REVIEW',scale='100%; identical WB/CCM and sRGB display proxy'))


def report(result,out):
    info=result['models']['v2'];p=info['completed'];counts=p['trainingCorpus']['counts']
    lines=['# BnC Neural v2 — Phase A evidence','',
        '**Product remains unavailable.** This report does not authorize a Vulkan backend or activation.','',
        f"Corpus source groups: {counts}. Natural/procedural exposure: 60/40.",
        f"W24: 27,200 parameters; 54,400 FP16 weight bytes. Green RF: 31 packed / 62 raw; opponent RF: 41 packed / 82 raw; halo: 20 packed.",
        f"V2 completed {p['trainingSteps']} optimizer updates; selected checkpoint: {info['bestStep']}.",
        f"Matched v1 completed {result['models']['v1_matched']['completed']['trainingSteps']} updates; selected checkpoint: {result['models']['v1_matched']['bestStep']}.",
        '', '## Fixed synthetic comparison','',
        '| Fixture | Malvar opponent RMS | AMaZE | Original v1 | Matched v1 | V2 | V2 luma gradient |',
        '|---|---:|---:|---:|---:|---:|---:|']
    def fmt(v):return 'undefined' if v is None else f'{v:.7f}'
    for name,row in result['synthetic']['v2'].items():
        values=[row[r]['opponent_error_rms'] if row.get(r) else None for r in ('malvar','amaze')]
        values += [result['synthetic'][r][name]['bnc_neural']['opponent_error_rms'] for r in ('v1_original','v1_matched','v2')]
        values += [row['bnc_neural']['luma_gradient_retention']]
        lines.append('| '+name+' | '+' | '.join(map(fmt,values))+' |')
    lines += ['',f"Synthetic gate: {'PASS' if result['syntheticGate']['passed'] else 'FAIL'}.",'']
    lines += ['- '+failure for failure in result['syntheticGate']['failures']]
    lines += ['', '## Architecture ablation diagnostics','',
        '| Held-out natural metric (normalized units) | Matched v1 | V2 |',
        '|---|---:|---:|']
    for key in ('missing_rgb_l1','missing_g_l1','rg_rms','bg_rms','opponent_gradient_error_rms','alternating_opponent_sign_change_error'):
        values=[]
        for route in ('v1_matched','v2'):
            cases=[q for q in result['natural'][route]['cases'] if not q['case'].startswith('analytic:')]
            values.append(float(np.mean([q[key] for q in cases])))
        lines.append('| '+key+' | '+' | '.join(map(fmt,values))+' |')
    lines += ['', '| Chromatic fixture / opponent | Original v1 gradient retention | Matched v1 | V2 |',
        '|---|---:|---:|---:|']
    for name in ('color_edge','isoluminant_color_edge'):
        for channel in ('RG','BG'):
            values=[result['synthetic'][r][name]['bnc_neural']['opponent_channels'][channel]['chromatic_gradient_retention'] for r in ('v1_original','v1_matched','v2')]
            lines.append('| '+name+' / '+channel+' | '+' | '.join(map(fmt,values))+' |')
    selected=json.loads((out.parent/'training/w24/validation'/f"{info['bestStep']:06d}.json").read_text())
    lines += ['', '## Selected W24 validation losses','', '| Term (normalized units) | Mean |','|---|---:|']
    for key in selected['cases'][0]['losses']:
        lines.append('| '+key+' | '+fmt(float(np.mean([q['losses'][key] for q in selected['cases']])))+' |')
    lines += ['', 'FP32 tile parity: `'+json.dumps(result['parity'],sort_keys=True)+'`.',
        'Measured CFA maximum error across synthetic cases: '+fmt(max(q['bnc_neural']['sampled_sensel_max_error'] for q in result['synthetic']['v2'].values()))+'.',
        'Phase A status: '+result['phaseAStatus']+'.']
    lines += ['', 'Complete RG/BG reconstruction, gradient, alternating-sign, edge-retention and energy results are in evidence.json.',
        'All training loss values and per-case diagnostics remain in training/*/validation/*.json; no aggregate substitutes for those rows.',
        'Natural held-out results use independent source groups and separate procedural test bins. The comparative screen is predeclared; visual acceptance remains pending.',
        'Only the matched v1 control shares the new split contract. The original v1 natural-image rows may overlap its older training split and are not used for the natural gate.',
        'Real RAW panels are 100% crops with identical downstream WB/CCM/display. They are visual proxies, never absolute ground truth.',
        'Exact 1px/checker CFA-Nyquist cases are ambiguous. Reported output energy/false color cannot establish recovered detail. Confidence is uncalibrated.',
        'Vulkan parity, Adreno performance, device captures, warmup, transient memory and APK delta: NOT RUN; Phase A acceptance is required first.',
        'Auto Hybrid, FLLF, downstream color and product availability are unchanged.']
    (out/'report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--root',type=Path,default=Path('build/bnc-neural-v2'))
    args=parser.parse_args();root=args.root;out=root/'evaluation';out.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2)
    v2,q2,i2=candidate(root/'training/w24',True)
    v1,q1,i1=candidate(root/'training/v1-matched',False)
    if i1['completed']['trainingSteps']!=i2['completed']['trainingSteps']:raise ValueError('ablation exposure mismatch')
    original=Path('build/bnc-neural/training/opponent-v2-full/candidate.bncmodel')
    old,old_info=load_reference(original,original.with_suffix('.bncmodel.sha256').read_text().strip())
    models=dict(v2=MissingRgbAdapter(v2),v1_matched=v1,v1_original=old)
    result=dict(models=dict(v2=i2,v1_matched=i1,v1_original=old_info),synthetic={},natural={},bncNeuralAvailable=False,
        evaluationCodeSHA256=source_snapshot(out),originalV1NaturalHoldoutGuaranteed=False)
    result['parity']=parity(models['v2'],MissingRgbAdapter(q2))
    fixtures=Path('build/bnc-neural/synthetic')
    for route,model in models.items():
        result['synthetic'][route]=synthetic_evaluate(model,fixtures)
        print('synthetic complete: '+route,flush=True)
    result['syntheticGate']=synthetic_gate(result['synthetic']['v2'])
    parts,_=read_corpus(root/'corpus/corpus.json')
    cases=validation_cases(parts['test'],split='test',natural_count=128,procedural_count=104)
    for route,model in (('v2',v2),('v1_matched',v1),('v1_original',old)):
        rows,aggregate=validate(model,cases,'cpu',route=='v2')
        natural=[q for q in rows if not q['case'].startswith('analytic:')]
        natural_aggregate=dict(missing=float(np.mean([q['missing_rgb_l1'] for q in natural])),
            opponent=float(np.mean([.5*(q['rg_rms']+q['bg_rms']) for q in natural])),
            edge=float(np.mean([q['losses']['luma_gradient']+q['losses']['opponent_gradient']+q['losses']['chromatic_edge'] for q in natural])))
        result['natural'][route]=dict(cases=rows,aggregate=natural_aggregate,combinedNaturalProceduralAggregate=aggregate)
    # Predeclared conservative comparison, not a substitute for visual inspection.
    a,b=result['natural']['v2']['aggregate'],result['natural']['v1_matched']['aggregate']
    result['naturalScreen']=dict(passed=all(a[k]<=b[k] for k in ('missing','opponent','edge')),
        rule='v2 aggregate missing/opponent/edge must each match or improve matched-exposure v1',visualAcceptance='PENDING_REVIEW')
    result['ambiguity']={n:dict(ambiguous=True,confidence=None,confidenceStatus='not calibrated',
        metrics=result['synthetic']['v2'][n]['bnc_neural']) for n in AMBIGUOUS}
    atomic_json(out/'evidence.json',result)
    real_comparison(dict(v1_original=old,v1_matched=v1,v2=models['v2']),out/'real-raw',Path('build/phase2-raw-quality'))
    result['phaseAStatus']='FAIL_STOP_NO_BACKEND' if not (result['syntheticGate']['passed'] and result['parity']['passed'] and result['naturalScreen']['passed']) else 'NUMERICAL_SCREENS_PASS_AWAITING_VISUAL_REVIEW'
    atomic_json(out/'evidence.json',result);report(result,out)
    print(result['phaseAStatus'],flush=True)


if __name__=='__main__':main()
