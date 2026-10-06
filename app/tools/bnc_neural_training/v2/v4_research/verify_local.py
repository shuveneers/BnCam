"""Replays real A/C failures and checks fresh V4, without original checkpoint writes."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

import torch

from ..checkpoint import load, objective_identity
from ..data import TrainingData
from ..training_safety import edge_input, probe, stress_probe, guard, serializable
from ..v3_phase_a.model import PhaseModel
from .model import ResearchModel
from .run import ARCHIVE, ARCHIVE_SHA, SEED, setup


def verify(checkpoint_root,archive,output,device='cpu'):
    setup(device)
    a,b=edge_input(archive,ARCHIVE_SHA)
    result=dict(device=device,torchVersion=torch.__version__,sourceObjective=objective_identity(),
                archiveSHA256=ARCHIVE_SHA,originalCheckpointWrites=0,replays=[])
    for candidate in ('A','C'):
        baseline=None
        for step in (15000,29000,30000):
            name='recovery_step_029000.pt' if step==29000 else f'stop_step_{step:06d}.pt'
            path=Path(checkpoint_root)/candidate/'checkpoints'/name
            state=load(path)
            if state['objectiveIdentity']!=objective_identity():
                raise ValueError('original checkpoint objective differs from current source')
            model=PhaseModel(candidate).to(device)
            model.load_state_dict(state['model'],strict=True)
            opt=torch.optim.AdamW(model.parameters(),lr=.001,betas=(.9,.99),weight_decay=1e-4)
            opt.load_state_dict(state['optimizer'])
            check=dict(stress=stress_probe(model,opt),edge=probe(model,None,a,b,'frozen_isoluminant_edge',False))
            if step==15000:
                baseline=copy.deepcopy(check)
            screen=guard(check,baseline)
            row=dict(candidate=candidate,step=step,checkpointSHA256=hashlib.sha256(path.read_bytes()).hexdigest(),
                     sentinels=check,researchGuard=screen)
            result['replays'].append(row)
            expected=step==15000
            if screen['passed']!=expected:
                raise AssertionError(f'unexpected real-fixture guard result: {candidate} step {step}')
            print(f'{candidate} step {step}: guard={screen["passed"]} reasons={screen["failures"]}',flush=True)
    for candidate in ('N','S'):
        torch.manual_seed(SEED)
        model=ResearchModel(candidate).to(device)
        opt=torch.optim.AdamW(model.parameters(),lr=.001,betas=(.9,.99),weight_decay=1e-4)
        check=dict(stress=stress_probe(model,opt),edge=probe(model,None,a,b,'frozen_isoluminant_edge',False))
        screen=guard(check)
        if not screen['passed']:
            raise AssertionError(f'fresh V4 {candidate} fails numerical probe')
        result['replays'].append(dict(candidate=candidate,step=0,sentinels=check,researchGuard=screen,
                                     note='untrained random model: finite is not reconstruction qualification'))
    output=Path(output);output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(serializable(result),indent=2,allow_nan=False),encoding='utf-8')
    print(str(output.resolve()),flush=True)


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--checkpoint-root',type=Path,default=Path('build/bnc-neural-v2/v3-phase-b'))
    parser.add_argument('--archive',type=Path,default=ARCHIVE)
    parser.add_argument('--out',type=Path,default=Path('build/bnc-neural-v2/v4-verification/replay.json'))
    parser.add_argument('--device',choices=('cpu','cuda'),default='cpu')
    args=parser.parse_args()
    verify(args.checkpoint_root,args.archive,args.out,args.device)


if __name__=='__main__':
    main()
