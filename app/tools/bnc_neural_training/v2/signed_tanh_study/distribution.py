"""Stream per-block |a|/|b| tails across every frozen validation case."""
import hashlib
import json
from pathlib import Path

import numpy as np
import torch

from ..checkpoint import load, objective_identity
from ..data import TrainingData, read_corpus
from ..model import BncNeuralV2
from ..validation_freeze import cases as fixed_cases
from .model import SignedModel

SOURCE=Path('build/bnc-neural-v2/diagnostics/checkpoint_failure_step_031555_resumable.pt')
ROOT=Path('build/bnc-neural-v2/signed-tanh-study')
SOURCE_SHA='c8e991675d3a2df67ee03d4561e0302115573e3a4df0d38a47b1f822dd513bfb'
PROB=(.99,.999,.9999,.99999)
MIN_LOG,MAX_LOG,BINS=-12.,32.,65536


class Histogram:
    def __init__(self):
        self.count=0
        self.underflow=0
        self.hist=np.zeros(BINS,dtype=np.int64)
        self.maximum=0.

    def add(self,v):
        value=v.detach().abs().flatten()
        if not bool(torch.isfinite(value).all()):
            raise FloatingPointError('nonfinite gate branch input')
        self.count+=value.numel()
        self.maximum=max(self.maximum,float(value.max()))
        positive=value[value>0]
        logs=torch.log10(positive)
        self.underflow+=value.numel()-positive.numel()+int((logs<MIN_LOG).sum())
        # CUDA histc uses nondeterministic atomics. Keep diagnostics deterministic
        # without changing neural arithmetic or the training determinism policy.
        self.hist+=np.histogram(logs.cpu().numpy(),bins=BINS,range=(MIN_LOG,MAX_LOG))[0]

    def summary(self):
        cdf=np.cumsum(self.hist)
        result={}
        for p in PROB:
            rank=p*(self.count-1)
            if rank<self.underflow:
                estimate=0.
            else:
                bin_index=int(np.searchsorted(cdf,rank-self.underflow,side='left'))
                if bin_index>=BINS:
                    raise ValueError('histogram range insufficient for tail')
                left=0 if bin_index==0 else cdf[bin_index-1]
                bin_count=self.hist[bin_index]
                fraction=(rank-self.underflow-left)/bin_count if bin_count else 0.
                estimate=10**(MIN_LOG+(bin_index+fraction)*(MAX_LOG-MIN_LOG)/BINS)
            # Interpolation in the terminal bin can slightly exceed the exact
            # observed maximum; a percentile must remain within the data range.
            result[{.99:'P99',.999:'P99.9',.9999:'P99.99',.99999:'P99.999'}[p]]=min(estimate,self.maximum)
        result.update(max=self.maximum,count=self.count,
                      method='streaming log histogram; 65536 bins from 1e-12 to 1e32; <=0.155% multiplicative bin width')
        return result


def exact_summary(v):
    values=v.detach().abs().flatten().cpu().numpy().astype(np.float64)
    if not np.isfinite(values).all():raise FloatingPointError('nonfinite failed-sample branch input')
    result={name:float(np.quantile(values,p)) for name,p in
            (('P99',.99),('P99.9',.999),('P99.99',.9999),('P99.999',.99999))}
    result.update(max=float(values.max()),count=len(values),method='exact NumPy quantile')
    return result


def main():
    ROOT.mkdir(parents=True,exist_ok=True)
    if hashlib.sha256(SOURCE.read_bytes()).hexdigest()!=SOURCE_SHA:
        raise ValueError('source checkpoint SHA changed')
    torch.set_num_threads(2)
    torch.backends.cudnn.benchmark=False
    torch.backends.cudnn.deterministic=True
    torch.backends.cuda.matmul.allow_tf32=False
    torch.backends.cudnn.allow_tf32=False
    torch.use_deterministic_algorithms(True)
    state=load(SOURCE)
    if state['step']!=31555 or state['modelIdentity']!=BncNeuralV2().config.description() or state['objectiveIdentity']!=objective_identity():
        raise ValueError('source W24 identity mismatch')
    parts,corpus=read_corpus(Path('build/bnc-neural-v2/corpus/corpus.json'))
    if corpus['sha256']!=state['datasetIdentity']['sha256']:raise ValueError('dataset changed')
    cases=fixed_cases(parts['validation'],823109,state['provenance'])
    model=SignedModel(None).cuda().eval()
    model.load_state_dict(state['model'],strict=True)
    normal={block.name:{'a':Histogram(),'b':Histogram()} for block in model.blocks()}
    def normal_observer(name,a,b):
        normal[name]['a'].add(a)
        normal[name]['b'].add(b)
    for block in model.blocks():block.observer=normal_observer
    with torch.no_grad():
        for index,(name,packed,truth) in enumerate(cases):
            output=model(packed.unsqueeze(0).cuda())
            if not bool(torch.isfinite(output).all()):raise FloatingPointError('normal validation nonfinite: '+name)
            if (index+1)%20==0:print(json.dumps(dict(validationCasesScanned=index+1,total=len(cases))),flush=True)
    normal_result={name:{branch:normal[name][branch].summary() for branch in ('a','b')} for name in normal}
    data=TrainingData(parts['train'],1,823109,1009779)
    packed,_=data[0]
    failed={}
    def failed_observer(name,a,b):
        failed[name]=dict(a=exact_summary(a),b=exact_summary(b))
    for block in model.blocks():block.observer=failed_observer
    with torch.no_grad():
        output=model(packed.unsqueeze(0).cuda())
    if not bool(torch.isfinite(output).all()):raise FloatingPointError('failed sample original forward nonfinite')
    result=dict(sourceCheckpoint=str(SOURCE.resolve()),sourceSHA256=SOURCE_SHA,
                step=state['step'],normalValidationCases=len(cases),failedSampleIndex=1009779,
                normal=normal_result,failedSample=failed,
                originalFailedSampleOutputMax=float(output.abs().max()),
                quantileMethod='normal: 0.155%-bin log histogram; failed sample: exact NumPy quantile')
    (ROOT/'distribution.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(distributionSaved=str((ROOT/'distribution.json').resolve()),
                          originalFailedSampleOutputMax=result['originalFailedSampleOutputMax'])),flush=True)


if __name__=='__main__':main()
