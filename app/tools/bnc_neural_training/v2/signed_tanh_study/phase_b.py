"""One isolated 200-step full-FP32 run from the unchanged diagnostic checkpoint."""
import argparse
import copy
import hashlib
import json
import os
import time
from pathlib import Path
from types import SimpleNamespace

import torch
from torch.utils.data import DataLoader

from ..checkpoint import GlobalCosine,capture,load,objective_identity,persist,restore_rng
from ..data import TrainingData,read_corpus
from ..evaluate import synthetic_gate
from ..losses import reconstruction_loss
from ..model import BncNeuralV2
from ..session_report import comparison
from ..train import atomic_json,validate
from ..validation_freeze import cases as fixed_cases
from .distribution import SOURCE,ROOT,SOURCE_SHA
from .model import SignedModel
from .phase_a import finite,TAUS


def model_identity():
    result=objective_identity()
    result['v2/signed_tanh_study/model.py']=hashlib.sha256(Path(__file__).with_name('model.py').read_bytes()).hexdigest()
    return result


def run(tau):
    out=ROOT/f'tau{tau}'
    if (out/'checkpoints/latest.pt').exists():
        raise ValueError('candidate checkpoint already exists; refusing to overwrite')
    zero=json.loads((out/'zero-update.json').read_text())
    screen=json.loads((ROOT/'phase-a-screen.json').read_text())
    if zero['numericalFailure'] is not None or not screen[str(tau)]['eligibleFor200Steps']:
        raise ValueError('candidate failed zero-update qualification')
    if hashlib.sha256(SOURCE.read_bytes()).hexdigest()!=SOURCE_SHA:
        raise ValueError('source checkpoint changed')
    os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG',':4096:8')
    torch.set_num_threads(2)
    torch.backends.cudnn.benchmark=False
    torch.backends.cudnn.deterministic=True
    torch.backends.cuda.matmul.allow_tf32=False
    torch.backends.cudnn.allow_tf32=False
    torch.use_deterministic_algorithms(True)
    source=load(SOURCE)
    if source['step']!=31555 or source['sampler']['nextSampleIndex']!=1009760:
        raise ValueError('source step/sample position changed')
    if source['modelIdentity']!=BncNeuralV2().config.description() or source['objectiveIdentity']!=objective_identity():
        raise ValueError('source architecture/loss/data contract changed')
    parts,corpus=read_corpus(Path('build/bnc-neural-v2/corpus/corpus.json'))
    if source['datasetIdentity']['sha256']!=corpus['sha256']:
        raise ValueError('dataset/split identity changed')
    cases=fixed_cases(parts['validation'],823109,source['provenance'])
    model=SignedModel(tau).cuda()
    model.load_state_dict(source['model'],strict=True)
    optimizer=torch.optim.AdamW(model.parameters(),lr=.001,betas=(.9,.99),weight_decay=1e-4)
    optimizer.load_state_dict(copy.deepcopy(source['optimizer']))
    scheduler=GlobalCosine(optimizer)
    scheduler.load_state_dict(copy.deepcopy(source['scheduler']))
    scaler=torch.amp.GradScaler('cuda')
    scaler.load_state_dict(copy.deepcopy(source['scaler']))
    provenance=copy.deepcopy(source['provenance'])
    provenance['signedTanhResearch']=dict(tau=tau,sourceCheckpoint=str(SOURCE.resolve()),
                                           sourceSHA256=SOURCE_SHA,fullFP32ForwardAndLoss=True)
    history=list(source['history'])
    best=source['best']
    generator=torch.Generator().manual_seed(823109+7919)
    if source['sampler']['loaderGeneratorState'] is not None:
        generator.set_state(source['sampler']['loaderGeneratorState'])
    generator_before=generator.get_state()
    data=TrainingData(parts['train'],200*32,823109,1009760)
    loader=iter(DataLoader(data,batch_size=32,shuffle=False,num_workers=4,pin_memory=True,
                           persistent_workers=True,prefetch_factor=2,generator=generator))
    generator.set_state(generator_before)
    restore_rng(source['rng'])
    step=31555
    started=time.perf_counter()
    stats=dict(numericalFailures=0,maxGradient=0.,maxGateInput=0.,maxGateOutput=0.,
               adamWStateFinite=True)
    args=SimpleNamespace(seed=823109,microbatch=32,workers=4,tau=tau,
                         manifest='build/bnc-neural-v2/corpus/corpus.json',
                         source=str(SOURCE),fullFP32=True)
    def save_checkpoint():
        state=capture(model,optimizer,scaler,scheduler,step,history,best,provenance,
                      source['elapsed']+time.perf_counter()-started,args,generator)
        state['objectiveIdentity']=model_identity()
        state['signedTanhResearch']=dict(tau=tau,sourceSHA256=SOURCE_SHA,
                                         gate='0.5*(a*tau*tanh(b/tau)+b*tau*tanh(a/tau))',
                                         forwardAndLoss='FP32')
        persist(out,state,periodic=True)
    save_checkpoint()
    model.train()
    failure=None
    try:
        while step<31755:
            packed,truth=next(loader)
            packed,truth=packed.cuda(non_blocking=True),truth.cuda(non_blocking=True)
            optimizer.zero_grad(set_to_none=True)
            scheduler.apply(step)
            with torch.autocast('cuda',enabled=False):
                output=model(packed.float())
                loss,terms,rgb=reconstruction_loss(packed.float(),output,truth.float(),True)
            if not finite((output,loss,terms,rgb)):
                raise FloatingPointError(f'nonfinite forward/loss at step {step}')
            loss.backward()
            gradients=[p.grad for p in model.parameters() if p.grad is not None]
            if not finite(gradients):
                raise FloatingPointError(f'nonfinite gradient at step {step}')
            stats['maxGradient']=max(stats['maxGradient'],max(float(g.detach().abs().max()) for g in gradients))
            peaks=model.peaks()
            stats['maxGateInput']=max(stats['maxGateInput'],peaks['maxGateInput'])
            stats['maxGateOutput']=max(stats['maxGateOutput'],peaks['maxGateOutput'])
            optimizer.step()
            if not finite(optimizer.state_dict()['state']):
                raise FloatingPointError(f'nonfinite AdamW state after step {step}')
            step+=1
            scheduler.committed()
            if (step-31555)%50==0 or step==31556:
                print(json.dumps(dict(phase='B',tau=tau,step=step,loss=float(loss.detach()),
                                      lr=optimizer.param_groups[0]['lr'])),flush=True)
            if (step-31555)%100==0:save_checkpoint()
    except FloatingPointError as exc:
        failure=str(exc)
        stats['numericalFailures']=1
        stats['adamWStateFinite']=finite(optimizer.state_dict()['state'])
        if stats['adamWStateFinite'] and finite(model.state_dict()) and scheduler.next_step==step:
            save_checkpoint()
    finally:
        if hasattr(loader,'_shutdown_workers'):loader._shutdown_workers()
    result=dict(tau=tau,startStep=31555,endStep=step,optimizerUpdates=step-31555,
                nextSampleIndex=step*32,numerical=stats,numericalFailure=failure,
                checkpoint=str((out/'checkpoints/latest.pt').resolve()),
                sourceCheckpoint=str(SOURCE.resolve()),sourceSHA256=SOURCE_SHA,
                validation=None,synthetic=None,syntheticGate=None)
    if failure is None:
        rows,aggregate=validate(model,cases,'cuda',True)
        aggregate['step']=step
        history.append(aggregate)
        best=min(best,aggregate['selectionScore'])
        save_checkpoint()
        model.cpu().eval()
        torch.cuda.empty_cache()
        synthetic,_=comparison(model)
        result['validation']=dict(aggregate=aggregate,cases=rows)
        result['synthetic']=synthetic
        result['syntheticGate']=synthetic_gate(synthetic)
    atomic_json(out/'after-200.json',result)
    print(json.dumps(dict(phase='B complete',tau=tau,endStep=step,
                          numericalFailure=failure,
                          validationScore=None if result['validation'] is None else result['validation']['aggregate']['selectionScore'],
                          syntheticGateFailures=None if result['syntheticGate'] is None else len(result['syntheticGate']['failures']))),flush=True)


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--tau',type=int,required=True,choices=TAUS)
    args=parser.parse_args()
    run(args.tau)


if __name__=='__main__':main()
