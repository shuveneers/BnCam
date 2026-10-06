"""One manually started W24 session. Resume never resets AdamW or the LR curve."""
from pathlib import Path
import argparse
import datetime
import json
import os
import random
import signal
import time
import numpy as np
import torch
from torch.utils.data import DataLoader
from ..package import export_reference
from .checkpoint import GlobalCosine,capture,load,persist,restore_rng,objective_identity
from .model import BncNeuralV2
from .losses import WEIGHTS,reconstruction_loss
from .data import TrainingData,read_corpus
from .validation_freeze import adopt_generator_fix,adopt_precision_fix,cases as fixed_validation_cases
from .train import atomic_json,source_snapshot,validate,publish_status
from .session_report import finish_session


def session_target(step,stop_at=None,session_steps=10000):
    target=stop_at if stop_at is not None else (step//session_steps+1)*session_steps
    if not step<target<=150000:raise ValueError('stop-at-step must exceed current step and be <=150000')
    return target


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--manifest',type=Path,default=Path('build/bnc-neural-v2/corpus/corpus.json'))
    parser.add_argument('--out',type=Path,default=Path('build/bnc-neural-v2/training/w24'))
    parser.add_argument('--resume',type=Path)
    parser.add_argument('--stop-at-step',type=int)
    parser.add_argument('--session-steps',type=int,default=10000)
    parser.add_argument('--checkpoint-every',type=int,default=1000,choices=(1000,2000))
    parser.add_argument('--microbatch',type=int,default=32)
    parser.add_argument('--workers',type=int,default=4)
    parser.add_argument('--seed',type=int,default=823109)
    parser.add_argument('--validate-only',action='store_true')
    args=parser.parse_args()
    if args.microbatch<1 or 32%args.microbatch or args.session_steps<1 or args.workers<0:parser.error('invalid batch/session/workers')
    if args.validate_only and not args.resume:parser.error('--validate-only requires --resume')
    # Also protect direct CLI invocation, independently of the PowerShell wrapper.
    import msvcrt
    args.out.parent.mkdir(parents=True,exist_ok=True)
    lock=(args.out.parent/(args.out.name+'.session.lock')).open('a+b');lock.seek(0)
    if lock.read(1)==b'':lock.write(b'0');lock.flush()
    lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_NBLCK,1)
    try:run(args)
    finally:
        lock.seek(0);msvcrt.locking(lock.fileno(),msvcrt.LK_UNLCK,1);lock.close()


def run(args):
    os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG',':4096:8')
    torch.set_num_threads(2)
    torch.backends.cudnn.benchmark=False;torch.backends.cudnn.deterministic=True
    torch.backends.cuda.matmul.allow_tf32=False;torch.backends.cudnn.allow_tf32=False
    torch.use_deterministic_algorithms(True)
    parts,corpus=read_corpus(args.manifest)
    if not json.loads(args.manifest.read_text()).get('dedupFinalized'):raise ValueError('dedup finalization required')
    state=load(args.resume) if args.resume else None
    if state:
        state=adopt_precision_fix(state)
        state=adopt_generator_fix(state,Path('build/bnc-neural-v2/training/w24'))
        if state['objectiveIdentity']!=objective_identity():raise ValueError('architecture/loss/data contract changed')
        if state['datasetIdentity']['sha256']!=corpus['sha256']:raise ValueError('dataset/split changed')
        if state['modelIdentity']!=BncNeuralV2().config.description():raise ValueError('model identity mismatch')
        for key in ('seed','microbatch'):
            if state['trainingArguments'][key]!=getattr(args,key):raise ValueError('resume argument changed: '+key)
        latest=args.out/'checkpoints/latest.pt'
        if latest.exists() and load(latest)['step']>state['step']:raise ValueError('refusing to overwrite newer progress; choose a new output directory')
    if args.validate_only:
        status=finish_session(args.out,args.manifest,args.resume)
        print(json.dumps(status,indent=2),flush=True);return status
    if not torch.cuda.is_available():raise RuntimeError('CUDA required for W24 training')
    if state is None:
        args.out.mkdir(parents=True,exist_ok=False)
        random.seed(args.seed);np.random.seed(args.seed);torch.manual_seed(args.seed);torch.cuda.manual_seed_all(args.seed)
    else:args.out.mkdir(parents=True,exist_ok=True)
    (args.out/'validation').mkdir(exist_ok=True)
    model=BncNeuralV2().cuda()
    # Construct the containers, then load every saved value before any update.
    optimizer=torch.optim.AdamW(model.parameters(),lr=.001,betas=(.9,.99),weight_decay=1e-4)
    scaler=torch.amp.GradScaler('cuda');scheduler=GlobalCosine(optimizer)
    step=0;history=[];best=float('inf');elapsed_previous=0.
    if state:
        model.load_state_dict(state['model']);optimizer.load_state_dict(state['optimizer'])
        scaler.load_state_dict(state['scaler']);scheduler.load_state_dict(state['scheduler'])
        step=state['step'];history=state['history'];best=state['best'];elapsed_previous=state['elapsed'];provenance=state['provenance']
    else:
        provenance=dict(modelVersion='bnc-v2-gopp-w24-research',trainingCorpus=corpus,
            splitProvenance='51 pinned shards; exact/perceptual dedup; source-disjoint; procedural bins disjoint',
            trainingCodeSHA256=source_snapshot(args.out),lossDefinition=WEIGHTS,exportDate='',trainingSteps=0,purpose='research_candidate',
            architecture='v2',seed=args.seed,optimizer=dict(name='AdamW',betas=[.9,.99],weightDecay=1e-4),
            schedule='cosine 1e-3 to3e-5 over100k; final LR held for extension to150k',
            effectiveBatch=32,rawCrop=256,packedCrop=128,naturalFraction=.6,proceduralFraction=.4,
            microbatch=args.microbatch,torchVersion=str(torch.__version__),gpu=torch.cuda.get_device_name(),
            selection='fixed validation missingL1 +.2 opponentRMS +.2 gradient/edge errors; every case retained',
            runStatus='TRAINING_NOT_QUALIFIED',productReady=False)
    target=session_target(step,args.stop_at_step,args.session_steps)
    publish_status(args.out,dict(status='STARTING_SESSION',optimizerStep=step,stopAtStep=target,
        safeToPowerOff=False,bncNeuralAvailable=False))
    orchestration=args.out/'session-sources'/f'from_{step:06d}'
    provenance['sessionCodeSHA256']=source_snapshot(orchestration)
    stop_file=args.out/'STOP_REQUESTED'
    if stop_file.exists():raise ValueError('pending stop request; remove it explicitly before starting a new session')
    requested=False
    def request_stop(signum,frame):
        nonlocal requested
        requested=True
    for name in ('SIGINT','SIGTERM','SIGBREAK'):
        if hasattr(signal,name):signal.signal(getattr(signal,name),request_stop)
    generator=torch.Generator().manual_seed(args.seed+7919)
    if state and state['sampler']['loaderGeneratorState'] is not None:generator.set_state(state['sampler']['loaderGeneratorState'])
    generator_before=generator.get_state()
    data=TrainingData(parts['train'],(target-step)*32,args.seed,step*32)
    loader=iter(DataLoader(data,batch_size=args.microbatch,shuffle=False,num_workers=args.workers,
        pin_memory=True,persistent_workers=bool(args.workers),prefetch_factor=2 if args.workers else None,generator=generator))
    # Workers use only index-derived RNGs. Iterator construction must not advance
    # the saved generator/global RNG state merely because a session was restarted.
    if state:generator.set_state(generator_before);restore_rng(state['rng'])
    cases=fixed_validation_cases(parts['validation'],args.seed,provenance)
    atomic_json(args.out/'validation-manifest.json',dict(cases=[c[0] for c in cases],seed=args.seed))
    start=time.perf_counter();start_step=step;model.train();torch.cuda.reset_peak_memory_stats()
    def snapshot():
        return capture(model,optimizer,scaler,scheduler,step,history,best,provenance,
            elapsed_previous+time.perf_counter()-start,args,generator)
    try:
        while step<target and not requested and not stop_file.exists():
            batches=[next(loader) for _ in range(32//args.microbatch)]
            for attempt in range(12):
                optimizer.zero_grad(set_to_none=True);batch_terms={k:0. for k in WEIGHTS};scheduler.apply(step)
                for a,b in batches:
                    a,b=a.cuda(non_blocking=True),b.cuda(non_blocking=True)
                    with torch.autocast('cuda',dtype=torch.float16):outputs=model(a)
                    loss,terms,_=reconstruction_loss(a,outputs,b,True)
                    if not torch.isfinite(loss):raise FloatingPointError('nonfinite same-signal reconstruction loss')
                    scaler.scale(loss/len(batches)).backward()
                    for k in terms:batch_terms[k]+=float(terms[k].detach())/len(batches)
                previous_scale=scaler.get_scale();scaler.step(optimizer);scaler.update()
                if scaler.get_scale()>=previous_scale:break
            else:raise FloatingPointError('AMP repeatedly overflowed; no optimizer update counted')
            step+=1;scheduler.committed()
            if step%50==0 or step==start_step+1:
                status=dict(status='TRAINING_SESSION',optimizerStep=step,stopAtStep=target,minQualityScheduleSteps=100000,
                    nextSampleIndex=step*32,lossTerms=batch_terms,lr=optimizer.param_groups[0]['lr'],
                    elapsedSeconds=elapsed_previous+time.perf_counter()-start,bncNeuralAvailable=False,safeToPowerOff=False)
                publish_status(args.out,status);print(json.dumps(status),flush=True)
            if step%args.checkpoint_every==0:
                persist(args.out,snapshot(),periodic=True)
            if step%1000==0 and step!=target and not requested and not stop_file.exists():
                rows,aggregate=validate(model,cases,'cuda',True);aggregate['step']=step;history.append(aggregate)
                atomic_json(args.out/'validation'/f'{step:06d}.json',dict(step=step,aggregate=aggregate,cases=rows))
                provenance.update(trainingSteps=step,exportDate=datetime.datetime.now(datetime.timezone.utc).isoformat())
                improved=aggregate['selectionScore']<best
                if improved:
                    best=aggregate['selectionScore'];export_reference(model,args.out/'best.bncmodel',provenance)
                    torch.save(dict(model=model.state_dict(),step=step,provenance=provenance),args.out/'best-fp32.pt')
                persist(args.out,snapshot(),periodic=step%args.checkpoint_every==0,best=improved)
                atomic_json(args.out/'validation-history.json',history)
        # Checkpoint comes first; report failure never loses this committed update.
        persist(args.out,snapshot(),periodic=True)
        publish_status(args.out,dict(status='CHECKPOINT_SAVED_VALIDATING',optimizerStep=step,safeToPowerOff=False,
            resumableCheckpoint=str((args.out/'checkpoints/latest.pt').resolve()),bncNeuralAvailable=False))
    finally:
        if hasattr(loader,'_shutdown_workers'):loader._shutdown_workers()
    del loader,model,optimizer,scaler
    torch.cuda.empty_cache()
    status=finish_session(args.out,args.manifest)
    print(json.dumps(status,indent=2),flush=True)
    return status
