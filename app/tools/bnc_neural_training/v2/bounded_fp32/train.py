"""Manually bounded FP32 research sessions; never writes official W24 artifacts."""
import argparse
import datetime
import hashlib
import json
import os
import random
import time
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader

from ..checkpoint import GlobalCosine, capture, load, objective_identity, persist, restore_rng
from ..data import TrainingData, read_corpus
from ..losses import reconstruction_loss
from ..model import BncNeuralV2
from ..session_report import comparison
from ..evaluate import synthetic_gate
from ..train import atomic_json, validate
from ..validation_freeze import cases as fixed_validation_cases
from .model import BoundedBncNeuralV2, BoundedGatedBlock


SOURCE = Path('build/bnc-neural-v2/diagnostics/checkpoint_failure_step_031555_resumable.pt')
OUT = Path('build/bnc-neural-v2/training/w24-bounded-fp32')
MANIFEST = Path('build/bnc-neural-v2/corpus/corpus.json')
EXPECTED_SOURCE_SHA = 'c8e991675d3a2df67ee03d4561e0302115573e3a4df0d38a47b1f822dd513bfb'


def fork_identity():
    identity = objective_identity()
    identity['v2/bounded_fp32/model.py'] = hashlib.sha256(Path(__file__).with_name('model.py').read_bytes()).hexdigest()
    return identity


def finite_tree(value):
    if isinstance(value, torch.Tensor):
        return bool(torch.isfinite(value).all()) if value.is_floating_point() else True
    if isinstance(value, dict):
        return all(finite_tree(v) for v in value.values())
    if isinstance(value, (tuple, list)):
        return all(finite_tree(v) for v in value)
    return True


def snapshot(model, optimizer, scaler, scheduler, step, history, best, provenance, elapsed, args, generator):
    state = capture(model, optimizer, scaler, scheduler, step, history, best, provenance, elapsed, args, generator)
    state['objectiveIdentity'] = fork_identity()
    state['fork'] = dict(branch='research/bnc-v2-bounded-gate-fp32', sourceCheckpointSHA256=EXPECTED_SOURCE_SHA,
                         gate='64 * tanh((a * b) / 64)', neuralForwardAndLoss='FP32', ampEnabled=False)
    return state


def run(args):
    if args.out.resolve() in (Path('build/bnc-neural-v2/training/w24').resolve(),
                              Path('build/bnc-neural-v2/training/w24-fp32-smoke').resolve()):
        raise ValueError('research output must not be an official W24 directory')
    if not torch.cuda.is_available():
        raise RuntimeError('CUDA required')
    os.environ.setdefault('CUBLAS_WORKSPACE_CONFIG', ':4096:8')
    torch.set_num_threads(2)
    torch.backends.cudnn.benchmark = False
    torch.backends.cudnn.deterministic = True
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    torch.use_deterministic_algorithms(True)
    parts, corpus = read_corpus(args.manifest)
    if not json.loads(args.manifest.read_text()).get('dedupFinalized'):
        raise ValueError('dedup finalization required')
    initial = args.resume.resolve() == SOURCE.resolve()
    if initial and hashlib.sha256(SOURCE.read_bytes()).hexdigest() != EXPECTED_SOURCE_SHA:
        raise ValueError('diagnostic checkpoint identity changed')
    state = load(args.resume)
    if state['datasetIdentity']['sha256'] != corpus['sha256']:
        raise ValueError('dataset/split identity changed')
    if state['trainingArguments']['seed'] != args.seed or state['trainingArguments']['microbatch'] != args.microbatch:
        raise ValueError('training sampling arguments changed')
    if initial:
        if state['step'] != 31555 or state['sampler']['nextSampleIndex'] != 1009760:
            raise ValueError('diagnostic checkpoint must replay the failed batch')
        if state['objectiveIdentity'] != objective_identity() or state['modelIdentity'] != BncNeuralV2().config.description():
            raise ValueError('source architecture/loss/data identity changed')
        if args.out.exists():
            raise ValueError('research output already exists; resume its own latest checkpoint instead')
        args.out.mkdir(parents=True)
    else:
        if state['objectiveIdentity'] != fork_identity() or state['modelIdentity'] != BoundedBncNeuralV2().config.description():
            raise ValueError('research fork identity mismatch')
        if state.get('fork', {}).get('sourceCheckpointSHA256') != EXPECTED_SOURCE_SHA:
            raise ValueError('research fork ancestry mismatch')
        latest = args.out / 'checkpoints/latest.pt'
        if latest.exists() and load(latest)['step'] > state['step']:
            raise ValueError('refusing to overwrite newer research progress')
    if not state['optimizer']['state'] or not state['scaler'] or not finite_tree(state['model']) or not finite_tree(state['optimizer']):
        raise ValueError('incomplete or nonfinite starting state')
    model = BoundedBncNeuralV2().cuda()
    model.load_state_dict(state['model'], strict=True)
    optimizer = torch.optim.AdamW(model.parameters(), lr=.001, betas=(.9, .99), weight_decay=1e-4)
    optimizer.load_state_dict(state['optimizer'])
    scheduler = GlobalCosine(optimizer)
    scheduler.load_state_dict(state['scheduler'])
    # Preserved verbatim in research checkpoints, but never applied in FP32 training.
    scaler = torch.amp.GradScaler('cuda')
    scaler.load_state_dict(state['scaler'])
    step = state['step']
    if not step < args.stop_at_step <= 150000:
        raise ValueError('stop-at-step must exceed checkpoint step and be <=150000')
    if initial and args.stop_at_step != 32555:
        raise ValueError('first research qualification must stop exactly at step 32555')
    history, best, provenance = list(state['history']), state['best'], dict(state['provenance'])
    provenance['researchFork'] = dict(branch='research/bnc-v2-bounded-gate-fp32',
                                      startingCheckpoint=str(SOURCE.resolve()), sourceSHA256=EXPECTED_SOURCE_SHA,
                                      gate='64 * tanh((a * b) / 64)', fullFP32ForwardAndLoss=True)
    provenance['runStatus'] = 'RESEARCH_NOT_QUALIFIED'
    args.out.joinpath('validation').mkdir(exist_ok=True)
    cases = fixed_validation_cases(parts['validation'], args.seed, provenance)
    atomic_json(args.out/'validation-manifest.json', dict(cases=[c[0] for c in cases], seed=args.seed))
    generator = torch.Generator().manual_seed(args.seed + 7919)
    if state['sampler']['loaderGeneratorState'] is not None:
        generator.set_state(state['sampler']['loaderGeneratorState'])
    generator_before = generator.get_state()
    data = TrainingData(parts['train'], (args.stop_at_step-step)*32, args.seed, step*32)
    loader = iter(DataLoader(data, batch_size=args.microbatch, shuffle=False, num_workers=args.workers,
                             pin_memory=True, persistent_workers=bool(args.workers),
                             prefetch_factor=2 if args.workers else None, generator=generator))
    generator.set_state(generator_before)
    restore_rng(state['rng'])
    elapsed_previous = state['elapsed']
    started = time.perf_counter()
    beginning = step
    stats = dict(maxAbsSharedFeature=0., maxAbsGreenOutput=0., maxAbsOpponentOutput=0.,
                 maxRawGateProduct=0., maxBoundedGate=0., maxGradient=0., numericalFailures=0)
    def save_checkpoint(periodic=False, improved=False):
        current = snapshot(model, optimizer, scaler, scheduler, step, history, best, provenance,
                           elapsed_previous + time.perf_counter()-started, args, generator)
        persist(args.out, current, periodic=periodic, best=improved)
    def record_validation():
        nonlocal best
        rows, aggregate = validate(model, cases, 'cuda', True)
        aggregate['step'] = step
        history.append(aggregate)
        improved = aggregate['selectionScore'] < best
        if improved: best = aggregate['selectionScore']
        atomic_json(args.out/'validation'/f'{step:06d}.json', dict(step=step, aggregate=aggregate, cases=rows))
        atomic_json(args.out/'validation-history.json', history)
        save_checkpoint(periodic=True, improved=improved)
        print(json.dumps(dict(validationStep=step, aggregate={k:aggregate[k] for k in
                              ('missing','opponent','edge','selectionScore')})), flush=True)
    atomic_json(args.out/'status.json', dict(status='RESEARCH_TRAINING', startStep=step,
                stopAtStep=args.stop_at_step, nextSampleIndex=step*32, safeToPowerOff=False))
    save_checkpoint(periodic=True)
    model.train()
    try:
        while step < args.stop_at_step:
            batches = [next(loader) for _ in range(32//args.microbatch)]
            optimizer.zero_grad(set_to_none=True)
            scheduler.apply(step)
            for packed, truth in batches:
                packed, truth = packed.cuda(non_blocking=True), truth.cuda(non_blocking=True)
                with torch.autocast('cuda', enabled=False):
                    output = model(packed.float())
                    loss, terms, reconstructed = reconstruction_loss(packed.float(), output, truth.float(), True)
                if not finite_tree((output, reconstructed, loss, terms)):
                    raise FloatingPointError(f'nonfinite FP32 forward/loss at step {step}, sample {step*32}')
                (loss/len(batches)).backward()
                stats['maxAbsSharedFeature'] = max(stats['maxAbsSharedFeature'], model.last_activations['shared'])
                stats['maxAbsGreenOutput'] = max(stats['maxAbsGreenOutput'], model.last_activations['green'])
                stats['maxAbsOpponentOutput'] = max(stats['maxAbsOpponentOutput'], model.last_activations['opponent'])
                for block in model.modules():
                    if isinstance(block, BoundedGatedBlock):
                        stats['maxRawGateProduct'] = max(stats['maxRawGateProduct'], block.raw_abs_max)
                        stats['maxBoundedGate'] = max(stats['maxBoundedGate'], block.gate_abs_max)
            for parameter in model.parameters():
                if parameter.grad is not None:
                    if not finite_tree(parameter.grad):
                        raise FloatingPointError(f'nonfinite FP32 gradient at step {step}')
                    stats['maxGradient'] = max(stats['maxGradient'], float(parameter.grad.detach().abs().max()))
            optimizer.step()
            if not finite_tree(optimizer.state_dict()['state']):
                raise FloatingPointError(f'nonfinite AdamW state after step {step}')
            step += 1
            scheduler.committed()
            if step % 50 == 0 or step == beginning+1:
                status = dict(status='RESEARCH_TRAINING', optimizerStep=step, nextSampleIndex=step*32,
                              lr=optimizer.param_groups[0]['lr'], loss=float(loss.detach()),
                              safeToPowerOff=False)
                atomic_json(args.out/'status.json', status)
                print(json.dumps(status), flush=True)
            if initial and step in (31755, 32055, 32555):
                record_validation()
            elif step == args.stop_at_step:
                record_validation()
            elif (step-beginning) % 200 == 0:
                save_checkpoint(periodic=True)
    finally:
        if hasattr(loader, '_shutdown_workers'):
            loader._shutdown_workers()
    if step != args.stop_at_step:
        raise AssertionError('session did not reach exact target')
    if not finite_tree(optimizer.state_dict()['state']):
        raise FloatingPointError('nonfinite AdamW state at session end')
    stats['adamWStateFinite'] = True
    stats['optimizerStep'] = step
    atomic_json(args.out/'numerics.json', stats)
    model.cpu()
    torch.cuda.empty_cache()
    # CPU fixed validation is independent of the training GPU state.
    rows, aggregate = validate(model, cases, 'cpu', True)
    aggregate['step'] = step
    atomic_json(args.out/'validation'/f'{step:06d}.json', dict(step=step, aggregate=aggregate, cases=rows))
    synthetic, v1_identity = comparison(model)
    gate = synthetic_gate(synthetic)
    report_dir = args.out/'sessions'/f'step_{step:06d}'
    report_dir.mkdir(parents=True, exist_ok=True)
    next_step = min(150000, (step//10000+1)*10000)
    command = (f".\\train-bnc-w24-bounded-fp32.ps1 -Resume '{(args.out/'checkpoints/latest.pt').resolve()}' "
               f'-StopAtStep {next_step}')
    evidence = dict(step=step, startingStep=beginning,
                    checkpoint=str((args.out/'checkpoints/latest.pt').resolve()),
                    validation=dict(aggregate=aggregate, cases=rows),
                    synthetic=synthetic, syntheticGate=gate, originalV1=v1_identity,
                    numerics=stats, nextCommand=command, productReady=False,
                    automaticContinuation=False)
    atomic_json(report_dir/'evidence.json', evidence)
    atomic_json(args.out/'status.json', dict(status='RESEARCH_SESSION_STOPPED', currentStep=step,
                resumableCheckpoint=evidence['checkpoint'], nextCommand=command,
                validationSummary={k:aggregate[k] for k in ('missing','opponent','edge','selectionScore')},
                syntheticGateFailures=len(gate['failures']), safeToPowerOff=True,
                automaticContinuation=False, bncNeuralAvailable=False))
    print(json.dumps(dict(stoppedAtStep=step, validation=aggregate, syntheticGateFailures=len(gate['failures']),
                          checkpoint=evidence['checkpoint'])), flush=True)
    return evidence


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--resume', type=Path, required=True)
    parser.add_argument('--stop-at-step', type=int, required=True)
    parser.add_argument('--out', type=Path, default=OUT)
    parser.add_argument('--manifest', type=Path, default=MANIFEST)
    parser.add_argument('--seed', type=int, default=823109)
    parser.add_argument('--microbatch', type=int, default=32)
    parser.add_argument('--workers', type=int, default=4)
    args = parser.parse_args()
    if args.microbatch < 1 or 32 % args.microbatch or args.workers < 0:
        parser.error('invalid microbatch/workers')
    import msvcrt
    args.out.parent.mkdir(parents=True, exist_ok=True)
    lock = args.out.parent/(args.out.name+'.session.lock')
    with lock.open('a+b') as stream:
        stream.seek(0)
        if stream.read(1) == b'': stream.write(b'0'); stream.flush()
        stream.seek(0)
        msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        try: run(args)
        finally:
            stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)


if __name__ == '__main__':
    main()
