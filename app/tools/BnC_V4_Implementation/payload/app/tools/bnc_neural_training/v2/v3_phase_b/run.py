"""Resume a v3 A/C candidate, stop at an explicit step, then evaluate and exit."""
import argparse
import copy
import hashlib
import json
import os
import shutil
import signal
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import torch
from torch.utils.data import DataLoader

from ..checkpoint import GlobalCosine, capture, load, objective_identity, restore_rng, save
from ..data import TrainingData, read_corpus
from ..losses import reconstruction_loss
from ..train import atomic_json, validate
from ..validation_freeze import cases as fixed_cases
from ..training_safety import (UnsafeUpdate, ResearchRegression, enforce_guard, ScopedObserver, checked_step, probe as scoped_probe, guard as research_guard, serializable)
from ..signed_tanh_study.distribution import Histogram
from ..v3_phase_a.model import GATES, PhaseBlock, PhaseModel
from ..v3_phase_a.run import FAILED_INDEX, FROZEN, MANIFEST, SEED, failed_probe, finite, setup

DEFAULT_ROOT = Path('build/bnc-neural-v2/v3-phase-b')
PHASE_A_ROOT = Path('build/bnc-neural-v2/v3-phase-a')
GATE_SOURCE = Path(__file__).resolve().parent.parent / 'v3_phase_a/model.py'
MIN_STEP = 2000
MAX_STEP = 150000
REPORT_FIXTURES = ('thin_lines', 'two_pixel_horizontal', 'two_pixel_vertical',
                   'frequency_sweep', 'diagonal_edge', 'slanted_edge_0417',
                   'slanted_edge_131', 'fine_repetitive', 'repetitive_period_5',
                   'repetitive_period_7', 'repetitive_period_11',
                   'repetitive_period_13', 'zero_chroma_fine_detail',
                   'flat_neutral', 'color_edge', 'isoluminant_color_edge')


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def copy_checkpoint(source, destination):
    """Copy a validated checkpoint and its sidecar into a new run atomically."""
    source, destination = Path(source), Path(destination)
    load(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() or destination.with_suffix('.pt.sha256').exists():
        raise FileExistsError(f'refusing to overwrite an existing checkpoint: {destination}')
    for src, dst in ((source, destination),
                     (source.with_suffix('.pt.sha256'), destination.with_suffix('.pt.sha256'))):
        temp = dst.with_suffix(dst.suffix + '.partial')
        shutil.copyfile(src, temp)
        os.replace(temp, dst)
    load(destination)


def verify_state(state, candidate, corpus):
    if state['step'] < MIN_STEP or state['step'] > MAX_STEP:
        raise ValueError('checkpoint step outside Phase B range')
    if state['modelIdentity'] != PhaseModel(candidate).config.description():
        raise ValueError('checkpoint candidate/gate identity mismatch')
    if state['objectiveIdentity'] != objective_identity():
        raise ValueError('loss/data/objective identity changed')
    if state['datasetIdentity']['sha256'] != corpus['sha256']:
        raise ValueError('dataset or split identity changed')
    args = state['trainingArguments']
    if args['seed'] != SEED or args['microbatch'] != 32 or args['workers'] != 4 or args['variant'] != candidate:
        raise ValueError('sample order or training arguments changed')
    sampler = state['sampler']
    if (sampler['seed'], sampler['effectiveBatch'], sampler['nextSampleIndex']) != (SEED, 32, state['step'] * 32):
        raise ValueError('sampler position changed')
    if state['scheduler']['nextStep'] != state['step']:
        raise ValueError('scheduler step changed')
    if not state['optimizer']['state'] or not finite(state['model']) or not finite(state['optimizer']['state']):
        raise ValueError('missing or nonfinite model/AdamW state')
    if sampler['loaderGeneratorState'] is None or 'torchCPU' not in state['rng'] or not state['rng']['torchCUDA']:
        raise ValueError('incomplete RNG/sampler checkpoint')


def valid_checkpoints(directory, candidate, corpus):
    valid, invalid = [], []
    for path in directory.glob('*.pt'):
        try:
            state = load(path)
            verify_state(state, candidate, corpus)
            valid.append((state['step'], path.name == 'latest.pt', path, state))
        except Exception as error:
            invalid.append((str(path), str(error)))
    valid.sort(key=lambda row: (row[0], row[1]), reverse=True)
    return valid, invalid


def prepare(root, candidate, corpus):
    out = root / candidate
    checkpoints = out / 'checkpoints'
    identity_path = out / 'identity.json'
    source = PHASE_A_ROOT / candidate / 'checkpoints/latest.pt'
    gate_hash = sha256(GATE_SOURCE)
    if not identity_path.exists():
        if out.exists() and any(path.name != 'checkpoints' for path in out.iterdir()):
            raise ValueError(f'nonempty run without identity; refusing import: {out}')
        source_state = load(source)
        verify_state(source_state, candidate, corpus)
        if source_state['step'] != MIN_STEP:
            raise ValueError('Phase A source must be step 2000')
        checkpoints.mkdir(parents=True, exist_ok=True)
        expected = {'source_step_002000.pt', 'latest.pt', 'best.pt'}
        if any(path.name not in expected and not path.name.endswith('.pt.sha256')
               for path in checkpoints.iterdir()):
            raise ValueError(f'unexpected checkpoint file during import: {checkpoints}')
        for name in ('source_step_002000.pt', 'latest.pt', 'best.pt'):
            destination = checkpoints / name
            if destination.exists():
                if sha256(destination) != sha256(source):
                    raise ValueError(f'incomplete/different imported checkpoint: {destination}')
                load(destination)
            else:
                copy_checkpoint(source, destination)
        atomic_json(identity_path, dict(candidate=candidate, gate=GATES[candidate],
                    gateImplementationSHA256=gate_hash,
                    phaseASource=str(source.resolve()), phaseASourceSHA256=sha256(source),
                    importedStep=MIN_STEP, datasetSHA256=corpus['sha256'],
                    initialWeightsSHA256=source_state['provenance']['initialWeightsSHA256'],
                    fullFP32=True, productReady=False))
    identity = json.loads(identity_path.read_text())
    if (identity['candidate'], identity['gate'], identity['gateImplementationSHA256'],
            identity['datasetSHA256']) != (candidate, GATES[candidate], gate_hash, corpus['sha256']):
        raise ValueError('Phase B run identity, gate code, or dataset changed')
    if sha256(source) != identity['phaseASourceSHA256']:
        raise ValueError('Phase A source checkpoint changed')
    (out / 'sessions').mkdir(exist_ok=True)
    valid, invalid = valid_checkpoints(checkpoints, candidate, corpus)
    if not valid:
        raise ValueError(f'no valid resumable checkpoint for {candidate}; invalid={invalid}')
    step, _, path, state = valid[0]
    if invalid:
        bad_latest = checkpoints / 'latest.pt'
        if any(name == str(bad_latest) for name, _ in invalid) and bad_latest.exists():
            quarantine = checkpoints / 'quarantine'
            quarantine.mkdir(exist_ok=True)
            tag = str(time.time_ns())
            shutil.copy2(bad_latest, quarantine / f'latest_{tag}.pt')
            sidecar = bad_latest.with_suffix('.pt.sha256')
            if sidecar.exists():
                shutil.copy2(sidecar, quarantine / f'latest_{tag}.pt.sha256')
        print(json.dumps(dict(candidate=candidate, invalidCheckpoints=invalid,
                              selectedValidCheckpoint=str(path.resolve()))), flush=True)
    return out, path, state


def named_save(path, state):
    if path.exists() or path.with_suffix('.pt.sha256').exists():
        old = load(path)
        if old['step'] == state['step']:
            return path
        raise FileExistsError(f'refusing to overwrite an existing named checkpoint: {path}')
    save(path, state)
    return path


def trim_recovery(directory):
    directory = directory.resolve()
    old = sorted(directory.glob('recovery_step_*.pt'))[:-2]
    for path in old:
        if path.resolve().parent != directory:
            raise ValueError('recovery checkpoint path escape')
        path.unlink()
        path.with_suffix('.pt.sha256').unlink(missing_ok=True)


class ActivationObserver:
    def __init__(self, model, candidate):
        self.candidate = candidate
        self.hist = {}
        self.max_raw = 0.
        self.max_gate = 0.
        self.max_model_output = 0.
        self.handles = []
        for name, block in model.named_modules():
            if isinstance(block, PhaseBlock):
                self.hist[name] = dict(a=Histogram(), b=Histogram())
                self.handles.append(block.expand.register_forward_hook(self._hook(name)))
        self.handles.append(model.register_forward_hook(self._model_hook))

    def _model_hook(self, module, inputs, output):
        self.max_model_output = max(self.max_model_output, float(output.detach().abs().amax()))

    def _hook(self, name):
        def observe(module, inputs, output):
            a, b = output.detach().chunk(2, dim=1)
            self.hist[name]['a'].add(a)
            self.hist[name]['b'].add(b)
            raw = a * b
            gate = raw if self.candidate == 'A' else 16 * torch.tanh(raw / 16)
            self.max_raw = max(self.max_raw, float(raw.abs().amax()))
            self.max_gate = max(self.max_gate, float(gate.abs().amax()))
        return observe

    def close(self):
        for handle in self.handles:
            handle.remove()

    def result(self):
        return dict(branchQuantiles={name: {branch: hist.summary() for branch, hist in pair.items()}
                                     for name, pair in self.hist.items()},
                    maxRawProduct=self.max_raw, maxActualGateOutput=self.max_gate,
                    maxModelOutput=self.max_model_output)


class TrainingRawObserver:
    def __init__(self, model):
        self.peak = None
        self.handles = [block.expand.register_forward_hook(self.observe)
                        for block in model.modules() if isinstance(block, PhaseBlock)]

    def observe(self, module, inputs, output):
        a, b = output.detach().chunk(2, dim=1)
        peak = (a*b).abs().amax()
        self.peak = peak if self.peak is None else torch.maximum(self.peak, peak)

    def value(self):
        return 0. if self.peak is None else float(self.peak)

    def close(self):
        for handle in self.handles:
            handle.remove()


def report(out, candidate, start_step, end_step, checkpoint, validation, synthetic,
           quality_gate, probe, activation, telemetry, interrupted=False):
    session = out / 'sessions' / f'step_{end_step:06d}'
    temporary = session.with_name(f'.{session.name}.{os.getpid()}.partial')
    temporary.mkdir(parents=True, exist_ok=False)
    evidence = dict(candidate=candidate, gate=GATES[candidate], startStep=start_step,
                    endStep=end_step, checkpoint=str(checkpoint.resolve()),
                    validation=validation, synthetic=synthetic, syntheticGate=quality_gate,
                    failedSample=probe, activation=activation, numerical=telemetry,
                    interrupted=interrupted, safeToPowerOff=True, productReady=False)
    atomic_json(temporary / 'evidence.json', evidence)
    fmt = lambda v: 'n/a' if v is None else f'{v:.7f}'
    lines = [f'# BnC Neural v3 Phase B — {candidate} stopped at step {end_step}', '',
             f'Gate: `{GATES[candidate]}`. Start step: {start_step}. Full FP32; unchanged W24 data, '
             'losses, optimizer, scheduler, validation and quality thresholds.',
             f'Checkpoint: `{checkpoint.resolve()}`. Next sample index: {end_step * 32}.',
             f"Fixed validation: {len(validation['cases'])} cases; selection score "
             f"{validation['aggregate']['selectionScore']:.9f}.",
             f"Synthetic gate failures: {len(quality_gate['failures'])}. "
             f"Measured-sensel max error: {max(q['bnc_neural']['sampled_sensel_max_error'] for q in synthetic.values()):.9g}.",
             f"Known sample {FAILED_INDEX}: finite={probe['finite']}, output max={fmt(probe['outputMax'])}, "
             f"loss={fmt(probe['loss'])}, dry gradient max={fmt(probe['maxGradient'])}.",
             f"Numerical telemetry: branch max={fmt(telemetry['maxBranchActivation'])}; "
             f"raw product max={fmt(telemetry['maxRawProduct'])}; actual gate max={fmt(telemetry['maxActualGateOutput'])}; "
             f"model output max={fmt(telemetry['maxModelOutput'])}; gradient max={fmt(telemetry['maxGradient'])}; "
             f"AdamW finite={telemetry['adamWStateFinite']}; failures={telemetry['numericalFailures']}.",
             '', '## Branch activation quantiles on all 116 fixed-validation cases', '',
             '| Block | Branch | P99 | P99.9 | P99.99 | P99.999 | Max |',
             '|---|---|---:|---:|---:|---:|---:|']
    for block, pair in activation['branchQuantiles'].items():
        for branch in ('a', 'b'):
            q = pair[branch]
            values = (q['P99'], q['P99.9'], q['P99.99'], q['P99.999'], q['max'])
            lines.append('| '+block+' | '+branch+' | '+' | '.join(map(fmt, values))+' |')
    lines += ['', '| Fixture | Missing RGB | Missing G | Opponent RMS | Luma gradient | RG edge | BG edge | Sensel max error |',
             '|---|---:|---:|---:|---:|---:|---:|---:|']
    for name in REPORT_FIXTURES:
        q = synthetic[name]['bnc_neural']
        values = (q['missing_rgb_l1'], q['missing_g_l1'], q['opponent_rms'],
                  q['luma_gradient_retention'],
                  q['opponent_channels']['RG']['chromatic_gradient_retention'],
                  q['opponent_channels']['BG']['chromatic_gradient_retention'],
                  q['sampled_sensel_max_error'])
        lines.append('| '+name+' | '+' | '.join(map(fmt, values))+' |')
    lines += ['', 'Synthetic gate failures:', '']
    lines += ['- '+failure for failure in quality_gate['failures']]
    lines += ['', 'Training stopped. safeToPowerOff=true. No product or Vulkan integration.', '']
    (temporary / 'report.md').write_text('\n'.join(lines), encoding='utf-8')
    os.replace(temporary, session)
    shutil.copyfile(session / 'report.md', out / 'latest-report.md')
    return session / 'report.md'


def run(candidate, target, root, prepare_only=False):
    if candidate not in ('A', 'C'):
        raise ValueError('Phase B permits only A and C')
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    # One training session per output root at a time, including validation.
    import msvcrt
    lock = (root / '.gpu-session.lock').open('a+b')
    try:
        try:
            lock.seek(0)
            if lock.read(1) == b'':
                lock.write(b'0')
                lock.flush()
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise RuntimeError(f'another Phase B session already owns the GPU lock: {root}') from error
        signal_snapshot = {getattr(signal,n): signal.getsignal(getattr(signal,n)) for n in
                           ('SIGINT','SIGTERM','SIGBREAK') if hasattr(signal,n)}
        try:
            return _run_locked(candidate, target, root, prepare_only)
        finally:
            for sig,handler in signal_snapshot.items():
                signal.signal(sig,handler)
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
    finally:
        lock.close()


def _run_locked(candidate, target, root, prepare_only):
    from ..evaluate import synthetic_gate
    from ..session_report import comparison
    parts, corpus = read_corpus(MANIFEST)
    if not json.loads(MANIFEST.read_text()).get('dedupFinalized'):
        raise ValueError('deduplicated W24 corpus required')
    out, selected, state = prepare(root, candidate, corpus)
    print(f'candidate={candidate}\ncheckpointStep={state["step"]}\nresumeCheckpoint={selected.resolve()}', flush=True)
    if prepare_only:
        return
    if target is None or not state['step'] <= target <= MAX_STEP:
        raise ValueError(f'StopAtStep must be between current step {state["step"]} and {MAX_STEP}')
    session_path = out / 'sessions' / f'step_{target:06d}'
    if session_path.exists():
        raise FileExistsError(f'session already completed: {session_path}')
    setup()
    frozen = json.loads(FROZEN.read_text())
    provenance = state['provenance']
    source_base = Path(__file__).resolve().parent.parent
    provenance['runnerSafetyRevision'] = {name: sha256(source_base/name) for name in
        ('v3_phase_b/run.py','v3_phase_a/run.py','training_safety.py')}
    if provenance['generatorReliabilityMigration']['validationArchiveSHA256'] != frozen['sha256']:
        raise ValueError('fixed validation archive changed')
    cases = fixed_cases(parts['validation'], SEED, provenance)
    if len(cases) != 116:
        raise ValueError('fixed validation changed')
    model = PhaseModel(candidate).cuda()
    model.load_state_dict(state['model'], strict=True)
    optimizer = torch.optim.AdamW(model.parameters(), lr=.001, betas=(.9, .99), weight_decay=1e-4)
    optimizer.load_state_dict(state['optimizer'])
    scheduler = GlobalCosine(optimizer)
    scheduler.load_state_dict(state['scheduler'])
    scaler = torch.amp.GradScaler('cuda', enabled=False)
    scaler.load_state_dict(state['scaler'])
    generator = torch.Generator().manual_seed(SEED + 7919)
    generator.set_state(state['sampler']['loaderGeneratorState'])
    restore_rng(state['rng'])
    step, start_step = state['step'], state['step']
    history, best, prior_elapsed = state['history'], state['best'], state['elapsed']
    args = SimpleNamespace(seed=SEED, microbatch=32, workers=4, variant=candidate,
                           manifest=str(MANIFEST), fullFP32=True)
    start = time.perf_counter()
    stats = dict(numericalFailures=0, qualityFailures=0, maxBranchActivation=0., maxRawProduct=0.,
                 maxActualGateOutput=0., maxModelOutput=0., maxGradient=0.,
                 adamWStateFinite=True, activationQuantileSource='fixed validation')
    stop_requested = False

    def request_stop(signum, frame):
        nonlocal stop_requested
        stop_requested = True

    previous_handlers = {}
    for name in ('SIGINT', 'SIGTERM', 'SIGBREAK'):
        if hasattr(signal, name):
            sig = getattr(signal, name)
            previous_handlers[sig] = signal.getsignal(sig)
            signal.signal(sig, request_stop)

    def snapshot():
        return capture(model, optimizer, scaler, scheduler, step, history, best,
                       provenance, prior_elapsed+time.perf_counter()-start, args, generator)

    def latest_checkpoint(recovery=False):
        if not finite(model.state_dict()) or not finite(optimizer.state_dict()['state']):
            raise FloatingPointError('nonfinite model/AdamW state; previous checkpoint preserved')
        state_now = snapshot()
        directory = out / 'checkpoints'
        if recovery:
            named_save(directory / f'recovery_step_{step:06d}.pt', state_now)
        save(directory / 'latest.pt', state_now)
        if recovery:
            trim_recovery(directory)
        return directory / 'latest.pt'

    failure_dir = out / 'failures'
    last_qualified_step = None
    def record_failure(error, attempted_step):
        key = 'qualityFailures' if isinstance(error,ResearchRegression) else 'numericalFailures'
        stats[key] += 1
        failure_dir.mkdir(exist_ok=True)
        event = dict(candidate=candidate, attemptedStep=attempted_step,
                     stage=getattr(error, 'stage', 'runtime_numerical'), reason=str(error),
                     numericalFailures=stats['numericalFailures'], qualityFailures=stats['qualityFailures'],
                     lastStoredCheckpoint=str((out/'checkpoints/latest.pt').resolve()),
                     lastQualifiedStep=last_qualified_step,
                     automaticRestart=False, productReady=False, safeToPowerOff=True)
        atomic_json(failure_dir/f'{time.time_ns()}.json', serializable(event))
        atomic_json(out/'status.json', dict(status='RESEARCH_STOP', **serializable(event)))

    edge_case = next((a,b) for name,a,b in cases if name == 'analytic:validation:16:isoluminant_edge')
    baseline = None
    reference_path = out/'checkpoints/stop_step_015000.pt'
    if reference_path.exists():
        reference_state = load(reference_path)
        verify_state(reference_state, candidate, corpus)
        reference = copy.deepcopy(model)
        reference.load_state_dict(reference_state['model'], strict=True)
        reference_opt = torch.optim.AdamW(reference.parameters(), lr=.001, betas=(.9,.99), weight_decay=1e-4)
        reference_opt.load_state_dict(reference_state['optimizer'])
        baseline = dict(stress=failed_probe(reference, parts['train'], reference_opt),
                        edge=scoped_probe(reference,None,*edge_case,'frozen_isoluminant_edge',False))
        del reference, reference_opt

    def qualify():
        nonlocal last_qualified_step
        checks = dict(stress=failed_probe(model,parts['train'],optimizer),
                      edge=scoped_probe(model,None,*edge_case,'frozen_isoluminant_edge',False))
        screen = research_guard(checks,baseline,4.)
        audit = out/'sentinels'/f'step_{step:06d}_{time.time_ns()}.json'
        audit.parent.mkdir(exist_ok=True)
        atomic_json(audit,serializable(dict(step=step,sentinels=checks,researchGuard=screen,
                    baseline='same-candidate frozen 15k checkpoint' if baseline else 'numerical only before 15k')))
        if not screen['passed']:
            enforce_guard(screen)
        last_qualified_step = step

    try:
        qualify()
    except (FloatingPointError, ResearchRegression) as error:
        record_failure(error,step+1)
        raise
    training_observer = ScopedObserver(model,'training_only')
    raw_observer = TrainingRawObserver(model)
    if step < target:
        before = generator.get_state()
        data = TrainingData(parts['train'], (target-step)*32, SEED, step*32)
        loader = iter(DataLoader(data, batch_size=32, shuffle=False, num_workers=4,
                                 pin_memory=True, persistent_workers=True,
                                 prefetch_factor=2, generator=generator))
        generator.set_state(before)
        model.train()
        try:
            while step < target and not stop_requested:
                packed, truth = next(loader)
                packed = packed.cuda(non_blocking=True).float()
                truth = truth.cuda(non_blocking=True).float()
                optimizer.zero_grad(set_to_none=True)
                scheduler.apply(step)
                with torch.autocast('cuda', enabled=False):
                    output = model(packed)
                    loss, terms, rgb = reconstruction_loss(packed, output, truth, True)
                if not finite((packed, truth, output, loss, terms, rgb)):
                    raise FloatingPointError(f'nonfinite forward/loss at attempted step {step+1}')
                loss.backward()
                grads = [p.grad for p in model.parameters() if p.grad is not None]
                if not finite(grads):
                    raise FloatingPointError(f'nonfinite gradient at attempted step {step+1}')
                stats['maxModelOutput'] = max(stats['maxModelOutput'], float(output.detach().abs().amax()))
                stats['maxGradient'] = max(stats['maxGradient'],
                                           float(torch.stack([g.detach().abs().amax() for g in grads]).amax()))
                checked_step(model, optimizer)
                step += 1
                scheduler.committed()
                if step % 50 == 0 or step == start_step+1:
                    elapsed = time.perf_counter()-start
                    rate = (step-start_step)/elapsed if elapsed else 0.
                    eta = (target-step)/rate if rate else 0.
                    print(f'candidate={candidate} step={step} target={target} '
                          f'lr={optimizer.param_groups[0]["lr"]:.10f} loss={float(loss.detach()):.7f} '
                          f'elapsed={elapsed:.0f}s ETA={eta:.0f}s finite=true', flush=True)
                if step % 250 == 0 or step == target:
                    qualify()
                if step % 1000 == 0:
                    latest_checkpoint(recovery=True)
        except (FloatingPointError, ResearchRegression) as error:
            training_observer.close()
            raw_observer.close()
            record_failure(error,step+1)
            raise
        finally:
            if hasattr(loader, '_shutdown_workers'):
                loader._shutdown_workers()
    training_activation = training_observer.result()
    training_observer.close()
    stats.update(maxBranchActivation=training_activation['maxBranchActivation'],
                 maxActualGateOutput=training_activation['maxActualGateOutput'])
    stats['maxRawProduct'] = max(stats['maxRawProduct'], raw_observer.value())
    raw_observer.close()
    if stop_requested and step < target:
        checkpoint = latest_checkpoint(recovery=True)
        print(f'candidate={candidate}\nstartStep={start_step}\nendStep={step}\n'
              f'checkpoint={checkpoint.resolve()}\ninterrupted=true\nsafeToPowerOff=true', flush=True)
        return
    checkpoint = latest_checkpoint()
    # Evaluate after the complete optimizer checkpoint exists. A failed
    # validation can be retried with the same target and no new optimizer step.
    probe = failed_probe(model, parts['train'], optimizer)
    if not probe['finite']:
        raise FloatingPointError(f'known sample {FAILED_INDEX} nonfinite at step {step}')
    observer = ActivationObserver(model, candidate)
    try:
        rows, aggregate = validate(model, cases, 'cuda', True)
    finally:
        observer.close()
    aggregate['step'] = step
    activation = observer.result()
    stats['scopes'] = dict(training=training_activation, fixedValidation=activation, knownStress=probe)
    stats['scopeOfFlatMaxima'] = 'training only; fixed validation and known stress are separate'
    stats['adamWStateFinite'] = finite(optimizer.state_dict()['state'])
    if not stats['adamWStateFinite']:
        raise FloatingPointError('AdamW state nonfinite at session end')
    eval_model = PhaseModel(candidate).cpu().eval()
    eval_model.load_state_dict({k: v.detach().cpu() for k, v in model.state_dict().items()}, strict=True)
    synthetic, _ = comparison(eval_model)
    gate = synthetic_gate(synthetic)
    validation = dict(aggregate=aggregate, cases=rows)
    history.append(aggregate)
    improved = aggregate['selectionScore'] < best
    if improved:
        best = aggregate['selectionScore']
    # Persist the post-validation best/history state only after all evaluation
    # succeeds; preserve the pre-validation checkpoint if it does not.
    state_now = snapshot()
    directory = out / 'checkpoints'
    named_save(directory / f'stop_step_{step:06d}.pt', state_now)
    if improved:
        save(directory / 'best.pt', state_now)
    save(directory / 'latest.pt', state_now)
    report_path = report(out, candidate, start_step, step,
                         directory / f'stop_step_{step:06d}.pt', validation,
                         synthetic, gate, probe, activation, stats)
    print(f'candidate={candidate}\nstartStep={start_step}\nendStep={step}\n'
          f'checkpoint={(directory / "latest.pt").resolve()}\n'
          f'validationScore={aggregate["selectionScore"]:.9f}\n'
          f'syntheticGateFailures={len(gate["failures"])}\n'
          f'numericalFailures={stats["numericalFailures"]}\n'
          f'report={report_path.resolve()}\nsafeToPowerOff=true', flush=True)
    for sig, handler in previous_handlers.items():
        signal.signal(sig, handler)


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--candidate', required=True, choices=('A', 'C'))
    parser.add_argument('--stop-at-step', type=int)
    parser.add_argument('--output-root', type=Path, default=DEFAULT_ROOT)
    parser.add_argument('--prepare-only', action='store_true')
    args = parser.parse_args()
    if not args.prepare_only and args.stop_at_step is None:
        parser.error('--stop-at-step required')
    try:
        run(args.candidate, args.stop_at_step, args.output_root, args.prepare_only)
    except BaseException as error:
        try:
            _, corpus = read_corpus(MANIFEST)
            valid, _ = valid_checkpoints(Path(args.output_root) / args.candidate / 'checkpoints', args.candidate, corpus)
            resumable = str(valid[0][2].resolve()) if valid else 'none'
            step = valid[0][0] if valid else 'unknown'
        except Exception:
            resumable, step = 'unknown', 'unknown'
        print(f'candidate={args.candidate}\nerror={type(error).__name__}: {error}\n'
              f'latestValidStep={step}\nlatestValidCheckpoint={resumable}\n'
              'automaticRestart=false', file=sys.stderr, flush=True)
        raise


if __name__ == '__main__':
    main()
