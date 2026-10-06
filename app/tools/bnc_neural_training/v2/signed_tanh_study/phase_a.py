"""Zero-update signed-gate qualification for distribution-derived tau values."""
import hashlib
import json
from pathlib import Path

import torch
from torch.utils.data import default_collate

from ..checkpoint import load, objective_identity
from ..data import TrainingData, read_corpus
from ..evaluate import synthetic_gate
from ..losses import reconstruction_loss
from ..model import BncNeuralV2
from ..session_report import comparison
from ..train import atomic_json, validate
from ..validation_freeze import cases as fixed_cases
from .distribution import SOURCE, ROOT, SOURCE_SHA
from .model import SignedModel


TAUS=(16,32,64)


def finite(value):
    if isinstance(value,torch.Tensor):
        return bool(torch.isfinite(value).all()) if value.is_floating_point() else True
    if isinstance(value,dict):return all(finite(v) for v in value.values())
    if isinstance(value,(tuple,list)):return all(finite(v) for v in value)
    return True


class GateDifference:
    def __init__(self):
        self.count=0
        self.over=[0,0,0]
        self.sum_abs_difference=0.
        self.sum_abs_original=0.
        self.max_abs_difference=0.

    def __call__(self,name,a,b,gate):
        raw=(a.detach()*b.detach())
        difference=(gate.detach()-raw).abs()
        amplitude=raw.abs()
        self.count+=raw.numel()
        self.sum_abs_difference+=float(difference.sum())
        self.sum_abs_original+=float(amplitude.sum())
        self.max_abs_difference=max(self.max_abs_difference,float(difference.max()))
        for i,threshold in enumerate((.01,.05,.10)):
            self.over[i]+=int((difference>threshold*amplitude).sum())

    def result(self):
        return dict(values=self.count,over1Percent=self.over[0]/self.count,
                    over5Percent=self.over[1]/self.count,
                    over10Percent=self.over[2]/self.count,
                    relativeL1Difference=self.sum_abs_difference/self.sum_abs_original,
                    maxAbsDifference=self.max_abs_difference)


def model_at_source(tau,state):
    model=SignedModel(tau)
    model.load_state_dict(state['model'],strict=True)
    return model


def main():
    ROOT.mkdir(parents=True,exist_ok=True)
    if hashlib.sha256(SOURCE.read_bytes()).hexdigest()!=SOURCE_SHA:
        raise ValueError('source checkpoint changed')
    torch.set_num_threads(2)
    torch.backends.cudnn.benchmark=False
    torch.backends.cudnn.deterministic=True
    torch.backends.cuda.matmul.allow_tf32=False
    torch.backends.cudnn.allow_tf32=False
    torch.use_deterministic_algorithms(True)
    state=load(SOURCE)
    if state['step']!=31555 or state['modelIdentity']!=BncNeuralV2().config.description() or state['objectiveIdentity']!=objective_identity():
        raise ValueError('original W24 checkpoint identity changed')
    parts,corpus=read_corpus(Path('build/bnc-neural-v2/corpus/corpus.json'))
    if corpus['sha256']!=state['datasetIdentity']['sha256']:raise ValueError('dataset/split changed')
    cases=fixed_cases(parts['validation'],823109,state['provenance'])
    distribution=json.loads((ROOT/'distribution.json').read_text())
    normal_max=max(distribution['normal'][name][branch]['max'] for name in distribution['normal'] for branch in ('a','b'))
    normal_tail=max(distribution['normal'][name][branch]['P99.999'] for name in distribution['normal'] for branch in ('a','b'))
    if not 4<normal_tail<5 or not 4<normal_max<5:
        raise ValueError('tau bracket must be reconsidered from measured activation distribution')
    rationale=dict(normalP99999Max=normal_tail,normalAbsoluteMax=normal_max,
                   chosenTau=list(TAUS),
                   rule='tau32 is first power-of-two >= normal max/sqrt(0.03), giving <=~1% tanh branch attenuation at normal maximum; tau16/64 bracket earlier/later extreme intervention',
                   extremeOnset='failed sample branch max >9 at shared3, >26 at shared4, >231 at shared5, then >4e3 at opponent0')
    atomic_json(ROOT/'tau-rationale.json',rationale)
    original=model_at_source(None,state)
    original_rows,original_aggregate=validate(original,cases,'cpu',True)
    original_synthetic,_=comparison(original)
    atomic_json(ROOT/'source-zero-update.json',dict(validation=dict(aggregate=original_aggregate,cases=original_rows),
                synthetic=original_synthetic,syntheticGate=synthetic_gate(original_synthetic)))
    batch_data=TrainingData(parts['train'],32,823109,1009760)
    packed,truth=default_collate([batch_data[i] for i in range(32)])
    results={}
    for tau in TAUS:
        model=model_at_source(tau,state).cuda()
        difference=GateDifference()
        for block in model.blocks():block.gate_observer=difference
        rows,aggregate=validate(model,cases,'cuda',True)
        normal_difference=difference.result()
        for block in model.blocks():
            block.gate_observer=None
            block.max_input=0.
            block.max_gate=0.
        sample_x,sample_y=packed[19:20].cuda(),truth[19:20].cuda()
        failure=None
        try:
            with torch.no_grad(),torch.autocast('cuda',enabled=False):
                output=model(sample_x.float())
                loss,terms,rgb=reconstruction_loss(sample_x.float(),output,sample_y.float(),True)
            if not finite((output,loss,terms,rgb)):
                raise FloatingPointError('failed sample forward/loss nonfinite')
            sample_output_max=float(output.detach().abs().max())
            sample_loss=float(loss.detach())
            peaks=model.peaks()
            model.zero_grad(set_to_none=True)
            all_x,all_y=packed.cuda(),truth.cuda()
            with torch.autocast('cuda',enabled=False):
                full_output=model(all_x.float())
                full_loss,full_terms,full_rgb=reconstruction_loss(all_x.float(),full_output,all_y.float(),True)
            if not finite((full_output,full_loss,full_terms,full_rgb)):
                raise FloatingPointError('failed batch dry forward/loss nonfinite')
            full_loss.backward()
            gradients=[p.grad for p in model.parameters() if p.grad is not None]
            if not finite(gradients):
                raise FloatingPointError('failed batch dry backward nonfinite')
            max_gradient=max(float(g.detach().abs().max()) for g in gradients)
            batch_loss=float(full_loss.detach())
        except FloatingPointError as exc:
            failure=str(exc)
            sample_output_max=sample_loss=max_gradient=batch_loss=None
            peaks=model.peaks()
        model.cpu().eval()
        torch.cuda.empty_cache()
        synthetic,_=comparison(model)
        gate=synthetic_gate(synthetic)
        result=dict(tau=tau,sourceStep=31555,optimizerUpdates=0,
                    validation=dict(aggregate=aggregate,cases=rows),
                    normalGateDifference=normal_difference,
                    failedSampleIndex=1009779,failedSceneType='thin_lines',
                    failedSampleOutputMax=sample_output_max,failedSampleLoss=sample_loss,
                    failedBatchLoss=batch_loss,maxGradient=max_gradient,
                    maxGateInput=peaks['maxGateInput'],maxGateOutput=peaks['maxGateOutput'],
                    numericalFailure=failure,synthetic=synthetic,syntheticGate=gate)
        out=ROOT/f'tau{tau}'
        out.mkdir(exist_ok=True)
        atomic_json(out/'zero-update.json',result)
        results[str(tau)]=dict(validationScore=aggregate['selectionScore'],
                               failedSampleOutputMax=sample_output_max,failedSampleLoss=sample_loss,
                               maxGradient=max_gradient,maxGateInput=peaks['maxGateInput'],
                               maxGateOutput=peaks['maxGateOutput'],numericalFailure=failure,
                               normalGateDifference=normal_difference,
                               syntheticGateFailures=len(gate['failures']))
        atomic_json(ROOT/'zero-update-summary.json',results)
        print(json.dumps(dict(phase='A',tau=tau,**results[str(tau)])),flush=True)
    print(json.dumps(dict(phase='A complete',sourceScore=original_aggregate['selectionScore'],
                          results=results)),flush=True)


if __name__=='__main__':main()
