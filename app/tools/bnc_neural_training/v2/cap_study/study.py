"""Three-cap, same-checkpoint FP32 study; 200 steps each, conditional 1k follow-up."""
import copy
import hashlib
import json
import os
import time
from pathlib import Path
from types import SimpleNamespace

import torch
from torch.utils.data import DataLoader, default_collate

from ..checkpoint import GlobalCosine, capture, load, objective_identity, persist, restore_rng
from ..data import TrainingData, read_corpus
from ..losses import reconstruction_loss
from ..model import BncNeuralV2
from ..session_report import comparison
from ..evaluate import synthetic_gate
from ..train import atomic_json, validate
from ..validation_freeze import cases as fixed_validation_cases
from .model import CapModel


SOURCE = Path('build/bnc-neural-v2/diagnostics/checkpoint_failure_step_031555_resumable.pt')
ROOT = Path('build/bnc-neural-v2/cap-study')
MANIFEST = Path('build/bnc-neural-v2/corpus/corpus.json')
SOURCE_SHA = 'c8e991675d3a2df67ee03d4561e0302115573e3a4df0d38a47b1f822dd513bfb'
SEED = 823109
CAPS = (16, 32, 64)


def finite(value):
    if isinstance(value, torch.Tensor):
        return bool(torch.isfinite(value).all()) if value.is_floating_point() else True
    if isinstance(value, dict):
        return all(finite(v) for v in value.values())
    if isinstance(value, (list, tuple)):
        return all(finite(v) for v in value)
    return True


def identity():
    result = objective_identity()
    result['v2/cap_study/model.py'] = hashlib.sha256(Path(__file__).with_name('model.py').read_bytes()).hexdigest()
    return result


def configure():
    os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG', ':4096:8')
    torch.set_num_threads(2)
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.use_deterministic_algorithms(True)


def source_and_cases():
    if hashlib.sha256(SOURCE.read_bytes()).hexdigest() != SOURCE_SHA:
        raise ValueError('source checkpoint SHA changed')
    state = load(SOURCE)
    if state['step'] != 31555 or state['sampler']['nextSampleIndex'] != 1009760:
        raise ValueError('source global step/sample position changed')
    if state['modelIdentity'] != BncNeuralV2().config.description() or state['objectiveIdentity'] != objective_identity():
        raise ValueError('W24 source architecture/loss/data identity changed')
    if not finite((state['model'], state['optimizer'], state['scaler'])):
        raise ValueError('nonfinite source checkpoint')
    parts, corpus = read_corpus(MANIFEST)
    if state['datasetIdentity']['sha256'] != corpus['sha256']:
        raise ValueError('dataset/split identity changed')
    cases = fixed_validation_cases(parts['validation'], SEED, state['provenance'])
    return state, parts, cases


def phase_a(source, parts, cases):
    ROOT.mkdir(parents=True, exist_ok=True)
    data = TrainingData(parts['train'], 32, SEED, 1009760)
    packed, truth = default_collate([data[i] for i in range(32)])
    from ..procedural import scene
    _, scene_meta = scene(1009779, 'train', 256, SEED)
    if scene_meta['kind'] != 'thin_lines':
        raise ValueError('failed sample scene identity changed')
    results = {}
    for cap in CAPS:
        model = CapModel(cap)
        model.load_state_dict(source['model'], strict=True)
        for block in model.blocks(): block.observe_amplitude = True
        rows, aggregate = validate(model, cases, 'cpu', True)
        amplitude = model.amplitude_report()
        for block in model.blocks():
            block.observe_amplitude = False
            block.max_raw = 0.
            block.max_bounded = 0.
        model = model.cuda()
        sample_x = packed[19:20].cuda()
        sample_y = truth[19:20].cuda()
        with torch.no_grad(), torch.autocast('cuda', enabled=False):
            output = model(sample_x.float())
            loss, terms, rgb = reconstruction_loss(sample_x.float(), output, sample_y.float(), True)
        if not finite((output, loss, terms, rgb)):
            raise FloatingPointError(f'C={cap} failed sample nonfinite')
        peaks = model.gate_peaks()
        sample_output_max = float(output.detach().abs().max())
        sample_loss = float(loss.detach())
        model.zero_grad(set_to_none=True)
        with torch.autocast('cuda', enabled=False):
            full_output = model(packed.cuda().float())
            full_loss, full_terms, full_rgb = reconstruction_loss(packed.cuda().float(), full_output,
                                                                  truth.cuda().float(), True)
        if not finite((full_output, full_loss, full_terms, full_rgb)):
            raise FloatingPointError(f'C={cap} failed batch dry forward nonfinite')
        full_loss.backward()
        gradients = [p.grad for p in model.parameters() if p.grad is not None]
        if not finite(gradients):
            raise FloatingPointError(f'C={cap} failed batch dry gradient nonfinite')
        result = dict(cap=cap, sourceStep=source['step'], failedSampleIndex=1009779,
                      failedSceneType=scene_meta['kind'], optimizerUpdates=0,
                      validationScore=aggregate['selectionScore'], validationAggregate=aggregate,
                      normalGateAmplitudeChange=amplitude,
                      failedSampleOutputMax=sample_output_max, failedSampleLoss=sample_loss,
                      failedBatchLoss=float(full_loss.detach()),
                      maxGradient=max(float(g.detach().abs().max()) for g in gradients),
                      maxRawGateProduct=peaks['maxRawGateProduct'], maxBoundedGate=peaks['maxBoundedGate'],
                      numericalFailures=0)
        results[str(cap)] = result
        out = ROOT/f'c{cap}'
        out.mkdir(exist_ok=True)
        atomic_json(out/'phase-a.json', result)
        atomic_json(out/'phase-a-validation.json', dict(aggregate=aggregate, cases=rows))
        atomic_json(ROOT/'phase-a.json', results)
        print(json.dumps(dict(phase='A', **result)), flush=True)
        del model
        torch.cuda.empty_cache()
    return results


def checkpoint_state(model, optimizer, scaler, scheduler, step, history, best, provenance,
                     elapsed, cap, generator):
    args = SimpleNamespace(seed=SEED, microbatch=32, workers=4, cap=cap,
                           manifest=str(MANIFEST), source=str(SOURCE), fullFP32=True)
    state = capture(model, optimizer, scaler, scheduler, step, history, best, provenance,
                    elapsed, args, generator)
    state['objectiveIdentity'] = identity()
    state['capStudy'] = dict(cap=cap, sourceSHA256=SOURCE_SHA,
                             gate=f'{cap} * tanh((a * b) / {cap})', precision='FP32')
    return state


def train_candidate(cap, initial, parts, cases, target, stage):
    out = ROOT/f'c{cap}'
    if stage == 'B':
        if (out/'checkpoints/latest.pt').exists():
            raise ValueError(f'C={cap} study checkpoint already exists; refusing to overwrite')
        if initial['step'] != 31555:
            raise ValueError('each candidate must start at step 31555')
    else:
        if initial['step'] != 31755 or initial['capStudy']['cap'] != cap:
            raise ValueError('phase C must resume its own 200-step cap checkpoint')
        if initial['objectiveIdentity'] != identity() or initial['modelIdentity'] != CapModel(cap).config.description():
            raise ValueError('cap study resume identity mismatch')
    model = CapModel(cap).cuda()
    model.load_state_dict(initial['model'], strict=True)
    optimizer = torch.optim.AdamW(model.parameters(), lr=.001, betas=(.9, .99), weight_decay=1e-4)
    optimizer.load_state_dict(copy.deepcopy(initial['optimizer']))
    scheduler = GlobalCosine(optimizer)
    scheduler.load_state_dict(copy.deepcopy(initial['scheduler']))
    scaler = torch.amp.GradScaler('cuda')
    scaler.load_state_dict(copy.deepcopy(initial['scaler']))
    step = initial['step']
    history = list(initial['history'])
    best = initial['best']
    provenance = copy.deepcopy(initial['provenance'])
    provenance['capStudy'] = dict(cap=cap, sourceCheckpoint=str(SOURCE.resolve()),
                                   sourceSHA256=SOURCE_SHA, fullFP32ForwardAndLoss=True)
    generator = torch.Generator().manual_seed(SEED+7919)
    if initial['sampler']['loaderGeneratorState'] is not None:
        generator.set_state(initial['sampler']['loaderGeneratorState'])
    generator_before = generator.get_state()
    data = TrainingData(parts['train'], (target-step)*32, SEED, step*32)
    loader = iter(DataLoader(data, batch_size=32, shuffle=False, num_workers=4,
                             pin_memory=True, persistent_workers=True, prefetch_factor=2,
                             generator=generator))
    generator.set_state(generator_before)
    restore_rng(initial['rng'])
    begin = step
    started = time.perf_counter()
    stats = dict(numericalFailures=0,maxGradient=0.,maxRawGateProduct=0.,maxBoundedGate=0.)
    def save_checkpoint():
        state = checkpoint_state(model, optimizer, scaler, scheduler, step, history, best,
                                 provenance, initial['elapsed']+time.perf_counter()-started, cap, generator)
        persist(out, state, periodic=True)
    model.train()
    try:
        while step < target:
            packed, truth = next(loader)
            packed, truth = packed.cuda(non_blocking=True), truth.cuda(non_blocking=True)
            optimizer.zero_grad(set_to_none=True)
            scheduler.apply(step)
            with torch.autocast('cuda', enabled=False):
                output = model(packed.float())
                loss, terms, rgb = reconstruction_loss(packed.float(), output, truth.float(), True)
            if not finite((output, loss, terms, rgb)):
                raise FloatingPointError(f'C={cap} step {step} nonfinite forward/loss')
            loss.backward()
            grads = [p.grad for p in model.parameters() if p.grad is not None]
            if not finite(grads):
                raise FloatingPointError(f'C={cap} step {step} nonfinite gradient')
            stats['maxGradient'] = max(stats['maxGradient'],max(float(g.detach().abs().max()) for g in grads))
            peaks = model.gate_peaks()
            for key in ('maxRawGateProduct','maxBoundedGate'):
                stats[key] = max(stats[key], peaks[key])
            optimizer.step()
            if not finite(optimizer.state_dict()['state']):
                raise FloatingPointError(f'C={cap} step {step} nonfinite AdamW state')
            step += 1
            scheduler.committed()
            if (step-begin)%50 == 0 or step == begin+1:
                print(json.dumps(dict(phase=stage, cap=cap, step=step, loss=float(loss.detach()),
                                      lr=optimizer.param_groups[0]['lr'])), flush=True)
            if (step-begin)%100 == 0:
                save_checkpoint()
    finally:
        if hasattr(loader,'_shutdown_workers'): loader._shutdown_workers()
    if step != target:
        raise AssertionError('candidate did not stop at exact target')
    rows, aggregate = validate(model, cases, 'cuda', True)
    aggregate['step'] = step
    history.append(aggregate)
    best = min(best, aggregate['selectionScore'])
    save_checkpoint()
    model.cpu()
    torch.cuda.empty_cache()
    synthetic, _ = comparison(model)
    gate = synthetic_gate(synthetic)
    result = dict(cap=cap, stage=stage, startStep=begin, endStep=step,
                  nextSampleIndex=step*32, validation=dict(aggregate=aggregate,cases=rows),
                  synthetic=synthetic, syntheticGate=gate, numerical=stats,
                  checkpoint=str((out/'checkpoints/latest.pt').resolve()),
                  optimizerStepPreserved=True, schedulerStepPreserved=True)
    atomic_json(out/f'phase-{stage.lower()}.json', result)
    print(json.dumps(dict(phase=stage,cap=cap,endStep=step,
                          validationScore=aggregate['selectionScore'],
                          syntheticGateFailures=len(gate['failures']),numerical=stats)),flush=True)
    return result


def quality_decision(cap, phase_a_result, phase_b_result, baseline):
    before = baseline['routes']['w24Step31000']
    after = phase_b_result['synthetic']
    q0, q = before['thin_lines']['bnc_neural'], after['thin_lines']['bnc_neural']
    thin = (q['missing_rgb_l1'] > q0['missing_rgb_l1'] or
            q['missing_g_l1'] > q0['missing_g_l1'] or
            q['opponent_error_rms'] > q0['opponent_error_rms'] or
            abs(q['luma_gradient_retention']-1) > abs(q0['luma_gradient_retention']-1))
    e0, e = before['isoluminant_color_edge']['bnc_neural'], after['isoluminant_color_edge']['bnc_neural']
    iso = any(abs(e['opponent_channels'][ch]['chromatic_gradient_retention']-1) >
              abs(e0['opponent_channels'][ch]['chromatic_gradient_retention']-1) for ch in ('RG','BG'))
    detail_tradeoffs=[]
    for name in ('two_pixel_horizontal','two_pixel_vertical','frequency_sweep','diagonal_edge',
                 'fine_repetitive','thin_lines','zero_chroma_fine_detail','slanted_edge_0417','slanted_edge_131'):
        a,b=before[name]['bnc_neural'],after[name]['bnc_neural']
        if (a['luma_gradient_retention'] is not None and b['luma_gradient_retention'] is not None
                and abs(b['luma_gradient_retention']-1)>abs(a['luma_gradient_retention']-1)
                and b['opponent_rms']<a['opponent_rms']):
            detail_tradeoffs.append(name)
    score=phase_b_result['validation']['aggregate']['selectionScore']
    baseline_score=baseline['validation31k']['selectionScore']
    # From an unchanged step-31555 start, an increase over its zero-update
    # score is movement away from, rather than back toward, the 31k baseline.
    score_toward=score<=baseline_score or score<phase_a_result['validationScore']
    numerical=phase_b_result['numerical']['numericalFailures']
    eligible=(numerical==0 and not thin and not iso and not detail_tradeoffs and score_toward)
    return dict(cap=cap, eligible=eligible, numericalFailures=numerical,
                thinLinesRegression=thin, isoluminantRegression=iso,
                detailForFalseColorTradeoffs=detail_tradeoffs,
                validationScore=score, validationBaseline=baseline_score,
                validationZeroUpdate=phase_a_result['validationScore'],
                scoreReturningTowardBaseline=score_toward,
                syntheticGateFailures=len(phase_b_result['syntheticGate']['failures']))


def main():
    configure()
    ROOT.mkdir(parents=True,exist_ok=True)
    source, parts, cases = source_and_cases()
    baseline = json.loads(Path('build/bnc-neural-v2/training/w24-bounded-fp32/sessions/step_032555/comparison.json').read_text())
    phase_a_results = phase_a(source, parts, cases)
    phase_b_results = {}
    decisions = {}
    for cap in CAPS:
        result = train_candidate(cap, source, parts, cases, 31755, 'B')
        phase_b_results[str(cap)] = result
        decisions[str(cap)] = quality_decision(cap, phase_a_results[str(cap)], result, baseline)
        atomic_json(ROOT/'decisions.json',decisions)
        print(json.dumps(dict(phase='selection',**decisions[str(cap)])),flush=True)
    eligible = [d for d in decisions.values() if d['eligible']]
    selected = min(eligible, key=lambda d:d['validationScore'])['cap'] if eligible else None
    phase_c = None
    phase_c_decision = None
    if selected is not None:
        phase_c = train_candidate(selected, load(ROOT/f'c{selected}/checkpoints/latest.pt'),
                                  parts, cases, 32555, 'C')
        phase_c_decision = quality_decision(selected, phase_a_results[str(selected)], phase_c, baseline)
    summary = dict(startingCheckpoint=str(SOURCE.resolve()), startStep=31555,
                   validationBaseline=baseline['validation31k']['selectionScore'],
                   phaseA=phase_a_results, phaseBDecisions=decisions, selectedCap=selected,
                   phaseC=None if phase_c is None else dict(cap=selected,
                     validationScore=phase_c['validation']['aggregate']['selectionScore'],
                     syntheticGateFailures=len(phase_c['syntheticGate']['failures']),
                     qualityDecision=phase_c_decision),
                   stopped=True, no55kTraining=True, productCodeChanged=False,
                   safeToContinueResearch=bool(phase_c_decision and phase_c_decision['eligible']
                                               and phase_c['syntheticGate']['passed']))
    atomic_json(ROOT/'summary.json',summary)
    print(json.dumps(dict(phase='done', selectedCap=selected,
                          validationBaseline=summary['validationBaseline'],
                          phaseBDecisions=decisions,phaseC=summary['phaseC'])),flush=True)


if __name__=='__main__': main()
