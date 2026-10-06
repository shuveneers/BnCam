"""Compare the fixed 31k W24 baseline and bounded research fork without changing gates."""
import json
from pathlib import Path

import numpy as np
import torch

from ..checkpoint import load
from ..model import BncNeuralV2
from ..session_report import comparison
from ..evaluate import synthetic_gate
from ..train import atomic_json

ROOT = Path('build/bnc-neural-v2')
RUN = ROOT/'training/w24-bounded-fp32'
BASE = ROOT/'training/w24-fp32-smoke'
CASES = ('flat_neutral','two_pixel_horizontal','two_pixel_vertical','frequency_sweep',
         'diagonal_edge','fine_repetitive','repetitive_period_5','repetitive_period_7',
         'repetitive_period_11','repetitive_period_13','thin_lines','zero_chroma_fine_detail',
         'slanted_edge_0417','slanted_edge_131','color_edge','isoluminant_color_edge')


def field(q, key):
    if key == 'rg_edge': return q['opponent_channels']['RG']['chromatic_gradient_retention']
    if key == 'bg_edge': return q['opponent_channels']['BG']['chromatic_gradient_retention']
    return q[key]


def assess(base, fork):
    details = []
    for name in CASES:
        before, after = base[name]['bnc_neural'], fork[name]['bnc_neural']
        current = dict(fixture=name)
        for key in ('missing_rgb_l1','missing_g_l1','opponent_error_rms','opponent_rms',
                    'luma_gradient_retention','rg_edge','bg_edge'):
            current[key] = dict(w24Step31000=field(before,key), boundedFp32Step32555=field(after,key))
        details.append(current)
    score_before = json.loads((BASE/'validation/031000.json').read_text())['aggregate']
    score_after = json.loads((RUN/'validation/032555.json').read_text())['aggregate']
    # Directly compare to the requested baseline; no quality threshold is changed.
    score_regressed = score_after['selectionScore'] > score_before['selectionScore']
    detail_regressions, chromatic_regressions, false_color_regressions = [], [], []
    for name in CASES:
        before, after = base[name]['bnc_neural'], fork[name]['bnc_neural']
        if name not in ('flat_neutral','color_edge','isoluminant_color_edge'):
            a, b = before['luma_gradient_retention'], after['luma_gradient_retention']
            if a is not None and b is not None and abs(b-1) > abs(a-1):
                detail_regressions.append(name)
        if name in ('color_edge','isoluminant_color_edge'):
            for channel in ('RG','BG'):
                a = before['opponent_channels'][channel]['chromatic_gradient_retention']
                b = after['opponent_channels'][channel]['chromatic_gradient_retention']
                if a is not None and b is not None and abs(b-1) > abs(a-1):
                    chromatic_regressions.append(name+'/'+channel)
        if name in ('flat_neutral','two_pixel_horizontal','two_pixel_vertical','frequency_sweep',
                    'diagonal_edge','thin_lines','zero_chroma_fine_detail'):
            if after['opponent_rms'] > before['opponent_rms']:
                false_color_regressions.append(name)
    return dict(validation31k=score_before, validation32k=score_after,
                scoreRegression=score_regressed, detailRegressions=detail_regressions,
                chromaticEdgeRegressions=chromatic_regressions,
                zeroChromaFalseColorRegressions=false_color_regressions,
                qualityRegression=bool(score_regressed or detail_regressions or
                                       chromatic_regressions or false_color_regressions),
                cases=details)


def validation_means(path):
    cases = json.loads(path.read_text())['cases']
    keys = ('missing_rgb_l1','missing_g_l1','rg_rms','bg_rms','luma_gradient_ratio',
            'opponent_gradient_ratio','neutral_false_color_energy',
            'opponent_gradient_error_rms','alternating_opponent_sign_change_error')
    result = {}
    for key in keys:
        values = [row[key] for row in cases if row[key] is not None]
        result[key] = float(np.mean(values)) if values else None
    for channel in ('RG','BG'):
        values = [row['chromatic_edge_retention'][channel] for row in cases
                  if row['chromatic_edge_retention'][channel] is not None]
        result[channel+'_edge_retention'] = float(np.mean(values)) if values else None
    return result


def main():
    torch.set_num_threads(2)
    baseline = load(BASE/'checkpoints/latest.pt')
    if baseline['step'] != 31000: raise ValueError('W24 baseline is not step 31000')
    model = BncNeuralV2()
    model.load_state_dict(baseline['model'])
    model.eval()
    rows, _ = comparison(model)
    gate = synthetic_gate(rows)
    evidence = json.loads((RUN/'sessions/step_032555/evidence.json').read_text())
    fork = evidence['synthetic']
    result = assess(rows, fork)
    result['validationMetrics'] = dict(w24Step31000=validation_means(BASE/'validation/031000.json'),
                                       boundedFp32Step32555=validation_means(RUN/'validation/032555.json'))
    result['syntheticGate31k'] = gate
    result['syntheticGate32k'] = evidence['syntheticGate']
    result['routes'] = dict(w24Step31000=rows, boundedFp32Step32555=fork,
                            boundedAmpPrototype=dict(step31755=.00918094,
                                                     step32055=.00890413846221957,
                                                     step32555=.008667568318961802,
                                                     note='validation-only prototype; weights/synthetic evidence were not saved'))
    result['safeToContinueTo55k'] = not result['qualityRegression'] and evidence['numerics']['adamWStateFinite']
    out = RUN/'sessions/step_032555'
    atomic_json(out/'comparison.json', result)
    status = json.loads((RUN/'status.json').read_text())
    status['qualityRegression'] = result['qualityRegression']
    status['safeToContinueTo55k'] = result['safeToContinueTo55k']
    status['commandToContinue'] = status['nextCommand'] if result['safeToContinueTo55k'] else 'none; quality regression'
    status['nextCommand'] = status['commandToContinue']
    atomic_json(RUN/'status.json', status)
    lines = ['# Bounded-gate FP32 research qualification','',
             f"Fixed validation: W24 31k {result['validation31k']['selectionScore']:.8f}; "
             f"fork 32,555 {result['validation32k']['selectionScore']:.8f}.",
             f"Unchanged synthetic gate failures: {len(gate['failures'])} at 31k; "
             f"{len(evidence['syntheticGate']['failures'])} at 32,555.",
             f"Quality regression: {result['qualityRegression']}. Safe to continue: {result['safeToContinueTo55k']}.",
             '', 'Full validation rows, synthetic routes, and gate failures are in evidence.json and comparison.json.',
             '', '| Fixed validation metric | W24 31k | Bounded FP32 32,555 |',
             '|---|---:|---:|']
    fmt = lambda v: 'n/a' if v is None else f'{v:.7f}'
    for key, before in result['validationMetrics']['w24Step31000'].items():
        after = result['validationMetrics']['boundedFp32Step32555'][key]
        lines.append('| '+key+' | '+fmt(before)+' | '+fmt(after)+' |')
    lines += ['',
             '', '| Fixture | Route | Missing RGB | Missing G | Opponent RMS error | Luma-gradient retention | RG edge retention | BG edge retention |',
             '|---|---|---:|---:|---:|---:|---:|---:|']
    for name in CASES:
        for route, q in [('Malvar', rows[name].get('malvar')),('AMaZE',rows[name].get('amaze')),
                         ('BnC v1',rows[name].get('bnc_v1')),('W24 31k',rows[name].get('bnc_neural')),
                         ('bounded FP32 32,555',fork[name].get('bnc_neural'))]:
            if q is None: continue
            values = [q['missing_rgb_l1'],q['missing_g_l1'],q['opponent_error_rms'],
                      q['luma_gradient_retention'],field(q,'rg_edge'),field(q,'bg_edge')]
            lines.append('| '+name+' | '+route+' | '+' | '.join(map(fmt, values))+' |')
    lines += ['', 'Bounded-AMP prototype fixed-validation scores: 31,755 0.00918094; '
              '32,055 0.00890414; 32,555 0.00866757. A deterministic reproduction supplies synthetic fixture comparison below.',
              'Bounded-FP32 transient is recorded at 31,755, 32,055 and 32,555 in validation/*.json.',
              '', 'Detail regressions: '+', '.join(result['detailRegressions']),
              'Chromatic-edge regressions: '+', '.join(result['chromaticEdgeRegressions']),
              'Zero-chroma false-color regressions: '+', '.join(result['zeroChromaFalseColorRegressions']),
              '']
    (out/'report.md').write_text('\n'.join(lines), encoding='utf-8')
    print(json.dumps({k:result[k] for k in ('scoreRegression','detailRegressions',
          'chromaticEdgeRegressions','zeroChromaFalseColorRegressions','qualityRegression',
          'safeToContinueTo55k')},indent=2),flush=True)


if __name__ == '__main__': main()
