"""Scoped diagnostics and fail-closed update checks; no clipping or loss changes."""
import copy
import hashlib
import math
from pathlib import Path

import numpy as np
import torch
from torch.nn import functional as F

from .data import TrainingData, encode
from .losses import reconstruction_loss, diagnostics


class UnsafeUpdate(FloatingPointError):
    def __init__(self, stage, reason):
        self.stage = stage
        super().__init__(reason)


class ResearchRegression(RuntimeError):
    stage = 'quality_regression'


def enforce_guard(screen):
    if not screen['numericalPassed']:
        raise UnsafeUpdate('sentinel_numerical','; '.join(screen['failures']))
    if not screen['regressionPassed']:
        raise ResearchRegression('; '.join(screen['failures']))


def finite(value):
    # Reduce on each device first; avoid one host/GPU synchronization per tensor.
    groups={}
    scalars_ok=True
    def collect(item):
        nonlocal scalars_ok
        if isinstance(item,torch.Tensor):
            if item.is_floating_point() or item.is_complex():
                groups.setdefault(item.device,[]).append(item)
        elif isinstance(item,dict):
            for child in item.values():collect(child)
        elif isinstance(item,(list,tuple)):
            for child in item:collect(child)
        elif isinstance(item,(float,np.floating)):
            scalars_ok=scalars_ok and math.isfinite(item)
    collect(value)
    return scalars_ok and all(bool(torch.stack([torch.isfinite(t).all() for t in group]).all())
                              for group in groups.values())


def serializable(value):
    if isinstance(value, torch.Tensor):
        return serializable(value.detach().cpu().tolist())
    if isinstance(value, np.generic):
        return serializable(value.item())
    if isinstance(value, float) and not math.isfinite(value):
        return str(value)
    if isinstance(value, dict):
        return {k: serializable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [serializable(v) for v in value]
    return value


def peak(value):
    return float(value.detach().abs().amax())


def check_before_update(model, optimizer):
    """Conservatively reject overflow-prone FP32 AdamW squares, never rescale."""
    if not finite((model.state_dict(),optimizer.state_dict()['state'])):
        raise UnsafeUpdate('pre_update_state', 'nonfinite model or optimizer before update')
    gradients = [p.grad for p in model.parameters() if p.grad is not None]
    if not gradients or not finite(gradients):
        raise UnsafeUpdate('gradient', 'missing or nonfinite gradients')
    for group in optimizer.param_groups:
        beta2 = group['betas'][1]
        grads=[p.grad for p in group['params'] if p.grad is not None]
        if not grads:
            continue
        if any(g.dtype!=torch.float32 for g in grads):
            raise UnsafeUpdate('precision','research updates require FP32 gradients')
        maximum=float(torch.stack([g.detach().abs().amax() for g in grads]).amax())
        limit=torch.finfo(torch.float32).max
        if maximum>math.sqrt(limit):
            raise UnsafeUpdate('adamw_square','finite gradient exceeds safe FP32 square range')
        moments=[optimizer.state[p]['exp_avg_sq'] for p in group['params']
                 if p.grad is not None and 'exp_avg_sq' in optimizer.state.get(p,{})]
        old=float(torch.stack([m.detach().amax() for m in moments]).amax()) if moments else 0.
        if moments and bool(torch.stack([m.detach().amin()<0 for m in moments]).any()):
            raise UnsafeUpdate('adamw_moment','negative AdamW second moment')
        if beta2*old+(1-beta2)*maximum**2>limit:
            raise UnsafeUpdate('adamw_moment','prospective second moment exceeds FP32 range')
    return float(torch.stack([g.detach().abs().amax() for g in gradients]).amax())


def checked_step(model, optimizer):
    maximum = check_before_update(model, optimizer)
    optimizer.step()
    if not finite((model.state_dict(),optimizer.state_dict()['state'])):
        raise UnsafeUpdate('post_update_state', 'nonfinite state after optimizer update; do not checkpoint')
    return maximum


def gate(a, b, variant):
    if variant == 'A':
        return a*b
    if variant == 'B':
        return a*torch.tanh(b)
    if variant in ('C', 'N'):
        return 16*torch.tanh((a*b)/16)
    if variant == 'S':
        return .5*(F.silu(a)+F.silu(b))
    raise ValueError('unknown gate variant')


class ScopedObserver:
    """Only forwards while these hooks are installed belong to this scope."""
    def __init__(self, model, scope):
        self.scope = scope
        self.blocks = {}
        self.output_peak = None
        self.handles = []
        for name, block in model.named_modules():
            if hasattr(block, 'variant') and hasattr(block, 'expand'):
                self.handles.append(block.expand.register_forward_hook(self.hook(name, block.variant)))
        self.handles.append(model.register_forward_hook(self.output_hook))

    def hook(self, name, variant):
        def observe(module, inputs, output):
            a, b = output.detach().chunk(2, 1)
            actual = gate(a, b, variant)
            raw = a*b if variant != 'S' else None
            previous = self.blocks.get(name, {})
            values = dict(branchMax=torch.maximum(a.abs().amax(),b.abs().amax()), gateMax=actual.abs().amax())
            if raw is not None:
                values['rawProductMax'] = raw.abs().amax()
            self.blocks[name] = {key:torch.maximum(previous[key],value) if key in previous else value
                                for key,value in values.items()}
        return observe

    def output_hook(self, module, inputs, output):
        value=output.detach().abs().amax()
        self.output_peak=value if self.output_peak is None else torch.maximum(self.output_peak,value)

    def result(self):
        blocks={name:{key:float(value) for key,value in row.items()} for name,row in self.blocks.items()}
        return dict(scope=self.scope,blocks=blocks,
                    modelOutputMax=float(self.output_peak) if self.output_peak is not None else 0.,
                    maxBranchActivation=max((v['branchMax'] for v in blocks.values()), default=0.),
                    maxActualGateOutput=max((v['gateMax'] for v in blocks.values()), default=0.),
                    maxRawProduct=max((v['rawProductMax'] for v in blocks.values() if 'rawProductMax' in v), default=None))

    def close(self):
        for handle in self.handles:
            handle.remove()


def probe(model, optimizer, packed, truth, scope, backward=True):
    """Model/AdamW copies only: original parameters, gradients, mode and state survive."""
    clone = copy.deepcopy(model).eval()
    for block in clone.modules():
        # Diagnostic closures on the live model must not receive copied forwards.
        block._forward_hooks.clear()
        block._forward_pre_hooks.clear()
        block._backward_hooks.clear()
        for key in ('peak_branch', 'peak_gate'):
            if hasattr(block, key):
                setattr(block, key, None)
    device = next(clone.parameters()).device
    a, b = packed.to(device).float(), truth.to(device).float()
    if a.ndim == 3:
        a, b = a.unsqueeze(0), b.unsqueeze(0)
    observer = ScopedObserver(clone, scope)
    try:
        with torch.autocast(device_type=device.type, enabled=False):
            output = clone(a)
            loss, terms, rgb = reconstruction_loss(a, output, b, True)
        result = dict(scope=scope, finite=finite((a,b,output,loss,terms,rgb)),
                      outputMax=peak(output), loss=float(loss.detach()), maxGradient=None,
                      optimizerPreflightPassed=None, optimizerAfterProbeFinite=None)
        if result['finite']:
            result['metrics'] = diagnostics(a, output, b, True)
            result['finite'] = finite(result['metrics'])
        if backward and result['finite']:
            loss.backward()
            gradients = [p.grad for p in clone.parameters() if p.grad is not None]
            result['finite'] = finite(gradients)
            result['maxGradient'] = max((peak(g) for g in gradients), default=None)
            if optimizer is not None and result['finite']:
                copied = torch.optim.AdamW(clone.parameters(), lr=.001, betas=(.9,.99), weight_decay=1e-4)
                copied.load_state_dict(copy.deepcopy(optimizer.state_dict()))
                try:
                    check_before_update(clone, copied)
                    result['optimizerPreflightPassed'] = True
                    checked_step(clone, copied)
                    result['optimizerAfterProbeFinite'] = True
                except UnsafeUpdate as error:
                    result.update(optimizerPreflightPassed=False if error.stage != 'post_update_state' else True,
                                  optimizerAfterProbeFinite=False if error.stage == 'post_update_state' else None,
                                  rejectedStage=error.stage, rejectedReason=str(error))
        result['activation'] = observer.result()
        result.update(maxBranchActivation=result['activation']['maxBranchActivation'],
                      maxGateOutput=result['activation']['maxActualGateOutput'])
        return serializable(result)
    finally:
        observer.close()


def stress_probe(model, optimizer, train_records=()):
    a,b = TrainingData(train_records, 1, 823109, 1009779)[0]
    result = probe(model, optimizer, a,b, 'known_stress_single_sample', True)
    result['sampleIndex'] = 1009779
    result['optimizerProbeScope'] = 'independent copy; single sample, not the original training batch'
    return result


def edge_input(archive, expected_sha):
    archive = Path(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != expected_sha:
        raise ValueError('frozen validation archive changed')
    with np.load(archive, allow_pickle=False) as content:
        rgb = content['rgb_16']
        if rgb.shape != (256,256,3) or rgb.dtype != np.float32 or not np.isfinite(rgb).all() or rgb.min()<0 or rgb.max()>4:
            raise ValueError('invalid frozen edge')
        return encode(rgb, 'BGGR')


def guard(current, baseline=None, factor=4.):
    """Versioned catastrophic-regression research stop, not a release quality gate."""
    numerical_reasons, quality_reasons = [], []
    stress, edge = current['stress'], current['edge']
    if not stress['finite'] or stress.get('optimizerPreflightPassed') is not True:
        numerical_reasons.append('known stress fails finite/AdamW preflight')
    if not edge['finite']:
        numerical_reasons.append('frozen edge nonfinite')
    if baseline:
        for name, value, reference in (
            ('stress loss', stress['loss'], baseline['stress']['loss']),
            ('edge missing RGB', edge.get('metrics',{}).get('missing_rgb_l1'),
             baseline['edge'].get('metrics',{}).get('missing_rgb_l1'))):
            if not isinstance(value,(int,float)) or not math.isfinite(value) or value > factor*max(reference, 1e-8):
                quality_reasons.append(f'{name} exceeds {factor:g}x frozen research baseline')
    return dict(version='catastrophic_regression_v1', factor=factor,
                passed=not numerical_reasons and not quality_reasons,
                numericalPassed=not numerical_reasons,regressionPassed=not quality_reasons,
                numericalFailures=numerical_reasons,qualityFailures=quality_reasons,
                failures=numerical_reasons+quality_reasons,releaseQualification=False)
