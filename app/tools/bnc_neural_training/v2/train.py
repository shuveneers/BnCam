"""W24 session CLI; the unchanged global optimization schedule spans manual sessions.

Every session saves complete state, validates, reports synthetic gates, then stops.
No automatic continuation or v1 retraining.
"""
from pathlib import Path
import hashlib
import json
import numpy as np
import torch
from .losses import diagnostics


def atomic_json(path,value):
    temp=path.with_suffix(path.suffix+".partial")
    temp.write_text(json.dumps(value,indent=2,allow_nan=False),encoding="utf-8");temp.replace(path)


def publish_status(run,value):
    run=Path(run)
    atomic_json(run/'status.json',value)
    root=Path('build/bnc-neural-v2/training').resolve()
    active_fork=(run.resolve().parent==root and (run/'source-checkpoint.json').is_file())
    if run.resolve()==(root/'w24') or active_fork:
        atomic_json(Path('build/bnc-neural-v2/phase-a-status.json'),value)


def source_snapshot(out):
    source=Path(__file__).resolve().parent.parent;sha=hashlib.sha256()
    for file in sorted(source.rglob("*.py")):
        relative=file.relative_to(source);data=file.read_bytes()
        sha.update(str(relative).replace("\\","/").encode());sha.update(data)
        dest=out/"source"/relative;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data)
    return sha.hexdigest()


def learning_rate(step):
    return 3e-5+(1e-3-3e-5)*.5*(1+np.cos(np.pi*min(step,100000)/100000))


def plateau(history):
    if len(history)<20:return False,{}
    before,after=history[-20:-10],history[-10:]
    changes={}
    for key in ("missing","opponent","edge"):
        a=np.mean([h[key] for h in before]);b=np.mean([h[key] for h in after])
        changes[key]=float((a-b)/max(a,1e-12))
    return all(v<=.002 for v in changes.values()),changes


@torch.no_grad()
def validate(model,cases,device,structured):
    model.eval();rows=[]
    for name,a,b in cases:
        a,b=a.unsqueeze(0).to(device),b.unsqueeze(0).to(device)
        # True FP32 validation: independent from AMP optimizer numerics.
        q=diagnostics(a,model(a),b,structured);q["case"]=name;rows.append(q)
    aggregate=dict(missing=float(np.mean([q['missing_rgb_l1'] for q in rows])),
        opponent=float(np.mean([.5*(q['rg_rms']+q['bg_rms']) for q in rows])),
        edge=float(np.mean([q['losses']['luma_gradient']+q['losses']['opponent_gradient']+q['losses']['chromatic_edge'] for q in rows])))
    aggregate['selectionScore']=aggregate['missing']+.2*aggregate['opponent']+.2*aggregate['edge']
    aggregate['perCaseExtrema']={key:dict(min=min((q[key] for q in rows if q[key] is not None),default=None),
        max=max((q[key] for q in rows if q[key] is not None),default=None)) for key in ('missing_rgb_l1','missing_g_l1','rg_rms','bg_rms',
        'luma_gradient_ratio','opponent_gradient_ratio','neutral_false_color_energy')}
    model.train();return rows,aggregate


def main():
    from .session_train import main as session_main
    session_main()


if __name__=='__main__':main()
