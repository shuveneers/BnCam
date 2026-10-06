"""Identified N continuation from reviewed 100k; unchanged training loop, fixed endpoint LR."""
import argparse
import copy
from contextlib import contextmanager
import hashlib
import json
import os
import platform
from pathlib import Path
import random
import signal
import time
from types import SimpleNamespace

import numpy as np
import torch
from torch.utils.data import DataLoader

from ..checkpoint import capture, load, save, GlobalCosine, objective_identity, rng_state, restore_rng
from ..data import read_corpus, TrainingData
from ..losses import diagnostics, reconstruction_loss
from ..train import atomic_json, validate
from ..training_safety import (UnsafeUpdate, ResearchRegression, enforce_guard,
                              ScopedObserver, checked_step, finite,
                              serializable, probe, stress_probe, edge_input, guard)
from ..validation_freeze import cases as fixed_cases
from .model import ResearchModel
from . import run as original

ROOT=Path('build/bnc-neural-v2/v4-extension-low-lr')
SOURCE=Path('build/bnc-neural-v2/v4-research/N/checkpoints/stop_step_100000.pt')
SOURCE_SHA='71a0c53ef42625df863b8b63dc482ba72df6207002c1f9a7287bb821c37f3c1b'
MAX_STEP=200000
MAX_SESSION=25000
MANIFEST=Path('build/bnc-neural-v2/corpus/corpus.json')
ARCHIVE=Path('build/bnc-neural-v2/training/w24/fixed-validation-procedural.npz')
ARCHIVE_SHA='62c51c87018c755b700f10a1624998e25e7a023cb9652041016212a1fac2b00b'
SEED=823109
MICROBATCH=32
WORKERS=4
FIRST_STOP=2000
CHECK_EVERY=250
GUARD_FACTOR=4.


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def continuation_contract():
    return dict(version='BNC_NEURAL_V4_N_LOW_LR_EXTENSION_V1',
        sourceStep=100000,sourceCheckpointSHA256=SOURCE_SHA,
        sourceRelativePath='build/bnc-neural-v2/v4-research/N/checkpoints/stop_step_100000.pt',
        maxStep=MAX_STEP,maxSessionUpdates=MAX_SESSION,
        learningRateAfter100k=3e-5,schedule='unchanged global cosine, clamped at its original endpoint',
        optimizer='preserved AdamW including moments and per-parameter steps',
        sampler='preserved stateless indices; first new batch starts at sample 3200000',
        lossAndData='unchanged',productReady=False)


def code_identity():
    identity=copy.deepcopy(original.code_identity())
    identity['extension']=dict(codeSHA256=sha(__file__),contract=continuation_contract())
    return identity


def verify_source(state,corpus,device):
    original.verify(state,'N',corpus)
    if state['step']!=100000 or state['globalOptimizerStep']!=100000:
        raise ValueError('extension requires the completed reviewed N 100000 checkpoint')
    if state['sampler']['nextSampleIndex']!=3200000:
        raise ValueError('100k source sampler changed')
    opt=torch.optim.AdamW(ResearchModel('N').parameters(),lr=.001,betas=(.9,.99),weight_decay=1e-4)
    opt.load_state_dict(state['optimizer'])
    schedule=GlobalCosine(opt);schedule.load_state_dict(state['scheduler'])
    if state['provenance'].get('continuation') is not None:
        raise ValueError('source must be the original 100k run, not a previous extension')
    if device=='cuda':
        environment=state['provenance']['environment']
        current=dict(torchVersion=str(torch.__version__),cudaVersion=torch.version.cuda,
            cudnnVersion=torch.backends.cudnn.version(),gpuName=torch.cuda.get_device_name())
        if any(environment.get(k)!=v for k,v in current.items()):
            raise ValueError('CUDA environment differs from reviewed source; review before continuation')


@contextmanager
def session_lock(root):
    """One research process, including preparation/evaluation, per output root."""
    root.mkdir(parents=True,exist_ok=True)
    stream=(root/'.research-session.lock').open('a+b')
    locked=False
    try:
        if os.name=='nt':
            import msvcrt
            stream.seek(0)
            if not stream.read(1):
                stream.write(b'0');stream.flush()
            stream.seek(0)
            msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
        locked=True
        yield
    finally:
        if locked:
            if os.name=='nt':
                stream.seek(0);msvcrt.locking(stream.fileno(),msvcrt.LK_UNLCK,1)
            else:
                fcntl.flock(stream.fileno(),fcntl.LOCK_UN)
        stream.close()


def setup(device):
    os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG',':4096:8')
    torch.set_num_threads(2)
    torch.use_deterministic_algorithms(True)
    torch.backends.cudnn.benchmark=False
    torch.backends.cudnn.deterministic=True
    torch.backends.cuda.matmul.allow_tf32=False
    torch.backends.cudnn.allow_tf32=False
    if device=='cuda' and not torch.cuda.is_available():
        raise RuntimeError('CUDA unavailable; no automatic CPU training fallback')


def verify(state,candidate,corpus):
    if state['modelIdentity']!=ResearchModel(candidate).config.description():
        raise ValueError('V4 candidate identity mismatch; v3 resumes are forbidden')
    if state['objectiveIdentity']!=objective_identity() or state['provenance']['researchCodeIdentity']!=code_identity():
        raise ValueError('research/model/objective code changed; create a new identified research run')
    if state['datasetIdentity']['sha256']!=corpus['sha256']:
        raise ValueError('corpus changed')
    a=state['trainingArguments']
    if (a['seed'],a['microbatch'],a['workers'],a['variant'],a['checkEvery'])!=(SEED,MICROBATCH,WORKERS,candidate,CHECK_EVERY):
        raise ValueError('training/sample/qualification cadence changed')
    if state['sampler']['nextSampleIndex']!=state['step']*MICROBATCH or state['scheduler']['nextStep']!=state['step']:
        raise ValueError('committed optimizer/sampler boundary changed')
    if not finite(state['model']) or not finite(state['optimizer']['state']):
        raise UnsafeUpdate('resume','nonfinite checkpoint')


def protected_root(root):
    root=original.protected_root(root)
    source_root=original.ROOT.resolve()
    if root==source_root or root.is_relative_to(source_root) or source_root.is_relative_to(root):
        raise ValueError('extension output must be disjoint from the original V4 run')
    return root


def prepare(root,candidate,device,corpus,archive):
    if candidate!='N':
        raise ValueError('only reviewed N may enter this continuation')
    out=root/candidate;latest=out/'checkpoints/latest.pt';identity_path=out/'identity.json'
    if not identity_path.exists():
        if out.exists() and any(out.iterdir()):
            raise ValueError('nonempty extension directory without identity; refusing overwrite')
        if sha(SOURCE)!=SOURCE_SHA:
            raise ValueError('source is not the reviewed 100k checkpoint; no extension created')
        state=load(SOURCE);verify_source(state,corpus,device)
        # Keep every training-state field; only add the identified continuation provenance.
        state=copy.deepcopy(state)
        state['provenance']['originalResearchCodeIdentity']=state['provenance']['researchCodeIdentity']
        state['provenance']['continuation']=continuation_contract()
        state['provenance']['researchCodeIdentity']=code_identity()
        out.mkdir(parents=True,exist_ok=True)
        save(out/'checkpoints/source_step_100000.pt',state)
        save(latest,state)
        save(out/'checkpoints/best.pt',state)
        atomic_json(identity_path,dict(candidate=candidate,modelIdentity=state['modelIdentity'],
            initialWeightsSHA256=state['provenance']['initialWeightsSHA256'],
            sourceCheckpointSHA256=SOURCE_SHA,corpusSHA256=corpus['sha256'],
            codeIdentity=code_identity(),continuation=continuation_contract(),productReady=False))
    identity=json.loads(identity_path.read_text())
    if (identity['candidate']!=candidate or identity['codeIdentity']!=code_identity() or
        identity['corpusSHA256']!=corpus['sha256'] or identity['continuation']!=continuation_contract()):
        raise ValueError('extension identity changed')
    state=load(latest);verify(state,candidate,corpus)
    if state['provenance'].get('continuation')!=continuation_contract():
        raise ValueError('continuation provenance changed')
    if not 100000<=state['step']<=MAX_STEP:
        raise ValueError('extension committed step outside reviewed bounds')
    if device=='cuda':
        environment=state['provenance']['environment']
        current=dict(torchVersion=str(torch.__version__),cudaVersion=torch.version.cuda,
            cudnnVersion=torch.backends.cudnn.version(),gpuName=torch.cuda.get_device_name())
        if any(environment.get(k)!=v for k,v in current.items()):
            raise ValueError('CUDA environment changed during extension')
    model=ResearchModel(candidate).to(device);model.load_state_dict(state['model'],strict=True)
    opt=torch.optim.AdamW(model.parameters(),lr=.001,betas=(.9,.99),weight_decay=1e-4)
    opt.load_state_dict(state['optimizer'])
    scheduler=GlobalCosine(opt);scheduler.load_state_dict(state['scheduler'])
    scaler=torch.amp.GradScaler('cuda',enabled=False);scaler.load_state_dict(state['scaler'])
    generator=torch.Generator();generator.set_state(state['sampler']['loaderGeneratorState'])
    return out,model,opt,scheduler,scaler,generator,state


def sentinels(model,opt,archive):
    a,b=edge_input(archive,ARCHIVE_SHA)
    return dict(stress=stress_probe(model,opt),
                edge=probe(model,None,a,b,'frozen_isoluminant_edge',False))


@contextmanager
def preserved_rng():
    saved=rng_state()
    try:
        yield
    finally:
        restore_rng(saved)


def qualification_resources():
    from ..evaluate import REQUIRED, PERIODS, DETAIL
    root=Path('build/bnc-neural/synthetic')
    resources={}
    for name in REQUIRED+PERIODS+DETAIL+('flat_neutral','color_edge','isoluminant_color_edge'):
        paths=[root/'fixtures'/f'synthetic-{name}'/'ground-truth.rgb.f32']
        paths.extend(root/'demosaic'/f'synthetic-{name}'/f'{route}-lsc-off.rgb.f32' for route in ('malvar','amaze'))
        for path in paths:
            if not path.is_file():
                raise FileNotFoundError(f'qualification resource missing: {path}')
            resources[str(path)]=sha(path)
    v1=Path('build/bnc-neural/training/opponent-v2-full/candidate.bncmodel')
    for path in (v1,v1.with_suffix('.bncmodel.sha256')):
        if not path.is_file():
            raise FileNotFoundError(f'existing comparison resource missing: {path}')
        resources[str(path)]=sha(path)
    return resources


def evaluate_only(out,model,opt,parts,state,archive,device):
    """Full report retry/inspection: checkpoint bytes and failure latch are untouched."""
    from ..session_report import comparison
    from ..evaluate import synthetic_gate
    resources=qualification_resources()
    cases=fixed_cases(parts['validation'],SEED,state['provenance'])
    if len(cases)!=116:
        raise ValueError('full fixed validation changed')
    observer=ScopedObserver(model,'fixed_validation_only')
    with preserved_rng():
        try:
            rows,aggregate=validate(model,cases,device,True)
        finally:
            observer.close()
        checks=sentinels(model,opt,archive)
        synthetic,_=comparison(copy.deepcopy(model).cpu().eval())
    value=dict(step=state['step'],optimizerUpdates=0,checkpointWrites=0,
        validation=dict(cases=rows,aggregate=aggregate),synthetic=synthetic,
        syntheticGate=synthetic_gate(synthetic),sentinels=checks,
        researchGuard=guard(checks,state['provenance'].get('sentinelBaseline'),GUARD_FACTOR),
        activation=observer.result(),qualificationResourceSHA256=resources,
        resourcesMatchTraining=state['provenance'].get('qualificationResourceSHA256') in (None,resources),
        productReady=False,automaticContinuation=False)
    path=out/'evaluation-only'/f'step_{state["step"]:06d}_{time.time_ns()}.json'
    path.parent.mkdir(exist_ok=True)
    atomic_json(path,serializable(value))
    print(str(path.resolve()),flush=True)


def fail(out,error,step,last_valid):
    event=dict(status='RESEARCH_STOP',attemptedStep=step,
        stage=getattr(error,'stage','evaluation_or_runtime'),reason=str(error),
        numericalFailures=1 if isinstance(error,FloatingPointError) else 0,
        qualityFailures=1 if isinstance(error,ResearchRegression) else 0,
        lastValidCheckpoint=str(last_valid.resolve()),productReady=False,
        automaticContinuation=False,safeToPowerOff=True)
    path=out/'failures'/f'{time.time_ns()}.json';path.parent.mkdir(exist_ok=True)
    atomic_json(path,serializable(event));atomic_json(out/'status.json',serializable(event))


def run(candidate,root,device,mode,target,manifest=MANIFEST,archive=ARCHIVE):
    root=protected_root(root)
    setup(device)
    parts,corpus=read_corpus(manifest)
    if not json.loads(Path(manifest).read_text()).get('dedupFinalized'):
        raise ValueError('finalized group-disjoint corpus required')
    # Validate archive before creating a candidate.
    edge_input(archive,ARCHIVE_SHA)
    with session_lock(root):
        out,model,opt,scheduler,scaler,generator,state=prepare(root,candidate,device,corpus,archive)
        step=state['step'];last_valid=out/'checkpoints/latest.pt'
        if mode=='prepare':
            print(json.dumps(dict(candidate=candidate,step=step,initialWeightsSHA256=state['provenance']['initialWeightsSHA256'],
                                  checkpoint=str(last_valid.resolve()),optimizerUpdates=0,productReady=False)),flush=True)
            return
        if mode=='diagnose':
            checks=sentinels(model,opt,archive)
            path=out/f'diagnosis_step_{step:06d}_{time.time_ns()}.json'
            atomic_json(path,serializable(dict(step=step,sentinels=checks,guard=guard(checks),optimizerUpdates=0,productReady=False)))
            print(str(path.resolve()),flush=True)
            return
        if mode=='validate':
            evaluate_only(out,model,opt,parts,state,archive,device)
            return
        if device!='cuda':
            raise ValueError('full corpus training requires explicit CUDA; use unit tests for CPU checks')
        if target is None or not step<target<=min(MAX_STEP,step+MAX_SESSION):
            raise ValueError('extension target must exceed current step, be <=200000, and add <=25000 updates')
        if step<FIRST_STOP and target>FIRST_STOP:
            raise ValueError('first research session must stop at 2000 or earlier for review')
        status=out/'status.json'
        if status.exists() and json.loads(status.read_text())['status']=='RESEARCH_STOP':
            raise ValueError('research stop is latched; diagnose and create a reviewed new run, never silently resume')
        if (out/'sessions'/f'step_{target:06d}').exists():
            raise ValueError('completed session exists; refusing overwrite')
        # Never skip the known failure before allowing a new optimizer update.
        baseline=state['provenance'].get('sentinelBaseline')
        initial=sentinels(model,opt,archive)
        gate=guard(initial,baseline,GUARD_FACTOR)
        if not gate['passed']:
            try:
                enforce_guard(gate)
            except (UnsafeUpdate,ResearchRegression) as error:
                fail(out,error,step,last_valid);raise
        provenance=state['provenance']
        cases=fixed_cases(parts['validation'],SEED,provenance)
        if len(cases)!=116:
            raise ValueError('fixed full-validation case count changed')
        # Fail before training if repo-only evaluators/classical fixtures are unavailable.
        resources=qualification_resources()
        previous_resources=provenance.get('qualificationResourceSHA256')
        if previous_resources is not None and previous_resources!=resources:
            raise ValueError('qualification fixtures/classical baselines changed; no training started')
        provenance['qualificationResourceSHA256']=resources
        history,best=state['history'],state['best']
        args=SimpleNamespace(**state['trainingArguments'])
        start_step=step;start=time.perf_counter();elapsed=state['elapsed']
        restore_rng(state['rng'])
        stop_requested=False
        def request_stop(signum,frame):
            nonlocal stop_requested
            stop_requested=True
        handlers={}
        for name in ('SIGINT','SIGTERM','SIGBREAK'):
            if hasattr(signal,name):
                sig=getattr(signal,name);handlers[sig]=signal.getsignal(sig);signal.signal(sig,request_stop)
        loader=None
        observer=ScopedObserver(model,'training_only')
        train_stats=dict(maxGradient=0.,numericalFailures=0)
        def snapshot():
            return capture(model,opt,scaler,scheduler,step,history,best,provenance,
                           elapsed+time.perf_counter()-start,args,generator)
        def persist(name=None):
            nonlocal last_valid
            snap=snapshot()
            if not finite(snap['model']) or not finite(snap['optimizer']['state']):
                raise UnsafeUpdate('checkpoint','invalid state cannot be resumable')
            if name:
                path=out/'checkpoints'/name
                if path.exists():
                    raise FileExistsError(path)
                save(path,snap)
            save(out/'checkpoints/latest.pt',snap)
            last_valid=out/'checkpoints/latest.pt'
        try:
            before=generator.get_state()
            loader=iter(DataLoader(TrainingData(parts['train'],(target-step)*MICROBATCH,SEED,step*MICROBATCH),
                batch_size=MICROBATCH,shuffle=False,num_workers=WORKERS,pin_memory=True,
                persistent_workers=True,prefetch_factor=2,generator=generator))
            generator.set_state(before)
            model.train()
            while step<target and not stop_requested:
                a,b=next(loader);a,b=a.to(device,non_blocking=True).float(),b.to(device,non_blocking=True).float()
                opt.zero_grad(set_to_none=True);scheduler.apply(step)
                output=model(a);loss,terms,rgb=reconstruction_loss(a,output,b,True)
                if not finite((a,b,output,loss,terms,rgb)):
                    raise UnsafeUpdate('forward','nonfinite inputs/forward/loss')
                loss.backward()
                maximum=checked_step(model,opt)
                step+=1;scheduler.committed()
                train_stats['maxGradient']=max(train_stats['maxGradient'],maximum)
                if step%50==0:
                    print(f'candidate={candidate} step={step} target={target} loss={float(loss.detach()):.7f} finite=true',flush=True)
                if step%CHECK_EVERY==0 or step==target or stop_requested:
                    checks=sentinels(model,opt,archive)
                    screen=guard(checks,baseline,GUARD_FACTOR)
                    audit=out/'sentinels'/f'step_{step:06d}.json';audit.parent.mkdir(exist_ok=True)
                    atomic_json(audit,serializable(dict(step=step,sentinels=checks,guard=screen)))
                    if not screen['passed']:
                        # A numerically finite rejected model is research evidence, not a resume target.
                        save(out/'checkpoints'/f'rejected_step_{step:06d}.pt',snapshot())
                        enforce_guard(screen)
                    if step>=FIRST_STOP:
                        if baseline is None:
                            baseline=copy.deepcopy(checks)
                            baseline['referenceSteps']=dict(stress=step,edge=step)
                        else:
                            if checks['stress']['loss']<baseline['stress']['loss']:
                                baseline['stress']=copy.deepcopy(checks['stress'])
                                baseline['referenceSteps']['stress']=step
                            if checks['edge']['metrics']['missing_rgb_l1']<baseline['edge']['metrics']['missing_rgb_l1']:
                                baseline['edge']=copy.deepcopy(checks['edge'])
                                baseline['referenceSteps']['edge']=step
                        provenance['sentinelBaseline']=baseline
                    persist(f'recovery_step_{step:06d}.pt')
            training_activation=observer.result();observer.close()
            # Evaluation scopes do not share hooks or accumulated peaks with training.
            validation_observer=ScopedObserver(model,'fixed_validation_only')
            try:
                rows,aggregate=validate(model,cases,device,True)
            finally:
                validation_observer.close()
            if not finite(rows):
                raise UnsafeUpdate('validation','nonfinite metrics')
            final=sentinels(model,opt,archive);screen=guard(final,baseline,GUARD_FACTOR)
            if not screen['passed']:
                enforce_guard(screen)
            aggregate['step']=step
            history.append(aggregate)
            improved=aggregate['selectionScore']<best
            if improved:
                best=aggregate['selectionScore']
            # Existing product quality gates, evaluated using repo fixtures; never export V4 weights.
            from ..session_report import comparison
            from ..evaluate import synthetic_gate
            with preserved_rng():
                cpu_model=copy.deepcopy(model).cpu().eval()
                synthetic,_=comparison(cpu_model)
            quality=synthetic_gate(synthetic)
            evidence=dict(candidate=candidate,startStep=start_step,endStep=step,
                validation=dict(cases=rows,aggregate=aggregate),synthetic=synthetic,syntheticGate=quality,
                numerical=dict(training=train_stats,trainingActivation=training_activation,
                    fixedValidationActivation=validation_observer.result(),sentinels=final),
                researchGuard=screen,interrupted=stop_requested and step<target,productReady=False,
                automaticContinuation=False,noPackageExport=True)
            session=out/'sessions'/f'step_{step:06d}';session.mkdir(parents=True,exist_ok=False)
            atomic_json(session/'evidence.json',serializable(evidence))
            text=(f'# BnC Neural V4 low-LR extension {candidate} — step {step}\n\n'
                  f'Fixed validation selection score: {aggregate["selectionScore"]:.9f}.\n'
                  f'Synthetic quality failures: {len(quality["failures"])}.\n'
                  'Research only. No export, Vulkan, or product activation. No automatic continuation.\n')
            (session/'report.md').write_text(text,encoding='utf-8')
            persist(f'stop_step_{step:06d}.pt')
            if improved:
                save(out/'checkpoints/best.pt',snapshot())
            atomic_json(out/'status.json',dict(status='SESSION_STOPPED',step=step,
                checkpoint=str(last_valid.resolve()),report=str((session/'report.md').resolve()),
                safeToPowerOff=True,productReady=False,automaticContinuation=False))
            print(text,flush=True)
        except BaseException as error:
            fail(out,error,step+1 if step<target else step,last_valid)
            raise
        finally:
            observer.close()
            if loader is not None and hasattr(loader,'_shutdown_workers'):
                loader._shutdown_workers()
            for sig,handler in handlers.items():
                signal.signal(sig,handler)


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--candidate',choices=('N',),default='N')
    parser.add_argument('--output-root',type=Path,default=ROOT)
    parser.add_argument('--device',choices=('cuda','cpu'),default='cuda')
    group=parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--prepare-only',action='store_true')
    group.add_argument('--diagnose-only',action='store_true')
    group.add_argument('--validate-only',action='store_true')
    group.add_argument('--stop-at-step',type=int)
    args=parser.parse_args()
    mode='prepare' if args.prepare_only else 'diagnose' if args.diagnose_only else 'validate' if args.validate_only else 'train'
    run(args.candidate,args.output_root,args.device,mode,args.stop_at_step)


if __name__=='__main__':
    main()
