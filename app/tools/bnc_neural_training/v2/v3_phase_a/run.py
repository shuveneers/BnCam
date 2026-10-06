"""Qualify exactly three freshly initialized gates at steps 0, 500 and 2000."""
import argparse
import copy
import hashlib
import json
import os
import random
import time
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import torch
from torch.utils.data import DataLoader

from ..checkpoint import GlobalCosine, capture, load, objective_identity, restore_rng, save
from ..data import TrainingData, read_corpus
from ..losses import reconstruction_loss
from ..model import BncNeuralV2
from ..train import atomic_json, validate
from ..validation_freeze import cases as fixed_cases
from .model import GATES, PhaseModel, VARIANTS

ROOT = Path('build/bnc-neural-v2/v3-phase-a')
MANIFEST = Path('build/bnc-neural-v2/corpus/corpus.json')
FROZEN = Path('build/bnc-neural-v2/training/w24/fixed-validation-procedural.json')
SEED = 823109
FAILED_INDEX = 1009779
TARGETS = (0, 500, 2000)
FIXTURES = ('thin_lines', 'two_pixel_horizontal', 'two_pixel_vertical',
            'frequency_sweep', 'fine_repetitive', 'zero_chroma_fine_detail',
            'color_edge', 'isoluminant_color_edge')


def finite(value):
    if isinstance(value, torch.Tensor):
        return bool(torch.isfinite(value).all()) if value.is_floating_point() else True
    if isinstance(value, dict):
        return all(finite(x) for x in value.values())
    if isinstance(value, (list, tuple)):
        if value and all(isinstance(x, torch.Tensor) for x in value):
            return bool(torch.stack([torch.isfinite(x).all() for x in value if x.is_floating_point()]).all())
        return all(finite(x) for x in value)
    return True


def safe_float(value):
    result = float(value.detach())
    return result if np.isfinite(result) else None


def weight_hash(state):
    digest = hashlib.sha256()
    for name, value in sorted(state.items()):
        digest.update(name.encode())
        digest.update(value.detach().cpu().contiguous().numpy().tobytes())
    return digest.hexdigest()


def setup():
    os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG', ':4096:8')
    torch.set_num_threads(2)
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.use_deterministic_algorithms(True)
    if not torch.cuda.is_available():
        raise RuntimeError('Full-FP32 Phase A requires the existing CUDA training device')


def fresh_model(variant):
    random.seed(SEED)
    np.random.seed(SEED)
    torch.manual_seed(SEED)
    torch.cuda.manual_seed_all(SEED)
    # All candidates receive the same random W24 parameter tensor. Neither
    # model nor optimizer values are loaded from a W24 training checkpoint.
    initial = BncNeuralV2().state_dict()
    model = PhaseModel(variant)
    model.load_state_dict(initial, strict=True)
    return model.cuda(), weight_hash(initial)


def frozen_provenance(corpus, variant):
    metadata = json.loads(FROZEN.read_text())
    return dict(trainingCorpus=corpus, gate=GATES[variant], seed=SEED,
                research='v3 phase A random-init qualification', productReady=False,
                generatorReliabilityMigration=dict(validationArchive=metadata['archive'],
                    validationArchiveSHA256=metadata['sha256']))


def failed_probe(model, train_records, optimizer):
    # Independent copies: never mix probe gradients/state with the live run.
    from ..training_safety import stress_probe
    return stress_probe(model, optimizer, train_records)


def evaluate_milestone(model, optimizer, cases, train_records, variant, step, stats, out):
    from ..evaluate import synthetic_gate
    from ..session_report import comparison
    probe = failed_probe(model, train_records, optimizer)
    rows, aggregate = validate(model, cases, 'cuda', True)
    aggregate['step'] = step
    copy_model = PhaseModel(variant).cpu().eval()
    copy_model.load_state_dict({k: v.detach().cpu() for k, v in model.state_dict().items()}, strict=True)
    synthetic, _ = comparison(copy_model)
    gate = synthetic_gate(synthetic)
    selected_fixtures = {name: synthetic[name]['bnc_neural'] for name in FIXTURES}
    evidence = dict(variant=variant, gate=GATES[variant], step=step,
                    validation=dict(aggregate=aggregate, cases=rows),
                    synthetic=selected_fixtures, syntheticGate=gate,
                    failedSample=probe, numerical=copy.deepcopy(stats),
                    maxBranchActivation=model.peaks()['maxBranchActivation'],
                    maxGateOutput=model.peaks()['maxGateOutput'],
                    optimizerUpdates=step, fullFP32=True,
                    measuredSenselMaxError=max(q['bnc_neural']['sampled_sensel_max_error']
                                                for q in synthetic.values()))
    atomic_json(out / f'step_{step:04d}.json', evidence)
    print(json.dumps(dict(variant=variant, step=step,
                          validationScore=aggregate['selectionScore'],
                          gateFailures=len(gate['failures']),
                          failedSampleFinite=probe['finite'],
                          maxGradient=stats['maxGradient'])), flush=True)
    return evidence


def run(variant, resume):
    setup()
    parts, corpus = read_corpus(MANIFEST)
    if not json.loads(MANIFEST.read_text()).get('dedupFinalized'):
        raise ValueError('deduplicated W24 corpus required')
    provenance = frozen_provenance(corpus, variant)
    cases = fixed_cases(parts['validation'], SEED, provenance)
    if len(cases) != 116:
        raise ValueError('fixed validation changed')
    out = ROOT / variant
    latest = out / 'checkpoints/latest.pt'
    if latest.exists() != resume:
        raise ValueError('use --resume for an existing candidate; never overwrite a run')
    out.mkdir(parents=True, exist_ok=True)
    model, initial_hash = fresh_model(variant)
    optimizer = torch.optim.AdamW(model.parameters(), lr=.001, betas=(.9, .99), weight_decay=1e-4)
    scheduler = GlobalCosine(optimizer)
    scaler = torch.amp.GradScaler('cuda', enabled=False)
    generator = torch.Generator().manual_seed(SEED + 7919)
    step = 0
    history = []
    best = float('inf')
    elapsed = 0.
    stats = dict(numericalFailures=0, maxGradient=0., maxBranchActivation=0.,
                 maxGateOutput=0., adamWStateFinite=True)
    if resume:
        state = load(latest)
        if state['modelIdentity'] != model.config.description() or state['objectiveIdentity'] != objective_identity():
            raise ValueError('candidate identity changed')
        if state['datasetIdentity']['sha256'] != corpus['sha256'] or state['trainingArguments']['seed'] != SEED:
            raise ValueError('dataset or training seed changed')
        model.load_state_dict(state['model'])
        optimizer.load_state_dict(state['optimizer'])
        scheduler.load_state_dict(state['scheduler'])
        scaler.load_state_dict(state['scaler'])
        generator.set_state(state['sampler']['loaderGeneratorState'])
        restore_rng(state['rng'])
        step, history, best, elapsed = (state[k] for k in ('step', 'history', 'best', 'elapsed'))
        stats = json.loads((out / 'progress.json').read_text())['numerical']
        initial_hash = state['provenance']['initialWeightsSHA256']
        provenance = state['provenance']
    else:
        provenance['initialWeightsSHA256'] = initial_hash
        atomic_json(out / 'identity.json', dict(variant=variant, gate=GATES[variant],
                    initialWeightsSHA256=initial_hash, datasetSHA256=corpus['sha256'],
                    sourceWeights='fresh seeded torch initialization',
                    sourceOptimizer='new AdamW', fullFP32=True))
    args = SimpleNamespace(seed=SEED, microbatch=32, workers=4, variant=variant,
                           manifest=str(MANIFEST), fullFP32=True)
    start = time.perf_counter()

    def checkpoint():
        state = capture(model, optimizer, scaler, scheduler, step, history, best,
                        provenance, elapsed+time.perf_counter()-start, args, generator)
        save(latest, state)
        save(out / f'checkpoints/step_{step:04d}.pt', state)
        atomic_json(out / 'progress.json', dict(step=step, nextSampleIndex=step*32,
                    numerical=stats, initialWeightsSHA256=initial_hash))

    if not resume:
        checkpoint()
    for target in TARGETS:
        if step > target:
            if not (out / f'step_{target:04d}.json').exists():
                raise ValueError('missing earlier milestone evidence')
            continue
        if step < target:
            before = generator.get_state()
            data = TrainingData(parts['train'], (target-step)*32, SEED, step*32)
            loader = iter(DataLoader(data, batch_size=32, shuffle=False, num_workers=4,
                                     pin_memory=True, persistent_workers=True,
                                     prefetch_factor=2, generator=generator))
            generator.set_state(before)
            model.train()
            try:
                while step < target:
                    packed, truth = next(loader)
                    packed = packed.cuda(non_blocking=True).float()
                    truth = truth.cuda(non_blocking=True).float()
                    optimizer.zero_grad(set_to_none=True)
                    scheduler.apply(step)
                    with torch.autocast('cuda', enabled=False):
                        output = model(packed)
                        loss, terms, rgb = reconstruction_loss(packed, output, truth, True)
                    if not finite((packed, truth, output, loss, terms, rgb)):
                        raise FloatingPointError(f'nonfinite training forward/loss at step {step}')
                    loss.backward()
                    gradients = [p.grad for p in model.parameters() if p.grad is not None]
                    if not finite(gradients):
                        raise FloatingPointError(f'nonfinite training gradient at step {step}')
                    stats['maxGradient'] = max(stats['maxGradient'],
                                               float(torch.stack([g.detach().abs().amax()
                                                                  for g in gradients]).amax()))
                    optimizer.step()
                    step += 1
                    scheduler.committed()
                    if step % 50 == 0:
                        print(json.dumps(dict(variant=variant, step=step,
                                              loss=float(loss.detach()),
                                              lr=optimizer.param_groups[0]['lr'])), flush=True)
                    if step % 250 == 0:
                        stats['adamWStateFinite'] = finite(optimizer.state_dict()['state'])
                        if not stats['adamWStateFinite']:
                            raise FloatingPointError(f'nonfinite AdamW state at step {step}')
                        stats.update(model.peaks())
                        checkpoint()
            except FloatingPointError as error:
                stats['numericalFailures'] += 1
                atomic_json(out / 'numerical-failure.json', dict(variant=variant,
                            attemptedStep=step, reason=str(error), lastCheckpoint=str(latest.resolve())))
                raise
            finally:
                if hasattr(loader, '_shutdown_workers'):
                    loader._shutdown_workers()
        evidence_path = out / f'step_{target:04d}.json'
        if not evidence_path.exists():
            evidence = evaluate_milestone(model, optimizer, cases, parts['train'],
                                          variant, target, stats, out)
            history.append(evidence['validation']['aggregate'])
            best = min(best, evidence['validation']['aggregate']['selectionScore'])
            if not evidence['failedSample']['finite']:
                stats['numericalFailures'] += 1
            stats.update(model.peaks())
            checkpoint()
    print(json.dumps(dict(variant=variant, stoppedAt=step,
                          result=str((out / 'step_2000.json').resolve()))), flush=True)


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--variant', required=True, choices=VARIANTS)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    run(args.variant, args.resume)


if __name__ == '__main__':
    main()
