"""Complete atomic session checkpoints; data resume uses committed sample indices."""
from pathlib import Path
import hashlib
import os
import random
import numpy as np
import torch

SCHEMA=2
CRITICAL=('contracts.py','model.py','losses.py','corpus.py','v2/model.py','v2/losses.py','v2/data.py','v2/procedural.py')


def objective_identity():
    base=Path(__file__).resolve().parent.parent
    return {name:hashlib.sha256((base/name).read_bytes()).hexdigest() for name in CRITICAL}


def rng_state():
    n=np.random.get_state()
    return dict(python=random.getstate(),numpy=dict(kind=n[0],keys=n[1].tolist(),position=n[2],hasGaussian=n[3],cachedGaussian=n[4]),
        torchCPU=torch.get_rng_state(),torchCUDA=torch.cuda.get_rng_state_all() if torch.cuda.is_available() else [])


def restore_rng(state):
    random.setstate(state['python']);n=state['numpy']
    np.random.set_state((n['kind'],np.array(n['keys'],dtype=np.uint32),n['position'],n['hasGaussian'],n['cachedGaussian']))
    torch.set_rng_state(state['torchCPU'].cpu())
    if state['torchCUDA']:
        if len(state['torchCUDA'])!=torch.cuda.device_count():raise ValueError('CUDA RNG device-count mismatch')
        torch.cuda.set_rng_state_all([s.cpu() for s in state['torchCUDA']])


class GlobalCosine:
    """Explicit scheduler state with the unchanged original global-step formula."""
    def __init__(self,optimizer,next_step=0):self.optimizer=optimizer;self.next_step=next_step
    @staticmethod
    def value(step):return float(3e-5+(1e-3-3e-5)*.5*(1+np.cos(np.pi*min(step,100000)/100000)))
    def apply(self,step):
        if step!=self.next_step:raise ValueError('scheduler/sample global-step drift')
        for group in self.optimizer.param_groups:group['lr']=self.value(step)
    def committed(self):self.next_step+=1
    def state_dict(self):
        return dict(name='global_step_cosine_v1',nextStep=self.next_step,decaySteps=100000,initialLR=.001,finalLR=.00003,
            lastAppliedStep=self.next_step-1,lastLR=[g['lr'] for g in self.optimizer.param_groups])
    def load_state_dict(self,state):
        if (state['name'],state['decaySteps'],state['initialLR'],state['finalLR'])!=('global_step_cosine_v1',100000,.001,.00003):
            raise ValueError('scheduler identity mismatch')
        self.next_step=state['nextStep']
        if [g['lr'] for g in self.optimizer.param_groups]!=state['lastLR']:raise ValueError('optimizer/scheduler LR mismatch')


def capture(model,optimizer,scaler,scheduler,step,history,best,provenance,elapsed,args,loader_generator=None):
    if torch.cuda.is_available():torch.cuda.synchronize()
    if scheduler.next_step!=step:raise ValueError('checkpoint scheduler step mismatch')
    arguments={k:str(v) if isinstance(v,Path) else v for k,v in vars(args).items()}
    return dict(schema=SCHEMA,model=model.state_dict(),optimizer=optimizer.state_dict(),scaler=scaler.state_dict(),
        scheduler=scheduler.state_dict(),step=step,globalOptimizerStep=step,
        sampler=dict(name='stateless_index_hash_v1',nextSampleIndex=step*32,effectiveBatch=32,seed=args.seed,
            epoch=None,workerPrefetchPolicy='discard uncommitted prefetch; replay exact indexed samples',
            loaderGeneratorState=loader_generator.get_state() if loader_generator is not None else None),
        rng=rng_state(),history=history,best=best,provenance=provenance,elapsed=elapsed,
        modelIdentity=model.config.description(),objectiveIdentity=objective_identity(),
        datasetIdentity=provenance['trainingCorpus'],trainingArguments=arguments,
        bestValidationState=dict(score=best,history=history),checkpointBoundary='after completed optimizer update; before consuming next batch')


def save(path,state):
    # The legacy LR formula returns np.float64; keep values but use safe primitives.
    def primitives(value):
        if isinstance(value,np.generic):return value.item()
        if isinstance(value,dict):return {k:primitives(v) for k,v in value.items()}
        if isinstance(value,list):return [primitives(v) for v in value]
        if isinstance(value,tuple):return tuple(primitives(v) for v in value)
        return value
    state=primitives(state)
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_suffix(path.suffix+'.partial')
    with temp.open('wb') as stream:
        torch.save(state,stream);stream.flush();os.fsync(stream.fileno())
    temp.replace(path)
    sha=hashlib.sha256(path.read_bytes()).hexdigest()
    sidecar=path.with_suffix(path.suffix+'.sha256');partial=sidecar.with_suffix(sidecar.suffix+'.partial')
    with partial.open('w') as stream:stream.write(sha+'\n');stream.flush();os.fsync(stream.fileno())
    partial.replace(sidecar)


def load(path):
    path=Path(path)
    if hashlib.sha256(path.read_bytes()).hexdigest()!=path.with_suffix(path.suffix+'.sha256').read_text().strip():
        raise ValueError('checkpoint SHA mismatch')
    state=torch.load(path,map_location='cpu',weights_only=True)
    if state.get('schema')!=SCHEMA:raise ValueError('full session checkpoint required')
    if state['step']!=state['scheduler']['nextStep'] or state['sampler']['nextSampleIndex']!=state['step']*32:
        raise ValueError('checkpoint committed-position mismatch')
    return state


def persist(run,state,periodic=False,best=False):
    directory=Path(run)/'checkpoints'
    save(directory/'latest.pt',state)
    if best:save(directory/'best.pt',state)
    if periodic:
        save(directory/f"checkpoint_step_{state['step']:06d}.pt",state)
        # Only task-owned checkpoint files under the resolved run directory.
        directory=directory.resolve()
        for obsolete in sorted(directory.glob('checkpoint_step_*.pt'))[:-2]:
            if obsolete.resolve().parent!=directory:raise ValueError('checkpoint retention path escape')
            obsolete.unlink();obsolete.with_suffix('.pt.sha256').unlink(missing_ok=True)


def capture_legacy_live(namespace):
    """One-time debugger call at the old loop's post-update boundary; no step lost."""
    import time
    s=namespace;step=s['step'];scheduler=GlobalCosine(s['optimizer'],step)
    elapsed=s['elapsed_previous']+time.perf_counter()-s['start']
    state=capture(s['model'],s['optimizer'],s['scaler'],scheduler,step,s['history'],s['best'],
        s['provenance'],elapsed,s['args'])
    state['migration']='live old-loop capture after completed update; no optimization or data change'
    path=s['args'].out/'checkpoints'/'latest.pt';save(path,state)
    save(s['args'].out/'checkpoints'/f'checkpoint_step_{step:06d}.pt',state)
    return dict(step=step,path=str(path.resolve()))
