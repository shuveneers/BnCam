"""Complete synthetic comparator for the deterministically reproduced AMP prototype."""
import json
from pathlib import Path

import torch

from ..evaluate import synthetic_gate
from ..session_report import comparison
from ..train import atomic_json
from .model import BoundedBncNeuralV2
from .compare import CASES, RUN, field


def main():
    torch.set_num_threads(2)
    source = RUN/'amp-prototype-reference/step_032555.pt'
    state = torch.load(source, map_location='cpu', weights_only=True)
    if state['step'] != 32555 or state['trainingPrecision'] != 'AMP':
        raise ValueError('wrong AMP reference')
    model = BoundedBncNeuralV2()
    model.load_state_dict(state['model'], strict=True)
    model.eval()
    rows, _ = comparison(model)
    gate = synthetic_gate(rows)
    out = RUN/'sessions/step_032555'
    atomic_json(out/'amp-prototype-synthetic.json', dict(step=32555, synthetic=rows, syntheticGate=gate,
                referenceModel=str(source.resolve())))
    result = json.loads((out/'comparison.json').read_text())
    result['routes']['boundedAmpPrototype']['synthetic'] = rows
    result['routes']['boundedAmpPrototype']['syntheticGate'] = gate
    atomic_json(out/'comparison.json', result)
    fmt = lambda v: 'n/a' if v is None else f'{v:.7f}'
    lines = ['','## Reproduced bounded-AMP comparator','',
             f"Fixed synthetic gate failures: {len(gate['failures'])}. Source step 31,555, 1,000 AMP updates, end step 32,555.",
             '', '| Fixture | Missing RGB | Missing G | Opponent RMS error | Luma-gradient retention | RG edge retention | BG edge retention |',
             '|---|---:|---:|---:|---:|---:|---:|']
    for name in CASES:
        q = rows[name]['bnc_neural']
        values = [q['missing_rgb_l1'],q['missing_g_l1'],q['opponent_error_rms'],
                  q['luma_gradient_retention'],field(q,'rg_edge'),field(q,'bg_edge')]
        lines.append('| '+name+' | '+' | '.join(map(fmt,values))+' |')
    report = out/'report.md'
    report.write_text(report.read_text(encoding='utf-8')+'\n'.join(lines)+'\n', encoding='utf-8')
    print(json.dumps(dict(referenceStep=state['step'],syntheticGateFailures=len(gate['failures']))),flush=True)


if __name__ == '__main__': main()
