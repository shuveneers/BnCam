"""Validation and unchanged synthetic gates at a stopped optimizer boundary."""
from pathlib import Path
import datetime
import json
import numpy as np
import torch
from ..contracts import pack, sample, measured_mask
from ..evaluate import evaluate
from ..inspect_candidate import reconstruct
from ..package import load_reference, export_reference
from .checkpoint import load, persist, restore_rng
from .data import read_corpus
from .validation_freeze import cases as fixed_validation_cases
from .model import BncNeuralV2
from .evaluate import synthetic_gate


def comparison(model):
    root=Path('build/bnc-neural/synthetic')
    original=Path('build/bnc-neural/training/opponent-v2-full/candidate.bncmodel')
    v1,identity=load_reference(original,original.with_suffix('.bncmodel.sha256').read_text().strip())
    rows=evaluate(model,root);old=evaluate(v1,root)
    for name,row in rows.items():
        row['bnc_v1']=old[name]['bnc_neural']
        truth=np.fromfile(root/'fixtures'/('synthetic-'+name)/'ground-truth.rgb.f32',dtype='f4').reshape(256,256,3)
        raw=sample(truth,'BGGR');_,g=pack(raw,'BGGR');mask=~measured_mask(g)[8:-8,8:-8]
        for route in ('malvar','amaze','bnc_v1','bnc_neural'):
            if row.get(route) is None:continue
            if route in ('malvar','amaze'):
                rgb=np.fromfile(root/'demosaic'/('synthetic-'+name)/(route+'-lsc-off.rgb.f32'),dtype='f4').reshape(truth.shape)
            else:rgb=reconstruct(model if route=='bnc_neural' else v1,raw)
            error=abs(rgb[8:-8,8:-8]-truth[8:-8,8:-8])
            row[route]['missing_rgb_l1']=float(error[mask].mean())
            row[route]['missing_g_l1']=float(error[...,1][mask[...,1]].mean())
    return rows,identity


def write_report(out,evidence):
    step=evidence['step'];summary=evidence['validation']['aggregate']
    lines=[f'# W24 sessie gestopt op step {step}', '',
        'Training is gestopt. Dit is een tussentijdse diagnose; geen productkwalificatie. Geen v1-retraining.',
        f"Checkpoint: `{evidence['checkpoint']}`. Volgende sample: {step*32}.",
        f"Volledige vaste validatie: {len(evidence['validation']['cases'])} voorbeelden. Missing L1 {summary['missing']:.7f}; opponent {summary['opponent']:.7f}; edge {summary['edge']:.7f} (modeldomein /4).",
        f"Ongewijzigde synthetic gate: {'PASS' if evidence['syntheticGate']['passed'] else 'FAIL'}.",
        '', '| Fixture | Route | Missing RGB L1 | Missing G L1 | Opponent RMS | Luma gradient | RG edge | BG edge |',
        '|---|---|---:|---:|---:|---:|---:|---:|']
    fmt=lambda v:'undefined' if v is None else f'{v:.7f}'
    for name,row in evidence['synthetic'].items():
        for route in ('malvar','amaze','bnc_v1','bnc_neural'):
            q=row.get(route)
            if q is None:lines.append(f'| {name} | {route} | missing evidence |');continue
            values=[q['missing_rgb_l1'],q['missing_g_l1'],q['opponent_error_rms'],q['luma_gradient_retention'],
                q['opponent_channels']['RG']['chromatic_gradient_retention'],q['opponent_channels']['BG']['chromatic_gradient_retention']]
            lines.append('| '+name+' | '+route+' | '+' | '.join(map(fmt,values))+' |')
    lines+=['','Synthetic errors use scene-linear units. Full individual validation losses/diagnostics and alternating opponent signs are in evidence.json.',
        'Original v1 is the existing 5k research baseline, not a matched-exposure ablation.',
        'Exact 1px/checker CFA-Nyquist cases remain ambiguous; output energy cannot prove true detail recovery. Confidence remains uncalibrated.',
        'The opponent branch uses FP32 arithmetic under training AMP to prevent gated-product overflow. Architecture, weights, loss, data, thresholds, optimizer and product integration are unchanged. Synthetic results do not select the best checkpoint.','']
    lines+=['- '+v for v in evidence['syntheticGate']['failures']]
    lines+=['',f"Volgende handmatige sessie: `{evidence['nextCommand']}`",'','Na checkpoint + validatie + gate + rapport stopt iedere sessie.']
    (out/'report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')


def finish_session(run,manifest,checkpoint=None):
    # Import lazily to avoid a train/report import cycle.
    from .train import validate, atomic_json, publish_status
    run=Path(run);checkpoint=Path(checkpoint or run/'checkpoints/latest.pt')
    state=load(checkpoint);step=state['step'];saved_rng=state['rng']
    parts,corpus=read_corpus(manifest)
    if corpus['sha256']!=state['datasetIdentity']['sha256']:raise ValueError('session report corpus mismatch')
    torch.set_num_threads(2);model=BncNeuralV2();model.load_state_dict(state['model']);model.eval()
    cases=fixed_validation_cases(parts['validation'],state['trainingArguments']['seed'],state['provenance'])
    rows,aggregate=validate(model,cases,'cpu',True);aggregate['step']=step
    state['history']=[h for h in state['history'] if h['step']!=step]+[aggregate]
    improved=aggregate['selectionScore']<state['best']
    if improved:state['best']=aggregate['selectionScore']
    state['bestValidationState']=dict(score=state['best'],history=state['history'])
    state['provenance'].update(trainingSteps=step,exportDate=datetime.datetime.now(datetime.timezone.utc).isoformat(),runStatus='SESSION_STOPPED_NOT_QUALIFIED')
    # Validation never consumes the resumable RNG state or changes the stored weights.
    state['rng']=saved_rng
    persist(run,state,periodic=True,best=improved or not (run/'checkpoints/best.pt').exists())
    if improved:
        export_reference(model,run/'best.bncmodel',state['provenance'])
        torch.save(dict(model=state['model'],step=step,provenance=state['provenance']),run/'best-fp32.pt')
    atomic_json(run/'validation'/f'{step:06d}.json',dict(step=step,aggregate=aggregate,cases=rows))
    atomic_json(run/'validation-history.json',state['history'])
    synthetic,baseline=comparison(model)
    out=run/'sessions'/f'step_{step:06d}';out.mkdir(parents=True,exist_ok=True)
    next_step=min(150000,((step//10000)+1)*10000)
    out_arg='' if run.resolve()==Path('build/bnc-neural-v2/training/w24').resolve() else f"-Out '{str(run.resolve())}' "
    command=f".\\train-bnc-w24.ps1 {out_arg}-Resume '{str((run/'checkpoints/latest.pt').resolve())}' -StopAtStep {next_step}"
    if step>=150000:command='none; maximum global training schedule reached'
    evidence=dict(step=step,checkpoint=str((run/'checkpoints/latest.pt').resolve()),
        validation=dict(aggregate=aggregate,cases=rows),synthetic=synthetic,syntheticGate=synthetic_gate(synthetic),
        originalV1=baseline,nextCommand=command,productReady=False,automaticContinuation=False,
        minimumScheduleComplete=step>=100000,optimizerUpdatesDuringReport=0)
    atomic_json(out/'evidence.json',evidence);write_report(out,evidence)
    status=dict(status='SESSION_STOPPED',currentStep=step,optimizerStep=step,
        resumableCheckpoint=evidence['checkpoint'],nextCommand=command,
        validationSummary=dict(cases=len(rows),missing=aggregate['missing'],opponent=aggregate['opponent'],edge=aggregate['edge'],
            syntheticGate='PASS' if evidence['syntheticGate']['passed'] else 'FAIL',failures=len(evidence['syntheticGate']['failures'])),
        report=str((out/'report.md').resolve()),safeToPowerOff=True,automaticContinuation=False,bncNeuralAvailable=False)
    publish_status(run,status)
    (run/'STOP_REQUESTED').unlink(missing_ok=True)
    restore_rng(saved_rng)
    return status
