"""Reviewable zero-update and 200-step cap comparison."""
import json
from pathlib import Path

import numpy as np

from ..checkpoint import load
from ..bounded_fp32.compare import CASES
from .study import ROOT, SOURCE_SHA


def mean_metric(cases, key):
    values=[case[key] for case in cases if case[key] is not None]
    return float(np.mean(values)) if values else None


def main():
    summary=json.loads((ROOT/'summary.json').read_text())
    baseline=json.loads(Path('build/bnc-neural-v2/training/w24-bounded-fp32/sessions/step_032555/comparison.json').read_text())
    baseline_validation=json.loads(Path('build/bnc-neural-v2/training/w24-fp32-smoke/validation/031000.json').read_text())
    a={str(c):json.loads((ROOT/f'c{c}/phase-a.json').read_text()) for c in (16,32,64)}
    b={str(c):json.loads((ROOT/f'c{c}/phase-b.json').read_text()) for c in (16,32,64)}
    decisions=summary['phaseBDecisions']
    for cap in (16,32,64):
        state=load(ROOT/f'c{cap}/checkpoints/latest.pt')
        if (b[str(cap)]['startStep'],b[str(cap)]['endStep'],state['step'],
                state['sampler']['nextSampleIndex'],state['scheduler']['nextStep']) != (31555,31755,31755,1016160,31755):
            raise ValueError(f'C={cap} did not complete independent 200-step session')
        if state['capStudy']['cap']!=cap or state['capStudy']['sourceSHA256']!=SOURCE_SHA:
            raise ValueError(f'C={cap} did not originate from diagnostic checkpoint')
        steps={float(v['step']) for v in state['optimizer']['state'].values() if 'step' in v}
        if steps!={31755.}:
            raise ValueError(f'C={cap} AdamW step mismatch: {steps}')
    old64=json.loads(Path('build/bnc-neural-v2/training/w24-bounded-fp32/validation/031755.json').read_text())
    c64score=b['64']['validation']['aggregate']['selectionScore']
    if abs(c64score-old64['aggregate']['selectionScore'])>1e-7:
        raise ValueError('C=64 deterministic reproduction differs from previous fork')
    rows=[]
    for name in CASES:
        for route,q in [('W24 31k',baseline['routes']['w24Step31000'][name]['bnc_neural'])]+[
            (f'C={cap} @200',b[str(cap)]['synthetic'][name]['bnc_neural']) for cap in (16,32,64)]:
            rows.append(dict(fixture=name,route=route,missingRGB=q['missing_rgb_l1'],
                             missingGreen=q['missing_g_l1'],opponentErrorRMS=q['opponent_error_rms'],
                             opponentRMS=q['opponent_rms'],
                             lumaGradientRetention=q['luma_gradient_retention'],
                             rgEdgeRetention=q['opponent_channels']['RG']['chromatic_gradient_retention'],
                             bgEdgeRetention=q['opponent_channels']['BG']['chromatic_gradient_retention']))
    validation_means={'W24 31k':{
        key:mean_metric(baseline_validation['cases'],key) for key in
        ('missing_rgb_l1','missing_g_l1','rg_rms','bg_rms','luma_gradient_ratio',
         'opponent_gradient_ratio','neutral_false_color_energy') }}
    for cap in (16,32,64):
        validation_means[f'C={cap} @200']={key:mean_metric(b[str(cap)]['validation']['cases'],key) for key in
            ('missing_rgb_l1','missing_g_l1','rg_rms','bg_rms','luma_gradient_ratio',
             'opponent_gradient_ratio','neutral_false_color_energy')}
    result=dict(sourceCheckpoint=summary['startingCheckpoint'],sourceSHA256=SOURCE_SHA,
                baselineScore=summary['validationBaseline'],phaseA=a,phaseBDecisions=decisions,
                selectedCap=summary['selectedCap'],phaseC=summary['phaseC'],
                validationMeans=validation_means,syntheticRows=rows,
                previousC64Score=old64['aggregate']['selectionScore'],
                reproducedC64Score=c64score,
                safeToContinueResearch=summary['safeToContinueResearch'])
    (ROOT/'comparison.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    fmt=lambda v:'n/a' if v is None else f'{v:.7f}'
    lines=['# BnC Neural v2 — bounded-gate cap study','',
           f"All caps load the same SHA-256 {SOURCE_SHA} step-31,555 diagnostic checkpoint; "
           'full neural forward and loss are FP32. AdamW and cosine scheduler step counters reach 31,755 independently.',
           '', '## Phase A: zero updates','',
           '| Cap | Fixed score | Failed thin-lines output max | Failed sample loss | Dry batch max gradient | Max raw product | Max bounded gate | Normal values >1% / >5% / >10% changed |',
           '|---:|---:|---:|---:|---:|---:|---:|---:|']
    for cap in (16,32,64):
        q=a[str(cap)];p=q['normalGateAmplitudeChange']
        vals=[cap,q['validationScore'],q['failedSampleOutputMax'],q['failedSampleLoss'],
              q['maxGradient'],q['maxRawGateProduct'],q['maxBoundedGate']]
        lines.append('| '+' | '.join([str(vals[0])]+[fmt(v) for v in vals[1:]]+[f"{p['over1Percent']:.9%} / {p['over5Percent']:.9%} / {p['over10Percent']:.9%}"])+' |')
    lines+=['','Normal activation percentages use all 410,517,504 gate values in the fixed validation set. '
            'The failed sample is excluded from that percentage and measured separately.','',
            '## Phase B: 200 independent FP32 optimizer updates','',
            '| Route | Fixed score | Thin-lines regression | Isoluminant RG/BG regression | Detail traded for lower false color | Synthetic gate failures |',
            '|---|---:|---|---|---|---:|']
    lines.append(f"| W24 31k | {summary['validationBaseline']:.7f} | baseline | baseline | baseline | {len(baseline['syntheticGate31k']['failures'])} |")
    for cap in (16,32,64):
        d=decisions[str(cap)]
        lines.append(f"| C={cap} | {d['validationScore']:.7f} | {d['thinLinesRegression']} | "
                     f"{d['isoluminantRegression']} | {', '.join(d['detailForFalseColorTradeoffs']) or 'none'} | "
                     f"{d['syntheticGateFailures']} |")
    lines+=['','| Fixed-validation metric | W24 31k | C=16 | C=32 | C=64 |',
            '|---|---:|---:|---:|---:|']
    for key in validation_means['W24 31k']:
        lines.append('| '+key+' | '+' | '.join(fmt(validation_means[route][key]) for route in
                     ('W24 31k','C=16 @200','C=32 @200','C=64 @200'))+' |')
    lines+=['','## Synthetic fixtures','',
            '| Fixture | Route | Missing RGB | Missing G | Opponent RMS error | Opponent RMS (neutral: false color) | Luma-gradient retention | RG edge retention | BG edge retention |',
            '|---|---|---:|---:|---:|---:|---:|---:|---:|']
    for row in rows:
        lines.append('| '+row['fixture']+' | '+row['route']+' | '+' | '.join(fmt(row[key]) for key in
                     ('missingRGB','missingGreen','opponentErrorRMS','opponentRMS',
                      'lumaGradientRetention','rgEdgeRetention','bgEdgeRetention'))+' |')
    lines+=['',f"Selected cap: {summary['selectedCap']}. "
            f"Phase C: {'not started' if summary['phaseC'] is None else 'completed at step 32,555'}. "
            f"Safe to continue research: {summary['safeToContinueResearch']}.",
            'No quality gate, training objective, dataset, optimizer, scheduler, or product code was changed.','']
    (ROOT/'report.md').write_text('\n'.join(lines),encoding='utf-8')
    print(json.dumps(dict(selectedCap=summary['selectedCap'],
                          scores={str(c):decisions[str(c)]['validationScore'] for c in (16,32,64)},
                          c64DeterministicMatch=True,safeToContinueResearch=summary['safeToContinueResearch'])),flush=True)


if __name__=='__main__':main()
