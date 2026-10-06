"""Compact step-0/500/2000 comparison; never schedules further training."""
import json

from ..checkpoint import load
from ..train import atomic_json
from .model import GATES, VARIANTS
from .run import FIXTURES, ROOT, TARGETS


def fmt(value):
    return 'n/a' if value is None else f'{value:.6f}'


def main():
    rows = {}
    identities = {}
    for variant in VARIANTS:
        root = ROOT / variant
        identities[variant] = json.loads((root / 'identity.json').read_text())
        rows[variant] = {step: json.loads((root / f'step_{step:04d}.json').read_text())
                         for step in TARGETS}
        state = load(root / 'checkpoints/latest.pt')
        if state['step'] != 2000 or state['sampler']['nextSampleIndex'] != 64000:
            raise ValueError(f'{variant} stopped at an unexpected optimizer position')
    if len({x['initialWeightsSHA256'] for x in identities.values()}) != 1:
        raise ValueError('initial random weights differ across candidates')
    if len({x['datasetSHA256'] for x in identities.values()}) != 1:
        raise ValueError('dataset differs across candidates')
    summary = dict(initialWeightsSHA256=identities['A']['initialWeightsSHA256'],
                   datasetSHA256=identities['A']['datasetSHA256'],
                   variants={variant:{str(step):dict(
                       validationScore=rows[variant][step]['validation']['aggregate']['selectionScore'],
                       syntheticGateFailures=len(rows[variant][step]['syntheticGate']['failures']),
                       failedSample=rows[variant][step]['failedSample'],
                       maxBranchActivation=rows[variant][step]['maxBranchActivation'],
                       maxGateOutput=rows[variant][step]['maxGateOutput'],
                       maxTrainingGradient=rows[variant][step]['numerical']['maxGradient'],
                       numericalFailures=rows[variant][step]['numerical']['numericalFailures'],
                       measuredSenselMaxError=rows[variant][step]['measuredSenselMaxError'])
                       for step in TARGETS} for variant in VARIANTS},
                   stoppedAt=2000, laterTrainingStarted=False,
                   productOrVulkanChanged=False)
    atomic_json(ROOT / 'summary.json', summary)
    lines = ['# BnC Neural v3 — Phase A only', '',
             'All three W24-topology candidates use the same new random weight tensor, corpus, '
             'fixed 116-case validation, synthetic fixtures, AdamW, cosine LR, data index order, '
             'canonicalization, measured-sensel merge, losses and FP32 arithmetic. '
             'Only the nine gate operations differ. No W24 training weights or optimizer state were loaded.',
             '', '| Variant | Step | Fixed score | Gate failures | Failed thin-lines finite | '
             'Failed output max | Failed loss | Branch max | Gate max | Training gradient max | '
             'Failed-sample gradient max | Numerical failures |',
             '|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|']
    for variant in VARIANTS:
        for step in TARGETS:
            q = rows[variant][step]
            p = q['failedSample']
            values = [fmt(q['validation']['aggregate']['selectionScore']),
                      str(len(q['syntheticGate']['failures'])), str(p['finite']),
                      fmt(p['outputMax']), fmt(p['loss']),
                      fmt(q['maxBranchActivation']), fmt(q['maxGateOutput']),
                      fmt(q['numerical']['maxGradient']), fmt(p['maxGradient']),
                      str(q['numerical']['numericalFailures'])]
            lines.append(f'| {variant}: {GATES[variant]} | {step} | '+' | '.join(values)+' |')
    lines += ['', '## Synthetic fixtures', '',
              '| Variant | Step | Fixture | Missing RGB | Missing G | Opponent RMS | '
              'Luma-gradient retention | RG edge retention | BG edge retention | Measured-sensel error |',
              '|---|---:|---|---:|---:|---:|---:|---:|---:|---:|']
    for variant in VARIANTS:
        for step in TARGETS:
            for name in FIXTURES:
                q = rows[variant][step]['synthetic'][name]
                values = [q['missing_rgb_l1'], q['missing_g_l1'], q['opponent_rms'],
                          q['luma_gradient_retention'],
                          q['opponent_channels']['RG']['chromatic_gradient_retention'],
                          q['opponent_channels']['BG']['chromatic_gradient_retention'],
                          q['sampled_sensel_max_error']]
                lines.append(f'| {variant} | {step} | {name} | '+' | '.join(map(fmt, values))+' |')
    lines += ['', 'No training after 2,000 updates; no Vulkan or product integration.', '']
    (ROOT / 'report.md').write_text('\n'.join(lines), encoding='utf-8')
    print(json.dumps(dict(report=str((ROOT / 'report.md').resolve()),
                          scores={v:{str(s):summary['variants'][v][str(s)]['validationScore']
                                     for s in TARGETS} for v in VARIANTS})), flush=True)


if __name__ == '__main__':
    main()
