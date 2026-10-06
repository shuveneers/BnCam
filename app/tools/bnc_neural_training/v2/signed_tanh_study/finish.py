"""Select or stop after independent signed-tanh 200-step runs; write all evidence."""
import json

import numpy as np

from ..bounded_fp32.compare import CASES
from .distribution import ROOT
from .phase_a import TAUS


def mean(cases,key):
    values=[row[key] for row in cases if row[key] is not None]
    return float(np.mean(values)) if values else None


def decision(tau,zero,trained,baseline,source_zero):
    if trained['numericalFailure'] is not None or trained['endStep']!=31755:
        return dict(tau=tau,eligible=False,numericalFailure=trained['numericalFailure'],
                    validationScore=None,validationTowardBaseline=False,
                    thinLinesRegression=None,chromaticRegression=None,
                    detailRegressions=[],falseColorHiddenByDetail=[],syntheticGateFailures=None)
    before=baseline['routes']['w24Step31000']
    after=trained['synthetic']
    thin_before,thin_after=before['thin_lines']['bnc_neural'],after['thin_lines']['bnc_neural']
    thin=any(thin_after[key]>thin_before[key] for key in
             ('missing_rgb_l1','missing_g_l1','opponent_error_rms')) or (
             abs(thin_after['luma_gradient_retention']-1)>abs(thin_before['luma_gradient_retention']-1))
    chromatic=[]
    for name in ('color_edge','isoluminant_color_edge'):
        for channel in ('RG','BG'):
            a=before[name]['bnc_neural']['opponent_channels'][channel]['chromatic_gradient_retention']
            b=after[name]['bnc_neural']['opponent_channels'][channel]['chromatic_gradient_retention']
            if a is None or b is None or abs(b-1)>abs(a-1):
                chromatic.append(name+'/'+channel)
    detail=[]
    hidden=[]
    for name in ('two_pixel_horizontal','two_pixel_vertical','frequency_sweep','diagonal_edge',
                 'fine_repetitive','repetitive_period_5','repetitive_period_7',
                 'repetitive_period_11','repetitive_period_13','thin_lines',
                 'zero_chroma_fine_detail','slanted_edge_0417','slanted_edge_131'):
        a,b=before[name]['bnc_neural'],after[name]['bnc_neural']
        ar,br=a['luma_gradient_retention'],b['luma_gradient_retention']
        if ar is None or br is None:continue
        if abs(br-1)>abs(ar-1):
            detail.append(name)
            if b['opponent_rms']<a['opponent_rms']:hidden.append(name)
    score=trained['validation']['aggregate']['selectionScore']
    baseline_score=baseline['validation31k']['selectionScore']
    zero_score=zero['validation']['aggregate']['selectionScore']
    toward=score<=baseline_score or score<zero_score
    gate_failures=len(trained['syntheticGate']['failures'])
    baseline_failures=len(baseline['syntheticGate31k']['failures'])
    eligible=(trained['numerical']['numericalFailures']==0 and toward and not thin and
              not chromatic and not detail and not hidden and gate_failures<=baseline_failures)
    return dict(tau=tau,eligible=eligible,numericalFailure=None,
                validationScore=score,validationBaseline=baseline_score,
                zeroUpdateScore=zero_score,validationTowardBaseline=toward,
                thinLinesRegression=thin,chromaticRegression=chromatic,
                detailRegressions=detail,falseColorHiddenByDetail=hidden,
                syntheticGateFailures=gate_failures,
                baselineSyntheticGateFailures=baseline_failures)


def main():
    distribution=json.loads((ROOT/'distribution.json').read_text())
    source_zero=json.loads((ROOT/'source-zero-update.json').read_text())
    screen=json.loads((ROOT/'phase-a-screen.json').read_text())
    baseline=json.loads(open('build/bnc-neural-v2/training/w24-bounded-fp32/sessions/step_032555/comparison.json').read())
    cap_study={str(cap):json.loads(open(f'build/bnc-neural-v2/cap-study/c{cap}/phase-b.json').read()) for cap in (16,32,64)}
    zero={str(tau):json.loads((ROOT/f'tau{tau}/zero-update.json').read_text()) for tau in TAUS}
    trained={str(tau):json.loads((ROOT/f'tau{tau}/after-200.json').read_text()) for tau in TAUS}
    decisions={str(tau):decision(tau,zero[str(tau)],trained[str(tau)],baseline,source_zero) for tau in TAUS}
    eligible=[d for d in decisions.values() if d['eligible']]
    selected=min(eligible,key=lambda x:x['validationScore'])['tau'] if eligible else None
    numeric_failures=sum(q['numerical']['numericalFailures'] for q in trained.values())
    route_rows={}
    for name in CASES:
        route_rows[name]={
            'W24 31k':baseline['routes']['w24Step31000'][name]['bnc_neural'],
            **{f'product cap {cap} @200':cap_study[str(cap)]['synthetic'][name]['bnc_neural'] for cap in (16,32,64)},
            **{f'signed tau {tau} @200':trained[str(tau)]['synthetic'][name]['bnc_neural'] for tau in TAUS
               if trained[str(tau)]['synthetic'] is not None}}
    baseline_validation=json.loads(open('build/bnc-neural-v2/training/w24-fp32-smoke/validation/031000.json').read())
    validation_rows={'W24 31k':baseline_validation['cases']}
    validation_rows.update({f'product cap {cap} @200':cap_study[str(cap)]['validation']['cases'] for cap in (16,32,64)})
    validation_rows.update({f'signed tau {tau} @200':trained[str(tau)]['validation']['cases'] for tau in TAUS
                            if trained[str(tau)]['validation'] is not None})
    keys=('missing_rgb_l1','missing_g_l1','rg_rms','bg_rms','luma_gradient_ratio',
          'opponent_gradient_ratio','neutral_false_color_energy')
    validation_means={route:{key:mean(rows,key) for key in keys} for route,rows in validation_rows.items()}
    result=dict(branchActivationDistribution=str((ROOT/'distribution.json').resolve()),
                testedTau=list(TAUS),zeroUpdateResults={str(t):dict(score=zero[str(t)]['validation']['aggregate']['selectionScore'],
                    failedSampleOutputMax=zero[str(t)]['failedSampleOutputMax'],
                    failedSampleLoss=zero[str(t)]['failedSampleLoss'],
                    maxGradient=zero[str(t)]['maxGradient'],
                    maxGateInput=zero[str(t)]['maxGateInput'],maxGateOutput=zero[str(t)]['maxGateOutput'],
                    normalGateDifference=zero[str(t)]['normalGateDifference'],
                    syntheticGateFailures=len(zero[str(t)]['syntheticGate']['failures']),
                    eligibleFor200Steps=screen[str(t)]['eligibleFor200Steps']) for t in TAUS},
                resultAfter200=decisions,numericalFailures=numeric_failures,
                detailRegression={key:value['detailRegressions'] for key,value in decisions.items()},
                chromaticRegression={key:value['chromaticRegression'] for key,value in decisions.items()},
                selectedTau=selected,safeToContinueResearch=selected is not None,
                validationMeans=validation_means,synthetic=route_rows,
                noLongerTraining=True,productCodeChanged=False)
    (ROOT/'summary.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    fmt=lambda v:'n/a' if v is None else f'{v:.7f}'
    lines=['# BnC Neural v2 — symmetric signed tanh gate study','',
           'The original W24 weights, AdamW state, LR schedule, data, losses, fixed validation and synthetic gates are unchanged. '
           'All candidate neural forward and loss calculations use FP32. Each 200-step run begins from the same step-31,555 checkpoint.',
           '', '## Activation distribution before changing the gate','',
           'The normal quantiles cover all 116 fixed-validation cases. They are estimated by a deterministic log histogram '
           '(maximum multiplicative bin width 0.155%); failed-sample quantiles are exact. Values are absolute branch activations.',
           '', '| Block | Branch | Normal P99 | P99.9 | P99.99 | P99.999 | max | Failed P99 | P99.9 | P99.99 | P99.999 | max |',
           '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for name in distribution['normal']:
        for branch in ('a','b'):
            n=distribution['normal'][name][branch]
            f=distribution['failedSample'][name][branch]
            lines.append('| '+name+' | '+branch+' | '+' | '.join(fmt(v) for v in
                         [n[k] for k in ('P99','P99.9','P99.99','P99.999','max')]+
                         [f[k] for k in ('P99','P99.9','P99.99','P99.999','max')])+' |')
    rationale=json.loads((ROOT/'tau-rationale.json').read_text())
    lines+=['',f"Tau selection: {rationale['rule']}. {rationale['extremeOnset']}",'',
            '## Zero optimizer updates','',
            '| τ | Fixed score | Failed output max | Failed loss | Dry max gradient | Max input a/b | Max gate output | Normal >1% / >5% / >10% changed | Gate failures |',
            '|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for tau in TAUS:
        q=zero[str(tau)];p=q['normalGateDifference']
        lines.append(f"| {tau} | {fmt(q['validation']['aggregate']['selectionScore'])} | "
                     f"{fmt(q['failedSampleOutputMax'])} | {fmt(q['failedSampleLoss'])} | "
                     f"{fmt(q['maxGradient'])} | {fmt(q['maxGateInput'])} | {fmt(q['maxGateOutput'])} | "
                     f"{p['over1Percent']:.7%} / {p['over5Percent']:.7%} / {p['over10Percent']:.7%} | "
                     f"{len(q['syntheticGate']['failures'])} |")
    lines+=['','## Independent 200-step results','',
            '| Route | Fixed score | Numerical failure | Thin-lines regression | Chromatic regression | Detail regressions | Hidden false color | Gate failures |',
            '|---|---:|---|---|---|---|---|---:|']
    lines.append(f"| W24 31k | {fmt(baseline['validation31k']['selectionScore'])} | baseline | baseline | baseline | baseline | baseline | {len(baseline['syntheticGate31k']['failures'])} |")
    for cap in (16,32,64):
        q=cap_study[str(cap)]
        lines.append(f"| product cap {cap} | {fmt(q['validation']['aggregate']['selectionScore'])} | 0 | see earlier cap study | see earlier cap study | see earlier cap study | see earlier cap study | {len(q['syntheticGate']['failures'])} |")
    for tau in TAUS:
        d=decisions[str(tau)]
        lines.append(f"| signed τ={tau} | {fmt(d['validationScore'])} | {d['numericalFailure'] or '0'} | "
                     f"{d['thinLinesRegression']} | {', '.join(d['chromaticRegression'] or []) or 'none'} | "
                     f"{', '.join(d['detailRegressions']) or 'none'} | "
                     f"{', '.join(d['falseColorHiddenByDetail']) or 'none'} | "
                     f"{d['syntheticGateFailures'] if d['syntheticGateFailures'] is not None else 'n/a'} |")
    lines+=['','| Fixed-validation metric | '+' | '.join(validation_means)+' |',
            '|---|'+'---:|'*len(validation_means)]
    for key in keys:
        lines.append('| '+key+' | '+' | '.join(fmt(validation_means[route][key]) for route in validation_means)+' |')
    lines+=['','## Synthetic fixtures','',
            '| Fixture | Route | Missing RGB | Missing G | Opponent error RMS | Opponent RMS | Luma-gradient retention | RG edge retention | BG edge retention |',
            '|---|---|---:|---:|---:|---:|---:|---:|---:|']
    for name,routes in route_rows.items():
        for route,q in routes.items():
            lines.append('| '+name+' | '+route+' | '+' | '.join(fmt(v) for v in
                         (q['missing_rgb_l1'],q['missing_g_l1'],q['opponent_error_rms'],
                          q['opponent_rms'],q['luma_gradient_retention'],
                          q['opponent_channels']['RG']['chromatic_gradient_retention'],
                          q['opponent_channels']['BG']['chromatic_gradient_retention']))+' |')
    lines+=['',f'Selected τ: {selected}. Safe to continue research: {selected is not None}.',
            'No training past 200 updates was run; no Vulkan or product integration was changed.','']
    (ROOT/'report.md').write_text('\n'.join(lines),encoding='utf-8')
    print(json.dumps(dict(selectedTau=selected,numericalFailures=numeric_failures,
                          decisions=decisions,safeToContinueResearch=selected is not None)),flush=True)


if __name__=='__main__':main()
